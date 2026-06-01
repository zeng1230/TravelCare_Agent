# TravelCare Agent

## 项目概览

TravelCare Agent 是一个面向旅行客服场景的后端系统原型，核心目标是把“AI 回复”放进一个可恢复、可审计、可解释、可评测的工程闭环里，而不是做一个只依赖 prompt 的聊天 Demo。

系统以 Spring Boot 为主体，围绕订单查询、退款咨询、客服会话、工作流任务、工具调用、RAG、Memory、人工复核、审计日志、异步 worker、AgentRun 追踪、Replay 下钻和本地 Evaluation Report 形成完整链路。

当前退款资格判断由 `RefundEligibilityPolicy` 等确定性规则负责。RAG 和 Memory 可以提供上下文，但不能覆盖退款规则；LLM / Agent 在项目定位中负责理解用户意图、组织回复和串联流程，后端负责状态、幂等、工作流、工具、审计、异步执行和评测。

## 为什么它不是一个简单的 Chatbot

普通 prompt-only chatbot 通常只关注“输入一句话，输出一句话”，但真实客服系统还需要回答这些问题：

- 回复基于哪些用户输入、知识片段和记忆？
- 退款资格是由模型猜出来的，还是由稳定规则判断出来的？
- 异步任务失败、重试或 worker 崩溃时，系统如何恢复？
- 一次客服回复能否被审计、复盘和解释？
- 新改动是否破坏了核心退款规则？

TravelCare Agent 把这些问题拆到后端工程能力中处理：

- 用 workflow 和 task 管理客服流程状态。
- 用 idempotency key 避免重复执行。
- 用 audit logs 记录关键动作。
- 用 AgentRun 记录单次 Agent 回复的事实来源。
- 用 Replay API 只读下钻一次回复。
- 用 Evaluation Report 批量跑 golden cases，防止规则回归。

## 系统架构

```text
用户消息
  |
  v
Session / SessionEvent
  |
  v
Agent Orchestrator
  |
  +--> 订单查询 / 退款资格规则
  +--> RAG 检索
  +--> Memory 读取
  +--> Workflow / Task
  +--> Human Review
  |
  v
AgentRun 追踪
  |
  +--> Replay API
  +--> Evaluation Report
```

主要技术栈：

- Java 17
- Spring Boot 3.3.4
- MyBatis Plus
- MySQL
- Flyway
- Redis
- RabbitMQ
- JUnit

## 核心能力

- 会话管理：创建 session，写入用户和助手事件，查询会话事件。
- 订单查询：支持旅行订单信息查询和退款咨询入口。
- 退款预判：基于确定性规则判断是否满足退款条件。
- RAG：支持知识文档、知识分块和检索命中追踪。
- Memory：支持用户记忆存储与读取，并参与 AgentRun 追踪。
- Workflow：支持工作流、步骤、任务、异步 worker、失败标记和重试。
- Human Review：支持人工复核 case 的创建、分配和处理。
- Audit Log：记录关键业务动作，便于审计。
- AgentRun：记录一次 Agent 回复的输入、检索、记忆、工作流快照、输出、耗时和状态。
- Replay API：只读解释单次 AgentRun。
- Evaluation Report：通过测试侧 golden cases 生成本地 Markdown 评测报告。

## 阶段路线

### Stage 1：订单查询与退款咨询闭环

完成基础客服闭环：用户发起咨询，系统查询订单并基于规则给出退款预判。

### Stage 2：RAG / Memory / Human Review

接入知识检索、用户记忆和人工复核能力，让客服链路具备上下文增强和人工兜底能力。

### Stage 3：异步 Workflow Task

完成异步 workflow task、重试、worker 执行和异步路径追踪，避免所有流程都阻塞在同步请求中。

### Stage 4 Round 1：AgentRun 核心追踪闭环

同步回复和异步 worker 回复都会创建 AgentRun。AgentRun 记录：

- `inputEventIds`
- `retrievalChunkIds`
- `memoryIds`
- `workflowSnapshot`
- `outputEventId`
- `answerHash`
- `latencyMs`
- `status`

异步失败路径可以标记 `FAILED_*` 状态，`AgentRunService` 具备终态保护，避免已经完成或失败的 AgentRun 被后续路径错误覆盖。

### Stage 4 Round 2.1：Replay API

完成只读 Replay API：

```http
GET /api/agent-runs/{agentRunId}/replay
```

Replay 返回：

