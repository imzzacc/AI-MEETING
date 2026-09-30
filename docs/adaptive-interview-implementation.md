# 可控追问、面经与错题集：实施说明

本分支基于个人仓库 `imzzacc/AI-MEETING` 的 `main`。用户已授权开发、推送及验证通过后合并。当前功能默认关闭；真实模型评估与完整面试 E2E 通过前，不合入 main，不开启 ADAPTIVE。

## 已实现的链路

1. 创建会话后选择 20 / 30 / 45 分钟软目标；主问题由原简历出题流程生成，首次读取时冻结顺序、正文及 SHA-256。已生成主问题的新模式会话不能再次上传简历覆盖题库。
2. 第一次获取题目启动服务端计时。刷新不重置，结束后计时冻结。为全部剩余主问题、收尾和不确定性预留时间；超时关闭可选追问，保留主问题。用户可主动结束，或每次延长 5 分钟，最多 15 分钟。
3. 从会话冻结的本地知识目录检索适用评分点、资料与候选模板。AI 只接收题目、回答、评分点与来源，不接收候选题库；输出评分及回答证据。
4. 校验评分范围、知识点和来源白名单、回答引用原文。资料或原话不足时标为 UNCERTAIN。跨轮冲突保守标为 UNCERTAIN；未提及不计错。
5. LiteFlow `adaptive_followup_v1` 决定追问与推进，覆盖时间、两级次数、知识点去重、后续主问题覆盖、候选撤销和紧急停追问。规则故障只关闭可选追问。提交前再次检查预算。
6. 原文、证据、决策及响应一并持久化。相同 requestId 回放已提交响应，正文不同则冲突；失败回答可从服务器恢复原文和 requestId。回答上限 5000 字符，不截断。
7. 最后一次答题或手动结束原子标记报告待生成。后台轮询归档并组装带原文和资料的 Markdown 面经，自动归类错题。模型调用失败不增加练习次数；两次不同回答连续正确可标为练习验证，用户手动标记单独记录来源。
8. 前端包含时间提示、延期、报告状态、面经导出、全库错题搜索/状态筛选、复习、纠错和移出错题集。报告页继续兼容旧会话。

## 持久化决策（ADR）

spec 中的计划、策略、证据、决策和报告任务是逻辑模型。本实现将它们收敛到 MongoDB `interview_adaptive_session` 单文档，并用 `@Version` 做 CAS：每轮的总分贡献、游标变化、完整证据和成功响应在一次写入中提交。Redis 与原 MySQL 报告是可修复投影，不是新模式的计分权威。

这样避免在 Redis 分数与 Mongo 提交日志之间制造半提交窗口；不需要副本集事务。Redis 锁使用 watchdog 覆盖模型调用与投影阶段，CAS 拒绝陈旧写入。已提交轮次的重试不再次调用模型或加分；模型返回后、结果持久化前崩溃仍可能重新调用模型，不能承诺供应商计费层面的 exactly-once。

报告采用确定性编排已有评分反馈、完整回答和检索资料，不额外调用模型自由总结，降低编造面经与重复费用。错题集保留用户纠正和移除标记，报告重试不覆盖或复活它们。当前错题最多保存 100 次练习；会话主问题最多 100 道、追问最多 20 次，限定聚合大小。

## 知识库范围与导入

目录：`admin/src/main/resources/knowledge/interview-catalog-v1.json`。首批覆盖 Java 17 equals/hashCode、volatile、MySQL 8 InnoDB 隔离，以及 Redis 缓存故障分类；来源含官方 URL、版本、编辑摘要及稳定 ID。

这是本地版本化、关键词/知识点过滤的 RAG，不依赖付费 embedding 或向量数据库。最多映射 5 个主题、给模型 6 段资料，每段最多 2000 字符；单主题候选最多 5 个。不匹配的题目仍正常评分，但不编造知识结论或候选。Redis 故障术语是编辑归纳，不能称为官方逐字原文。

更新目录时应审核资料适用的岗位、技术版本、候选措辞和评分点，递增目录版本。当前目录版本为 `java-starter-v3`，包含每个知识点的岗位及技术版本白名单，并补充明确的快照读、锁定读和缓存失效术语。会话保存过滤后的完整快照及内容哈希，已有会话不随文件更新改变；此版本的资料与映射仍待人工审核。

