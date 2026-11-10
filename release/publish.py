"""Verify signed bytes on a key-free VM; publish once, then advance the ledger."""
import hashlib
import json
import os
from pathlib import Path
import sys
from urllib import request, parse
from apk import digest, tool, verify
from controller import guard
from github_api import API, NoRedirect
from model import check_attempt, complete, patch, require

MARKER = '\n\n<!-- apk-ledger:'


def marker(release):
    body = release.get('body') or ''
    require(MARKER in body and body.endswith(' -->'), 'Existing release is not controlled by this ledger')
    return json.loads(body.split(MARKER)[-1][:-4])


def recover(api, state):
    tag = 'v' + patch(state['version'])
    release = api.call('/releases/tags/' + tag, missing=True)
    if release is None or release['draft']:
        return state
    record = marker(release)
    item = record['item']
    require(item in state['allocations'] and item['mode'] == 'reconcile' and item['name'] == tag[1:], 'Published release has no valid reservation')
    ref = api.call('/git/ref/tags/' + tag)
    require(ref['object']['type'] == 'commit' and ref['object']['sha'] == item['source'], 'Published tag/source mismatch')
    names = {x['name'] for x in release['assets']}
    require(names == {'voice-dictation.apk', 'verification.json', 'SHA256SUMS'}, 'Published release assets incomplete')
    # Marker is written only after all verified assets were uploaded. The owner's
    # contents-write authority is the trust boundary, not a cryptographic attestation.
    require(record.get('ready') is True, 'Published release missing final verification marker')
    return complete(state, item, dict(id=release['id'], url=release['html_url'], apk_sha256=record['apk_sha256']))


def upload(api, release_id, path):
    data = Path(path).read_bytes()
    name = parse.quote(Path(path).name)
    url = f'https://uploads.github.com/repos/{os.environ["GITHUB_REPOSITORY"]}/releases/{release_id}/assets?name={name}'
    req = request.Request(url, data=data, method='POST', headers={
        'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Content-Type': 'application/octet-stream',
        'Accept': 'application/vnd.github+json'})
    with request.build_opener(NoRedirect()).open(req, timeout=120) as response:
        result = json.loads(response.read(100_000))
        require(result['size'] == len(data), 'Uploaded asset length mismatch')
        # GitHub release asset digest may be null on older API instances: fail closed.
        require(result.get('digest') == 'sha256:' + hashlib.sha256(data).hexdigest(), 'Uploaded release asset digest mismatch')


def main():
    api = API()
    item = json.loads(os.environ['VD_ITEM'])
    guard(api, item)
    incoming, outgoing = Path(sys.argv[1]), Path(sys.argv[2])
    require(json.loads((incoming/'request.json').read_text()) == item, 'Signed artifact request mismatch')
    tools = Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
    report = verify(incoming/'app.apk', item, tools, signed=True)
    tool([tools/'zipalign', '-c', '-p', '4', incoming/'app.apk'])
    head, state = api.load()
    require(state is not None and item in state['allocations'] and item['code'] == state['next_code'] - 1, 'Stale publisher allocation')
    outgoing.mkdir(exist_ok=False)
    apk = outgoing/'voice-dictation.apk'
    apk.write_bytes((incoming/'app.apk').read_bytes())
    (outgoing/'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    (outgoing/'SHA256SUMS').write_text(''.join(f'{digest(outgoing/name)}  {name}\n' for name in ('voice-dictation.apk', 'verification.json')), encoding='utf-8')
    if item['mode'] != 'reconcile':
        print('Verified owner-requested APK; download the final artifact from this workflow run.')
        return
    tag = 'v' + item['name']
    ref = api.call('/git/ref/tags/' + tag, missing=True)
    if ref is None:
        api.call('/git/refs', 'POST', dict(ref='refs/tags/' + tag, sha=item['source']))
    else:
        require(ref['object']['type'] == 'commit' and ref['object']['sha'] == item['source'], 'Refusing to move an existing release tag')
    release = api.call('/releases/tags/' + tag, missing=True)
    record = dict(item=item, apk_sha256=report['apk_sha256'], ready=False)
    if release:
        require(release['draft'] and marker(release)['item'] in state['allocations']
                and marker(release)['item']['source'] == item['source'], 'Refusing to overwrite an unrelated/published release')
        # A prior draft attempt may have an abandoned lower code. Replace only
        # this bound unpublished draft, never any published artifact or tag.
        for asset in release['assets']:
            api.call(f'/releases/assets/{asset["id"]}', 'DELETE')
    else:
        release = api.call('/releases', 'POST', dict(tag_name=tag, target_commitish=item['source'], name=tag, draft=True,
                          body='Preparing verified APK.' + MARKER + json.dumps(record, separators=(',', ':')) + ' -->'))
    for path in sorted(outgoing.iterdir()):
        upload(api, release['id'], path)
    # Recheck the source controller and allocation immediately before publication.
    guard(api, item)
    current_head, current = api.load()
    require(current == state and current_head == head, 'Ledger changed during publication; draft retained for recovery')
    record['ready'] = True
    body = ('Android Voice Dictation by IlyasYOY.\n\n'
            'Signed APK, verification metadata and checksums. A new signing identity requires reinstalling the legacy app; save local data first. '
            'Build checks do not establish phone/OEM compatibility. No live provider request was made by CI.\n\n'
            f'Source: `{item["source"]}`; Android versionCode: `{item["code"]}`. '
            'See README, PRIVACY and THIRD_PARTY_NOTICES in this source revision.'
            + MARKER + json.dumps(record, separators=(',', ':')) + ' -->')
    release = api.call(f'/releases/{release["id"]}', 'PATCH', dict(body=body, draft=False, make_latest='true'))
    updated = complete(state, item, dict(id=release['id'], url=release['html_url'], apk_sha256=report['apk_sha256']))
    api.save(head, updated)
    print('Published ' + tag + ' and advanced the ledger.')


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(str(failure) if type(failure) is ValueError else 'Publish failed; reconcile the bound draft/release before retrying', file=sys.stderr)
        sys.exit(1)
