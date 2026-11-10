"""Bounded GitHub REST client, no redirects carrying credentials, no response-body logs."""
import base64
import json
import os
from urllib import request, error
from model import REPOSITORY, require


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class API:
    def __init__(self):
        self.root = 'https://api.github.com/repos/' + REPOSITORY
        self.opener = request.build_opener(NoRedirect())

    def call(self, path, method='GET', data=None, missing=False, binary=False):
        require(path.startswith('/') and '\n' not in path, 'Invalid API path')
        body = data if binary else (json.dumps(data).encode() if data is not None else None)
        req = request.Request(self.root + path, data=body, method=method, headers={
            'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/octet-stream' if binary else 'application/json',
            'User-Agent': 'voice-dictation-release-controller'})
        try:
            with self.opener.open(req, timeout=90) as response:
                raw = response.read(16_000_001)
                require(len(raw) <= 16_000_000, 'GitHub response exceeds controller limit')
                return json.loads(raw) if raw else None
        except error.HTTPError as failure:
            if missing and failure.code == 404:
                return None
            raise ValueError(f'GitHub API operation failed (HTTP {failure.code}); inspect state before retrying') from None

    def load(self):
        ref = self.call('/git/ref/heads/release-ledger', missing=True)
        if ref is None:
            return None, None
        head = ref['object']['sha']
        content = self.call('/contents/ledger.json?ref=' + head)
        return head, json.loads(base64.b64decode(content['content'], validate=False))

    def save(self, previous, state):
        raw = json.dumps(state, sort_keys=True, separators=(',', ':')).encode()
        require(len(raw) < 900_000, 'Ledger capacity reached; archive through an explicitly reviewed migration, never reset the counter')
        blob = self.call('/git/blobs', 'POST', dict(content=base64.b64encode(raw).decode(), encoding='base64'))
        tree = self.call('/git/trees', 'POST', dict(tree=[dict(path='ledger.json', mode='100644', type='blob', sha=blob['sha'])]))
        commit = self.call('/git/commits', 'POST', dict(message='Advance APK release ledger', tree=tree['sha'], parents=[previous] if previous else []))
        if previous:
            # Commit is a child of the observed ref. force:false makes this compare-and-swap:
            # a concurrent child is not its ancestor and GitHub rejects the update.
            self.call('/git/refs/heads/release-ledger', 'PATCH', dict(sha=commit['sha'], force=False))
        else:
            self.call('/git/refs', 'POST', dict(ref='refs/heads/release-ledger', sha=commit['sha']))