前端在上传简历前选择岗位、Java / MySQL 版本。`PUT /sessions/{id}/policy` 的可选 `retrievalScope` 为 `{"role":"java-backend","technologyVersions":{"java":"17","mysql":"8.0","redis":"general-v1"}}`；省略时沿用这组默认范围。配置完成后范围冻结，不允许后续请求扩大范围。其他岗位或未列入目录的版本只关闭受影响知识点的检索与追问，保持所有主问题。遗漏版本按不适用处理，排除原因 `ROLE_MISMATCH` / `TECHNOLOGY_VERSION_MISMATCH` / `KNOWLEDGE_SCOPE_MISSING` 随决策保存。目录缺少范围元数据时启动校验失败。

错题按目录版本和范围隔离去重；复习读取来源会话冻结的资料，不使用后续更新的在线目录。当前未提供上传管理界面或向量检索；真实版本差异和跨岗位的模型质量仍待评估，不能把首批示例视为已通过人工审核的生产知识库。

## 外部工作流配置

新增场景 `interview-grounded-evaluation` 默认绑定 `Grounded Interview Evaluator v1`。**不要绑定原“用户答案评分官”**：它的输出缺少知识证据，且会把新输入当作普通回答。

导入 `admin/src/main/resources/workflow/grounded-interview-evaluator-v1.yml` 到实际使用的讯飞工作流平台，选择账号已开通 API 权限且有额度的模型，并登记该工作流的凭据与 flow ID。2026-09-30 实际联调发现 Spark 的语义误判与 JSON 格式失败，已将新评分工作流改为 Qwen3-Next-80B-A3B-Instruct 并发布；原出题工作流未改动。六条真实语义 smoke 通过，60 条合成调试集的严格检查为 43/60，尚未通过正式质量验收。仓库随后补充了单评分点单条证据、原文引用及禁止虚构缺失要求的提示词，19:06后已在讯飞发布并更新绑定，最终编辑器全文与仓库提示一致。13条有限回归通过12条，MySQL案例仍有资料不支持的解释；追加通用规则实验未解决问题，已恢复简洁修正版，恢复后单条API通过。详情及原始失败见 `docs/evals/prompt-publication-20260930/README.md`，不能将此次发布视为正式质量验收通过。模板中的模型配置仍是导入占位选择，不能据此推定其他账号具有模型权限。系统提示另存于 `docs/grounded-evaluator-system-prompt.txt` 供审核；修改时须同步 YAML。

输入 `AGENT_USER_INPUT` 是一个 JSON 字符串，包含 `question`、`answer`、`rubrics`、`sources`；`question` 和 `resume_context` 兼容已有参数入口。输出必须是 JSON：

```json
{"score":70,"feedback":"本轮反馈","analysisSchemaVersion":"1","observations":[{"knowledgePointId":"java.volatile","rubricPointId":"atomicity","state":"INCORRECT","answerQuotes":["volatile 能保证 i++ 原子性"],"sourceChunkIds":["java-jls17-memory"],"rationale":"复合自增并非原子操作"}]}
```

兼容讯飞结束节点在 `choices[].delta.content` 内返回单一 `result` 字符串的包装；只解开这一层，不将任意对象转成评分反馈。`feedback` 必须是非空字符串，语言键对象等格式会明确失败；旧 schema 同样失败，保留待处理回答供重试。模型输出的追问题目或动作不会被采用。结构校验无法证明语义正确；上线前仍需要 spec 中的人工标注质量评估。

## 配置与运行

新配置在 `application.yaml` 和 `.env.example`，Compose 已显式透传：

