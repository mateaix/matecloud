# 项目结构

```
matecloud/
├── mate-common/                  # 纯类型库（零自动配置）
│   ├── mate-base/                # BaseEntity, Result, BizException, ErrorCode, Snowflake
│   └── mate-api/                 # RPC 接口、命令、响应、枚举
│
├── mate-starters/                # 13 个即插即用 Starter（7 核心 + 6 业务）
│   ├── mate-ds-starter/          # MyBatis Plus + Druid + Flyway
│   ├── mate-web-starter/         # 全局异常处理 + Jackson + DevTools
│   ├── mate-cache-starter/       # Caffeine L1 + Redis L2 + @DistributedLock + 雪花 ID
│   ├── mate-nacos-starter/       # Nacos 注册发现 + 配置中心
│   ├── mate-rpc-starter/         # Dubbo + DubboExceptionFilter
│   ├── mate-sa-token-starter/    # Sa-Token（Servlet + Reactor）
│   ├── mate-monitor-starter/     # Actuator + Prometheus + Tracing
│   ├── mate-mq-starter/          # RabbitMQ + 延迟队列 + @DelayQueueListener
│   ├── mate-job-starter/         # XXL-Job 执行器
│   ├── mate-security-starter/    # @ApiSign @RateLimit @AuditLog @DataPermission @Idempotent
│   ├── mate-file-starter/        # MinIO 上传/下载/预签名 URL
│   ├── mate-excel-starter/       # @ExcelExport / @ExcelImport (EasyExcel)
│   └── mate-tenant-starter/      # 多租户（行级/Schema/独立数据源）
│
├── mate-starters-contrib/        # 8 个高级 Starter（按需引入）
│   ├── mate-seata-starter/       # 分布式事务
│   ├── mate-sharding-starter/    # ShardingSphere 2库×4表 哈希分片
│   ├── mate-sentinel-starter/    # Sentinel + Nacos 动态规则
│   ├── mate-gray-starter/        # 灰度发布（Dubbo + Gateway）
│   ├── mate-flow-starter/        # 轻量工作流引擎
│   ├── mate-rule-starter/        # Aviator 规则引擎
│   ├── mate-ai-starter/          # Spring AI 2.0.1 + @Tool + MCP
│   └── mate-test-starter/        # Testcontainers + @MateTest
│
├── mate-gateway/                 # API 网关（WebFlux, 9010）
├── mate-auth/                    # 认证服务（9020）
├── mate-cli/                     # CLI 工具（Picocli + MCP）
├── mate-biz/
│   ├── mate-system/              # 系统管理（9030, DDD 示范模块）
│   └── mate-notice/              # 通知服务（9050）
└── mate-ui/                      # Vue 3 前端
    ├── apps/admin/               # 管理后台 SPA
    └── packages/                 # 共享包（core, hooks, ui, utils）
```

## 模块职责

### mate-common

纯类型定义，**不包含任何自动配置**。

- **mate-base**：基础类型——`BaseEntity`、`Result`、`BizException`、`ErrorCode` 枚举、`SnowflakeUtil`
- **mate-api**：跨服务共享的 DTO、Dubbo RPC 接口定义、命令对象、枚举

### mate-starters

每个 Starter 封装一个横切关注点，引入 Maven 依赖即自动生效。详见 [Starter 指南](/starters/overview)。

### mate-biz

业务模块目录。每个模块独立部署，严格遵循 [DDD 四层架构](/architecture/ddd)。

### mate-ui

Vue 3 前端，详见 [前端开发](/frontend/overview)。

### mate-cli

命令行工具，详见 [CLI 工具](/cli/overview)。
