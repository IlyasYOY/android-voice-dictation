"""Untrusted APK validation, isolated from the private-key job."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import zipfile
from model import check_attempt, require

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('verifier', ROOT / 'scripts/verify-release.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
MAX_APK = 100_000_000


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def safe_apk(path, unsigned=False):
    path = Path(path)
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= MAX_APK, 'APK outside size/type limit')
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        require(len(entries) <= 20000 and len(entries) == len({e.filename for e in entries}), 'Duplicate or excessive APK entries')
        require(sum(e.file_size for e in entries) <= 300_000_000, 'APK expanded size limit')
        for entry in entries:
            require(not stat.S_ISLNK(entry.external_attr >> 16) and not entry.flag_bits & 1, 'Symlink/encrypted APK entry')
            require(not entry.filename.startswith('/') and '\\' not in entry.filename and '..' not in entry.filename.split('/'), 'Unsafe APK path')
            require(entry.file_size <= 100_000_000, 'Oversized APK entry')
            if unsigned:
                require(not re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC|SF)', entry.filename, re.I), 'Expected unsigned release APK')


def tool(args):
    return verifier.command(args)


def verify(path, item, tools, signed):
    safe_apk(path, unsigned=not signed)
    verifier.metadata(path, item['source'])
    verifier.manifest(tool([tools/'aapt', 'dump', 'badging', path]), item['code'], item['name'])
    if signed:
        verifier.certificate(tool([tools/'apksigner', 'verify', '--verbose', '--print-certs', path]), item['certificate'])
    else:
        # A v2/v3 block is not a ZIP member. Refuse pre-signed input as well.
        result = subprocess.run([str(tools/'apksigner'), 'verify', str(path)], capture_output=True, timeout=60)
        require(result.returncode != 0, 'Unexpected pre-signed APK')
    return dict(source=item['source'], version_code=item['code'], version_name=item['name'],
                certificate_sha256=item['certificate'] if signed else None, apk_sha256=digest(path),
                scope='Bytes and declared metadata checked; not reproducible source provenance', controller=item['controller'])


def main():
    item = json.loads(os.environ['VD_ITEM'])
    check_attempt(item, os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'])
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    source, output = Path(sys.argv[1]), Path(sys.argv[2])
    output.mkdir(exist_ok=False)
    verify(source, item, tools, signed=False)
    aligned = output/'app.apk'
    tool([tools/'zipalign', '-p', '4', source, aligned])
    verify(aligned, item, tools, signed=False)
    (output/'request.json').write_text(json.dumps(item, sort_keys=True), encoding='utf-8')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Unsigned APK validation failed; no signing material was loaded', file=sys.stderr)
        sys.exit(1)
