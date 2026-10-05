# RFC-012: Multi-Tenant Architecture

- **Status**: COLUMN mode implemented (2026-05-25); business onboarding closed-loop (2026-06-03); SCHEMA / DATASOURCE planned
- **Created**: 2026-04-12
- **Author**: MateCloud Team
- **Wave**: 4
- **Dependencies**: RFC-003 (mate-ds-starter), RFC-004 (mate-cache-starter)

## Overview

This RFC provides the complete implementation specification for `mate-tenant-starter` and the tenant-management domain for tenant CRUD. It covers tenant context propagation, MyBatis Plus row-level isolation, Dubbo RPC passthrough, cache key isolation, gateway filtering, and tenant CRUD management.

> **Implementation status (2026-05-25)** — the COLUMN (row-level) isolation
> path is fully wired and functional. Key facts that differ from the original
> draft below:
> - Tenant management lives in **`vip.mate.system.tenant.*`** (mate-system),
>   not `vip.mate.admin.domain.tenant.*`. mate-admin is folded into mate-system
>   as the `vip.mate.system.admin` sub-package.
> - `TenantContext` uses a plain **ThreadLocal** (not `ScopedValue`, which is
>   still preview on JDK 21). See §1.3.
> - The row-level interceptor is exposed as an ordered `InnerInterceptor` bean
>   and **collected by `mate-ds-starter`** into the single MyBatis-Plus
>   interceptor chain. This is the load-bearing contract — see §1.13 / §1.13a.
> - Table selection is a **whitelist** (`include-tables`), not a blacklist.
> - A **super tenant** (`super-tenant-id`, default empty = disabled) and a
>   programmatic bypass utility (`TenantHelper`, §1.17) were added.
> - SCHEMA and DATASOURCE routing is implemented (static config); provisioning
>   is deferred — see Part 5.
> - **Security hardening (review follow-up)**: tenant ids are validated at the
>   trust boundary (§1.19), and the row-level filter is **fail-closed** — a
>   tenant-scoped table accessed with no/invalid context throws rather than
>   reading all tenants. Legitimate cross-tenant access (bootstrap, auth-by-
>   username RPC, tenant management) must opt in via `TenantHelper.withoutTenant`
>   (§1.13b).

> **Update (2026-06-03) — onboarding closed-loop & async/quota follow-ups.**
> The COLUMN path was extended from "isolation only" to a usable end-to-end loop:
> - **`mate_admin` tenant wiring completed.** `AdminPO`/`Admin` now carry
>   `tenantId`; `AdminQueryServiceImpl.toAuthResponse` and `SaTokenIssuer`
>   propagate it into the Sa-Token session, so the `token` resolver scopes every
>   downstream request. (Previously the column existed but was never read, so all
>   admins silently fell back to the system tenant.) `mate_role` got the same
>   treatment via `RolePO`.
> - **Tenant provisioning.** `TenantCommandService.create()` now invokes
>   `TenantProvisionService` (runs inside `TenantHelper.runAsTenant`) to create a
>   tenant-scoped role + admin + default menu grants and publish a
>   `TenantProvisionedEvent` — a freshly created tenant is immediately loginable.
> - **Expiration lifecycle.** `TenantAggregate.markExpiredIfDue/isExpired/
>   inGracePeriod` + an opt-in hourly `@Scheduled` `TenantLifecycleJob`
>   (guarded by `mate.tenant.enabled=true`) flip ACTIVE→EXPIRED past due.
> - **Async propagation.** `TenantContextTaskDecorator` is installed on every
>   `ThreadPoolTaskExecutor` so the tenant context survives `@Async` hops.
> - **Quota enforcement (RFC-028 slice).** Package quota columns
>   (`ai_enabled`, `ai_quota_daily`, `max_apps`, `price`) + `TenantQuotaService`
>   (`assertCanAddUser`/`assertActiveTenant`/`assertFeature`/`recordAndCheckAiCall`)
>   + a servlet `TenantStatusInterceptor` that blocks frozen / past-grace tenants.
> - **Demo data.** Migration `V1.3.1` seeds tenants `acme`/`globex` (admins,
>   roles, menus, users); profile `application-tenant.yml` turns the feature on
>   for a one-command isolation demo. See `docs/conventions/multi-tenant-guide.md`.
> - **Known constraint:** usernames / mobiles / role keys remain **globally**
>   unique (not per-tenant), because auth-by-username runs cross-tenant. Per-tenant
>   uniqueness requires resolving the tenant *before* auth (domain/header) — future.

---

## Part 1: mate-tenant-starter

Package: `vip.mate.starter.tenant`

### 1.1 pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>vip.mate</groupId>
        <artifactId>mate-starters</artifactId>
        <version>1.0.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>mate-tenant-starter</artifactId>
    <packaging>jar</packaging>
    <name>mate-tenant-starter</name>
    <description>Multi-tenant support starter - row-level isolation, context propagation, cache isolation</description>

    <dependencies>
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mate-base</artifactId>
        </dependency>
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mate-ds-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mate-cache-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mate-sa-token-starter</artifactId>
            <exclusions>
                <exclusion>
                    <groupId>cn.dev33</groupId>
                    <artifactId>sa-token-reactor-spring-boot3-starter</artifactId>
                </exclusion>
            </exclusions>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>
        <!-- Servlet API for TenantWebFilter (optional, non-reactive services) -->
        <dependency>
            <groupId>jakarta.servlet</groupId>
            <artifactId>jakarta.servlet-api</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- WebFlux for TenantGatewayFilter (optional, gateway only) -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-webflux</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-gateway-server</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- Dubbo for tenant propagation filters (optional) -->
        <dependency>
            <groupId>org.apache.dubbo</groupId>
            <artifactId>dubbo</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

</project>
```

### 1.2 TenantProperties.java

```java
package vip.mate.starter.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for multi-tenant support.
 */
@Data
@ConfigurationProperties(prefix = "mate.tenant")
public class TenantProperties {

    /** Master switch. When false the whole tenant chain is inert. */
    private boolean enabled = false;

    /** Isolation strategy. Only COLUMN is implemented today. */
    private MultiTenantType type = MultiTenantType.COLUMN;

    /** Tenant column name used by the row-level interceptor. */
    private String column = "tenant_id";

    /**
     * Whitelist of tables that carry the tenant column. ONLY these tables get
     * {@code WHERE tenant_id = ?} appended; every other table is untouched.
     * A whitelist (vs. a blacklist) is the safe default — a table that lacks
     * the column can never be filtered by accident.
     */
    private List<String> includeTables = new ArrayList<>();

    /**
     * Super-tenant id. Requests carrying this tenant see all tenants' data
     * (the row-level filter is skipped). Empty disables the super tenant.
     */
    private String superTenantId = "0";

