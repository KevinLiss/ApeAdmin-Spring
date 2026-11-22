<div align="center">
  <br/>
  <h1>ApeAdmin-Spring</h1>
  <p> ApeAdmin 的 Java 实现版 · Spring Boot 3 + Vue3 · 100%开源后台管理框架</p>
</div>

<p align="center">
  <a href="http://apehub.finecv.cn/apehub-web">apeadmin官网</a> ·
  <a href="http://apehub.finecv.cn/apehub-web/plugins.html">插件市场</a> ·
  <a href="http://apehub.finecv.cn/apehub-web/docs-portal">开发文档</a> ·
  <a href="https://apehub.finecv.cn/admin">demo体验</a> ·
  <a href="#功能特性">功能</a> ·
  <a href="#架构">架构</a> ·
  <a href="#配置说明">配置</a> ·
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.0.1-blue" alt="version">
  <img src="https://img.shields.io/badge/java-21-orange" alt="java">
  <img src="https://img.shields.io/badge/spring%20boot-3.5-brightgreen" alt="spring boot">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="license">
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20Linux%20%7C%20macOS-lightgrey" alt="platform">
</p>

---

> **本项目由 AI 驱动开发，人工负责产品与质量。** ApeAdmin 在 AI 辅助下高效迭代：人类开发者负责产品方向、架构评审、质量验证与最终决策，AI 负责加速实现、测试和文档工作。

---

