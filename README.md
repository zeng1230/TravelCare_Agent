# TravelCare-Agent

## PR-1C Observability and Runtime Diagnostics Boundary

- PR-1A Security Boundary is complete. Spring Security remains enabled and PR-1A 401/403 behavior must continue to pass.
- PR-1B Async Reliability Boundary is complete. Outbox, Worker, retry, DLQ, and Reconciliation semantics must continue to pass.
- Current work is **PR-1C Observability and Runtime Diagnostics Boundary**, not Stage 11.
- PR-1C is runtime observability hardening for metrics, structured logs, traceId propagation, and diagnostics permission boundaries. It is not a new AI feature phase.
- Tests remain fixed to `travelcare.agent.provider=mock`; they must not call DeepSeek or any real LLM provider.
- Actuator exposure is intentionally small: `GET /actuator/health` is public with no details, while `GET /actuator/metrics` and `GET /actuator/metrics/{meterName}` require ADMIN. Sensitive endpoints such as env, beans, and configprops are not exposed.
- Metrics use stable low-cardinality tags only. Counter names end with `.total`; timer names keep `duration` or `latency`, with units decided by the Micrometer registry. Metric tags must not contain user/session/order/trace IDs, prompts, tokens, secrets, raw errors, or dynamic model/deployment IDs.
- Structured logs include `traceId` through MDC and use redacted, bounded business messages. Raw prompts, provider raw responses, authorization headers, JWTs, API keys, and secrets are not business-log fields.
- Design document: [`docs/pr1/observability-runtime-diagnostics.md`](docs/pr1/observability-runtime-diagnostics.md).

Test command:

```powershell
.\mvnw.cmd test
```

## Production Readiness Phase 1

TravelCare-Agent 已完成 RC 验收，当前进入 **Production Readiness Phase 1**，阶段名称为
**PR-1: Security, Reliability, Observability Hardening**。当前已完成 **PR-1A Security Boundary**。

PR-1A 的目标是准生产可信后端硬化第一步：建立认证、授权、角色边界、对象级权限和基础脱敏能力。
这不是 Stage 11，也不是继续堆 AI 功能；本阶段不接入 Spring AI、LangGraph、MCP Tool Store、
多 Agent 平台、管理后台、微服务或 Kubernetes。

### PR-1A 认证方式

- 核心 `/api/**` 默认需要 `Authorization: Bearer <jwt>`。
- 未认证或非法 token 返回 `401`。
- 已认证但角色或对象权限不足返回 `403`。
- `GET /health` 和 `GET /actuator/health` 公开。
- Swagger、`doc.html`、`/v3/api-docs/**` 仅在 `local/dev/test` profile 下公开。
- `/api/dev/auth/token` 默认关闭；仅在 `local/dev/test` profile 且显式设置
  `travelcare.security.dev-auth-enabled=true` 时注册。
- 生产环境如果缺失 JWT secret、使用默认 secret，或 secret 短于 32 字节，应用启动失败。

开发 token 示例：

```http
POST /api/dev/auth/token
Content-Type: application/json

{"userId":1001,"tenantId":"default","roles":["USER"]}
```

### PR-1A 角色边界

| API surface | USER | OPERATOR | EVALUATOR | ADMIN |
| --- | --- | --- | --- | --- |
| Session / Message | 仅本人和本租户 | 否 | 否 | 全部 |
| Workflow | 仅本人 session 关联 | 人工处理相关 | 否 | 全部 |
| Memory | 仅本人 | 否 | 否 | 全部 |
| Trace / Dry Run / Diff | 否 | 否 | 否 | 是 |
| Evaluation | 否 | 否 | 是 | 是 |
| Human Review | 否 | 是 | 否 | 是 |
| Audit | future protected surface | future protected surface | future protected surface | future protected surface |
| Order REST API | 当前不存在；订单归属通过 message/workflow/tool 闭环校验 | 当前不存在 | 当前不存在 | 当前不存在 |

### PR-1A 测试

```powershell
docker compose -f travelcare_dev/docker-compose.yaml up -d
.\mvnw.cmd test
```

Surefire 固定 `travelcare.agent.provider=mock`，测试不得调用 DeepSeek 或真实 LLM Provider。
安全测试使用 `SecurityTestTokenFactory` 生成 JWT，不在 test profile 下关闭 Spring Security。

