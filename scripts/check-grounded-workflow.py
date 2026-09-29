"""Probe the published workflow without printing credentials or provider output.

Default: submit no model input. --generate: one synthetic contract smoke call,
only after confirming free quota or an authorized test budget. No retries.
This is a provider contract check, not application E2E or a quality evaluation.
"""

import argparse
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request
import uuid


def read_config(path):
    config = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            config[key.strip()] = value.strip().strip('"').strip("'")
    for key in ("API_FLOW_ID", "API_KEY", "API_SECRET"):
        if not config.get(key):
            raise ValueError("Missing field: " + key)
    return config


def sample_input():
    catalog = json.loads((Path(__file__).resolve().parents[1]
                          / "admin/src/main/resources/knowledge/interview-catalog-v1.json")
                         .read_text(encoding="utf-8"))
    topic = next(t for t in catalog["topics"] if t["id"] == "java.volatile")
    return {
        "question": "Can volatile make a shared counter's i++ atomic? Explain why.",
        "answer": "Yes. volatile makes i++ atomic, so concurrent increments never lose updates.",
        "rubrics": [{"knowledgePointId": topic["id"], "rubricPointIds": topic["rubricPoints"]}],
        "sources": topic["sources"],
    }


def check_contract(response, sample):
    content = response
    if "choices" in response:
        choice = response["choices"][0]
        content = choice.get("message", choice.get("delta", {})).get("content")
    elif "content" in response:
        content = response["content"]
    if isinstance(content, str):
        content = json.loads(content)
    checks = {
        "schema": str(content.get("analysisSchemaVersion")) == "1",
        "score": type(content.get("score")) is int and 0 <= content["score"] <= 100,
        "feedback": isinstance(content.get("feedback"), str) and bool(content["feedback"]),
        "no_actions": not any(k in content for k in (
            "next_question", "follow_up_question", "follow_up_needed", "action", "duration")),
    }
    observations = content.get("observations")
    checks["observations"] = isinstance(observations, list) and bool(observations)
    grounded = True
    incorrect = False
    for observation in observations if isinstance(observations, list) else []:
        quotes = observation.get("answerQuotes")
        sources = observation.get("sourceChunkIds")
        state = observation.get("state")
        grounded &= (observation.get("knowledgePointId") == "java.volatile"
                     and observation.get("rubricPointId") == "atomicity"
                     and state in {"COVERED", "PARTIAL", "INCORRECT", "NOT_OBSERVED", "UNCERTAIN"}
                     and isinstance(quotes, list) and bool(quotes)
                     and all(isinstance(q, str) and bool(q) and q in sample["answer"] for q in quotes)
                     and isinstance(sources, list) and bool(sources)
                     and all(s in {r["id"] for r in sample["sources"]} for s in sources))
        incorrect |= state == "INCORRECT"
    checks["grounded_references"] = bool(grounded and checks["observations"])
    checks["identifies_explicit_error"] = incorrect
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--generate", action="store_true")
    args = parser.parse_args()
    config = read_config(args.config)
    sample = sample_input()
    parameters = {} if not args.generate else {
        "AGENT_USER_INPUT": json.dumps(sample, ensure_ascii=False),
        "question": sample["question"], "resume_context": "",
    }
    request = urllib.request.Request(
        "https://xingchen-api.xf-yun.com/workflow/v1/chat/completions",
        data=json.dumps({"flow_id": config["API_FLOW_ID"], "parameters": parameters,
                         "stream": False, "uid": "adaptive-contract-check",
                         "chat_id": str(uuid.uuid4()), "history": []}).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + config["API_KEY"] + ":" + config["API_SECRET"]},
    )
    with urllib.request.urlopen(request, timeout=65) as response:
        data = json.loads(response.read(1_000_000))
    code = data.get("code")
    # Never emit raw errors, model output, request bodies or authentication headers.
    result = {"generation_requested": args.generate,
              "provider_code": code if type(code) is int else None}
    usage = data.get("usage")
    if isinstance(usage, dict):
        result["usage"] = {k: v for k, v in usage.items()
                           if k in {"prompt_tokens", "completion_tokens", "total_tokens"}
                           and type(v) is int}
    if not args.generate:
        result["workflow_input_validation_reached"] = (
            code == 22500 and "AGENT_USER_INPUT" in str(data.get("message", "")))
        passed = result["workflow_input_validation_reached"]
    elif code not in (None, 0):
        message = str(data.get("message", ""))
        for value in config.values():
            if value:
                message = message.replace(value, "[REDACTED]")
        result["provider_message"] = message[:500]
        passed = False
    else:
        result["contract_checks"] = check_contract(data, sample)
        passed = all(result["contract_checks"].values())
    result["passed"] = passed
    print(json.dumps(result, ensure_ascii=True))
    return 0 if passed else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(json.dumps({"passed": False, "error_type": type(error).__name__}))
        sys.exit(1)
