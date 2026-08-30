<p align="center">
  <img src="mate-ui/apps/admin/src/assets/matecloud-logo.png" alt="MateCloud" height="64" />
</p>

<p align="center">
 <img src="https://img.shields.io/badge/MateCloud-5.0.8-success.svg" alt="MateCloud">
 <img src="https://img.shields.io/badge/Spring%20Boot-4.0.7-blue.svg" alt="Spring Boot">
 <img src="https://img.shields.io/badge/Spring%20Cloud-2025.1-blue.svg" alt="Spring Cloud">
 <img src="https://img.shields.io/badge/Spring%20AI-2.0-blue.svg" alt="Spring AI">
 <img src="https://img.shields.io/badge/Vue-3.5-blue.svg" alt="Vue">
 <img src="https://img.shields.io/badge/Java-21-orange.svg" alt="Java">
 <img src="https://img.shields.io/github/license/matevip/matecloud" alt="License">
 <img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs Welcome">
</p>

## 系统说明

- **MateCloud** 是 **AI 原生 · 云原生**的 DDD 微服务脚手架，基于 **Spring Boot 4 + Spring Cloud 2025 + Dubbo 3 + Spring AI 2.0.1**，单体（`mate-monolith`）与微服务**双形态一键切换**。
- 开源版含 **网关 / 认证 / 系统管理 / 通知** 四个核心服务 + **27 个即插即用 Starter（18 核心 + 9 高级）**，完整展示 DDD 四层 + CQRS 读写分离。
- **AI 原生**：Spring AI 2.0.1 + `@Tool` 自动发现 + 会话记忆 + 流式对话，内置 Anthropic / OpenAI / DeepSeek / Ollama，并通过兼容端点支持智谱与 MiniMax。
- **MCP 原生工程闭环（Loop Engineering）**：`mate --mcp` 把 `mate-cli` 与业务 `@Tool` 暴露为 MCP 工具，让 AI Agent 在「观察 → 推理 → 执行 → 反馈」闭环中操作集群——查服务、调 RPC、生成代码、迁移数据库。
- 认证基于 **Sa-Token**（密码 / 短信 / 验证码登录）；**Docker Compose** 一键编排，`mate-cli` 提供脚手架、Nacos 配置、服务发现、健康检查与代码生成。

**设计理念：** 最小公共、各司其职、Starter = 即插即用能力。

## 架构全景

<p align="center">
  <img src="docs/matecloud-architecture.png" alt="MateCloud 架构全景" width="900" />
</p>

接入层 → 网关（:9010）→ 业务服务（auth / system / notice，Dubbo RPC + DDD 四层）→ 能力层 Starter → 基础设施（MySQL / Redis / RabbitMQ / Nacos / MinIO），可观测性（Actuator / Prometheus / Tracing）横切。详见 [总体架构文档](mate-ui/apps/docs/architecture/overview.md)。

## 界面预览

> 真实运行截图（前端 admin `:3000` + 后端网关 `:9010`），基于 mate-ui 设计系统。

<table>
  <tr>
    <td align="center" width="33%"><img src="docs/screenshots/dashboard.png" alt="工作台"/><br/><sub><b>工作台 · 概览 / 服务健康</b></sub></td>
    <td align="center" width="33%"><img src="docs/screenshots/menus.png" alt="菜单管理"/><br/><sub><b>菜单管理 · RBAC 菜单树</b></sub></td>
    <td align="center" width="33%"><img src="docs/screenshots/roles.png" alt="角色管理"/><br/><sub><b>角色管理 · 菜单 / 数据权限</b></sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/admins.png" alt="管理员"/><br/><sub><b>管理员 · 分配角色</b></sub></td>
    <td align="center"><img src="docs/screenshots/dict.png" alt="数据字典"/><br/><sub><b>数据字典 · 类型 / 数据</b></sub></td>
    <td align="center"><img src="docs/screenshots/config.png" alt="参数配置"/><br/><sub><b>参数配置 · 系统键值</b></sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/identity.png" alt="身份接入"/><br/><sub><b>身份接入 · 企微/钉钉/飞书/LDAP</b></sub></td>
    <td align="center"><img src="docs/screenshots/gray-release.png" alt="灰度发布"/><br/><sub><b>微服务 · 灰度发布 / 路由测试</b></sub></td>
    <td align="center"><img src="docs/screenshots/login-log.png" alt="登录审计"/><br/><sub><b>登录审计 · 日志 / UA / 状态</b></sub></td>
  </tr>
</table>

## 使用文档

MateCloud 提供了完整的部署与开发文档，涵盖架构设计、Starter 使用、CLI 命令、前端开发和生产部署等内容。

```bash
cd mate-ui && pnpm dev:docs    # → http://localhost:5174
```

