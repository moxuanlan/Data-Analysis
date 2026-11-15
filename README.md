# DataLens

Java 11+ / Spring Boot 2.7.18 / MySQL 8，浏览器使用的数据处理工作台。本机地址：<http://127.0.0.1:8088/DataLens-UI.html>。

## 已实现

- 账号注册、BCrypt 密码哈希、会话登录／退出及 CSRF 校验。
- CSV / XLSX 真实导入：暂存预览后确认；最多 20 MB、10 万行、100 字段。CSV 支持 UTF-8、GB18030 和逗号／分号／制表符；可选表头、NULL 标记，Excel 可选工作表序号。
- 分页数据集（每页 50 条），来源、时间、原始文本与空值；全量字段统计。列表显示最近 500 个可访问数据集。
- 去重、固定值／均值补缺、日期及金额格式统一、拆分／合并、范围异常标记。先预览后确认，清洗保存独立版本，原始数据不变。
- MySQL CREATE / INSERT / SELECT / UPDATE 与全量 SQL 下载；字段映射、类型、可空和主键校验；SELECT 支持手动筛选、分组、聚合、排序及一个已知数据集关联；UPDATE 必须提供非空、不重复的匹配键。脚本只生成，不自动执行。
- 独立账号团队：创建团队、添加已注册成员、移除成员、数据版本只读共享及撤销。成员可看和下载，不能修改、清洗或删除他人的数据。
- 安徽天气页专用采集：<https://www.tianqi.com/province/anhui/>，后台任务、持久化状态、取消、实际数量和结束原因。只采集当前页面城市天气，不采集历史、动态或登录页。页面数据不足时显示 PARTIAL；不重复数据凑数量。任务列表最近 100 条，每个账号每分钟最多新建一次，本机单工作线程、最多排队 10 个任务。重启中断任务标记 INTERRUPTED，保留已有结果，不自动重试。
- CSV 下载及 17 类可视化工具的字段映射参考内容。代码类提供示例，BI 工具提供数据导入说明；这些不是原生报表，也未逐个工具联调。主页面天气图仍明确标记为演示，不代表已导入数据。
- 腾讯元宝搜索 SearchPro 服务端适配：只发送输入的搜索词，结果显示来源，页面不获取密钥；每分钟最多 5 次。不自动上传字段、样例或全部数据。

## 边界与外部依赖

- DeepSeek 生成 SQL 参数、清洗规则或可视化参考，元宝搜索提供公开来源。服务端校验生成参数，清洗先预览再确认，SQL 只下载不执行。每分钟最多 5 次模型请求，45 秒读超时，2048 输出 token；不完整输出会被拒绝。复杂需求超出现有参数能力时要求补充，不声称已完成。真实外部服务调用仍须配置有效凭据并验证。
- 采集验收只覆盖上述一个静态省份页面。10 万行存储／导出可测试，但该来源通常仅十余条，不能用它证明 10 万条真实网页采集。
- XLSX 使用内存解析，压缩文件上限并不等于内存峰值；提高上限前应改用 SAX 流式解析。公式一律拒绝；数字保留 Excel 原始数值，日期格式不自动推断为日期，文本型前导零保留。
- 不自动创建团队邀请邮件、不验证邮箱或手机号归属、不自动执行 SQL、模型输出或参考代码。
- Spring Boot 2.7.18 已结束开源维护，当前用于本机开发；公网部署前需升级并补充 HTTPS、共享限流、账号验证与备份。账号限流仍为会话级。

## 本机启动

创建 MySQL 数据库 `datalens`（utf8mb4）；应用启动自动添加所需表，不删除原有账号表。数据库账号需要该库的建表及读写权限。

```sh
cd DataLens-backend
mvn package
./start.sh
```

脚本在环境变量未配置时隐藏询问数据库密码，不将凭据写入文件。也可以预先设置 `DATALENS_DB_USERNAME`、`DATALENS_DB_PASSWORD`。Windows 可使用 `start.ps1`。在浏览器打开上述地址，不要双击 HTML，也不要用 Python 静态服务器替代后端。保持后端目录、HTML 和 `Workbench.js` 的相对位置。

可选天气采集代理：`DATALENS_HTTP_PROXY=http://127.0.0.1:7897`，按本机实际代理设置，未设置时直连。

## 元宝搜索配置

只在服务器进程环境中配置 `DATALENS_TENCENT_SECRET_ID` 与 `DATALENS_TENCENT_SECRET_KEY`，之后重启。不要把值放进前端、仓库、日志或聊天。接口未配置、超时、额度不足都会明确报错，不影响数据查看下载。

