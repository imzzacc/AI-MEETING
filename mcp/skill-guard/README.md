# Skill Guard MCP

这个 MCP server 给 AI-Meeting 的 Code Agent 提供一个本地知识防腐工具层。它不会静默改业务 Skill，而是把 `git diff`、Skill 影响分析、generated references 更新和代码锚点巡检暴露成可调用工具。

## 工具

- `get_git_diff`: 读取 staged、unstaged、all 或 `base_ref...HEAD` 范围的 diff。
- `skill_diff_check`: 根据 diff 判断可能影响哪些 Skill，并生成风险分级和更新建议。
- `regenerate_skill_indexes`: 运行仓库已有的确定性脚本，更新 generated references。
- `skill_health_check`: 扫描 Skill 文档中的代码路径/引用路径是否失效。

## 本地启动

```bash
py -3 mcp/skill-guard/server.py
```

这是 stdio MCP server，通常由支持 MCP 的客户端拉起，不需要常驻后台监听你的电脑。

## 客户端配置示例

```json
{
  "mcpServers": {
    "ai-meeting-skill-guard": {
      "command": "python",
      "args": [
        "C:/Users/hp/Documents/Codex/2026-07-20/lishuangqiang-ai-meeting-https-github-com/work/AI-Meeting/mcp/skill-guard/server.py"
      ]
    }
  }
}
```

如果本机 `python` 不在 PATH 中，可以把 `command` 改成 `py`，并把 `args` 改成：

```json
[
  "-3",
  "C:/Users/hp/Documents/Codex/2026-07-20/lishuangqiang-ai-meeting-https-github-com/work/AI-Meeting/mcp/skill-guard/server.py"
]
```

在 Codex Desktop 当前运行时里，也可以直接使用内置 Python：

```json
{
  "mcpServers": {
    "ai-meeting-skill-guard": {
      "command": "C:/Users/hp/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe",
      "args": [
        "C:/Users/hp/Documents/Codex/2026-07-20/lishuangqiang-ai-meeting-https-github-com/work/AI-Meeting/mcp/skill-guard/server.py"
      ]
    }
  }
}
```

## 推荐使用方式

开发结束或准备提交前，让 Agent 调用：

1. `skill_diff_check`，范围用 `staged` 或 `all`。
2. 如果报告建议更新 generated references，再调用 `regenerate_skill_indexes`。
3. 如果要做基线巡检，再调用 `skill_health_check`。
4. 对业务规则类修改，先人工 Review，再写回对应 Skill。

## 配置规则

Skill 路由规则在 `skill_guard_config.json` 中维护。新增业务 Skill 后，需要补充：

- `name`
- `description`
- `patterns`
- `keywords`

`patterns` 用来匹配变更文件路径，`keywords` 用来从 diff 内容中捕捉业务信号。
