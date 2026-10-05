# RFC-062: 运行时动态多数据源切换 (Tenant Dynamic DataSource)

- **Status**: Proposed (设计稿，未落地代码)
- **Created**: 2026-06-15
- **Author**: MateCloud Team
- **Wave**: 12
- **Dependencies**: RFC-012 (mate-tenant-starter / DATASOURCE 模式骨架), RFC-003 (mate-ds-starter), RFC-016 (database-sharding), RFC-041 (Flyway 迁移)
- **参考来源对比对象**: joolun-pro (`joolun-common-data` / `joolun-common-datasource` 的运行时分库实现)

---

## 0. 一句话目标

让 `mate.tenant.type=DATASOURCE` 从"必须把每个租户库写进 YAML 并重启"变成
"**租户库连接信息放 Nacos，运行时按需懒注册、命中即缓存、超量按 LRU 回收**"——
在补齐 joolun-pro 运行时分库能力的同时，修掉它的两处性能/稳定性硬伤
（全局锁串行注册、连接池无上限）。

---

## 1. 背景与现状

### 1.1 matecloud 当前的 DATASOURCE 模式：只有"切换"，没有"注册"

`mate-tenant-starter` 已经具备完整的数据源**路由**链路（RFC-012 Part 5）：

| 环节 | 文件 | 作用 |
|------|------|------|
| 解析租户 | `web/TenantWebFilter` | 把租户写入 `TenantContext` |
| 服务内路由 | `datasource/TenantDataSourceWebFilter` | `dsName = tenant_<id>`，`push` 到 baomidou 路由栈 |
| 派生 ds 名 | `datasource/TenantDataSourceHelper#resolveDsName` | 校验 + 派生，非法 id fail-closed |
| RPC 路由 | `rpc/TenantDubboProviderFilter` | DATASOURCE 模式下 provider 端也 push |
| 路由底座 | baomidou `DynamicRoutingDataSource` | `spring.datasource.dynamic.enabled=true` 时成为唯一 `DataSource` |

**缺口**：`TenantDataSourceHelper.push(dsName)` 只是把名字压栈，
baomidou 在取连接时按这个名字到它的 `Map<String, DataSource>` 里找。
但这个 Map **只有 `spring.datasource.dynamic.datasource.*` 静态配置的条目**。
没有任何代码在运行时为新租户创建 `tenant_<id>` 数据源。后果有二：

1. **新增租户必须改 YAML + 重启全部服务** —— SaaS 场景不可接受。
2. **更危险**：baomidou 默认 `strict=false`，push 一个不存在的 `dsName` 会**静默回退到 primary（master）库**。
   也就是说——租户 A 的请求会读到 master 库的数据，**行级 fail-closed 在 DATASOURCE 模式下被绕过，成为静默越权**。
   （RFC-012 §1.13b 的 fail-closed 只保护 COLUMN 模式的 SQL 改写，管不到数据源路由层。）

> 结论：要么把 DATASOURCE 模式标记为"需手工预配 + strict=true 才安全"，
> 要么补上运行时注册。本 RFC 选后者。

### 1.2 joolun-pro 怎么做的（运行时分库）

`com.joolun.cloud.common.data.tenant.TenantContextHolder`：

- `setTenantId(tenantId)` 时，若 `sharding.enabled`，派生库名 `dbname_{tenantId}`；
- 若 baomidou 路由表里没有该库，**当场用 `DriverManager.getConnection()` 探活**，
  再 `DefaultDataSourceCreator` 建池、`addDataSource` 注册；
- 注册方法挂 **`@Synchronized`**（对象级全局锁）。
- 数据源清单另有 `JdbcDynamicDataSourceProvider` 从 `sys_datasource` 表加载，密码 Jasypt 解密。

**两处硬伤**（本 RFC 要规避）：

1. **全局 `@Synchronized`**：任意两个租户首次访问互相阻塞；高并发冷启动会排长队。
2. **无连接池上限、无空闲回收**：每个租户一个常驻 HikariCP 池。1 万租户 = 1 万池，
   连接数线性膨胀直到打满 MySQL `max_connections`。joolun 没有任何驱逐策略。
3. 次要：每次 `setTenantId` 都可能 `DriverManager` 探活，热路径上有额外握手开销。

### 1.3 两者对比小结

