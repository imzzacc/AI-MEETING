from __future__ import annotations

import fnmatch
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SERVER_NAME = "ai-meeting-skill-guard"
SERVER_VERSION = "0.1.0"
PROTOCOL_VERSION = "2024-11-05"

ROOT = Path(__file__).resolve().parents[2]
CONFIG_PATH = Path(__file__).with_name("skill_guard_config.json")


@dataclass
class SkillRule:
    name: str
    description: str
    patterns: list[str]
    keywords: list[str]


def load_config() -> dict[str, Any]:
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))


CONFIG = load_config()
SKILLS = [
    SkillRule(
        name=item["name"],
        description=item.get("description", ""),
        patterns=item.get("patterns", []),
        keywords=item.get("keywords", []),
    )
    for item in CONFIG.get("skills", [])
]
INDEX_SCRIPTS: dict[str, str] = CONFIG.get("index_scripts", {})


def run_git(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def diff_args(mode: str = "staged", base_ref: str | None = None) -> list[str]:
    if base_ref:
        return ["diff", f"{base_ref}...HEAD"]
    if mode == "staged":
        return ["diff", "--cached"]
    if mode == "unstaged":
        return ["diff"]
    return ["diff", "HEAD"]


def changed_file_args(mode: str = "staged", base_ref: str | None = None) -> list[str]:
    return [*diff_args(mode, base_ref), "--name-only"]


def normalize_path(path: str) -> str:
    return path.replace("\\", "/").strip("/")


def matches_pattern(path: str, pattern: str) -> bool:
    path = normalize_path(path)
    pattern = normalize_path(pattern)
    return fnmatch.fnmatch(path, pattern)


def read_diff(mode: str, base_ref: str | None, max_chars: int) -> str:
    result = run_git(diff_args(mode, base_ref))
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "git diff failed")
    diff = result.stdout
    if len(diff) > max_chars:
        return diff[:max_chars] + f"\n\n[truncated: {len(diff) - max_chars} chars omitted]"
    return diff


def read_changed_files(mode: str, base_ref: str | None) -> list[str]:
    result = run_git(changed_file_args(mode, base_ref))
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "git diff --name-only failed")
    return [normalize_path(line) for line in result.stdout.splitlines() if line.strip()]


def score_skill(skill: SkillRule, files: list[str], diff_text: str) -> tuple[int, list[str]]:
    reasons: list[str] = []
    score = 0
    for file in files:
        matched = [pattern for pattern in skill.patterns if matches_pattern(file, pattern)]
        if matched:
            score += 4
            reasons.append(f"path `{file}` matched `{matched[0]}`")
    diff_lower = diff_text.lower()
    for keyword in skill.keywords:
        if keyword.lower() in diff_lower:
            score += 1
            reasons.append(f"keyword `{keyword}` found in diff")
    return score, reasons[:6]


def risk_level(files: list[str], diff_text: str, impacted_count: int) -> tuple[str, list[str]]:
    reasons: list[str] = []
    high_patterns = [
        "admin/src/main/resources/workflow/",
        "admin/src/main/resources/application.yaml",
        "admin/src/main/resources/interview-followup-rule.yaml",
        "admin/src/main/resources/sql/",
    ]
    high_keywords = [
        "@RequestMapping",
        "@PostMapping",
        "@GetMapping",
        "@CurrentUser",
        "SaToken",
        "singleflight",
        "questionNumber",
        "requestId",
        "status",
    ]
    medium_keywords = ["DTO", "Mapper", "Entity", "cache", "timeout", "retry", "WebSocket", "SseEmitter"]

    for file in files:
        if any(file.startswith(pattern) for pattern in high_patterns):
            reasons.append(f"`{file}` touches a contract/config/sql/workflow file")
    for keyword in high_keywords:
        if keyword.lower() in diff_text.lower():
            reasons.append(f"high-risk keyword `{keyword}` found")
    if impacted_count >= 3:
        reasons.append("change impacts three or more skills")

    if reasons:
        return "high", reasons[:5]

    medium_reasons = []
    for keyword in medium_keywords:
        if keyword.lower() in diff_text.lower():
            medium_reasons.append(f"medium-risk keyword `{keyword}` found")
    if medium_reasons or impacted_count >= 2:
        return "medium", medium_reasons[:5] or ["change impacts multiple skills"]
    return "low", ["localized change with no obvious contract keyword"]


def suggested_index_scripts(files: list[str]) -> list[str]:
    suggestions: set[str] = set()
    for file in files:
        if "/api/" in file or file.endswith("Controller.java"):
            suggestions.add("api")
        if file in {"admin/src/main/resources/application.yaml", "admin/src/main/resources/interview-followup-rule.yaml"}:
            suggestions.add("config")
        if file.startswith("admin/src/main/resources/workflow/"):
            suggestions.add("workflow")
        if file.endswith("BusinessAgentScene.java") or file == "admin/src/main/resources/application.yaml":
            suggestions.add("agent_scene")
    return sorted(suggestions)


