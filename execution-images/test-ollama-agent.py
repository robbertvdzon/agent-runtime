#!/usr/bin/env python3
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ollama_agent', Path(__file__).with_name('ollama-agent.py'))
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)
SCHEMA = {'type': 'object', 'required': ['plaats'], 'additionalProperties': False, 'properties': {'plaats': {'type': 'string'}}}


class OllamaAgentTest(unittest.TestCase):
    def test_fenced_result_is_normalized_but_prose_and_invalid_schema_are_rejected(self):
        self.assertEqual(agent.validate('```json\n{"plaats":"Heemskerk"}\n```', SCHEMA), {'plaats': 'Heemskerk'})
        for candidate in ('prose {"plaats":"Heemskerk"}', '{}', '{"plaats":123}'):
            with self.assertRaises(ValueError):
                agent.validate(candidate, SCHEMA)

    def exercise(self, answers, exit_code=0, extra_events=(), completed=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'response-schema.json').write_text(json.dumps(SCHEMA))
            calls = []
            def start(command, **kwargs):
                index = len(calls)
                env = kwargs['env']
                calls.append(dict(env))
                if 'AR_OLLAMA_CORRECTION_FILE' in env:
                    calls[-1]['correction_text'] = Path(env['AR_OLLAMA_CORRECTION_FILE']).read_text()
                answer = answers[min(index, len(answers) - 1)]
                if answer is not None:
                    (root / 'result.json').write_text(answer)
                class Process:
                    stdout = io.StringIO((json.dumps({'type': 'turn.completed', 'usage': {'input_tokens': 100, 'cached_input_tokens': 10, 'output_tokens': 20}}) + '\n' if completed else '') + ''.join(json.dumps(e) + '\n' for e in extra_events))
                    def wait(self): return exit_code
                return Process()
            stream = io.StringIO()
            with patch.object(agent.subprocess, 'Popen', side_effect=start), patch.object(agent.signal, 'signal'), patch.dict(agent.os.environ, {}, clear=True), contextlib.redirect_stdout(stream):
                code = agent.run(root, root)
            result = json.loads((root / 'result.json').read_text()) if (root / 'result.json').exists() else None
            return code, result, calls, [json.loads(line) for line in stream.getvalue().splitlines()]

    def test_schema_repair_keeps_all_usage_and_does_not_constrain_tool_turns(self):
        code, result, calls, events = self.exercise(['{}', '```json\n{"plaats":"Heemskerk"}\n```'])
        self.assertEqual(code, 0)
        self.assertEqual(result, {'plaats': 'Heemskerk'})
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0]['AR_ENGINE'], 'OLLAMA_CODEX')
        self.assertIn("'plaats' is a required property", calls[1]['correction_text'])
        self.assertIn('Previous candidate (JSON):\n{}', calls[1]['correction_text'])
        self.assertNotIn('AR_OLLAMA_CORRECTION_FILE', calls[0])
        self.assertEqual(events[-1]['usage'], {'input_tokens': 200, 'cached_input_tokens': 20, 'output_tokens': 40})

    def test_invalid_output_stops_after_three_repairs_without_a_success_result(self):
        code, result, calls, events = self.exercise(['{}'])
        self.assertEqual(code, 65)
        self.assertIsNone(result)
        self.assertEqual(len(calls), 4)
        self.assertEqual(events[-1]['usage']['input_tokens'], 400)

    def test_prose_or_missing_output_restarts_without_recycling_error_text(self):
        for bad in ('Backend error: unavailable', None, 'null'):
            code, result, calls, _ = self.exercise([bad, '{"plaats":"Heemskerk"}'])
            self.assertEqual(code, 0)
            self.assertEqual(len(calls), 2)
            self.assertNotIn('AR_OLLAMA_CORRECTION_FILE', calls[1])

    def test_repeated_unparseable_output_stops_after_one_fresh_retry(self):
        code, result, calls, _ = self.exercise(['not JSON'])
        self.assertEqual(code, 65)
        self.assertIsNone(result)
        self.assertEqual(len(calls), 2)

    def test_failed_execution_never_repairs_even_when_result_contains_valid_json(self):
        for exit_code, events in ((1, ()), (0, ({'type':'turn.failed', 'error': {'message':'context window exceeded'}},))):
            code, result, calls, _ = self.exercise(['{"plaats":"Heemskerk"}'], exit_code, events)
            self.assertEqual(code, 65)
            self.assertIsNone(result)
            self.assertEqual(len(calls), 1)

    def test_json_error_envelope_is_not_offered_for_schema_repair(self):
        code, result, calls, _ = self.exercise(['{"error":"connection refused"}'])
        self.assertEqual(code, 65)
        self.assertIsNone(result)
        self.assertEqual(len(calls), 1)

    def test_exit_zero_without_completion_event_is_not_a_completed_answer(self):
        code, result, calls, _ = self.exercise(['{"plaats":"Heemskerk"}'], completed=False)
        self.assertEqual(code, 65)
        self.assertIsNone(result)
        self.assertEqual(len(calls), 1)

    def test_repair_preserves_previous_content_and_reports_actual_constraint(self):
        code, result, calls, _ = self.exercise(['{"plaats":123}', '{"plaats":"Heemskerk"}'])
        self.assertEqual(code, 0)
        self.assertIn('"plaats": 123', calls[1]['correction_text'])
        self.assertIn('constraint type="string"', calls[1]['correction_text'])

    def test_recovered_nonterminal_warning_does_not_discard_completed_answer(self):
        code, result, calls, _ = self.exercise(['{"plaats":"Heemskerk"}'], extra_events=({'type':'error', 'message':'Reconnecting 1/5'},))
        self.assertEqual(code, 0)
        self.assertEqual(result, {'plaats':'Heemskerk'})
        self.assertEqual(len(calls), 1)

    def test_failure_categories_distinguish_context_from_temporary_connection_failure(self):
        self.assertEqual(agent.failure_code('context length exceeded'), 'SESSION_TOKEN_LIMIT')
        self.assertEqual(agent.failure_code('connection refused'), 'PROVIDER_UNAVAILABLE')
        self.assertEqual(agent.failure_code('unexpected failure'), 'ENGINE_FAILED')


if __name__ == '__main__':
    unittest.main()
