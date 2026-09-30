# 自适应面试验收记录

更新：2026-09-30。个人仓库 imzzacc/AI-MEETING，功能分支 feature/adaptive-interview-rag，主线 main@be2e9d7，Draft PR #1。用户已授权完整验证通过后合并；目前尚未满足条件，功能默认关闭，main 未合并。

## 最新验证

- 20:14最终反馈防护代码01ffc2b：完整`mvn -B -ntp spotless:apply clean verify`成功，160项单元/服务+6项真实Mongo IT，0失败/错误/跳过。新增5项反馈防护与1项PARTIAL卡片待确认回归。日志grounded-feedback-final-verify.log。先前05095e4的159+6也通过，但以最终160+6为准。
- 有RAG主题的反馈与rationale改由程序从校验后的状态、回答摘录及冻结资料生成。历史90份输出（60+13+16+1，含重复调试样本）用实际编译Java重放；任意替换模型feedback/rationale后，生成的评价完全一致，二次校验稳定。零模型调用；只证明不透传模型自由解释，不证明评分状态正确，原43/60、12/13、13/16结果不改。
- 最终代码真实浏览器面试：10道主问题+2次RAG追问，主问题逐轮不变，刷新恢复、自动结束、面经展示/导出无pageerror。持久化核验6轮有资料反馈与响应一致，4个本场错题均采用受控解释，1条未解决REQUIRED_GAP为NEEDS_CONFIRMATION。此前05095e4另完成一场10+2，保留独立记录。合计新增26份应用供应商响应、33,365 token。详见[反馈防护记录](evals/grounded-feedback-guard-20260930.md)。

