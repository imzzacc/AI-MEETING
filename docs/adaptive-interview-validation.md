# 自适应面试验收记录

更新日期：2026-09-30。分支：`feature/adaptive-interview-rag`，基线：`main@be2e9d799ee943a53eaecd9806cf0daa57338255`。

状态：本地实现与真实联调阶段。2026-09-30 16:23:47 后端最终复验142项单元/服务、6项真实Mongo测试全部通过；前端99项测试通过，lint、类型检查、构建成功。首场完整HTTP面试及浏览器报告流程已运行，评分语义回归6/6通过。尚未满足完整spec合并条件，功能默认关闭，main未合并。

## 环境与测试入口

- Windows，Java 17.0.19，Maven 3.9.16；真实 MongoDB 8.0.15 独立实例，绑定 `127.0.0.1:28029`，测试数据库使用随机名称，结束后删除。
- 后端最终入口：格式化后 `mvn -B -ntp clean verify`，含 Surefire、Failsafe、打包与格式检查，不跳过测试。最后一次源代码修改后重跑，2026-09-30 15:21:11（Asia/Shanghai）BUILD SUCCESS；40 个 Surefire 测试类 136 项，1 个 Failsafe 测试类 3 项全部通过。首次命令被 PowerShell 的 Stop 错误策略将 JVM stderr 警告当作异常中断，不能记为成功；改用合并 stdout/stderr 的 subprocess 后完整重跑成功。
- 前端最终目录：`AI-MEETING/frontend`，执行 `npm ci`、`npm run check`（ESLint、TypeScript、Vitest）、`npm run build`。22 个测试文件，99 项通过，0 失败，0 跳过；构建成功。
- `git diff --check`、`docker compose config --quiet` 通过。Compose 校验不代表镜像构建、Nginx 实际部署或浏览器 E2E。
- 对新增前端源码与工作流进行凭据特征检查，未发现私钥/常见 API token；未复制原 `.env.development` / `.env.production`，未复制凭据、node_modules、dist 或原仓库 Git 元数据。

## 自动化覆盖

| 范围 | 验证内容 | 证据边界 |
| --- | --- | --- |
| AT-01—06 | 主问题固定、剩余主问题预算、目标超时继续主线、单题/全场上限、0 禁追问 | 规则和服务测试；三个档位真实体验未跑 |
| AT-07—12 | 覆盖/未涉及、无证据降为待确认、去重、后续主题保留、候选撤销、TIME_ONLY 隔离画像 | 合成证据；不代表真实模型质量 |
| AT-13 | 发题前二次规则门禁与不推进未提交游标 | 发布代码已接入；真实慢模型耗时场景未跑 |
| AT-14—18 | 不重置时钟、同 ID 回放、不同正文冲突、投影失败重试、长回答保存、CAS 并发只一个成功 | 服务测试及真实 Mongo 重读；未做完整应用进程断电/Redis 清空 E2E |
| AT-19—21 | 超长拒绝、未回答不计错、冲突待确认、主问题分数不被追问改写 | 服务/证据校验测试；真实转写和追问补全质量待验 |
| AT-22—25 | 自动/手动结束排队、报告重试、保存用户纠正、删除错题不复活、同重试 ID 不重置任务 | 任务逻辑及持久化测试；跨进程 worker 超时夺租待验 |
| AT-26—27 | 不同用户读取拒绝、Mongo 查询按用户过滤、已删除来源不读取/发布 | 非真实登录 E2E；完整会话删除 API 尚不存在，不能声称删除全链已交付 |
| AT-28—29 | 复习失败不记错、相同 ID 不重复、相同答案不算第二次独立练习、延期幂等与上限 | 服务与前端测试 |
| AT-30 | 旧接口原测试继续通过；旧会话没有 timeBudget 时不展示新功能 | 旧记录/新旧客户端真实联调待验 |
| LiteFlow | 读取实际 XML 链，真实 FlowExecutor 运行候选选择和超时分支 | 未冒充真实 HTTP 面试 E2E |
| 工作流契约 | 专用场景、拒绝旧 schema/非字符串反馈、完整回答、忽略 AI 发题指令；兼容讯飞 result 包装 | 4 项回归测试及 1 条真实 API 合成样本通过；不代表质量评估或应用 E2E |
| 前端 | 提交重试 ID、刷新恢复、结束失败留在原会话、超时不强制结束、延期重用 ID | React/Vitest；尚无真实浏览器完整面试验收 |

## 尚未完成的发布门槛

