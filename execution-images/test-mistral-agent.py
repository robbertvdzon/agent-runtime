import contextlib
import io
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('mistral', Path(__file__).with_name('mistral-agent.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
SCHEMA = {'type':'object','required':['title','sources'],'properties':{'title':{'type':'string'},'sources':{'type':'array','items':{'type':'integer'}}},'additionalProperties':False}


def record_completion(identifier, tokens=100):
    return {'type':'action_result','payload':{'result':{'type':'completion_succeeded','action_id':identifier,'result':{'usage':{'input_tokens':tokens,'cached_input_tokens':20,'output_tokens':10}}}}}


class MistralAdapterTest(unittest.TestCase):
    def test_full_schema_and_empty_answers_are_rejected(self):
        for answer in ['', 'words', '{', '{"sources":["7"]}', '[]', '{"title":"x","sources":[NaN]}']:
            with self.subTest(answer=answer), self.assertRaises(Exception):
                m.validate(answer, SCHEMA)
        self.assertEqual(m.validate('{"title":"x","sources":[7]}', SCHEMA)['sources'], [7])

    def test_usage_counts_rejected_completions_once_across_segments(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d); (p/'journal').mkdir(); (p/'meta.json').write_text('{"session_id":"sess"}')
            a=record_completion('a'); b=record_completion('b',200)
            (p/'journal'/'0001.jsonl').write_text(json.dumps(a)+'\n')
            (p/'journal'/'0002.jsonl').write_text(json.dumps(a)+'\n'+json.dumps(b)+'\n{"partial":')
            events=list(m.usage_events(p))
            self.assertEqual([e['request_id'] for e in events], ['a','b'])
            self.assertEqual(sum(e['usage']['input_tokens'] for e in events),300)
            self.assertEqual({e['session_id'] for e in events},{'sess'})

    def test_pending_candidate_includes_uncommitted_answer(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d); (p/'journal').mkdir()
            r={'type':'action_intent','payload':{'request':{'hook':'post_agent_turn','input':{'candidate':{'message':{'content':[{'type':'reasoning','text':'private'},{'type':'text','text':'broken JSON'}]}}}}}}
            (p/'journal'/'0001.jsonl').write_text(json.dumps(r)+'\n')
            self.assertEqual(m.pending_candidate(p),'broken JSON')

    def test_reasoning_and_discarded_answers_never_become_public(self):
        self.assertIsNone(m.public_event({'type':'reasoning','text':'private'}))
        self.assertIsNone(m.public_event({'type':'message','role':'assistant','outcome':{'type':'discarded'},'content':[{'type':'text','text':'bad'}]}))
        self.assertIsNone(m.public_event({'type':'message','role':'user','outcome':None}))
        self.assertEqual(m.public_event({'type':'message','role':'assistant','id':'a','content':[{'type':'text','text':'ok'}]})['item']['text'],'ok')

    def test_failures_are_classified_without_returning_provider_errors(self):
        self.assertEqual(m.failure_code({'error':'HTTP 429 quota exceeded secret'},1),'PROVIDER_UNAVAILABLE')
        self.assertEqual(m.failure_code({'outcome':'deadline'},3),'EXECUTION_TIMEOUT')
        self.assertEqual(m.failure_code({'outcome':'token_limit'},3),'SESSION_TOKEN_LIMIT')
        self.assertEqual(m.failure_code({'outcome':'turn_limit'},3),'SESSION_TURN_LIMIT')
        self.assertEqual(m.failure_code({'error':'generic'},1),'ENGINE_FAILED')

    def test_quota_evidence_is_sanitized_and_local_caps_are_not_provider_limits(self):
        self.assertIsNone(m.limit_event({'outcome':'token_limit'}))
        event = m.limit_event({'error':{'code':'model_stream_failed','message':'secret provider detail',
                                      'details':{'httpStatus':429,'correlationId':'private'}}})
        self.assertEqual(event['http_status'],429)
        self.assertEqual(event['kind'],'UNKNOWN')
        self.assertNotIn('secret',json.dumps(event))
        self.assertNotIn('private',json.dumps(event))
        monthly = m.limit_event({'error':{'code':'monthly_quota_exceeded','status':429}})
        self.assertEqual(monthly['kind'],'MONTH')

    def test_pinned_journal_failure_is_detected_without_export_error(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d); (p/'journal').mkdir()
            failure={'type':'action_result','payload':{'result':{'type':'completion_failed','action_id':'failed-call',
                     'error':{'code':'model_stream_failed','message':'hidden detail','retryable':False,'details':{'httpStatus':429}}}}}
            (p/'journal'/'0001.jsonl').write_text(json.dumps(failure)+'\n')
            event=list(m.limit_events(p))[0]
            self.assertEqual(event['request_id'],'failed-call')
            self.assertEqual(event['http_status'],429)
            self.assertNotIn('hidden detail',json.dumps(event))

    def test_finished_exit_zero_with_invalid_answer_still_fails(self):
        self.exercise('NOT_JSON', expected=1)

    def test_valid_final_answer_and_retry_usage_are_captured(self):
        self.exercise('{"title":"ok","sources":[7]}', expected=0)

    def test_schema_repair_preserves_valid_text_and_source_ids(self):
        def check(root, events, calls):
            self.assertEqual(calls, 2)
            result = json.loads((root/'output'/'result.json').read_text())
            self.assertEqual(result, {'title': 'Original', 'sources': [7]})
            self.assertFalse((root/'output'/'.mistral-draft.txt').exists())
        self.exercise('', 0, answers=['{"title":"Original"}', '{"title":"ok","sources":[7]}'], check=check)

    def test_explicit_draft_survives_cli_acknowledgement(self):
        self.exercise('Answer saved.', 0, draft='{"title":"ok","sources":[7]}',
                      check=lambda root,events,calls: self.assertEqual(calls, 1))

    def test_malformed_json_can_be_repaired_without_research(self):
        self.exercise('', 0, answers=['{"title":"ok","sources":', '{"title":"ok","sources":[7]}'],
                      check=lambda root,events,calls: self.assertEqual(calls, 2))

    def test_repair_stops_after_three_attempts_and_keeps_bounded_draft(self):
        def check(root, events, calls):
            self.assertEqual(calls, 4)
            self.assertEqual((root/'output'/'.mistral-draft.txt').read_text(), '{}')
        self.exercise('{}', 1, expected_code='MODEL_OUTPUT_SCHEMA_INVALID', check=check)

    def test_engine_failure_diagnostics_are_safe_and_do_not_start_repair(self):
        def check(root, events, calls):
            self.assertEqual(calls, 1)
            diagnostic = next(e for e in events if e['type']=='mistral.diagnostic')
            self.assertEqual(diagnostic['exit_code'], 2)
            self.assertEqual(diagnostic['outcome'], 'infrastructure_failure')
            self.assertEqual(diagnostic['category'], 'provider_connection')
            self.assertEqual(diagnostic['provider_error_code'], 'model_stream_failed')
            self.assertNotIn('PRIVATE_SECRET', json.dumps(events))
            self.assertNotIn('PRIVATE_REASONING', json.dumps(events))
        self.exercise('', 1, exit_code=2, outcome='infrastructure_failure',
                      stderr='connection reset PRIVATE_SECRET PRIVATE_REASONING',
                      error={'code':'model_stream_failed','message':'PRIVATE_SECRET'},
                      expected_code='PROVIDER_UNAVAILABLE', check=check)

    def test_only_known_diagnostic_values_are_published(self):
        event=m.diagnostic({'outcome':'SECRET', 'error':{'code':'SECRET','message':'SECRET', 'details':{'httpStatus':429,'token':'SECRET'}}}, 2, 'SECRET', 'research', 0)
        self.assertNotIn('SECRET', json.dumps(event))
        self.assertEqual(event['http_status'],429)
        self.assertEqual(event['outcome'],'unknown')

    def test_hook_captures_invalid_candidate_without_restarting_research(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); session=root/'session'; (session/'journal').mkdir(parents=True)
            record={'type':'action_intent','payload':{'request':{'hook':'post_agent_turn','input':{'candidate':{'message':{'content':[{'type':'reasoning','text':'PRIVATE'},{'type':'text','text':'{"title":'}]}}}}}}
            (session/'journal'/'0001.jsonl').write_text(json.dumps(record)+'\n')
            draft=root/'draft'; output=io.StringIO()
            with patch.dict(os.environ, {'AR_MISTRAL_DRAFT_FILE':str(draft)}), patch.object(m.sys,'stdin',io.StringIO(json.dumps({'transcript_path':str(session)}))), contextlib.redirect_stdout(output):
                m.hook(str(root/'schema'))
            self.assertEqual(json.loads(output.getvalue())['decision'],'allow')
            self.assertEqual(draft.read_text(),'{"title":')
            self.assertNotIn('PRIVATE',draft.read_text())

    def test_markdown_envelope_is_normalized_but_trailing_prose_is_rejected(self):
        self.assertEqual(m.validate('```json\n{"title":"ok","sources":[7]}\n```',SCHEMA)['sources'],[7])
        with self.assertRaises(ValueError):
            m.validate('```json\n{"title":"ok","sources":[7]}\n``` trailing',SCHEMA)

    def test_shared_token_budget_stops_before_another_model_call(self):
        with patch.object(m, 'MAX_SESSION_TOKENS', 200):
            self.exercise('{}', 1, expected_code='SESSION_TOKEN_LIMIT',
                          check=lambda root,events,calls: self.assertEqual(calls, 1))

    def test_provider_limit_from_export_survives_without_journal_failure(self):
        def check(root, events, calls):
            self.assertEqual(calls, 1)
            limit = next(e for e in events if e['type']=='mistral.limit')
            self.assertEqual(limit['kind'], 'MONTH')
            self.assertNotIn('PRIVATE', json.dumps(events))
        self.exercise('', 1, exit_code=2, outcome='infrastructure_failure',
                      error={'code':'monthly_quota_exceeded','status':429,'message':'PRIVATE'},
                      expected_code='PROVIDER_UNAVAILABLE', check=check)

    def test_deadline_failure_does_not_start_a_repair(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); (root/'input').mkdir(); (root/'output').mkdir()
            (root/'input'/'prompt.md').write_text('Original sources')
            events=[]
            with patch.dict(os.environ, {'AR_MODEL':'glm-5-3'}), patch.object(m, 'run_vibe', side_effect=TimeoutError) as call, patch.object(m, 'emit', events.append):
                self.assertEqual(m.run(root/'input',root/'output',root),1)
            self.assertEqual(call.call_count,1)
            self.assertEqual(next(e for e in events if e['type']=='mistral.status')['code'],'EXECUTION_TIMEOUT')
            self.assertFalse(events[-1]['complete'])

    def test_unknown_engine_failure_retains_exit_and_outcome_without_guessing(self):
        def check(root, events, calls):
            diag=next(e for e in events if e['type']=='mistral.diagnostic')
            self.assertEqual(diag['exit_code'],2)
            self.assertEqual(diag['outcome'],'infrastructure_failure')
            self.assertEqual(diag['category'],'unknown_execution_failure')
            self.assertEqual(calls,1)
            self.assertNotIn('PRIVATE',json.dumps(events))
        self.exercise('',1,exit_code=2,outcome='infrastructure_failure',
                      error={'message':'PRIVATE'},expected_code='ENGINE_FAILED',check=check)

    def test_wall_deadline_stops_process_without_publishing_stderr(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); fake=root/'vibe'
            fake.write_text('#!/usr/bin/env python3\nimport sys,time\nprint("PRIVATE", file=sys.stderr, flush=True)\ntime.sleep(30)\n')
            fake.chmod(0o755)
            events=[]
            with patch.dict(os.environ, {'AR_VIBE_BINARY':str(fake)}),patch.object(m,'emit',events.append):
                answer,code,complete=m.run_vibe('Prompt',root/'schema',root,root,time.monotonic()+0.1,'glm-5-3','research',0,1000,events.append)
            self.assertEqual(code,'EXECUTION_TIMEOUT')
            self.assertIsNone(answer)
            self.assertFalse(complete)
            self.assertNotIn('PRIVATE',json.dumps(events))

    def exercise(self, answer, expected, *, answers=None, draft=None, exit_code=0, outcome="finished", stderr="", error=None, expected_code="MODEL_OUTPUT_NOT_JSON", check=None):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d); inp=p/'input';out=p/'output';work=p/'work'
            for directory in [inp,out,work]: directory.mkdir()
            (inp/'prompt.md').write_text('Synthetic prompt'); (inp/'response-schema.json').write_text(json.dumps(SCHEMA))
            fake=p/'vibe'; fake.write_text('''#!/usr/bin/env python3
import os,sys,json,pathlib
args=sys.argv
marker=pathlib.Path(os.environ['TEST_MARKER']); index=int(marker.read_text()) if marker.exists() else 0; marker.write_text(str(index+1))
config=(pathlib.Path(os.environ['VIBE_HOME'])/'config.toml').read_text()
if index: assert 'disabled_tools = ["*"]' in config
if index: assert not list(pathlib.Path(args[args.index('--workdir')+1]).iterdir())
if index: assert 'Synthetic prompt' not in pathlib.Path(args[args.index('--prompt-file')+1]).read_text()
assert 'api_retry_max_elapsed_time = 0.0' in (pathlib.Path(os.environ['VIBE_HOME'])/'config.toml').read_text()
out=pathlib.Path(args[args.index('--output-dir')+1]); (out/'session'/'journal').mkdir(parents=True)
(out/'session'/'meta.json').write_text('{"session_id":"session-proof"}')
records=[]
for i in range(2):
 records.append({'type':'action_result','payload':{'result':{'type':'completion_succeeded','action_id':str(i),'result':{'usage':{'input_tokens':100,'cached_input_tokens':20,'output_tokens':10}}}}})
(out/'session'/'journal'/'0001.jsonl').write_text('\\n'.join(json.dumps(r) for r in records)+'\\n')
(out/'export.json').write_text(json.dumps({'outcome':os.environ['TEST_OUTCOME'],'error':json.loads(os.environ['TEST_ERROR']),'session_id':'session-proof','steps':2,'config':{'active_model':'glm-5-3'}}))
if index == 0 and os.environ.get('TEST_DRAFT'):
 pathlib.Path(os.environ['AR_MISTRAL_DRAFT_FILE']).write_text(os.environ['TEST_DRAFT'])
print(os.environ['TEST_STDERR'],file=sys.stderr)
print(json.dumps({'type':'reasoning','text':'MUST_NOT_LEAK'}))
print(json.dumps({'id':'a','type':'message','role':'assistant','sessionId':'session-proof','outcome':{'type':'committed'},'content':[{'type':'text','text':json.loads(os.environ['TEST_FINALS'])[min(index,len(json.loads(os.environ['TEST_FINALS']))-1)]}]}))
sys.exit(int(os.environ['TEST_EXIT']))
'''); fake.chmod(0o755)
            events=[]
            with patch.dict(os.environ,{'AR_MODEL':'glm-5-3','AR_RESULT_FILE':str(out/'result.json'),'AR_VIBE_BINARY':str(fake),'TEST_FINALS':json.dumps(answers or [answer]),'TEST_MARKER':str(p/'calls'),'TEST_DRAFT':draft or '', 'TEST_EXIT':str(exit_code),'TEST_OUTCOME':outcome,'TEST_STDERR':stderr,'TEST_ERROR':json.dumps(error)}),patch.object(m,'emit',events.append):
                self.assertEqual(m.run(inp,out,work),expected)
            self.assertNotIn('MUST_NOT_LEAK',json.dumps(events))
            calls=int((p/'calls').read_text())
            usage=[e for e in events if e['type']=='mistral.usage']
            self.assertEqual(len(usage),2*calls)
            self.assertEqual(len({e['request_id'] for e in usage}),len(usage))
            self.assertEqual(len([e for e in events if e['type']=='mistral.completed']),1)
            self.assertEqual(next(e for e in events if e['type']=='mistral.completed')['complete'], outcome == 'finished')
            if check: check(p,events,calls)
            if expected:
                self.assertFalse((out/'result.json').exists())
                self.assertEqual(json.loads((out/'mistral-status.json').read_text())['code'],expected_code)
            else:
                self.assertEqual(json.loads((out/'result.json').read_text())['sources'],[7])

if __name__=='__main__': unittest.main()