    /** Request resolver: header | token | domain. */
    private String resolver = "header";

    /** Header carrying the tenant id (header/domain resolvers). */
    private String headerName = "X-Tenant-Id";

    /** URL patterns (Ant-style) that bypass tenant resolution entirely. */
    private List<String> ignoreUrls = new ArrayList<>();
}
```

> **Why a whitelist, not a blacklist** — the original draft used
> `ignore-tables` (filter everything *except* these). With an empty default
> that means "append `tenant_id` to every table", which throws
> *Unknown column 'tenant_id'* on the many tables that don't have it. The
> whitelist inverts this: nothing is filtered unless explicitly listed.

### 1.3 TenantContext.java

```java
package vip.mate.starter.tenant;

/**
 * Tenant context holder. Uses ThreadLocal to propagate the current tenant ID
 * within a thread of execution.
 *
 * <p>Note: we intentionally use ThreadLocal rather than ScopedValue, since
 * ScopedValue is still a preview API in JDK 21 and we do not want to require
 * --enable-preview at compile time.
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_LOCAL = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String getTenantId() {
        return TENANT_LOCAL.get();
    }

    public static void setTenantId(String tenantId) {
        TENANT_LOCAL.set(tenantId);
    }

    public static void clear() {
        TENANT_LOCAL.remove();
    }

    /** Execute a runnable within a tenant context scope (restores previous). */
    public static void runWithTenant(String tenantId, Runnable task) {
        String previous = getTenantId();
        try {
            setTenantId(tenantId);
            task.run();
        } finally {
            if (previous == null) {
                clear();
            } else {
                setTenantId(previous);
            }
        }
    }
}
```

> **Thread-pool / @Async caveat** — a plain ThreadLocal does not cross thread
> boundaries. Tenant context is restored explicitly at each boundary: the
> servlet filter (§1.8), the Dubbo provider filter (§1.10), and — for manual
> hand-offs to executors — `TenantHelper.runAsTenant` (§1.17). A
> `TaskDecorator` for `@Async` is a future enhancement.

### 1.4 TenantResolver.java

```java
package vip.mate.starter.tenant;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Strategy interface for resolving the tenant ID from an incoming request.
 */
public interface TenantResolver {

    /**
     * Resolve tenant ID from the HTTP request.
     * @return tenant ID or null if not resolved
     */
    String resolve(HttpServletRequest request);
}
```

### 1.5 HeaderTenantResolver.java

```java
package vip.mate.starter.tenant;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * Resolves tenant ID from the HTTP header (default: X-Tenant-Id).
 */
@RequiredArgsConstructor
public class HeaderTenantResolver implements TenantResolver {

    private final TenantProperties properties;

    @Override
    public String resolve(HttpServletRequest request) {
        return request.getHeader(properties.getHeaderName());
    }
}
```

### 1.6 DomainTenantResolver.java

```java
package vip.mate.starter.tenant;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves tenant ID from the domain name prefix.
 * e.g. "acme.matecloud.vip" resolves to "acme"
 */
public class DomainTenantResolver implements TenantResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        String host = request.getServerName();
        if (host == null || host.isBlank()) return null;
        // Extract first subdomain segment
        String[] parts = host.split("\\.");
        if (parts.length >= 3) {
            return parts[0];
        }
        return null;
    }
}
```

### 1.7 TokenTenantResolver.java

```java
package vip.mate.starter.tenant;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves tenant ID from the Sa-Token session.
 * Requires the tenant ID to be stored in session during login.
 */
@Slf4j
public class TokenTenantResolver implements TenantResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        try {
            Object tenantId = StpUtil.getSession().get("tenantId");
            return tenantId != null ? tenantId.toString() : null;
        } catch (Exception e) {
            log.debug("Failed to resolve tenant from token: {}", e.getMessage());
            return null;
        }
    }
}
```

### 1.8 TenantWebFilter.java

```java
package vip.mate.starter.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that sets the TenantContext for each incoming request
 * using the configured TenantResolver.
 */
@Slf4j
@RequiredArgsConstructor
public class TenantWebFilter extends OncePerRequestFilter {

    private final TenantResolver tenantResolver;
    private final TenantProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {
        // Skip ignored URLs
        String uri = request.getRequestURI();
        for (String pattern : properties.getIgnoreUrls()) {
            if (pathMatcher.match(pattern, uri)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        try {
            String tenantId = tenantResolver.resolve(request);
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.setTenantId(tenantId);
                log.debug("Tenant context set: {}", tenantId);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
```

### 1.9 TenantDubboConsumerFilter.java

```java
package vip.mate.starter.tenant;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * Dubbo consumer filter that propagates tenant ID to RPC attachments.
 * Registered via Dubbo SPI.
 */
@Activate(group = "consumer", order = -1000)
public class TenantDubboConsumerFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            invocation.setAttachment("tenantId", tenantId);
        }
        return invoker.invoke(invocation);
    }
}
```

### 1.10 TenantDubboProviderFilter.java

```java
package vip.mate.starter.tenant;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * Dubbo provider filter that restores tenant ID from RPC attachments.
 * Registered via Dubbo SPI.
 */
@Activate(group = "provider", order = -1000)
public class TenantDubboProviderFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String tenantId = invocation.getAttachment("tenantId");
        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.setTenantId(tenantId);
        }
        try {
            return invoker.invoke(invocation);
        } finally {
            TenantContext.clear();
        }
    }
}
```

### 1.11 TenantCacheKeyGenerator.java

```java
package vip.mate.starter.tenant;

import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Spring Cache KeyGenerator that prefixes all cache keys with the current tenant ID.
 * Ensures cache isolation between tenants.
 */
public class TenantCacheKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        String tenantId = TenantContext.getTenantId();
        String base = target.getClass().getSimpleName() + ":" + method.getName()
                + ":" + Arrays.deepHashCode(params);
        if (tenantId == null || tenantId.isBlank()) {
            return base;
        }
        return "t:" + tenantId + ":" + base;
    }
}
```

### 1.12 TenantGatewayFilter.java

```java
package vip.mate.starter.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway GlobalFilter that resolves tenant ID and injects it
 * into downstream request headers. For use in mate-gateway (WebFlux).
 */
@Slf4j
@RequiredArgsConstructor
public class TenantGatewayFilter implements GlobalFilter, Ordered {

    private final TenantProperties properties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Check if this path should be ignored
        String path = request.getPath().value();
        for (String pattern : properties.getIgnoreUrls()) {
            if (path.startsWith(pattern)) {
                return chain.filter(exchange);
            }
        }

