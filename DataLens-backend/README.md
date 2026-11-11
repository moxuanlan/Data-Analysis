# DataLens 登录后端

Java 21 / Spring Boot / MySQL，本地监听 127.0.0.1:8088。

数据库为 datalens；凭据通过 DATALENS_DB_USERNAME 和 DATALENS_DB_PASSWORD 环境变量提供，不写入源码。

运行 `./start.ps1`，访问 http://127.0.0.1:8088/DataLens-UI.html 。不要直接用 file:// 打开后端版页面。
页面从父目录 DataLens-UI.html 提供，需保持目录关系。

接口：GET /api/auth/csrf、POST /api/auth/register、POST /api/auth/login、GET /api/auth/me、POST /api/auth/logout。
POST 必须携带 CSRF 接口返回的请求头与令牌。会话使用 HttpOnly、SameSite=Strict Cookie，30 分钟闲置失效。
密码使用 BCrypt（成本 12），数据库不保存明文密码。手机号和邮箱正常保存，尚无归属验证码验证。

当前仅供本机开发。部署公网前必须配置 HTTPS / Secure Cookie、独立最小权限数据库用户、共享的账号/IP 限流及验证码验证。当前限流仅每会话一分钟 10 次，不能抵御重新建立会话的攻击。其他数据分析功能仍为前端演示，不是后端业务接口。

测试：mvn test -DforkCount=0。测试使用模拟数据库；真实 MySQL 联调需另行执行。

已执行真实 MySQL 联调：注册、重复账号、弱密码、错误密码、会话识别、退出失效、CSRF 缺失拒绝、页面访问均通过。test_live.py 会创建 qa- 前缀测试账号，执行后需按输出账号精确清理。
