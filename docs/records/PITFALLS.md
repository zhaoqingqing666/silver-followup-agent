# 踩坑记录

## 2026-09-02 脚手架默认部署依赖产生安全告警

- 现象：初始站点脚手架带入 Cloudflare/Wrangler 依赖，`npm audit` 报告多项上游漏洞。
- 原因：本项目只需本地 Demo，却保留了不需要的云部署插件。
- 处理：移除 Cloudflare 与站点部署插件，升级 React、vinext、Vite 及间接依赖；重新执行 `npm run build` 和 `npm audit`。
- 结果：前端构建通过，`npm audit` 为 0 个漏洞。
- 避免复发：新增依赖后同时提交 `package-lock.json`，合并前执行构建和审计；不要使用 `npm audit fix --force` 强行跨版本升级。

只记录能够帮助下一个人复现和解决的问题，不写“今天很累”“代码有问题”之类无法行动的内容。

## 2026-09-07 用户资料与家属联系人展示

- 现象：设置页曾写死“张阿姨 / 女儿 / 138****1234”，而 H2 中的演示用户实为“王阿姨”，家属为“小丽（女儿）”；联系人电话也并非真实可读号。
- 根因：早期实现把用户资料散落在页面，违背“业务数据以 H2 为唯一来源”（DECISIONS 2026-09-06）。
- 处理：`GET /api/users/{userId}` 返回体新增 `contacts`（脱敏电话），设置页改为读取后端；不在页面写死用户或家属。
- 复现提醒：任何页面出现用户名、家属、医院、号源、地址、材料等展示文字，都应来自 H2，不要写死在组件里。

## 2026-09-07 Docker 虚拟磁盘损坏污染依赖卷

- 现象：Dev Containers 无法启动开发容器，报 `hosts: input/output error`；`docker system df` 连镜像列表都读不出，同样 I/O error。容器内前端依赖卷 `frontend/node_modules` 出现 7452 个 0 字节文件（含 96 个 package.json），`npm run build` 报 `Invalid package config @jridgewell/sourcemap-codec`。
- 根因：Docker Desktop（WSL2）底层虚拟磁盘在异常关机/升级后损坏，containerd 元数据与依赖卷文件被截断成空文件。docker-desktop 发行版内 `/var/lib/docker`、`/var/lib/desktop-containerd` 均不存在，属数据盘未正确挂载。
- 解决：① `docker system df` 先排除空间不足；② 重启 Docker Desktop / `wsl --shutdown` 后数据盘恢复；③ 依赖卷内损坏文件无法逐个修，清空 `node_modules` 内容（挂载点不能 rm 整目录）后 `npm ci` 重建，0 漏洞。
- 无效尝试：`docker rm` 损坏容器在磁盘损坏期间会二次 I/O 失败，需先重启 Docker 再删。
- 是否需要修改文档/测试：`PROGRESS.md` 需在容器验收后把“待验收”改为通过。

## 2026-09-07 默认主联系人加载为死代码

- 现象：`FollowupAgentService.loadPrimaryContact`（会把一个主联系人放入 state）已定义但**从未被调用**，属死代码。
- 结论：当前不存在“默认联系人被当作已确认对象”的路径——`SET_NOTIFY=false` 清空 contact，`SET_CONTACT` 要求从候选人显式选择，`ready()` 也要求 `notifyFamily && contact != null`。16 项回归测试锁定该行为。
- 留意点：容器内开启模型联调（`agent.llm.enabled=true`）时，若模型自由语言路径能影响 notify/contact 字段，仍需验证不会把主联系人误读为用户指定。未来可顺手删除该死代码；删除后不影响任何现有测试。

## 记录模板

### PIT-XXX 简短标题

- 日期：
- 环境：操作系统、Java、Node、浏览器等。
- 现象：完整错误信息或用户可见表现。
- 触发步骤：怎样稳定复现。
- 根因：真正原因；未知时写“待确认”。
- 解决办法：实际有效的步骤。
- 无效尝试：避免别人重复浪费时间。
- 是否需要修改文档/测试：
- 相关 PR/提交：

## 示例：前端跨域失败

- 现象：Vue 请求后端时浏览器提示 CORS 错误。
- 触发步骤：前端开发服务器直接访问后端地址。
- 根因：后端未允许本地前端来源，或前端代理未配置。
- 解决办法：统一采用前端开发代理，避免每个 Controller 单独配置跨域。
- 是否需要修改文档/测试：在 README 中写明前端代理与后端端口。

## 2026-09-03 Agent上下文与真实事项重构

- 模型提示词缺少当前日期会把9月20日解析为错误年份。
- 只用按钮文字匹配号源会导致重复询问，按钮必须携带slotId。
- 前端mockAppointment会造成未预约也显示事项。

## 2026-09-03

- 不要在 data.sql 中随应用启动删除预约和会话，否则重启即丢数据。
- 不要只把聊天放在 React useState；页面卸载后必须通过 conversationId 从后端恢复。
- 不要让大模型直接生成号源时间；模型解析偏好，工具查询和业务代码负责最终校验。
- 预约列表不能固定读取第一条作为唯一事项，重复预约必须提供记录选择。