        String tenantId = resolveTenantId(request);
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Missing tenant ID for request: {}", path);
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return response.setComplete();
        }

        // Inject tenant ID into downstream headers
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(properties.getHeaderName(), tenantId)
                .build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -100; // Before authentication
    }

    private String resolveTenantId(ServerHttpRequest request) {
        // Strategy 1: from header
        String tenantId = request.getHeaders().getFirst(properties.getHeaderName());
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }

        // Strategy 2: from domain prefix
        if ("domain".equals(properties.getResolver())) {
            String host = request.getHeaders().getFirst("Host");
            if (host != null) {
                String[] parts = host.split("\\.");
                if (parts.length >= 3) {
                    return parts[0];
                }
            }
        }

        return null;
    }
}
```

### 1.13 TenantAutoConfiguration.java

```java
package vip.mate.starter.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Auto-configuration for multi-tenant support.
 * Activated when mate.tenant.enabled=true.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mate.tenant", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TenantProperties.class)
public class TenantAutoConfiguration {

    // ==================== MyBatis Plus Tenant Interceptor ====================

    /**
     * Row-level tenant interceptor, exposed as an ordered InnerInterceptor bean
     * so ds-starter collects it ahead of pagination (§1.13a).
     *
     * <p>Contract: getTenantId() is only invoked for tables that ignoreTable()
     * did NOT ignore, and we only decline to ignore when a concrete tenant
     * context exists — so getTenantId never produces {@code tenant_id = NULL}.
     */
    @Bean
    @Order(10)
    @ConditionalOnClass(name = "com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor")
    public TenantLineInnerInterceptor tenantLineInnerInterceptor(TenantProperties properties) {
        return new TenantLineInnerInterceptor(new TenantLineHandler() {

            @Override
            public Expression getTenantId() {
                return new StringValue(TenantContext.getTenantId());
            }

            @Override
            public String getTenantIdColumn() {
                return properties.getColumn();
            }

            @Override
            public boolean ignoreTable(String tableName) {
                if (properties.getType() != MultiTenantType.COLUMN) {
                    return true;
                }
                String tenantId = TenantContext.getTenantId();
                // No tenant context (anonymous / bootstrap / internal job) → don't filter.
                if (tenantId == null || tenantId.isBlank()) {
                    return true;
                }
                // Super tenant sees every tenant's rows.
                String superTenantId = properties.getSuperTenantId();
                if (superTenantId != null && !superTenantId.isBlank()
                        && superTenantId.equals(tenantId)) {
                    return true;
                }
                // Only whitelisted tables carry the tenant column.
                return !properties.getIncludeTables().contains(tableName);
            }
        });
    }

    // ==================== Tenant Resolver ====================

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

    // ==================== Servlet Filter ====================

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

    // ==================== Gateway Filter (WebFlux) ====================

    @Bean
    @ConditionalOnClass(name = "org.springframework.cloud.gateway.filter.GlobalFilter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public TenantGatewayFilter tenantGatewayFilter(TenantProperties properties) {
        return new TenantGatewayFilter(properties);
    }

    // ==================== Cache Key Generator ====================

    @Bean("tenantCacheKeyGenerator")
    public KeyGenerator tenantCacheKeyGenerator() {
        return new TenantCacheKeyGenerator();
    }
}
```

### 1.13a Interceptor wiring contract (mate-ds-starter)

This is the load-bearing detail. A MyBatis-Plus `InnerInterceptor` does **nothing**
unless it is added to the single `MybatisPlusInterceptor` bean. Declaring a
`TenantLineInnerInterceptor` `@Bean` in isolation (as the original draft did)
leaves it dangling — never invoked, so no SQL is ever rewritten.

`mate-ds-starter` owns the one `MybatisPlusInterceptor` and **collects every
contributed `InnerInterceptor` bean** (tenant, data-permission, …), ordered by
`@Order`, inserting them ahead of its own optimistic-lock / block-attack /
pagination interceptors. Pagination must come **last** so the rewritten WHERE
clause is reflected in both the page query and its count query.

```java
@Bean
@ConditionalOnMissingBean
public MybatisPlusInterceptor mybatisPlusInterceptor(
        ObjectProvider<InnerInterceptor> contributedInterceptors) {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

    // Contributed by other starters, ordered by @Order:
    //   TenantLineInnerInterceptor @Order(10)  ← tenant
    //   DataScopeInterceptor       @Order(20)  ← data permission
    contributedInterceptors.orderedStream().forEach(interceptor::addInnerInterceptor);

    interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
    interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

    PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
    pagination.setMaxLimit(500L);
    interceptor.addInnerInterceptor(pagination);   // always last
    return interceptor;
}
```

Resulting chain order: **tenant → data-permission → optimistic-lock →
block-attack → pagination**.

### 1.13b Fail-closed contract & `withoutTenant`

`ignoreTable()` / `getTenantId()` enforce isolation **fail-closed**:

- `ignoreTable` returns true only for: non-COLUMN mode, non-whitelisted tables,
  and the super tenant. It does **not** ignore a whitelisted table just because
  the context is empty.
- `getTenantId` **throws** when the context is blank or malformed. So a
  tenant-scoped table reached with no/invalid tenant aborts the query instead of
  silently returning every tenant's rows.

Legitimate cross-tenant access must therefore opt in explicitly via
`TenantHelper.withoutTenant(...)`, which sets MyBatis-Plus
`InterceptorIgnoreHelper` so the tenant interceptor is skipped entirely (the
handler is never called, so it cannot throw). Audited opt-in sites:

| Site | Why cross-tenant |
|------|------------------|
| `AdminUserSeeder` | Bootstrap, runs before any request/context. |
| `RpcUserServiceImpl` (getUserBy{Id,Username,Mobile}, registerUser) | Authentication resolves a user by a global identifier before a tenant exists. |
| `RpcPermissionServiceImpl` (by adminId / username) | Gateway/auth Sa-Token authorization lookup, pre-tenant. |
| Tenant-management CRUD | Operates on `mate_tenant*` (not whitelisted) — no bypass needed. |

> `RpcDictServiceImpl` and other code touching non-whitelisted tables need no
> bypass — `ignoreTable` already returns true for them.
>
> **Per-tenant usernames** — the auth RPCs currently resolve by global-unique
> username. If per-tenant usernames are introduced, pass the tenant id in the
> RPC contract and scope the provider query instead of `withoutTenant`.

### 1.19 Tenant id validation (trust boundary)

`X-Tenant-Id` (and the subdomain) are client-controllable. JSQLParser
`StringValue` does **not** escape quotes, so an unvalidated id is a SQL-injection
/ bypass vector. `TenantId.isValid` enforces a strict allow-list
(`^[A-Za-z0-9_-]{1,64}$`); ids are opaque generated keys. Enforced at:

- `TenantWebFilter` and `TenantGatewayFilter` — reject (400) before setting/forwarding.
- `TenantDubboProviderFilter` — ignore an invalid attachment.
- `TenantLineHandler#getTenantId` — defense-in-depth, throws on invalid.
- `TenantDataSourceHelper#resolveDsName` — throws (fail closed) so a spoofed id
  can't route to an arbitrary `tenant_<x>` datasource.

