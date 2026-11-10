import copy
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'release'))
import model
import fetch_artifact
import github_api
import publish

A, B, C, D = (x * 40 for x in 'abcd')
CERT = 'f' * 64


def initial():
    return model.initialize(A, '0.19.2', 42, CERT)


class LedgerTest(unittest.TestCase):
    def test_multi_commit_push_and_lost_events_drain_in_source_order(self):
        state = initial()
        # One wake-up can recover arbitrarily many source commits; no push payload
        # or run queue entry is the source of truth. Each iteration is a fresh VM run.
        history = [A] + [f'{i:040x}' for i in range(1, 251)]
        codes = []
        for i, source in enumerate(history[1:], 1):
            state, item = model.reserve(state, history, 'reconcile', str(i), '1', D)
            self.assertEqual(source, item['source'])
            self.assertEqual(f'0.19.{2+i}', item['name'])
            state = model.complete(state, item, {'id': i})
            codes.append(item['code'])
        self.assertEqual(list(range(43, 293)), codes)
        state, no_work = model.reserve(state, history, 'reconcile', '999', '1', D)
        self.assertIsNone(no_work)

    def test_failed_commit_is_not_skipped_and_code_is_burned(self):
        state, first = model.reserve(initial(), [A, B, C], 'reconcile', '1', '1', D)
        state, retry = model.reserve(state, [A, B, C], 'reconcile', '2', '1', D)
        self.assertEqual(B, retry['source'])
        self.assertEqual(first['name'], retry['name'])
        self.assertGreater(retry['code'], first['code'])
        with self.assertRaises(ValueError): model.complete(state, first, {})
        state = model.complete(state, retry, {})
        state, next_item = model.reserve(state, [A, B, C], 'reconcile', '3', '1', D)
        self.assertEqual(C, next_item['source'])

    def test_pr_stable_restore_all_use_one_increasing_counter(self):
        state, stable = model.reserve(initial(), [A, B], 'reconcile', '1', '1', D)
        state = model.complete(state, stable, {})
        state, pr = model.reserve(state, [A, B], 'pr', '2', '1', D, 17, C)
        state, restore = model.reserve(state, [A, B], 'restore-main', '3', '1', D)
        state, newer = model.reserve(state, [A, B, C], 'reconcile', '4', '1', D)
        self.assertEqual([43, 44, 45, 46], [x['code'] for x in (stable, pr, restore, newer)])
        self.assertEqual(B, restore['source'])
        self.assertEqual('0.19.3+main.45', restore['name'])
        self.assertEqual('0.19.4', newer['name'])

    def test_force_push_unknown_anchor_restore_backlog_and_overflow_stop(self):
        state = initial()
        for history in ([B, C], [A, A, B]):
            with self.assertRaises(ValueError): model.backlog(state, history)
        with self.assertRaises(ValueError): model.reserve(state, [A, B], 'restore-main', '1', '1', D)
        state['next_code'] = model.MAX_CODE
        state, item = model.reserve(state, [A, B], 'reconcile', '1', '1', D)
        self.assertEqual(model.MAX_CODE, item['code'])
        with self.assertRaises(ValueError): model.reserve(state, [A, B], 'reconcile', '2', '1', D)

    def test_duplicate_attempt_and_partial_job_rerun_are_refused(self):
        state, item = model.reserve(initial(), [A, B], 'reconcile', '1', '1', D)
        with self.assertRaises(ValueError): model.reserve(state, [A, B], 'reconcile', '1', '1', D)
        with self.assertRaises(ValueError): model.check_attempt(item, '1', '2')
        model.check_attempt(item, '1', '1')

    def test_first_parent_scope_is_explicit_and_semver_is_canonical(self):
        # C is a merge on main. Side-branch commits aren't first-parent entries.
        state = initial()
        self.assertEqual([B, C], model.backlog(state, [A, B, C]))
        self.assertEqual('1.2.10', model.patch('1.2.9'))
        for value in ('01.2.3', 'v1.2.3', '1.2.3-pr.1', '1.2', '1;echo x'):
            with self.assertRaises(ValueError): model.version(value)


class AuthorizationTest(unittest.TestCase):
    def env(self):
        return dict(GITHUB_REPOSITORY=model.REPOSITORY, VD_REPOSITORY_ID=model.REPOSITORY_ID,
                    VD_OWNER_ID=model.OWNER_ID, GITHUB_REF='refs/heads/main',
                    GITHUB_EVENT_NAME='workflow_dispatch', VD_ACTOR_ID=model.OWNER_ID,
                    GITHUB_TRIGGERING_ACTOR='IlyasYOY', GITHUB_ACTOR='IlyasYOY',
                    VD_RELEASES_ENABLED='true', VD_PR_ENABLED='true')

    def test_owner_only_and_no_untrusted_ref_or_event(self):
        env = self.env()
        for operation in ('pr', 'restore-main', 'reconcile'):
            model.authorize(env, operation)
        for field, value in [('GITHUB_REF', 'refs/pull/17/merge'), ('GITHUB_REPOSITORY', 'attacker/fork'),
                             ('VD_REPOSITORY_ID', '123'), ('VD_OWNER_ID', '123'),
                             ('VD_ACTOR_ID', '123'), ('GITHUB_TRIGGERING_ACTOR', 'other'),
                             ('GITHUB_EVENT_NAME', 'pull_request_target')]:
            changed = dict(env, **{field: value})
            with self.subTest(field=field):
                with self.assertRaises(ValueError): model.authorize(changed, 'pr')

    def test_flags_default_off_bot_cannot_request_pr_or_initialize(self):
        env = self.env()
        env.pop('VD_RELEASES_ENABLED'); env.pop('VD_PR_ENABLED')
        for operation in ('pr', 'restore-main', 'reconcile'):
            with self.assertRaises(ValueError): model.authorize(env, operation)
        model.authorize(env, 'initialize')
        env.update(GITHUB_ACTOR='github-actions[bot]', VD_ACTOR_ID='41898282', GITHUB_TRIGGERING_ACTOR='github-actions[bot]', VD_RELEASES_ENABLED='true')
        model.authorize(env, 'reconcile')
        for operation in ('pr', 'initialize'):
            with self.assertRaises(ValueError): model.authorize(env, operation)


