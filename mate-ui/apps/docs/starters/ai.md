# mate-ai-starter

AI 集成 Starter，基于 Spring AI 2.0.1，详见 [AI 集成](/ai/overview)。

## 快速启用

1. 引入依赖：

```xml
<dependency>
    <groupId>vip.mate</groupId>
    <artifactId>mate-ai-starter</artifactId>
</dependency>
```

2. 配置 API Key：

```bash
export ANTHROPIC_API_KEY=sk-ant-xxx
```

3. 使用 `@Tool` 暴露领域方法：

```java
@Component
public class DictAiTools {
    @Tool(description = "List all dict entries for a given dictType.")
    public List<DictData> listDictByType(
            @ToolParam(description = "Dict type code") String dictType) {
        return dictQueryService.findByType(dictType);
    }
}
```

详见 [AI 集成文档](/ai/overview)。
