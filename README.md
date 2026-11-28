# 字问（Ziwen）· 伴读小伙伴

> 问一个字，问一首诗，陪娃学习。

陪娃写作业的 AI 伴读——"字问先生"像爸妈一样耐心。

这是 **LangChain4j 中文入门教程系列的配套项目**：一篇教程 = 一个产品能力 = 一个 git tag，`git checkout` 对应版本即可跟着写。当前版本 **v0.5.1**。

## 当前版本能做什么（v0.5.1）

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
| 懂生字 | 生字表结构化查询：问"灌字哪课学的"秒答第几课第几页，要"第 2 课预习字表"按课列出——向量答不了的精确事实，走 SQLite 白名单查询 |
| 分得清 | 多用户隔离：家长账号下挂娃、娃名下再挂多个聊天窗口，记忆键是"家长:娃:窗口"三段——同娃多窗口、老大老二、跨家庭各聊各的，请求参数冒充不出别家的键 |
| 会分诊 | 多 Agent 路由：每轮先分诊再分发——背诗问背诗分身（只挂古诗工具）、查字问查字分身（只挂生字表工具），其余落回全能兜底；误分诊与分诊调用失败都落兜底，不放大成整轮失败；代价是每轮多一次分诊调用 |

## 演进路线

| 版本 | 功能 | 状态 |
|---|---|---|
| v0.1 | 人设聊天 + 多轮记忆 + SSE 流式 | ✅ |
| v0.1.1 | 对话记忆落 Redis（重启不失忆） | ✅ |
| v0.2 | 古诗 RAG（embedding + 内存向量库 + 引用来源） | ✅ |
| v0.2.1 | 向量库落 Qdrant（重启不重嵌入） | ✅ |
| v0.3 | Agentic RAG（@Tool、该查才查、拒答） | ✅ |
| v0.3.1 | 拒答做硬（相似度阈值、工具入参防御、结构化日志） | ✅ |
| v0.3.2 | 工具循环护栏（软闸限次、保险丝收紧、status 状态推送、记忆窗口放宽到 30 条） | ✅ |
| v0.4 | 生字表结构化查询（SQLite、只读白名单查询、"字→课"与"课→字表"两个工具） | ✅ |
| v0.5 | 多用户隔离 + 家长注册（记忆键 parentId:childId:windowId、多窗口、轻量认证、儿童信息最小化） | ✅ |
| v0.5.1 | 多 Agent 路由与任务分解（RouterAgent 分诊、背诗/查字专才、全工具兜底含分诊失败降级） | ✅ 当前 |
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

# 3. 启动（首次会把 data/poems.json 向量化写入 Qdrant，之后重启日志显示"跳过嵌入"，不再重嵌；
#    生字表在 SQLite 单文件库里，无需容器，首次启动自动建库 ziwen.sqlite.db 并灌入种子数据；
#    种子只录了二年级上前 4 课，换自家课本在 src/main/resources/data.sql 补录即可）
mvn spring-boot:run
```

打开 http://localhost:8082 ，先注册一个家长账号（用户名 + 密码），给娃建个档案（只要一个昵称），然后问一句试试：

```
你：二年级那首写瀑布的诗，背给我听？
AI：《望庐山瀑布》，李白写的，你们二年级上第 8 课学的——
    日照香炉生紫烟，遥看瀑布挂前川。飞流直下三千尺，疑是银河落九天。
你："灌"字是哪一课学的？
AI：第 2 课《我是什么》学的，在第 6 页——灌，guàn，20 画，三点水旁。
你：帮我背一下《将进酒》
AI：我的古诗书里还没有这首哦。
```

## 接口

v0.5 起除注册/登录/注销外全部要求登录：token 放在登录响应的 data 里，前端存 localStorage，
此后每个请求以 `Authorization: Bearer` 头带回，校验不过返回 401。

| 接口 | 说明 |
|---|---|
| `GET /` | 内置对话页面（未登录自动跳转独立登录页 login.html） |
| `POST /api/auth/register` | 家长注册，body：`{"username": "...", "password": "..."}`，成功即在响应里返回 token |
| `POST /api/auth/login` / `POST /api/auth/logout` | 登录 / 注销（注销即删 Redis 里的 token） |
| `GET /api/children` / `POST /api/children` | 列出 / 新建娃档案（娃只有昵称一个字段） |
| `GET /api/children/{childId}/windows` / `POST 同路径` | 列出 / 新开聊天窗口：一个娃可开多个，窗口间记忆互不串 |
| `DELETE /api/children/{childId}/windows/{windowId}` | 删除窗口：SQLite 删窗口行，连带清空其对话记忆 |
| `DELETE /api/children/{childId}` | 删除娃：SQLite 删档案与名下全部窗口，连带清空各窗口的对话记忆 |
| `POST /api/chat` | 同步问答，body：`{"windowId": 1, "question": "..."}`，记忆键由家长 id + 娃 id + 窗口 id 在服务端拼成 |
| `GET /api/chat/stream?windowId=...&question=...` | SSE 流式问答，逐 token 推送；工具执行期间另推具名 status 事件 |
| `DELETE /api/chat/memory/{windowId}` | 清空该窗口的对话记忆（从 Redis 删，窗口档案不动） |
| `GET /api/poems` | 古诗库全量列表 |
| `GET /api/poems/search?question=...` | 裸检索：不调 chat 模型，返回命中的古诗 + 相似度得分；低于 `ai.rag.min-score` 的命中被过滤，空结果即"书里没有" |

接口调试样例在 [http/](http/) 目录，VSCode 装 REST Client 扩展后，逐条点 Send Request 即可（登录响应脚本把 token 存进变量，同文件内后续请求自动带上）。

## 技术栈

Java 21 · Spring Boot 3.5 · LangChain4j 1.19 · Redis（对话记忆 + 登录 token） · Qdrant（古诗向量库） · SQLite（生字表 + 用户档案） · 通义千问 embedding · Maven。

## License

MIT，详见 [LICENSE](LICENSE)。