class ArchiveTest(unittest.TestCase):
    def raw(self, members):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, body in members: archive.writestr(name, body)
        return stream.getvalue()

    def test_only_fixed_members_are_written(self):
        with tempfile.TemporaryDirectory() as root:
            dest = Path(root)/'output'
            fetch_artifact.extract(self.raw([('app.apk', b'fixture'), ('request.json', b'{}')]), dest, ('app.apk', 'request.json'))
            self.assertEqual(b'fixture', (dest/'app.apk').read_bytes())

    def test_traversal_extra_duplicate_symlink_and_bomb_refused(self):
        bad = [self.raw([('../app.apk', b'bad')]), self.raw([('/app.apk', b'bad')]),
               self.raw([('app.apk', b'good'), ('extra', b'bad')]),
               self.raw([('request.json', b'x'*16385)])]
        member = zipfile.ZipInfo('app.apk'); member.external_attr = (0o120777 << 16)
        bad.append(self.raw([(member, b'/etc/passwd')]))
        with self.assertWarns(UserWarning): bad.append(self.raw([('app.apk', b'a'), ('app.apk', b'b')]))
        for raw in bad:
            with tempfile.TemporaryDirectory() as root:
                with self.assertRaises(ValueError): fetch_artifact.extract(raw, Path(root)/'out', ('app.apk',))
                self.assertFalse((Path(root)/'out').exists())

    def test_immutable_artifact_digest_and_run_are_checked_before_extraction(self):
        raw = self.raw([('app.apk', b'fixture'), ('request.json', b'{}')])
        class FakeAPI:
            def call(self, path):
                return dict(id=7, workflow_run=dict(id=123), expired=False, size_in_bytes=len(raw))
        with tempfile.TemporaryDirectory() as root, patch.object(fetch_artifact, 'API', FakeAPI), patch.object(fetch_artifact, 'download', return_value=raw):
            destination = str(Path(root)/'output')
            with patch.dict(os.environ, dict(GITHUB_RUN_ID='123', VD_ARTIFACT_DIGEST='0'*64)), patch.object(sys, 'argv', ['fetch', 'trusted', '7', destination]):
                with self.assertRaises(ValueError): fetch_artifact.main()
                self.assertFalse(Path(destination).exists())
                os.environ['VD_ARTIFACT_DIGEST'] = hashlib.sha256(raw).hexdigest()
                fetch_artifact.main()
                self.assertEqual(b'fixture', (Path(destination)/'app.apk').read_bytes())
            with patch.dict(os.environ, dict(GITHUB_RUN_ID='124', VD_ARTIFACT_DIGEST=hashlib.sha256(raw).hexdigest())), patch.object(sys, 'argv', ['fetch', 'trusted', '7', str(Path(root)/'wrong-run')]):
                with self.assertRaises(ValueError): fetch_artifact.main()


class RecoveryTest(unittest.TestCase):
    def test_publish_success_lost_ledger_response_is_recovered_once(self):
        state, item = model.reserve(initial(), [A, B], 'reconcile', '1', '1', D)
        release = dict(id=7, html_url='https://example.invalid/release', draft=False,
                       assets=[dict(name=x) for x in ('voice-dictation.apk', 'verification.json', 'SHA256SUMS')],
                       body='verified' + publish.MARKER + json.dumps(dict(item=item, ready=True, apk_sha256='a'*64)) + ' -->')
        class FakeAPI:
            def call(self, path, **kwargs):
                if path.startswith('/git/ref/tags/'): return dict(object=dict(type='commit', sha=B))
                if path.endswith('v0.19.4'): return None
                return release
        fixed = publish.recover(FakeAPI(), state)
        self.assertEqual(B, fixed['cursor'])
        self.assertEqual('0.19.3', fixed['version'])
        self.assertEqual(fixed, publish.recover(FakeAPI(), fixed))
        for change in (dict(draft=True),):
            release.update(change)
            self.assertEqual(state, publish.recover(FakeAPI(), state))

    def test_git_ref_update_is_non_forced_child_commit(self):
        class FakeAPI(github_api.API):
            def __init__(self): self.calls = []
            def call(self, path, method='GET', data=None, **kwargs):
                self.calls.append((path, method, data))
                return dict(sha=C)
        api = FakeAPI()
        api.save(B, initial())
        self.assertEqual([B], api.calls[-2][2]['parents'])
        self.assertEqual(('/git/refs/heads/release-ledger', 'PATCH', dict(sha=C, force=False)), api.calls[-1])

    def test_unknown_release_or_wrong_tag_never_advances_cursor(self):
        state, item = model.reserve(initial(), [A, B], 'reconcile', '1', '1', D)
        class FakeAPI:
            def call(self, path, **kwargs):
                if path.startswith('/git/ref/tags/'):
                    return dict(object=dict(type='commit', sha=C))
                return dict(id=7, draft=False, assets=[], body='unrelated')
        with self.assertRaises(ValueError): publish.recover(FakeAPI(), state)
        self.assertEqual(A, state['cursor'])


if __name__ == '__main__': unittest.main()
