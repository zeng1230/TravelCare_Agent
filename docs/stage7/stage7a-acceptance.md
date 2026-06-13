# Stage 7A 验收记录

## 验收项

- 同步消息成功后生成持久化 root trace，并在响应中返回 `traceId/traceAvailable`。
- Root 创建失败时响应 `traceId=null`、`traceAvailable=false`，USER、Workflow、ToolCall、ASSISTANT 和 answer 仍正常完成。
- 成功链路包含 REQUEST、CONTEXT、RETRIEVAL、MODEL、WORKFLOW、WORKFLOW_STEP、TOOL、POLICY、AUDIT、OUTPUT span。
- 异步任务通过 payload 和 RabbitMQ body 显式传播 trace，并产生 ASYNC_TASK span。
- Tool span 包含状态、耗时和 ToolCall 引用。
- diagnostics 返回完整诊断聚合，且 RUNNING 或缺少 finishedAt 时标记 `incomplete=true`。
- Trace metadata、event、snapshot 和 error 在写库前同步脱敏。
- Trace 写入使用独立事务，repository 异常不会污染业务事务。
- 三个 Agent Trace API 可访问。
- 测试 provider 固定为 mock，不调用真实 DeepSeek。

## 边界

Trace 是诊断视图，不替代业务事实源。本阶段未实现 dry run、diff，也未修改 Evaluation Report 聚合。

## 验证命令

```powershell
.\mvnw.cmd test -q
```

最终验证结果：37 个测试套件，108 个测试，Failures 0，Errors 0，Skipped 0。Flyway 成功校验 6 个 migration，当前 schema 版本为 V6。

## Stage 7A 手工验收通过。

验收路径：
1. POST /api/sessions 创建会话成功。
2. 使用内置 mock 订单 ORD-1001 调用 POST /api/sessions/{sessionId}/messages 成功。
3. 响应返回 answer、traceId、traceAvailable=true。
4. GET /api/agent-traces/{traceId} 可查询 TraceRun、TraceSpan、TraceEvent、TraceSnapshot。
5. GET /api/agent-traces/{traceId}/diagnostics 可返回 workflowPath、toolCalls、policyDecisions、finalOutput。
6. 数据库中 tool_calls、workflow_steps、audit_logs 均能通过 trace_id/span_id 与本次 trace 关联。
7. 说明 Stage 7A 已实现 Agent Execution Trace Foundation。

结论：
Stage 7A 通过，可以进入 Stage 7B。


说明：早期计划曾设计订单表和模拟订单接口，但当前实现没有 `/api/orders/mock` 接口，也没有 `travel_orders` 表。本地手工验收使用 `MockOrderAdapter` 内置订单 `ORD-1001`。