## 快速开始

### 基础环境

- JDK 21+
- Maven 3.9+
- Docker & Docker Compose
- Node.js 20+（运行 `mate-ui` 前端时需要）

### 启动服务

在项目根目录执行完整编译，再构建并启动本地服务栈：

```bash
# 1. 编译
mvn clean install -DskipTests

# 2. 启动基础设施（MySQL + Redis + RabbitMQ + Nacos + MinIO）
make infra-up

# 3. 初始化 Nacos 配置
java -jar mate-cli/target/mate-cli.jar config init

# 4. 启动所有服务
make up
```

服务启动后，通过网关端口 `9010` 访问后端接口，Nacos 控制台端口为 `8848`。

> **数据库自动初始化**：无需手动建表。`mate-system` 首次启动时，Flyway 自动执行
> `db/migration/` 下的迁移（`V1.0.0` 建表 → `V1.0.1` 种子数据 → ……），完成建表、
> 内置账号 `admin/admin123`、角色、菜单与字典的初始化。每个服务有独立的版本历史表
> `flyway_history_<module>`。详见 [数据库迁移](mate-ui/apps/docs/deploy/database.md) 与
> [菜单配置](mate-ui/apps/docs/guide/menu-config.md)。

### 启动前端

```bash
cd mate-ui
pnpm install
pnpm dev                       # → http://localhost:3000
```

默认开发账号：`admin` / `admin123`

## 核心依赖

| 依赖 | 版本 |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.0.8 |
| Spring Cloud | 2025.1.2 |
| Spring Cloud Alibaba | 2025.1.0.0 |
| Spring AI | 2.0.1 |
| Dubbo | 3.3.6 |
| MyBatis Plus | 3.5.16 |
| Sa-Token | 1.45.0 |
| Redisson | 4.5.0 |
| Vue | 3.5 |
| Element Plus | latest |
| Vite | latest |

## 功能特性

| 功能 | 说明 |
| --- | --- |
| **DDD 四层架构** | trigger / application / domain / infrastructure，领域层零框架依赖 |
| **CQRS 读写分离** | CommandService 写 + QueryService 读，职责清晰 |
| **微服务 · 单体双形态** | Spring Cloud + Dubbo 3 RPC + Nacos；`mate-monolith` 单体一键切换；灰度 · 限流 · Seata |
| **27 个 Starter** | 18 核心 + 9 高级：持久化、缓存、锁、MQ、任务、分片、租户、安全、可观测、AI、测试…… |
| **多租户 SaaS** | 行级隔离 / Schema 隔离 / 独立数据源三种模式 |
| **AI 原生集成** | Spring AI 2.0.1 + @Tool 自动发现 + 会话记忆 + 流式对话 + 4 个原生 Provider 与兼容端点 |
| **MCP 原生工程闭环** | `mate --mcp` + 业务 @Tool 暴露为 MCP 工具，AI Agent 闭环操作集群（Loop Engineering）|
| **分布式锁** | `@DistributedLock` 注解，Redisson 实现 |
| **接口签名** | `@ApiSign` 防篡改，HMAC-SHA256 签名验证 |
| **限流降级** | `@RateLimit` 注解限流 + Sentinel 动态规则 |
| **审计日志** | `@AuditLog` 自动记录操作日志 |
| **数据权限** | `@DataPermission` 注解式行级数据过滤 |
| **幂等控制** | `@Idempotent` 防重复提交 |
| **灰度发布** | Dubbo + Gateway 双链路灰度路由 |
| **代码生成** | CLI 一键生成 DDD 四层代码 + Flyway 迁移 |
| **MCP Server** | `mate-cli --mcp`，Claude Code / Claude Desktop 直接调用业务工具 |

## 模块说明