| 维度 | joolun-pro | matecloud 现状 | 本 RFC 目标 |
|------|-----------|---------------|------------|
| 运行时注册租户库 | ✅ 有 | ❌ 无（需重启） | ✅ 有 |
| 配置来源 | `sys_datasource` 表 | 静态 YAML | **Nacos 动态配置**（热更新） |
| 并发注册 | 全局锁串行 | — | **per-tenant 分段锁**，不同租户并行 |
| 连接池上限 | ❌ 无 | 单池 | **LRU 上限 + 空闲驱逐** |
| 未注册 dsName | 建库 | **静默回退 master（越权）** | **fail-closed 抛错** |
| 跨实例传播 | TTL + Feign | TTL? 否，纯 ThreadLocal + Dubbo/异步 Filter（更全） | 复用现有链路 |
| 密码加密 | Jasypt | — | Jasypt（可选） |

---

## 2. 设计目标与非目标

**目标**
- G1 运行时按需注册租户数据源，**零重启**新增/下线租户。
- G2 配置来源为 **Nacos**（`mate-tenant-datasource-${profile}.yml`），监听变更热加载。
- G3 高性能：命中缓存零开销；冷启动 per-tenant 分段锁、不互相阻塞；连接池 LRU 上限 + 空闲驱逐。
- G4 安全：未注册/非法租户 **fail-closed 抛错**，绝不回退 master；密码支持 Jasypt 解密。
- G5 零侵入：复用现有 `TenantContext` / filter / Dubbo / 异步链路，只在 `push` 前插一个 `ensureRegistered`。
- G6 新租户库首次注册时按需跑 Flyway（复用 RFC-041 per-service history 逻辑）。

**非目标**
- N1 不做 SCHEMA 模式的库内 schema 切换（本 RFC 聚焦 DATASOURCE = 一租户一库/一实例）。
- N2 不做读写分离 / 从库路由（baomidou 支持 `tenant_x_slave`，留作后续）。
- N3 不改 COLUMN 模式行为（默认仍是 COLUMN）。
- N4 不做租户库的自动 DDL 建库（建库由租户开通流程 `TenantProvisionService` 负责，本 RFC 只负责"已存在的库"接入路由 + 迁移版本对齐）。

---

## 3. 总体架构

```
请求/RPC/异步任务
   │  TenantContext.getTenantId() = "acme"
   ▼
TenantDataSourceHelper.push("tenant_acme")          ← 唯一改造点
   │
   ├─(1) registryHolder.ensureRegistered("tenant_acme")   ← 新增
   │        │  baomidou DynamicRoutingDataSource 已含该 ds?  ──yes──► 直接返回 (热路径，零锁)
   │        │  no
   │        ▼
   │     从 TenantDataSourceMetaCache 取 acme 的连接信息（Nacos 来源）
   │        │  无元数据? ─► 抛 TenantDataSourceNotFound (fail-closed)
   │        ▼
   │     striped lock(acme).lock()  ← per-tenant，不同租户并行
   │        double-check 再查一次路由表
   │        DefaultDataSourceCreator 建池 (per-tenant 小池)
   │        (可选) Flyway.migrate 对齐版本
   │        DynamicRoutingDataSource.addDataSource("tenant_acme", ds)
   │        LRU.recordAccess("tenant_acme") → 超上限则 evict 最久未用
   │
   └─(2) DynamicDataSourceContextHolder.push("tenant_acme")  ← 现有行为
   ▼
后续所有 SQL 路由到 tenant_acme 库
```

新增组件全部落在 `mate-tenant-starter` 的 `datasource` 包下。

---

## 4. 组件设计

### 4.1 配置：Nacos 数据源清单

新增 Nacos dataId：`mate-tenant-datasource-${profile}.yml`（共享配置，所有业务服务 import）。
模板放 `docs/nacos-templates/mate-tenant-datasource-dev.yml`。

```yaml
mate:
  tenant:
    datasource:
      enabled: true                 # 运行时注册总开关
      # 池治理
      max-pools: 200                # 常驻租户池上限；超出按 LRU 驱逐
      idle-evict-minutes: 30        # 池空闲多久后回收（0=不按空闲回收）
      per-pool-max-active: 5        # 每个租户池最大连接数（小池！）
      per-pool-min-idle: 0          # 默认 0，冷租户不占连接
      # Flyway
      migrate-on-register: false    # 首次注册是否对齐迁移版本
      # 元数据（未列出的租户 → 用模板派生，见 4.2）
      template:
        url: "jdbc:mysql://10.0.0.10:3306/mate_{tenant}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
        username: "mate_app"
        password: "ENC(xxxx)"       # Jasypt 加密，可选
        driver-class-name: "com.mysql.cj.jdbc.Driver"
      # 显式覆盖（跨实例/独立账号的大租户）
      tenants:
        bigcorp:
          url: "jdbc:mysql://10.0.1.99:3306/bigcorp_db?serverTimezone=Asia/Shanghai"
          username: "bigcorp"
          password: "ENC(yyyy)"
        globex:
          url: "jdbc:mysql://10.0.0.11:3306/mate_globex?serverTimezone=Asia/Shanghai"
          username: "mate_app"
          password: "ENC(zzzz)"
```