ApeAdmin-Spring 是 [ApeAdmin](https://apehub.finecv.cn/apehub-web) 的 **Java 实现版**（Python 版基于 FastAPI）。两版保持**接口契约与语义逐一对齐**：相同的 REST API（`/api/v1`，全局 snake_case 字段）、同一套 Vue3 管理端前端、相同的功能面（RBAC / AI 对话 / MCP 网关 / 插件体系），可按团队技术栈二选一或并存运行。

底座内置 RBAC 权限管控、审计日志、文件管理等企业级基础能力；集成 MCP 网关，可把系统底座和插件的功能封装为标准化 AI 工具供 Agent 调用；AI 助手支持多模型流式对话与 Function Calling。项目采用 MIT 开源协议。

## 功能特性

### RBAC 权限体系

- **五表模型** —— 用户 / 角色 / 菜单 / 部门 + 关联表，标准 RBAC 基础设施
- **四层权限** —— 免登录 → 免鉴权 → 规则鉴权 → 数据范围（本部门 / 本部门及以下 / 全部），数据范围由 MyBatis-Plus 拦截器自动改写 SQL
- **菜单三类型** —— 目录(M) / 菜单(C) / 按钮(F)，支持无限层级树形结构
- **超管通配** —— 超级管理员自动拥有所有权限，普通用户按角色菜单分配
- **动态路由** —— 前端路由从后端菜单树动态生成，`v-permission` 指令控制按钮级显示

### AI 对话

- **多模型支持** —— DeepSeek / 通义千问 / 智谱 GLM / OpenAI / 自定义端点，OpenAI 兼容接口
- **流式 SSE** —— 流式输出 + Markdown 实时渲染 + 代码高亮
- **并行 Run 架构** —— 会话与 Run 分离，消息持久化、中断与状态恢复
- **Function Calling** —— AI 可调用系统工具完成管理操作（用户增删改查 / 系统统计），工具名自动兼容 DeepSeek 等供应商的命名约束
- **配额控制** —— 对话配额语义与 Python 版一致（超限返回 429 与上限提示）
- **密钥管理** —— 后台管理 AI 供应商密钥，加密存储

### MCP 网关

将底座与插件的管理能力对外暴露为 AI Agent 可调用的工具：

- **三原语** —— Tools（工具调用）/ Resources（资源读取）/ Prompts（模板渲染）
- **RBAC 过滤** —— AI Agent 只能看到当前用户有权限调用的工具，执行时二次校验
- **AI 工具面合并** —— MCP 工具与系统工具统一暴露给 AI 助手 Function Calling
- **审计日志** —— 每次工具调用自动记录请求、响应、耗时、调用者（语义对齐 Python 版）
- **内置注册** —— 启动时自动注册内置工具 / 资源 / 提示词（幂等覆盖）

内置 MCP 工具：

| 工具 | 说明 |
|------|------|
| `system_health_check` | 系统健康检查 |
| `system_list_plugins` | 列出已安装插件 |
| `role_list / create / update / delete` | 角色管理（RBAC 校验） |
| `dept_list / create / update / delete` | 部门管理（RBAC 校验） |
| `menu_list / create / update / delete` | 菜单管理（RBAC 校验） |

另内置资源 3 项（系统状态 / 用户统计 / 系统信息）、提示词模板 1 项（system_summary）。

### 三级插件体系

- **L1 静态注册** —— 编译期插件：Spring Bean 实现 `ApePlugin` 接口，启动自动发现，后台启用 / 停用
- **L2 ZIP 热加载** —— 上传 ZIP（白名单校验）→ URLClassLoader 动态加载实现类，插件可注册 MCP 工具与事件监听
- **L3 MCP 外挂** —— 独立进程的 MCP server（stdio JSON-RPC），网关代理其工具，配置 command / args / env
- **EventBus** —— 插件间松耦合事件通信
- **开发示例** —— 内置 `dev_example` 示例插件，覆盖路由 / 工具 / 事件全链路

### 系统管理与审计

- **文件管理** —— 文件 / 文件夹两级结构，上传下载（单文件上限 50MB）
- **登录保护** —— 滑块验证码 + 登录日志
- **操作日志** —— `OperateLog` 注解 + AOP 切面自动记录，支持按模块、时间、用户筛选
- **数据脱敏** —— 序列化层统一脱敏（手机号 / 邮箱等）

### 仪表盘

- **主机监控** —— 基于 OSHI 的 CPU / 内存 / 磁盘实时指标
- **业务统计** —— 用户 / 角色 / 菜单 / 插件 / 在线用户等统计

### 一键自更新

- **后台更新** —— 系统设置 → 版本信息：检查更新 / 上传更新包，自动校验 SHA256、备份旧包、替换 jar 并重启
- **守护拉起** —— 约定退出码 90 表示「更新完成」，`apeadmin-start.sh` / `.bat` 守护脚本或 Docker `--restart` 自动拉起新版本
- **登录态保留** —— Sa-Token 会话持久化到 SQLite，更新重启后不掉登录

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 / 运行时 | Java 21（虚拟线程承载 SSE 长连接与工具调用） |
| 后端 | Spring Boot 3.5.13 + MyBatis-Plus 3.5.18 + Flyway 12.4 |
| 认证 | Sa-Token 1.46（JWT Bearer，会话持久化 SQLite） |
| 数据库 | SQLite（默认 profile，WAL）/ MySQL 双 profile 自动切换 |
| 监控 | OSHI 6.9（CPU / 内存 / 磁盘） |
| 前端 | Vue 3.5 + Vite + TypeScript + Element Plus（构建产物内嵌 `static/admin`，与 Python 版共用源码） |
| AI 协议 | MCP（HTTP API + stdio 外挂代理） |
| AI 模型 | DeepSeek / 通义千问 / 智谱 GLM / OpenAI（OpenAI 兼容接口） |
| 构建 | Maven 多模块 + spring-boot-maven-plugin fat jar |

## 快速开始

### 在线体验

无需自己搭建，直接访问线上演示环境（Python 版部署，两版前端与功能面一致）：

- **案例地址**：<https://apehub.finecv.cn/admin>
- **测试账号**：`ceshi110`
- **密码**：`ceshi110`

> 测试账号为只读访客（viewer）角色，仅可查看各模块数据，无增删改权限。

### 方式一：源码构建运行

要求 JDK 21 + Maven：

```bash
mvn -q package -DskipTests
java -jar ape-server/target/apeadmin.jar
```

打开 `http://127.0.0.1:18080/admin/`，Flyway 自动建表并初始化种子数据：

- **默认账号**：`admin`
- **默认密码**：`ApeAdmin@2026`（首次登录后请立即修改）

### 方式二：发布包（推荐生产）

```bash
# 构建发布包（生成 release/dist/apeadmin-spring-<version>.zip + SHA256）
./release/package-release.sh 1.0.1

# 解压后运行（守护脚本，支持退出码 90 自更新自动拉起）
unzip apeadmin-spring-1.0.1.zip && cd apeadmin-spring-1.0.1
sh apeadmin-start.sh        # Windows: 双击 apeadmin-start.bat
```

发布包只含 `apeadmin.jar` + 启动脚本 + 说明，天然不含任何运行态残留（数据库 / 上传 / 插件 / 密钥均不进入包）。

### 方式三：Docker

```bash
docker build -t apeadmin-spring:latest .
docker run -d --name apeadmin \
  -p 18080:18080 \
  -v apeadmin-data:/app/data \
  -e APE_JWT_SECRET=<生产请更换> \
  --restart unless-stopped \
  apeadmin-spring:latest
```

运行态（SQLite 库 / 上传 / 插件）统一落 `/app/data`，挂卷即可持久化。

### 切换 MySQL

默认使用 SQLite 零配置启动；生产环境切换 MySQL 只需更换 profile 并提供连接信息：

```bash
java -jar ape-server/target/apeadmin.jar \
  --spring.profiles.active=mysql \
  -DMYSQL_HOST=127.0.0.1 -DMYSQL_PORT=3306 \
  -DMYSQL_DB=apeadmin -DMYSQL_USER=root -DMYSQL_PASSWORD=xxx
```

### 默认体验路径

1. 登录后台（默认 `admin / ApeAdmin@2026`）
2. 系统管理 → 用户 / 角色 / 菜单 / 部门 / 文件 / 插件 / 日志 / 设置
3. MCP 管理 → 工具 / 资源 / 提示词 / 调用日志
4. AI 助手 → 配置模型密钥后即可对话
5. 系统设置 → 版本信息 → 一键更新

## 架构

Maven 多模块单体应用，按业务纵向切分，底座与业务解耦：

```
apeadmin-spring/
  ape-dependencies/            # 依赖 BOM（Spring Boot / Sa-Token / MyBatis-Plus / Flyway / SQLite / OSHI 统一版本）
  ape-common/                  # 通用基础（统一响应 R / 错误码 / 分页 / 权限常量 / 工具类）
  ape-framework/               # 框架层（starter 风格，按能力拆分）
    ape-framework-web/         #   全局异常处理 / SSE 助手 / 数据脱敏
    ape-framework-mybatis/     #   BaseDO / 审计字段填充 / 数据权限（DataScope 四层范围 SQL 改写）
    ape-framework-log/         #   操作日志注解 + AOP 切面
    ape-framework-redis/       #   Redis 支持（预留）
  ape-modules/                 # 业务模块
    ape-system/                #   RBAC（用户/角色/菜单/部门）+ 认证 + 文件 + 日志 + 设置 + 自更新
    ape-chat/                  #   AI 对话（Provider 管理 / SSE 流式 / Run 引擎 / 系统工具 / 配额）
    ape-mcp/                   #   MCP 网关（工具/资源/提示词注册与调用 / 审计）
    ape-plugin/                #   插件体系（L1 静态 / L2 ZIP 热加载 / L3 stdio 外挂）+ EventBus
  ape-server/                  # 启动装配 + 仪表盘（OSHI）+ Flyway 迁移 + 内嵌前端（static/admin）
  release/                     # 发布物料（守护启动脚本 / package-release.sh / dist）
  Dockerfile                   # 容器化（JRE 21，数据落 /app/data）
```

### 设计原则

1. **契约对齐** —— REST API 路径、字段（全局 snake_case）、错误码与语义与 Python 版逐一对齐，前端共用一套源码，两版可无缝切换
2. **底座与业务解耦** —— framework 层只提供 starter 能力，业务全部收敛在 ape-modules 各模块，依赖方向单向
3. **双数据库** —— 开发用 SQLite 零配置启动，生产用 MySQL，profile 一键切换；Flyway 全自动迁移与种子数据
4. **虚拟线程** —— Java 21 虚拟线程承载 SSE 长连接与 MCP 工具调用，高并发低开销
5. **权限贯穿 AI** —— MCP 工具调用和 AI Function Calling 均受 RBAC 权限控制，AI Agent 只能操作有权限的资源
6. **守护式自更新** —— 约定退出码 90，守护脚本 / Docker restart 自动拉起新版本；更新包 SHA256 校验 + 旧包备份 + 登录态保留

## 配置说明

核心配置在 `ape-server/src/main/resources/application.yml`（可被环境变量 / 启动参数覆盖）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | 18080 | 服务端口 |
| `spring.profiles.active` | sqlite | 数据库 profile（sqlite / mysql） |
| `MYSQL_HOST` / `MYSQL_PORT` | 127.0.0.1 / 3306 | MySQL 地址（mysql profile） |
| `MYSQL_DB` / `MYSQL_USER` / `MYSQL_PASSWORD` | apeadmin / root / — | MySQL 库名与凭据 |
| `APE_JWT_SECRET` | 内置开发默认值 | JWT 签名密钥，生产环境必须更换 |
| `sa-token.timeout` | 86400 | 登录态有效期（秒） |
| `spring.servlet.multipart.max-file-size` | 50MB | 单文件上传上限（请求总上限 100MB） |
| `ape.upload-dir` | ./uploads | 上传文件目录（自动创建） |
| `ape.plugin.dir` | ./plugins | 插件目录（L2 解包 / L3 注册持久化） |
| `APE_APP_VERSION` | 1.0.0 | 运行版本号（启动脚本 / Docker 注入，自更新对比用） |

### AI 模型配置

在管理后台 → AI 助手 → 模型密钥管理中添加：

| 供应商 | 默认模型 | Base URL |
|--------|----------|----------|
| DeepSeek | deepseek-chat | `https://api.deepseek.com` |
| 通义千问 | qwen-plus | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 智谱 GLM | glm-4-flash | `https://open.bigmodel.cn/api/paas/v4` |
| OpenAI | gpt-4o-mini | `https://api.openai.com/v1` |
| 自定义 | — | 任意 OpenAI 兼容端点 |

## 贡献指南

欢迎贡献！

### 如何贡献

1. **Fork** 仓库并克隆：
   ```bash
   git clone https://github.com/<你的用户名>/ApeAdmin-Spring.git
   cd ApeAdmin-Spring
   ```
2. 创建分支（`feat/`、`fix/`、`refactor/`、`docs/`、`chore/` 前缀命名）
3. 修改代码，确保能构建：
   ```bash
   mvn clean package -DskipTests
   ```
4. 清晰地写 commit（如 `feat: add xxx support`）
5. **Push** 并向 `master` 分支提交 **Pull Request**

### 约定

- 与 Python 版接口契约保持对齐，改动 REST 语义需评估双版同步
- 新业务能力放进 `ape-modules` 对应模块，framework 与 common 不放业务代码
- 业务插件不进底座仓库与发布包，以独立插件包分发
- API 遵循 RESTful 风格，前缀 `/api/v1`，字段 snake_case
- 提交前确保 `mvn clean package` 通过

## 更新历史

| 版本 | 日期 | 要点 |
|------|------|------|
| v1.0.1 | 2026-11-21 | 契约对齐打磨：自更新/重启链路、插件管理交互、MCP 审计与仪表盘全部对齐 Python 契约；AI 工具名兼容 DeepSeek；Sa-Token 会话持久化 SQLite；菜单/部门树选择、401 真实状态码等修复 |
| v1.0.0 | 2026-11-20 | 首个发布版：M1 底座骨架与 RBAC → M2 AI 对话（SSE 流式 + Run 引擎）→ M3 MCP 网关 → 三级插件体系 + 文件管理 → M4 仪表盘与发布链路 → M5 前端契约端点 |

## 社区交流

用微信扫描二维码联系作者加入 ApeAdmin 用户群，反馈问题、分享使用心得（二维码见 [ApeAdmin 主仓库](https://github.com/KevinLiss/ApeAdmin)），或通过 [apehub 官网](http://apehub.finecv.cn/apehub-web) 与仓库 Issue 和我们联系。
