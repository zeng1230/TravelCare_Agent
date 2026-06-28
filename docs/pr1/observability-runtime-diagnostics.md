# PR-1C Observability and Runtime Diagnostics Boundary

## 背景

TravelCare-Agent 已完成 RC 验收、PR-1A Security Boundary 和 PR-1B Async Reliability Boundary。PR-1C 属于 Production Readiness Phase 1：Security, Reliability, Observability Hardening 的第三步。

本轮不命名为 Stage 11，不继续增加 AI 产品功能，不接入真实 DeepSeek 或真实 LLM Provider。测试继续固定 `travelcare.agent.provider=mock`。

## 目标

- 通过 Actuator health/metrics、Micrometer metrics、structured logs、traceId 和既有诊断能力解释一次 AI 客服请求从 API 入口到 LLM、Workflow、Tool、Outbox、Worker、Reconciliation 的运行状态。
- 保持 PR-1A 的认证、授权、角色边界和脱敏策略。
- 保持 PR-1B 的 Outbox、Worker、retry、DLQ、Reconciliation 语义。
- 用低基数、非敏感指标描述运行状态。

## 非目标

- 不做完整 OpenTelemetry 平台。
- 不强制要求 Prometheus/Grafana。
- 不做管理后台或新的公开诊断 API。
- 不接入 Kubernetes、微服务拆分、多 Agent 平台、Spring AI、LangGraph、MCP Tool Store。
- 不调用真实 DeepSeek、真实支付或真实供应商。

## Actuator 暴露边界

- `GET /actuator/health`：公开访问，`show-details=never`。
- `GET /actuator/metrics`：需要 ADMIN。
- `GET /actuator/metrics/{meterName}`：需要 ADMIN。
- `/actuator/env`、`/actuator/beans`、`/actuator/configprops` 等敏感端点不在 `management.endpoints.web.exposure.include` 中。

PR-1C 只暴露 health 和 metrics。metrics 端点用于运行诊断，不能作为普通 USER 的能力面。

## Metrics 设计

PR-1C 使用 Spring Boot Actuator 和 Micrometer。当前不绑定外部 registry，也不要求 Prometheus/Grafana 环境。

命名约定：

- Counter 指标统一使用 `.total` 后缀，例如 `travelcare.workflow.started.total`。
- Timer 指标保留 `duration` 或 `latency` 命名，例如 `travelcare.workflow.duration`、`travelcare.llm.latency`。实际单位由 Micrometer registry 决定。
- Distribution summary 用于 token 运行时记账边界，例如 `travelcare.llm.input_tokens`、`travelcare.llm.output_tokens`。
- 测试验证 meter 存在、counter count、timer count 和 totalTime，不依赖外部 registry 单位。

## Metric tag 安全规则

允许 tag 只表达低基数运行维度，例如类型、状态、步骤、低基数失败码和 provider mode。

禁止 tag key 或 tag value 包含：

- `userId`
- `tenantId`
- `sessionId`
- `workflowId`
- `orderNo`
- `traceId`
- `prompt`
- `token`
- `secret`

禁止 tag value 出现疑似 UUID、长数字 ID、Bearer/JWT、邮箱、手机号、订单号模式、raw error、raw prompt、provider raw response。

LLM `model` tag 必须归一化。当前白名单包含 `mock-stage10a` 和 `deepseek-chat`；其他未知、动态 deployment id、request id 或完整 provider model path 统一记录为 `unknown`。

traceId 不作为 metric tag。traceId 只进入 HTTP response、MDC 日志和可持久化诊断记录。

## Workflow Metrics

- `travelcare.workflow.started.total`
- `travelcare.workflow.completed.total`
- `travelcare.workflow.failed.total`
- `travelcare.workflow.need_human.total`
- `travelcare.workflow.duration`

Tags：

- `workflowType`
- `status`
- `currentStep`
- `failureCode`

## Tool Call Metrics

- `travelcare.tool.call.started.total`
- `travelcare.tool.call.completed.total`
- `travelcare.tool.call.failed.total`
- `travelcare.tool.call.unknown.total`
- `travelcare.tool.call.skipped.total`
- `travelcare.tool.call.retry.total`
- `travelcare.tool.call.duration`

Tags：

- `toolName`
- `status`
- `failureCode`
- `sideEffecting`

UNKNOWN、retry、skipped 单独计数。request/response JSON、orderNo、userId 不进入 tags。

## LLM Metrics

- `travelcare.llm.request.total`
- `travelcare.llm.success.total`
- `travelcare.llm.failure.total`
- `travelcare.llm.fallback.total`
- `travelcare.llm.safety_block.total`
- `travelcare.llm.latency`
- `travelcare.llm.input_tokens`
- `travelcare.llm.output_tokens`