详细设计见 [`docs/pr1/security-boundary.md`](docs/pr1/security-boundary.md)。

> 旅游退款客服场景下的受控式 AI Agent 后端。

## 项目定位

TravelCare-Agent 是旅游退款客服场景下的受控式 AI Agent 后端。LLM 负责意图理解和回复草拟，确定性工作流负责事实查询、规则判断、工具授权、审计和异常恢复。

项目基于 Spring Boot 模块化单体实现，围绕“订单退款咨询”构建可运行的 AI 客服链路，并把会话、RAG、Memory、确定性工作流、工具调用、异步任务、人工复核、执行追踪、诊断回放和离线评测组织为可持久化的工程闭环。

这个项目当前可以准确描述为：

- **受控 Agent 后端**：存在 Orchestrator、Prompt、Provider 和工具调用，但不是让模型自主规划并直接执行敏感动作。
- **具备最小 RAG 闭环**：支持知识导入、分块、检索、Answerability 判断和 Citation Policy；检索采用 MySQL FULLTEXT/LIKE，不是向量 RAG。
- **默认 Mock、可选 DeepSeek**：真实 Provider Adapter 已实现，但默认配置和全量测试固定使用 Mock，不宣称已完成生产级真实模型验证。
- **按生产问题设计的学习项目**：具备幂等、审计、异步恢复、Trace、Dry Run、Diff 和 Evaluation，但没有真实供应商、支付或退款执行能力。

核心原则：**LLM 提议，后端验证、授权、执行、持久化并审计。**

## 核心业务链路

```mermaid
flowchart TD
    Client[API Client] --> Session[Session / SessionEvent]
    Session --> Orchestrator[Agent Orchestrator]
    Orchestrator --> Context[Context Assembler]
    Context --> Retrieval[RAG Retrieval]
    Context --> Memory[Agent Memory]
    Retrieval --> Answerability[Answerability / Citation Policy]
    Orchestrator --> Model[AgentModelService]
    Model --> Mock[Mock Provider]
    Model -. explicit config .-> DeepSeek[DeepSeek Provider]
    Orchestrator --> Workflow[Order Refund Inquiry Workflow]
    Workflow --> Tool[ToolService / GetOrderTool]
    Tool --> Order[MockOrderAdapter]
    Workflow --> Policy[RefundEligibilityPolicy]
    Workflow --> Human[Human Review]
    Session --> AgentRun[AgentRun / Replay]
    Orchestrator --> Trace[Agent Trace]
    Trace --> DryRun[Diagnostic Dry Run]
    DryRun --> Diff[Trace Diff]
    Trace --> Evaluation[Persistent Evaluation / Baseline]
```

订单状态来自 `OrderAdapter`，退款资格由 `RefundEligibilityPolicy` 基于结构化订单事实判断。RAG、Memory 和 LLM 可以增强解释与表达，但不能覆盖退款结论，也不能直接触发退款、支付、取消或改签。

## 技术栈

- Java 17、Spring Boot 3.3.4
- MyBatis-Plus、MySQL 8、Flyway
- Redis、RabbitMQ
- JUnit 5、Spring Boot Test、Mockito
- DeepSeek OpenAI-compatible Chat Completions API（可选）
- Mermaid、Markdown Evaluation Report

## 已实现能力

