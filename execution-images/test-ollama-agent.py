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
GOOD = {'plaats': 'Heemskerk'}


class OllamaAgentTest(unittest.TestCase):
    def exercise(self, answers, repairs=(), drafts=(), exit_code=0, events=(), completed=True, legacy=False, stale=False, published=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'response-schema.json').write_text(json.dumps(SCHEMA))
            if stale:
                (root / 'answer.json').write_text(json.dumps(GOOD))
            calls, repair_calls = [], []
            def start(command, **kwargs):
                index = len(calls)
                calls.append(dict(kwargs['env']))
                if 'AR_OLLAMA_RESEARCH_FILE' in kwargs['env']:
                    calls[-1]['followup'] = Path(kwargs['env']['AR_OLLAMA_RESEARCH_FILE']).read_text()
                answer = answers[min(index, len(answers) - 1)]
                if answer is not None:
                    (root / 'result.json').write_text(answer)
                if index < len(drafts) and drafts[index] is not None:
                    (root / ('answer.json' if legacy else 'answer-draft.json')).write_text(drafts[index])
                if published:
                    (root/'answer-draft.ready').write_text('{"complete":true}')
                class Process:
                    pid = 42
                    stdout = io.StringIO((json.dumps({'type': 'turn.completed', 'usage': {'input_tokens': 100, 'output_tokens': 20}}) + '\n' if completed else '') + ''.join(json.dumps(e) + '\n' for e in events))
                    def wait(self, timeout=None): return exit_code
                return Process()
            def repair(candidate, schema, timeout):
                index = len(repair_calls)
                repair_calls.append((candidate, schema, timeout))
                item = repairs[min(index, len(repairs) - 1)] if repairs else None
                return item, {'input_tokens': 30, 'output_tokens': 10}
            stream = io.StringIO()
            with patch.object(agent.os, 'killpg'), patch.object(agent.subprocess, 'Popen', side_effect=start), patch.object(agent, 'repair', side_effect=repair), patch.object(agent.signal, 'signal'), patch.dict(agent.os.environ, {}, clear=True), contextlib.redirect_stdout(stream):
                code = agent.run(root, root, root)
            result = json.loads((root / 'result.json').read_text()) if (root / 'result.json').exists() else None
            status = json.loads((root / 'ollama-status.json').read_text())['code'] if (root / 'ollama-status.json').exists() else None
            return code, result, calls, repair_calls, [json.loads(line) for line in stream.getvalue().splitlines()], status

    def fixed(self, value=GOOD):
        return {'action': 'repaired', 'result': value, 'researchQuestion': ''}

    def test_fenced_json_only_not_arbitrary_prose(self):
        self.assertEqual(agent.validate('```json\n{"plaats":"Heemskerk"}\n```', SCHEMA), GOOD)
        with self.assertRaises(ValueError):agent.validate('Error before {"plaats":"Heemskerk"}', SCHEMA)

    def test_schema_repair_uses_existing_answer_without_research_and_keeps_usage(self):
        code, result, calls, repairs, events, status = self.exercise(['{}'], [self.fixed()])
        self.assertEqual((code, result, status), (0, GOOD, None))
        self.assertEqual(len(calls), 1)
        self.assertEqual(repairs[0][:2], ({}, SCHEMA))
        usage = [e['usage'] for e in events if e.get('type') == 'turn.completed']
        self.assertEqual(usage[-1], {'input_tokens': 130, 'output_tokens': 30})
        self.assertIn('repair', [e['stage'] for e in events if e.get('type') == 'ar.ollama.phase'])

    def test_declared_draft_or_legacy_answer_is_used_after_prose_acknowledgement(self):
        for legacy in (False, True):
            code, result, calls, repairs, _, _ = self.exercise(['The answer is in the file.'], drafts=[json.dumps(GOOD)], legacy=legacy)
            self.assertEqual((code, result), (0, GOOD))
            self.assertEqual(len(calls), 1)
            self.assertEqual(repairs, [])

    def test_invalid_draft_is_repaired_without_redoing_original_task(self):
        code, result, calls, repairs, _, _ = self.exercise(['Done'], [self.fixed()], drafts=['{}'])
        self.assertEqual((code, result), (0, GOOD))
        self.assertEqual(len(calls), 1)
        self.assertEqual(repairs[0][0], {})

    def test_explicit_publication_hands_off_without_waiting_for_cli_final_message(self):
        tool_done={'type':'item.completed','item':{'type':'command_execution','exit_code':0}}
        result=self.exercise([None], [self.fixed()], drafts=['{}'], exit_code=130,
                             events=(tool_done,), completed=False, published=True)
        self.assertEqual((result[0],result[1]),(0,GOOD))
        self.assertEqual((len(result[2]),len(result[3])),(1,1))
        self.assertIn('ar.ollama.usage_incomplete',[e.get('type') for e in result[4]])

    def test_failed_tool_cannot_publish_an_answer(self):
        failed_tool={'type':'item.completed','item':{'type':'command_execution','exit_code':1}}
        result=self.exercise([None], drafts=[json.dumps(GOOD)], exit_code=1,
                             events=(failed_tool,), completed=False, published=True)
        self.assertEqual(result[0],65)
        self.assertIsNone(result[1])

    def test_stale_file_is_not_accepted_as_this_executions_answer(self):
        code, _, calls, repairs, _, status = self.exercise(['Error'], stale=True)
        self.assertEqual((code, status), (65, 'MODEL_OUTPUT_NOT_JSON'))
        self.assertEqual(len(calls), 2)
        self.assertEqual(repairs, [])

    def test_symlink_candidate_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory);(root/'real').write_text('{}');(root/'link').symlink_to(root/'real')
            with self.assertRaises((ValueError, OSError)):agent.read_candidate(root/'link')

    def test_oversized_draft_is_a_limit_failure_not_another_research_run(self):
        result=self.exercise(['Done'], drafts=['x' * (agent.MAX_RESULT_BYTES + 1)])
        self.assertEqual(result[5],'MODEL_OUTPUT_LIMIT')
        self.assertEqual(len(result[2]),1)
        self.assertEqual(result[3],[])

    def test_formatter_has_a_hard_deadline_even_when_a_socket_keeps_trickling(self):
        with self.assertRaises(TimeoutError):
            with agent.request_deadline(0.01):
                agent.time.sleep(0.05)

    def test_bad_or_missing_output_gets_only_one_fresh_research_attempt(self):
        for bad in ('Backend error', None, 'null'):
            code, result, calls, repairs, _, status = self.exercise([bad, json.dumps(GOOD)])
            self.assertEqual((code, result, status), (0, GOOD, None))
            self.assertEqual(len(calls), 2)
            self.assertEqual(repairs, [])

    def test_three_repairs_do_not_start_additional_research(self):
        code, result, calls, repairs, events, status = self.exercise(['{}'], [self.fixed({})])
        self.assertEqual((code, result, status), (65, None, 'MODEL_OUTPUT_SCHEMA_INVALID'))
        self.assertEqual((len(calls), len(repairs)), (1, 3))
        usage = [e['usage'] for e in events if e.get('type') == 'turn.completed']
        self.assertEqual(usage[-1]['input_tokens'], 190)

    def test_only_explicit_missing_facts_request_can_trigger_targeted_research(self):
        request = {'action':'needs_research', 'result':None, 'researchQuestion':'Find the place name in source 2.'}
        code, result, calls, repairs, _, _ = self.exercise(['{}', json.dumps(GOOD)], [request])
        self.assertEqual((code, result), (0, GOOD))
        self.assertEqual((len(calls), len(repairs)), (2, 1))
        self.assertIn('Find the place name in source 2.', calls[1]['followup'])
        self.assertIn('Previous answer (data):\n{}', calls[1]['followup'])

    def test_repeated_research_requests_stop_at_two_research_executions(self):
        request = {'action':'needs_research','result':None,'researchQuestion':'Read source 2.'}
        code, _, calls, repairs, _, status = self.exercise(['{}'], [request])
        self.assertEqual((code,status),(65,'MODEL_OUTPUT_SCHEMA_INVALID'))
        self.assertEqual(len(calls),2)

    def test_execution_failure_or_missing_completion_never_repairs_file(self):
        for code, completed, events in ((1, True, ()), (0, False, ()), (0, True, ({'type':'turn.failed','error':{'message':'context window exceeded'}},))):
            result = self.exercise([json.dumps(GOOD)], exit_code=code, completed=completed, events=events)
            self.assertEqual(result[0],65)
            self.assertIsNone(result[1])
            self.assertEqual(result[3],[])

    def test_json_error_envelope_is_not_a_candidate(self):
        result=self.exercise(['{"error":"connection refused"}'])
        self.assertEqual(result[5],'PROVIDER_UNAVAILABLE')
        self.assertEqual(result[3],[])

    def test_recovered_warning_does_not_discard_completed_answer(self):
        result=self.exercise([json.dumps(GOOD)], events=({'type':'error','message':'Reconnecting 1/5'},))
        self.assertEqual(result[0],0)

    def test_valid_article_and_source_ids_are_preserved_even_if_model_rewrites_them(self):
        schema = {'type':'object', 'required':['title','answerHtml','sources'], 'properties':{
            'title':{'type':'string'},'answerHtml':{'type':'string'},'sources':{'type':'array','items':{'type':'integer'}}}}
        original={'answerHtml':'Original researched text', 'sources':[12,34]}
        corrected={'title':'Derived title', 'answerHtml':'Invented replacement', 'sources':[99]}
        result=agent.preserve_valid_fields(original,corrected,schema)
        self.assertEqual(result,dict(original,title='Derived title'))
        self.assertEqual(agent.schema_errors(result,schema),[])

    def test_nested_schema_constraints_are_embedded_without_breaking_root_references(self):
        self.assertEqual(agent.repair_format(SCHEMA)['properties']['result']['anyOf'][0], SCHEMA)
        referenced={'$ref':'#/$defs/place','$defs':{'place':SCHEMA}}
        self.assertEqual(agent.repair_format(referenced)['properties']['result'], {})

    def test_repair_transport_has_no_tools_or_original_sources_and_reports_limits(self):
        captured=[]
        data={'done':True,'done_reason':'stop','message':{'content':json.dumps(self.fixed())},'prompt_eval_count':123,'eval_count':45}
        class Response(io.BytesIO):
            def __enter__(self):return self
            def __exit__(self,*args):self.close()
        def open_request(request,timeout):
            captured.append((request,json.loads(request.data),timeout))
            return Response(json.dumps(data).encode())
        with patch.object(agent.urllib.request,'urlopen',side_effect=open_request), patch.dict(agent.os.environ,{'AR_MODEL':'qwen3.5:35b','AR_OLLAMA_BASE_URL':'http://ollama:11434/v1'}):
            outcome,usage=agent.repair({},SCHEMA,5)
            self.assertEqual(outcome,self.fixed())
            self.assertEqual(usage,{'input_tokens':123,'output_tokens':45})
            request,body,timeout=captured[0]
            self.assertEqual(request.full_url,'http://ollama:11434/api/chat')
            self.assertNotIn('tools',body)
            self.assertFalse(body['think'])
            user=json.loads(body['messages'][1]['content'])
            self.assertEqual(set(user),{'candidate','resultSchema','validationErrors'})
            self.assertEqual(user['validationErrors'][0]['rule'],'required')
            data['done_reason']='length'
            outcome,usage=agent.repair({},SCHEMA,5)
            self.assertIsNone(outcome)
            self.assertEqual(usage['output_tokens'],45)


if __name__ == '__main__':unittest.main()
