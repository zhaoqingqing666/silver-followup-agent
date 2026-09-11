# 三人协作与Git规则

## 一、模块负责人

| 角色 | 主要目录 | 主要职责 |
|---|---|---|
| Agent/流程负责人 | `backend/.../agent`、`workflow` | 意图、字段、状态机、确认门禁、安全边界 |
| 工具/数据负责人 | `backend/.../tool`、`repository`、`mock-data` | 四类工具、材料服务、模拟数据、异常返回 |
| 前端负责人 | `frontend/src` | 三个页面、交互组件、适老化、接口接入 |

负责人表示“最终对该模块质量负责”，不是别人永远不能修改。公共 DTO 和接口文档必须共同确认。

## 二、先定接口，再分别写代码

以事项卡片为例：

1. 三人先在 `05-api-contracts.md` 确认字段。
2. 前端根据示例 JSON 开发 `TaskCard`。
3. 后端根据同一份 JSON 创建 DTO。
4. Agent/流程把执行结果组装进 DTO。
5. 联调时只检查是否符合契约，不要求对方理解自己的内部代码。

## 三、Git分支

```text
main
├─ feature/agent-information-collection
├─ feature/appointment-tool
├─ feature/assistant-workspace
└─ fix/confirmation-expiration
```

规则：

- 不直接在 `main` 上开发。
- 一项清楚的功能一个分支。
- 分支名使用英文小写和连字符。
- 功能可运行后提交 Pull Request。
- 由另一名队员阅读变更并实际运行关键路径。
- 合并前处理冲突，不把带冲突标记的文件提交。
- 不长期积累大量未合并代码。

## 四、提交信息

使用简单格式：

```text
feat: add appointment slot query tool
fix: invalidate confirmation after date change
docs: update task card api contract
test: add no-slot scenario cases
refactor: split workflow from controller
```

一次提交只表达一个主要目的，不要使用“改了一些东西”“最终版2”。

## 五、Pull Request最少检查项

- [ ] 项目可以启动。
- [ ] 修改范围与标题一致。
- [ ] 没有上传 API Key、密码、真实个人信息。
- [ ] 新增接口已更新 `05-api-contracts.md`。
- [ ] 修改状态或流程已更新 `03-agent-workflow.md`。
- [ ] 用户能看到的功能已经自己操作一遍。
- [ ] 异常路径不会直接白屏或结束对话。
- [ ] 没有把大量逻辑写进 Controller 或 Vue 单个组件。

## 六、最容易产生冲突的共享文件

- `pom.xml`
- `package.json`
- `application.yml`
- 公共 DTO
- 路由配置
- `05-api-contracts.md`

修改这些文件前在团队群里说明目的，尽量由一个人完成一次变更，其他人随后拉取。

## 七、接口变更流程

1. 在 `records/INTERFACE_CHANGES.md` 写清楚旧结构、新结构和原因。
2. 确认前端与后端谁先兼容。
3. 更新 `05-api-contracts.md`。
4. 修改代码。
5. 前后端联调。
6. 合并后通知所有成员拉取。

## 八、如何保证所有人电脑上结果一致

- Java 版本固定为 17，并写入 README。
- 使用 Maven Wrapper，统一用 `mvnw`。
- 前端提交锁文件，例如 `package-lock.json` 或 `pnpm-lock.yaml`。
- 配置项通过环境变量读取。
- 提交 `.env.example`，不提交 `.env`。
- 模拟数据放入仓库，不让每个人手工创建不同数据。
- 所有启动步骤写入 README，不能只存在某个人脑子里。
- 模拟场景支持重置，避免一次预约后数据永久变化。

## 九、协作时的“完成”定义

一个功能只有同时满足以下条件才算完成：

1. 代码已实现。
2. 正常情况已操作验证。
3. 至少一个相关异常已验证。
4. 前后端接口与文档一致。
5. 没有把密钥和个人数据提交到仓库。
6. 另一名队员能在自己的电脑上运行。

