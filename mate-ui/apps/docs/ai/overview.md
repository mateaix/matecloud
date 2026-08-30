# AI 集成

MateCloud 通过 `mate-ai-starter`（位于 `mate-starters-contrib`）提供原生 AI 集成，基于 Spring AI 2.0.1。

## 三大能力

### 1. @Tool 自动发现

任何 Spring Bean 方法标注 `@Tool` 即可被 LLM 自动调用：

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

### 2. 多提供商支持

一个环境变量切换 LLM：

| 提供商 | 环境变量 | 说明 |
|--------|---------|------|
| Anthropic（默认） | `ANTHROPIC_API_KEY` | Claude |
| 智谱 GLM | `OPENAI_API_KEY` + `MATE_AI_PROVIDER=openai` + 兼容端点 | GLM |
| DeepSeek | `DEEPSEEK_API_KEY` + `MATE_AI_PROVIDER=deepseek` | DeepSeek |
| OpenAI 兼容 | `OPENAI_API_KEY` + `OPENAI_BASE_URL` | Kimi / Moonshot 等 |
| MiniMax | `ANTHROPIC_API_KEY` + `MATE_AI_PROVIDER=anthropic` + 兼容端点 | MiniMax M2.x |
| Ollama | `OLLAMA_BASE_URL` + `MATE_AI_PROVIDER=ollama` | 本地部署 |

### 3. MCP Server 桥接

通过 `mate-cli --mcp` 或服务内置 MCP 传输，Claude Code / Claude Desktop 可以直接调用集群中的 `@Tool` 方法。

## 访问方式

| 入口 | 方式 |
|------|------|
| REST | `GET /api/v1/ai/tools`（列出）；`POST /api/v1/ai/tools/{name}/invoke`（调用） |
| 对话 | `POST /api/v1/ai/chat`（LLM 自动选工具） |
| 流式 | `POST /api/v1/ai/chat/stream`（SSE） |
| CLI | `mate ai tools` / `mate ai chat "..."` |
| MCP | `mate --mcp` + Claude Code |

## 会话记忆

`AiChatService` 通过 Spring AI 2.0.1 的 Advisor 链支持多轮对话：

```json
POST /api/v1/ai/chat
{
  "conversationId": "alice-session-1",
  "message": "What did I just ask you?"
}
```

传入相同的 `conversationId` 即可保持上下文。