### 1.14 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

```text
vip.mate.starter.tenant.TenantAutoConfiguration
```

### 1.15 META-INF/dubbo/org.apache.dubbo.rpc.Filter

Dubbo SPI registration file at:
`src/main/resources/META-INF/dubbo/org.apache.dubbo.rpc.Filter`

```text
tenantConsumer=vip.mate.starter.tenant.TenantDubboConsumerFilter
tenantProvider=vip.mate.starter.tenant.TenantDubboProviderFilter
```

### 1.16 Application Configuration Example

```yaml
mate:
  tenant:
    enabled: true
    type: COLUMN               # NONE / COLUMN (SCHEMA, DATASOURCE planned)
    column: tenant_id
    resolver: header           # header / domain / token
    header-name: X-Tenant-Id
    super-tenant-id: "0"       # this tenant sees all tenants' data
    # Whitelist: ONLY these tables get `WHERE tenant_id = ?`. Everything else
    # (config / dict / menu / mate_tenant itself) is global and never filtered.
    include-tables:
      - mate_user
      - mate_admin
      - mate_role
    ignore-urls:
      - /actuator/**
      - /api/v1/auth/**
```

### 1.17 TenantHelper.java

Programmatic control for the cases the automatic interceptor can't cover:
cross-tenant admin reads, bootstrap seeding, the tenant-management module
itself, and manual hand-offs to executors.

```java
public final class TenantHelper {

    // --- temporarily disable the tenant-line filter (MyBatis-Plus InterceptorIgnoreHelper) ---
    public static <T> T withoutTenant(Supplier<T> supplier) { ... }
    public static void runWithoutTenant(Runnable runnable) { ... }

    // --- run a block as a specific tenant (sets TenantContext, restores after) ---
    public static <T> T callAsTenant(String tenantId, Supplier<T> supplier) { ... }
    public static void runAsTenant(String tenantId, Runnable runnable) { ... }
}
```

`withoutTenant` is backed by `InterceptorIgnoreHelper.handle(IgnoreStrategy
.builder().tenantLine(true).build())` and always clears the strategy in a
`finally` block. Note that `mate_tenant` / `mate_tenant_package` are *not* in
the whitelist, so the tenant-management CRUD does not actually need a bypass —
the helper exists for code that touches whitelisted tables across tenants.

### 1.18 MultiTenantType.java (isolation strategy)

```java
public enum MultiTenantType {
    NONE,        // single-tenant, no isolation
    COLUMN,      // row-level WHERE tenant_id = ?   (implemented)
    SCHEMA,      // one schema per tenant           (reserved)
    DATASOURCE   // one datasource per tenant        (reserved)
}
```

Only `COLUMN` is wired today. `SCHEMA` / `DATASOURCE` are reserved so config and
the resolver chain stay forward-compatible — see Part 5.

---

## Part 2: Domain Extension for Tenant Management

> **As-built note** — the tenant-management domain ships in **mate-system**
> under `vip.mate.system.tenant.*` (4-layer DDD: `domain.model.aggregate
> .TenantAggregate`, `domain.model.entity.{Tenant,TenantPackage}`,
> `domain.model.valobj.TenantStatus`, `application.command.TenantCommandService`,
> `application.query.TenantQueryService`, `infrastructure.dao.*`,
> `trigger.controller.TenantController`). The aggregate exposes
> create / updateProfile / suspend / activate / renew / changePackage / delete.
> The code blocks below are the original design sketch and use the older
> `vip.mate.admin.*` package and slightly different field names — treat them as
> illustrative, not authoritative.

### 2.1 domain/tenant/model/aggregate/TenantAggregate.java

```java
package vip.mate.admin.domain.tenant.model.aggregate;

import cn.hutool.core.date.DateUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vip.mate.admin.domain.tenant.model.entity.TenantPackage;
import vip.mate.admin.domain.tenant.model.valobj.TenantStatus;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantAggregate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String domain;
    private String packageId;
    private String contactName;
    private String contactMobile;
    private TenantStatus status;
    private Date expireTime;
    private Date createdAt;
    private Date updatedAt;

    private TenantPackage tenantPackage;

    /**
     * Factory method to create a new tenant.
     */
    public static TenantAggregate create(String name, String domain,
                                          String packageId, String contactName,
                                          String contactMobile) {
        return TenantAggregate.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .name(name)
                .domain(domain)
                .packageId(packageId)
                .contactName(contactName)
                .contactMobile(contactMobile)
                .status(TenantStatus.ACTIVE)
                .expireTime(DateUtil.offsetMonth(new Date(), 1)) // Default 1 month trial
                .build();
    }

    /**
     * Freeze this tenant (disable access).
     */
    public void freeze() {
        this.status = TenantStatus.FROZEN;
    }

    /**
     * Activate this tenant.
     */
    public void activate() {
        this.status = TenantStatus.ACTIVE;
    }

    /**
     * Renew tenant subscription by N months.
     */
    public void renew(int months) {
        Date base = this.expireTime.before(new Date()) ? new Date() : this.expireTime;
        this.expireTime = DateUtil.offsetMonth(base, months);
        if (this.status == TenantStatus.EXPIRED) {
            this.status = TenantStatus.ACTIVE;
        }
    }

    /**
     * Check if the tenant subscription has expired.
     */
    public boolean isExpired() {
        return new Date().after(this.expireTime);
    }

    /**
     * Upgrade or change the tenant package.
     */
    public void changePackage(String newPackageId) {
        this.packageId = newPackageId;
    }
}
```

### 2.2 domain/tenant/model/entity/TenantPackage.java

```java
package vip.mate.admin.domain.tenant.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantPackage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private Integer maxUsers;
    private Integer maxStorage;   // Max storage in MB
    private List<String> menuIds; // Accessible menu IDs
    private Integer price;        // Monthly price in cents
    private Integer status;       // 1=active 0=disabled
    private String remark;
    private Date createdAt;
    private Date updatedAt;
}
```

### 2.3 domain/tenant/model/valobj/TenantStatus.java

```java
package vip.mate.admin.domain.tenant.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TenantStatus {

    ACTIVE(1, "Active"),
    FROZEN(2, "Frozen"),
    EXPIRED(3, "Expired");

    private final int code;
    private final String description;

    public static TenantStatus of(int code) {
        for (TenantStatus s : values()) {
            if (s.code == code) return s;
        }
        return ACTIVE;
    }
}
```

