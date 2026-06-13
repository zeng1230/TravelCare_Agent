# Stage 7A Agent Trace Foundation

## 目标

Stage 7A 为每次用户消息建立可查询的 Agent Execution Trace，将请求、上下文、检索、模型、工作流、步骤、工具、Policy、审计、人工复核和最终输出组织为统一的 run/span/event/snapshot 诊断视图。

Trace 是诊断视图，不替代 SessionEvent、Workflow、WorkflowStep、ToolCall、AuditLog、HumanReviewCase 或 AgentRun 等业务事实源。业务执行不依赖 trace 写入成功。

## 数据模型

- `agent_trace_runs`：一次用户消息的根执行记录。
- `agent_trace_spans`：分层执行节点及状态、耗时和父子关系。
- `agent_trace_events`：fallback、timeout、handoff、guardrail、retry、cache 和幂等复用等离散事件。
- `agent_trace_snapshots`：同步脱敏后的输入、输出和 existing-record 引用。

`agent_runs`、`tool_calls`、`workflow_steps`、`audit_logs` 仅增加 nullable `trace_id/span_id`。原唯一约束和业务查询语义保持不变。

## 传播与事务

- 同步调用栈使用 `TraceContextHolder`，scope 结束后清理 ThreadLocal 和 MDC。
- 异步链路显式把 `traceId/parentSpanId` 写入 WorkflowTask payload 和 RabbitMQ message body，worker 不依赖提交线程的 ThreadLocal。
- Trace 写入由 `TracePersistenceService` 使用 `REQUIRES_NEW` 独立事务完成。
- Root run 或 root span 创建失败时返回 `traceAvailable=false`，不返回不可查询的 traceId。
- 后续 span/event/snapshot/link 写失败仅记录脱敏后的 warn 日志，不影响业务事务。

## 脱敏

所有 metadata、snapshot payload、event metadata 和 error message 在 repository 调用前同步脱敏。覆盖 phone、email、idCard、token、password、secret、apiKey、authorization、cookie，以及自由文本中的邮箱、手机号、身份证和 Bearer token。

## API

- `GET /api/agent-traces/{traceId}`
- `GET /api/agent-traces/by-session/{sessionId}?pageNo=1&pageSize=20`
- `GET /api/agent-traces/{traceId}/diagnostics`

Diagnostics 汇总 provider/model/prompt、retrieval、workflow path、tool calls、policy decisions、特殊事件、最终输出、脱敏计数和错误。

## 非范围

Stage 7A 不实现 dry run、diff、Evaluation Report 聚合修复、Spring AI、LangGraph、MCP、多 Agent、前端 Dashboard 或真实供应商副作用。