**解析优先级**：`tenants.<id>` 显式条目 > `template` 派生（`{tenant}` 占位符替换为租户 id）。
若两者都没有且不允许模板派生（template 未配） → fail-closed。

`TenantDataSourceProperties`（`@ConfigurationProperties("mate.tenant.datasource")`，
`@RefreshScope` 以支持 Nacos 热更新）：

```java
@Data
@RefreshScope
@ConfigurationProperties("mate.tenant.datasource")
public class TenantDataSourceProperties {
    private boolean enabled = false;
    private int maxPools = 200;
    private int idleEvictMinutes = 30;
    private int perPoolMaxActive = 5;
    private int perPoolMinIdle = 0;
    private boolean migrateOnRegister = false;
    private DsConn template;                       // 可空
    private Map<String, DsConn> tenants = new HashMap<>();

    @Data
    public static class DsConn {
        private String url;
        private String username;
        private String password;
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
    }
}
```

> **为什么 Nacos 而非 DB 表（joolun 的 `sys_datasource`）**：
> 用户选型为 Nacos。优点：运维集中、`@RefreshScope` 天然热更新、不引入"读元数据又要先有数据源"的鸡生蛋问题。
> 代价：海量租户（>万级）时清单膨胀——届时再演进为 DB 表 + 本地 TTL 缓存（见 §9 演进）。

### 4.2 元数据缓存 `TenantDataSourceMetaResolver`

把 `TenantDataSourceProperties` 解析成"租户 id → DsConn"，对外提供 `resolve(tenantId)`：

- 命中 `tenants.<id>` → 用之；
- 否则若 `template != null` → 占位符替换派生；
- 否则返回 `Optional.empty()`（调用方 fail-closed）。
- 密码若以 `ENC(` 包裹且容器内有 `StringEncryptor`（Jasypt）→ 解密；否则原样。
- `@RefreshScope` 保证 Nacos 推送新清单后下次 `resolve` 立即可见。

**热更新下线租户**：Nacos 删除某租户条目并不会主动关闭已注册的池——
由 `TenantDataSourceRegistry` 的"下线钩子"（监听 `RefreshScopeRefreshedEvent`）
对比新旧清单，对已删除且已注册的租户执行 `removeDataSource` + 关闭池（见 4.4）。

### 4.3 注册中枢 `TenantDataSourceRegistry`（核心）

职责：`ensureRegistered(dsName)`、`unregister(dsName)`、LRU、空闲驱逐。

```java
public class TenantDataSourceRegistry {

    private final DataSource routingDataSource;            // = DynamicRoutingDataSource
    private final DataSourceCreator dsCreator;             // baomidou (Druid creator, 见 §11 GAP-3)
    private final TenantDataSourceMetaResolver metaResolver;
    private final TenantDataSourceProperties props;
    private final TenantSchemaMigrator migrator;           // per-tenant Flyway, 见 §11 GAP-5

    /** per-tenant 锁条带：不同租户并行注册，同租户串行。 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /** 访问顺序 LRU，记录已注册的租户 ds 名 → 最近访问纳秒。 */
    private final ConcurrentHashMap<String, Long> lastAccess = new ConcurrentHashMap<>();

    public void ensureRegistered(String dsName) {
        if (dsName.equals(props's masterName)) { return; }          // master 永远静态存在
        DynamicRoutingDataSource drds = (DynamicRoutingDataSource) routingDataSource;

        // 热路径：已注册 → 仅刷新 LRU 访问时间，零锁
        if (drds.getDataSources().containsKey(dsName)) {
            lastAccess.put(dsName, System.nanoTime());
            return;
        }

        String tenantId = stripPrefix(dsName);
        DsConn conn = metaResolver.resolve(tenantId)
            .orElseThrow(() -> new TenantDataSourceNotFoundException(tenantId)); // fail-closed

        Object lock = locks.computeIfAbsent(dsName, k -> new Object());
        synchronized (lock) {
            // double-check：可能在排队期间已被另一线程注册
            if (drds.getDataSources().containsKey(dsName)) {
                lastAccess.put(dsName, System.nanoTime());
                return;
            }
            evictIfOverCapacity(drds);                  // 先腾位
            DataSourceProperty p = toBaomidouProperty(dsName, conn); // 含 per-pool 池参数 (§11 GAP-3)
            DataSource ds = dsCreator.createDataSource(p);          // 不做 DriverManager 探活 (§11 GAP-2)
            if (props.isMigrateOnRegister()) { migrator.migrate(dsName, ds); } // per-tenant Flyway (§11 GAP-5)
            drds.addDataSource(dsName, ds);
            lastAccess.put(dsName, System.nanoTime());
            log.info("[tenant-ds] registered datasource {} (active pools={})",
                     dsName, drds.getDataSources().size());
        }
    }
}
```