### 2.4 domain/tenant/adapter/repository/TenantRepository.java

```java
package vip.mate.admin.domain.tenant.adapter.repository;

import vip.mate.admin.domain.tenant.model.aggregate.TenantAggregate;
import vip.mate.admin.domain.tenant.model.entity.TenantPackage;

import java.util.List;

public interface TenantRepository {

    void save(TenantAggregate aggregate);

    void update(TenantAggregate aggregate);

    void deleteById(String id);

    TenantAggregate findById(String id);

    TenantAggregate findByDomain(String domain);

    List<TenantAggregate> pageQuery(int pageNum, int pageSize, String keyword);

    long countAll(String keyword);

    // Package operations
    void savePackage(TenantPackage pkg);

    void updatePackage(TenantPackage pkg);

    void deletePackageById(String id);

    TenantPackage findPackageById(String id);

    List<TenantPackage> listPackages();
}
```

### 2.5 infrastructure/dao/po/TenantPO.java

```java
package vip.mate.admin.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vip.mate.base.model.entity.BaseEntity;

import java.io.Serial;
import java.util.Date;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("mate_tenant")
public class TenantPO extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String domain;
    private String packageId;
    private String contactName;
    private String contactMobile;
    private Integer status;
    private Date expireTime;
}
```

### 2.6 infrastructure/dao/po/TenantPackagePO.java

```java
package vip.mate.admin.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vip.mate.base.model.entity.BaseEntity;

import java.io.Serial;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("mate_tenant_package")
public class TenantPackagePO extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private Integer maxUsers;
    private Integer maxStorage;
    private String menuIds;   // Comma-separated menu IDs
    private Integer price;
    private Integer status;
    private String remark;
}
```

### 2.7 infrastructure/dao/mapper/TenantMapper.java

```java
package vip.mate.admin.infrastructure.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.admin.infrastructure.dao.po.TenantPO;

@Mapper
public interface TenantMapper extends BaseMapper<TenantPO> {
}
```

### 2.8 infrastructure/dao/mapper/TenantPackageMapper.java

```java
package vip.mate.admin.infrastructure.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.admin.infrastructure.dao.po.TenantPackagePO;

@Mapper
public interface TenantPackageMapper extends BaseMapper<TenantPackagePO> {
}
```

### 2.9 infrastructure/adapter/repository/TenantRepositoryImpl.java

```java
package vip.mate.admin.infrastructure.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vip.mate.admin.domain.tenant.adapter.repository.TenantRepository;
import vip.mate.admin.domain.tenant.model.aggregate.TenantAggregate;
import vip.mate.admin.domain.tenant.model.entity.TenantPackage;
import vip.mate.admin.domain.tenant.model.valobj.TenantStatus;
import vip.mate.admin.infrastructure.dao.mapper.TenantMapper;
import vip.mate.admin.infrastructure.dao.mapper.TenantPackageMapper;
import vip.mate.admin.infrastructure.dao.po.TenantPO;
import vip.mate.admin.infrastructure.dao.po.TenantPackagePO;

import java.util.Arrays;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class TenantRepositoryImpl implements TenantRepository {

    private final TenantMapper tenantMapper;
    private final TenantPackageMapper tenantPackageMapper;

    @Override
    public void save(TenantAggregate aggregate) {
        TenantPO po = TenantPO.builder()
                .name(aggregate.getName())
                .domain(aggregate.getDomain())
                .packageId(aggregate.getPackageId())
                .contactName(aggregate.getContactName())
                .contactMobile(aggregate.getContactMobile())
                .status(aggregate.getStatus().getCode())
                .expireTime(aggregate.getExpireTime())
                .build();
        po.setId(aggregate.getId());
        tenantMapper.insert(po);
    }

    @Override
    public void update(TenantAggregate aggregate) {
        TenantPO po = tenantMapper.selectById(aggregate.getId());
        if (po == null) return;
        po.setName(aggregate.getName());
        po.setDomain(aggregate.getDomain());
        po.setPackageId(aggregate.getPackageId());
        po.setContactName(aggregate.getContactName());
        po.setContactMobile(aggregate.getContactMobile());
        po.setStatus(aggregate.getStatus().getCode());
        po.setExpireTime(aggregate.getExpireTime());
        tenantMapper.updateById(po);
    }

    @Override
    public void deleteById(String id) {
        tenantMapper.deleteById(id);
    }

    @Override
    public TenantAggregate findById(String id) {
        TenantPO po = tenantMapper.selectById(id);
        if (po == null) return null;
        return toAggregate(po);
    }

    @Override
    public TenantAggregate findByDomain(String domain) {
        LambdaQueryWrapper<TenantPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TenantPO::getDomain, domain);
        TenantPO po = tenantMapper.selectOne(wrapper);
        if (po == null) return null;
        return toAggregate(po);
    }

    @Override
    public List<TenantAggregate> pageQuery(int pageNum, int pageSize, String keyword) {
        LambdaQueryWrapper<TenantPO> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(TenantPO::getName, keyword)
                   .or().like(TenantPO::getDomain, keyword);
        }
        wrapper.orderByDesc(TenantPO::getCreatedAt);
        Page<TenantPO> page = tenantMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return page.getRecords().stream().map(this::toAggregate).toList();
    }

    @Override
    public long countAll(String keyword) {
        LambdaQueryWrapper<TenantPO> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(TenantPO::getName, keyword)
                   .or().like(TenantPO::getDomain, keyword);
        }
        return tenantMapper.selectCount(wrapper);
    }

    // ==================== Package Operations ====================

    @Override
    public void savePackage(TenantPackage pkg) {
        TenantPackagePO po = TenantPackagePO.builder()
                .name(pkg.getName())
                .maxUsers(pkg.getMaxUsers())
                .maxStorage(pkg.getMaxStorage())
                .menuIds(pkg.getMenuIds() != null ? String.join(",", pkg.getMenuIds()) : "")
                .price(pkg.getPrice())
                .status(pkg.getStatus())
                .remark(pkg.getRemark())
                .build();
        tenantPackageMapper.insert(po);
        pkg.setId(po.getId());
    }

    @Override
    public void updatePackage(TenantPackage pkg) {
        TenantPackagePO po = tenantPackageMapper.selectById(pkg.getId());
        if (po == null) return;
        po.setName(pkg.getName());
        po.setMaxUsers(pkg.getMaxUsers());
        po.setMaxStorage(pkg.getMaxStorage());
        po.setMenuIds(pkg.getMenuIds() != null ? String.join(",", pkg.getMenuIds()) : "");
        po.setPrice(pkg.getPrice());
        po.setStatus(pkg.getStatus());
        po.setRemark(pkg.getRemark());
        tenantPackageMapper.updateById(po);
    }

    @Override
    public void deletePackageById(String id) {
        tenantPackageMapper.deleteById(id);
    }

    @Override
    public TenantPackage findPackageById(String id) {
        TenantPackagePO po = tenantPackageMapper.selectById(id);
        if (po == null) return null;
        return toPackage(po);
    }

    @Override
    public List<TenantPackage> listPackages() {
        return tenantPackageMapper.selectList(null).stream().map(this::toPackage).toList();
    }

    // ==================== Converters ====================

    private TenantAggregate toAggregate(TenantPO po) {
        TenantAggregate agg = TenantAggregate.builder()
                .id(po.getId())
                .name(po.getName())
                .domain(po.getDomain())
                .packageId(po.getPackageId())
                .contactName(po.getContactName())
                .contactMobile(po.getContactMobile())
                .status(TenantStatus.of(po.getStatus()))
                .expireTime(po.getExpireTime())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();

        // Load package if available
        if (po.getPackageId() != null) {
            TenantPackagePO pkgPo = tenantPackageMapper.selectById(po.getPackageId());
            if (pkgPo != null) {
                agg.setTenantPackage(toPackage(pkgPo));
            }
        }
        return agg;
    }

    private TenantPackage toPackage(TenantPackagePO po) {
        List<String> menuIds = (po.getMenuIds() != null && !po.getMenuIds().isBlank())
                ? Arrays.asList(po.getMenuIds().split(","))
                : List.of();
        return TenantPackage.builder()
                .id(po.getId())
                .name(po.getName())
                .maxUsers(po.getMaxUsers())
                .maxStorage(po.getMaxStorage())
                .menuIds(menuIds)
                .price(po.getPrice())
                .status(po.getStatus())
                .remark(po.getRemark())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}
```