```lua
mate-ui  -- Vue 3 前端 (pnpm monorepo)
  ├── apps/admin                    -- 管理后台 SPA [3000]
  ├── apps/docs                     -- VitePress 文档站 [5174]
  └── packages/                     -- 共享包 (core, hooks, ui, utils)

matecloud
├── mate-common                     -- 纯类型库（零自动配置）
│   ├── mate-base                   -- BaseEntity, Result, BizException, ErrorCode
│   └── mate-api                    -- RPC 接口、命令、响应、枚举
├── mate-starters                   -- 13 个即插即用 Starter（7 核心 + 6 业务）
│   ├── mate-ds-starter             -- MyBatis Plus + Druid + Flyway
│   ├── mate-web-starter            -- 全局异常 + Jackson + DevTools
│   ├── mate-cache-starter          -- Caffeine L1 + Redis L2 + @DistributedLock
│   ├── mate-nacos-starter          -- Nacos 注册发现 + 配置中心
│   ├── mate-rpc-starter            -- Dubbo RPC
│   ├── mate-sa-token-starter       -- Sa-Token（Servlet + Reactor）
│   ├── mate-monitor-starter        -- Actuator + Prometheus + Tracing
│   ├── mate-mq-starter             -- RabbitMQ + 延迟队列
│   ├── mate-job-starter            -- XXL-Job 执行器
│   ├── mate-security-starter       -- @ApiSign @RateLimit @AuditLog @Idempotent
│   ├── mate-file-starter           -- MinIO 上传/下载/预签名
│   ├── mate-excel-starter          -- EasyExcel 导入导出
│   └── mate-tenant-starter         -- 多租户（行级/Schema/独立数据源）
├── mate-starters-contrib           -- 8 个高级 Starter（按需引入）
│   ├── mate-seata-starter          -- 分布式事务
│   ├── mate-sharding-starter       -- ShardingSphere 分库分表
│   ├── mate-sentinel-starter       -- Sentinel 限流降级
│   ├── mate-gray-starter           -- 灰度发布
│   ├── mate-flow-starter           -- 轻量工作流引擎
│   ├── mate-rule-starter           -- Aviator 规则引擎
│   ├── mate-ai-starter             -- Spring AI 2.0.1 + @Tool + MCP
│   └── mate-test-starter           -- Testcontainers + @MateTest
├── mate-gateway                    -- API 网关 [9010]
├── mate-auth                       -- 认证服务 [9020]
├── mate-cli                        -- CLI 工具 + MCP Server
└── mate-biz
    ├── mate-system                 -- 系统管理（DDD 示范 + RBAC + 字典 + AI）[9030]
    └── mate-notice                 -- 通知服务（短信/邮件适配器）[9050]
```

## 配置说明

- MateCloud 使用 **5 层配置**策略，服务的 `application.yml` 只需约 15 行。
- 环境变量 → Nacos 服务级配置 → Nacos 共享配置 → classpath 默认值 → 服务入口文件，优先级从高到低。
- 默认数据库脚本通过 Flyway 自动迁移，业务表前缀 `mate_`；每服务独立版本线（`flyway_history_<module>`），详见 [数据库迁移文档](mate-ui/apps/docs/deploy/database.md)。
- 包名统一为 `vip.mate.*`，Starter 包名 `vip.mate.starter.*`。
- 详细配置策略参见 [`docs/conventions/config-strategy.md`](docs/conventions/config-strategy.md)。

## AI 集成

`mate-ai-starter` 封装 Spring AI 2.0.1，提供三大能力：

1. **`@Tool` 自动发现** — 任何 Spring Bean 方法标注 `@Tool` 即可被 LLM 调用
2. **多提供商支持** — 一个环境变量切换 Anthropic / OpenAI / DeepSeek / Ollama；智谱与 MiniMax 使用兼容端点
3. **MCP Server 桥接** — Claude Code / Claude Desktop 可以直接调用集群中的 `@Tool` 方法

```java
@Component
@RequiredArgsConstructor
public class DictAiTools {

    private final IDictQueryService dictQueryService;

    @Tool(description = "List all dict entries for a given dictType.")
    public List<DictData> listDictByType(
            @ToolParam(description = "Dict type code, e.g. 'user_status'")
            String dictType) {
        return dictQueryService.findByType(dictType);
    }
}
```

```bash
# CLI 直接对话
java -jar mate-cli/target/mate-cli.jar ai chat "列出所有用户状态字典"

# 启动 MCP Server（集成 Claude Code）
java -jar mate-cli/target/mate-cli.jar --mcp
```

## CLI 工具

```bash
mate new module mate-order --port 9060    # 新建业务模块（DDD 四层骨架）
mate new aggregate Order --module mate-order  # 新建聚合
mate service list                         # Nacos 注册的服务
mate service health                       # 健康检查
mate config init                          # 初始化 Nacos 共享配置
mate gen code --table mate_order          # 代码生成
mate ai chat "..."                        # AI 对话
mate --mcp                                # 启动 MCP Server
```

## 开源共建

### 开源协议

MateCloud 开源软件遵循 [Apache 2.0 协议](https://www.apache.org/licenses/LICENSE-2.0.html)，允许商业使用，但务必保留类作者、Copyright 信息。

### 其他说明

1. 欢迎提交 [PR](https://github.com/matevip/matecloud/pulls)，请基于 `dev` 分支提交，详见 [贡献指南](CONTRIBUTING.md)。
2. 欢迎提交 [Issue](https://github.com/matevip/matecloud/issues)，请写清楚问题现象、开发环境和复现步骤。
3. 本项目遵循 [Contributor Covenant 行为准则](CODE_OF_CONDUCT.md)。
4. 安全漏洞请参阅 [安全策略](SECURITY.md) — **不要**创建公开 Issue。