| 领域 | 当前能力 | 关键实现 |
| --- | --- | --- |
| Conversation | 创建会话、顺序事件流、上下文读取 | `SessionService`、`SessionEventService` |
| Workflow | 退款咨询工作流、步骤和任务持久化 | `WorkflowEngine`、`OrderRefundInquiryWorkflow` |
| Tool / Policy | 工具生命周期、订单查询、确定性退款规则 | `ToolService`、`GetOrderTool`、`RefundEligibilityPolicy` |
| Reliability | 幂等键、请求 hash、Redis Lock、异步重试 | `IdempotencyService`、`WorkflowTaskWorker` |
| RAG | 文档导入、段落分块、有效期过滤、FULLTEXT/LIKE 检索 | `KnowledgeIngestionService`、`RetrievalService` |
| Answerability | 可回答性判断、引用准入、低质量证据拒绝、Fallback | `AnswerabilityService`、`CitationPolicy` |
| Memory | 用户偏好和行程上下文 | `MemoryService` |
| Agent | 固定编排、Prompt 版本、Mock/DeepSeek Provider、LLM Safety Gate | `AgentOrchestrator`、`AgentModelService`、`ModelSafetyGate` |
| Human Review | Case 创建、分配、处理和工作流恢复 | `HumanReviewService` |
| Audit | 关键业务动作及 evidence 持久化 | `AuditService` |
| AgentRun | 模型调用和回复上下文追踪 | `AgentRunService` |
| Replay | 按 AgentRun 只读聚合历史事实 | `AgentRunReplayService` |
| Trace | Run、Span、Event、Snapshot 和诊断聚合 | `TraceService`、`TraceQueryService` |
| Dry Run / Diff | 基于脱敏快照的无副作用模拟和差异分析 | `DiagnosticDryRunService`、`TraceDiffService` |
| Evaluation | Dataset、Case、Run、Scorer、Report、Baseline Regression | `EvaluationRunnerService`、`BaselineComparisonService` |

## 阶段演进

| 阶段 | 已完成内容 |
| --- | --- |
| Stage 1 | 退款咨询最小闭环 |
| Stage 2 | RAG 与知识检索 |
| Stage 3 | Trace 与可回放基础 |
| Stage 4 | Evaluation 初版 |
| Stage 5 | Provider 与真实 LLM 可选接入 |
| Stage 6 | 文档与展示增强 |
| Stage 7 | Trace、Diagnostics、Dry Run、Diff |
| Stage 8 | Persistent Evaluation 与 Baseline Regression |
| Stage 9 | RAG Answerability 与 Citation Gate |
| Stage 10 | 真实 LLM 可控运行，包括 Provider 抽象、AgentRun Tracking、LLM Safety Gate |

## RAG 与 Answerability

知识导入接口将文本按段落写入 `knowledge_documents` 和 `knowledge_chunks`。检索优先使用 MySQL FULLTEXT，零结果时回退到 LIKE 匹配，并过滤文档状态和有效期。

Stage 9 在“检索命中”与“允许回答”之间增加了 Answerability Gate：

1. `RetrievalService` 返回带 `retrievalRunId`、chunk、document、source URI、有效期和 score 的候选证据。
2. `AnswerabilityService` 检查证据是否属于当前检索、是否过期、分数是否达到阈值。
3. `CitationPolicy` 给出 `REQUIRED`、`OPTIONAL` 或 `FORBIDDEN`。
4. 无证据、低匹配或过期证据进入确定性 Fallback，不把未经验证的内容交给模型自由发挥。
5. 已锁定的退款等业务结论禁止由 Citation 改写；RAG 最多解释结论。

当前 RAG 的边界：没有 embedding、向量数据库或 reranker；同步文本导入和 MySQL 检索适合验证工程闭环，不代表已经解决大规模语义检索问题。

## Agent 与 LLM 边界

`AgentOrchestrator` 使用固定编排完成上下文组装、意图识别、工作流启动、人工复核和回复生成。Stage 10 将模型调用统一到 `ChatModelProvider`，使用单条逻辑 AgentRun 记录 Provider、Prompt 版本、请求/响应 hash、token、fallback 和 Safety Decision。

模型输出必须通过严格结构化解析和 `ModelSafetyGate`。Safety Gate 可以给出 `ALLOW`、`FALLBACK`、`CLARIFY`、`HANDOFF` 或 `BLOCK`，并在输出包含危险业务承诺、敏感信息、越权工具提议、伪造 Citation 或退款规则冲突时失败关闭。Stage 10 v1 只允许 `GetOrderTool / READ_ORDER` 作为候选只读提议，模型本身不会执行工具。

LLM 可以：

- 识别意图并提取候选订单号。
- 基于后端提供的确定性结果组织客服回复。
- 使用通过 Answerability/Citation Policy 的知识证据增强解释。

LLM 不可以：

- 提供或修改订单事实。
- 决定退款资格、金额或资金动作。
- 绕过 Workflow、Policy、ToolService 和 Human Review。
- 将 RAG 或 Memory 当成业务授权依据。

因此，本项目是**规则工作流主导的受控 Agent**，不是自主规划、多 Agent 协作或模型直接执行工具的通用 Agent。

### Stage 10 收口