def skill_diff_check(args: dict[str, Any]) -> str:
    mode = args.get("mode", "staged")
    base_ref = args.get("base_ref")
    include_diff_excerpt = bool(args.get("include_diff_excerpt", True))
    max_excerpt_chars = int(args.get("max_excerpt_chars", 6000))

    files = read_changed_files(mode, base_ref)
    diff_text = read_diff(mode, base_ref, max(max_excerpt_chars, 1))
    full_diff_for_scoring = read_diff(mode, base_ref, 200000)

    impacted: list[tuple[int, SkillRule, list[str]]] = []
    for skill in SKILLS:
        score, reasons = score_skill(skill, files, full_diff_for_scoring)
        if score > 0:
            impacted.append((score, skill, reasons))
    impacted.sort(key=lambda item: (-item[0], item[1].name))
    risk, risk_reasons = risk_level(files, full_diff_for_scoring, len(impacted))
    indexes = suggested_index_scripts(files)

    lines = [
        "# Skill Diff-Check Report",
        "",
        f"- Repository: `{ROOT}`",
        f"- Scope: `{base_ref + '...HEAD' if base_ref else mode}`",
        f"- Changed files: `{len(files)}`",
        f"- Risk: `{risk}`",
        "",
    ]

    if files:
        lines.extend(["## Changed Files", ""])
        lines.extend(f"- `{file}`" for file in files)
        lines.append("")
    else:
        lines.extend(["No changed files were found in this scope.", ""])

    lines.extend(["## Impacted Skills", ""])
    if impacted:
        lines.extend(["| Skill | Score | Why |", "| --- | ---: | --- |"])
        for score, skill, reasons in impacted:
            reason_text = "<br>".join(reasons) if reasons else "-"
            lines.append(f"| `{skill.name}` | {score} | {reason_text} |")
    else:
        lines.append("No Skill rule was matched. If the change is business-related, add or refine rules in `mcp/skill-guard/skill_guard_config.json`.")
    lines.append("")

    lines.extend(["## Risk Reasons", ""])
    lines.extend(f"- {reason}" for reason in risk_reasons)
    lines.append("")

    lines.extend(["## Suggested Actions", ""])
    if indexes:
        lines.append(f"- Regenerate generated references: `{', '.join(indexes)}`.")
    if risk == "high":
        lines.append("- Human review is required before updating Skill knowledge.")
    elif impacted:
        lines.append("- Review the impacted Skill files and update references if the diff changes a business rule or contract.")
    else:
        lines.append("- No automatic Skill update is suggested.")
    lines.append("- Do not silently rewrite business rules; write back to Skill only after review.")
    lines.append("")

    if include_diff_excerpt and diff_text:
        lines.extend(["## Diff Excerpt", "", "```diff", diff_text, "```", ""])

    return "\n".join(lines)