### 2.10 trigger/controller/TenantController.java

```java
package vip.mate.admin.trigger.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.admin.domain.tenant.adapter.repository.TenantRepository;
import vip.mate.admin.domain.tenant.model.aggregate.TenantAggregate;
import vip.mate.admin.domain.tenant.model.entity.TenantPackage;
import vip.mate.base.exception.BizException;
import vip.mate.base.result.Result;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantRepository tenantRepository;

    // ==================== Tenant CRUD ====================

    @SaCheckPermission("admin:tenant:create")
    @PostMapping
    public Result<String> create(@Valid @RequestBody CreateTenantRequest req) {
        TenantAggregate aggregate = TenantAggregate.create(
                req.name, req.domain, req.packageId, req.contactName, req.contactMobile);
        tenantRepository.save(aggregate);
        return Result.ok(aggregate.getId());
    }

    @SaCheckPermission("admin:tenant:update")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id,
                                @Valid @RequestBody UpdateTenantRequest req) {
        TenantAggregate aggregate = tenantRepository.findById(id);
        if (aggregate == null) {
            throw new BizException("T0001", "Tenant not found");
        }
        aggregate.setName(req.name);
        aggregate.setDomain(req.domain);
        aggregate.setContactName(req.contactName);
        aggregate.setContactMobile(req.contactMobile);
        if (req.packageId != null) {
            aggregate.changePackage(req.packageId);
        }
        tenantRepository.update(aggregate);
        return Result.ok();
    }

    @SaCheckPermission("admin:tenant:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        tenantRepository.deleteById(id);
        return Result.ok();
    }

    @SaCheckPermission("admin:tenant:query")
    @GetMapping("/{id}")
    public Result<TenantAggregate> getById(@PathVariable String id) {
        return Result.ok(tenantRepository.findById(id));
    }

    @SaCheckPermission("admin:tenant:query")
    @GetMapping
    public Result<Map<String, Object>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        List<TenantAggregate> list = tenantRepository.pageQuery(pageNum, pageSize, keyword);
        long total = tenantRepository.countAll(keyword);
        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", total);
        return Result.ok(result);
    }

    @SaCheckPermission("admin:tenant:update")
    @PutMapping("/{id}/freeze")
    public Result<Void> freeze(@PathVariable String id) {
        TenantAggregate aggregate = tenantRepository.findById(id);
        if (aggregate == null) {
            throw new BizException("T0001", "Tenant not found");
        }
        aggregate.freeze();
        tenantRepository.update(aggregate);
        return Result.ok();
    }

    @SaCheckPermission("admin:tenant:update")
    @PutMapping("/{id}/activate")
    public Result<Void> activate(@PathVariable String id) {
        TenantAggregate aggregate = tenantRepository.findById(id);
        if (aggregate == null) {
            throw new BizException("T0001", "Tenant not found");
        }
        aggregate.activate();
        tenantRepository.update(aggregate);
        return Result.ok();
    }

    @SaCheckPermission("admin:tenant:update")
    @PutMapping("/{id}/renew")
    public Result<Void> renew(@PathVariable String id, @RequestParam @NotNull Integer months) {
        TenantAggregate aggregate = tenantRepository.findById(id);
        if (aggregate == null) {
            throw new BizException("T0001", "Tenant not found");
        }
        aggregate.renew(months);
        tenantRepository.update(aggregate);
        return Result.ok();
    }

    // ==================== Tenant Package CRUD ====================

    @SaCheckPermission("admin:tenant:package:create")
    @PostMapping("/packages")
    public Result<String> createPackage(@Valid @RequestBody CreatePackageRequest req) {
        TenantPackage pkg = TenantPackage.builder()
                .name(req.name)
                .maxUsers(req.maxUsers)
                .maxStorage(req.maxStorage)
                .menuIds(req.menuIds)
                .price(req.price)
                .status(1)
                .remark(req.remark)
                .build();
        tenantRepository.savePackage(pkg);
        return Result.ok(pkg.getId());
    }

    @SaCheckPermission("admin:tenant:package:update")
    @PutMapping("/packages/{id}")
    public Result<Void> updatePackage(@PathVariable String id,
                                       @Valid @RequestBody UpdatePackageRequest req) {
        TenantPackage pkg = tenantRepository.findPackageById(id);
        if (pkg == null) {
            throw new BizException("T0010", "Tenant package not found");
        }
        pkg.setName(req.name);
        pkg.setMaxUsers(req.maxUsers);
        pkg.setMaxStorage(req.maxStorage);
        pkg.setMenuIds(req.menuIds);
        pkg.setPrice(req.price);
        pkg.setStatus(req.status);
        pkg.setRemark(req.remark);
        tenantRepository.updatePackage(pkg);
        return Result.ok();
    }

    @SaCheckPermission("admin:tenant:package:delete")
    @DeleteMapping("/packages/{id}")
    public Result<Void> deletePackage(@PathVariable String id) {
        tenantRepository.deletePackageById(id);
        return Result.ok();
    }

    @SaCheckPermission("admin:tenant:package:query")
    @GetMapping("/packages")
    public Result<List<TenantPackage>> listPackages() {
        return Result.ok(tenantRepository.listPackages());
    }

    @SaCheckPermission("admin:tenant:package:query")
    @GetMapping("/packages/{id}")
    public Result<TenantPackage> getPackage(@PathVariable String id) {
        return Result.ok(tenantRepository.findPackageById(id));
    }

    // ==================== Request Records ====================

    public record CreateTenantRequest(
            @NotBlank String name,
            String domain,
            String packageId,
            String contactName,
            String contactMobile
    ) {}

    public record UpdateTenantRequest(
            String name,
            String domain,
            String packageId,
            String contactName,
            String contactMobile
    ) {}

    public record CreatePackageRequest(
            @NotBlank String name,
            @NotNull Integer maxUsers,
            @NotNull Integer maxStorage,
            List<String> menuIds,
            @NotNull Integer price,
            String remark
    ) {}

    public record UpdatePackageRequest(
            String name,
            Integer maxUsers,
            Integer maxStorage,
            List<String> menuIds,
            Integer price,
            Integer status,
            String remark
    ) {}
}
```