**关键设计点**

1. **热路径零锁**：`getDataSources()` 是 `ConcurrentHashMap`，`containsKey` 无锁。
   绝大多数请求（池已建）只多一次 map 查询 + 一次 `lastAccess.put`，纳秒级。
   —— 这是相对 joolun 全局锁的最大性能提升。
2. **per-tenant 分段锁**：`locks.computeIfAbsent(dsName, ...)`。
   只有同一租户的首次并发请求会串行，不同租户**完全并行**。
3. **双重检查**：避免重复建池。
4. **小池**：`perPoolMaxActive=5`，`minIdle=0`。冷租户几乎不占连接。
   假设 200 池 × 5 = 1000 连接上限，可控且可估算（对照 joolun 无上限）。
5. **fail-closed**：无元数据直接抛 `TenantDataSourceNotFoundException`（→ 400/500），
   绝不回退 master。建议同时把 baomidou 设为 `spring.datasource.dynamic.strict=true` 作为兜底。

### 4.4 容量治理：LRU 上限 + 空闲驱逐

```java
private void evictIfOverCapacity(DynamicRoutingDataSource drds) {
    while (countTenantPools(drds) >= props.getMaxPools()) {
        String victim = lastAccess.entrySet().stream()
            .filter(e -> !isMaster(e.getKey()))
            .min(Map.Entry.comparingByValue())   // 最久未访问
            .map(Map.Entry::getKey).orElse(null);
        if (victim == null) break;
        unregister(victim);
    }
}

/** 关池前确认无活跃借出连接，否则跳过该 victim 选下一个（避免拔正在用的池）。 */
public void unregister(String dsName) {
    DynamicRoutingDataSource drds = ...;
    drds.removeDataSource(dsName);   // baomidou 内部会 close 池
    lastAccess.remove(dsName);
    locks.remove(dsName);
    log.info("[tenant-ds] evicted datasource {}", dsName);
}
```

- **空闲驱逐**：一个 `@Scheduled`（或 `ScheduledExecutorService`）每 N 分钟扫 `lastAccess`，
  把空闲超过 `idleEvictMinutes` 的池 `unregister`。`idleEvictMinutes=0` 关闭该策略。
- **驱逐安全**：被驱逐后租户再来一次请求会重新 `ensureRegistered`（懒重建），对正确性无影响。
  唯一代价是重建池的冷启动延迟——所以 LRU 而非随机，命中热租户的概率最低。
- **Nacos 下线钩子**：监听 `RefreshScopeRefreshedEvent`，diff 新旧 `tenants` 清单，
  对"清单已删 + 仍在注册表"的租户主动 `unregister`。

### 4.5 接入点：唯一改造 `TenantDataSourceHelper`

仿照现有 `TenantRuntime`（Dubbo SPI filter 用的静态持有者）模式，
新增 `TenantDataSourceRegistryHolder`，在 `TenantAutoConfiguration` 启动时 `set`。
`push` 前先 `ensureRegistered`，**一处改动覆盖全部三个调用方**
（`TenantDataSourceWebFilter`、`TenantDubboProviderFilter`、`executeWithDs`）：

```java
// TenantDataSourceHelper.push —— 唯一改造
public static void push(String dsName) {
    TenantDataSourceRegistryHolder.get().ensureRegistered(dsName); // 新增：懒注册
    DynamicDataSourceContextHolder.push(dsName);
}
```

> `TenantDataSourceRegistryHolder.get()` 在注册功能未启用（`mate.tenant.datasource.enabled=false`）
> 时返回一个 no-op 实现，保持现有"静态预配"行为完全不变——向后兼容。

### 4.6 自动装配（`TenantAutoConfiguration` 增量）

```java
@Bean
@ConditionalOnClass(name = "com.baomidou.dynamic.datasource.DynamicRoutingDataSource")
@ConditionalOnProperty(prefix = "mate.tenant.datasource", name = "enabled", havingValue = "true")
public TenantDataSourceRegistry tenantDataSourceRegistry(
        DataSource dataSource,
        DataSourceCreator dataSourceCreator,          // 注入 Druid creator (§11 GAP-3)
        TenantDataSourceMetaResolver metaResolver,
        TenantDataSourceProperties props,
        TenantSchemaMigrator migrator) {              // per-tenant Flyway (§11 GAP-5)
    TenantDataSourceRegistry registry =
        new TenantDataSourceRegistry(dataSource, dataSourceCreator, metaResolver, props, migrator);
    TenantDataSourceRegistryHolder.set(registry);   // 供静态 helper / Dubbo SPI 使用
    return registry;
}
```