- **Stage 10A - Provider Abstraction**：使用统一 `ChatModelProvider` 接入确定性 Mock 和可选 DeepSeek，网络、超时、HTTP 和响应错误转换为安全错误并允许确定性 fallback。
- **Stage 10B - AgentRun Tracking**：一次 `AgentModelService` 调用对应一条逻辑 AgentRun，记录 Provider 模式、Prompt 版本、请求/响应 hash、token、fallback、Trace 和安全错误码，不持久化完整 Prompt 或 Provider 原始响应。
- **Stage 10C - LLM Safety Gate**：Provider 输出经过严格结构化解析、Citation 校验、危险承诺检测、敏感信息检测、Tool Proposal 白名单和退款 Policy 冲突检查；最终结果为 `ALLOW`、`FALLBACK`、`CLARIFY`、`HANDOFF` 或 `BLOCK`。

## Trace、Replay 与 Dry Run

三个概念承担不同职责：

- **AgentRun**：记录一次业务回复或模型调用的输入、Prompt、Provider、输出和状态。
- **Replay**：按 AgentRun ID 只读聚合历史事件、知识、Memory、Workflow 和 Audit，不重新执行任何逻辑。
- **Agent Trace**：以 Run/Span/Event/Snapshot 描述一次执行过程，关联 Workflow Step、Tool Call、Audit 和 AgentRun。

Diagnostic Dry Run 只接受具备必要结构化快照的 Trace。它创建独立的 `dryRun=true` Trace，使用快照型 Tool/Retrieval 执行器和 Mock Provider，不调用 DeepSeek、RabbitMQ 或 `MockOrderAdapter`，也不新增业务表记录。Trace Diff 输出字段变化、归一化摘要、解释和风险等级。

Trace 是诊断视图，不替代 Session、Workflow、Tool Call 或 Audit 等业务事实源。

## Evaluation 与回归基线

Stage 8 后 Evaluation 不再只有测试生成的单份 Markdown：生产代码中已经具备持久化离线评测领域模型。

- Dataset 支持 `DRAFT`、`ACTIVE`、`ARCHIVED` 和版本克隆。
- Case 绑定历史 source trace 和结构化 expectation。
- Run 固定使用 Mock Provider 和注册的 Prompt Stub，通过 Stage 7 Dry Run 执行。
- Scorer 覆盖 Policy、Workflow、Trace 结构、事件、输出断言、副作用、Diff 风险、Answerability、Citation 和业务越权。
- Baseline 可从已完成 Run 提升，并在后续 Run 中标记 `UNCHANGED`、`REGRESSION`、`IMPROVED`、`NEW`、`MISSING`。
- 每个 Run 的 Markdown 报告写入 `target/evaluation/runs/{runId}_report.md`。

Evaluation 是离线、确定性、无业务副作用的回归系统，不是线上 A/B 平台，也不评估真实供应商网络质量。

## 主要 API

### 会话与工作流

```http
POST /api/sessions
POST /api/sessions/{sessionId}/messages
GET  /api/sessions/{sessionId}/events
GET  /api/sessions/{sessionId}/context?query=refund
GET  /api/sessions/{sessionId}/workflows
GET  /api/workflows/{workflowId}
GET  /api/workflows/{workflowId}/steps
```

### 知识、Memory 与人工复核

```http
POST /api/knowledge/documents
GET  /api/knowledge/search?query=refund
POST /api/knowledge/answerability/check   # 仅 local/dev/test profile
GET  /api/memories/users/{userId}
POST /api/memories/users/{userId}
GET  /api/human-review/cases
POST /api/human-review/cases/{caseId}/assign
POST /api/human-review/cases/{caseId}/resolve
```

### AgentRun 与 Trace

```http
GET  /api/agent-runs/{agentRunId}
GET  /api/agent-runs/{agentRunId}/replay
GET  /api/sessions/{sessionId}/agent-runs?pageNo=1&pageSize=20
GET  /api/agent-traces/{traceId}
GET  /api/agent-traces/by-session/{sessionId}
GET  /api/agent-traces/{traceId}/diagnostics
POST /api/agent-traces/{traceId}/dry-run
GET  /api/agent-traces/{traceId}/diffs/{dryRunTraceId}
POST /api/agentops/debug/qa
```

