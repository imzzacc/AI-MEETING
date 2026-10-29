# 评分评测数据

| 文件 | 用途 |
| --- | --- |
| `dev-60.json` | 调试集：20 道题、60 条回答，调提示词时使用 |
| `holdout-60.json` | 验收集：20 道新场景题、60 条回答，标签在调用模型前冻结 |
| `smoke-6.json` | 冒烟用例：6 条，用来快速检查工作流输出格式 |

回答都是合成的，标签还没有经过人工复核（`humanReviewed: false`）。

每条用例的结构：

```json
{
  "id": "s01-1",
  "topicId": "java.volatile",
  "question": "...",
  "answer": "...",
  "expectedStates": { "atomicity": ["COVERED"] }
}
```

`expectedStates` 的键是评分点 ID（必须存在于知识目录中），值是可以接受的状态列表。运行和汇总方式见 [testing.md](../testing.md#怎么跑)。
