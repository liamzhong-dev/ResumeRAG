<div align="center">

**ResumeRAG** — 面向校招/秋招场景的 AI 面试辅助平台

简历解析与评分 · RAG 知识库问答 · Skill 驱动模拟面试 · MCP 邮件自动通知

[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0-6DB33F?logo=spring)](https://spring.io/projects/spring-ai)
[![React](https://img.shields.io/badge/React-18.3-blue?logo=react)](https://react.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-336791?logo=postgresql)](https://www.postgresql.org/)
[![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-black)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-MIT-blue)](./LICENSE)
[![CI](https://github.com/liamzhong-dev/ResumeRAG/actions/workflows/ci.yml/badge.svg)](https://github.com/liamzhong-dev/ResumeRAG/actions/workflows/ci.yml)

**仓库**：<https://github.com/liamzhong-dev/ResumeRAG>

</div>

---

## 项目定位

本项目**参考 [InterviewGuide](https://github.com/Snailclimb/interview-guide) 的基础架构**，在其之上做了三处自研增强。

保留上游的整体骨架：Spring Boot + Spring AI + PostgreSQL/pgvector + Redis Stream + Apache Tika 的技术选型，以及简历 / 模拟面试 / 知识库 / 面试安排 / 语音面试 / 多模型配置的模块划分。

自研增强集中在三块：

| 增强点 | 上游状态 | 本项目的实现 |
| --- | --- | --- |
| RAG 检索策略 | 固定 TopK + 固定阈值，无检索质量度量 | Query Rewrite + 按 Query 长度动态的 TopK/minScore 分档 + 双路召回融合开关，配套 40 条标注样本的**可复现测评 harness** 与分块/改写 ablation 报告 |
| MCP 协议业务集成 | 无 | 自建 MCP 客户端（JSON-RPC 2.0 over Streamable HTTP），把企业邮件服务封装成工具调用，Java 侧零耦合 |
| 邮件自动通知 | 无 | 双评分（LLM 评分 + 规则评分）达标自动通知 HR，含幂等建单、退避重投、送达率指标 |

上游未实现而本项目补齐的部分，全部集中在 `app/src/main/java/interview/guide/modules/notification/`（新增模块）与 `modules/knowledgebase/service/KnowledgeBaseQueryService.java`（检索策略改造）。

---

## 技术栈

### 后端

| 技术 | 版本 | 说明 |
| --- | --- | --- |
| Java | 25 | 虚拟线程（`spring.threads.virtual.enabled=true`），适配 AI 调用与 SSE 长连接 |
| Spring Boot | 4.1.0 | 应用框架 |
| Spring AI | 2.0.0 | ChatClient、结构化输出、TokenTextSplitter、pgvector VectorStore |
| Spring AI Agent Utils | 0.10.0 | Skill 资源加载、Advisor 扩展 |
| **MCP（Model Context Protocol）** | 2025-06-18 | 自建 JSON-RPC 2.0 客户端，接入企业邮件服务 |
| PostgreSQL + pgvector | 16 | 关系数据 + 向量存储，1024 维、HNSW、余弦距离 |
| Redis + Redisson | 6+ / 4.0.0 | 会话缓存 + Redis Stream 异步任务队列 |
| Apache Tika | 2.9.2 | PDF / DOCX / DOC / TXT 多格式简历解析 |
| iText 8 | 8.0.5 | 简历分析报告与面试评估报告 PDF 导出 |
| MapStruct / Flyway / SpringDoc | 1.6.3 / - / 3.0.2 | 对象映射 / schema 版本化 / OpenAPI 文档 |
| DashScope SDK | 2.22.7 | Qwen3 ASR / TTS 实时语音面试 |
| Micrometer + Prometheus | - | RAG 阶段耗时、通知送达等业务指标 |
| Gradle | 9.6.1 | 构建工具 |

### AI 与前端

| 组件 | 说明 |
| --- | --- |
| 文本模型 | 阿里百炼 `qwen3.5-flash`（OpenAI 兼容模式接入） |
| Embedding | `text-embedding-v3`，**1024 维** |
| 语音 | `qwen3-asr-flash-realtime` / `qwen3-tts-flash-realtime`（WebSocket） |
| 对象存储 | S3 兼容（RustFS / MinIO） |
| 前端 | React 18.3 + TypeScript 5.6 + Vite 5.4 + Tailwind CSS 4.1 + Framer Motion + Recharts |

---

## 六项核心工作

### 1. Tika 多格式简历解析与噪声清洗

**思路**：简历正文的质量直接决定后面 LLM 评分和 RAG 的效果，而 Tika 的默认行为会把大量非正文内容带进来。这里做了两层处理：

- **解析层**：`AutoDetectParser` + `BodyContentHandler`（5MB 上限）只取正文；通过 `ParseContext` 注入 `NoOpEmbeddedDocumentExtractor` 禁用嵌入资源提取；`PDFParserConfig` 关闭 `extractInlineImages` 并开启 `sortByPosition` 修复多栏 PDF 的阅读顺序。
- **清洗层**：解析结果再过一遍语义级去噪，去掉图片文件名行、图片 URL、`file:` 协议临时路径、符号分隔线、控制字符，然后统一换行与空行。

**关键代码**：

| 文件 | 作用 |
| --- | --- |
| `infrastructure/file/DocumentParseService.java` | `parseContent(InputStream)`：解析器与 ParseContext 装配（第 129–160 行） |
| `infrastructure/file/NoOpEmbeddedDocumentExtractor.java` | 空实现，阻断 Tika 对图片/附件的二次解析 |
| `infrastructure/file/TextCleaningService.java` | `cleanText()`：5 类预编译正则 + 格式规范化（第 80–105 行） |
| `infrastructure/file/DocumentParseConfiguration.java` | 解析专用有界线程池（默认 2 线程 / 队列 20 / 2 分钟超时） |

**为什么要禁用图片提取**：Tika 默认会把 PDF 内嵌图片交给 `EmbeddedDocumentExtractor` 处理，输出里混入 `image1.png`、`file:/tmp/apache-tika-xxx` 之类的噪声行。这些字符串进到 LLM prompt 里既污染上下文又白烧 token，在 RAG 场景还会被当成正文向量化。

**结果**：PDF / DOCX / DOC / TXT 四类格式的解析输出中不再出现图片文件名行与临时文件路径；解析走独立有界线程池，单份文件超时 2 分钟即失败，不会拖垮业务线程池。

---

### 2. Redis Stream 异步解耦长耗时任务

**思路**：简历分析、知识库向量化、面试评估、知识库出题这四类任务都要调 LLM，单次调用是**秒到十几秒**量级。同步阻塞接口会把这个耗时直接暴露给用户，且请求堆积时线程池会被打满。统一改成：

- 接口侧只做「落库 + 投递 Stream」，立刻返回任务状态；
- 消费者侧串行消费，用 `AbstractStreamProducer` / `AbstractStreamConsumer` 两个模板类收敛发送、ACK、重试、失败兜底的骨架；
- **条件领取 + 代次 fencing**：`PENDING → PROCESSING` 用带 `attemptId` 的条件更新做原子领取，多实例部署时只有领取成功的消费者执行任务；心跳与终态写入都必须匹配同一 `attemptId`；
- **恢复调度器**：定时扫描超过阈值的 `PENDING` / `PROCESSING` 任务补投，避免消息丢失后任务永久卡住。

**关键代码**：

| 文件 | 作用 |
| --- | --- |
| `common/async/AbstractStreamProducer.java` / `AbstractStreamConsumer.java` | 生产者/消费者模板，统一入队、领取、重试、失败处理 |
| `modules/resume/listener/AnalyzeStreamConsumer.java` | 简历分析消费者（条件领取 + 心跳 + 终态写入） |
| `modules/knowledgebase/listener/VectorizeStreamConsumer.java` | 知识库向量化消费者 |
| `modules/resume/listener/ResumeAnalysisRecoveryScheduler.java` | 卡住任务恢复调度 |
| `resources/db/migration/V20260905__task_recovery_fields.sql`、`V20260906__task_attempt_fencing.sql` | 恢复字段与代次围栏 |

**结果**：瓶颈定位有实测依据——同一套链路上 LLM 生成阶段 P50 18.7s / P95 29.9s（见文末性能表）。同步模型下接口响应时间就是这个量级；改成异步后接口只做入库与投递，响应时间降到 ~200ms 量级，接口侧吞吐相应提升约 **70 倍**（单实例本地自测，复现方式见「数据口径说明」）。

---

### 3. pgvector + 智能分块 + 1024 维向量 + HNSW 索引

**思路**：知识库问答的底座。四个决策点：

1. **向量库选型**：PG 自带向量能力就够用，不额外引入专用向量数据库，少一个运维组件。
2. **schema 交给 Flyway**：`spring.ai.vectorstore.pgvector.initialize-schema=false`，避免应用启动时绕过迁移改表结构。扩展与索引在 `V20260723` 里用幂等 DDL 建：`vector(1024)` + `USING hnsw (embedding vector_cosine_ops)`，`dimensions=1024`、`distance-type=COSINE_DISTANCE` 与 `text-embedding-v3` 对齐。
3. **智能分块**：`TokenTextSplitter`，目标 800 token、单块最小 350 字符、按中英文句末标点（`。？！；\n`）优先切分、保留分隔符；低于 5 字符的碎片不参与 Embedding。分块参数全部外置为配置，并写入向量 metadata 的 `chunk_strategy` 便于排查混用。
4. **写入的原子性**：向量化先写 `pending:{kbId}:{jobId}` 的临时 `kb_id`，全部批次成功后由 `promoteVectorJob` 一次性切换为正式 `kb_id`，失败按 `jobId` 清理——避免重新向量化过程中旧数据已删、新数据没写完导致知识库"空窗"。

**关键代码**：

| 文件 | 作用 |
| --- | --- |
| `resources/db/migration/V20260723__ensure_pgvector_store.sql` | 建扩展、`vector(1024)` 表、HNSW 余弦索引 |
| `modules/knowledgebase/service/KnowledgeBaseVectorService.java` | 分块 → 分批 Embedding（批大小 10，对齐 DashScope 限制）→ 临时 kb_id → promote |
| `modules/knowledgebase/service/KnowledgeBaseVectorProperties.java` | 分块参数与校验（第 26–107 行） |

**结果**：40 条样本、2 篇文档共 44 个 chunk 的测评集上，向量检索 P50 **161ms** / P95 **207ms**（真实实测，报告见 `app/src/test/resources/rag-eval/baselines/`）。分块大小的 ablation 结论见下一节。

---

### 4. Query Rewrite 与动态 TopK / minScore 自适应

**思路**：固定 TopK 和阈值的检索策略在长短 Query 上表现是矛盾的——短 Query（"G1 停顿？"）语义稀疏，需要放宽召回；长 Query 自带足够信息，TopK 给大了只会引入弱相关噪声。做法是：

- **按 Query 长度分档**（`resolveSearchParams`）：去空白后 ≤4 字 → TopK 20 / minScore 0.18；≤12 字 → TopK 12 / 0.28；更长 → TopK 8 / 0.28。
- **Query Rewrite**：用 LLM 结合最近 10 条历史把口语化、指代不清的问题改写成完整检索 Query；改写失败、返回空、或与原句相同都自动降级回原始 Query，不影响可用性。
- **候选串串行降级 + 可选双路融合**：先检索改写 Query，无命中再退原始 Query；`mergeOriginalQuery` 打开时两路各检索一次，按 Document ID 去重取高分、稳定排序后融合。
- **全程埋点**：改写/检索/生成三段耗时与命中数走 `RagMetrics`，改写失败原因（disabled/blank/unchanged/error）单独计数。

**关键代码**：

| 文件 | 作用 |
| --- | --- |
| `modules/knowledgebase/service/KnowledgeBaseQueryService.java` | `resolveSearchParams()`（第 600–609 行）、`rewriteQuestion()`（第 612–646 行）、`retrieveAndMerge()`（第 533–568 行） |
| `modules/knowledgebase/service/KnowledgeBaseQueryProperties.java` | 三档 TopK / minScore 与开关 |
| `modules/knowledgebase/metrics/RagMetrics.java` | `app.rag.*` 指标 |
| `app/src/test/java/interview/guide/rag/` | 40 条标注样本的测评 harness |

**A/B 实测（同一份 40 条样本、同一套 fixture、同一模型）**：

| 配置 | Hit@K | MRR | EvidenceRecall@K | 检索 P50 | 改写 P50 | 端到端 P50 |
| --- | --- | --- | --- | --- | --- | --- |
| 基线（关闭改写） | 96.88% | 0.6818 | 0.9531 | 161ms | - | 18.9s |
| 仅开启 Query Rewrite | 93.75% | **0.7081** | 0.9219 | 154ms | 36.6s | 66.0s |
| 改写 + 双路融合 | 96.88% | 0.6961 | 0.9531 | 292ms | 34.5s | 57.0s |

**按题型拆开看，结论才成立**：

| 题型 | 基线 MRR | 开启改写后 MRR | 变化 |
| --- | --- | --- | --- |
| 多轮上下文题 | 0.6319 | **0.9167** | **+45%** |
| 直接事实题 | 0.6521 | 0.6521 | 持平 |
| 同义改写题 | 0.6083 | 0.5000 | **−18%** |

这是本项目最有价值的一条结论：**Query Rewrite 不是全局有效**。它对"Query 依赖上文指代"的题型收益显著（MRR +45%），对同义改写题反而有害（口语化问题被改写成书面语后，与文档的口语表述反而不匹配）。因此默认开启但保留 `app.ai.rag.rewrite.enabled` 开关与失败降级，双路融合作为召回补回手段（`mergeOriginalQuery`）默认关闭。

**分块策略 ablation**（同一测评集）：

| chunkSize | chunk 数 | Hit@K | MRR | EvidenceRecall@K |
| --- | --- | --- | --- | --- |
| 400 | 89 | 90.63% | **0.7333** | 0.8906 |
| 800（默认） | 44 | 87.50% | 0.6964 | 0.8281 |
| 1200 | 30 | **93.75%** | 0.5951 | 0.9063 |

小块 MRR 更好（定位更准）、大块召回更好（上下文更完整），默认取 800 作为折中。

> ⚠️ **必须说明的置信度问题**：同一配置（chunkSize=800、关闭改写）两次独立 run 的 Hit@K 分别是 96.88% 和 87.50%，差 9 个百分点。40 条样本下 Hit@K 的置信区间很宽，上表结论应作为**方向性判断**而非精确定量。拿更硬的结论需要扩样本集，已列入后续规划。

---

### 5. MCP 集成企业邮件服务 + 双评分 ≥90 自动通知

**思路**：候选人筛选的最后一环是"通知 HR"，这一步要解决的不是"怎么发邮件"，而是**什么时候该发**、以及**不能重复发、不能丢**。

- **MCP 层**：自己实现了 MCP 客户端（`initialize` / `tools/list` / `tools/call`，JSON-RPC 2.0 over Streamable HTTP）。业务侧只见 `McpMailService.send(to, subject, text)`，换邮件服务商只需换 MCP Server 和工具名，Java 侧零改动——这是引入 MCP 协议而不是直接写 SMTP 的原因。
- **双评分判定**：只看 LLM 评分不够。同一份简历重复分析，LLM 可能给出 88 和 93 两个结果，单点阈值会让通知边界抖动。所以加了第二路**规则评分**（确定性函数，可复现）：技能关键词命中率 / 工作年限 / 学历 / 项目密度 / 结构完整度五维加权。**两路同时 ≥90 才发**。
- **幂等与可靠投递**：`(biz_type, biz_id)` 唯一索引做数据库兜底，建单时若已 `SENT` 直接跳过；失败按 2s / 4s 退避重投，最多 3 次；投递结果落 `notification_tasks` 表并打 `app.notification.delivery` 指标。
- **旁路隔离**：通知挂在简历分析消费者之后，异常吞掉只记日志，不影响分析主流程的完成态。

**关键代码**：

| 文件 | 作用 |
| --- | --- |
| `modules/notification/mcp/McpProtocolClient.java` | MCP JSON-RPC 2.0 客户端（initialize / tools.list / tools.call） |
| `modules/notification/mcp/McpMailService.java` | 邮件工具适配层 + 送达指标 + 异常降级 |
| `modules/notification/service/ResumeRuleScoringService.java` | 规则评分（五维加权，权重全部可配） |
| `modules/notification/service/NotificationDecisionService.java` | 双评分阈值判定 |
| `modules/notification/service/NotificationDispatchService.java` | 幂等建单、退避重投、状态机 |
| `modules/notification/model/NotificationTaskEntity.java` | 通知任务表实体 |
| `resources/db/migration/V20260918__add_notification_tasks.sql` | 表与唯一索引 |
| `modules/resume/listener/AnalyzeStreamConsumer.java` | 分析完成后的旁路触发点 |

**开启方式**（默认关闭，未配置时整条链路降级为 `SKIPPED`，不产生任何副作用）：

```bash
APP_NOTIFICATION_ENABLED=true
APP_NOTIFICATION_RECIPIENT=hr@example.com
APP_NOTIFICATION_MCP_ENDPOINT=http://localhost:8931/mcp
APP_NOTIFICATION_MCP_TOOL_NAME=send_email
APP_NOTIFICATION_MIN_RESUME_SCORE=90
APP_NOTIFICATION_MIN_RULE_SCORE=90
```

**结果**：双评分口径下通知边界稳定（规则分是确定性函数，不受模型抖动影响）；投递结果全部可观测（`notification_tasks.status` + `app_notification_delivery_total{result=sent|failed}`）；送达率数据见文末性能表。

---

### 6. Skill 标签驱动个性化模拟面试

**思路**：模拟面试的质量取决于"考什么、按什么比例考、用什么标准评"。把这部分从代码里抽出去，做成可配置的 Skill 资源：

- 每个面试方向一个目录：`SKILL.md`（YAML front matter 定义 persona）+ `skill.meta.yml`（分类清单：key / label / priority / ref / shared）。
- 启动时扫描 `classpath:skills/*/SKILL.md` 建注册表，并构建全局 `category → reference` 索引；`_shared/references` 下 20 篇共享知识（Java、Spring、MySQL、Redis、MQ、分布式、系统设计、算法等）供各方向复用。
- **题量配额三阶段分配**：`ALWAYS_ONE` 保底各 1 题 → 所有类目各 1 题（`CORE` 优先）保证覆盖率 → 剩余名额按 `CORE` 优先轮转。
- **JD 解析生成自定义 Skill**：粘贴 JD 由 LLM 提取考察方向，模型返回的 `ref` 会用本地 `categoryRefIndex` 纠正（防止模型编造不存在的参考文件），并对路径做白名单校验防目录穿越。
- **字符预算**：references 注入按场景分级——出题 12000 字符、评估 6000 字符、单文件 3000 字符，超出截断并标注，避免撑爆上下文。

**关键代码**：

| 文件 | 作用 |
| --- | --- |
| `modules/interview/skill/InterviewSkillService.java` | 注册表加载、`calculateAllocation()`（第 248–315 行）、references 注入与预算、`parseJd()` |
| `resources/skills/*/SKILL.md`、`skill.meta.yml` | 10 个预设方向：Java 后端 / 阿里后端 / 字节后端 / 腾讯 Java / 前端 / Python 后端 / 算法 / 系统设计 / 测开 / AI Agent |
| `resources/skills/_shared/references/` | 20 篇共享参考知识 |

---

## 数据口径说明

README 里所有数字都标注了来源，避免"看起来很能打但一问就露馅"：

| 数字 | 口径 | 能否复现 |
| --- | --- | --- |
| 检索 P50 161ms / P95 207ms；Hit@K、MRR、Recall 各值；分块与改写 ablation 全部数值 | 仓库自带测评 harness 实测，原始报告在 `app/src/test/resources/rag-eval/baselines/` | ✅ `RUN_RAG_EVAL=true ./gradlew :app:ragEvaluation` |
| LLM 生成 P50 18.7s / P95 29.9s | 同上，来自 baseline 报告的 `generationMsP50` | ✅ |
| 异步化后接口 ~200ms、吞吐 ~70 倍 | 单实例本地自测，未随仓库附压测脚本 | ⚠️ 建议补一组 wrk/JMeter 报告放进 `docs/` |
| 邮件通知送达率 98% | 本地自测（3 次重投 + 2s/4s 退避），原始记录未入库 | ⚠️ 复现：批量投递后用 `app_notification_delivery_total` 与 `notification_tasks` 统计，把报告放进 `docs/` |
| 解析输出不再含噪声行 | 单测覆盖（`TextCleaningServiceTest`） | ✅ `./gradlew :app:test` |

---

## 项目结构

```
ResumeRAG/
├── app/                              # 后端应用
│   ├── src/main/java/interview/guide/
│   │   ├── App.java
│   │   ├── common/                   # 通用能力
│   │   │   ├── ai/                   # LLM Provider 注册、结构化输出、Prompt 安全
│   │   │   ├── annotation/ + aspect/ # @RateLimit 可重复限流 + Redis Lua
│   │   │   ├── async/                # Redis Stream 生产者/消费者模板  ← 核心工作 2
│   │   │   ├── config/               # CORS、S3、OpenAPI、Jackson
│   │   │   ├── evaluation/           # 文字/语音共用的统一评估引擎
│   │   │   └── exception/ result/    # 错误码、统一响应
│   │   ├── infrastructure/           # 基础设施
│   │   │   ├── file/                 # Tika 解析、清洗、校验、S3 存储  ← 核心工作 1
│   │   │   ├── export/               # iText PDF 导出
│   │   │   ├── mapper/               # MapStruct
│   │   │   └── redis/                # RedisService、面试会话缓存
│   │   └── modules/
│   │       ├── resume/               # 简历模块（上传、解析、评分、Stream 消费）
│   │       ├── knowledgebase/        # 知识库 + RAG 检索 + 题库  ← 核心工作 3、4
│   │       ├── interview/            # 模拟面试 + Skill 驱动出题  ← 核心工作 6
│   │       ├── interviewschedule/    # 面试安排（邀请解析 + 日历）
│   │       ├── voiceinterview/       # 语音面试（WebSocket + Qwen3 ASR/TTS）
│   │       ├── llmprovider/          # 多模型 Provider 与语音配置
│   │       └── notification/         # ★ 新增：MCP 邮件通知  ← 核心工作 5
│   │           ├── mcp/              # MCP JSON-RPC 客户端 + 邮件适配层
│   │           ├── service/          # 规则评分、双评分判定、派发
│   │           └── model/ repository/ dto/ controller/ metrics/
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── prompts/                  # StringTemplate 提示词模板
│   │   ├── skills/                   # 面试 Skill 定义与共享 references
│   │   └── db/migration/             # Flyway 迁移（含 pgvector、通知表）
│   └── src/test/
│       ├── java/interview/guide/rag/ # RAG 测评 harness
│       └── resources/rag-eval/       # 40 条标注样本 + 6 份 baseline 报告
├── frontend/                         # React 18 + TypeScript 前端
├── docker-compose.yml                # 完整部署
├── docker-compose.dev.yml            # 本地依赖（PostgreSQL + Redis + RustFS）
└── .env.example
```

---

## 本地启动

### 环境要求

| 依赖 | 版本 | 必需 |
| --- | --- | --- |
| JDK | 25 | 是 |
| Node.js | 18+ | 是（前端） |
| pnpm | 10+ | 推荐 |
| Docker | - | 推荐（一键起依赖） |

### 步骤

```bash
# 0. 克隆仓库
git clone https://github.com/liamzhong-dev/ResumeRAG.git
cd ResumeRAG

# 1. 配置环境变量（最少填 AI_BAILIAN_API_KEY）
cp .env.example .env

# 2. 启动依赖：PostgreSQL(pgvector) + Redis + RustFS
docker compose -f docker-compose.dev.yml up -d

# 3. 启动后端（Flyway 会自动建表，含 vector_store 与 HNSW 索引）
./gradlew :app:bootRun          # Windows: .\gradlew.bat :app:bootRun

# 4. 启动前端
cd frontend && corepack enable && pnpm install && pnpm dev
```

后端 `http://localhost:8080`，前端 `http://localhost:5173`，接口文档 `http://localhost:8080/swagger-ui.html`。

Windows PowerShell 下若日志中文乱码，先执行：

```powershell
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
```

### 可选：开启 MCP 邮件通知

```bash
# .env 中追加（默认关闭，不配则整条链路自动降级为 SKIPPED）
APP_NOTIFICATION_ENABLED=true
APP_NOTIFICATION_RECIPIENT=hr@example.com
APP_NOTIFICATION_MCP_ENDPOINT=http://localhost:8931/mcp
```

自检 MCP 连通性：

```bash
curl http://localhost:8080/api/notifications/mcp/tools
```

### 测试

```bash
./gradlew :app:test                                                 # 单元测试（不含 rag-eval）
RUN_RAG_EVAL=true REDIS_DATABASE=1 ./gradlew :app:ragEvaluation     # RAG 测评（会调用付费 API）
```

---

## 核心接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/resume/upload` | 上传简历，入队异步分析，立即返回 |
| GET | `/api/resume/{id}` | 查询简历分析详情与评分 |
| GET | `/api/resume/list` | 简历列表与状态（待分析/分析中/已完成/失败） |
| GET | `/api/resume/{id}/export` | 导出 PDF 简历分析报告 |
| POST | `/api/knowledge-base/upload` | 上传知识库文档，入队异步向量化 |
| POST | `/api/knowledge-base/query` | RAG 问答（同步） |
| GET | `/api/knowledge-base/query/stream` | RAG 问答（SSE 流式） |
| POST | `/api/interview/sessions` | 创建模拟面试会话（Skill 或自定义 JD） |
| POST | `/api/interview/sessions/{id}/answers` | 提交回答，触发追问/下一题 |
| GET | `/api/interview/skills` | 列出所有面试 Skill 方向 |
| POST | `/api/interview/skills/parse-jd` | JD 解析生成自定义考察方向 |
| GET | `/api/notifications` | 通知任务列表（含双评分与状态） |
| POST | `/api/notifications/resume/{resumeId}/dispatch` | 手动触发通知判定与投递 |
| POST | `/api/notifications/{id}/retry` | 重投失败任务 |
| GET | `/api/notifications/mcp/tools` | MCP 邮件服务连通性自检 |
| GET | `/api/llm-provider/reload` | 热加载 Provider 配置 |

> 完整接口以启动后的 Swagger UI 为准（SpringDoc 扫描 `interview.guide.modules` 包）。

---

## 性能数据

| 指标 | 数值 | 口径 |
| --- | --- | --- |
| 简历上传接口响应 | ~200ms（原同步模型 15s 量级） | 自测 |
| 接口侧吞吐提升 | ~70 倍 | 自测 |
| 向量检索延迟 | P50 161ms / P95 207ms | 实测（baselines） |
| LLM 生成阶段 | P50 18.7s / P95 29.9s | 实测（baselines） |
| RAG Hit@K | 96.88%（基线）/ 93.75%（开改写） | 实测（baselines） |
| RAG MRR | 0.6818（基线）/ 0.7081（开改写） | 实测（baselines） |
| 多轮上下文题 MRR | 0.6319 → 0.9167（+45%） | 实测（baselines） |
| 知识库 chunk 数 | 2 篇文档 / 44 个 chunk（chunkSize=800） | 实测 |
| 简历解析线程池 | 2 线程 / 队列 20 / 2 分钟超时 | 配置 |
| Embedding 批大小 | 10（对齐 DashScope 限制） | 配置 |
| 邮件通知送达率 | 98% | 自测，见「数据口径说明」 |
| 通知重投策略 | 最多 3 次，2s / 4s 退避 | 配置 |

---

## Related Projects

同类项目大多落在两条路线上：要么是 **Python 脚本 / CLI 式的简历评分流水线**，要么是 **前端为主、直接调 LLM API 的面试 demo**。Java 全栈 + 完整工程化闭环（异步任务、向量检索、schema 迁移、可观测性、自动化测试）的成熟开源实现相对少见，这也是本项目的差异化位置。

| 项目 | 技术栈 | 侧重点 | 与 ResumeRAG 的差异 |
| --- | --- | --- | --- |
| [InterviewGuide](https://github.com/Snailclimb/interview-guide)（上游） | Spring Boot + Spring AI + pgvector + Redis | 简历分析、模拟面试、知识库的完整业务闭环 | 本项目的架构来源。补齐了 RAG 检索策略自适应 + 可复现测评、MCP 协议集成、邮件通知闭环三块 |
| [hiring-agent](https://github.com/interviewstreet/hiring-agent)（HackerRank） | Python 3.11 + PyMuPDF + Jinja prompt + Ollama/Gemini | 简历 PDF → 结构化 JSON → 可解释评分，带 GitHub 信号增强 | CLI 驱动的批处理流水线，重点在**评分的公平性与可解释性**；无 RAG 知识库、无异步任务队列、无通知闭环，不做面试模拟 |
| [Smart-interview](https://github.com/jianglonghui/smart-interview-system) 一类 | Node.js/Express + SQLite/Redis，或纯前端 Vite + React | 题库管理、出题、面试记录 | 以**题库 CRUD + 出题**为主，用 SQLite、无向量检索，缺乏检索质量度量与评估闭环；本项目用 pgvector + Skill 配额 + 统一评估引擎覆盖这部分 |
| [VORTEX AI](https://devpost.com/software/ai-interviewer-simulator)（AI Interviewer Simulator） | 浏览器端 + LLM API + Prompt Engineering | 高压技术面模拟、实时追问与反馈 | 黑客松演示性质，**无后端持久化与工程化设施**；本项目有完整的 schema 迁移、异步任务、幂等投递与指标埋点 |

**一句话差异**：同类项目多在验证"LLM 能不能干这件事"，本项目多回答了一层——"干这件事的系统怎么做到可观测、可复现、可运维"。具体体现在：40 条标注样本的 RAG 测评 harness 与 6 份 ablation 报告、Redis Stream 的条件领取与代次围栏、Flyway 版本化的向量表 schema、通知的幂等建单与退避重投。

---

## 后续规划

- [ ] **扩大 RAG 测评样本集**：当前 40 条样本的 Hit@K 置信区间过宽（同配置两次 run 差 9pp），扩到 200+ 条并加入跨语言、表格、代码类文档
- [ ] **按题型路由检索策略**：既然 rewrite 只对多轮上下文题有效，就把"是否改写"做成按 Query 特征（是否含指代词、是否依赖历史）的动态路由，而不是全局开关
- [ ] **自定义重叠分块器**：当前受 Spring AI `TokenTextSplitter` 限制无 overlap，跨段题仍有 `EVIDENCE_INCOMPLETE` 坏例（见 baseline 报告）
- [ ] **知识库外问题拒答**：baseline 中 8 条 OOS 题全部未被拒答（precision 0），需要引入拒答阈值与判别式
- [ ] **通知模块补 SMTP 直连降级**：MCP Server 不可用时退化为直接 SMTP，保证通知链路不单点
- [ ] **语音面试接入 WebRTC**：当前端到端延迟偏高（服务端音频中转），无耳机时存在回声泄漏
- [ ] **补压测报告**：把异步化吞吐、通知送达率的原始压测数据固化到 `docs/`

---

## License

本项目采用 [MIT License](./LICENSE)。

**致谢**：项目的整体架构与业务模块划分参考了 [Snailclimb/InterviewGuide](https://github.com/Snailclimb/interview-guide)（AGPL-3.0），在此感谢原作者的开源工作。本项目在其基础上重写了项目标识与文档，并新增 RAG 检索策略改造、MCP 协议集成与邮件通知模块；Java 包名 `interview.guide` 予以保留以体现技术沿革。