服务：`wsa.tencentcloudapi.com`，Action `SearchPro`，Version `2025-05-08`。官方 SDK：`tencentcloud-sdk-java-wsa`。
文档：<https://cloud.tencent.com/document/api/1806/121811>。
DeepSeek 使用 `DATALENS_DEEPSEEK_API_KEY`，默认模型 `deepseek-flash`，可通过 `DATALENS_DEEPSEEK_MODEL` 更换。官方接口：<https://api-docs.deepseek.com/api/create-chat-completion>。

停止已有 DataLens 服务后，在原终端运行 `./configure-ai.sh`，隐藏输入新建的两家凭据并启动。脚本不持久保存密钥。不要在服务仍占用 8088 时再启动第二个实例。

生成前需勾选外发同意：默认仅问题、字段名、统计信息，以及用户单独填写检索词得到的来源。样例默认不发送；勾选后最多前 5 行，每格截断到 200 字符。团队只读成员不能将共享数据发送模型。网页摘要始终视为不可信内容，不执行其中指令。搜索结果、生成代码均不自动执行。

凭据更换无需修改源码；更换成其他提供商需要对应协议适配，不能把 SecretKey 当作通用 Bearer API Key。

## 数据保真与导出

默认 SQL 字段为 LONGTEXT，修改类型须通过现有值校验。SQL 文本值使用 UTF-8 十六进制字面量，不依赖反斜杠转义模式。UPDATE 还要求执行目标库的匹配键有唯一约束，平台无法验证用户外部数据库。
CSV 导出用 `\N` 表示 NULL，空字符串单独保留；如果原数据恰含 `\N` 则拒绝此 CSV 导出以避免混淆，可用 SQL 导出。电子表格导入时将不可信文本列设为文本，避免公式求值和前导零丢失。建议先备份目标数据库并在隔离测试库验证脚本。

## 自动验证

```sh
cd DataLens-backend
mvn test -DforkCount=0
```

测试使用独立 H2 内存数据库，不读取用户 MySQL 凭据，覆盖账号隔离、共享及撤销、CSRF、文件解析、清洗、SQL 验证、搜索未配置故障，以及 10 万行完整导出。H2 不能替代真实 MySQL 语法验收。

浏览器测试需要 Node 22+ 和 macOS Google Chrome，使用独立浏览器目录及独立测试服务器：

```sh
# 在 DataLens-backend 目录中
mvn test-compile dependency:build-classpath -Dmdep.outputFile=/tmp/datalens-test-classpath -Dmdep.includeScope=test
java -cp "target/classes:$(cat /tmp/datalens-test-classpath)" dev.datalens.App --server.port=18088 \
  '--spring.datasource.url=jdbc:h2:mem:browser;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1' \
  --spring.datasource.username=sa --spring.datasource.password= --spring.datasource.driver-class-name=org.h2.Driver
# 另一个终端，在项目根目录
node tests/browser-smoke.mjs
```

`DataLens-backend/test_live.py` 是旧的真实 MySQL 账号联调脚本，会创建测试账号，不自动清理；不要把它误认为内存测试。


### 2026-11-15 工作台交互更新

- 文件导入默认首行为字段名，预览表格直接显示单元格内容。
- 当前浏览器中每个账号首次进入工作台弹出流程介绍；顶部“工作流程”可随时重开。支持滚轮、步骤点击、前后按钮和键盘。
- 清洗采用左侧规则、右侧前后对照；只显示所选操作相关参数，预览后才能生成新版本。
- 本地 ECharts 5.6.0 直接绘制六种图形，自动选择数值字段；使用当前数据集前 1000 行，并显示范围，不代表全量统计。重复类别不自动汇总，空值不转为零，玫瑰图拒绝负数。
- AI 侧栏分为腾讯元宝网页检索和 DeepSeek SQL／清洗建议，保留外发确认。
- 验证：`node tests/chart-options.mjs`；启动隔离 H2 服务后 `node tests/browser-smoke.mjs`；`mvn -B -q -f DataLens-backend/pom.xml package -DforkCount=0`。

### 本机 AI 测试配置

后端启动时自动读取 `~/.config/datalens/ai.properties`，无需每次输入 AI 凭据。该文件位于 Git 仓库之外，仅当前用户可读写；不应复制进源码。支持 `DATALENS_TENCENT_SECRET_ID`、`DATALENS_TENCENT_SECRET_KEY`、`DATALENS_DEEPSEEK_API_KEY` 属性。修改后重启服务；同名环境变量优先。其他电脑需单独配置。
