# 用 VS Code 开发容器（Docker）开发

团队统一用 **Docker 容器**做开发环境，前端、后端、JDK、Node 全在容器里，
不依赖成员宿主机装了什么东西，保证三台电脑结果一致。

> 需要本机已装 Docker Desktop（Windows）并开启 WSL2，VS Code 装
> “Dev Containers”扩展（`ms-vscode-remote.remote-containers`）。

## 一、第一次打开

1. 用 VS Code 打开仓库根目录 `silver-followup-agent`。
2. `Ctrl+Shift+P` → 输入并选择：**Dev Containers: Reopen in Container**。
3. 第一次会拉取基础镜像并构建（几分钟），之后秒开。
4. 构建完成后，VS Code 会在容器内自动执行 `npm ci` 与 Maven 依赖预拉取。

容器配置在 `.devcontainer/`：`devcontainer.json` 声明端口与 VS Code 插件，
`Dockerfile` 固定 JDK 17 + Node 22。

## 二、日常启动

在容器内开 VS Code 集成终端（三个终端各跑一个）：

```bash
# 终端 1：后端（Spring Boot，端口 8080）
cd backend
mvn spring-boot:run

# 终端 2：前端（vite 开发服务器，端口 3000）
cd frontend
npm run dev

# 终端 3（可选）：需要热重载 + 远程调试时，用下面参数重启后端
cd backend
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

浏览器访问：

| 地址 | 用途 |
|---|---|
| http://localhost:3000 | 手机端页面 |
| http://localhost:8080/api/demo/health | 后端健康检查 |
| http://localhost:8080/h2-console | H2 数据库控制台 |

`vite` 的 `dev` 配置允许在容器内监听，改前端代码自动热更新；
后端 `mvn spring-boot:run` 默认不自动重启，改完 Java 代码后
`Ctrl+C` 再运行一次即可（本仓库未启用 devtools，保持一致、行为可预期）。

## 三、VS Code 直接点按钮（可选）

安装容器内的扩展后：

- 左侧 **Spring Boot Dashboard** 面板 → 选中 `silver-agent-api` → ▶ 运行（Debug 就是带断点的调试）。
- 前端直接 `npm run dev` 即可，无需额外配置。

## 四、数据与调试端口

- H2 数据保存在具名卷 `silver-backend-data`，不会写进仓库，删除容器后仍在；
  重置演示数据：清空卷再重启（`docker volume rm silver-backend-data`）。
- 5005 是后端远程调试端口（JDWP），在 VS Code 里加一个 Java 调试配置即可断点调试，
  与 IDEA 的体验一致。

## 五、跟宿主机 / 局域网真机联调

页面要调用后端时，前后端都在本容器内开发，直接 `http://localhost:8080` 即可。
需要把页面给手机真机/其他人演示时，把后端与前端临时都放到宿主映射端口：
在浏览器地址里把 `localhost` 换成电脑局域网 IP 即可（端口仍是 3000 / 8080）。

## 六、换机器、换成员

- 提交 `package-lock.json` 与 `pom.xml`，其他人“在容器中重新打开”后依赖自动一致。
- 容器配置改动要进仓库：`docker compose down` 后重新打开即重建。
- 具体启动步骤、跨域、密钥配置与 IDEA 方式对比，见根目录 `README.md`。

## 常见问题

- **端口被占**：关掉宿主机上占 8080/3000 的进程，或在 `devcontainer.json` 里改端口映射。
- **改 pom/package.json 后不生效**：重启对应进程；改了 Dockerfile/依赖版本才需要重建容器。
- **Windows 防火墙弹窗**：首次转发端口放行即可。
