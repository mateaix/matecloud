# LLM 提供商

MateCloud 内置 Anthropic、OpenAI、DeepSeek、Ollama 四个 Spring AI provider，
智谱 GLM 和 MiniMax 分别通过 OpenAI、Anthropic 兼容端点接入。

## Anthropic Claude（默认）

```bash
export ANTHROPIC_API_KEY=sk-ant-xxx
```

## 智谱 GLM（OpenAI 兼容）

```bash
export MATE_AI_PROVIDER=openai
export OPENAI_API_KEY=your-key
export OPENAI_BASE_URL=https://open.bigmodel.cn/api/paas/v4
export OPENAI_MODEL=glm-5
```

## DeepSeek

```bash
export MATE_AI_PROVIDER=deepseek
export DEEPSEEK_API_KEY=your-key
```

## OpenAI 兼容（Kimi / Moonshot 等）

```bash
export MATE_AI_PROVIDER=openai
export OPENAI_API_KEY=your-key
export OPENAI_BASE_URL=https://api.moonshot.cn
export OPENAI_MODEL=moonshot-v1-32k
```

## MiniMax（Anthropic 兼容）

```bash
export MATE_AI_PROVIDER=anthropic
export ANTHROPIC_API_KEY=your-key
export ANTHROPIC_BASE_URL=https://api.minimax.io/anthropic
export ANTHROPIC_MODEL=MiniMax-M2.7
```

## Ollama（本地部署）

```bash
export MATE_AI_PROVIDER=ollama
export OLLAMA_BASE_URL=http://127.0.0.1:11434
export OLLAMA_MODEL=qwen2.5:latest
```

## 查看配置

```bash
java -jar mate-cli.jar ai providers
```

此命令显示所有可用提供商及其当前配置状态。