def regenerate_skill_indexes(args: dict[str, Any]) -> str:
    targets = args.get("targets") or ["all"]
    if isinstance(targets, str):
        targets = [targets]
    if "all" in targets:
        selected = sorted(INDEX_SCRIPTS)
    else:
        selected = [target for target in targets if target in INDEX_SCRIPTS]
    unknown = [target for target in targets if target not in INDEX_SCRIPTS and target != "all"]

    lines = ["# Regenerate Skill Indexes", ""]
    for target in selected:
        script = ROOT / INDEX_SCRIPTS[target]
        if not script.exists():
            lines.append(f"- `{target}`: missing script `{script.relative_to(ROOT).as_posix()}`")
            continue
        result = subprocess.run(
            [sys.executable, str(script)],
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if result.returncode == 0:
            lines.append(f"- `{target}`: ok")
        else:
            detail = (result.stderr or result.stdout or "unknown error").strip()
            lines.append(f"- `{target}`: failed\n\n```text\n{detail}\n```")

    for target in unknown:
        lines.append(f"- `{target}`: unknown target")
    if not selected and not unknown:
        lines.append("- No target selected.")
    return "\n".join(lines) + "\n"


PATH_IN_BACKTICKS_RE = re.compile(r"`([^`]+)`")


def skill_health_check(args: dict[str, Any]) -> str:
    max_findings = int(args.get("max_findings", 200))
    skill_root = ROOT / "skills"
    findings: list[str] = []
    checked = 0

    def skill_dir_for(markdown_path: Path) -> Path:
        relative = markdown_path.relative_to(skill_root)
        return skill_root / relative.parts[0]

    def anchor_exists(path: Path) -> bool:
        text = path.as_posix()
        if "*" in text or "?" in text:
            return bool(list(path.parent.glob(path.name)))
        return path.exists()

    for markdown in sorted(skill_root.rglob("*.md")):
        text = markdown.read_text(encoding="utf-8", errors="replace")
        base = skill_dir_for(markdown)
        for raw in PATH_IN_BACKTICKS_RE.findall(text):
            candidate = raw.strip()
            if not candidate or " " in candidate:
                continue
            if candidate.startswith(("http://", "https://", "$", "/", "#")):
                continue
            if not (
                candidate.startswith("admin/")
                or candidate.startswith("skills/")
                or candidate.startswith("references/")
                or candidate.startswith("scripts/")
            ):
                continue
            checked += 1
            path = ROOT / candidate if candidate.startswith(("admin/", "skills/")) else base / candidate
            if not anchor_exists(path):
                rel_md = markdown.relative_to(ROOT).as_posix()
                findings.append(f"- `{rel_md}` references missing path `{candidate}`")
                if len(findings) >= max_findings:
                    break
        if len(findings) >= max_findings:
            break

    lines = [
        "# Skill Health Check",
        "",
        f"- Checked path anchors: `{checked}`",
        f"- Missing anchors: `{len(findings)}`",
        "",
    ]
    if findings:
        lines.extend(["## Findings", ""])
        lines.extend(findings)
    else:
        lines.append("No missing path anchors were found.")
    return "\n".join(lines) + "\n"


def get_git_diff(args: dict[str, Any]) -> str:
    mode = args.get("mode", "staged")
    base_ref = args.get("base_ref")
    max_chars = int(args.get("max_chars", 20000))
    diff_text = read_diff(mode, base_ref, max_chars)
    if diff_text:
        return f"```diff\n{diff_text}\n```"
    return "No diff found for the requested scope."


TOOLS: dict[str, dict[str, Any]] = {
    "get_git_diff": {
        "description": "Return the current git diff for staged, unstaged, all, or base_ref...HEAD scope.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "mode": {"type": "string", "enum": ["staged", "unstaged", "all"], "default": "staged"},
                "base_ref": {"type": "string", "description": "Optional git ref. When set, uses base_ref...HEAD."},
                "max_chars": {"type": "integer", "default": 20000}
            }
        },
        "handler": get_git_diff,
    },
    "skill_diff_check": {
        "description": "Analyze a git diff and report which Skill modules may need updates.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "mode": {"type": "string", "enum": ["staged", "unstaged", "all"], "default": "staged"},
                "base_ref": {"type": "string", "description": "Optional git ref. When set, uses base_ref...HEAD."},
                "include_diff_excerpt": {"type": "boolean", "default": True},
                "max_excerpt_chars": {"type": "integer", "default": 6000}
            }
        },
        "handler": skill_diff_check,
    },
    "regenerate_skill_indexes": {
        "description": "Run deterministic scripts that regenerate generated Skill reference indexes.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "targets": {
                    "type": "array",
                    "items": {"type": "string", "enum": ["all", "api", "config", "workflow", "agent_scene"]},
                    "default": ["all"]
                }
            }
        },
        "handler": regenerate_skill_indexes,
    },
    "skill_health_check": {
        "description": "Scan Skill markdown files for missing code/reference path anchors.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "max_findings": {"type": "integer", "default": 200}
            }
        },
        "handler": skill_health_check,
    },
}


def tool_list() -> list[dict[str, Any]]:
    return [
        {
            "name": name,
            "description": item["description"],
            "inputSchema": item["inputSchema"],
        }
        for name, item in TOOLS.items()
    ]


def result_content(text: str) -> dict[str, Any]:
    return {"content": [{"type": "text", "text": text}]}


def handle_request(message: dict[str, Any]) -> dict[str, Any] | None:
    method = message.get("method")
    msg_id = message.get("id")

    if msg_id is None:
        return None

    try:
        if method == "initialize":
            return {
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                },
            }
        if method == "tools/list":
            return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": tool_list()}}
        if method == "tools/call":
            params = message.get("params") or {}
            name = params.get("name")
            arguments = params.get("arguments") or {}
            tool = TOOLS.get(name)
            if not tool:
                raise ValueError(f"unknown tool: {name}")
            text = tool["handler"](arguments)
            return {"jsonrpc": "2.0", "id": msg_id, "result": result_content(text)}
        raise ValueError(f"unknown method: {method}")
    except Exception as exc:  # MCP clients expect errors to stay in JSON-RPC.
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "error": {"code": -32000, "message": str(exc)},
        }


def main() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError as exc:
            response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": str(exc)}}
        else:
            response = handle_request(message)
        if response is not None:
            sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