- 前一版后端：Java 17.0.19、Maven 3.9.16，`mvn -B -ntp spotless:apply clean verify`，2026-09-30 17:29:50 成功。154 项单元/服务测试 + 6 项真实 Mongo IT，0 失败/错误/跳过。日志 adaptive-pace-final-verify-2.log。新增六组 20/30/45 分钟快慢时钟模拟及发布前预算变化回归；首次新增用例的 Mockito 重设桩写法导致异常，修正后重跑全部范围，保留 adaptive-pace-final-verify.log 失败记录。
- 功能提交 ab5b750 的 GitHub push / PR verify 和 frontend 均成功：[CI](https://github.com/imzzacc/AI-MEETING/actions/runs/36696646458)。CI 不包含付费模型质量或真实浏览器完整面试，后者独立记录于下文及[脱敏验收摘要](evals/verification-receipt-20260930.json)。
- 前端含刷新恢复输入门禁：lint、TypeScript 通过，完整 22 文件、100 项通过，build 成功。此轮 forks / maxWorkers=2 有两个 worker 启动超时（84 项通过，整体失败）；以完整范围 `npm run test:run -- --pool=threads --maxWorkers=1` 重跑全过，未过滤或跳过用例。日志 frontend-recovery-final.log 保留失败，frontend-recovery-threads-full.log、frontend-recovery-build.log 记录成功。
- 真实数据库：个人独立 MongoDB 127.0.0.1:28029；VM 专用 MySQL/Redis，经本机 13306/16379 隧道访问。未使用公司数据库、队列或 Jenkins，未启动用户原部署。
- 应用使用独立 JAR 副本，不锁构建产物。重启后实际并发执行 5 组“恢复 + 当前题目”读取，10/10 成功，20 分钟会话开始时间保持不变。证据 concurrent-browser-reads.json。

## 真实面试与复习

| 场景 | 实际结果及边界 |
| --- | --- |
| 45 分钟策略，HTTP 全流程 | 真实注册/登录、上传合成 PDF、原工作流生成 10 道主问题，答完 10 主问题 + 1 次追问，自动结束。逐轮原题 map 不变；requestId 回放一致、正文冲突拒绝、恢复不重置时钟、跨用户报告拒绝。曾出现 Spark 语义错误，不作为模型质量通过。 |
| 面经与错题 | 报告 SUCCEEDED，Markdown 5,192 字符，2 条错题。真实浏览器登录、报告渲染、下载、搜索和取消删除通过，无 pageerror。此测试使用既有 HTTP 面试结果。 |
| 真实模型复习 | 第一份回答只讲定义、没有回答题目要求的项目场景，未晋升；保留失败断言。随后两份独立完整场景回答依次 REVIEWING → MASTERED，来源 PRACTICE_VERIFIED；重复 ID 不计两次、相同正文换 ID 拒绝、跨用户拒绝。3 次调用 3,930 token。 |
| 30 分钟策略，浏览器全流程 | 5 道原主问题 + 3 次 RAG 追问全部完成，逐轮题库不变，自动结束、面经渲染/导出通过，无 pageerror。首次刷新后提前提交暴露题目未恢复却开放输入的缺陷；修复后续跑同一场，保留失败和恢复记录。证据 browser-full-30.json。 |
| 20 分钟策略，浏览器实际慢答 | 独立账号实际等待20分钟后，界面显示到时可继续主问题，全部10道原题答完，0次追问，逐轮 TARGET_TIME_EXCEEDED，自动结束及面经导出通过。没有修改数据库或浏览器时钟。首场被同账号另开面试废弃的中断保留；独立场测试又发现首次快照读在首题发出前取到 start=0，修正脚本等待题号出现，保存原失败后续跑同场。证据 browser-full-20-slow-b.json。 |
| 手动结束与报告恢复 | 对前述已废弃的未答场次，真实 UI 手动结束，生成 0/10 已答报告，未答不计错。隔离 Mongo 注入过期报告租约后，真实定时 worker 接管并生成相同报告，错题数仍为零。随后另启18003服务，与18002竞争同一过期任务；两进程日志均观察到同一会话锁竞争，20秒测试锁到期后仅增加1次领取，报告相同且无重复错题。证据 two-worker-report-e2e.json；临时第二进程已停止，无模型调用。 |
| 旧客户端兼容 | 不调用新 policy API，真实上传、原评分工作流、一次答题、题库不变、手动结束及恢复通过；timeBudget 均缺省。仅一次旧模式答题，未冒称所有旧会话和语音均已验收。证据 legacy-api-compatibility.json。 |

全部使用合成简历、回答和专用账号。文本浏览器流程不包含实际麦克风或摄像头验收；独立 TTS 凭据未配置，自动朗读请求被拒绝，不把文本流程称为语音功能通过。

## 删除链路

已实现已结束自适应会话 DELETE API、报告页二次确认、删除标记、后台补偿、共享错题来源及复习缓存清理。删除与恢复/投影共用会话锁，复习发布前重新核验来源。

首场真实 UI 删除失败：保存删除标记后 Redis scan 抛 ListScanResult.getPos() 的 NoSuchMethodError，页面未跳转。实际 JAR 混用 Redisson core 3.21.0 与 starter/adapter 3.27.2。6ae04e2 统一为 3.27.2，完整验证后重启，后台补偿成功。

真实删除前后：首场 question 1→0、hot/cold 各 1→0、turn archive 11→0、错题来源 2→0、MySQL interview_record 1→0、Redis 派生键 18→0；agent message/conversation/file asset 无残留，deletionComplete=true，聚合正文和报告已清空。再次 DELETE 成功；报告、恢复、题目、导出、重试与跨用户 DELETE 均拒绝。新 30 分钟场次又从报告页确认删除成功：8 个 turn、5 个错题来源、30 个 Redis 派生键及 SQL/Mongo 投影均清零。证据 browser-deletion-30-result.json、erasure-30-before-counts.json、erasure-30-after-counts.json；首场失败证据保留。

仅清供应商托管文件的本地引用，未接入讯飞文件本体删除 API，不宣称供应商副本已物理删除。旧错题 unique 索引遗漏范围已修复，隔离测试库按核验过的旧定义迁移，未删除业务文档。

## 模型与检索质量

Spark Pro-128k 曾把“volatile 保证 i++ 原子性”判 100/COVERED，并把错误回答改写成表扬；强化语义提示后又产生非法 JSON。只将新评分工作流改为 Qwen3-Next-80B-A3B-Instruct 并发布/绑定，原出题框架不变。

六条真实语义 smoke 通过；随后 20 题、60 条合成回答严格检查 **43/60**，62,777 token。标签由助手编写，humanReviewed=false，不能称正式人工验收：

- 9 条“不知道”返回空 observations：运行时安全弃权，严格评估要求显式状态而失败。
- 6 条重复评分点：运行时可合并同状态，严格评估拒绝重复；保留失败，不修改检查追求全绿。
- m3-correct 引用问题原文，并添加资料未支持的幻读解释；c3-incorrect 无回答引用却声称 COVERED。运行时降 UNCERTAIN 不等于反馈文字准确。

19:06 起已在解锁后的讯飞页面发布“每个评分点一条、引用只来自 answer、PARTIAL 不得虚构缺失要求”的提示词，完成 API 配置更新及绑定。完整编辑器文本与仓库模板按空白归一化比较一致；原出题工作流未改。

这次有限回归并非重跑全部60条：抽取7条旧失败案例并加原6条smoke，严格通过 **12/13**，15,913 token。空观察、重复评分点及c3引用问题在本轮抽样中通过；m3-correct仍把资料未要求的幻读细节当作缺失，并补充资料不支持的解释，不能称已修复语义问题。

另尝试一段“语义充分性/技术纠正限定于资料”的通用提示，增加3条部分正确回答，严格 **13/16**，22,050 token。m3仍失败，c3再次把题目当成回答引用。新volatile样例的NOT_OBSERVED与助手建议PARTIAL不一致，但它只展示可见性、未展示atomicity评分点，因此属于待人审的标签范围争议，不直接归为模型技术错误。原输出和建议标签均保留，未修改标签追求通过。

追加提示没有解决问题，最终线上恢复已提交的简洁修正版；恢复后网页调试及1条新API请求通过（API 1,210 token）。三次网页调试分别1,183、1,367、1,200 token。最终API成功仅证明可调用和该样例契约成立；此前12/13不是重新发布后的独立质量验收。所有输入、输出、实验提示及最终文本哈希见[发布回归记录](evals/prompt-publication-20260930/README.md)。

额外用实际编译后的 Java InterviewEvidenceValidator 离线重放全部 60 条结果：两处无效事实证据被降为 UNCERTAIN；59/60 与未审核建议状态相符（缺省观察按未观察处理），没有新增模型调用。此结构防护结果不改变原始严格 43/60，不证明反馈文字或语义准确。证据 grounded-runtime-audit.json。

原始基线：[60 条输出](evals/grounded-qwen-baseline-20260930.json)、[建议标签](evals/grounded-review-draft-60.json)、[人工复核包](evals/grounded-review-packet.md)。本组已用于调试，正式验收须按题目独立，并补部分正确、版本差异、转写错误、长回答、追问补全和诱导改题。尚未通过人工 precision / recall / 引用支持率目标。

检索还发现“快照读”“热点键失效”等明确术语未映射。目录 java-starter-v3 补充别名，真实目录回归覆盖正例、无关负例及版本隔离；既有会话保留冻结 v2。资料、候选、映射仍待人工审核，不声称 Recall@6 ≥90%。目前只有四个主题，资料不足时正常问主问题、关闭知识追问。

## 费用与待完成条件

本轮累计授权人民币5元，无充值或购买。此前已知121,341 token，加本次30次API共39,173 token及3次网页调试3,750 token，最新已知累计 **164,264 token**。此前应用的39份响应按cacheKey合并保存，避免TTL或删除后漏算。Qwen页面价格：输入原价1 / 折扣0.6元每百万token，输出原价4 / 折扣2.4元每百万token。本次续作42,923 token即使全部按较高的输出原价计算约0.172元，这是价表估算，实际平台账单未核对。所有调用有次数上限，无自动模型重试。

20:19更新：两场反馈防护真实面试新增33,365 token，累计 **197,629 token**；应用用量账本从39份/45,567增至65份/78,932，其他网页与直接API累计量不变。没有新批量调参或充值。实际账单仍未核对，不将token用量当作已付金额。

提示词发布及有限复验已完成，但语义失败仍未解决。合并前仍需修复或保守处理这些质量问题、完成独立题目及人工标注质量评估、人工审核资料和映射。三档快慢组合已有注入时钟的服务级回归，真实体验已完成45分钟策略HTTP、30分钟策略完整浏览器快答、20分钟真实超时浏览器慢答；完整故障矩阵、所有旧记录形态、实际语音尚未全部验收。已完成的重启、过期租约和双进程竞争恢复不等于所有故障注入通过，mock、skip、构建或单条供应商调用不能替代完整spec。

20:19保守处理已落地：模型自由技术解释不再透传到新RAG评价，PARTIAL不直接进入已确认缺口；主问题与规则裁决不变。剩余质量限制是语义状态、参考分数和来源本身仍未经独立人工验收，不能因展示内容受控就宣称模型准确率达标。默认开关仍关闭、PR仍Draft、main未合并。

## 证据与历史失败

个人日志在 C:/Users/hp/Documents/AI-MEETING-Workspace/temp/；自动化 XML 在 admin/target/surefire-reports 与 failsafe-reports。测试账号、供应商凭据、签名简历 URL 和完整运行配置只留仓库外，不提交整个 temp。

PowerShell 将 JVM stderr 当错误、JAR 被运行进程占用的失败记录保留，完整重跑后才报告成功。Mongo 点号 Map key、错题范围唯一性、复习删除失败重试、Redisson 版本冲突均已有修复/验证。

原模板凭据曾进入 Git 历史，当前模板已清空；未替用户轮换账号密钥或重写历史。新增文件扫描不能证明整个历史无凭据。
