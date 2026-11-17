# 字问（Ziwen）· 伴读小伙伴

> 问一个字，问一首诗，陪娃学习。

陪娃写作业的 AI 伴读——"字问先生"像爸妈一样耐心。

这是 **LangChain4j 中文入门教程系列的配套项目**：一篇教程 = 一个产品能力 = 一个 git tag，`git checkout` 对应版本即可跟着写。当前版本 **v0.1.1**。

## 当前版本能做什么（v0.1.1）

| 能力 | 说明 |
|---|---|
| 有人设 | "字问先生"：耐心、讲解知识 |
| 有记性 | 多轮对话记得上下文（滑动窗口） |
| 会流式 | 回答逐 token 流式输出 |
| 不失忆 | 记忆落 Redis：重启服务对话还接得上，7 天不聊自动过期 |

## 演进路线

| 版本 | 功能 | 状态 |
|---|---|---|
| v0.1 | 人设聊天 + 多轮记忆 + SSE 流式 | ✅ |
| v0.1.1 | 对话记忆落 Redis（重启不失忆） | ✅ 当前 |
| v0.2 | 古诗 RAG（embedding + Qdrant + 引用来源） | ⬜ |
| v0.3 | Agentic RAG（@Tool、该查才查、拒答） | ⬜ |
| v0.4 | 生字表结构化查询 | ⬜ |
| v0.5 | 多 Agent 路由 + 多用户记忆隔离 | ⬜ |
| v0.6 | 听写模式、错词本、评测、一键部署 | ⬜ |

## 快速开始

环境要求：JDK 21、Maven 3.9+、Docker（起 Redis）、任一 OpenAI 兼容模型的 API Key（DeepSeek / 通义千问 / OneAPI 均可）。

```bash
# 1. 配置模型（OpenAI 兼容协议，任选一家）
export AI_CHAT_BASE_URL=https://api.deepseek.com/v1
export AI_CHAT_API_KEY=sk-xxx
export AI_CHAT_MODEL=deepseek-v4-pro

# 2. 起 Redis（对话记忆存这里）
docker compose up -d

# 3. 启动
mvn spring-boot:run
```

打开 http://localhost:8082 ，问一句试试：

```
你：灌溉的"灌"怎么写？
AI：左边三点水，右边是"雚"，组词"灌溉"。你想记口诀吗？
你：它哪一课学的呀？
AI：这个我要去查一查你的课本，先不瞎说哦～
```

## 接口

| 接口 | 说明 |
|---|---|
| `GET /` | 内置聊天页面 |
| `POST /api/chat` | 同步问答，body：`{"sessionId": "...", "question": "..."}` |
| `GET /api/chat/stream?sessionId=...&question=...` | SSE 流式问答，逐 token 推送 |

接口调试样例在 [http/](http/) 目录，VSCode 装 REST Client 扩展后，逐条点 Send Request 即可。

## 技术栈

Java 21 · Spring Boot 3.5 · LangChain4j 1.19 · Redis（对话记忆） · Maven。

## License

MIT，详见 [LICENSE](LICENSE)。


