/*
 * Copyright (c) 2024-2026 Beijing Daotiandi Technology Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package vip.mate.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import vip.mate.cli.config.CliConfig;
import vip.mate.cli.http.JsonHttpClient;
import vip.mate.cli.nacos.NacosClient;
import vip.mate.cli.render.Ansi;
import vip.mate.cli.render.Spinner;
import vip.mate.cli.render.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code mate ai tools|chat|providers}
 * <p>
 * Talks to the AI endpoints (/api/v1/ai/chat, /api/v1/ai/tools) exposed by
 * any service that includes mate-ai-starter. Services are discovered from
 * Nacos when possible; if none is reachable, falls back to the admin base URL
 * (env {@code MATE_ADMIN_URL}).
 */
@Command(name = "ai", description = "AI tool inspection and conversational chat",
        subcommands = {AiCommand.ToolsSub.class, AiCommand.ChatSub.class, AiCommand.ProvidersSub.class,
                AiCommand.SessionsSub.class, AiCommand.ShowSub.class, AiCommand.ForkSub.class})
public class AiCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("Usage: mate ai <tools|chat|providers|sessions|show|fork>");
    }

    /** Aggregates /api/v1/ai/tools across every Nacos-registered service. */
    @Command(name = "tools", description = "List every @Tool exposed by AI-enabled services")
    public static class ToolsSub implements Runnable {
        @Override
        public void run() {
            JsonHttpClient http = new JsonHttpClient();
            List<String> endpoints = discoverAiEndpoints();
            if (endpoints.isEmpty()) {
                System.out.println("(no AI-enabled services found)");
                return;
            }
            Table table = Table.of("TOOL", "SERVICE", "DESCRIPTION").maxWidth(60);
            for (String ep : endpoints) {
                try {
                    Map<String, Object> resp = http.get(ep + "/api/v1/ai/tools");
                    Object data = resp.get("data");
                    if (!(data instanceof List<?> list)) continue;
                    String svcLabel = labelFor(ep);
                    for (Object t : list) {
                        if (!(t instanceof Map<?, ?> m)) continue;
                        table.row(m.get("name"), svcLabel, m.get("description"));
                    }
                } catch (Exception e) {
                    System.err.println(Ansi.warn("[warn] ") + ep + " — " + e.getMessage());
                }
            }
            table.styler((col, raw, padded) -> col == 0 ? Ansi.cyan(padded) : padded)
                    .print(System.out);
        }
    }

    /** {@code mate ai chat "What users signed up today?"} */
    @Command(name = "chat", description = "Ask the AI; it will pick + call @Tool methods automatically")
    public static class ChatSub implements Runnable {
        /** Streaming first-token watchdog + retry tuning. */
        private static final long FIRST_TOKEN_TIMEOUT_MS = 30_000L;
        private static final int STREAM_RETRIES = 2;
        private static final long RETRY_BACKOFF_MS = 1_500L;

        @Parameters(index = "0..*", description = "Your question")
        List<String> message;

        @Option(names = "--service", description = "Target service (default: admin)")
        String service;

        @Option(names = {"--conversation", "--resume"},
                description = "Continue an existing conversation by id (see 'mate ai sessions')")
        String conversationId;

        @Option(names = "--new", description = "Force a brand-new conversation (ignore any default id)")
        boolean forceNew;

        @Override
        public void run() {
            if (message == null || message.isEmpty()) {
                System.err.println(Ansi.fail("Missing message. Usage: mate ai chat \"your question\" [--resume <id>]"));
                return;
            }
            String endpoint = resolveEndpoint(service);
            String question = String.join(" ", message);
            String sessionId = (conversationId != null && !forceNew)
                    ? conversationId : AiSessionStore.newId();
            AiSessionStore.Session session = AiSessionStore.loadOrCreate(sessionId);
            if (conversationId != null && !forceNew && AiSessionStore.load(sessionId) != null) {
                System.out.println(Ansi.muted("↻ resuming " + sessionId
                        + " (" + session.turns.size() / 2 + " prior turns)"));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", question);
            body.put("conversationId", sessionId);

            JsonHttpClient http = new JsonHttpClient();
            Spinner spinner = new Spinner("thinking");
            boolean[] firstToken = {false};
            StringBuilder reply = new StringBuilder();
            spinner.start();
            try {
                // Try streaming first (SSE), with one retry if no token arrives in time.
                boolean streamed = false;
                for (int attempt = 1; attempt <= STREAM_RETRIES && !streamed; attempt++) {
                    streamed = http.postStream(endpoint + "/api/v1/ai/chat/stream", body, token -> {
                        if (!firstToken[0]) {
                            spinner.stop();
                            firstToken[0] = true;
                        }
                        System.out.print(token);
                        System.out.flush();
                        reply.append(token);
                    }, FIRST_TOKEN_TIMEOUT_MS);
                    if (!streamed && attempt < STREAM_RETRIES) {
                        try {
                            Thread.sleep(RETRY_BACKOFF_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (!streamed) {
                    // Streaming unavailable (older service / timeout) → blocking fallback.
                    spinner.stop();
                    Map<String, Object> resp = http.postJson(endpoint + "/api/v1/ai/chat", body);
                    Object data = resp.get("data");
                    reply.append(data == null ? "" : data);
                    System.out.println(data == null ? Ansi.muted("(empty response)") : data);
                } else {
                    System.out.println();
                }
                AiSessionStore.record(session, question, reply.toString());
                System.out.println(Ansi.muted("session: " + sessionId + "   (resume: mate ai chat --resume "
                        + sessionId + " \"...\")"));
            } catch (Exception e) {
                spinner.stop();
                if (firstToken[0]) {
                    System.out.println();
                }
                System.err.println(Ansi.fail("Chat failed: ") + e.getMessage());
            }
        }
    }

    /** {@code mate ai sessions} — list saved conversations. */
    @Command(name = "sessions", description = "List saved chat sessions (resume with 'ai chat --resume <id>')")
    public static class SessionsSub implements Runnable {
        @Override
        public void run() {
            List<AiSessionStore.Session> sessions = AiSessionStore.list();
            if (sessions.isEmpty()) {
                System.out.println(Ansi.muted("(no saved sessions — start one with: mate ai chat \"...\")"));
                return;
            }
            Table table = Table.of("ID", "TITLE", "TURNS", "UPDATED").maxWidth(48);
            for (AiSessionStore.Session s : sessions) {
                table.row(s.id, s.title == null ? "" : s.title, s.turns.size() / 2,
                        s.updated == null ? "" : s.updated);
            }
            table.styler((c, raw, p) -> c == 0 ? Ansi.cyan(p) : p).print(System.out);
        }
    }

    /** {@code mate ai show <id>} — print a saved transcript. */
    @Command(name = "show", description = "Print the transcript of a saved chat session")
    public static class ShowSub implements Runnable {
        @Parameters(index = "0", description = "Session id (see 'mate ai sessions')")
        String id;

        @Override
        public void run() {
            AiSessionStore.Session s = AiSessionStore.load(id);
            if (s == null) {
                System.err.println(Ansi.fail("No such session: ") + id);
                return;
            }
            System.out.println(Ansi.heading("Session ") + s.id
                    + Ansi.muted("   " + (s.title == null ? "" : s.title)));
            System.out.println(Ansi.muted("created " + s.created + " · updated " + s.updated));
            System.out.println();
            for (AiSessionStore.Turn t : s.turns) {
                boolean user = "user".equals(t.role);
                System.out.println((user ? Ansi.cyan("你 ❯ ") : Ansi.green("AI ❯ ")) + t.text);
                System.out.println();
            }
        }
    }

    /** {@code mate ai fork <id> [newId]} — branch a session's transcript locally. */
    @Command(name = "fork", description = "Fork a saved session's transcript into a new local id")
    public static class ForkSub implements Runnable {
        @Parameters(index = "0", description = "Source session id")
        String id;

        @Parameters(index = "1", arity = "0..1", description = "New session id (optional; auto-generated)")
        String newId;

        @Override
        public void run() {
            String target = (newId == null || newId.isBlank()) ? AiSessionStore.newId() : newId;
            AiSessionStore.Session forked = AiSessionStore.fork(id, target);
            if (forked == null) {
                System.err.println(Ansi.fail("No such session: ") + id);
                return;
            }
            System.out.println(Ansi.ok("Forked ") + id + Ansi.muted(" → ") + Ansi.cyan(target));
            System.out.println(Ansi.muted("Note: local transcript only — server-side memory is not copied, so the "
                    + "new id starts fresh. Continue with: mate ai chat --resume " + target + " \"...\""));
        }
    }

    @Command(name = "providers", description = "List supported LLM providers")
    public static class ProvidersSub implements Runnable {
        @Override
        public void run() {
            System.out.println("Supported providers (set MATE_AI_PROVIDER or spring.ai.model.chat):");
            System.out.println();
            System.out.println("  anthropic   Claude Opus 4.8 / Sonnet 4.6 / Haiku 4.5      (default)");
            System.out.println("  openai      GPT-5.5 / 5.5-Pro / 5.3-Codex  [also: KIMI and Zhipu GLM via OPENAI_BASE_URL]");
            System.out.println("  deepseek    deepseek-v4 / deepseek-v4-flash / deepseek-r1");
            System.out.println("  ollama      Self-hosted (qwen2.5, llama3.x, etc.)");
            System.out.println();
            System.out.println("Compatibility endpoints:");
            System.out.println("  Zhipu GLM   MATE_AI_PROVIDER=openai, OPENAI_BASE_URL=https://open.bigmodel.cn/api/paas/v4");
            System.out.println("  MiniMax     MATE_AI_PROVIDER=anthropic, ANTHROPIC_BASE_URL=https://api.minimax.io/anthropic");
            System.out.println();
            System.out.println("API keys: ANTHROPIC_API_KEY / OPENAI_API_KEY / DEEPSEEK_API_KEY");
        }
    }

    // ---- Shared helpers ----

    static List<String> discoverAiEndpoints() {
        List<String> endpoints = new ArrayList<>();
        try {
            NacosClient nacos = new NacosClient();
            for (String svc : nacos.listServices()) {
                for (Map<String, Object> inst : nacos.listInstances(svc)) {
                    endpoints.add("http://" + inst.get("ip") + ":" + inst.get("port"));
                }
            }
        } catch (Exception ignored) {
            // Fall back to the admin URL
        }
        if (endpoints.isEmpty()) {
            endpoints.add(CliConfig.adminBaseUrl());
        }
        return endpoints;
    }

    static String resolveEndpoint(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return CliConfig.adminBaseUrl();
        }
        try {
            NacosClient nacos = new NacosClient();
            List<Map<String, Object>> instances = nacos.listInstances(serviceName);
            if (!instances.isEmpty()) {
                Map<String, Object> inst = instances.get(0);
                return "http://" + inst.get("ip") + ":" + inst.get("port");
            }
        } catch (Exception ignored) {
        }
        return CliConfig.adminBaseUrl();
    }

    static String labelFor(String endpoint) {
        int colon = endpoint.lastIndexOf(':');
        return colon > 0 ? endpoint.substring(colon + 1) : endpoint;
    }
}
