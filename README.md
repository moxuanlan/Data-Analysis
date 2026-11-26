# DataLens

DataLens 将数据采集、预览、清洗、SQL 脚本和可视化串联到同一个工作台，并结合腾讯元宝搜索与 DeepSeek 辅助分析。

## 在线体验

**访问 DataLens：[https://data-analysis-woad-sigma.vercel.app/](https://data-analysis-woad-sigma.vercel.app/)**

直接通过浏览器访问，无需下载代码，也无需在自己的电脑安装 Java 或创建 MySQL 数据库。注册并登录后，即可使用工作台；线上账号和数据与自行部署的实例相互独立。

使用流程：导入 CSV / Excel 或识别公开网页 → 预览并保存数据 → 智能清洗并确认新版本 → 获取 SQL 或查看图表。

- 网页采集并不适用于所有网站：仅用于学习研究，遵守来源网站的访问规则；登录验证、动态加载或禁止采集的页面可能无法读取。
- 智能清洗会按页面说明向 DeepSeek 发送字段、统计和有限样例，请确认数据适合外发；保存前核对预览，原始数据保留。
- 若要把导出的 SQL 用于本地分析，需要自行创建并选择目标数据库；网站不会代替你执行 SQL。

## 自行部署

技术栈：Java 11+ / Spring Boot 2.7.18 / MySQL 8。本机部署完成后访问：<http://127.0.0.1:8088/DataLens-UI.html>。具体环境配置见下方“本机启动”。

## 已实现

- 账号注册、BCrypt 密码哈希、会话登录／退出及 CSRF 校验。
- CSV / XLSX 真实导入：暂存预览后确认；最多 20 MB、10 万行、100 字段。CSV 支持 UTF-8、GB18030 和逗号／分号／制表符；可选表头、NULL 标记，Excel 可选工作表序号。
- 分页数据集（每页 50 条），来源、时间、原始文本与空值；全量字段统计。列表显示最近 500 个可访问数据集。
- DeepSeek 智能清洗：识别每列用途和处理方案，后端全量预览缺失行、明确范围异常值、日期和金额格式变化；确认后保存独立版本。无法可靠解析的值保留并展示待检查项。旧单规则清洗接口保留兼容。
- SQL 页面仅保留 CREATE / UPDATE / INSERT / SELECT 一个类型下拉框；点击获取查看完整脚本，可下载一个 SQL 文件。UPDATE 需输入原始匹配字段，服务器校验非空和唯一性；高级查询接口保留兼容。
- 团队角色按团队区分：创建者是管理员，被加入者是成员，可继续管理自己的其他团队。管理列表仅展示自己的团队，查看成员不包含创建者；邮箱查找兼容大小写和首尾空格，不允许模糊匹配。
- 静态网页先识别并预览，再采集：识别表格表头，排除重复表头、表尾和已识别的导航/广告区域；列表只选择一个内容列表，不拼接整页列表。支持天气专用结构和正文段落，不执行 JavaScript、不自动翻页。未知结构仍需核对预览。最近三条任务、每个账号每分钟最多创建一次。
- CSV 下载及 17 类可视化工具的字段映射参考内容。代码类提供示例，BI 工具提供数据导入说明；这些不是原生报表，也未逐个工具联调。主页面天气图仍明确标记为演示，不代表已导入数据。
- 腾讯元宝搜索 SearchPro 服务端适配：只发送输入的搜索词，结果显示来源，页面不获取密钥；每分钟最多 5 次。不自动上传字段、样例或全部数据。

## 边界与外部依赖

- DeepSeek 生成 SQL 参数、清洗规则或可视化参考，元宝搜索提供公开来源。服务端校验生成参数，清洗先预览再确认，SQL 只下载不执行。每分钟最多 5 次模型请求，45 秒读超时，普通建议 2048、自动清洗 8192 输出 token；不完整输出会被拒绝。复杂需求超出现有参数能力时要求补充，不声称已完成。真实外部服务调用仍须配置有效凭据并验证。
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

侧栏点击生成会发送问题、字段名、统计信息及最多前 5 行样例（每格 200 字符）。智能清洗按钮会发送字段名、全量统计及每列最多 12 个不同样例（每格 120 字符），页面会事先说明。自动清洗不会调用元宝搜索；模型方案经服务器校验后才展示预览，点击确认才保存。团队只读成员不能将共享数据发送模型。网页摘要始终视为不可信内容，不执行其中指令。搜索结果、生成代码均不自动执行。

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

### 通用网页采集

导入页输入 HTTP/HTTPS 公开网址，点击“识别标题与数据”查看自动标题、提取类型和前 5 条；确认后开始采集。优先读取行数最多的 HTML 表格，其次读取页面列表或正文段落。支持原天气页面，保留温度字段。每次只采集该页面，不自动翻页或执行 JavaScript；登录、重定向、访问限制、合并单元格会给出失败原因。禁止内网和本机地址，响应大小上限 2 MB。数据集以页面标题命名，保存来源网址。

### 网页问答与访问错误

AI 面板直接输入问题或网址，不再单独填写检索词。无网址时元宝 SearchPro 检索前 3 个来源，读取可访问的网页详情后由 DeepSeek 整理回答；有网址时直接读取该网页。界面标出来源及详情读取状态，失败时不编造页面内容。DeepSeek 的可选联网辅助直接使用当前问题。仅允许有限次同站公开页面重定向；错误页、登录验证及明确访问拒绝会停止，并显示原因。

### 可选方案：Vercel + Railway 部署

Vercel 部署前端及同源 `/api/*` 代理；Railway 运行 Docker 中的 Java 服务及 MySQL。Vercel 不能直接运行本项目的 Java 常驻服务。

1. Railway 创建项目，添加带持久卷的 MySQL 服务，再从本仓库根目录部署 Java 服务（读取 `railway.json` 和 `deploy/Dockerfile`）。
2. Java 服务配置 `MYSQLHOST`、`MYSQLPORT`、`MYSQLDATABASE`、`MYSQLUSER`、`MYSQLPASSWORD` 为 MySQL 服务对应变量的引用。数据库须通过私有网络连接；不要开启数据库公网端口。
3. Java 服务按需配置 `DATALENS_TENCENT_SECRET_ID`、`DATALENS_TENCENT_SECRET_KEY`、`DATALENS_DEEPSEEK_API_KEY`。密钥只填平台变量，不提交仓库。为 Java 服务生成 HTTPS 域名。
4. Vercel 导入本仓库 main 分支、根目录，环境变量 `DATALENS_API_ORIGIN` 填上一步的 HTTPS 源站（不含路径）。使用仓库 `vercel.json` 构建部署。
5. 验证 Vercel 地址的登录、上传、采集、图表和 AI，确认请求通过同源代理。首次上线是空数据库，需重新注册；本地数据不会自动上传。

云端使用单副本：登录会话和任务执行队列保存在进程中，重启后需重新登录，进行中的采集标记为中断。先确认平台额度与计费，再启用资源。公开上线前应限制分享范围；当前认证限流仍是会话级，尚未进行公网压力及安全审计。

### 2026-11-21 更新与验证

本次新增接口 `POST /api/data/{id}/clean/auto/apply`，自动预览通过现有 `/api/data/{id}/ai` 的 `purpose=clean_auto` 完成。无需数据库结构迁移，沿用服务器的 DeepSeek 环境变量；不将密钥写入仓库。

更新顺序：先备份当前 JAR，部署 `DataLens-backend/target/datalens-0.1.0.jar` 并用原来的数据库及 AI 环境配置重启；后端启动正常后再发布前端。不要仅替换前端，以免新页面请求旧后端不支持的接口。

本地验证：Java 11 执行 `mvn -f DataLens-backend/pom.xml clean package`；执行 `node scripts/test-workbench.mjs`。自动化测试使用 H2 和模拟模型响应，不会验证生产 MySQL、真实 DeepSeek 额度或公网连通性。浏览器冒烟测试同步使用模拟模型方案和真实测试后端保存接口。

自动清洗不承诺识别所有业务异常；不明确的日期、币种和格式会保留并报告。最多展示 10 个变化样例和 20 个待检查项，处理和计数覆盖整个数据集。模型建议移除的行必须在预览中核对；异常范围基于模型给出的业务解释，不能当作客观事实。