`DataSourceCreator` 由 baomidou starter 提供——但必须确保选到 **Druid** 实现（matecloud
全局用 Druid，见 §11 GAP-3），而非 joolun 用的 Hikari。

---

## 5. 端到端时序

**首次访问租户 acme（冷启动）**
1. 网关解析 `X-Tenant-Id: acme`（域名模式则取 Host），转发 header。
2. 服务 `TenantWebFilter` 写 `TenantContext=acme`。
3. `TenantDataSourceWebFilter` → `resolveDsName` → `tenant_acme` → `push`。
4. `push` → `ensureRegistered("tenant_acme")`：路由表无 → 取元数据 → 分段锁 → 建小池 →（可选迁移）→ `addDataSource`。
5. 业务 SQL 全部走 `tenant_acme` 库。
6. 请求结束 `poll()` 出栈；池**保留**在路由表（供后续请求复用）。

**第二次访问（热路径）**：步骤 4 命中 `containsKey`，仅刷 LRU 时间，零锁。

**跨 RPC**：consumer filter 带 `tenantId` attachment → provider filter 在 DATASOURCE 模式 `push` → 同样触发 `ensureRegistered`。

**异步任务**：`TenantContextTaskDecorator` 已传播 `TenantContext`；
如异步任务内发起 SQL，需在任务体内显式 `TenantDataSourceHelper.executeWithDs(resolveDsName(...), ...)`
（与现有约定一致，TaskDecorator 只搬 `TenantContext`，不搬 baomidou 路由栈）。

---

## 6. 安全

| 威胁 | 对策 |
|------|------|
| 伪造 `X-Tenant-Id` 路由到他库 | `TenantId.isValid` 正则（已存在）+ 域名模式只信 Host（已存在）。 |
| push 未注册名静默回退 master（越权） | `ensureRegistered` fail-closed 抛错；并建议 `dynamic.strict=true` 兜底。 |
| 无租户上下文的受保护请求 | `TenantDataSourceWebFilter` 已 fail-closed 返回 400（现有）。 |
| 明文库密码 | Nacos 中 `ENC(...)` + Jasypt 解密；Nacos 本身鉴权 + 命名空间隔离。 |
| 注册风暴（恶意造大量随机租户 id） | `maxPools` 上限 + LRU 即天然限流；元数据 `resolve` 对未知 id 直接 empty → 不建池。 |
| 跨租户管理操作 | 走 `TenantDataSourceHelper.executeWithMaster` 显式回 master（现有）。 |

---

## 7. 性能分析

| 场景 | joolun | 本设计 |
|------|--------|--------|
| 热请求（池已建）开销 | 全局锁 + 可能 `DriverManager` 探活 | **1 次 ConcurrentHashMap 查 + 1 次 put**（≈纳秒，无锁） |
| 两个不同租户首访并发 | 互相阻塞（全局锁） | **并行**（分段锁） |
| 同一租户首访并发 N 个 | 串行建 N 次? 否（有 containsKey 判断），但仍全局锁 | 串行 1 次建池，其余 double-check 命中 |
| 1 万租户稳态连接数 | 无上限 → 可能打满 MySQL | `maxPools×perPoolMaxActive`（如 200×5=1000）封顶 + 空闲回收 |
| 冷租户连接占用 | minIdle 默认 → 常驻 | `perPoolMinIdle=0` → 不占 |

**容量估算公式**：`稳态连接数 ≈ min(活跃租户数, maxPools) × 实时并发/租户`，上界 `maxPools × perPoolMaxActive`。
据此设置 MySQL `max_connections` 余量。

**压测验收指标**（落地阶段补基准）：
- 热路径 P99 相对单库基线增量 < 0.1ms；
- 1000 不同租户并发首访注册总耗时 ≈ 单租户建池耗时 × ceil(1000 / 注册线程并发度)，无锁竞争平台期；
- 稳态池数严格 ≤ `maxPools`。

---

## 8. 落地计划（分阶段，本 RFC 仅出设计）

| 阶段 | 内容 | 产物 |
|------|------|------|
| P0 | `TenantDataSourceProperties` + `TenantDataSourceMetaResolver` + `RegistryHolder`（no-op） | 配置 + 元数据，行为不变 |
| P1 | `TenantDataSourceRegistry`（ensureRegistered + 分段锁 + double-check）+ `push` 接入 | 运行时注册可用 |
| P2 | LRU 上限 + 空闲驱逐 + Nacos 下线钩子 | 容量治理 |
| P3 | Jasypt 解密 + `migrate-on-register` Flyway 对齐 | 安全 + 迁移 |
| P4 | 单测（分段锁并发、double-check、LRU 驱逐、fail-closed）+ Testcontainers 多库集成测试 + 压测基准 | 验收 |
| P5 | `dynamic.strict=true` 兜底 + `docs/conventions/multi-tenant-guide.md` 增补 DATASOURCE 章 + Nacos 模板 | 文档/默认值 |

