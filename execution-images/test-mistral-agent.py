import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
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
        self.assertEqual(m.failure_code({},3),'EXECUTION_TIMEOUT')
        self.assertEqual(m.failure_code({'error':'generic'},1),'ENGINE_FAILED')

    def test_finished_exit_zero_with_invalid_answer_still_fails(self):
        self.exercise('NOT_JSON', expected=1)

    def test_valid_final_answer_and_retry_usage_are_captured(self):
        self.exercise('{"title":"ok","sources":[7]}', expected=0)

    def exercise(self, answer, expected):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d); inp=p/'input';out=p/'output';work=p/'work'
            for directory in [inp,out,work]: directory.mkdir()
            (inp/'prompt.md').write_text('Synthetic prompt'); (inp/'response-schema.json').write_text(json.dumps(SCHEMA))
            fake=p/'vibe'; fake.write_text('''#!/usr/bin/env python3
import os,sys,json,pathlib
args=sys.argv
out=pathlib.Path(args[args.index('--output-dir')+1]); (out/'session'/'journal').mkdir(parents=True)
(out/'session'/'meta.json').write_text('{"session_id":"session-proof"}')
records=[]
for i in range(2):
 records.append({'type':'action_result','payload':{'result':{'type':'completion_succeeded','action_id':str(i),'result':{'usage':{'input_tokens':100,'cached_input_tokens':20,'output_tokens':10}}}}})
(out/'session'/'journal'/'0001.jsonl').write_text('\\n'.join(json.dumps(r) for r in records)+'\\n')
(out/'export.json').write_text(json.dumps({'outcome':'finished','session_id':'session-proof','steps':2,'config':{'active_model':'glm-5-3'}}))
print(json.dumps({'type':'reasoning','text':'MUST_NOT_LEAK'}))
print(json.dumps({'id':'a','type':'message','role':'assistant','sessionId':'session-proof','outcome':{'type':'committed'},'content':[{'type':'text','text':os.environ['TEST_FINAL']}]}))
'''); fake.chmod(0o755)
            events=[]
            with patch.dict(os.environ,{'AR_MODEL':'glm-5-3','AR_RESULT_FILE':str(out/'result.json'),'AR_VIBE_BINARY':str(fake),'TEST_FINAL':answer}),patch.object(m,'emit',events.append):
                self.assertEqual(m.run(inp,out,work),expected)
            self.assertNotIn('MUST_NOT_LEAK',json.dumps(events))
            self.assertEqual(len([e for e in events if e['type']=='mistral.usage']),2)
            self.assertTrue(next(e for e in events if e['type']=='mistral.completed')['complete'])
            if expected:
                self.assertFalse((out/'result.json').exists())
                self.assertEqual(json.loads((out/'mistral-status.json').read_text())['code'],'MODEL_OUTPUT_NOT_JSON')
            else:
                self.assertEqual(json.loads((out/'result.json').read_text())['sources'],[7])

if __name__=='__main__': unittest.main()