Human Handoff Context Packet details: [`docs/pr-3a-human-handoff-context-packet.md`](docs/pr-3a-human-handoff-context-packet.md).

AgentOps Debug API details: [`docs/pr-3b-agentops-debug-api.md`](docs/pr-3b-agentops-debug-api.md).

Safety & RAG Quality Evaluation Pack details: [`docs/pr-3c-safety-rag-quality-evaluation-pack.md`](docs/pr-3c-safety-rag-quality-evaluation-pack.md).

### Evaluation

```http
POST   /api/evaluation/datasets
GET    /api/evaluation/datasets/{datasetId}
POST   /api/evaluation/datasets/{datasetId}/activate
POST   /api/evaluation/datasets/{datasetId}/versions
POST   /api/evaluation/datasets/{datasetId}/cases
PUT    /api/evaluation/datasets/{datasetId}/cases/{caseId}
DELETE /api/evaluation/datasets/{datasetId}/cases/{caseId}
POST   /api/evaluation/datasets/{datasetId}/runs
GET    /api/evaluation/runs/{runId}
GET    /api/evaluation/runs/{runId}/results
GET    /api/evaluation/runs/{runId}/report
POST   /api/evaluation/runs/{runId}/promote-baseline
GET    /api/evaluation/datasets/{datasetId}/baseline
```

## 数据模型

| 类别 | 主要表 |
| --- | --- |
| 会话 | `sessions`、`session_events` |
| 工作流 | `workflows`、`workflow_steps`、`workflow_tasks` |
| 工具与幂等 | `tool_calls`、`idempotency_keys` |
| 业务与人工 | `refund_cases`、`human_review_cases` |
| 知识与记忆 | `knowledge_documents`、`knowledge_chunks`、`agent_memories` |
| 审计与模型运行 | `audit_logs`、`agent_runs` |
| 执行追踪 | `agent_trace_runs`、`agent_trace_spans`、`agent_trace_events`、`agent_trace_snapshots` |
| 诊断差异 | `agent_trace_diffs` |
| 离线评测 | `evaluation_datasets`、`evaluation_cases`、`evaluation_runs`、`evaluation_case_results`、`evaluation_baselines` |

Flyway migration 当前为 V1-V12。

## 本地运行

### 环境要求

- JDK 17
- Docker Desktop，或本地 MySQL 8、Redis、RabbitMQ

启动依赖：

```powershell
docker compose -f travelcare_dev/docker-compose.yaml up -d
```

启动应用：

```powershell
.\mvnw.cmd spring-boot:run
```

默认数据库地址为 `jdbc:mysql://localhost:3307/travelcare_agent`。默认账号密码仅用于本地开发，不应直接用于共享或生产环境。

### Stage 10A Provider 配置

Stage 10A 将模型调用入口统一为 `ChatModelProvider`。默认配置使用确定性的
`MockChatModelProvider`，不会创建或访问真实 LLM：

```yaml
travelcare:
  agent:
    provider: mock
    model: mock-stage10a
    prompt-version: stage10a-default
    timeout-ms: 5000
```

手工验证 DeepSeek 时必须显式配置 Provider 和 API Key：

```powershell
$env:TRAVELCARE_AGENT_PROVIDER = "deepseek"
$env:TRAVELCARE_AGENT_MODEL = "deepseek-chat"
$env:TRAVELCARE_AGENT_API_KEY = "<your-api-key>"
$env:TRAVELCARE_AGENT_BASE_URL = "https://api.deepseek.com"
$env:TRAVELCARE_AGENT_TIMEOUT_MS = "8000"
.\mvnw.cmd spring-boot:run
```

不要把 API Key 写入仓库、测试、日志或评测数据。全量测试通过 Maven Surefire
强制使用 Mock Provider。详细设计、fallback 行为和限制见
[`docs/stage10/10A-provider-abstraction.md`](docs/stage10/10A-provider-abstraction.md)。

## 测试

```powershell
.\mvnw.cmd test
```

当前 Release Candidate 基线：

```text
Tests: 200
Failures: 0
Errors: 0
Skipped: 0
Flyway migrations: V1 到 V12
Provider mode: mock
Exit code: 0
```

包含 Spring 上下文的集成测试会连接本地 MySQL、Redis 和 RabbitMQ，因此该结果不是完全无基础设施依赖的内存测试。

