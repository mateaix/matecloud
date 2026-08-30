# Starter 总览

MateCloud 提供 **21 个 Starter**（13 标准 + 8 高级），覆盖微服务开发的各个横切关注点。

## 设计理念

每个 Starter 遵循 Spring Boot 自动配置规范：
- 引入 Maven 依赖即自动生效
- 通过 `mate.*` 配置项控制行为
- 可以通过 `@ConditionalOnProperty` 关闭

## 核心 Starter（7 个）

通常每个业务模块都会引入：

| Starter | 提供的能力 |
|---------|-----------|
| [mate-ds-starter](/starters/ds) | MyBatis Plus + Druid 连接池 + Flyway 数据库迁移 |
| [mate-web-starter](/starters/web) | REST 支持 + 全局异常处理 + Jackson 配置 + DevTools |
| [mate-cache-starter](/starters/cache) | Caffeine L1 + Redis L2 二级缓存 + `@DistributedLock` + 雪花 ID |
| mate-nacos-starter | Nacos 服务注册发现 + 配置中心 |
| mate-rpc-starter | Dubbo RPC + DubboExceptionFilter |
| [mate-sa-token-starter](/starters/sa-token) | Sa-Token 认证鉴权（Servlet + Reactor 双模式） |
| mate-monitor-starter | Actuator + Prometheus + Micrometer Tracing |

## 业务 Starter（6 个）

按需引入：

| Starter | 提供的能力 |
|---------|-----------|
| [mate-mq-starter](/starters/mq) | RabbitMQ + 延迟队列 + `@DelayQueueListener` + 领域事件桥接 |
| mate-job-starter | XXL-Job 执行器自动注册 |
| [mate-security-starter](/starters/security) | `@ApiSign` `@RateLimit` `@AuditLog` `@DataPermission` `@Idempotent` |
| [mate-file-starter](/starters/file) | MinIO 上传/下载/预签名 URL |
| mate-excel-starter | `@ExcelExport` / `@ExcelImport`（基于 EasyExcel） |
| [mate-tenant-starter](/starters/tenant) | 多租户隔离（行级/Schema/独立数据源） |

## 高级 Starter（8 个，mate-starters-contrib）

特定场景使用：

| Starter | 提供的能力 |
|---------|-----------|
| mate-seata-starter | 分布式事务（Seata AT 模式） |
| mate-sharding-starter | ShardingSphere 分库分表（2 库 × 4 表哈希） |
| mate-sentinel-starter | Sentinel 流量防护 + Nacos 动态规则 |
| mate-gray-starter | 灰度发布（Dubbo + Gateway 双维度） |
| mate-flow-starter | 轻量工作流引擎 |
| mate-rule-starter | Aviator 规则引擎 |
| [mate-ai-starter](/starters/ai) | Spring AI 2.0.1 + `@Tool` + MCP |
| mate-test-starter | Testcontainers + `@MateTest` / `@MateIntegrationTest` |

## 引入方式

在业务模块的 `pom.xml` 中添加依赖即可：

```xml
<dependency>
    <groupId>vip.mate</groupId>
    <artifactId>mate-cache-starter</artifactId>
</dependency>
```

版本号由父 POM 统一管理，无需指定。
