#!/usr/bin/env python3
"""Exercise change selection with real Git history and a stubbed GitHub API."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('changed-components.sh').resolve()

class ChangesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'CI Test')
        self.git('config', 'user.email', 'test@example.org')
        self.commit('README.md', 'initial')
        self.base = self.git('rev-parse', 'HEAD')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        gh = self.bin / 'gh'
        gh.write_text('#!/bin/sh\nprintf "%s\\n" "$TEST_BASE"\n')
        gh.chmod(0o755)

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True).strip()

    def commit(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
        self.git('add', path)
        self.git('commit', '-qm', 'test change')

    def select(self, event='push'):
        output = self.root / 'output'
        output.write_text('')
        payload = self.root / 'event.json'
        payload.write_text(json.dumps({'pull_request': {'base': {'sha': self.base}}}))
        subprocess.run(['bash', str(SCRIPT)], cwd=self.root, check=True, env={
            **os.environ, 'PATH': str(self.bin) + ':' + os.environ['PATH'],
            'TEST_BASE': self.base, 'GITHUB_OUTPUT': str(output),
            'GITHUB_EVENT_NAME': event, 'GITHUB_EVENT_PATH': str(payload),
            'GITHUB_REPOSITORY': 'test/runtime',
        })
        return {key for key, value in (line.split('=') for line in output.read_text().splitlines())
                if value == 'true'}

    def test_docs_skip_builds(self):
        self.commit('docs/example.md', 'docs')
        self.assertEqual(self.select(), set())

    def test_cancelled_changes_are_accumulated(self):
        self.commit('agent-runtime-server/src/example.kt', 'server')
        self.commit('docs/example.md', 'later push')
        self.assertEqual(self.select(), {'java', 'server'})

    def test_server_stays_selected_until_deployment_pin_catches_up(self):
        pinned = self.base
        self.commit('agent-runtime-server/src/example.kt', 'server')
        self.base = self.git('rev-parse', 'HEAD')
        self.commit('deploy/base/kustomization.yaml', 'images:\n  - newTag: sha-' + pinned + '\n')
        self.assertIn('server', self.select())

    def test_worker_does_not_rebuild_server_or_tools(self):
        self.commit('agent-runtime-worker/src/example.kt', 'worker')
        self.assertEqual(self.select(), {'java'})

    def test_manual_checks_everything_and_pr_uses_target(self):
        self.commit('monitor-ui/lib/example.dart', 'ui')
        self.assertEqual(self.select('pull_request'), {'ui'})
        self.assertEqual(self.select('workflow_dispatch'), {'java', 'ui', 'execution', 'deploy', 'server'})

if __name__ == '__main__':
    unittest.main()
