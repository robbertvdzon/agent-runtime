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

    def exercise(self, answers):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'response-schema.json').write_text(json.dumps(SCHEMA))
            calls = []
            def start(command, **kwargs):
                index = len(calls)
                calls.append(kwargs['env'])
                (root / 'result.json').write_text(answers[min(index, len(answers) - 1)])
                class Process:
                    stdout = io.StringIO(json.dumps({'type': 'turn.completed', 'usage': {'input_tokens': 100, 'cached_input_tokens': 10, 'output_tokens': 20}}) + '\n')
                    def wait(self): return 0
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
        self.assertIn('failed JSON/schema validation', calls[1]['AR_OLLAMA_CORRECTION'])
        self.assertEqual(events[-1]['usage'], {'input_tokens': 200, 'cached_input_tokens': 20, 'output_tokens': 40})

    def test_invalid_output_stops_after_three_repairs_without_a_success_result(self):
        code, result, calls, events = self.exercise(['{}'])
        self.assertEqual(code, 65)
        self.assertIsNone(result)
        self.assertEqual(len(calls), 4)
        self.assertEqual(events[-1]['usage']['input_tokens'], 400)


if __name__ == '__main__':
    unittest.main()
