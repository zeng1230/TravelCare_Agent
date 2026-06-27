# PR-1A Security Boundary

## 背景

TravelCare-Agent 已完成 RC 验收，当前进入 Production Readiness Phase 1。阶段名称为
**PR-1: Security, Reliability, Observability Hardening**。PR-1A 是可信后端硬化的第一步，
目标是建立认证、授权、角色边界、对象级权限和基础脱敏能力。

本阶段不是 Stage 11，也不是继续扩展 AI Agent 功能。

## 目标

- 所有核心 `/api/**` 默认需要认证。
- 未认证或非法 JWT 返回 401。
- 已认证但角色或对象权限不足返回 403。
- Controller 层使用方法级权限表达角色边界。
- 对象级权限通过 `AuthorizationService` 显式校验。
- Session、Workflow、Memory、订单工具链路遵守 userId 与 tenantId 边界。
- Trace、Evaluation、Human Review 等敏感面按角色保护。
- API 响应和日志避免暴露 JWT、Authorization header、API key、provider secret、raw provider error 和 raw stack trace。

## 角色模型

| Role | 用途 |
| --- | --- |
| USER | 普通旅客用户，只能访问本人、本租户对象。 |
| OPERATOR | 人工客服/处理人员，可访问 Human Review 和相关 Workflow。 |
| EVALUATOR | 离线评测人员，可访问 Evaluation。 |
| ADMIN | 诊断与运维管理员，可访问 Trace、Evaluation、Human Review 和诊断面。 |
| SYSTEM | 预留给内部 Worker 流程；PR-1A 不通过 dev token 默认发放。 |

## API 权限矩阵

| API surface | USER | OPERATOR | EVALUATOR | ADMIN |
| --- | --- | --- | --- | --- |
| `POST /api/sessions` | 使用 JWT userId/tenantId 创建 | 否 | 否 | 可代创建 |
| `POST /api/sessions/{id}/messages` | 仅本人 session | 否 | 否 | 是 |
| `GET /api/sessions/{id}/events` | 仅本人 session | 否 | 否 | 是 |
| `GET /api/sessions/{id}/context` | 仅本人 session | 否 | 否 | 是 |
| `GET /api/sessions/{id}/workflows` | 仅本人 session | 人工处理相关 | 否 | 是 |
| `GET /api/workflows/{id}` | 仅本人 session 关联 | 是 | 否 | 是 |
| `GET /api/workflows/{id}/steps` | 仅本人 session 关联 | 是 | 否 | 是 |
| `GET/POST /api/memories/users/{userId}` | 仅本人 | 否 | 否 | 是 |
| `/api/agent-traces/**` | 否 | 否 | 否 | 是 |
| `/api/evaluation/**` | 否 | 否 | 是 | 是 |
| `/api/human-review/cases/**` | 否 | 是 | 否 | 是 |
| Audit API | 当前无公开 Controller；future protected surface | future protected surface | future protected surface | future protected surface |
| Order API | 当前无 REST Controller；通过 message/workflow/tool 闭环校验 | 当前无 | 当前无 | 当前无 |

公开接口：

- `GET /health`
- `GET /actuator/health`
- Swagger、`doc.html`、`/v3/api-docs/**` 仅在 `local/dev/test` profile 下公开
- `/api/dev/auth/token` 仅在 `local/dev/test` profile 且 `travelcare.security.dev-auth-enabled=true` 时注册

不公开：

- `/actuator/**` 其他端点
- trace、metrics、env、beans、diagnostic 类接口

## 对象级权限规则

- USER 创建 session 时，最终 userId 来自 JWT；请求体可不传 userId。
- USER 如果在创建 session 请求体传入 userId，必须与 JWT userId 一致，否则 403。
- USER 只能访问本人、本租户 session。
- USER 只能访问本人 session 关联 workflow。
- USER 只能访问本人 memory path。
- Trace、Evaluation、Human Review、Audit 对 USER 默认不可读。
- tenantId 纳入 session 校验，避免跨租户读取。
- 当前没有 Order REST API；订单归属通过 `GetOrderTool` 的 userId 过滤和 message/workflow/tool 闭环验证。用户查询他人订单时返回受控拒绝结果，不产生 SUCCESS ToolCall。

## 脱敏策略

- `RedactionService` 对手机号、邮箱、身份证式字段、JWT、Bearer token、API key、secret、provider secret、Authorization、cookie、stack frame 做基础 redaction。
- API 响应不返回 raw stack trace、raw provider error、token、secret。
- 日志不打印 Authorization header、JWT、API key、provider secret。
- 全局异常日志保留 exception type 和简短脱敏 message；API 仍返回统一错误码。
- 不大规模替换 logging framework，不吞掉业务错误；敏感诊断面由 ADMIN 权限控制。

## 测试清单

- 无 token 访问核心 API 返回 401。
- 非法 token 返回 401。
- 合法 USER token 可访问允许的 Session API。
- USER 访问 Trace/Evaluation/Human Review 返回 403。
- EVALUATOR 可访问 Evaluation。
- OPERATOR 可访问 Human Review。
- ADMIN 可访问 Trace。
- USER A 不能读取 USER B 的 session。
- USER A 不能读取跨 tenant workflow。
- 用户查询他人订单不会产生 SUCCESS GetOrderTool 结果。
- 响应脱敏 JWT、Authorization、provider secret、API key、raw stack trace。
- `mvn test` 使用 mock provider，不调用 DeepSeek 或真实 LLM Provider。

## 不做内容

- 不命名为 Stage 11。
- 不全面接入 Spring AI。
- 不接入 LangGraph 或 LangGraph4j。
- 不做多 Agent 平台。
- 不做 MCP Tool Store。
- 不做管理后台。
- 不拆微服务。
- 不引入 Kubernetes。
- 不大改现有 workflow、RAG、Trace、Evaluation、Human Review 逻辑。
- 不为了测试新增 Audit、Trace、Evaluation、Human Review 的业务型公开接口。
