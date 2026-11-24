# 字问（Ziwen）· 伴读小伙伴

> 问一个字，问一首诗，陪娃学习。

陪娃写作业的 AI 伴读——"字问先生"像爸妈一样耐心。

这是 **LangChain4j 中文入门教程系列的配套项目**：一篇教程 = 一个产品能力 = 一个 git tag，`git checkout` 对应版本即可跟着写。当前版本 **v0.3.2**。

## 当前版本能做什么（v0.3.2）

| 能力 | 说明 |
|---|---|
| 有人设 | "字问先生"：耐心、讲解知识 |
| 有记性 | 多轮对话记得上下文（滑动窗口） |
| 会流式 | 回答逐 token 流式输出 |
| 不失忆 | 记忆落 Redis：重启服务对话还接得上，7 天不聊自动过期 |
| 会背诗 | 古诗 RAG：问"二年级写瀑布的诗"给全诗 + 题目作者 + 哪课学的 |
| 不重算 | 向量落 Qdrant：古诗向量化一次就存住，重启不用重算，启动更快、不花重复的钱 |
| 有分寸 | Agentic RAG：模型自己决定查不查古诗书——闲聊直接答不翻书，问诗翻书并带出处 |
| 不硬编 | 拒答有阈值兜底：相似度低于 ai.rag.min-score 的命中直接丢，书里没有的诗老实说"没有"，不拿最像的一首顶上 |
| 有刹车 | 工具循环护栏：单轮调用超过 ai.tool.max-calls-per-turn 次回劝停文本，模型拿已查到的结果收尾而不是报错；框架保险丝同步收紧（100 圈 → 10 圈） |
| 不干等 | 工具执行的白屏期有动静：SSE 具名 status 事件推"字问先生正在翻古诗书…"，首个 token 一到自动撤掉 |

## 演进路线

| 版本 | 功能 | 状态 |
|---|---|---|
| v0.1 | 人设聊天 + 多轮记忆 + SSE 流式 | ✅ |
| v0.1.1 | 对话记忆落 Redis（重启不失忆） | ✅ |
| v0.2 | 古诗 RAG（embedding + 内存向量库 + 引用来源） | ✅ |
| v0.2.1 | 向量库落 Qdrant（重启不重嵌入） | ✅ |
| v0.3 | Agentic RAG（@Tool、该查才查、拒答） | ✅ |
| v0.3.1 | 拒答做硬（相似度阈值、工具入参防御、结构化日志） | ✅ |
| v0.3.2 | 工具循环护栏（软闸限次、保险丝收紧、status 状态推送、记忆窗口放宽到 30 条） | ✅ 当前 |
| v0.4 | 生字表结构化查询 | ⬜ |
| v0.5 | 多 Agent 路由 + 多用户记忆隔离 | ⬜ |
| v0.6 | 听写模式、错词本、评测、一键部署 | ⬜ |

## 快速开始

环境要求：JDK 21、Maven 3.9+、Docker（起 Redis 和 Qdrant）、两把 API Key——chat 一把（DeepSeek / 通义千问 / OneAPI 均可）、embedding 一把（v0.2 起，通义千问 DashScope）。

```bash
# 1. 配置模型：chat 一家、embedding 一家（都是 OpenAI 兼容协议）
export AI_CHAT_BASE_URL=https://api.deepseek.com/v1
export AI_CHAT_API_KEY=sk-xxx
export AI_CHAT_MODEL=deepseek-v4-pro
export AI_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export AI_EMBEDDING_API_KEY=sk-xxx
export AI_EMBEDDING_MODEL=text-embedding-v3

# 2. 起外部依赖：Redis（对话记忆）+ Qdrant（古诗向量库，v0.2.1 起）
docker compose up -d

# 3. 启动（首次会把 data/poems.json 向量化写入 Qdrant；之后重启日志显示"跳过嵌入"，不再重嵌）
mvn spring-boot:run
```

打开 http://localhost:8082 ，问一句试试：

```
你：二年级那首写瀑布的诗，背给我听？
AI：《望庐山瀑布》，李白写的，你们二年级上第 8 课学的——
    日照香炉生紫烟，遥看瀑布挂前川。飞流直下三千尺，疑是银河落九天。
你：帮我背一下《将进酒》
AI：我的古诗书里还没有这首哦。
```

## 接口

| 接口 | 说明 |
|---|---|
| `GET /` | 内置聊天页面 |
| `POST /api/chat` | 同步问答，body：`{"sessionId": "...", "question": "..."}` |
| `GET /api/chat/stream?sessionId=...&question=...` | SSE 流式问答，逐 token 推送；检索期间另推具名 status 事件 |
| `GET /api/poems` | 古诗库全量列表 |
| `GET /api/poems/search?question=...` | 裸检索：不调 chat 模型，返回命中的古诗 + 相似度得分；低于 `ai.rag.min-score` 的命中被过滤，空结果即"书里没有" |

接口调试样例在 [http/](http/) 目录，VSCode 装 REST Client 扩展后，逐条点 Send Request 即可。

## 技术栈

Java 21 · Spring Boot 3.5 · LangChain4j 1.19 · Redis（对话记忆） · Qdrant（古诗向量库） · 通义千问 embedding · Maven。

## License

MIT，详见 [LICENSE](LICENSE)。


