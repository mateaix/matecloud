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

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolMcpBridgeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolCallbackConverterAutoConfiguration.class))
            .withPropertyValues(
                    "spring.ai.mcp.server.enabled=true",
                    "spring.ai.mcp.server.type=SYNC",
                    "spring.ai.mcp.server.tool-callback-converter=true")
            .withBean(AiToolRegistry.class, () -> {
                AiToolRegistry registry = new AiToolRegistry();
                registry.postProcessAfterInitialization(new McpTools(), "mcpTools");
                return registry;
            });

    @Test
    void convertsRegistryCallbacksToMcpToolSpecifications() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            @SuppressWarnings("unchecked")
            List<McpServerFeatures.SyncToolSpecification> tools =
                    (List<McpServerFeatures.SyncToolSpecification>) context.getBean("syncTools", List.class);
            assertThat(tools).singleElement()
                    .satisfies(tool -> assertThat(tool.tool().name()).isEqualTo("mcpEcho"));
        });
    }

    static class McpTools {

        @Tool(name = "mcpEcho", description = "Echo a value over MCP")
        String echo(String value) {
            return value;
        }
    }
}
