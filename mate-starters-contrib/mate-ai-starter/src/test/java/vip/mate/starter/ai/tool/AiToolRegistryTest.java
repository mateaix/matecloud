/*
 * Copyright (c) 2024-2026 Beijing Daotiandi Technology Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package vip.mate.starter.ai.tool;

import org.junit.jupiter.api.Test;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.aop.framework.ProxyFactory;
import reactor.core.publisher.Flux;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiToolRegistryTest {

    @Test
    void preservesToolMetadataAndResultConverter() {
        AiToolRegistry registry = new AiToolRegistry();

        registry.postProcessAfterInitialization(new MetadataTools(), "metadataTools");

        var callback = registry.get("formatValue");
        assertThat(callback).isNotNull();
        assertThat(callback.getToolMetadata().returnDirect()).isTrue();
        assertThat(callback.call("{\"value\":\"mate\"}")).isEqualTo("converted:mate");
        assertThat(callback.getToolDefinition().inputSchema()).contains("value");
    }

    @Test
    void rejectsDuplicateToolNamesInsteadOfSilentlyChoosingOne() {
        AiToolRegistry registry = new AiToolRegistry();
        registry.postProcessAfterInitialization(new FirstDuplicateTool(), "firstTool");

        assertThatThrownBy(() ->
                registry.postProcessAfterInitialization(new SecondDuplicateTool(), "secondTool"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate AI tool name 'duplicateTool'");
    }

    @Test
    void discoversAndInvokesToolsBehindJdkProxy() {
        AtomicInteger adviceInvocations = new AtomicInteger();
        ProxyFactory proxyFactory = new ProxyFactory(new ProxiedTools());
        proxyFactory.setInterfaces(ProxiedToolContract.class);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            adviceInvocations.incrementAndGet();
            return invocation.proceed();
        });
        Object proxy = proxyFactory.getProxy();
        AiToolRegistry registry = new AiToolRegistry();

        registry.postProcessAfterInitialization(proxy, "proxiedTools");

        assertThat(registry.get("proxiedEcho").call("{\"value\":\"mate\"}"))
                .isEqualTo("\"proxy:mate\"");
        assertThat(adviceInvocations).hasValue(1);
    }

    @Test
    void defaultToolsExecutesRegisteredCallbackThroughToolCallingAdvisor() {
        AiToolRegistry registry = new AiToolRegistry();
        LoopTools tools = new LoopTools();
        registry.postProcessAfterInitialization(tools, "loopTools");
        ToolCallingChatModel model = new ToolCallingChatModel();

        String content = ChatClient.builder(model)
                .defaultTools(registry)
                .build()
                .prompt("Use the echo tool")
                .call()
                .content();

        assertThat(content).isEqualTo("tool completed");
        assertThat(tools.invocations).hasValue(1);
        assertThat(model.calls).hasValue(2);
        assertThat(model.receivedToolResponse).isTrue();
    }

    @Test
    void streamingAlsoExecutesRegisteredCallbackThroughToolCallingAdvisor() {
        AiToolRegistry registry = new AiToolRegistry();
        LoopTools tools = new LoopTools();
        registry.postProcessAfterInitialization(tools, "loopTools");
        ToolCallingChatModel model = new ToolCallingChatModel();

        List<String> content = ChatClient.builder(model)
                .defaultTools(registry)
                .build()
                .prompt("Use the echo tool")
                .stream()
                .content()
                .collectList()
                .block();

        assertThat(content).containsExactly("tool completed");
        assertThat(tools.invocations).hasValue(1);
        assertThat(model.calls).hasValue(2);
        assertThat(model.receivedToolResponse).isTrue();
    }

    static class MetadataTools {

        @Tool(name = "formatValue", description = "Format a value", returnDirect = true,
                resultConverter = PrefixResultConverter.class)
        String format(@ToolParam(description = "Value to format") String value) {
            return value;
        }
    }

    public static class PrefixResultConverter implements ToolCallResultConverter {

        @Override
        public String convert(Object result, Type returnType) {
            return "converted:" + result;
        }
    }

    static class FirstDuplicateTool {

        @Tool(name = "duplicateTool", description = "First duplicate")
        String first() {
            return "first";
        }
    }

    static class SecondDuplicateTool {

        @Tool(name = "duplicateTool", description = "Second duplicate")
        String second() {
            return "second";
        }
    }

    static class LoopTools {

        private final AtomicInteger invocations = new AtomicInteger();

        @Tool(name = "echo", description = "Echo a value")
        String echo(@ToolParam(description = "Value to echo") String value) {
            invocations.incrementAndGet();
            return value;
        }
    }

    interface ProxiedToolContract {

        String echo(String value);
    }

    static class ProxiedTools implements ProxiedToolContract {

        @Override
        @Tool(name = "proxiedEcho", description = "Echo through an AOP proxy")
        public String echo(@ToolParam(description = "Value to echo") String value) {
            return "proxy:" + value;
        }
    }

    static class ToolCallingChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private boolean receivedToolResponse;

        @Override
        public ChatResponse call(Prompt prompt) {
            if (calls.getAndIncrement() == 0) {
                var toolCall = new AssistantMessage.ToolCall(
                        "call-1", "function", "echo", "{\"value\":\"mate\"}");
                var assistant = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build();
                return new ChatResponse(List.of(new Generation(assistant)));
            }
            receivedToolResponse = prompt.getInstructions().stream()
                    .anyMatch(ToolResponseMessage.class::isInstance);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("tool completed"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
