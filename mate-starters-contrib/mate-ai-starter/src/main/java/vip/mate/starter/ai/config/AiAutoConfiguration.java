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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vip.mate.starter.ai.chat.AiChatService;
import vip.mate.starter.ai.controller.AiChatController;
import vip.mate.starter.ai.controller.AiToolController;
import vip.mate.starter.ai.tool.AiToolInvoker;
import vip.mate.starter.ai.tool.AiToolRegistry;

/**
 * Auto-configuration for mate-ai-starter (Spring AI 2.0.1 flavor).
 * <p>
 * Spring AI's own auto-configuration provides a {@link ChatClient.Builder}
 * when {@code spring-ai-starter-model-anthropic} is on the classpath. We add:
 * <ul>
 *   <li>{@link AiToolRegistry} bean-post-processor that discovers every
 *       {@code @Tool}-annotated method as a {@code ToolCallback}</li>
 *   <li>{@link ChatMemory} using in-memory repository (swap via Redis/JDBC dep)</li>
 *   <li>{@link AiChatService} with advisor chain (memory + logging + safeguard)</li>
 *   <li>REST controllers at {@code /api/v1/ai/tools} and {@code /api/v1/ai/chat}</li>
 * </ul>
 * MCP server auto-bridge is handled by Spring AI's own
 * {@code spring-ai-starter-mcp-server-webmvc} — just add it to the classpath
 * and enable {@code spring.ai.mcp.server.enabled=true}.
 *
 * @author mateaix
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(AiProperties.class)
@ConditionalOnClass(ChatClient.class)
@ConditionalOnProperty(prefix = "mate.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AiAutoConfiguration implements WebMvcConfigurer {

    @Value("${mate.gateway.internal.secret:}")
    private String gatewaySecret;
    @Value("${mate.gateway.internal.signature-required:true}")
    private boolean signatureRequired;
    @Value("${mate.gateway.internal.timestamp-skew-ms:300000}")
    private long timestampSkewMs;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (signatureRequired && (gatewaySecret == null || gatewaySecret.isBlank())) {
            log.error("[ai][security] 网关签名校验已开启但 mate.gateway.internal.secret 未配置, "
                    + "所有 /api/v1/ai/** 请求都将被拒绝; 请配置 MATE_GATEWAY_INTERNAL_SECRET 与网关一致");
        }
        registry.addInterceptor(new AiAuthInterceptor(gatewaySecret, timestampSkewMs, signatureRequired))
                .addPathPatterns("/api/v1/ai/**");
    }


    /**
     * {@code static} because {@link AiToolRegistry} is a {@code BeanPostProcessor}:
     * a static factory method lets Spring instantiate it early without forcing this
     * whole {@code @Configuration} to initialise first (silences the
     * "not eligible for getting processed by all BeanPostProcessors" warning).
     */
    @Bean
    @ConditionalOnMissingBean
    public static AiToolRegistry aiToolRegistry() {
        log.info("[mate-ai] AI tool registry activated (Spring AI @Tool + ToolCallbackProvider)");
        return new AiToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public AiToolInvoker aiToolInvoker(AiToolRegistry registry, ObjectMapper objectMapper) {
        return new AiToolInvoker(registry, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatMemory chatMemory(AiProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(properties.getMemoryMaxMessages())
                .build();
    }

    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    @ConditionalOnMissingBean
    public AiChatService aiChatService(ChatClient.Builder chatClientBuilder,
                                       AiToolRegistry registry,
                                       ChatMemory chatMemory,
                                       AiProperties properties) {
        return new AiChatService(chatClientBuilder, registry, chatMemory, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiToolController aiToolController(AiToolRegistry registry, AiToolInvoker invoker) {
        return new AiToolController(registry, invoker);
    }

    @Bean
    @ConditionalOnBean(AiChatService.class)
    @ConditionalOnMissingBean
    public AiChatController aiChatController(AiChatService service, AiProperties properties) {
        return new AiChatController(service, properties);
    }
}