---

## Part 3: SQL DDL

```sql
-- =============================================
-- Multi-Tenant DDL
-- =============================================

CREATE TABLE `mate_tenant` (
    `id`              VARCHAR(64)     NOT NULL COMMENT 'Primary key (UUID)',
    `name`            VARCHAR(128)    NOT NULL COMMENT 'Tenant name',
    `domain`          VARCHAR(128)    DEFAULT NULL COMMENT 'Tenant subdomain (e.g. acme)',
    `package_id`      VARCHAR(64)     DEFAULT NULL COMMENT 'Tenant package ID',
    `contact_name`    VARCHAR(64)     DEFAULT NULL COMMENT 'Contact person name',
    `contact_mobile`  VARCHAR(20)     DEFAULT NULL COMMENT 'Contact mobile',
    `status`          TINYINT         NOT NULL DEFAULT 1 COMMENT 'Status: 1=active 2=frozen 3=expired',
    `expire_time`     DATETIME        DEFAULT NULL COMMENT 'Subscription expiration time',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT         NOT NULL DEFAULT 0,
    `lock_version`    INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_domain` (`domain`),
    KEY `idx_status` (`status`),
    KEY `idx_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tenants';

CREATE TABLE `mate_tenant_package` (
    `id`              VARCHAR(64)     NOT NULL COMMENT 'Primary key',
    `name`            VARCHAR(128)    NOT NULL COMMENT 'Package name (e.g. Basic, Pro, Enterprise)',
    `max_users`       INT             NOT NULL DEFAULT 10 COMMENT 'Max number of users',
    `max_storage`     INT             NOT NULL DEFAULT 1024 COMMENT 'Max storage in MB',
    `menu_ids`        TEXT            DEFAULT NULL COMMENT 'Accessible menu IDs (comma-separated)',
    `price`           INT             NOT NULL DEFAULT 0 COMMENT 'Monthly price in cents',
    `status`          TINYINT         NOT NULL DEFAULT 1 COMMENT 'Status: 1=active 0=disabled',
    `remark`          VARCHAR(256)    DEFAULT NULL COMMENT 'Remark',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT         NOT NULL DEFAULT 0,
    `lock_version`    INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tenant packages (subscription plans)';

-- =============================================
-- Add tenant_id column to business tables
-- =============================================

ALTER TABLE `mate_admin` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT '0' COMMENT 'Tenant ID' AFTER `id`;
ALTER TABLE `mate_admin` ADD KEY `idx_tenant_id` (`tenant_id`);

ALTER TABLE `mate_role` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT '0' COMMENT 'Tenant ID' AFTER `id`;
ALTER TABLE `mate_role` ADD KEY `idx_tenant_id` (`tenant_id`);

ALTER TABLE `mate_admin_role` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT '0' COMMENT 'Tenant ID' AFTER `id`;
ALTER TABLE `mate_admin_role` ADD KEY `idx_tenant_id` (`tenant_id`);

-- mate_user table (from mate-system)
ALTER TABLE `mate_user` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT '0' COMMENT 'Tenant ID' AFTER `id`;
ALTER TABLE `mate_user` ADD KEY `idx_tenant_id` (`tenant_id`);

-- Seed data: default package
INSERT INTO `mate_tenant_package` (`id`, `name`, `max_users`, `max_storage`, `menu_ids`, `price`, `status`, `remark`)
VALUES ('1', 'Basic', 10, 1024, '', 0, 1, 'Free trial package');

INSERT INTO `mate_tenant_package` (`id`, `name`, `max_users`, `max_storage`, `menu_ids`, `price`, `status`, `remark`)
VALUES ('2', 'Professional', 50, 10240, '', 9900, 1, 'Professional package');

INSERT INTO `mate_tenant_package` (`id`, `name`, `max_users`, `max_storage`, `menu_ids`, `price`, `status`, `remark`)
VALUES ('3', 'Enterprise', 500, 102400, '', 29900, 1, 'Enterprise package');
```

---

## Part 4: Verification Plan

1. **Row-level isolation**: Create two tenants (A and B), each creates users. Verify tenant A cannot see tenant B's data via the same API endpoint.
2. **Tenant resolver**: Test all three strategies:
   - Header: `X-Tenant-Id: tenantA` in request header
   - Domain: `tenanta.matecloud.vip` resolves to `tenanta`
   - Token: tenant ID stored in Sa-Token session during login
3. **RPC propagation**: Call from mate-auth to mate-system, verify `tenant_id` is carried through Dubbo attachment automatically.
4. **Cache isolation**: Different tenants caching the same query produce different cache keys (`t:tenantA:...` vs `t:tenantB:...`).
5. **Package limits**: Try to create users beyond the package's `max_users` limit, expect rejection.
6. **Tenant expiration**: Set a tenant's `expire_time` to the past, verify requests return 403 or frozen response.
7. **Gateway filter**: Verify that requests without `X-Tenant-Id` header are rejected at gateway level with 400 Bad Request.
8. **Whitelist safety**: With `enabled=true`, query a non-whitelisted table (e.g. `mate_config`) and confirm NO `tenant_id` predicate is appended.
9. **Super tenant**: Log in as `super-tenant-id`, confirm queries on whitelisted tables return rows across all tenants.
10. **Programmatic bypass**: Wrap a whitelisted-table query in `TenantHelper.runWithoutTenant` and confirm the predicate is dropped.
11. **Fail-closed**: With `enabled=true`, hit a protected endpoint that queries `mate_user` with NO resolved tenant (e.g. internal call path) and confirm it errors out instead of returning all tenants' rows.
12. **Login still works**: With `enabled=true`, confirm password login succeeds end-to-end (auth → `IRpcUserService`/`IRpcPermissionService` run cross-tenant via `withoutTenant`) and the issued session carries the user's real tenant id.
13. **Malformed tenant id**: Send `X-Tenant-Id: 1' OR '1'='1` and confirm a 400 at the gateway/web filter (never reaches SQL).

