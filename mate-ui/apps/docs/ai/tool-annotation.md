# @Tool 注解

`@Tool` 是 Spring AI 2.0.1 提供的注解，用于将任意 Spring Bean 方法暴露为 AI 可调用的工具。

## 基本用法

```java
@Component
public class UserAiTools {

    @Tool(description = "查询指定状态的用户数量")
    public long countUsersByStatus(
            @ToolParam(description = "用户状态：ACTIVE 或 DISABLED")
            String status) {
        return userRepository.countByStatus(UserStatus.valueOf(status));
    }
}
```

## 注解说明

### @Tool

| 属性 | 类型 | 说明 |
|------|------|------|
| name | `String` | 工具名称；留空时使用方法名，名称必须全局唯一 |
| description | `String` | 工具描述（LLM 用来决定何时调用） |
| returnDirect | `boolean` | 为 `true` 时将工具结果直接返回调用方，不再让模型继续生成 |
| resultConverter | `Class` | 自定义工具返回值序列化器 |

### @ToolParam

| 属性 | 类型 | 说明 |
|------|------|------|
| description | `String` | 参数描述（LLM 用来理解如何传参） |

## 最佳实践

### 描述要清晰

LLM 根据 `description` 决定何时调用你的工具。描述应当准确说明：
- 这个工具**做什么**
- **什么时候**应该使用它
- 参数的**取值范围**

```java
// ✅ 好的描述
@Tool(description = "List all dict entries for a given dictType. "
    + "Use this when the user asks about dictionary or config values.")

// ❌ 模糊的描述
@Tool(description = "Get dict data")
```

### 返回结构化数据

返回有意义的对象，LLM 会自动序列化为 JSON：

```java
@Tool(description = "Get user profile by username")
public UserProfile getUserProfile(@ToolParam(description = "Username") String username) {
    return userQueryService.findByUsername(username);
}
```

### 避免副作用

Tool 方法应尽量是只读查询。如果涉及写操作，在描述中明确说明。

## 工具注册流程

1. Spring 容器扫描到 `@Tool` 标注的方法
2. `mate-ai-starter` 的 `AiToolRegistry` 收集所有工具
3. 通过 REST API（`/api/v1/ai/tools`）对外暴露
4. `AiChatService` 在对话时将工具列表传给 LLM
5. LLM 返回 tool_call 时，框架自动调用对应方法

Spring AI 2.0.1 默认只允许执行本次请求显式附着的工具。MateCloud 通过
`defaultTools(AiToolRegistry)` 显式附着 registry，并保持全局 fallback 关闭。
重复工具名会在应用启动时直接报错，避免运行时随机选择实现。