回滚：`mate.tenant.datasource.enabled=false` 即回到现有静态预配行为；`mate.tenant.type=COLUMN` 完全旁路。

---

## 9. 后续演进（非本 RFC）

- 海量租户（>万级）：元数据来源从 Nacos 清单切换为 DB 表 + Caffeine 本地 TTL 缓存（回到 joolun 的 `sys_datasource` 思路，但带缓存与驱逐）。
- 读写分离：复用 baomidou `tenant_x` 主 + `tenant_x_slave` 从，`@DS` 或路由策略选从库。
- SCHEMA 模式：同实例 `USE db_x`，省去多池，适合中等租户数；可作为 DATASOURCE 的轻量替代。
- 租户库自动 DDL 建库：纳入 `TenantProvisionService` 开通流程（RFC-028 SaaS 生命周期）。

---

## 10. 待确认问题 (Open Questions)

1. **建库归属**：本 RFC 假设租户库已由开通流程建好，注册中枢只接入"已存在的库"。
   若希望"首访即建库"（joolun 行为），需把 `CREATE DATABASE` 纳入——但这会让数据面持有 DDL 权限，安全面变大。**建议保持分离**。
2. `migrate-on-register` 默认 `false`：首访不阻塞在 Flyway 上（迁移放开通流程）。是否需要"启动时预热常驻大租户池"开关？
3. 是否需要把 `master` 也纳入 LRU 统计豁免之外的健康检查？（当前 master 永远静态、豁免驱逐。）
4. 监控：建议暴露 `tenant_ds_active_pools`、`tenant_ds_register_total`、`tenant_ds_evict_total` 到 Micrometer/Prometheus（RFC-monitor）。落地阶段确认指标命名。

---

## 11. joolun 源码深读补遗与设计修正 (2026-06-15 二轮)

逐行读 joolun 实际源码（`TenantContextHolder` / `DynamicDataSourceUtils` /
`JdbcDynamicDataSourceProvider` / `MybatisPlusConfig` / `ShardingDataBaseProperties` /
`DynamicDataSourceAutoConfiguration` / `BaseTenantHandler` / `SysTenantMapper.xml`）后，
对前文做如下修正与补充。

### 11.1 对 joolun 的事实修正（前文不够准确处）

- **C1 建库时机**：前文 §10 Q1 暗示 joolun "首访即建库"。**错**。
  joolun 在 `addDataSources` 里**只建池、不建库**（假设库已存在）；真正的
  `CREATE DATABASE ..._{tenantId}` + 逐表 `CREATE TABLE ... LIKE`（克隆基库全表）
  发生在**租户开通**时 `SysTenantServiceImpl.initTenantDataBase` → `SysTenantMapper.createTenantDataBase`。
  删除租户时 `DROP TABLE sys_log_${id}` 等。**结论**：joolun 与本 RFC 的推荐一致——
  开通时建库、运行时只挂池。Q1 据此澄清：保持"建库属开通流程"是与 joolun 一致且更安全的选择，非分歧。
- **C2 租户库元数据来源**：前文对比表写 joolun "配置来源=`sys_datasource` 表"。**不精确**。
  joolun 有**两套互不相干**的机制：
  1. **租户分库**用 `ShardingDataBaseProperties`（`spring.datasource.sharding.*`）——
     单一 URL **模板** + **共享**一套 `dbusername/dbpassword`，库名 `StrUtil.format(url,{dbname}_{tenantId})`。
     即 joolun 的**租户分库其实是"纯模板派生 + 同账号同实例"**，并不读表。
  2. `sys_datasource` 表 + `JdbcDynamicDataSourceProvider` 是给 **`@DS` 注解**用的**业务数据源**
     （启动时加载、Jasypt 解密、master 恒在），与租户无关。
  → 本 RFC 的 Nacos 方案（template 派生 + tenants 显式覆盖）**比 joolun 的租户分库更灵活**
  （joolun 租户库无法用独立账号/独立实例；本 RFC 的 `tenants.<id>` 覆盖可以）。对比表已据此修订认知。

### 11.2 joolun 的真实硬伤（前文漏列或说轻了）

