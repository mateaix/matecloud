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
package vip.mate.starter.ai.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import vip.mate.starter.ai.config.AiProperties;
import vip.mate.starter.ai.tool.AiToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring-AI-2.0-powered chat orchestrator.
 * <p>
 * One {@link ChatClient} is built per instance with the full advisor chain:
 * <ul>
 *   <li>{@link MessageChatMemoryAdvisor} — multi-turn conversation memory</li>
 *   <li>{@link SimpleLoggerAdvisor} — log every prompt and response</li>
 *   <li>{@link SafeGuardAdvisor} — optional sensitive-word block</li>
 * </ul>
 * Every bean method annotated with {@code @Tool} is wired in via the registry.
 * Supports both sync ({@link #chat}) and streaming ({@link #stream}) calls.
 *
 * @author mateaix
 */
@Slf4j
public class AiChatService {

    private final ChatClient chatClient;
    private final AiProperties properties;

    public AiChatService(ChatClient.Builder chatClientBuilder,
                         AiToolRegistry toolRegistry,
                         ChatMemory chatMemory,
                         AiProperties properties) {
        this.properties = properties;
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        if (properties.isAdvisorLoggingEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }
        if (properties.getSafeGuardWords() != null && !properties.getSafeGuardWords().isEmpty()) {
            advisors.add(SafeGuardAdvisor.builder()
                    .sensitiveWords(properties.getSafeGuardWords())
                    .build());
        }

        this.chatClient = chatClientBuilder
                .defaultSystem(properties.getSystemPrompt())
                .defaultAdvisors(advisors.toArray(new Advisor[0]))
                .defaultTools(toolRegistry)
                .build();
    }

    /**
     * One conversation turn (blocking). Uses {@code conversationId} so history
     * is preserved across calls.
     */
    public String chat(String conversationId, String systemPrompt, String userMessage) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, resolveConversationId(conversationId)));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }
        return spec.user(userMessage).call().content();
    }

    /**
     * Streaming conversation turn. Emits content fragments as the model
     * generates them. Backed by Spring AI's reactive {@code .stream()}.
     */
    public Flux<String> stream(String conversationId, String systemPrompt, String userMessage) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, resolveConversationId(conversationId)));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }
        return spec.user(userMessage).stream().content();
    }

    /**
     * Structured output: ask Claude a question and coerce the reply into a
     * Java type via Spring AI's structured-output parser.
     */
    public <T> T entity(String conversationId, String systemPrompt, String userMessage, Class<T> type) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, resolveConversationId(conversationId)));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }
        return spec.user(userMessage).call().entity(type);
    }

    private String resolveConversationId(String cid) {
        return (cid == null || cid.isBlank()) ? properties.getDefaultConversationId() : cid;
    }
}
