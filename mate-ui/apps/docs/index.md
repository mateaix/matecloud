---
layout: home

hero:
  name: MateCloud
  text: AI 原生的云原生微服务脚手架
  tagline: DDD 工程底座 · Spring Boot 4 / Spring Cloud 2025 / Dubbo 3 / Spring AI 2.0.1 —— 即插即用 Starter、MCP 原生工程闭环（Loop Engineering），单体与微服务双形态一键交付。
  actions:
    - theme: brand
      text: 快速开始
      link: /guide/quick-start
    - theme: alt
      text: GitHub
      link: https://github.com/matevip/matecloud

features:
  - icon:
      src: /feature/network.svg
      width: 24
      height: 24
    title: 微服务 · 单体双形态
    details: Spring Cloud 2025 + Dubbo 3 RPC + Nacos 注册配置；mate-monolith 单体模式一键切换；灰度路由 · 限流熔断 · Seata 分布式事务
  - icon:
      src: /feature/layers.svg
      width: 24
      height: 24
    title: DDD 四层架构
    details: trigger / application / domain / infrastructure，Domain 层零框架依赖，CQRS 读写分离
  - icon:
      src: /feature/blocks.svg
      width: 24
      height: 24
    title: 27 个即插即用 Starter
    details: 18 核心 + 9 高级：持久化、缓存、锁、MQ、任务、分片、租户、安全、可观测、AI——引入依赖即生效
  - icon:
      src: /feature/sparkles.svg
      width: 24
      height: 24
    title: AI 原生集成
    details: Spring AI 2.0.1 · @Tool 自动发现 · 4 个原生 Provider 与兼容端点 · 会话记忆 · 流式对话，领域方法一键成为 AI 可调用工具
  - icon:
      src: /feature/loop.svg
      width: 24
      height: 24
    title: MCP 原生 · 工程闭环 (Loop Engineering)
    details: mate --mcp 把 CLI 与业务 @Tool 暴露为 MCP 工具，让 AI Agent 在「观察 → 推理 → 执行 → 反馈」闭环中操作集群——查服务、调 RPC、生成代码、迁移数据库
  - icon:
      src: /feature/terminal.svg
      width: 24
      height: 24
    title: mate-cli 全栈管控
    details: 一条命令脚手架 DDD 模块、逆向生成代码，服务 / RPC / 缓存 / 数据库调试，Nacos 配置、健康检查、内置 MCP Server
  - icon:
      src: /feature/layout.svg
      width: 24
      height: 24
    title: Vue 3 管理前端
    details: pnpm monorepo + Vite + Element Plus + TypeScript，开箱即用的 CRUD、权限、字典
  - icon:
      src: /feature/building.svg
      width: 24
      height: 24
    title: 多租户开箱即用
    details: 行级隔离 / Schema 隔离 / 独立数据源三种模式，租户套餐管理，fail-closed 安全策略
  - icon:
      src: /feature/activity.svg
      width: 24
      height: 24
    title: 可观测 · 生产就绪
    details: Actuator + Prometheus + Micrometer 链路追踪；@ApiSign @RateLimit @AuditLog @Idempotent @DataPermission 安全注解开箱即用
---
