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
package vip.mate.starter.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level mate-ai-starter toggles. The actual model / API-key settings are
 * provided by Spring AI's own properties ({@code spring.ai.<provider>.*}).
 * Pick the active provider via {@code spring.ai.model.chat=<anthropic|openai|
 * deepseek|ollama>}. ZhipuAI uses the OpenAI-compatible endpoint; MiniMax uses
 * the Anthropic-compatible endpoint.
 *
 * @author mateaix
 */
@Data
@ConfigurationProperties(prefix = "mate.ai")
public class AiProperties {

    /** Global on/off switch. */
    private boolean enabled = true;

    /** Default system prompt when callers omit one. */
    private String systemPrompt =
            "You are MateCloud Assistant, an AI operator embedded in a "
          + "Spring Boot microservice platform. Use available tools when the "
          + "user's question requires live data; otherwise answer concisely.";

    /** Chat memory window: max messages retained per conversation. */
    private int memoryMaxMessages = 20;

    /** Default conversation id when the caller doesn't provide one. */
    private String defaultConversationId = "default";

    /** Whether SimpleLoggerAdvisor should log every prompt/response. */
    private boolean advisorLoggingEnabled = true;

    /** Optional SafeGuardAdvisor sensitive-word list. */
    private List<String> safeGuardWords = new ArrayList<>();
}
