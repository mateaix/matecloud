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
package vip.mate.starter.tenant;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import vip.mate.base.trace.MateThreadContext;
import vip.mate.starter.tenant.async.TenantContextTaskDecorator;
import vip.mate.starter.tenant.cache.TenantCacheKeyGenerator;
import vip.mate.starter.tenant.core.TenantContext;
import vip.mate.starter.tenant.core.TenantProperties;
import vip.mate.starter.tenant.core.TenantRuntime;
import vip.mate.starter.tenant.datasource.TenantDataSourceWebFilter;
import vip.mate.starter.tenant.mybatis.MateTenantLineHandler;
import vip.mate.starter.tenant.resolver.DomainTenantResolver;
import vip.mate.starter.tenant.resolver.HeaderTenantResolver;
import vip.mate.starter.tenant.resolver.TenantResolver;
import vip.mate.starter.tenant.resolver.TokenTenantResolver;
import vip.mate.starter.tenant.web.TenantGatewayFilter;
import vip.mate.starter.tenant.web.TenantWebFilter;

/**
 * Auto-configuration for multi-tenant support.
 *
 * <p>Activated by {@code mate.tenant.enabled=true}. The row-level interceptor
 * is exposed as an ordered {@code InnerInterceptor} bean so that
 * {@code mate-ds-starter} collects it into the single MyBatis-Plus interceptor
 * chain ahead of pagination.
 *
 * @author mateaix
 */
@AutoConfiguration(after = TenantPropertiesAutoConfiguration.class)
@ConditionalOnProperty(prefix = "mate.tenant", name = "enabled", havingValue = "true")
public class TenantAutoConfiguration {

    /**
     * Row-level tenant interceptor. Ordered before the data-permission
     * interceptor (and well before pagination, which ds-starter always adds
     * last).
     *
     * <p>Contract: {@link TenantLineHandler#getTenantId()} is only invoked for
     * tables that {@link TenantLineHandler#ignoreTable(String)} did NOT ignore,
     * and we only decline to ignore when a concrete tenant context exists — so
     * getTenantId never produces {@code tenant_id = NULL}.
     */
    /** Expose properties to the Dubbo SPI filters (not Spring-managed). */
    @Bean
    public InitializingBean tenantRuntimeInitializer(
            TenantProperties properties) {
        return () -> {
            TenantRuntime.set(properties);
            // 把租户上下文透传器登记到统一登记处, 让 @Async/线程池任务同时带上租户 + MDC(traceId)。
            // 复用既有、已测的 TenantContextTaskDecorator 逻辑, 行为不变。
            MateThreadContext.register(new TenantContextTaskDecorator()::decorate);
        };
    }

    @Bean
    @Order(10)
    @ConditionalOnClass(name = "com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor")
    public TenantLineInnerInterceptor tenantLineInnerInterceptor(TenantProperties properties) {
        return new TenantLineInnerInterceptor(new MateTenantLineHandler(properties));
    }

    @Bean
    @ConditionalOnMissingBean(TenantResolver.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public TenantResolver tenantResolver(TenantProperties properties) {
        return switch (properties.getResolver()) {
            case "domain" -> new DomainTenantResolver();
            case "token" -> new TokenTenantResolver();
            default -> new HeaderTenantResolver(properties);
        };
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<TenantWebFilter> tenantWebFilter(
            TenantResolver tenantResolver, TenantProperties properties) {
        FilterRegistrationBean<TenantWebFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TenantWebFilter(tenantResolver, properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }

    /**
     * Per-request datasource routing (SCHEMA / DATASOURCE modes). Registered
     * only when baomidou dynamic-datasource is enabled. Runs right after
     * {@link TenantWebFilter} (which populates {@link TenantContext}).
     */
    @Bean
    @ConditionalOnClass(name = "com.baomidou.dynamic.datasource.DynamicRoutingDataSource")
    @ConditionalOnProperty(prefix = "spring.datasource.dynamic", name = "enabled", havingValue = "true")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<TenantDataSourceWebFilter> tenantDataSourceWebFilter(
            TenantProperties properties) {
        FilterRegistrationBean<TenantDataSourceWebFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TenantDataSourceWebFilter(properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 6);
        return registration;
    }

    /**
     * Gateway-side filter. Resolves the tenant up front and forwards it as a
     * header so downstream servlet services pick it up via their web filter.
     * Only wires up in a reactive (gateway) application.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.cloud.gateway.filter.GlobalFilter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class GatewayConfiguration {
        @Bean
        @ConditionalOnMissingBean(TenantGatewayFilter.class)
        TenantGatewayFilter tenantGatewayFilter(TenantProperties properties) {
            return new TenantGatewayFilter(properties);
        }
    }

    @Bean("tenantCacheKeyGenerator")
    public KeyGenerator tenantCacheKeyGenerator() {
        return new TenantCacheKeyGenerator();
    }

    /**
     * Propagate the tenant context across {@code @Async} / {@code TaskExecutor}
     * boundaries by installing a {@link TenantContextTaskDecorator} on every
     * {@link ThreadPoolTaskExecutor} bean (including Spring Boot's
     * auto-configured {@code applicationTaskExecutor}). Without this, async work
     * runs with no tenant and the fail-closed interceptor rejects it.
     *
     * <p>Implemented as a {@link BeanPostProcessor} (rather than a
     * version-specific executor customizer) so it stays stable across Boot
     * releases. It overwrites any decorator the application may have set — only
     * relevant when {@code mate.tenant.enabled=true}.
     */
    @Bean
    public static BeanPostProcessor tenantTaskExecutorDecoratorPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof ThreadPoolTaskExecutor executor) {
                    // 统一装饰器: 同时透传租户 + MDC(traceId)。指向同一登记处, 与 monitor 端的
                    // 同名设置互相覆盖也等价, 不再冲突。
                    executor.setTaskDecorator(MateThreadContext::decorate);
                }
                return bean;
            }
        };
    }
}
