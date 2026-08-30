# 简介

MateCloud 是一套基于 **DDD（领域驱动设计）** 的微服务脚手架，采用当前主流的 Java 技术栈：

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 虚拟线程、模式匹配 |
| Spring Boot | 4.0.8 | 基础框架 |
| Spring Cloud | 2025.1.2 | 云原生抽象 |
| Spring Cloud Alibaba | 2025.1.0.0 | Nacos、Sentinel、Seata |
| Spring AI | 2.0.1 | LLM 客户端、@Tool、MCP |
| Dubbo | 3.3.6 | RPC 框架 |
| MyBatis Plus | 3.5.16 | ORM |
| Sa-Token | 1.45.0 | 认证鉴权 |
| Redisson | 3.52.0 | Redis 客户端、分布式锁 |

## 设计理念

**最小公共、各司其职、Starter = 即插即用能力。**

- **mate-common** 只放纯类型（DTO、枚举、异常），零自动配置
- **mate-starters** 每个 starter 解决一个横切关注点，引入即生效
- **业务模块** 严格遵循 DDD 四层，Domain 层零框架依赖
- **CLI 工具** 一条命令完成脚手架、配置、服务管理、AI 对话

## 适用场景

- 需要 DDD 架构参考的企业级微服务项目
- 希望快速搭建 Spring Cloud 微服务集群的团队
- 需要 AI 能力集成的后台管理系统
- 学习 DDD + 微服务 + Spring AI 的开发者

## 开源协议

MateCloud 基于 [Apache License 2.0](https://github.com/matevip/matecloud/blob/main/LICENSE) 开源。