- **GAP-A 连接探活泄漏（新增，joolun 真 bug）**：`addDataSources` 与 `DynamicDataSourceUtils.checkDataSource`
  都用 `DriverManager.getConnection(url,user,pwd)` 探活，**却从不 close 这个 Connection**——
  每注册一个租户泄漏一条物理连接。本 RFC 决策：**注册时不做 DriverManager 探活**，
  交给池自身初始化/`testOnBorrow` 校验；若确需探活，必须 try-with-resources。（已在 §4.3 代码注释标注。）
- **GAP-B `@Synchronized` = 全局锁（前文已提，此处坐实）**：joolun `TenantContextHolder` 是
  `@UtilityClass`（成员全 static）+ `addDataSources` 上的 Lombok `@Synchronized` →
  锁的是类级 `$LOCK`，**所有租户首次注册全局串行**。本 RFC 的 per-tenant 分段锁规避之。
- **GAP-C 静态 `SpringContextHolder.getBean` 初始化**：joolun 在 `TenantContextHolder` 静态字段里
  `getBean(DataSource.class)` 等，依赖类加载时容器已就绪，初始化顺序脆弱。
  本 RFC 用 `TenantDataSourceRegistryHolder`（autoconfig 启动时 `set`）规避，但需注意：
  Holder 必须在第一个请求前完成 `set`（autoconfig bean 初始化即 set，OK）。

### 11.3 本 RFC 自身的遗漏（需补的技术点）

- **GAP-1 池实现类型（Druid vs Hikari）**：matecloud 全局排除 Druid 自动配置但仍用 Druid 池；
  joolun 的 `DefaultDataSourceCreator` 只塞了 `HikariDataSourceCreator`。
  本 RFC 落地时**必须确认 per-tenant 池用 Druid `DataSourceCreator`**（保持与主库一致的监控/慢 SQL），
  并把 `perPoolMaxActive/minIdle` 映射到 Druid 的 `maxActive/minIdle`（非 Hikari 的 `maximumPoolSize`）。
  依赖：`dynamic-datasource-spring-boot4-starter:4.5.0` + druid creator 在 classpath。
- **GAP-2 已并入 GAP-A**（探活泄漏）。
- **GAP-3 master 数据源的完整配置缺失**：前文 §4.1 只给了租户 template，没给 `master`。
  `dynamic.enabled=true` 时 mate-ds-starter 的单 Druid bean 退避、`DynamicRoutingDataSource` 接管，
  此时 **`master` 必须由 `spring.datasource.dynamic.datasource.master.*` 显式配置**，且
  `spring.datasource.dynamic.primary=master` 必须等于 `mate.tenant.default-ds-name`。补全配置样例：

  ```yaml
  spring:
    datasource:
      dynamic:
        enabled: true
        primary: master
        strict: true          # GAP-6：未注册 ds 名直接抛错，禁止静默回退 master
        datasource:
          master:
            url: jdbc:mysql://10.0.0.10:3306/mate_master?serverTimezone=Asia/Shanghai
            username: mate_app
            password: ENC(...)
            driver-class-name: com.mysql.cj.jdbc.Driver
  mate:
    tenant:
      type: DATASOURCE
      default-ds-name: master   # == dynamic.primary
  ```

- **GAP-4 per-tenant Flyway 接线错误（前文 bug）**：前文注入 `ObjectProvider<FlywayMigrationStrategy>`——
  **不可行**，该 strategy 绑定的是主库的自动配置 Flyway，对任意租户 DS 无效。
  正确做法：新增 `TenantSchemaMigrator`，对每个租户 DS 现建
  `Flyway.configure().dataSource(ds).table("flyway_history_<module>").locations(...)
  .baselineOnMigrate(true).load().migrate()`，**复用 RFC-041 的 per-service history 表命名**。
  代码已改为注入 `TenantSchemaMigrator`（§4.3/§4.6）。注意：这会让首访冷启动包含一次 migrate，
  故默认 `migrate-on-register=false`，迁移优先放开通流程（与 C1 一致）。
- **GAP-5 驱逐 vs 在途连接的竞态（前文说轻了）**：`removeDataSource` 关池时若有请求正在该池上查询，
  连接会被掐断。前文只说"确认无活跃借出连接否则跳过"，但 baomidou `removeDataSource` 不保证等待在途。
  **强化策略**：① LRU 选victim 时跳过"最近 `idleEvictMinutes` 或最近 N 秒内访问过"的池；
  ② 关池走 Druid 的优雅关闭（等待活跃连接归还，设超时）；③ 被驱逐租户下次访问自动懒重建——
  正确性不受影响，仅一次冷启动延迟。空闲驱逐天然避开热租户，竞态窗口极小但必须显式处理。
