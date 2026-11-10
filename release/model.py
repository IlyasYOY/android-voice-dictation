"""Pure release ledger. The queue wakes the worker; Git history is the backlog."""
import copy
import re

MAX_CODE = 2_100_000_000
REPOSITORY = 'IlyasYOY/android-voice-dictation'
REPOSITORY_ID = '1438924211'
OWNER_ID = '18665585'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(value):
    require(isinstance(value, str) and re.fullmatch('[0-9a-f]{40}', value), 'Invalid source SHA')
    return value


def cert(value):
    require(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value), 'Configure the lowercase certificate SHA-256 first')
    return value


def version(value):
    require(isinstance(value, str) and re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', value), 'Invalid SemVer baseline')
    return value


def patch(value):
    major, minor, number = map(int, version(value).split('.'))
    return f'{major}.{minor}.{number + 1}'


def authorize(env, mode):
    require(env.get('GITHUB_REPOSITORY') == REPOSITORY and env.get('VD_REPOSITORY_ID') == REPOSITORY_ID
            and env.get('VD_OWNER_ID') == OWNER_ID and env.get('GITHUB_REF') == 'refs/heads/main', 'Only the canonical repository main workflow is trusted')
    event = env.get('GITHUB_EVENT_NAME')
    require(event in ('push', 'schedule', 'workflow_dispatch'), 'Unsupported event')
    require(mode in ('reconcile', 'pr', 'restore-main', 'initialize'), 'Unsupported operation')
    if mode != 'reconcile':
        require(event == 'workflow_dispatch' and env.get('VD_ACTOR_ID') == OWNER_ID
                and env.get('GITHUB_TRIGGERING_ACTOR') == 'IlyasYOY', 'This manual operation is owner-only, including reruns')
    elif event == 'workflow_dispatch':
        # The reconciler may wake itself using GITHUB_TOKEN. It cannot request PRs,
        # change the baseline or initialize state through this route.
        require(env.get('VD_ACTOR_ID') == OWNER_ID or env.get('GITHUB_ACTOR') == 'github-actions[bot]', 'Reconcile dispatch is owner/bot-only')
    if mode == 'initialize':
        require(env.get('VD_RELEASES_ENABLED') != 'true' and env.get('VD_PR_ENABLED') != 'true', 'Disable both release gates before initialization')
    else:
        gate = 'VD_PR_ENABLED' if mode in ('pr', 'restore-main') else 'VD_RELEASES_ENABLED'
        require(env.get(gate) == 'true', 'This release operation is disabled')


def initialize(anchor, base, floor, fingerprint):
    require(type(floor) is int and 0 < floor < MAX_CODE, 'Invalid Android versionCode floor')
    return dict(schema=1, anchor=sha(anchor), cursor=anchor, version=version(base), next_code=floor + 1,
                certificate=cert(fingerprint), allocations=[], published={})


def backlog(state, first_parent):
    require(state['schema'] == 1, 'Unknown ledger schema')
    require(len(first_parent) == len(set(first_parent)), 'Duplicate mainline SHA')
    require(state['anchor'] in first_parent and state['cursor'] in first_parent, 'Main history changed; do not reset the ledger')
    require(first_parent.index(state['anchor']) <= first_parent.index(state['cursor']), 'Invalid ledger cursor')
    return first_parent[first_parent.index(state['cursor']) + 1:]


def reserve(state, first_parent, mode, run, attempt, controller, pr=None, source=None):
    state = copy.deepcopy(state)
    waiting = backlog(state, first_parent)
    if mode == 'reconcile' and not waiting:
        return state, None
    require(state['next_code'] <= MAX_CODE, 'Android versionCode exhausted; no code was reused')
    require(not any(a['run'] == run and a['attempt'] == attempt for a in state['allocations']), 'This attempt already reserved a versionCode; reconcile in a new run')
    code = state['next_code']
    state['next_code'] += 1  # Failed/abandoned allocations are never recycled.
    if mode == 'reconcile':
        source, name = waiting[0], patch(state['version'])
    elif mode == 'restore-main':
        require(not waiting and state['cursor'] in state['published'], 'Finish the stable backlog before restoring main')
        source, name = state['cursor'], state['version'] + f'+main.{code}'
    else:
        require(mode == 'pr' and type(pr) is int and pr > 0, 'Invalid PR request')
        name = state['version'] + f'-pr.{pr}.{code}'
    item = dict(mode=mode, source=sha(source), code=code, name=name, pr=pr,
                run=str(run), attempt=str(attempt), controller=sha(controller), certificate=state['certificate'])
    state['allocations'].append(item)
    return state, item


def check_attempt(item, run, attempt):
    require(item['run'] == str(run) and item['attempt'] == str(attempt), 'Rerun all jobs or start a new reconcile; stale job output is refused')


def complete(state, item, release):
    state = copy.deepcopy(state)
    require(item in state['allocations'] and item['code'] == state['next_code'] - 1, 'Stale or unknown allocation')
    if item['mode'] == 'reconcile':
        require(item['name'] == patch(state['version']) and item['source'] not in state['published'], 'Release is out of order')
        state['cursor'], state['version'] = item['source'], item['name']
        state['published'][item['source']] = dict(item=item, release=release)
    return state
