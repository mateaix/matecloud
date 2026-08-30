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
package vip.mate.starter.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Scans every Spring bean for methods annotated with Spring AI's
 * {@link Tool @Tool} and builds a central registry.
 * <p>
 * The registry doubles as a {@link ToolCallbackProvider} so it can be passed
 * directly to {@code ChatClient.builder(...).defaultTools(...)}. Each
 * registered tool is also exposed through our REST endpoint
 * ({@code /api/v1/ai/tools}) for inspection and direct invocation.
 *
 * @author mateaix
 */
@Slf4j
public class AiToolRegistry implements BeanPostProcessor, ToolCallbackProvider {

    private final Map<String, ToolCallback> callbacks = new ConcurrentSkipListMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        boolean hasToolMethods = Arrays.stream(ReflectionUtils.getDeclaredMethods(targetClass))
                .anyMatch(method -> AnnotationUtils.findAnnotation(method, Tool.class) != null);
        if (!hasToolMethods) {
            return bean;
        }

        ToolCallback[] discovered = AopUtils.isJdkDynamicProxy(bean)
                ? callbacksForJdkProxy(bean, targetClass)
                : MethodToolCallbackProvider.builder()
                        .toolObjects(bean)
                        .build()
                        .getToolCallbacks();
        for (ToolCallback callback : discovered) {
            register(callback, beanName, targetClass);
        }
        return bean;
    }

    /**
     * Spring AI 2.0.1 discovers annotations on a JDK proxy's target class, but
     * its generated callback retains the target-class method and then invokes
     * it on the proxy. Resolve the interface method for invocation while still
     * deriving the definition, metadata and result converter from the annotated
     * target method. This preserves AOP advice instead of unwrapping the proxy.
     */
    private ToolCallback[] callbacksForJdkProxy(Object proxy, Class<?> targetClass) {
        return Arrays.stream(ReflectionUtils.getDeclaredMethods(targetClass))
                .filter(method -> AnnotationUtils.findAnnotation(method, Tool.class) != null)
                .filter(method -> !isFunctionalType(method.getReturnType()))
                .filter(ReflectionUtils.USER_DECLARED_METHODS::matches)
                .map(method -> MethodToolCallback.builder()
                        .toolDefinition(ToolDefinitions.from(method))
                        .toolMetadata(ToolMetadata.from(method))
                        .toolMethod(AopUtils.selectInvocableMethod(method, proxy.getClass()))
                        .toolObject(proxy)
                        .toolCallResultConverter(ToolUtils.getToolCallResultConverter(method))
                        .build())
                .toArray(ToolCallback[]::new);
    }

    private boolean isFunctionalType(Class<?> returnType) {
        return Function.class.isAssignableFrom(returnType)
                || Supplier.class.isAssignableFrom(returnType)
                || Consumer.class.isAssignableFrom(returnType);
    }

    private void register(ToolCallback callback, String beanName, Class<?> targetClass) {
        String toolName = callback.getToolDefinition().name();
        ToolCallback existing = callbacks.putIfAbsent(toolName, callback);
        if (existing != null) {
            throw new IllegalArgumentException("Duplicate AI tool name '%s' on bean '%s'; already registered by %s"
                    .formatted(toolName, beanName, existing));
        }
        log.info("[mate-ai] Registered tool: {} (bean={}, type={})", toolName,
                beanName, targetClass.getName());
    }

    // ---- ToolCallbackProvider (consumed by ChatClient.builder) ----

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.values().toArray(new ToolCallback[0]);
    }

    // ---- Lookup / listing API ----

    public Collection<ToolCallback> listAll() {
        return Collections.unmodifiableCollection(callbacks.values());
    }

    public ToolCallback get(String name) {
        return callbacks.get(name);
    }

    public int size() {
        return callbacks.size();
    }

    public List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> result = new ArrayList<>(callbacks.size());
        for (ToolCallback cb : callbacks.values()) {
            var def = cb.getToolDefinition();
            result.add(Map.of(
                    "name", def.name(),
                    "description", def.description(),
                    "inputSchema", def.inputSchema()
            ));
        }
        return result;
    }
}
