# 项目开发环境

- 本项目统一在 VS Code Dev Container 中开发。
- 安装项目依赖、编译、构建、运行服务和测试必须在开发容器内执行，不使用 Windows 宿主机的 Maven、Java、npm 或 Node 构建项目。
- 宿主机可以编辑源码、查看 Git 状态、调用 Docker 或 VS Code Dev Containers 启动器管理开发容器。
- 容器工作目录为 `/workspace`。开发配置位于 `.devcontainer/`，使用方法见 `README.devcontainer.md`。
- 前端依赖、后端编译产物、数据库和依赖缓存使用配置中的具名卷；不要用宿主机安装结果验证 Linux 容器行为。
- 修改接口时同步前端类型、调用代码、`docs/05-api-contracts.md` 和 `docs/records/INTERFACE_CHANGES.md`；完成修改后记录容器内验证结果。