- AgentRun 摘要
- input events preview
- retrieval chunks preview
- memory 摘要
- workflow snapshot
- current workflow
- output assistant event preview
- audit actions
- 同 task attempts
- warnings

Replay API 不会重新执行 Agent，不会调用 LLM，不会执行 workflow，不会写 audit log，也不会创建新的 AgentRun。

### Stage 4 Round 2.2：Evaluation Report

完成基于 JUnit 的本地 Markdown evaluation report：

```text
target/evaluation/evaluation_report.md
```

当前 golden cases 覆盖：

- CASE-001：可退款咨询成功，期望 `ELIGIBLE`
- CASE-002：已使用订单不可退款，期望 `INELIGIBLE`
- CASE-003：出行时间不足 24 小时不可退款，期望 `INELIGIBLE`
- CASE-004：RAG / Memory 不得覆盖确定性退款规则，期望 `actualUnsafeOverride=false`

当前全量测试基线：

```text
Tests run: 95, Failures: 0, Errors: 0, Skipped: 0
```

## AgentRun 可观测性

AgentRun 是一次 Agent 回复的追踪事实源。它回答的是“这次回复到底基于什么产生”。

AgentRun 记录的核心字段包括：

- 输入事件 ID
- 检索命中的知识 chunk ID
- 使用到的 memory ID
- workflow 快照
- 输出 assistant event ID
- 回复 hash
- 延迟
- 状态
- 错误摘要

这让系统可以在测试、排障、审计和回归分析中，把一次自然语言回复追溯到结构化数据。

## Replay API

Replay API 用于只读解释单次 AgentRun：

```http
GET /api/agent-runs/{agentRunId}/replay
```

设计边界：

- 只读。
- 不重新生成回答。
- 不调用 LLM。
- 不执行 workflow。
- 不调用外部工具。
- 不写入 audit logs。
- 不创建新的 AgentRun。
- 不修改 session events、workflow、refund case 或 memory。

安全边界：

- memory 不返回完整 `memoryValue`。
- chunk content 只返回 preview。
- session event content 只返回 preview。
- 缺失 chunk、memory、event 或 workflow 时返回 warnings，而不是抛 500。

## Evaluation Report

Evaluation Report 是测试侧生成的本地 Markdown 报告，不是线上评测平台。

报告路径：

```text
target/evaluation/evaluation_report.md
```

报告包含：

- Summary
- Metrics
- Cases
- totalCases
- passedCases
- failedCases
- ragHitRate
- memoryUsageRate
- unsafeOverrideCount
- agentRunSuccessCount
- agentRunFailedCount
- regressionStatus

每个 case 会记录：

- caseId
- description
- inputMessage
- expectedWorkflowStatus
- actualWorkflowStatus
- expectedRefundDecision
- actualRefundDecision
- expectedRetrievalHit
- actualRetrievalChunkIds
- expectedMemoryUsage
- actualMemoryIds
- expectedAuditActions
- actualAuditActions
- expectedNoUnsafeOverride
- actualUnsafeOverride
- agentRunId
- replayEndpoint
- passed
- failureReason

## API 示例

### 创建会话

```http
POST /api/sessions
```

### 发送消息

```http
POST /api/sessions/{sessionId}/messages
```

### 查询会话事件

```http
GET /api/sessions/{sessionId}/events
```

### 查询会话上下文

```http
GET /api/sessions/{sessionId}/context?query=refund
```

### 创建知识文档

```http
POST /api/knowledge/documents
```

### 检索知识

```http
GET /api/knowledge/search?query=refund
```

### 查询用户记忆

```http
GET /api/memories/users/{userId}
```

### 写入用户记忆

```http
POST /api/memories/users/{userId}
```

### 查询 workflow

```http
GET /api/workflows/{workflowId}
```

### 查询 workflow steps

```http
GET /api/workflows/{workflowId}/steps
```

### 查询 session workflows

```http
GET /api/sessions/{sessionId}/workflows
```

### 查询人工复核 cases

```http
GET /api/human-review/cases
```

### 查询 AgentRun

```http
GET /api/agent-runs/{agentRunId}
```

### 查询 session 下的 AgentRuns

```http
GET /api/sessions/{sessionId}/agent-runs?pageNo=1&pageSize=20
```

### Replay 下钻

```http
GET /api/agent-runs/{agentRunId}/replay
```

## 数据库表

主要表包括：

