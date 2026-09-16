# 项目长期约定与价值定位（silver-followup-agent）

## 价值定位口径（2026-09-14 确立，重要）

**不能把命题要求包装成我们提出的方法论，更不能对外主张其知识产权。**

命题（面向银发群体的复诊事项协同办理智能体）本身已规定：一次只问一个主要问题（4.6.13）、
异常不终止对话且要给可选方案（4.5.9）、服务边界与不得提供诊断（4.7）、关键操作前确认（4.4）。
这些都是**命题要求**，对外只能说"完整覆盖/严格落地"，**不能说"我们提出了这套方法论"**，
否则评委一眼看穿，反而暴露原创性不足。

### 三分法（对外表述前先对号入座）
- **命题要求**：适老化交互原则（一次一问/异常不终止/重复确认）、异常处理要求、服务边界、
  确认机制、**四类模拟工具**（预约查询/日程管理/出行规划/家属通知，命题 4.3 点名设计）
  → 一律说"完整覆盖"，**不主张为原创或创新**（主公两次明确纠正：工具也是命题要求的，
  不适合当创新点；命题要求也不能声明成自有方法论/IP）
- **我们的原创实现**：受控工具调用架构的具体机制、可复现机制 → 说"我们设计并实现"
- **行业共识/工程基本功**：CAS 条件更新、幂等、事务 → 说"工程可靠性保障"，不单列卖点

### 真正可主张价值的三类资产（有代码依据）
1. **架构实现（最硬）**：`READ_ONLY` vs `CONFIRMATION_ONLY` 分层；`confirmationId` 由 Java
   注入、模型不得提供；确认卡内容由权威状态拼装、不交模型压缩；两层守门（注册表可见性 +
   执行前按角色再判）。命题只提"要确认"，**没给实现方式**。
2. **自主设计决策（命题未规定处）**：越界不打断办理（命题没要求保持确认卡，是我们选的
   trade-off）；附近日期**只往后看 3 天**（命题例子是前后都看，我们给了不同且更严谨的口径）；
   冲突时当天只取 1 个候选凑成 3 个按钮（按"老人翻不到第二屏"）；提醒时点（提前 1 天 /
   出发前 10 分钟）；出行 +20 分钟取号预留。
3. **工程解法**：滚动种子让"冲突/无号"必然发生（下周三体检 10:00–11:00 vs 工作日 10:30 号源）。

### 知识产权务实定位
- 代码整体可登记**软件著作权**（最现实）；
- 受控工具调用机制理论上可申发明专利，但周期长、对比赛价值有限；
- 方法论原则是命题要求/行业共识，**不主张**。

## 已知真实缺口（勿在材料里说成已完成）
- **紧急情况前置**：`SafetyGuard#precheck` 的紧急词表只在模型不可用时执行；模型模式下靠模型
  `EMERGENCY` 分类，**无规则兜底**（越界侧有兜底，紧急侧没有）。已记录在
  `docs/13-demo-scenarios-workflow.md` 7.1。演示紧急场景前需实测模型输出。
- 4.6 部分前端细节（重要信息重复确认、按钮密度）尚未逐条核透。

## 死代码扫描：本项目固有的两类误报（每次扫描必先排除）
- **Spring DI**：所有 mock 工具、Service、Controller 都靠 `@Component`/接口注入或反射装配，
  静态引用计数为 0 是**正常**的，不能据此判死。`LlmAnswerGenerator`/`LlmConversationPlanner`
  是 `@Primary @Component`。
- **vinext peerDependencies**：`react-dom`、`react-server-dom-webpack`、`@vitejs/plugin-react`
  源码零 import 但必须留（plugin-react 是**非 optional** peer）；项目跑 vinext 不是标准 Next.js，
  `app/layout.tsx`/`app/page.tsx`/`vite.config.ts` 都是真入口，`next-env.d.ts` 在 tsconfig include 里。
- 2026-09-14 第三轮静态扫描 14 个维度**全部为空**，无新增死代码（详见当日日志）。
  脚本在 `%TEMP%\dcscan\`：`all.mjs`(A–I)、`scan2b.mjs`(J/K/L)、`scan3.mjs`(M/N)，纯正则可重跑。

## 数据库 schema 变更约定（2026-09-16 确立）

`spring.sql.init.mode=always`，`schema.sql` **每次启动都执行**且要同时兼容旧库和新库，
所以任何结构变更都必须**幂等**（`CREATE TABLE IF NOT EXISTS`、`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`）。

**表改名不要用 `ALTER TABLE ... RENAME TO`** —— H2 没有条件 RENAME，第二次启动会直接报错中断。
本项目采用的做法（已用于 clinic_locations → clinics）：

```sql
DROP TABLE IF EXISTS 旧表名;              -- 只认旧表名，对已建好的新表无影响
CREATE TABLE IF NOT EXISTS 新表名 (...);  -- 新库直接建
```

行数据交给 `data.sql` 的 `MERGE INTO 新表名 ... KEY(id)` 按 id 全量重建，不必写 UPDATE/INSERT 迁移。
副作用（须事先说明）：旧表里 id 不在种子数据范围内的自定义行会丢失，引用它们的外键值会悬空。

表名的引用面固定是这几处，改一处就要全扫：
- `backend/src/main/resources/schema.sql`、`data.sql`
- `H2FacilityGuideTool`（`FROM 表名`）
- `docs/tools/export_h2_snapshot.py` 的 `TABLE_PURPOSES`
- 同步文档：`docs/06-mock-data-design.md`、`docs/14-database-table-data.md`、`docs/设计思路报告.md`
- 新增记录：`docs/records/INTERFACE_CHANGES.md`（倒序，最新在最上面）；
  `PROGRESS.md` / 旧条目属历史事实，**不改写**
- `backend/target/classes/*.sql` 是构建产物，宿主机不会自动更新，**必须在开发容器内重新构建**才生效

## 其他长期事实
- 数据为 H2 file 模式 `./data/silver-agent`；号源/日程由两个 `ApplicationRunner` 按"今天"滚动生成，
  故四场景任何一天可复现。
- 后端完整回归 341/341 通过（2026-09-12 记录）。
- 演示话术必须避开"步行"（`travel_routes` 只配家属开车/打车/公交，但口语解析认得"步行"）。
