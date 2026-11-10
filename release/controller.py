#!/usr/bin/env python3
"""Trusted main-only controller. No Gradle, PR scripts, keystore or private data."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from github_api import API
from model import authorize, backlog, cert, check_attempt, complete, initialize, require, reserve, sha

ROOT = Path(__file__).resolve().parents[1]
TRUSTED = ['release', '.github/workflows/apk-release.yml', 'scripts/verify-release.py', 'app/src/main/assets/licenses']


def git(*args):
    result = subprocess.run(['git', '-C', str(ROOT), *args], capture_output=True, text=True, timeout=120)
    require(result.returncode == 0, 'Trusted source/history lookup failed')
    return result.stdout.strip()


def fingerprint(revision):
    # Include all executable controller code and verifier, but not ordinary app changes.
    return hashlib.sha256(git('ls-tree', '-r', sha(revision), '--', *TRUSTED).encode()).hexdigest()


def guard(api, item=None):
    mode = item['mode'] if item else os.environ.get('VD_MODE', 'reconcile')
    authorize(os.environ, mode)
    current = sha(api.call('/git/ref/heads/main')['object']['sha'])
    git('fetch', '--no-tags', 'origin', current)
    require(fingerprint(os.environ['VD_CONTROLLER']) == fingerprint(current), 'Release controller changed; start a fresh main workflow')
    if item:
        check_attempt(item, os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'])
        require(item['controller'] == os.environ['VD_CONTROLLER'], 'Controller revision mismatch')
        require(item['certificate'] == cert(os.environ['VD_CERTIFICATE']), 'Certificate changed; review identity migration instead of resetting state')
    return current


def emit(name, value):
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as target:
        target.write(name + '=' + (value if isinstance(value, str) else json.dumps(value, separators=(',', ':'))) + '\n')


def main():
    api = API()
    action = sys.argv[1]
    if action == 'guard':
        guard(api, json.loads(os.environ['VD_ITEM']))
        return
    if action == 'wake':
        current = guard(api)
        _, state = api.load()
        require(state is not None, 'Release ledger missing')
        history = git('rev-list', '--first-parent', '--reverse', current).splitlines()
        if backlog(state, history):
            api.call('/actions/workflows/apk-release.yml/dispatches', 'POST', dict(ref='main', inputs=dict(operation='reconcile')))
        return
    if action == 'plan':
        mode = os.environ.get('VD_MODE') or 'reconcile'
        current = guard(api)
        history = git('rev-list', '--first-parent', '--reverse', current).splitlines()
        head, state = api.load()
        fingerprint_value = cert(os.environ['VD_CERTIFICATE'])
        if mode == 'initialize':
            require(state is None, 'Ledger already exists; never initialize/reset it again')
            require(not api.call('/releases?per_page=1') and not api.call('/git/matching-refs/tags/v'),
                    'Existing releases/version tags require a reviewed migration; initialization is not disaster recovery')
            anchor = sha(os.environ['VD_ANCHOR'])
            require(anchor in history, 'Start-after SHA is not on the main first-parent line')
            state = initialize(anchor, os.environ['VD_BASE'], int(os.environ['VD_FLOOR']), fingerprint_value)
            api.save(None, state)
            emit('has_work', 'false')
            return
        require(state is not None, 'Owner must initialize release-ledger before enabling this operation')
        require(state['certificate'] == fingerprint_value, 'Ledger and configured certificate differ')
        # A public release may have succeeded before the ledger update response was lost.
        # Reconcile that immutable result before allocating another code.
        from publish import recover
        recovered = recover(api, state)
        if recovered != state:
            api.save(head, recovered)
            head, state = api.load()
        source, pr = None, None
        if mode == 'pr':
            pr = int(os.environ['VD_PR'])
            require(pr > 0, 'Positive PR number required')
            info = api.call(f'/pulls/{pr}')
            require(info['state'] == 'open' and info['base']['ref'] == 'main'
                    and info['base']['repo']['full_name'] == os.environ['GITHUB_REPOSITORY'], 'PR must be open and target canonical main')
            source = sha(info['head']['sha'])
        state, item = reserve(state, history, mode, os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'], os.environ['VD_CONTROLLER'], pr, source)
        if item:
            api.save(head, state)
            emit('item', item)
            emit('environment', 'apk-stable-signing' if mode == 'reconcile' else 'apk-pr-signing')
            emit('source', item['source'])
            emit('code', str(item['code']))
        emit('has_work', 'true' if item else 'false')
        return
    raise ValueError('Unknown controller command')


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(str(failure) if isinstance(failure, ValueError) else 'Release controller failed; inspect ledger/run state before retrying', file=sys.stderr)
        sys.exit(1)