---

## Part 5: Isolation Strategy Roadmap

`MultiTenantType` defines four strategies. Their status and design:

| Type | Status | Mechanism | Trade-off |
|------|--------|-----------|-----------|
| `NONE` | ✅ | No interceptor; single-tenant. | — |
| `COLUMN` | ✅ implemented | One shared DB; `WHERE tenant_id = ?` appended to whitelisted tables. | Cheapest; weakest physical isolation; fine until a table approaches ~10M rows. |
| `SCHEMA` | ✅ routing done / provisioning pending | Shared server, one schema per tenant — configured as separate dynamic-datasource entries whose URLs point at different schemas. | Stronger isolation, single server; cross-schema joins get harder. |
| `DATASOURCE` | ✅ routing done / provisioning pending | One datasource (DB/instance) per tenant via baomidou dynamic-datasource routing. | Strongest isolation, per-tenant scaling/backup; most operational overhead. |

### Implemented: routing foundation (2026-05-25)

SCHEMA and DATASOURCE share one routing mechanism (they differ only in how the
operator points the datasource URLs). The foundation is wired; **automatic
per-tenant DB provisioning is deferred** (operator configures datasources
statically for now).

1. **Dependency** — `mate-tenant-starter` pulls
   `com.baomidou:dynamic-datasource-spring-boot4-starter:4.5.0`. It is dormant unless
   `spring.datasource.dynamic.enabled=true`.
2. **Datasource coexistence** — `mate-ds-starter`'s single Druid datasource is
   gated by `@ConditionalOnProperty("spring.datasource.dynamic.enabled"=false,
   matchIfMissing=true)`. Enabling dynamic-datasource hands the `DataSource`
   bean to baomidou's `DynamicRoutingDataSource`; ds-starter's SqlSessionFactory
   binds to whichever `DataSource` exists. Default is OFF (see
   `mate-defaults.yml`).
3. **Routing key** — `TenantDataSourceWebFilter` (registered when
   `spring.datasource.dynamic.enabled=true`, runs right after `TenantWebFilter`)
   pushes `dsName = dsPrefix + tenantId` onto `DynamicDataSourceContextHolder`
   for the request; the super tenant routes to master. **Fail closed:** a
   protected request with no tenant context is rejected (400) — it does NOT fall
   back to master (which holds tenant-management and other tenants' data). Master
   is reachable only via an `ignore-urls` path or programmatically through
   `TenantDataSourceHelper.executeWithMaster`. (Unlike COLUMN mode there is no
   row-level fallback in DATASOURCE/SCHEMA mode, so this boundary check is the
   sole isolation gate.)
4. **Programmatic control** — `TenantDataSourceHelper`:
   `executeWithDs(dsName, …)`, `executeWithMaster(props, …)`, `resolveDsName(…)`,
   plus raw `push/poll`.
5. **Interaction with COLUMN** — the tenant-line interceptor's `ignoreTable`
   returns true whenever `type != COLUMN`, so row-level and datasource isolation
   are mutually exclusive at runtime.
6. **Super tenant / master operations** — tenant-management tables
   (`mate_tenant*`) are not whitelisted and the super/empty tenant resolves to
   the master datasource, so CRUD there always hits master.

```yaml
spring:
  datasource:
    dynamic:
      enabled: true
      primary: master
      datasource:
        master:        # tenant-management tables + super tenant live here
          url: jdbc:mysql://db-host:3306/mate_master
          username: ${DB_USER}
          password: ${DB_PWD}
        tenant_1001:   # dsName = dsPrefix("tenant_") + tenantId("1001")
          url: jdbc:mysql://db-host:3306/mate_tenant_1001
          username: ${DB_USER}
          password: ${DB_PWD}
        tenant_1002:
          url: jdbc:mysql://db-host2:3306/mate_tenant_1002   # different host = DATASOURCE
          username: ${DB_USER}
          password: ${DB_PWD}
mate:
  tenant:
    enabled: true
    type: DATASOURCE          # or SCHEMA (same host, different schema URLs)
    default-ds-name: master
    ds-prefix: "tenant_"
    super-tenant-id: "0"
```

### Deferred (next phase)

- `mate_tenant_db_binding` table + runtime dynamic registration of datasources
  (so new tenants don't require a config edit + restart).
- Auto-provisioning on tenant creation: create the schema/DB and run the tenant
  DDL scripts.

> **RPC datasource routing (done)** — `TenantDubboProviderFilter` pushes the
> tenant datasource (via `TenantRuntime.isDatasourceMode()` +
> `TenantDataSourceHelper.resolveDsName`) when a tenant context is present, so a
> business RPC in DATASOURCE/SCHEMA mode hits `tenant_<id>` rather than master.
> It is also **fail-closed for missing context**: in SCHEMA/DATASOURCE mode an
> RPC with no tenant attachment is rejected with `RpcException` unless the target
> method is annotated `@CrossTenantRpc` (the auth/identity *lookups* on
> `IRpcUserService` / `IRpcPermissionService`), which are explicitly allowed to
> run on the primary/master datasource. This is the RPC counterpart of the web
> filter's reject — a business RPC that forgets to propagate the tenant errors
> out instead of silently reading master.
>
> - **Exact-signature match**: methods are resolved by `getMethod(name, paramTypes)`,
>   not name alone, so an annotated overload can't whitelist an unannotated
>   same-named overload.
> - **Generic invocation** (`$invoke`/`$invokeAsync`) is unwrapped: the real
>   method name (arg 0) and parameter-type names (arg 1, `String[]`) rebuild the
>   signature for the same annotation check; unresolvable types fail closed. Only
>   `$echo` is allowed among other `$*` pseudo-methods — any other fails closed.
> - **`registerUser` is intentionally NOT `@CrossTenantRpc`** — it is a write, so
>   in DATASOURCE/SCHEMA mode it must run with a tenant context (lands in that
>   tenant's DB); a no-tenant call is rejected rather than writing to master.
> **Limitation**: those `@CrossTenantRpc` auth lookups still resolve against
> master; in DATASOURCE mode the user lives in a tenant DB, so cross-tenant auth
> needs the tenant→DB binding (deferred above).
