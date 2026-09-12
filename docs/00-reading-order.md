# 阅读顺序

> 文档版本：v0.2　更新日期：2026年9月12日

这些文档不是要求一次读完。按当前问题选择：

> 准备企业命题组报名材料：看 [报名解决方案](报名解决方案.md)。了解技术设计、确认机制与实现边界：看 [设计思路报告](设计思路报告.md)。团队更新和 Git 协作规则见 [参赛文档维护说明](参赛文档维护说明.md)。

> 面向**使用者/评委**、想了解怎么操作系统（不写代码）：直接看 **[用户手册](user-manual.md)**，其余是开发与评审文档。

先读 [原始需求](../Requirement.md) 与 [当前设计及需求对齐](../Design.md)。后者明确区分源码事实与待实现设计，是 2026-09-07 的审阅基线；下列早期方案不能作为已完成验收的证明。

1. 想知道比赛到底要求什么：看 `01-requirement-mapping.md`。
2. 想知道至少做哪些页面：看 `02-pages-and-interactions.md`。
3. 想理解 Agent 为什么不止一条线：看 `03-agent-workflow.md`。
4. 想知道 IDEA 项目怎么分包：看 `04-architecture-and-modules.md`。
5. 三个人要对接接口：看 `05-api-contracts.md`。
6. 不知道模拟医院数据怎么造：看 `06-mock-data-design.md`。
7. 不知道 GitHub 怎么配合：看 `07-collaboration-and-git.md`。
8. 准备真正开始搭建：看 `08-beginner-implementation-guide.md`。
9. 判断 Demo 是否达标：看 `09-demo-acceptance-checklist.md`。
10. 判断项目创新点、价值及后续方向：看 [设计思路报告](设计思路报告.md)（原独立的 `10-innovation-assessment.md` 已并入该报告，编号不再使用）。
11. 想从零理解大模型、工具调用、中控权限及项目下一版架构：看 [复诊事项智能体架构与受控工具调用方案](11-agent-architecture-and-controlled-tool-calling.md)。
12. 想了解语音优先（P0）方案：看 [语音优先 P0 方案](12-voice-first-p0-plan.md)。
13. 想知道比赛要求的四个演示场景（正常办理、无号、冲突、越界）在代码里各走哪条路：看 [四个演示场景的智能体工作流](13-demo-scenarios-workflow.md)。

上述清单中的文档在 `docs/` 下均存在。

## v0.2 新增能力该看哪几份

- **代他人办理 / 协同照护端**（家属、志愿者代长辈办理）：先看 `01-requirement-mapping.md` 第十一节，再看 `06-mock-data-design.md` 的 `care_relations` 身份绑定。
- **健康记录与健康备忘**（记血压血糖、设长期重复备忘）：看 `06-mock-data-design.md` 第八节（`memos`、`health_records`）。
- **模型工具循环 / 受控工具调用**（模型提出只读工具调用、写操作过确认）：看 `03-agent-workflow.md` 与 `11-agent-architecture-and-controlled-tool-calling.md`。
- **录屏前理清四个演示场景**（正常办理、无号、冲突、越界在代码里各走哪条路，以及各自的起始状态怎么摆）：看 `13-demo-scenarios-workflow.md`。

`records/` 下的四个文件（`PROGRESS.md`、`DECISIONS.md`、`INTERFACE_CHANGES.md`、`PITFALLS.md`）从项目开始后持续更新，它们不是一次性报告。