1. 原四个面试工作流仅完成缺输入的定位校验（22500），尚需在完整应用流程中验证简历上传、主问题生成及旧场景兼容。新 grounded 工作流的发布与单条真实输出契约已验证，细节见下文；这不替代完整面试 E2E。
2. 隔离环境正在准备真实登录和完整 HTTP/前端流程，测试数据只使用合成简历与回答。原部署数据库未启动、没有读取旧用户或面试记录。
3. 首批资料的人工审核、按岗位和技术版本的检索评估，以及至少 20 道题/60 条人工标注回答的真实模型评估。当前只有 4 个示例知识点，未声称 precision、recall 或引用支持率达到 spec 门槛。
4. 从真实前端或 HTTP API 完成创建、上传、固定题库、动态追问、时间紧张省略追问、恢复、结束、面经和错题复习；覆盖三档时长及快慢脚本。真实账号鉴权、并发 worker 夺租和删除链路仍需验证。
5. 岗位/版本选择器及检索白名单现已补齐，首批仍只覆盖 Java 后端、Java 17 / MySQL 8.0 与通用缓存模式。知识库上传审核界面及完整会话删除入口尚未完成。在线检索使用本地目录方案，不能把未完成的管理能力描述成已交付。

这些缺口没有用 mock、skip 或构建成功替代。分支可以供 review，但不能据此称“完整功能已验证无问题”或合入 main。

## 2026-09-30 真实面试与评分修复进展

以下记录更新上方初始验收缺口：在隔离应用18002、专用MySQL/Redis及Mongo库中，真实注册登录、上传合成PDF、原工作流生成10道主问题、回答全部主问题和一次MySQL追问、自动结束已实际完成。每轮核对主问题map不变；同requestId回放一致、正文冲突拒绝、刷新计时不变、第二账号读取报告被拒绝。报告SUCCEEDED，Markdown 5,192字符，产生2条错题（MySQL追问后补全、缓存待复习）。这是HTTP E2E，未替代浏览器完整面试或语音验收。

该场同时暴露质量失败：Spark Pro-128k将“volatile能保证i++原子性”的明确错误回答判100分/COVERED，反馈还错误声称用户解释了非原子性。结构校验不能识别此语义矛盾。因此该场不作为质量验收通过。增加通用“候选人实际断言与来源事实逐条比较、保留否定与条件、禁止把参考答案归功于候选人”的提示后，Spark网页调试又输出非法JSON，也记录为失败。

仅新评分工作流切换Qwen3-Next-80B-A3B-Instruct，更新API配置及绑定；中文错误样例调试与API契约通过。`docs/evals/grounded-semantic-smoke.json` 六条真实API样例全部通过，涵盖正确/错误/不知道及嵌入指令。样例由助手编写，**未人工审核，不是正式20题/60条验收集**。保留脚本和逐条输出供复核，不用单一模型分数替代语义审核。

用量：此前3,818 + 首场出题/评分11,085 + Spark新提示调试1,161 + Qwen网页1,048 + Qwen API契约991 + 六条回归5,979 = 已知24,082 token。预算仍为本轮累计人民币5元，未充值或购买套餐，尚未核对实际账单金额。

本机证据位于个人temp目录：`e2e-completed-interview.json`、`e2e-report-and-mistakes.json`、`e2e-provider-usage.json`、`grounded-qwen-contract.json`、`grounded-qwen-semantic-smoke.json`。全部为合成资料；账号令牌配置单独保存在仓库外，不随证据提交。

新增删除与来源竞态回归6项、Mongo投影删除及不同范围同缺口持久化2项。最终 `spotless:apply clean verify` 为142+6项，0失败/跳过，日志 `adaptive-deletion-final-verify.log`。最初前端 `npm run check` 的lint、TypeScript通过，Vitest部分worker启动超时；完整范围以 `npm run test:run -- --maxWorkers=2` 重跑22文件99项全通过，无过滤/跳过；`npm run build` 成功。日志 `deletion-frontend-tests-2workers.log`、`deletion-frontend-build.log`，原失败日志保留。

独立headless Edge从真实登录页面开始，面经渲染、Markdown下载（5,192字符）、错题搜索和删除二次确认取消通过，无页面异常。该浏览器测试使用首场既有HTTP面试结果，尚非从浏览器上传到答题结束的完整E2E。证据 `browser-review-e2e-result.json`、`browser-review-e2e.png`、`browser-exported-review.md`。新删除API的真实HTTP/数据库清理验收仍待运行。

## 凭据核查补充

