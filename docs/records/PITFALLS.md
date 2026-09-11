# 踩坑记录

## 2026-09-11 「无新增告警」的比对漏掉了全新文件

- 现象：核对「前端代码规范检查是否引入新命中」时，只把**改动过的、且在 HEAD 里存在**的文件取出来逐一比对，结论是「与改动前一致」。但本轮新增的 `tool-trace-describe.ts`（未跟踪文件）实际带着 40 条 `no-base-to-string` / `restrict-template-expressions`，`camera-capture.tsx` 还带着 1 条 `EffectSetState`——两者都是本次新写的代码，等于一次也没被比对过。
- 原因：`git show HEAD:<path>` 对未跟踪文件直接报错，脚本把这种情况当成「基线为空」，而不是「基线不存在」。于是新文件在比对表里整体缺席——第一轮比对里那两条 `unicorn(no-empty-file)` 就是空基线留下的线索，顺着它才发现有两个文件根本没进比对。
- 处理：`str(value: unknown)` 统一把工具返回值收敛成能显示的文字（对象/数组给空串，而不是 `[object Object]` 写进「办理过程」）；`camera-capture` 的开流逻辑改成链式回调，`setState` 一律落在异步回调里。
- 结论：比对「有没有变差」时，**新增文件必须单独列出来**——它们没有基线，任何命中都是新的。脚本里遇到「取不到基线」要显式报错，不要静默跳过。

## 2026-09-11 已结束会话的「拒绝」被当成一轮对话落了库

- 现象：会话关掉之后，一个还开着确认卡的老页面每重试一次，历史里就多一条一模一样的「这段对话已经结束了」。连试三次后，这段历史里三句复读，真正聊过的内容反倒被淹掉。
- 原因：`/messages`、`/actions`、`/confirmations` 被拒时走的是 `respondWithoutModel()` → `finishWithoutModel()`，这条公共出口会顺手把这句话 `addMessage` 进会话。对正常回答这是对的，对一句拒绝就是错的——拒绝只是一次失败的调用，不是一轮对话。
- 处理：`closedResponse()` 直接构造 `AgentTurnResponse` 返回，不落库。拒绝照常返回，界面上照常看得见，只是不再进历史。
- 结论：写历史这件事要按语义分，不能挂在「所有出口」上。凡是「这句话不代表一次交流」的地方（拒绝、限流、重放），都要绕开落库那一层。

## 2026-09-11 `pkill -f` 的匹配串把执行它的那个 shell 也一起杀了

- 现象：`pkill -f "server.port=8099"` 之后命令以退出码 144 结束，自己的 shell 也被杀掉；`pkill -f "spring-boot:run"` 同理——`-f` 匹配的是整条命令行，而执行它的 `bash -c` 命令行里正好就带着这个字符串。
- 原因：`pkill -f` 拿模式去匹配 `/proc/*/cmdline`，执行命令的 shell 自己也在那张表里。
- 处理：先用 `pgrep -af` 看清目标再按 PID 杀；或用 `[s]erver.port=8099` 这样的字符类写法让模式本身不自匹配。
- 结论：这台机器上还跑着用户自己的 8080 后端（本会话中一直没动它）。杀进程前必须先 `pgrep -af` 确认，绝不拿一个可能自匹配的 `-f` 模式直接扫。

## 2026-09-11 在 effect 里同步 setState，被 lint 拦下也顺便修掉了一个竞态

- 现象：`profile-view.tsx` 里 `useEffect(() => { void load(); }, [])`，`load()` 第一句是 `setLoading(true)`，oxlint 报 `react(react-compiler) EffectSetState`。
- 原因：effect 体内同步调用 setState 会触发级联渲染。
- 处理：改成只在 promise 回调里 setState（`listMemories().then(ok, fail)`），用一个 `cancelled` 标志在清理函数里丢弃迟到结果；「正在读取」这个状态改用 `memories === null` 表达，少一个可能对不上的布尔。重试按钮走 `reloadToken` 递增重新触发 effect，setState 落在事件处理里不受这条规则约束。
- 结论：这条 lint 规则不只是形式要求——它逼出来的写法顺手修掉了「重试连点两下、先发的那次后到把新结果盖回旧的」这个真实竞态。`cancelled` 守卫在这里不是摆设。

