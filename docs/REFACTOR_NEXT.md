# Next Refactor Roadmap

状态基线：2026-09，基于 `docs/AGENT_RUNTIME.md` 六阶段计划的完成度评估。runtime 已是唯一主管道（`/api/care/qa` 是它的兼容壳，`chat-with-tools` 与关键词路由已删除）。本路线的重心从「统一入口」转向「可靠性加固 → 领域事件收敛 → 前端迁移收官」。

进度（2026-09-09）：**P0、P1、P2 全部完成**。care/timeline 页面已切换到 agent 域（`/api/agent/subjects*`），legacy 表（`care_record`、`plant_profile`、`pet_profile`）代码不再读写——存量数据保留在库中未迁移（无归属字段，无法自动归属），如需清理由运维评估。仅剩 P3 体验与部署项（可选）。

## 阶段完成度快照

| 阶段 | 完成度 | 遗留缺口 |
|---|---|---|
| 1 Context 基础 | 完成 | — |
| 2 Runtime v1 | 完成 | — |
| 3 领域事件模型 | 基本完成 | care_event 仅确认执行一处写入 |
| 4 记忆与检索 | 完成 | 旧无主向量无 owner 回填；索引为单机内存态 |
| 5 写操作确认 | 基本完成 | diagnoseDisease 写 identify_history 未经确认 |
| 6 遗留清理 | 大部分完成 | identify/diagnose/pet-plant recognize 独立路径；死代码；回归用例 5-7 缺失 |

## P0 可靠性债（正确性，最先做）

1. **确认闭环加固**（`ActionConfirmationService`）
   - 原子 claim（`PENDING/FAILED → EXECUTING`），防并发双击双执行
   - 执行失败置 `FAILED` 并允许重试（旧实现先置 CONFIRMED 再执行，失败即卡死）
   - CareEvent 与 EXECUTED 状态同事务落库
   - `EXPIRED` 定时清理，替代点击时的惰性置位
2. **工具循环迭代级重试不重放副作用**（`ToolCallingService.executeToolLoop`）：重试只对幂等读工具放行，否则降级为直接回复
3. **拆掉双线程池嵌套**：`tool-executor` 包 `tool-broker`，并发 >4 即排队；`cancel(true)` 对 OkHttp 中断无效
4. **落库失败可观测**：trace 审计与向量保存失败目前静默 `log.warn`
5. **幂等约束**：`care_event.source_event_id`、`agent_tool_trace.trace_id` 唯一约束（注意存量重复数据需先清理）
6. **限流换滑动窗口**（单机内存态，边界可突发 2 倍；多实例部署时需外部化）

## P1 领域事件收敛

7. `diagnoseDisease` 归类修正：要么进 `WRITE_TOOLS` 走确认卡，要么诊断结果只存 artifact、落库移到确认后
8. `care_event` 全覆盖：REST 直写（`POST /api/care/records`）、reminder 定时投递等写路径统一产事件；timeline 从 care_event 读
9. 双表归并：`care_record`（chat 包旧实体）与 `care_records`（care 包）二选一，废除 `LegacyCareRecordRepository`
10. 老向量一次性回填 owner 或清除（现在对所有用户不可见）

## P2 前端迁移收官

11. **会话历史 UI**：conversationId 只存 JS 内存，刷新即失忆；后端 `AgentConversationService.history()` 与 `GET /api/agent/traces/{id}` 已就绪
12. **档案与护理记录管理**：agent.html 的 subjects 只读；补建档/删除/记录 UI 与「识别→建档案」引导流（走确认卡片）
13. 页面下线：chat.html（agent.html 为超集）、care.html 问答面板、`/api/care/qa/summary`、home.html 的 `/api/ai/care/reminders/pending`
14. disease.html 整页并入工作台（诊断卡片化）
15. 工程清理：死代码 `WeatherTool`、`NearbyServiceTool`、`ChatMemoryService` 链路；无调用端点 `/api/pet|plant/recognize`、`/api/briefing/generate`；12 处重复 auth 守卫提取共享 `auth.js`；home.html 内联 push 订阅并入 push.js

## P3 体验与部署（可选）

16. SSE 流式输出（当前全站同步 request/response，长工具链干等）
17. uploads 对象存储或归档策略（本地盘重启/换机后 `/uploads/` 旧链接失效）
18. 回归测试补齐 golden case 5-7（带 target 记忆注入、图片→分析、病害诊断）

## 执行纪律

- 延续 staged commit + 单一关注点，每步可独立回滚（同 `AGENT_RUNTIME.md` 的 Rollback 约定）
- 顺序：P0 → P1 → P2；P3 量力
- P0+P1 完成后更新 `AGENT_RUNTIME.md` 状态为「运行时已稳定，进入前端迁移期」