2026-09-30：用户提供仓库外的新工作流配置文件，四个必要字段齐全，名称与新评分场景一致。空输入请求返回 22500（缺少 AGENT_USER_INPUT），说明流程可定位。用户说明选择 DeepSeek V4 Flash、网页调试成功，并授权本轮累计最多人民币 5 元。实际进行了两次单条合成回答 API 请求（第二次用于获取脱敏错误说明），均返回 20373：`Model authorization error: appId has no feature authorization or business volume exceeds limit`，两次返回 prompt/completion/total tokens 均为 0。已停止模型调用；平台实际扣费尚未查询，不能把 0 token 直接称为已核实零费用。需在讯飞核对发布所绑定应用的模型 API 权限及剩余额度，不能据网页调试成功宣称 API 联调通过。该预算只覆盖本轮，不是后续无限授权。

后续已通过已登录浏览器确认“码上面试”应用的 Spark Pro-128K 页面有 5,000,000 token 余额、有效期至 2050-12-01。只将新 grounded 工作流切为 Spark Pro-128k，更新严格字符串反馈提示词，通过网页调试后更新 API 配置与绑定；没有购买或充值。原授权错误已消失。

成功生成用量按执行顺序为：网页 713、API 734、API 768、更新提示词后网页 802、最终 API 801，共 3,818 token。中间两次 API 暴露了 result 字符串包装及 feedback 语言对象问题；最终 API 返回 code 0，schema、score、feedback、无发题动作、observations、原文/来源引用、明确错误识别全部通过。该样例是固定合成的 volatile 错误回答。平台实际账单金额尚未核对，不能将 token 额度等同已核实零扣费。

可重复诊断脚本 `scripts/check-grounded-workflow.py --config <仓库外配置路径>` 默认只发缺少输入的定位请求；加 `--generate` 才执行一次合成回答契约检查，无自动重试。`--save-synthetic-response <仓库外路径>` 可保存这条固定合成样本的脱敏响应供复现。脚本只输出检查结果、token 用量及脱敏供应商错误，不输出凭据或完整模型回答。此脚本不是后端 HTTP E2E，也不是 60 条模型质量验收；当前仍不能合并 main。

旧工作流模板中的同一组凭据也存在于 Git 跟踪文件中。本次将模板内 `apiKey` / `apiSecret` 清空，原本机目录保持不变；YAML 解析对比确认除这两个字段外数据结构及内容相同。部署时在讯飞账号内配置凭据，不回填到仓库。已进入 Git 历史的凭据需要账号持有人在讯飞控制台轮换；本次未撤销账号密钥或重写远端历史。

此前“未发现凭据”的检查仅覆盖新增前端和新增工作流，不能作为既有仓库无凭据的证明。已重新检查跟踪文件，确认当前文件不再包含本次识别出的这组值；此检查不等于完整历史扫描。

## 岗位及版本边界补充

- `java-starter-v2` 的岗位和版本白名单在调用模型前过滤资料与候选。自动化覆盖岗位不兼容、技术版本不兼容、未指定版本、缺失范围元数据以及恶意/超长范围格式；服务级用例确认资料为空时原主问题内容与顺序保持不变，模型不会收到被排除资料，决策中保存原因。
- 错题复习使用来源会话的冻结资料；不同目录版本或岗位/技术范围不合并为同一错题。上述为确定性自动化证据，不替代人工检索标注或真实模型验收。
- 本轮 SSH 检查发现原前端 Nginx 报上游主机无法解析，后端、MySQL、Mongo、Redis 均已停止；未启动或变更原部署，没有将其他项目的服务作为本任务测试环境。

## 日志

2026-09-30 HTTP 联调补充：真实账号注册/登录和会话创建成功后，策略配置暴露 Mongo 默认拒绝 `mysql.isolation` 等含点号的 Map key。新增 Mongo 映射配置保留原始键（要求 MongoDB 5+，项目部署版本为 7/8），避免改成下划线后与已有 ID 碰撞。真实 Mongo 回归现使用完整目录、范围和决策排除原因，并经 Spring 配置装配验证往返一致。修复后 15:35:48 完整 `clean verify` 通过：136 项单元/服务测试、4 项 Mongo IT，0 失败/跳过，日志 `grounded-mongo-map-final-verify.log`。前次打包被正在运行的测试应用占用 JAR 中断；后续应用从独立运行副本启动，不锁构建产物。

本机日志在个人工作区 `C:\Users\hp\Documents\AI-MEETING-Workspace\temp\`：后端本轮最终 `grounded-contract-final-verify.log`，最终单条合成 API 响应 `grounded-live-response-strict.json`；上一轮 `ai-meeting-scope-final-verify-2.log`、`ai-meeting-scope-frontend-check.log`、`ai-meeting-scope-frontend-build.log` 保留。后端 XML 结果位于 `admin/target/surefire-reports` 和 `admin/target/failsafe-reports`。上述证据不包含真实用户面试资料或供应商密钥，不提交整个构建目录或个人测试环境配置。
