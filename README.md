# DataLens

数据分析平台原型，包含东方明珠首页、登录注册、产品概览及导入、预览、清洗、SQL 和可视化演示。

## 当前实现范围

- **真实功能**：MySQL 账号注册、BCrypt 密码哈希、登录、会话识别、退出登录与 CSRF 防护。
- **演示功能**：网页采集、文件导入、清洗、AI 回复、可视化均为样例交互；SQL 复制和多选合并下载使用样例数据。
- 暂不发送手机号／邮箱验证码，不校验账号归属。

## 本地启动

准备 Java 21、Maven 3.6.3+、MySQL 8。默认数据库地址为 `127.0.0.1:3306`，应用端口为 `8088`。

```sql
CREATE DATABASE IF NOT EXISTS datalens
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

在本机环境变量中设置 `DATALENS_DB_USERNAME` 和 `DATALENS_DB_PASSWORD`，使用自己的数据库凭据，不要提交密码到仓库。应用启动时自动创建用户表。

Windows PowerShell：

```powershell
cd DataLens-backend
.\start.ps1
```

其他环境（已设置上述环境变量与 Java 21）：

```sh
cd DataLens-backend
mvn spring-boot:run
```

打开 **http://127.0.0.1:8088/DataLens-UI.html**。请勿双击 HTML 使用真实登录。保持 HTML 与后端文件夹在同一父目录。

## 验证

```sh
cd DataLens-backend
mvn test -DforkCount=0
```

真实 MySQL 联调脚本为 `DataLens-backend/test_live.py`，需先启动服务；脚本会创建测试账号，运行后需按输出账号精确清理。

## 部署边界

当前仅监听本机地址，GitHub 保存源码，不提供在线后端。公网使用前需配置 HTTPS、Secure Cookie、独立最小权限数据库用户、账号/IP 限流及账号归属验证。会话限流仅适合本地开发。未包含个人数据库数据、凭据、Java 安装包、构建产物和日志。

更多说明见 [后端 README](DataLens-backend/README.md)。
