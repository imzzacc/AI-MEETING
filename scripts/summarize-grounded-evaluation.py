"""Offline, strict summary of predeclared labels. Never infer human review or silent abstentions."""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path


def summarize(dataset, report):
    cases = {c['id']: c for c in dataset['cases']}
    if len(cases) != len(dataset['cases']):
        raise ValueError('Duplicate case IDs')
    results = {r['id']: r for r in report['results']}
    if len(results) != len(report['results']) or not set(results) <= set(cases):
        raise ValueError('Duplicate or unknown result IDs')
    matrix = Counter()
    tp = fp = fn = uncertain = missing = duplicates = matched = targets = 0
    details = []
    for key, case in cases.items():
        result = results.get(key, {})
        observations = (result.get('evaluation') or {}).get('observations', [])
        if not isinstance(observations, list):
            observations = []
        failures = [k for k, v in result.get('checks', {}).items() if not v]
        states = {}
        for rubric, expected in case['expectedStates'].items():
            targets += 1
            found = [o for o in observations if isinstance(o, dict)
                     and o.get('knowledgePointId') == case['topicId'] and o.get('rubricPointId') == rubric]
            # Absent evidence is not a successful NOT_OBSERVED classification.
            actual = 'MISSING' if not found else 'DUPLICATE' if len(found) > 1 else found[0].get('state', 'INVALID')
            states[rubric] = actual
            matrix['|'.join(sorted(expected)) + ' -> ' + actual] += 1
            matched += actual in expected
            missing += actual == 'MISSING'
            duplicates += actual == 'DUPLICATE'
            uncertain += actual == 'UNCERTAIN'
            # Do not let a duplicate/invalid prediction disappear from error precision.
            predicted_error = any(o.get('state') == 'INCORRECT' for o in found)
            gold_error = expected == ['INCORRECT']
            if predicted_error and gold_error: tp += 1
            if predicted_error and not gold_error: fp += 1
            if gold_error and not predicted_error: fn += 1
        if not result.get('passed') or any(states[r] not in ex for r, ex in case['expectedStates'].items()):
            details.append({'id': key, 'expected': case['expectedStates'], 'actual': states, 'failedChecks': failures,
                            'providerReturned': key in results})
    return {'humanReviewed': dataset.get('humanReviewed') is True,
            'labelAuthority': dataset.get('reviewerType', 'unspecified'),
            'caseCount': len(cases), 'completedCases': len(results),
            'strictPassedCases': sum(r.get('passed') is True for r in results.values()),
            'targetRubrics': targets, 'matchingTargetRubrics': matched,
            'missingTargetRubrics': missing, 'duplicateTargetRubrics': duplicates,
            'uncertainTargetRubrics': uncertain,
            'errorClassification': {'truePositive': tp, 'falsePositive': fp, 'falseNegative': fn,
                                    'precision': tp / (tp + fp) if tp + fp else None,
                                    'recall': tp / (tp + fn) if tp + fn else None},
            'confusion': dict(sorted(matrix.items())), 'failures': details,
            'semanticCitationSupportRate': None,
            'limitations': ['Metrics are relative to the declared labels and do not independently establish their correctness.',
                           'Error metrics are target-rubric classification metrics, not end-to-end mistake-card precision.',
                           'Structural quote/source validation cannot determine semantic citation support.'],
            'totalTokens': report.get('totalTokens')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    dataset = json.loads(args.dataset.read_text(encoding='utf-8'))
    report = json.loads(args.results.read_text(encoding='utf-8'))
    digest = hashlib.sha256(args.dataset.read_bytes()).hexdigest()
    if report.get('datasetSha256') != digest:
        raise ValueError('Missing or mismatched dataset hash; do not summarize changed labels as the original evaluation')
    summary = summarize(dataset, report)
    summary['datasetSha256'] = digest
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(summary, output, ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in summary.items() if k not in {'confusion', 'failures', 'limitations'}}))


if __name__ == '__main__':
    main()