Tags：

- `provider`
- `model`
- `mode`
- `result`
- `failureCode`
- `safetyDecision`

Mock provider 也产生指标。PR-1C 的 token metric 是 runtime accounting boundary：当 provider 没有真实 token 使用量时，使用当前 response usage 字段或估算值，不能代表真实供应商计费。

## Safety Metrics

- `travelcare.safety.decision.total`
- `travelcare.safety.blocked.total`
- `travelcare.safety.allowed.total`
- `travelcare.safety.fallback.total`

Tags：

- `decision`
- `reasonCode`
- `providerMode`

用户原文、raw prompt、内部推理文本不进入指标。

## Outbox / Worker / Reconciliation Metrics

Outbox：

- `travelcare.outbox.event.created.total`
- `travelcare.outbox.event.published.total`
- `travelcare.outbox.event.retry.total`
- `travelcare.outbox.event.failed.total`
- `travelcare.outbox.publish.latency`
- `travelcare.outbox.backlog`

Worker：

- `travelcare.worker.task.started.total`
- `travelcare.worker.task.succeeded.total`
- `travelcare.worker.task.failed.total`
- `travelcare.worker.task.retry_scheduled.total`
- `travelcare.worker.task.skipped.total`
- `travelcare.worker.task.dead_lettered.total`

Reconciliation：

- `travelcare.reconciliation.created.total`
- `travelcare.reconciliation.resolved_success.total`
- `travelcare.reconciliation.resolved_failed.total`
- `travelcare.reconciliation.unknown.total`
- `travelcare.reconciliation.pending`

Gauge 仅做轻量 count：outbox backlog 统计 NEW/RETRYING/PUBLISHING，reconciliation pending 统计 PENDING。不 join，不读取 payload_json，不做复杂 dashboard 查询。

## Structured Logging 策略

日志 pattern 包含 MDC `traceId`。关键业务事件记录低敏定位字段，例如 workflowType、toolName、status、failureCode、attempts。

普通业务日志不输出：

- Authorization header
- JWT
- API Key
- provider secret
- raw prompt
- raw provider response
- raw stack trace

异常处理响应只返回脱敏后的 errorCode、message、traceId。异常不会被吞掉；服务层仍通过状态、指标、持久化记录和受控日志暴露排障信息。

## TraceId 贯穿设计

`TraceIdFilter` 在 HTTP 入口生成或复用 traceId，写入 MDC，并通过 `X-Trace-Id` 返回。

PR-1C 复用既有 trace 体系：

- HTTP response
- session event metadata
- Agent run / trace event
- workflow
- tool call
- outbox event
- workflow task
- reconciliation job
- logs

异步边界继续使用 PR-1B 已引入的 traceId 字段和 payload 传播策略。traceId 允许作为日志字段和诊断查询线索，不允许作为 metric tag。

## Runtime diagnostics 权限边界

PR-1C 不新增管理后台，不新增大量公开诊断 API。

既有权限边界保持不变：

- Trace / runtime diagnostics：仅 ADMIN。
- Evaluation：EVALUATOR 或 ADMIN。
- Human Review：OPERATOR 或 ADMIN。
- 普通 USER 不能访问 runtime diagnostics。

返回数据继续经过 RedactionService 或等价脱敏策略处理。

## 测试清单

- Actuator health 可访问。
- `/actuator/metrics` 和 `/actuator/metrics/{meterName}` 未认证返回 401。
- USER 访问 metrics 返回 403。
- ADMIN 访问 metrics 返回 200。
- Workflow started/completed/failed/need_human/duration 指标存在。
- Tool success/failure/unknown/skipped/retry/duration 指标存在。
- LLM mock provider success/failure/fallback/safety block/latency/token 指标存在。
- Safety allowed/blocked/fallback 指标存在。
- Outbox created/published/retry/failed/backlog 指标存在。
- Worker retry/skipped/dead-letter 指标存在。
- Reconciliation pending/resolved/unknown 指标存在。
- Metric tag key/value 不含敏感字段、高基数字段或疑似动态 ID。
- 全量 `.\mvnw.cmd test` 继续固定 mock provider，不调用真实 LLM。

## 后续演进方向

- 可以按需增加外部 registry，例如 Prometheus registry，但不能改变当前 tag 安全规则。
- 可以增加采样 trace 或 OpenTelemetry bridge，但不应替代现有业务事实表和 PR-1A 权限边界。
- 可以在已有 ADMIN 诊断能力上增加只读查询，但需要继续做脱敏、分页和对象级访问控制。