## Release Candidate 状态

Stage 10 已完成，当前功能已经不建议继续横向堆 Stage，也不再开启新的 Stage 编号。下一步目标是 RC 总体验收，包括 CI、空库 Flyway、Compose healthcheck、五分钟 Demo、已知限制和展示证据。

RC 收口文档：

- [RC 验收清单](docs/release-candidate/acceptance.md)
- [RC 已知限制](docs/release-candidate/known-limits.md)
- [Stage 10A Provider Abstraction](docs/stage10/10A-provider-abstraction.md)
- [Stage 10B AgentRun Tracking](docs/stage10/10B-agent-run-tracking.md)
- [Stage 10C LLM Safety Gate](docs/stage10/10C-llm-safety-gate.md)

### 五分钟 Demo 入口

前提：使用 `docker compose -f travelcare_dev/docker-compose.yaml up -d` 启动 MySQL、Redis、RabbitMQ，应用以默认 `mock` Provider 运行。

1. 创建会话：

```http
POST /api/sessions
Content-Type: application/json

{"userId":1001,"channel":"WEB"}
```

2. 使用可退款 Mock 订单发送同步咨询：

```http
POST /api/sessions/{sessionId}/messages
Content-Type: application/json

{"content":"Can I refund order ORD-1001?","idempotencyKey":"rc-demo-001","async":false}
```

3. 从响应获取 `traceId`，依次下钻：

```http
GET  /api/agent-traces/{traceId}
GET  /api/agent-traces/{traceId}/diagnostics
POST /api/agent-traces/{traceId}/dry-run
Content-Type: application/json

{"reason":"rc-five-minute-demo","providerMode":"mock","compareAfterRun":true}
```

4. 从 Dry Run 响应获取 `dryRunTraceId`，查看差异：

```http
GET /api/agent-traces/{traceId}/diffs/{dryRunTraceId}
```

5. 展示证据：退款结论来自 `RefundEligibilityPolicy`，Tool Call 有幂等记录，Trace 可下钻，Dry Run 不产生业务副作用，Diff 给出变化和风险等级。

## 项目边界与已知限制

- 没有真实退款。
- 没有真实支付。
- 没有真实供应商订单系统；订单数据来自 `MockOrderAdapter`。
- 没有完整认证授权。
- 没有完整 Outbox、Publisher Confirm、DLQ。
- 没有完整 OpenTelemetry 平台、Prometheus/Grafana 强依赖或 Alerting 平台；PR-1C 仅提供 Observability Lite 的 Actuator health/metrics、Micrometer 指标、structured logs 和 traceId 边界。
- Human Review 是后端复核 Case，不是完整坐席系统。
- DeepSeek Adapter 已实现，但真实网络、限流、费用、SLA 和供应商错误契约未完成生产验证。
- RAG 使用 MySQL FULLTEXT/LIKE，不是向量检索；没有 embedding、reranker 或大规模知识同步。
- Trace、Replay 和 Evaluation 可能包含业务上下文；虽然已有预览和脱敏处理，仍需继续做字段级最小化与权限控制。
- Evaluation 固定使用 Mock Provider 和 Snapshot，不代表真实 LLM 质量或线上效果。
- 当前不能宣称真实生产上线。
- 当前定位是可运行、可验证、可展示的 AI Agent 后端原型。
- 当前不是通用 Agent Platform，不追 Spring AI、LangGraph、MCP、多 Agent、微服务和管理后台。

## 目录索引

```text
src/main/java/travelcare_agent/
├── conversation/   会话与事件
├── agent/          Orchestrator、Prompt、Provider
│   └── safety/     Structured Output 与 LLM Safety Gate
├── workflow/       工作流、任务与 Worker
├── tool/           工具调用与幂等
├── retrieval/      知识导入与检索
├── answerability/  可回答性与 Citation Policy
├── memory/         用户与行程记忆
├── agentrun/       AgentRun 与 Replay
├── trace/          Run、Span、Event、Snapshot
├── dryrun/         诊断模拟与 Trace Diff
├── evaluation/     Dataset、Scorer、Baseline Regression
├── human/          人工复核
├── audit/          审计日志
└── api/            REST API
```

详细阶段设计和验收记录见 [`docs/`](docs/)。
