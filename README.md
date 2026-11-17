# 基于 RAG 的施工安全规范智能问答系统

> **分支说明**：`main` 分支的向量库实现是 **Chroma 0.5.23**；本分支（`qdrant`）是同一套功能的
> **Qdrant 1.13.6** 版本。业务代码、REST 接口、前端页面、评测集完全一致，差异只集中在三处：
> 向量库装配（`VectorStoreConfig`）、按文档删除的实现、以及连接重试的异常类型判定。
> 两版共用同一套 45 个单元测试，切换分支即可对比同一功能在两种向量库上的落地方式。

<div align="center">

![JDK](https://img.shields.io/badge/JDK-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-6DB33F?logo=springboot&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-6DB33F?logo=spring&logoColor=white)
![Qdrant](https://img.shields.io/badge/Qdrant-1.13.6-FF4F8B)
![Maven](https://img.shields.io/badge/Maven-3.9.12-C71A36?logo=apachemaven&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-4C9F38?logo=opensourceinitiative&logoColor=white)

**本地知识库 · 引用可溯源 · 无召回不编造**

</div>

面向施工现场安全管理场景的本地知识库问答：把施工安全规范（国家标准、行业标准）切分入库，
提问时先检索相关条款、再基于条款生成回答，每条结论都能追溯到「哪份文件、第几条、第几页」的原文。
回答只依据检索到的条款，不依赖模型自身记忆；检索不到就明确拒答，而不是硬编一段像模像样的答案。

<div align="center">

| 对话页 | 知识库页 |
|---|---|
| ![对话页](docs/screenshots/chat.png) | ![知识库页](docs/screenshots/knowledge-base.png) |

</div>

## 目录

- [功能概览](#功能概览)
- [快速开始](#快速开始)
- [技术栈](#技术栈)
- [架构与 RAG 链路](#架构与-rag-链路)
- [API 接口清单](#api-接口清单)
- [配置说明](#配置说明applicationyml-关键项)
- [数据目录](#数据目录)
- [测试](#测试)
- [常见问题](#常见问题)
- [可选的对照实验](#可选的对照实验)

## 功能概览

系统只有一个页面，但有**两个菜单**：

### 菜单一：对话
- 聊天问答：输入施工安全相关问题，系统先从知识库检索规范条款，再生成回答；
- **流式输出**（SSE）：回答逐段出现，可随时点"停止"中断，已生成的部分保留；
- **多轮追问**：带上下文对话。问题里出现"那颜色呢""这个有什么要求"这类短问句或指代词时，
  先把问题改写成完整问句再检索（"那颜色呢" → "安全帽的颜色有什么要求？"），
  回答上方会标注"结合上下文理解为「…」"，改写失败自动回退原问题；
- **范围限定**：可以只在勾选的一篇或几篇规范里检索，而不是全库。范围条件下推到
  向量库的元数据过滤（下推成 Qdrant 的 payload 条件），不是"先全库召回再在应用层丢掉"；
- **引用溯源**：每条回答下方有引用卡片，标明来源文档名、条款号、PDF 页码、原文片段和相似度分数；
  点"原文"直接跳到该篇文档的对应条款；
- 左侧可调参数：召回条数 topK。
- **无召回不编造**：向量相似度全部低于 `min-score` 时，明确回复"知识库中未找到相关规范"，绝不让模型硬编。
  是否召回**只由向量相似度决定**；BM25 只在"向量路已确认有召回"之后才允许补充字面命中块
  （见下文配置说明里的取舍）。

### 菜单二：知识库
- 文档列表：文件名、大小、切分块数、入库时间、状态，可删除；
- **三种导入入口**：拖拽 / 多选 / 选文件夹，或**直接把条文粘贴进文本框**（Ctrl+V，不用先存成文件）。
  支持 PDF、Word（docx/doc/wps）、TXT、Markdown、PPTX、XLSX、HTML；
- **入库前预检**：选完文件先解析一遍但**不写库**，回报"预计切多少块、条款号覆盖率、要调几次 embedding、
  是否与库里已有文档重复、是不是同一部标准的另一个年号"；确认无误再入库；
- 多个文件排队逐个入库，**每个文件独立进度条**（解析 → 向量化 x/y 块 → 完成），单个文件失败不影响其他文件；
- 重建索引：一键按 `data/docs/` 中的原文件全量重建；
- 索引统计：文档总数、块总数、向量维度、当前 Embedding 模型、向量库实现；
- **入库内容可直接查看**：点文档名打开弹层，按块看原文、条款号、页码与字数，
  支持在这篇文档内按关键字或条款号（如 `3.13.3`）过滤，命中处高亮；
- **检索测试**小工具：输入问题只看召回片段和分数，不生成回答（不消耗生成调用，便于调参对照）。
- **检索质量评测面板**：一键用内置评测集（12 条正样本 + 3 条负样本）跑同一批问题，
  同时跑"纯向量"和"混合检索"两种模式并排对比 Hit@1 / Hit@5 / MRR / 拒答正确率；
  逐条明细里还给出**向量路 top1 的原始相似度**，调 `min-score` 时不用再猜。
  评测集可用 `data/eval-set.json` 覆盖而不必重新打包；
- **引用可定位到条款**：入库时解析 `第X条` / `3.2.1` / `附录A` 三类编号，引用卡片显示
  "《JGJ80-2016》4.1.1 · 第 47 页"，而不是笼统的"正文片段"。

## 快速开始

三分钟跑起来，最少只要一个可用的 Qdrant 和一条 OpenAI 兼容接口的 API Key。

```bash
# 1. 起向量库（本机装 Docker 时一条命令搞定）
docker compose up -d

# 2. 配密钥（OpenAI 兼容接口，通义 / Kiwi / 本地 vLLM 都行）
export LLM_API_KEY=sk-xxxxxxxx

# 3. 启动（JDK 21 + Maven 3.9.12）
mvn spring-boot:run

# 4. 打开浏览器
open http://localhost:8080   # 首次启动自动导入 sample_docs/ 示例规范，直接就能问
```

Qdrant 不在本机时：`export QDRANT_HOST=<ip>`（裸 IP，不要带 `http://`）。没有 key 想看效果：加 `APP_DEMO_MODE=true`
切到离线演示模式（见文末[「演示模式说明」](#演示模式说明无-api-key-时)）。完整的 IDEA 启动与
`restart.sh` 用法见[「运行步骤」](#运行步骤intellij-idea)。

> 向量库是必要依赖，**连不上会直接启动失败**（不会静默降级）。起来后建议先花 1 分钟自检：
> `python tools/qdrant_selfcheck.py --url http://localhost:6333`。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21（pom 中 `java.version=21`、`maven.compiler.release=21`） |
| 框架 | Spring Boot 3.4.5 + **Spring AI 1.0.0**（spring-ai-bom 统一版本） |
| 构建 | Maven 3.9.12，IntelliJ IDEA 直接打开 pom.xml 导入 |
| 向量库 | **Qdrant 1.13.6**（Docker，可本机也可在虚拟机/远程，REST 6333 / gRPC 6334），通过 Spring AI `VectorStore` 抽象接入 |
| 模型 | OpenAI 兼容接口（`spring-ai-starter-model-openai`），默认指向通义千问兼容端点，可换 Kiwi |
| Embedding | 默认 `text-embedding-v3`（通义）；可换任意兼容端点的 bge 系列模型名 |
| 文档解析 | PDFBox（PDF 逐页，保留页码）+ Apache Tika（docx/doc/wps/pptx/xlsx/html） |
| 前端 | 原生 HTML/CSS/JS（Spring Boot 静态托管，无前端框架） |

## 架构与 RAG 链路

```
                        ┌──────────────────── 入库（ETL）────────────────────┐
  PDF/TXT/MD        ──► DocumentReader ──► ClauseTextSplitter ──► VectorStore.add()
  Word/PPT/Excel/HTML   (PagePdf 带页码 /   (自研：条款优先切分，   │  metadata:
                         TextReader /       文本规整 + 编号识别，   │  docName/clause/
                         Tika)              超长走 Token 兜底)      │  chunkIndex/page
                                                                    ▼
                        ┌──────────────────── 检索 ────────────────────────┐
  用户问题 ──► QuestionRewriter ─► HybridRetriever ──┬─ 向量检索 similaritySearch(topK, min-score 过滤)
        （多轮时先改写）                            │    └─ 过滤后为空 = 无召回（拒答由这一步决定）
                                                    ├─ BM25 关键词召回（自研 Bm25Index）
                                                    │    └─ 向量有召回时才允许补字面强命中块
                                                    └─ 融合：RRF，按名次不按分数
                                                              │
                        ┌──────────────────── 生成 ────────────────────────┐
                                 └─► ChatService 手写组装 Prompt
                                     （条款按 [1][2] 编号 + 只依据原文回答）
                                     ──► ChatClient（流式）──► 回答 + 引用列表
```

批量上传走的是"落盘即返回、后台排队入库"的独立通道（`IngestJobService`），
与上面这条 ETL 链路共用 `KnowledgeBaseService.ingestSavedFile`，只是多了逐文件进度上报。

对照 Spring AI 的一键方案：`QuestionAnswerAdvisor` 内部做的就是"检索→拼上下文→提问"，
本项目为了把检索、拼接、拒答这三步都放在明面上，在 `ChatService` 里手写了这条链路；
两种写法的对照见 `ChatService` 类注释。

### 核心代码导读

| 文件 | 作用 |
|---|---|
| `etl/ClauseTextSplitter.java` | **自研切分器**：继承 Spring AI `TextSplitter`，文本规整（压空白/接断行/剥页脚水印/过滤目录）→ 按条款边界切分、章标题并入下一条、超长条款用 `TokenTextSplitter` 兜底 |
| `etl/ClauseLabels.java` | 条款号识别：`第X条` / `3.2.1` / `附录A` 三类写法，能把 PDF 拆开的 `8. 2.1` 修回 `8.2.1`，并挡掉参数表里的数字（`1.50 1.8 1.5`）与页码 |
| `etl/DocumentIngestService.java` | ETL：按扩展名选 Reader（PDF 逐页带页码 / TXT / Word 等走 Tika），切分并按顺序回溯补齐 clause |
| `etl/SampleDataInitializer.java` | 首次启动自动导入 `sample_docs/` 示例规范，开箱可问 |
| `rag/Bm25Index.java` | **自研 BM25**：纯 Java 实现（字符 bigram 分词 + Okapi BM25 公式），并附带查询词项覆盖率供录取判定 |
| `rag/HybridRetriever.java` | 混合检索：向量 + BM25 两路召回，向量路做绝对阈值 + 范围限定（下推 Qdrant payload 过滤），两路按 **RRF（倒数排名融合）** 合并——用名次不用分数，避开两路量纲不可比的问题 |
| `rag/QuestionRewriter.java` | 多轮追问改写：仅在"有历史"且"问题过短或含指代词"时才调模型改写，失败回退原问题 |
| `rag/RetrievedChunk.java` | 检索结果载体（Document + 排序分 + 向量分 + BM25 分，展示与排序分开） |
| `service/ChatService.java` | 问答主链路：改写 → 检索 → 无召回拦截 → 手写组装 Prompt → 调用模型 / 抽取式降级 |
| `service/EvalService.java` | 检索评测：同一批问题跑纯向量与混合两种模式，算 Hit@1 / Hit@K / MRR / 拒答率 |
| `service/KnowledgeBaseService.java` | 知识库管理：上传/删除/重建/统计/检索测试；`saveUpload` 与 `ingestSavedFile` 拆开，供后台任务复用 |
| `service/IngestJobService.java` | 批量入库任务：落盘即返回 taskId，单线程顺序入库并逐块上报进度，单文件失败不影响其余 |
| `service/DocumentRegistry.java` | 文档注册表（`data/registry.json`）：补齐 VectorStore 抽象没有的"文档级管理"能力 |
| `config/DemoModeConfig.java` | 演示模式：本地兜底 EmbeddingModel（字符 n-gram 哈希向量），无 key 可跑 |
| `config/VectorStoreConfig.java` | **Qdrant 装配**：显式建集合（维度即契约）、建 payload 索引、启动时校验维度，用 gRPC 连接 |
| `config/HostsFileGuard.java` | **启动守卫**：`-Djdk.net.hosts.file` 指向不可用文件时自动清除并回退系统 DNS（JDK 在该设置失效时不会 fallback，会让所有外部请求直接失败，见"hosts 绕行也可能反噬"） |
| `config/CachingEmbeddingModel.java` | **Embedding 批量预取装饰器**：把逐块调用合并成 5 条/请求，失败自动拆小重试（见下节） |
| `config/HttpClientConfig.java` | 把流式用的 WebClient 换成 JDK HttpClient 传输层，与非流式保持一致（见"卡住 19 秒"一节） |
| `tools/pin_dashscope_ip.py` | 逐个探测百炼的 A 记录，挑出"上行大包能通"的 IP 写入 `hosts`，绕开 PMTU 黑洞 |
| `tools/qdrant_selfcheck.py` | Qdrant 服务端自检：建集合 → 写 600 条 → 删不存在的 id → 按 payload 删除，验证幂等与吞吐（只用标准库） |
| `tools/migrate_to_qdrant.py` | 把 Chroma 里的向量整库搬到 Qdrant（含 payload 与原文），换库不必重新算 embedding |
| `web/ChatController.java` | `/api/chat`、`/api/chat/stream`（SSE：rewrite / token / citations 事件） |
| `web/KnowledgeBaseController.java` | `/api/kb/documents`、`/api/kb/preview`（入库预检）、`/api/kb/uploads`（批量上传 + 进度查询）、`/api/kb/paste`（粘贴入库）、`/api/kb/reindex`、`/api/kb/stats`、`/api/kb/search-test`、`/api/kb/eval`（检索评测）、`/api/kb/documents/{docId}/chunks`（块原文，支持过滤与分页） |
| `web/ApiExceptionHandler.java` | 全局异常映射：400/404/409/413/500 与一句可读文案，替代"一律 500 + 堆栈" |
| `service/IngestPreviewService.java` | 入库预检：解析切分但不写库，算块数/条款率/重复/版本冲突 |
| `util/StandardCode.java` | 从文件名抽标准编号（`JGJ 80-2016`），用于新旧版本提醒 |

## API 接口清单

### 对话

| 接口 | 说明 |
|---|---|
| `POST /api/chat` | 同步问答，一次返回完整回答 + 引用列表 |
| `POST /api/chat/stream` | 流式问答（SSE），事件序列见下 |

两个接口共用同一请求体（`Content-Type: application/json`）：

```json
{
  "question": "安全帽的颜色有什么要求？",
  "topK": 5,
  "history": [
    { "role": "user", "content": "安全帽有哪些要求？" },
    { "role": "assistant", "content": "……" }
  ],
  "docIds": ["JGJ80-2016__xxx"]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `question` | string | 本轮问题（必填） |
| `topK` | int | 召回条数，不传默认 5，后端限幅 1~20 |
| `history` | `Turn[]` | 最近若干轮对话，按时间正序（旧→新），为空表示单轮。只用于「追问改写」（把"那颜色呢"补全成可独立检索的问句），**不参与**最终 Prompt 的上下文拼接 |
| `docIds` | string[] | 限定检索范围的文档 docId 列表，为空/不传 = 全库检索 |

`history` 里的 `Turn`：`{ "role": "user" | "assistant", "content": "……" }`。

SSE 流式事件序列：`rewrite`（多轮改写结果）→ 一串 `token`（逐段回答）→ `citations`（引用列表）→ `done`。

### 知识库（入库 / 管理）

| 接口 | 用途 |
|---|---|
| `POST /api/kb/preview`（字段 `files`，可重复） | 入库预检：解析、切分、统计，**不写库**；返回块数、条款号覆盖率、预计 embedding 调用次数、重复文档名、版本提醒、前 3 块样本 |
| `POST /api/kb/paste`（JSON `{title, text}`） | 粘贴入库：把一段条文落成 txt 后走与上传完全相同的链路，立刻返回 `jobId` |
| `POST /api/kb/documents`（字段 `file`） | 单文件同步入库，兼容脚本/命令行调用 |
| `POST /api/kb/uploads`（字段 `files`，可重复） | 批量上传：**只落盘**，立刻返回 `jobId` 与每文件初始状态 |
| `GET /api/kb/uploads/{jobId}` | 轮询进度：`stage`（排队中/解析切分/向量化/已完成/失败）、`written`/`total`（块数）、`message`（失败原因） |
| `GET /api/kb/documents` | 文档列表（文件名、大小、块数、入库时间、状态） |
| `DELETE /api/kb/documents/{docId}` | 删除单篇文档（同时清掉向量） |
| `POST /api/kb/reindex` | 一键按 `data/docs/` 原文件全量重建索引 |
| `GET /api/kb/stats` | 索引统计：文档总数、块总数、向量维度、Embedding 模型、向量库实现 |
| `POST /api/kb/search-test`（JSON `{query, topK}`） | 检索测试：只看召回片段与分数，不生成回答 |
| `POST /api/kb/eval`（JSON `{topK}`） | 跑内置评测集，返回纯向量/混合两模式的 Hit@1 / Hit@K / MRR / 拒答率 |
| `GET /api/kb/documents/{docId}/chunks?q=&offset=&limit=` | 查看某篇文档入库后的块原文：
`total` 是全文块数、`matched` 是关键字过滤后的条数；`q` 同时匹配块正文与条款号 |

设计取舍：

- **为什么先预检再入库**：解析几秒、写 300 条向量几十秒还要花配额，代价不对称。
  预检把"会切多少块 / 条款号能不能认出来 / 是不是扫描件 / 和库里哪篇重复 / 是不是旧版标准"
  提前摆出来，避免"等了半分钟才发现传的是封面页或者已经有的文件"。
- **为什么粘贴也要落成文件**：重建索引只扫 `data/docs/`，粘贴内容若只存在内存，
  一次"重建索引"就凭空消失；落成 txt 之后粘贴与上传在后续链路里完全同构。
- **为什么单线程**：embedding 接口有并发/配额限制，多线程反而更容易 429；
  入库本身不追求并行（用户要看到的是逐个完成）。
- **为什么落盘要同步做**：先落盘才有 `data/docs` 里的原文件，重建索引才有数据来源；
  落盘失败（类型不支持）的文件直接标 `FAILED`，不会拖垮整批。

## 为什么大文档会"入库失败：Connection reset"

这是本项目**实测踩到并修掉**的一个真实缺陷。

**现象**：上传一篇 171 块的规范（GB50656），几十秒后报 `失败：Connection reset`，
重传还是失败；重建索引要跑三分半。而同样环境下直接 `curl` 调 embedding 接口是 1 秒内成功的。

**根因有两层**：

1. 向量库写入走的是 `embed(List<Document>, options, BatchingStrategy)` 这条批量入口，
   但它的默认实现是**一条文本一次请求**（本项目在 `CachingEmbeddingModel` 里沿用同一
   策略，好让所有调用都统一走缓存）。一篇 171 块的规范 = **171 次 HTTPS**，
   1221 块的语料 = **1221 次**。
   调用次数越多，"某一次被中间设备掐断"的概率越高；而任何一次失败都会让整批回滚重来。
2. 批量越大，响应体越大，越容易在传输中途被重置。实测（同一网络、同一 key）：

| 单次请求条数 | 响应体 | 结果 |
|---|---|---|
| 1 条 | 21 KB | 10/10 成功，每次 0.25~0.33 秒 |
| 5 条 | 108 KB | 6/6 成功，每次 0.4~0.5 秒 |
| 10 条（接口上限） | 217 KB | 3 次里 2 次在 **19 秒**后 `Connection reset` |

**解法**：`config/CachingEmbeddingModel.java` 装饰 EmbeddingModel——
入库前先按 **5 条/批**把整篇文本批量算好放进 LRU 缓存，随后写入时的逐条调用
全部命中缓存，不再产生网络请求；某一批连续失败时自动**二分拆小**
（5 → 2+3 → 1）再试，最终落到"单条"这个稳定档位。同时 `spring.ai.retry`
配了 3 次尝试（1s/2s 退避），兜住偶发的单次抖动。

| 指标 | 改造前 | 改造后 |
|---|---|---|
| 171 块的 PDF | 171 次请求，**失败** | 35 次请求，12 秒入库成功 |
| 重建索引（1392 块 / 5 篇） | 193 秒（实测 3 分 13 秒 @1221 块） | **66 秒** |
| 失败时的行为 | 删掉源文件，用户得重传 | 保留源文件，点「重建索引」即可补录 |

顺带把失败文案改成可操作的：不再只显示 `Connection reset`，而是
"这是调用 Embedding 接口时的网络故障……源文件已保留，稍后点『重建索引』即可补录"。

## 为什么对话会被卡住 19 秒然后降级成抽取式

**现象**：页面提问后转圈约 20 秒，最后显示【抽取式回答】，服务端日志是
`Connection reset`。而同一时刻用 curl 直连百炼只要 0.5 秒。更迷惑的是"时好时坏"：
同一个问题、同一段代码，改天再问又正常了。

**排查过程**（每一步都做了对照，最后才找到真凶）：

| 观察 | 结论 |
|---|---|
| curl 直连 3/3 成功、0.5 秒；Java 独立程序也 3/3 成功 | 不是 key、不是断网 |
| 独立 JVM 里跑 Apache HttpClient5，1.5 秒成功 | 不是客户端库的锅 |
| 去掉 `http_proxy/https_proxy` 再启动，仍 19 秒 reset | 不是代理环境变量 |
| JDK 客户端强制 HTTP/1.1、加 `Expect: 100-continue`，仍 8/8 失败 | 不是 HTTP 版本、不是 100-continue |
| 同一份 4KB 请求体：curl `--resolve` 逐个 IP 打 | **4 个 IP 里只有 1 个通** |

逐个 IP 的实测结果（同一时刻、同一份请求体）：

| dashscope.aliyuncs.com 的 A 记录 | 轻量 GET `/models` | 带 4KB body 的 POST |
|---|---|---|
| 8.140.217.18 | 401 · 0.11s | **401 · 2.6s** |
| 39.96.198.249 | 401 · 0.14s | 12s+ 无响应，随后连接被重置 |
| 8.152.159.24 | 401 · 0.13s | 12s+ 无响应，随后被重置 |
| 39.96.213.166 | 401 · 0.12s | 12s+ 无响应，随后被重置 |

**根因**：TCP 连得上、TLS 握手正常、小请求也正常，只有**上行请求体超过约 1.2KB**
的请求丢包，然后被重置——典型的 **PMTU 黑洞**（路径 MTU 变小，ICMP
"fragmentation needed" 又被中间设备吞掉，大包就静默消失）。而
`dashscope.aliyuncs.com` 有 4 个 A 记录轮询，等于每次请求在抽签：抽到坏 IP 就卡
19 秒再失败。这也解释了为什么"小问题能答、召回 5 条就挂"——不是 topK 的问题，
是请求体跨过了那个临界值。

**解法（两步）**：

1. **固定接入 IP**：`tools/pin_dashscope_ip.py` 逐个 IP 发一个约 2KB 的无鉴权
   POST（服务端立刻回 401，不需要 API key），谁能快速回应就选谁，写进 `hosts`；
   启动时加 `-Djdk.net.hosts.file=<项目>/hosts`，JVM 就只认这个 IP。
   只影响本进程，不动系统 hosts 表，也不影响浏览器和 curl。
   用 `PIN_IP=1 bash restart.sh` 会先重新探测再带这个参数启动（默认不启用，见下方说明）。
2. **把 HTTP 客户端统一到 JDK 的 `java.net.http.HttpClient`**（顺手做的一致性改动）：
   非流式用 `spring.http.client.factory: jdk`，流式在 `config/HttpClientConfig.java`
   里用 `WebClientCustomizer` + `JdkClientHttpConnector` 换传输层。它不影响能不能通，
   但连接与超时行为更可预测（connect 10s / read 120s）。

**改完实测**（就是截图里那条"施工现场临时用电有什么要求"）：

| 场景 | 改造前 | 改造后 |
|---|---|---|
| topK=5 的流式问答连测 5 次 | 5/5 全部降级成抽取式，每次 40 秒 | **5/5 模型生成**，9~13 秒 |
| topK=10 | 必挂 | 模型生成，20 秒（回答更长） |
| 日志里的降级/重置次数 | 每次都有 | **0** |

另外流式链路还加了一道保险：流式失败且尚未吐出任何 token 时，先换非流式
`call()` 补答一次（不同连接池），补到就逐块推给前端，效果与流式一致；
只有它也不通才退回抽取式。**这里是三步兜底**：原地重试一次 → 换链路补答 → 抽取式。

> 如果你把项目拷到别的机器上，那边网络没有 PMTU 问题，`hosts` 文件可以直接删掉，
> 或者跑一次 `python tools/pin_dashscope_ip.py` 重新选路。

> **⚠️ `hosts` 绕行本身也可能反噬（本项目后来实测踩到）**：网络环境变了之后（PMTU 黑洞消失），
> 那个被固定下来的 IP 对 JVM 的 HTTPS 连接反而会触发 `Connection reset`，表现和原来一模一样——
> 回答一直降级成【抽取式】，而 `curl` 直连百炼却正常（401 秒回）。根因是固定 IP 失效/不再友好，
> 但 `-Djdk.net.hosts.file` 还在强制走它。**排查口诀**：模型调用 reset 时，先 `curl` 直连百炼
> 区分"网络问题"还是"固定 IP 问题"；若 curl 通、JVM 不通，去掉 `restart.sh` 里的
> `-Djdk.net.hosts.file` 参数（或删掉 `hosts` 文件）再试。结论：**换网络后优先验证是否还需要
> hosts 绕行，不需要就直接删，别让一个过期的绕行方案反过来拖垮链路。**

> **⚠️⚠️ 更隐蔽的一种：文件删了，参数还在。** `-Djdk.net.hosts.file` 指向的文件不存在时，
> JDK **不会回退到系统 DNS**，而是对任何域名直接抛 `UnknownHostException`。实测（JDK 21）：
> 不带参数 `resolve = 4 addrs / HTTP 401 / 375ms`；带参数但文件已删 → `resolve FAILED:
> Unable to resolve host ... as hosts file ... not found`，请求 45ms 就失败。
>
> 由于 `hosts` 是临时产物、删了很自然，但启动参数容易残留在 **IDEA 运行配置的 VM options**、
> 命令行历史或旧脚本里，于是症状变成"**一个根因、三个面具**"：
>
> | 表面症状 | 实际原因 |
> |---|---|
> | 回答降级成【抽取式】 | 生成模型调用在 DNS 解析阶段就失败 |
> | 引用分数全是 `0.004` 量级 | 查询向量化同样失败 → 向量路降级，只剩 BM25（`0.25/(60+名次)`） |
> | 索引统计里 `vectorDimension: 0` | 探测维度要发一次极小 embedding 请求，同样失败 |
>
> 看起来像网络故障、像向量库坏了，实际只是 DNS 解析被一个失效参数掐死了。
> 本项目已加启动守卫 `config/HostsFileGuard`：发现该属性指向的文件不存在/不可读/只有注释时，
> 自动清除属性并回退系统 DNS，同时打一条 `[HostsFileGuard]` 的 WARN 说明原因。
> **即便如此，也请顺手把 IDEA 运行配置里残留的 `-Djdk.net.hosts.file=...` 删掉**，
> 否则每次启动都会多一条警告。

## 写操作互斥与失败处理

这三件事都会同时改"注册表 + 向量库"，所以必须串起来做：

| 操作 | 冲突场景 | 现在的行为 |
|---|---|---|
| 入库（单文件 / 批量 / 粘贴） | 正在重建索引 | 排队等待，日志打一行"正在等待其他写操作完成" |
| 重建索引 | 有文件正在入库 | 排队等待当前文件写完再开始 |
| 删除文档 | 上面任一操作进行中 | 排队等待 |

改动前没有互斥：一边批量上传一边点重建索引，重建会把刚写进去的块当旧块删掉，
上传线程随后又把注册表写回去，向量库与注册表从此对不上——之后每次重建都在收拾上一次的残局。
实测（重建 3 分 13 秒期间发起一次上传）：上传等了 3 分 02 秒后正常完成，数据一致。

另外两处容错：

- **重建按篇兜底**：单篇解析失败只记进 `failed`，其余照常入库，不再"重建到一半停下、
  旧向量已删新向量没建完"。返回体：`{"reindexedDocuments":4,"skippedDuplicates":0,"failed":[{"name":"x.pdf","reason":"…"}]}`。
- **注册表损坏不自毁**：`registry.json` 解析失败时先改名成 `registry.json.corrupt-<时间戳>` 留档再以空表启动。
  以前是直接当空表跑，下一次入库落盘就把坏文件覆盖了，全库块 id 永久丢失（向量还在库里，但没人认识它们）。

## 统一错误响应

`web/ApiExceptionHandler` 把异常翻译成前端能直接显示的一句话：

| 情况 | 状态码 | 响应 |
|---|---|---|
| 类型不支持、内容为空、粘贴超长 | 400 | `{"status":400,"message":"不支持的文件类型…"}` |
| 写冲突（注册表只读保护等） | 409 | `{"status":409,"message":"…"}` |
| 文件超过 50MB 上限 | 413 | `{"status":413,"message":"文件超过大小上限…"}` |
| 任务号不存在、文档不存在 | 404 | 原业务提示 |
| 真正未知的异常 | 500 | 记堆栈日志，返回简短原因 |

改动前这些情况一律 500 + 堆栈，前端只能显示"入库失败：HTTP 500"。

## 条款结构化：让引用卡片说得出"第几条"

改造前后（同一批 3 篇 PDF + 1 篇示例 txt，重建索引实测）：

| 指标 | 改造前 | 改造后 |
|---|---|---|
| 总块数 | 586 | 1221（按条款切分，粒度更细） |
| `clause` 为"正文片段" | 558 块（95%） | 383 块（31%） |
| 能定位到具体条款 | 28 块（5%） | 838 块（69%） |
| 重建耗时 | 1 分 47 秒 | 3 分 28 秒（块数变多） |

关键处理（都在 `ClauseTextSplitter` + `ClauseLabels`）：

1. **文本规整**：压掉 PDF 行尾几十个空格、把被换行切断的句子接回去，
   否则"行首锚点"根本匹配不到条款号；
2. **编号修复**：`8. 2.1` / `2.1. 7` / `第 4 章` 这类被排版拆开的编号先压紧；
3. **三类边界**：`第X条`、`3.2.1`（数字编号）、`附录A`，四级以上数字编号才算边界，避免把页码当条款；
4. **挡掉表格数字**：`1.50 1.8 1.5 1.5` 这种参数表行形状像编号，但紧跟其后十几个字符
   没有汉字，判为表格而不是条款；
5. **去掉版面垃圾**：重复 ≥5 次的短行（页眉页脚水印）、省略号密集的目录行；
6. **顺序回溯**：超长条款被兜底切成多块时，只有第一块带编号，后面几块沿用上一条
   （最多回溯 3 块）；但**只有"块首就是编号"的强证据才向后传**，正文中间蹭到的编号
   不传——否则评分表里"……悬挑长度的 1.25 倍"会把后面几行表格全标成 1.25。

> 剩余 31% 没有条款号的主要是 JGJ59《建筑施工安全检查标准》的评分表——
> 表格行本身没有条款号（"扣 5 分""扣 10 分"），这类块靠"页码 + 文档名"定位，符合预期。
> 这是启发式规则的固有上限：要再提高就只能上版面分析（区分正文/表格区域），
> 属于另一个工程量级。

## 运行步骤（IntelliJ IDEA）

环境要求：**JDK 21**、**Maven 3.9.12**（IDEA 设置里把 Maven home 指到 3.9.12、JDK 指到 21 即可）。

1. **导入工程**：IDEA → Open → 选择本目录的 `pom.xml` → 以 Maven 工程导入，等待依赖下载完成。
2. **先起向量库**：向量库是必要依赖（本工程只保留 Qdrant 一套实现），默认连 `localhost:6334`（gRPC）。
   - 本机有 Docker：在工程目录 `docker compose up -d`，一条命令搞定（**必须同时映射 6333 和 6334**）；
   - Qdrant 在虚拟机/另一台机器：确认两个端口都可达，然后设环境变量 `QDRANT_HOST=<ip>`（裸 IP，别带 `http://`），
     或在运行配置的 Program arguments 里写 `--spring.ai.vectorstore.qdrant.host=<ip>`；
   - 起来后先自检：`python tools/qdrant_selfcheck.py --url http://<ip>:6333`。
   具体见下方「向量库（Qdrant）怎么起」。**连不上时应用会直接启动失败并给出提示**，不会静默降级。
3. **配置模型**：默认 `app.demo-mode=false`，即走真实模型，需要可用的 `LLM_API_KEY`。
   手上暂时没有 key 时，给运行配置加上环境变量 `APP_DEMO_MODE=true` 即可切到离线演示模式（见文末「演示模式说明」）。接真实模型时，二选一：
   - 改 `src/main/resources/application.yml` 里的 `spring.ai.openai.*` 与 `app.demo-mode: false`；
   - 或在 IDEA 运行配置的 Environment variables 里填：
     `LLM_API_KEY=你的key;APP_DEMO_MODE=false`（可另加 `LLM_BASE_URL`、`LLM_CHAT_MODEL`、`LLM_EMBEDDING_MODEL` 覆盖端点与模型名）。
4. **运行**：打开 `SafetyRagApplication`，点 main 方法旁的运行按钮。
   > 若你之前为了绕开网络问题配过 `-Djdk.net.hosts.file=...`，**请到 Run → Edit Configurations
   > → VM options 里把它删掉**。文件被删掉而参数还在时，JDK 会让所有域名解析直接失败，
   > 表现为"回答永远是抽取式、向量维度显示 0"（详见上文"hosts 绕行也可能反噬"）。
   > 代码里已有 `HostsFileGuard` 兜底，但清掉参数才是干净做法。
   命令行等价写法：`mvn spring-boot:run`（后端在本机跑时记得加 `--server.port=8080` 之类的显式端口）。
5. **访问**：浏览器打开 http://localhost:8080 ，左侧切换「对话 / 知识库」两个菜单。
   首次启动会自动把 `sample_docs/` 里的示例规范入库，直接就能提问。

> 启动日志里会打印 `Qdrant 服务地址：...` 和 `当前 VectorStore 实现：QdrantVectorStore ...`，
> 知识库菜单的「索引统计」里 `vectorStore` 字段也能确认。

### 改完代码怎么重启（`restart.sh`）

Windows 上有个坑：服务在跑的时候直接 `mvn package`，jar 被占用会让 `repackage` 失败，
而且会把 fat jar 覆盖成一个一百多 KB 的薄 jar，正在跑的服务当场崩溃（页面报 500）。
`restart.sh` 把顺序固定成「停服 → 打包 → 启动 → 等就绪」：

```bash
bash restart.sh              # 默认端口 18080
PORT=8080 bash restart.sh    # 换端口
SKIP_BUILD=1 bash restart.sh # 只重启不打包
NO_TEST=0 bash restart.sh    # 打包时顺带跑全量测试
PIN_IP=1 bash restart.sh     # 先探测并固定百炼接入 IP 再启动（见下方说明）
QDRANT_HOST=192.168.1.20 bash restart.sh   # Qdrant 不在本机时
```

脚本会做三件额外的事：按端口反查并杀掉旧进程（Git Bash 的 `$!` 拿的是 MSYS 虚拟 pid，
`taskkill` 认不了，所以统一走端口）、识别「薄 jar」并直接报错、轮询 `/api/kb/stats`
确认真的起来了才返回。日志固定写到 `.run.log`。

Qdrant 地址按「`QDRANT_HOST` 环境变量 → 项目根 `.qdrant-host` 文件 → `localhost`」
的顺序取；`.qdrant-host` 已在 `.gitignore` 里，适合放本机常用的虚拟机地址，免得每次都带参数。
脚本会自动去掉误写的 `http://` 前缀——Qdrant 走 gRPC，配置项要的是裸主机名。

> **关于 `PIN_IP`（固定百炼 IP）**：默认**不**固定，走 DNS 轮询。只有当你又遇到
> "模型调用 ~19 秒后 `Connection reset`、回答降级成抽取式"时，才用 `PIN_IP=1 bash restart.sh`
> 重新探测并固定一个能通大包的 IP。原因是这个绕行只对「确实存在 PMTU 黑洞」的网络有效，
> 网络环境变了之后固定下来的 IP 可能反而失效（本项目实测踩到过），所以宁可默认关闭、需要时显式打开。

### 向量库（Qdrant）怎么起

**为什么只留一套实现**：早期版本并排放过 `SimpleVectorStore`（内存 + 本地 JSON）兜底，
结果两套写入路径、两套坑，而且它逐块调 embedding，把大文档入库放大成几百次 HTTPS。
现在统一走 Qdrant，只保留一条链路；代价是启动前必须有一个可用的 Qdrant 服务
（连不上就启动失败，不会静默降级）。

**Qdrant 相对 Chroma 去掉的坑**（这也是这次换库的主要理由）：

| Chroma 上的问题 | Qdrant 上的表现 |
|---|---|
| Spring AI 靠**精确匹配错误文案**判断集合是否存在，真实文案对不上 → 空库永远起不来 | 有 `collectionExists` 明确接口，不依赖文案 |
| 删除**不存在的 id** 会在原生层段错误，服务端直接退出 | 删除是幂等的，不存在的 id 直接忽略 |
| Windows 原生 pip 版写入必崩（`chroma-hnswlib`） | 单二进制 / 官方镜像，没有原生轮子的坑 |
| 数据目录不能被不同版本交替打开，否则写入时崩 | 存储格式稳定，升级不用清库 |
| 只能按 id 删除，删一篇要先捞全库 id 求交集 | 支持按 payload 条件删除（`docId` 过滤），一次请求删完整篇 |

#### 先自检，再动数据

```bash
python tools/qdrant_selfcheck.py --url http://localhost:6333
```

脚本只依赖标准库，会依次验证：连通性 → 建集合与 payload 索引 → 写 600 条（1024 维）
→ exact count 校验 → 删除不存在的 id（幂等） → 按 payload 删除 → 清理临时集合。
本机实测写入约 **990 条/秒**，600 条一共 600 毫秒左右。

#### Docker 部署（推荐，本机或虚拟机都行）

工程自带 `docker-compose.yml`，已锁 `qdrant/qdrant:v1.13.6`，两个端口都映射：

```bash
docker compose up -d
docker compose ps
curl -s localhost:6333/healthz          # 期望：healthz check passed
curl -s localhost:6333/collections
```

**两个端口缺一不可**：

- `6333` = HTTP / REST / Web UI（浏览器打开 `http://<ip>:6333/dashboard`）
- `6334` = gRPC，**Spring AI 的 `QdrantVectorStore` 走这个**

只映射 6333 的典型症状很有迷惑性：dashboard 能打开、`/collections` 也能访问，
但应用启动时报 `UNAVAILABLE`。遇到这个先查端口映射，别去查代码。

手动 `docker run` 的等价写法：

```bash
docker run -d --name safety-rag-qdrant --restart unless-stopped \
  -p 6333:6333 -p 6334:6334 \
  -v /data/qdrant-data:/qdrant/storage \
  -v /data/qdrant-snapshots:/qdrant/snapshots \
  -e QDRANT__TELEMETRY_DISABLED=true \
  qdrant/qdrant:v1.13.6
```

**镜像拉不下来怎么办**（实测踩过，值得先知道）：直连 Docker Hub 在国内基本超时；
用加速站时，一个 300MB 的镜像要发 8 个 blob 请求，很容易中途被上游限流，
日志表现为**所有层都 `Download complete`，却一个 `Pull complete` 都没有**，
或者直接抛 `toomanyrequests`。已下载的层会被缓存，所以**重试几次就能逐步累积到完成**，
不必换源也不必改配置。实测 `docker.m.daocloud.io/qdrant/qdrant:v1.13.6` 反复重试后可拉全。

#### 版本要点

- **服务端 1.13.6 对应客户端 1.13.0**：Spring AI 1.0.0 传递依赖的是 `io.qdrant:client:1.13.0`，
  服务端选同代版本最稳（gRPC 协议向后兼容，但没必要给自己找跨代的麻烦）。
- **维度是建库契约**：Chroma 插入时才确定维度，Qdrant 在 `createCollection` 时就定死 `size`。
  换 embedding 模型必须同时改 `app.vector-dimension` 并重建集合，否则写入直接报错。
  好处是这类错误会在启动阶段就被 `VectorStoreConfig` 拦下来（它会对比集合实际维度与配置）。
- **payload 里正文的键名是 `doc_content`**：这是 Spring AI 的约定，不是我们自己起的名字。
  迁移脚本和手工 scroll 都要按这个键名写，否则检索回来 `getText()` 是空。

#### 从 Chroma 迁数据

如果手里已经有一库 Chroma 数据，不必重新算 embedding（1436 块约等于 300 次付费请求）：

```bash
python tools/migrate_to_qdrant.py \
    --chroma http://<chroma-host>:8000 \
    --qdrant http://<qdrant-host>:6333 \
    --collection safety-regulations
```

脚本按 200 条/页从 Chroma 导出 id、向量、metadata 与原文，再按 100 条/批写入 Qdrant
（payload = metadata 平铺 + `doc_content`），最后用 exact count 校验条数。
`chunkId` 两边都是同一个 UUID，所以注册表 `data/registry.json` 可以原样搬过来，不用重建。


### 为什么没有本地兜底

业务代码只依赖 Spring AI 的 `VectorStore` 接口，理论上换实现不动一行业务逻辑；
早期版本确实并排放过一套 `SimpleVectorStore`（`@Profile` 互斥切换），后来删掉了：

- **它把大文档入库放大成几百次 HTTPS**——写入按块调 embedding，正是"171 块 PDF 必失败"的放大器；
- **两套实现 = 两套行为**：删除是否幂等、集合是否存在、向量存哪，全都不一样，
  排查问题时先要确认"你用的是哪套"，沟通成本高于收益；
- **生产形态本来就该是独立向量服务**：换机器、扩容、多实例共用一个库，本地 JSON 都做不到。

现在只有一条链路：Qdrant。启动时连不上就是启动失败，错误信息里直接给可执行的下一步——
连不上 gRPC、端口只映射了 6333、集合维度与配置不一致，三种情况各有各的提示。

### 演示模式说明（无 API key 时）

`application.yml` 中 `app.demo-mode` 的默认值是 **`false`**（走真实模型）。没有可用 key 时，
用环境变量 `APP_DEMO_MODE=true` 或启动参数 `--app.demo-mode=true` 打开演示模式，此时：
- Embedding 用本地兜底实现 `HashNgramEmbeddingModel`（字符 n-gram 哈希 + 余弦相似度），不调任何外部接口；
- 回答用**抽取式**：直接按相关度摘录召回条款原文并标注出处，页面上会显示"演示模式"角标；
- 全链路（上传→切分→入库→混合检索→引用溯源）都真实运行，只有"模型生成"这一步被摘录代替。
- **能力边界（务必知道）**：在真实语料上实测，哈希向量与问题的余弦相似度只有 **0.02~0.11**，
  全部低于默认 `min-score=0.60`，因此演示模式下向量路基本召回不到东西，实际靠 BM25 兜底。
  这也意味着**演示模式下"无召回不编造"不可靠**：BM25 只认字面，问"今天食堂吃什么"仍会
  命中食堂相关条款。演示模式用于展示链路能跑通，效果与拒答行为请以真实模型为准。
- 实际使用建议配一个真实 key 并关闭演示模式。

### 上游故障为什么不会变成 500

真实模式下模型接口偶发 `Connection reset`：百炼会主动关闭闲置的 keep-alive 连接，
JVM 的 HTTP 连接池复用到这条已经失效的连接时就报这个错；而流式接口的异常发生在
**订阅之后**，`try/catch` 根本接不住——这是曾经"对话请求失败 500"的真实原因。

`ChatService.streamTokens` 因此做了三层处理：

1. **连接期失败自动重试**：还没吐出任何 token、且距发起不到 8 秒时重试一次（新连接基本能成）；
2. **重试仍失败则降级**：改用抽取式回答，SSE 照常推完，`citations` / `done` 事件照常下发；
3. **生成中途断开**：在已输出内容后追加"模型连接中断"提示，再串上召回到的条款原文。

于是前端只会感到"这次回答质量差了点"，不会看到 500；日志里对应两条 WARN：
`模型流式连接失败，重试一次` 与 `模型流式输出中断（已输出=...）`。

## 配置说明（application.yml 关键项）

| 配置 | 说明 |
|---|---|
| `spring.ai.openai.base-url` | OpenAI 兼容端点：通义 `https://dashscope.aliyuncs.com/compatible-mode`；Kiwi `https://api.Kiwi.com` |
| `spring.ai.openai.api-key` | 模型 API key（环境变量 `LLM_API_KEY`） |
| `spring.ai.openai.chat.options.model` | 聊天模型，默认值见 `application.yml`（可用 `LLM_CHAT_MODEL` 覆盖）；须填服务端真实支持的模型名 |
| `spring.ai.openai.embedding.options.model` | 向量模型，默认 `text-embedding-v3`（1024 维）；换 bge 系列时改这里的模型名即可（如服务端部署的 `bge-large-zh-v1.5`） |
| `spring.ai.vectorstore.qdrant.*` | 向量库地址与集合名（`QDRANT_HOST` / `QDRANT_GRPC_PORT`）；端口是 gRPC 的 6334，不是 6333 |
| `app.vector-dimension` | 向量维度（环境变量 `EMBEDDING_DIMENSION`），Qdrant 建集合时定死，换 embedding 模型必须同步改 |
| `app.demo-mode` | 演示模式开关（环境变量 `APP_DEMO_MODE`），默认 `false` |
| `app.chunking.chunk-size / overlap` | 切分参数：单块最大字符数 / 预留重叠量 |
| `app.retrieval.min-score` | **向量路**的最低相似度，低于它视为无召回（防编造的第一道门槛）。默认 `0.60`，取值依据见下 |
| `app.retrieval.hybrid-enabled` | 是否开启 BM25 混合召回 |
| `app.retrieval.fusion` | 两路融合策略：`rrf`（默认，倒数排名融合，只看名次）/ `weighted`（线性加权，旧行为，做对照用） |
| `app.retrieval.rrf-k` | RRF 平滑常数，默认 60；越大则各名次之间分差越平缓 |
| `app.retrieval.vector-weight / bm25-weight` | 融合权重，默认 1.0 / 0.25。RRF 下作用于名次分，`weighted` 下作用于原始分 |
| `app.retrieval.min-bm25-score` | **BM25 路**的原始分下限（默认 1.5），低于它的块不参与融合，挡掉只蹭到零星字词的低分块 |
| `app.retrieval.bm25-introduce-new` | 向量路**已有召回**时，是否允许 BM25 引入新块，默认 `true`；填 `false` 则退化成"BM25 只能给已召回候选重排"，用于对照实验 |
| `app.retrieval.margin` | 相似度尖峰门限（top1 与候选均值之差），默认 `0` 即关闭；见下方"试过但不建议" |

**为什么 `min-score` 是 0.60**：这个数是量出来的，不是拍的。用内置评测集跑一遍，向量路 top1
原始相似度分布是——正样本 `0.648 ~ 0.847`，负样本 `0.475 ~ 0.576`，中间有一条 0.07 宽的空白带，
0.60 落在带子中间。旧的 0.20 完全没有分界作用（三条负样本全在它上方），所以"无召回不编造"
在实测中根本不生效。评测面板的"语义分"列就是用来复现这条结论的。

**为什么融合用 RRF 而不是线性加权**：两路分数的量纲根本不可比。BM25 归一化后 top1 恒为 1.0，
乘 0.3 权重就是 0.3 分；而向量相邻两名常常只差 0.02。线性加权下 BM25 那一个权重项必然盖过
语义差距，实测把 MRR 从 0.917 拖到 0.767、Hit@5 少命中 1 条。RRF 只用名次
（`1.0/(60+向量名次) + 0.25/(60+BM25名次)`），不碰分数，从机制上消除这个问题。

**BM25 的准入条件**：BM25 只认字面、不认语义，问题里只要有一个字出现在块里它就能给出正分。
所以"能不能引入新块"绑在**向量路的结论**上，而不是无条件开放：

- 向量路**有召回** → 说明这个问题库里确实有答案，此时让 BM25 补充字面强命中的新块（精确术语、
  条文编号、数字指标）才有意义，这是混合检索真正的收益；
- 向量路**无召回** → 问题多半不在知识库范围内，此时任何 BM25 命中都是"凑字"，一律拒绝。
  否则问"今天食堂吃什么"也会凑出几条食堂条款，"无召回不编造"就成了死代码；
- 演示模式（哈希向量无语义判别力）与向量库故障时无条件放开，因为此时 BM25 是唯一手段。

**试过但不建议：相似度"尖峰门限"**。曾试过用"top1 与候选均值之差小于门限就判无召回"来区分
闲聊和真问题，实测是**纯负收益**：同一主题的规范条文彼此都很像，正样本的候选分本来就平摊
（top1−均值仅 0.03），设 0.04 会把两条正样本直接杀成"无召回"；而闲聊问句因为完全不沾边、
候选分掉得更快，反而形成"假尖峰"，一条都没挡住。结论：本语料只能用绝对阈值。
`margin` 配置项保留下来，换跨领域混合库时值得再试一次。

## 数据目录

```
data/
├── docs/            上传的原始文档（重建索引的数据来源），文件名为 docId__原名
├── registry.json    文档注册表（文档与块的元信息、块原文）
└── qdrant-storage/  仅当用 docker compose 在本机跑 Qdrant 时的数据卷

向量本身不在这里：本机 compose 时落在上面的 qdrant-storage/，
连虚拟机/远程 Qdrant 时在对方磁盘上（用 `docker volume inspect` 或 `-v` 参数确认挂载点）。
--app.data-dir 只影响 docs/ 与 registry.json。
sample_docs/         随工程自带的示例规范（自拟文本，非正式规范原文）
```

> **换了 Embedding 模型（或开关演示模式）一定要"重建索引"**：Qdrant 里存的是旧模型算出的
> 向量，新旧向量维度可能一样（都是 1024），混在一起不会报错，但余弦相似度不在同一量纲上——
> 真实语义向量（0.5 上下）会把哈希兜底向量（0.0x）整体压死，表现为"老文档怎么都搜不到"。
> 现在没有本地向量文件可做模型标识比对，**这一步只能靠人记住**：改模型就点一次"重建索引"。

## 可选的对照实验

1. **切分参数对比**：固定问题集（自拟 15~20 个问题并人工标注应召回的条款），对比 `chunk-size` 取 200 / 500 / 1000 时的召回命中率（Hit@5），说明"块太小语义不完整、太大噪声多"的权衡；再对比"条款切分 vs 纯 TokenTextSplitter 切分"。
2. **混合检索 vs 纯向量**：直接点知识库页的"开始评测"——同一个问题集会同时跑两种模式并排给出
   Hit@1 / Hit@5 / MRR，不用改配置重启。想对比不同融合策略，把 `fusion` 在 `rrf` / `weighted`
   之间切换后重跑，标签里会带上当前策略（"混合检索（RRF）"/"混合检索（加权）"），两张表能对上号。
   注意：当前语料只有 5 篇同主题规范、1436 块，纯向量已接近饱和（Hit@1 11/12），混合检索体现不出
   增益；要看到 BM25 的价值，得换成含多领域文档、且提问里常带精确术语/编号的库。
3. **阈值与拒答**：拒答由 `min-score`（向量路）决定。先在评测面板的"语义分"列看清正负样本的原始分
   分布，再决定阈值——本项目实测正样本 `0.648~0.847`、负样本 `0.475~0.576`，所以取 0.60。
   把 `min-score` 依次取 0.3 / 0.5 / 0.6 / 0.7 重跑评测，可以直接看到"拒答率"与"正样本误拒"的权衡曲线。
4. **BM25 准入条件的对照**：把 `bm25-introduce-new` 改成 `false` 再跑评测，对比"允许 BM25 在向量
   有召回后补块"与"只让它重排"的差异；再把向量路强行清空（问一个无关问题）验证拒答不受影响——
   这是本项目的真实踩坑记录：早期版本让 BM25 无条件召回，拒答率直接归零。
5. **topK 对回答质量的影响**：topK 取 1 / 3 / 5 / 10，对比引用条款覆盖率和回答中引用标注的准确性。
6. **多轮改写是否值得**：同一个追问（"那颜色呢"）分别带 `history` 和不带 `history` 请求，
   对比召回条款是否切题；改写的代价是多一次模型调用（约 1 秒），只在短问句/含指代词时触发。

## 测试

```bash
mvn test
```

包含 `ClauseTextSplitterTest`（条款切分/章标题合并/超长兜底/PDF 脏排版/目录与页脚过滤）、
`ClauseLabelsTest`（编号压紧/三类条款号/表格数字拒绝/回溯）、`Bm25IndexTest`（关键词排序/删除/分词）、
`HybridRetrieverTest`（8 条检索决策矩阵：无召回不答、BM25 不得单独引入新块、向量路有召回时允许 BM25 补块、
关闭该开关后只重排、演示模式/向量库故障时放开）、
`DocNamesTest`（磁盘文件名前缀清洗）、`DocumentRegistryTest`（落盘重载/前缀还原/损坏留档/批量销记）、
`HostsFileGuardTest`（5 条：未设置不处理、文件不存在清除、空 hosts 等于无效、合法文件保留、非法路径不抛异常）
与 `ApiErrorMappingTest`（接口层 5 条：空问题 400、未知任务 404、缺上传字段 400、类型不支持 400、写冲突 409），
共 **45 个用例**。除最后一个用 `@WebMvcTest` 只加载 Web 层（3 秒，不连库、不调模型）外，其余都是纯单元测试。

## 常见问题

- **回答永远是【抽取式】，同时索引统计里 `vectorDimension` 显示 0、引用分数都是 `0.004` 量级**：
  八成是启动参数里残留了 `-Djdk.net.hosts.file=...`，而指向的 `hosts` 文件已经删了。
  JDK 在这种情况下**不会回退系统 DNS**，而是对任何域名直接抛 `UnknownHostException`，
  于是模型调用、查询向量化、维度探测全部失败。三个症状其实是一个根因。
  确认办法：启动日志里搜 `HostsFileGuard`，或临时 `curl` 直连百炼看是否 401 秒回（通就说明网络没问题）。
  处理：删掉 IDEA 运行配置 VM options 里那个参数（新版本代码已会自动清除并打 WARN，但建议手工清理干净）。
- **启动报 `UNAVAILABLE` 或 `failed to connect`**：九成是端口映射问题。Qdrant 要**两个**端口——
  `6333` 给 REST/dashboard，`6334` 给 gRPC，而应用只走 6334。只映射 6333 时 dashboard 能开、
  应用却连不上，很容易误判成代码问题。确认办法：`docker ps` 看 PORTS 是否两个都在，
  再 `curl <host>:6333/healthz` 与 `telnet <host> 6334` 分别验证。
- **启动报「向量维度是 X，但配置 app.vector-dimension=Y」**：说明换了 embedding 模型但没重建集合。
  Qdrant 的维度是建库时定死的，不能像 Chroma 那样"混进去再说"。二选一：把配置改回 X，
  或删集合（`curl -X DELETE http://<host>:6333/collections/safety-regulations`）后点"重建索引"。
- **`docker pull` 卡在 `Download complete`、一个 `Pull complete` 都没有**：这是加速站把上游
  限流了（日志里可能出现 `toomanyrequests`）。已下载的层会被缓存，**重复 pull 几次就能累积完成**，
  不必换源、也不必改配置。
- **回答总是"未找到相关规范"**：先在知识库菜单确认文档已入库；再到"检索测试"看召回分数，必要时调低 `app.retrieval.min-score`。（`min-score`、`min-bm25-score` 等检索参数改完即时生效，只有切分参数才需要"重建索引"。）
- **无关问题也硬答**：说明门槛太松 —— 检查 `min-score` 是否被设成 0，以及两个 BM25 门槛是否被显式填 0（0 表示关闭）。
- **改了切分参数不生效**：切分只发生在入库时，改完参数后在知识库菜单点"重建索引"。
- **上传报"未解析出任何文本内容"**：多半是**扫描版 PDF**（整页是图片、没有文字层）。
  本项目用的是 `PagePdfDocumentReader`，只读文字层；这类文件需要先 OCR 再入库。
  判断办法：拖进阅读器能选中文字就是有文字层。
- **批量上传时某个文件一直"排队中"**：入库是单线程串行跑的，前面的大文件没跑完后面就会排队；
  查 `GET /api/kb/uploads/{jobId}` 能看到当前在跑哪个文件、已经向量化到第几块。
- **换了 Embedding 模型 / 开关了演示模式后检索结果全乱**：Qdrant 里存的是旧模型算出的向量，
  两组向量不能混用。到知识库菜单点一次"重建索引"即可（先按 docId 删旧向量，再按 `data/docs/`
  重新向量化；1436 块实测约 30 秒，取决于 embedding 接口速度）。若维度也变了，先改
  `app.vector-dimension` 并删集合，否则写入会被 Qdrant 直接拒绝。
- **"删除文档"删干净了吗**：删，而且比 Chroma 时代更彻底。Qdrant 支持按 payload 条件删除，
  所以 `delete(docId)` 是**一次请求删完整篇**（`filter.must[docId === "xxx"]`），不再需要
  "先分页捞全库 id 求交集"；删不存在的点也只是幂等返回，不会让服务端崩。
  验证办法：`curl -X POST <host>:6333/collections/safety-regulations/points/count -d '{"exact":true}'`，
  删完文档后点数应正好减少该文档的块数。