- **GAP-6 `strict=true` 兜底（前文仅在安全表一笔带过，提升为硬性默认）**：baomidou 默认 `strict=false`
  会把未知 ds 名静默路由到 primary。本 RFC 的 `ensureRegistered` fail-closed 是第一道；
  **`spring.datasource.dynamic.strict=true` 必须设为默认**作为第二道（万一有人绕过 helper 直接 push）。
- **GAP-7 DATASOURCE 模式无行级"安全网"（新增权衡）**：matecloud `MateTenantLineHandler.ignoreTable`
  在非 COLUMN 模式直接 `return true`——即 DATASOURCE 模式**关闭**了 `tenant_id` 行过滤。
  joolun 则**同时保留** TenantLine 行过滤 + 分库（双保险：路由失效仍有 WHERE 兜底）。
  代价是租户表都得带 `tenant_id` 列。本 RFC 默认沿用 matecloud 的纯路由（不强制列），
  但**新增可选项** `mate.tenant.defense-in-depth=true`：DATASOURCE 模式下仍叠加行过滤，
  供"一库多租户混存 + 怕路由 bug"的保守部署选用。
- **GAP-8 跨租户聚合查询的固有限制（新增已知限制）**：DATASOURCE 模式下，super/system 租户
  路由到 master，**只能看 master 的数据，无法一条 SQL 跨所有租户库聚合**（joolun 同限制）。
  平台级报表需"按租户 DS 扇出 + 应用层合并"或单独的数仓。本限制需在运维文档显式声明。
- **GAP-9 `@DS` 通用数据源切换（范围澄清）**：本 RFC 只解决**租户**多库。若业务需要显式切到
  具名业务库（如只读报表库），baomidou 的 `@DS("xxx")` 注解 + DsProcessor 链开箱即用
  （joolun 用 `DsJakartaHeader/Session/Spel + LastParam` 处理器链 + `sys_datasource` 表装载）。
  matecloud 现仅有 `TenantDataSourceHelper.executeWithDs` 编程式切换；如需注解式，
  另起小 RFC 接 `sys_datasource` 等价的业务数据源清单，不在本 RFC 范围。

### 11.4 lifecycle 差异（值得对照，非缺陷）

joolun `setTenantId` 内 `push` 但**不 poll**，靠 `ClearTtlDataSourceFilter`（order=`Integer.MIN_VALUE`，
请求前后各 `DynamicDataSourceContextHolder.clear()`）兜底清栈。matecloud 走 `push`/`poll` 成对
（`TenantDataSourceWebFilter` try-push / finally-poll），更精确、无需全局 clear。
本 RFC 沿用 matecloud 的成对模型，无需引入 ClearTtl 式过滤器。

### 11.5 修正后落地清单增量

- `TenantSchemaMigrator.java`（新增，per-tenant Flyway，复用 RFC-041 history 命名）。
- `TenantProperties` 增 `defenseInDepth`（GAP-7）；`MateTenantLineHandler.ignoreTable` 据此在
  DATASOURCE 模式可选不 ignore。
- Nacos `mate-tenant-datasource` 模板补 `master` 与 `dynamic.strict=true`（GAP-3/6）。
- Registry 注入 `DataSourceCreator`（Druid）而非 `DefaultDataSourceCreator` 泛指（GAP-1）。
- 驱逐策略加"近期访问保护窗 + Druid 优雅关池"（GAP-5）。

---

## 附：相对 RFC-012 的增量文件清单（落地时）

```
mate-starters/mate-tenant-starter/src/main/java/vip/mate/starter/tenant/datasource/
  ├── TenantDataSourceProperties.java        (新增)
  ├── TenantDataSourceMetaResolver.java      (新增)
  ├── TenantDataSourceRegistry.java          (新增，核心)
  ├── TenantDataSourceRegistryHolder.java    (新增，静态持有者，仿 TenantRuntime)
  ├── TenantDataSourceNotFoundException.java (新增)
  ├── TenantSchemaMigrator.java              (新增，per-tenant Flyway，§11 GAP-5)
  ├── TenantDataSourceHelper.java            (改：push 前 ensureRegistered)
  └── (TenantDataSourceWebFilter / TenantDubboProviderFilter 无需改，经由 helper)
mate-starters/mate-tenant-starter/.../TenantAutoConfiguration.java  (改：注册 Registry bean)
mate-starters/mate-tenant-starter/.../core/TenantProperties.java    (改：增 defenseInDepth，§11 GAP-7)
mate-starters/mate-tenant-starter/.../mybatis/MateTenantLineHandler.java (改：defenseInDepth 时 DATASOURCE 模式仍过滤)
docs/nacos-templates/mate-tenant-datasource-dev.yml                 (新增，含 master + dynamic.strict=true)
docs/conventions/multi-tenant-guide.md                              (改：DATASOURCE 章 + 跨租户聚合限制声明)
```