- `sessions`
- `session_events`
- `workflows`
- `workflow_steps`
- `workflow_tasks`
- `tool_calls`
- `idempotency_keys`
- `refund_cases`
- `human_review_cases`
- `audit_logs`
- `knowledge_documents`
- `knowledge_chunks`
- `agent_memories`
- `agent_runs`

这些表共同支撑客服会话、状态机、异步任务、工具调用、退款判断、人工复核、知识检索、记忆和 AgentRun 可观测性。

## 如何运行

本地依赖：

- JDK 17
- MySQL
- Redis
- RabbitMQ

默认配置位于：

```text
src/main/resources/application.yaml
```

默认 MySQL 连接：

```text
jdbc:mysql://localhost:3307/travelcare_agent
username: root
password: 123456
```

启动应用：

```powershell
.\mvnw.cmd spring-boot:run
```

## 如何测试

执行全量测试：

```powershell
.\mvnw.cmd clean test
```

测试会覆盖核心客服闭环、异步 workflow、AgentRun 追踪、Replay API 和 Evaluation Report。

## 面试讲述版本

这个项目解决的是旅行客服场景里“AI 回复如何进入生产级后端链路”的问题。它不是只做一个聊天窗口，而是把订单查询、退款预判、知识检索、用户记忆、人工复核、异步任务、审计日志和评测报告串成完整闭环。

我把退款资格判断设计为确定性 workflow 和规则引擎负责，Agent 只负责理解用户意图、组织回复和串联上下文。这样可以避免模型根据 RAG 或 Memory 中的提示越权修改退款结论，保证核心业务规则稳定可控。

AgentRun 是项目里的关键可观测性设计。每次同步回复和异步 worker 回复都会生成 AgentRun，记录输入事件、检索 chunk、memory、workflow 快照、输出事件、回复 hash、耗时和状态。后续可以通过 Replay API 只读下钻一次回复，解释它为什么这样回答。

项目中比较难的点是异步失败路径和终态保护。worker 重试时每次尝试都可能产生 AgentRun，如果失败路径没有收口，容易出现 AgentRun 悬挂或终态被覆盖的问题。因此我实现了失败状态标记和终态保护，保证一次回复的追踪记录不会被后续流程错误改写。

这个项目和普通 AI Demo 的区别在于，它把 AI 能力放进了可审计、可恢复、可解释、可评测的后端系统里。Evaluation Report 使用 golden cases 验证退款规则、RAG/Memory 边界和 AgentRun 可下钻能力，用工程化方式降低回归风险。

## 简历 Bullet 候选

- 基于 Spring Boot、MyBatis Plus、MySQL 和 Flyway 构建旅行客服后端系统，覆盖会话、订单查询、退款预判、工作流和审计链路。
- 设计并实现异步 workflow task、RabbitMQ worker、重试语义和幂等控制，支持客服流程从同步请求扩展到可恢复的异步执行。
- 接入 RAG 和 Memory 能力，并通过确定性退款规则约束业务边界，保证知识检索和用户记忆不会覆盖核心退款资格判断。
- 设计 AgentRun 追踪模型，记录输入事件、检索 chunk、memory、workflow 快照、输出事件、耗时、状态和错误摘要，提升 AI 回复可观测性。
- 实现只读 Replay API，支持对单次 AgentRun 下钻查看输入、检索、记忆、workflow、输出、审计动作和同 task attempts。
- 构建 JUnit 驱动的本地 Evaluation Report，使用 golden cases 验证可退款、不可退款、24 小时规则和 RAG/Memory 不越权等关键回归场景。

## 当前限制

- 当前未接入真实 LLM。
- 当前未接入真实支付。
- 当前未接入真实供应商。
- 当前不会执行真实退款。
- Evaluation Report 是测试侧本地 Markdown 报告，不是线上评测平台。
- Replay API 是只读调试 API，不会重新执行 Agent。
- `metadataJson` / `evidenceJson` 仍有后续脱敏加固空间。

## 下一步

- 接入受控的 LLM provider adapter，并保持 deterministic refund policy 的最终裁决权。
- 对 `metadataJson` / `evidenceJson` 做字段级脱敏和最小化暴露。
- 将 Evaluation Report 扩展为更完整的回归基线和趋势报告。
- 增加更细粒度的 AgentRun 查询、过滤和运维视图。
- 为真实供应商、支付和退款执行接入建立隔离 adapter 与沙箱验证流程。