| 变量 | 默认 | 用途 |
| --- | --- | --- |
| INTERVIEW_ADAPTIVE_ENABLED | false | 允许新会话配置策略 |
| INTERVIEW_ADAPTIVE_MODE | SHADOW | TIME_ONLY / SHADOW / ADAPTIVE，创建时冻结 |
| INTERVIEW_ADAPTIVE_MAX_PER_MAIN | 2 | 单主问题追问上限；0 为禁用 |
| INTERVIEW_ADAPTIVE_MAX_PER_SESSION | 6 | 全场上限；0 为禁用 |
| INTERVIEW_ADAPTIVE_STOP_FOLLOW_UP | false | 紧急关闭现有会话新增追问 |
| INTERVIEW_ADAPTIVE_REPORT_WORKER | true | 是否领取报告任务；关闭不删除已有任务 |
| XUNZHI_AGENT_INTERVIEW_GROUNDED_EVALUATION | Grounded Interview Evaluator v1 | 新工作流绑定名 |

通过环境变量修改配置需要重启服务；撤销候选可设置 `xunzhi-agent.interview.adaptive.revoked-candidate-ids`。禁用新会话不改变已有会话冻结策略；需紧急停追问时使用单独开关。SHADOW 执行 TIME_ONLY 结果，同时保存 ADAPTIVE 对照决策。

索引清单 `scripts/adaptive-indexes.json`，执行脚本 `scripts/adaptive-indexes.js`。由项目负责人在确认目标数据库后从仓库根目录执行：

```powershell
$env:AI_MEETING_MIGRATION_DB='实际目标数据库名'
mongosh '实际Mongo连接URI/目标数据库' --file scripts/adaptive-indexes.js
```

脚本核对数据库名称并创建索引，不删除业务文档。升级时核对并移除旧 `adaptive_mistake_identity` 唯一索引（它遗漏岗位/目录/版本，错误阻止不同范围保存相同缺口），改由包含范围的稳定 `_id` 保证唯一；若旧索引定义不同则中止，要求先检查。回退无需删除索引或证据；先停止追问及新任务领取，并保留能读取新聚合的兼容后端。

已结束的自适应面试可通过 `DELETE /sessions/{id}` 删除，报告页提供二次确认。服务先持久化仅含身份的删除标记，同时移除聚合内的正文、题目和报告；原接口也检查该标记。随后清理数据库、缓存与错题来源，失败可重试，后台每10秒补偿。同一错题的其他场次依据保留；来自删除场次的练习失效，旧练习未记录来源时保守清理。错题已被用户移除的抑制标记会保留，避免任务重放覆盖用户决定。先结束正在进行的面试再删除；供应商托管文件只清本地引用，不宣称已物理删除供应商副本。

前端源自本机 `C:\tmp\AI-Meeting-Frontend` 的 `main@8aa34ee`，保留原许可证和说明；本项目将其放入 `frontend/`，不向原作者仓库推送。开发：`cd frontend; npm ci; npm run dev`，按 `.env.example` 配置后端地址。可选 Compose `web` profile 提供本机 `127.0.0.1:8088` Nginx 入口并转发 `/api` 到 backend；本轮未启动 Docker 部署，未修改用户原 Nginx。

## 验证与当前边界

后端：Java 17，`./mvnw -B -ntp clean verify`。需要真实独立 MongoDB，默认 `127.0.0.1:28029`；可设置 `AI_MEETING_TEST_MONGO_URI`。集成测试仅创建/删除自己随机命名的 `ai_meeting_it_*` 数据库。CI 提供 Mongo 服务，Failsafe 真正绑定 verify，数据库不可用会失败，不跳过。

前端：`npm ci`、`npm run check`、`npm run build`。CI 同时运行前后端检查。本轮额外修正基线中缺失的测试构造参数、Mockito argLine、异步限流 mock、归档调用序列和语音测试的音频时间范围；未通过删用例来放行。

详细数量和限制见 [验收记录](adaptive-interview-validation.md)。已执行45分钟策略真实HTTP面试、30分钟完整浏览器面试（3次RAG追问）、20分钟实际超时后的浏览器面试（10道主问题、0追问）、面经导出、复习、直接UI删除及Mongo/MySQL/Redis清理；保留失败及修复记录。刷新时题目恢复完成前禁止输入。会话锁默认等待2秒应对正常页面并发读取，可通过 `xunzhi-agent.interview.answer-guard.adaptive-lock-wait-millis` 覆盖；持续占用仍明确失败，原模式锁策略不变。正式人工模型/检索评估、最后提示词发布及部分故障场景仍需补齐，自动化不等于完整spec验收完成。