## 2026-09-11 `probe=true` 把一条正常的模型通道自检成「密钥无效」

- 现象：`GET /api/demo/channels?probe=true` 返回 `probe.ok=false`、`reason="调用抛异常：密钥无效、余额不足或网络不通"`；但用同一把 key 直接 curl DeepSeek 是 200，回复也正常。
- 原因：自检写死了 `max_tokens=16`。`deepseek-v4-flash` 是推理模型，会先输出 `reasoning_content`，16 个 token 被思考链吃光后 `content` 为空、`finish_reason=length`；网关重试三次仍拿不到正文，抛出的空内容异常里其实带着 `finish_reason` 和 `usage`，却被 probe 的 catch 折成了一句与真实原因无关的「密钥无效」。
- 处理：自检预算提到 512（`PROBE_MAX_TOKENS`），空内容与调用异常分开报，异常细节写服务端日志、写前抹掉 `sk-` 片段。
- 结论：推理模型的 token 预算不能按普通模型给；诊断信息已经在异常消息里时，别在 catch 里丢掉它，否则排查方向会被带偏。

## 2026-09-11 固定的系统提示被回答模型改写，测试也照不出来

- 现象：关掉视觉模型后发图片，回复本应是「图片识别功能暂时没有开启……」，实际却是「图片识别功能**目前**没有开启……」外加一段编出来的替代建议，还多花了 4 秒。
- 原因：该分支走的是 `respond()` → `finish()`，而 `finish()` 会调回答模型润色每一句话。测试跑在 `agent.model.enabled=false` 下，模板路径逐字返回，所以断言 `contains("图片识别功能暂时没有开启")` 一直是通过的——它测不到「模型开着」的那个环境。
- 处理：改成 `respondWithoutModel()`，固定说明逐字出去、不再为此调用模型（实测 4.15s → 0.13s）。
- 结论：教老人「换个办法」的系统提示必须原样送达；判断某个分支要不要润色，要按「模型开着」的环境去看，不能只看规则模式的测试。

## 2026-09-09 `.env` 已填写但开发后端仍走规则模式

- 现象：`.devcontainer/.env` 中已设置 `AGENT_MODEL_ENABLED=true`，重启旧的 `mvn spring-boot:run` 命令后，模型状态仍是 `RULE_FALLBACK` / `TEMPLATE_FALLBACK`。
- 原因：环境变量只会由启动它的 shell 传给 Java 进程；普通 Maven 命令不会自动读取 `.env`。根目录 `.env` 则是 docker compose 的配置文件，也不会被开发容器中的 Maven 自动读取。
- 处理：统一使用 VS Code 的“Dev Container: 后端服务”任务；该任务启动前可选加载 `.devcontainer/.env`。修改配置后停止并重新运行后端任务，不需要重建容器。
- 结论：前端与后端在开发容器中是两个独立进程，停止或重启后端不会自动停止前端。

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
- 处理：2026-09-07 已删除整条死代码链（`loadPrimaryContact`、接口 `FamilyNotificationTool.findPrimaryContact`、mock 实现及 `mask` 私有方法），容器内 `mvn test` 16/16 通过。提交见 `refactor/remove-dead-primary-contact`。
- 结论：删除前后都不存在“默认联系人被当作已确认对象”的路径——`SET_NOTIFY=false` 清空 contact，`SET_CONTACT` 要求从候选人显式选择，`ready()` 也要求 `notifyFamily && contact != null`。
- 留意点：容器内开启模型联调（`agent.model.enabled=true`）时，若模型自由语言路径能影响 notify/contact 字段，仍需验证不会把主联系人误读为用户指定。

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
