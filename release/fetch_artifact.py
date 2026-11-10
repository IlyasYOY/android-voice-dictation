"""Download a bounded immutable artifact; never extract attacker-controlled paths."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import sys
from urllib import request, error, parse
import zipfile
from github_api import API, NoRedirect
from model import require

MAX_ZIP = 110_000_000


def extract(raw, destination, expected):
    require(0 < len(raw) <= MAX_ZIP, 'Artifact ZIP exceeds limit')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries = archive.infolist()
        require(len(entries) == len(expected) and {x.filename for x in entries} == set(expected), 'Unexpected, missing or duplicate artifact members')
        require(sum(x.file_size for x in entries) <= MAX_ZIP, 'Artifact expanded size limit')
        for member in entries:
            require(not member.flag_bits & 1 and not stat.S_ISLNK(member.external_attr >> 16), 'Encrypted/symlink artifact member')
            require(member.file_size <= (100_000_000 if member.filename.endswith('.apk') else 16384), 'Artifact member too large')
        Path(destination).mkdir(exist_ok=False)
        for name in expected:
            # Fixed caller-provided names only; no extract/extractall or remote filenames.
            with (Path(destination)/name).open('xb') as output:
                output.write(archive.read(name))


def download(api, artifact_id):
    url = api.root + f'/actions/artifacts/{artifact_id}/zip'
    headers = {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'}
    opener = request.build_opener(NoRedirect())
    for _ in range(4):
        try:
            with opener.open(request.Request(url, headers=headers), timeout=120) as response:
                result = response.read(MAX_ZIP + 1)
                require(len(result) <= MAX_ZIP, 'Artifact transfer exceeds limit')
                return result
        except error.HTTPError as redirect:
            require(redirect.code in (301, 302, 303, 307, 308), 'Artifact transfer failed; no alternate-host bypass attempted')
            url = parse.urljoin(url, redirect.headers.get('Location', ''))
            parts = parse.urlsplit(url)
            host = parts.hostname or ''
            require(parts.scheme == 'https' and not parts.username and not parts.password and parts.port in (None, 443)
                    and (host.endswith('.blob.core.windows.net') or host.endswith('.githubusercontent.com')
                         or host == 'results-receiver.actions.githubusercontent.com'), 'Unexpected artifact redirect host')
            headers = {}  # Never send the repository token to a storage redirect.
    raise ValueError('Too many artifact redirects')


def main():
    api = API()
    kind, selector, destination = sys.argv[1:4]
    run = int(os.environ['GITHUB_RUN_ID'])
    if kind == 'source':
        artifacts = []
        for page in range(1, 11):
            batch = api.call(f'/actions/runs/{run}/artifacts?per_page=100&page={page}')['artifacts']
            artifacts.extend(batch)
            if len(batch) < 100:
                break
        else:
            raise ValueError('Too many run artifacts')
        matches = [x for x in artifacts if x['name'] == selector and not x['expired']]
        require(len(matches) == 1, 'Expected one source artifact')
        artifact = matches[0]
        expected = ('app-release-unsigned.apk',)
    else:
        require(kind == 'trusted' and re.fullmatch('[1-9][0-9]*', selector), 'Invalid artifact ID')
        artifact = api.call('/actions/artifacts/' + selector)
        expected = ('app.apk', 'request.json')
    require(artifact['workflow_run']['id'] == run and not artifact['expired']
            and artifact['size_in_bytes'] <= MAX_ZIP, 'Artifact run/size/expiry mismatch')
    raw = download(api, artifact['id'])
    actual = hashlib.sha256(raw).hexdigest()
    if kind == 'trusted':
        require(re.fullmatch('[0-9a-f]{64}', os.environ['VD_ARTIFACT_DIGEST']) and actual == os.environ['VD_ARTIFACT_DIGEST'], 'Trusted producer artifact digest mismatch')
    extract(raw, destination, expected)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Artifact download/validation failed; no raw URL or response body logged', file=sys.stderr)
        sys.exit(1)
