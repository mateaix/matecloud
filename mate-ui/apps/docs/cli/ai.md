# AI 命令

## 查看 LLM 提供商

```bash
java -jar mate-cli.jar ai providers
```

显示所有支持的 LLM 提供商和配置方式。

## 查看 AI 工具

```bash
java -jar mate-cli.jar ai tools
```

列出集群中所有服务暴露的 `@Tool` 方法。

## AI 对话

```bash
# 自然语言查询——AI 自动选择调用哪个 @Tool
java -jar mate-cli.jar ai chat "今天注册了多少用户?"

# 指定服务
java -jar mate-cli.jar ai chat "查一下 user_status 字典" --service mate-system
```

AI 会自动：
1. 解析你的问题
2. 在集群中找到匹配的 `@Tool` 方法
3. 调用工具获取结果
4. 用自然语言回复

## 切换 LLM 提供商

```bash
# Claude（默认）
export ANTHROPIC_API_KEY=sk-ant-xxx

# 智谱 GLM
export MATE_AI_PROVIDER=openai
export OPENAI_API_KEY=...
export OPENAI_BASE_URL=https://open.bigmodel.cn/api/paas/v4
export OPENAI_MODEL=glm-5

# DeepSeek
export MATE_AI_PROVIDER=deepseek
export DEEPSEEK_API_KEY=...
```

详见 [LLM 提供商](/ai/providers)。
