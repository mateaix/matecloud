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
package vip.mate.starter.ai.controller;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import vip.mate.base.result.Result;
import vip.mate.starter.ai.chat.AiChatService;
import vip.mate.starter.ai.config.AiProperties;

/**
 * Conversational AI endpoints powered by Spring AI 2.0.1.
 *
 * <ul>
 *   <li>{@code POST /api/v1/ai/chat}        — blocking chat (Result&lt;String&gt;)</li>
 *   <li>{@code POST /api/v1/ai/chat/stream} — SSE streaming chat (text/event-stream)</li>
 * </ul>
 *
 * Both accept an optional {@code conversationId} so Spring AI's
 * {@code MessageChatMemoryAdvisor} preserves multi-turn history.
 *
 * <pre>
 * POST /api/v1/ai/chat
 * {
 *   "conversationId": "alice-2026-04-12",
 *   "message": "Which users registered today?"
 * }
 * </pre>
 *
 * @author mateaix
 */
@RestController
@RequestMapping("/api/v1/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;
    private final AiProperties properties;

    @Data
    public static class ChatRequest {
        private String conversationId;
        private String system;
        private String message;
    }

    @PostMapping
    public Result<String> chat(@RequestBody ChatRequest request) {
        String sys = resolveSystem(request.getSystem());
        String reply = aiChatService.chat(request.getConversationId(), sys, request.getMessage());
        return Result.ok(reply);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody ChatRequest request) {
        String sys = resolveSystem(request.getSystem());
        return aiChatService.stream(request.getConversationId(), sys, request.getMessage());
    }

    private String resolveSystem(String override) {
        return (override == null || override.isBlank()) ? properties.getSystemPrompt() : override;
    }
}
