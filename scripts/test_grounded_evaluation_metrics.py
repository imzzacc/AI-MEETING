import importlib.util
from pathlib import Path
import unittest


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


runner = module('evaluate-grounded-workflow')
summary = module('summarize-grounded-evaluation')


class EvaluationMetricsTest(unittest.TestCase):
    def setUp(self):
        self.case = {'id': 'one', 'topicId': 'topic', 'answer': 'actual', 'expectedStates': {'a': ['NOT_OBSERVED'], 'b': ['INCORRECT']}}
        self.dataset = {'cases': [self.case]}

    def test_empty_observations_are_missing_not_successful_abstentions(self):
        result = summary.summarize(self.dataset, {'results': [{'id': 'one', 'evaluation': {'observations': []}}]})
        self.assertEqual(2, result['missingTargetRubrics'])
        self.assertEqual(0, result['matchingTargetRubrics'])
        self.assertEqual(1, result['errorClassification']['falseNegative'])

    def test_every_rubric_and_duplicate_is_counted(self):
        observations = [{'knowledgePointId': 'topic', 'rubricPointId': 'a', 'state': 'NOT_OBSERVED'},
                        {'knowledgePointId': 'topic', 'rubricPointId': 'b', 'state': 'COVERED'},
                        {'knowledgePointId': 'topic', 'rubricPointId': 'b', 'state': 'INCORRECT'}]
        result = summary.summarize(self.dataset, {'results': [{'id': 'one', 'evaluation': {'observations': observations}}]})
        self.assertEqual(1, result['matchingTargetRubrics'])
        self.assertEqual(1, result['duplicateTargetRubrics'])
        self.assertEqual('DUPLICATE', result['failures'][0]['actual']['b'])

    def test_false_positive_and_missing_error_denominators(self):
        row = {'id': 'one', 'evaluation': {'observations': [{'knowledgePointId': 'topic', 'rubricPointId': 'a', 'state': 'INCORRECT'}]}}
        result = summary.summarize(self.dataset, {'results': [row]})['errorClassification']
        self.assertEqual({'truePositive': 0, 'falsePositive': 1, 'falseNegative': 1, 'precision': 0, 'recall': 0}, result)

    def test_no_error_predictions_is_undefined_precision(self):
        result = summary.summarize(self.dataset, {'results': []})
        self.assertIsNone(result['errorClassification']['precision'])
        self.assertEqual(0, result['completedCases'])

    def test_duplicate_does_not_overwrite_a_failed_grounding_check(self):
        observation = {'knowledgePointId': 'topic', 'rubricPointId': 'a', 'state': 'COVERED',
                       'answerQuotes': ['actual'], 'sourceChunkIds': ['s'], 'rationale': 'text'}
        result = {'analysisSchemaVersion': '1', 'score': 80, 'feedback': 'text', 'observations': [observation.copy(), observation.copy(), observation.copy()]}
        checks = runner.assess(result, self.case, {'id': 'topic', 'rubricPoints': ['a', 'b'], 'sources': [{'id': 's'}]})
        self.assertFalse(checks['grounded_a'])
        self.assertFalse(checks['state_a'])


if __name__ == '__main__':
    unittest.main()
