"""Bounded live evaluation of synthetic fixtures; never a substitute for human review.

Without --generate only validate the dataset. Each live case has a fresh conversation,
no retry, and a durable result before the next call. Credentials stay outside the repo.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('probe', Path(__file__).with_name('check-grounded-workflow.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
STATES = {'COVERED', 'PARTIAL', 'INCORRECT', 'NOT_OBSERVED', 'UNCERTAIN'}


def unpack(data):
    content = data
    if 'choices' in data:
        choice = data['choices'][0]
        content = choice.get('message', choice.get('delta', {})).get('content')
    if isinstance(content, str):
        content = json.loads(content)
    if isinstance(content, dict) and 'result' in content:
        content = content['result']
        if isinstance(content, str):
            content = json.loads(content)
    return content


def assess(result, case, topic):
    observations = result.get('observations', [])
    checks = {
        'schema': str(result.get('analysisSchemaVersion')) == '1',
        'score': type(result.get('score')) is int and 0 <= result['score'] <= 100,
        'feedback': isinstance(result.get('feedback'), str) and bool(result['feedback'].strip()),
        'no_actions': not any(k in result for k in ('action', 'duration', 'next_question', 'follow_up_question', 'follow_up_needed')),
        'observations': isinstance(observations, list) and bool(observations),
    }
    if not checks['observations']:
        return checks
    permitted = {s['id'] for s in topic['sources']}
    seen = set()
    for o in observations:
        key = o.get('rubricPointId')
        valid = (o.get('knowledgePointId') == topic['id'] and key in topic['rubricPoints']
                 and o.get('state') in STATES and key not in seen
                 and isinstance(o.get('rationale'), str))
        seen.add(key)
        quotes, sources = o.get('answerQuotes'), o.get('sourceChunkIds')
        valid = valid and isinstance(quotes, list) and isinstance(sources, list)
        if valid:
            valid = (all(isinstance(q, str) and q and q in case['answer'] for q in quotes)
                     and all(s in permitted for s in sources))
            if o['state'] in {'COVERED', 'PARTIAL', 'INCORRECT'}:
                valid = valid and bool(quotes) and bool(sources)
        check_key = 'grounded_' + str(key)
        checks[check_key] = checks.get(check_key, True) and bool(valid)
    for key, states in case['expectedStates'].items():
        matches = [o for o in observations if o.get('rubricPointId') == key]
        checks['state_' + key] = len(matches) == 1 and matches[0].get('state') in states
    checks['score_range'] = case.get('minScore', 0) <= result.get('score', -1) <= case.get('maxScore', 100)
    return checks


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--dataset', type=Path, required=True)
    p.add_argument('--config', type=Path)
    p.add_argument('--output', type=Path)
    p.add_argument('--generate', action='store_true')
    p.add_argument('--max-calls', type=int, default=6)
    args = p.parse_args()
    data = json.loads(args.dataset.read_text(encoding='utf-8'))
    catalog = json.loads((ROOT / 'admin/src/main/resources/knowledge/interview-catalog-v1.json').read_text(encoding='utf-8'))
    topics = {t['id']: t for t in catalog['topics']}
    cases = data['cases']
    assert len({c['id'] for c in cases}) == len(cases)
    for c in cases:
        t = topics[c['topicId']]
        assert c['question'].strip() and c['answer'].strip()
        assert set(c['expectedStates']) <= set(t['rubricPoints'])
        assert all(states and set(states) <= STATES for states in c['expectedStates'].values())
    print(json.dumps({'cases': len(cases), 'humanReviewed': data.get('humanReviewed') is True}), flush=True)
    if not args.generate:
        return 0
    if not args.config or not args.output or not 0 < len(cases) <= args.max_calls <= 60:
        p.error('Live evaluation requires config, a new output path and an explicit bounded call limit')
    if args.output.exists():
        p.error('Output exists; refusing accidental repeat calls or evidence overwrite')
    config = probe.read_config(args.config)
    report = {'humanReviewed': data.get('humanReviewed') is True, 'dataset': args.dataset.name,
              'datasetSha256': hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
              'catalogSha256': hashlib.sha256((ROOT / 'admin/src/main/resources/knowledge/interview-catalog-v1.json').read_bytes()).hexdigest(),
              'localPromptSha256': hashlib.sha256((ROOT / 'docs/grounded-evaluator-system-prompt.txt').read_bytes()).hexdigest(),
              'promptHashLimit': 'Local template hash, not an attestation of the hosted workflow contents.',
              'catalogVersion': catalog['version'], 'totalTokens': 0, 'results': []}
    # Reserve output before the first paid request; never retry uncertain network outcomes.
    args.output.write_text(json.dumps(report), encoding='utf-8')
    for c in cases:
        topic = topics[c['topicId']]
        payload = {'question': c['question'], 'answer': c['answer'],
                   'rubrics': [{'knowledgePointId': topic['id'], 'rubricPointIds': topic['rubricPoints']}],
                   'sources': topic['sources']}
        request = urllib.request.Request('https://xingchen-api.xf-yun.com/workflow/v1/chat/completions',
            data=json.dumps({'flow_id': config['API_FLOW_ID'], 'stream': False,
                'uid': 'adaptive-synthetic-eval', 'chat_id': str(uuid.uuid4()), 'history': [],
                'parameters': {'AGENT_USER_INPUT': json.dumps(payload, ensure_ascii=False),
                               'question': c['question'], 'resume_context': ''}}).encode('utf-8'),
            headers={'Content-Type': 'application/json',
                     'Authorization': 'Bearer ' + config['API_KEY'] + ':' + config['API_SECRET']})
        record = {'id': c['id'], 'passed': False}
        stop = False
        try:
            with urllib.request.urlopen(request, timeout=65) as response:
                raw = json.loads(response.read(1_000_000))
            record['providerCode'] = raw.get('code')
            record['usage'] = {k: v for k, v in raw.get('usage', {}).items()
                               if k in {'prompt_tokens', 'completion_tokens', 'total_tokens'} and type(v) is int}
            report['totalTokens'] += record['usage'].get('total_tokens', 0)
            if raw.get('code') not in (None, 0):
                stop = True
            else:
                record['evaluation'] = unpack(raw)
                record['checks'] = assess(record['evaluation'], c, topic)
                record['passed'] = all(record['checks'].values())
        except Exception as e:
            record['errorType'] = type(e).__name__
            stop = True
        report['results'].append(record)
        safe = json.dumps(report, ensure_ascii=False, indent=2)
        for value in config.values():
            if value:
                safe = safe.replace(value, '[REDACTED]')
        args.output.write_text(safe, encoding='utf-8')
        print(json.dumps({k: v for k, v in record.items() if k != 'evaluation'}), flush=True)
        if stop:
            break
    passed = len(report['results']) == len(cases) and all(r['passed'] for r in report['results'])
    print(json.dumps({'passed': passed, 'totalTokens': report['totalTokens'],
                      'humanReviewed': report['humanReviewed']}))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
