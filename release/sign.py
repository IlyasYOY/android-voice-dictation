"""Runs only in a fresh environment-protected VM. Never executes build/PR code."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from model import cert, check_attempt, require


def run(args, env=None):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True, timeout=90, env=env)
    require(result.returncode == 0, 'Signing tool failed (tool output withheld)')
    return result.stdout


def main():
    item = json.loads(os.environ['VD_ITEM'])
    check_attempt(item, os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'])
    require(cert(os.environ['VD_CERTIFICATE']) == item['certificate'], 'Unexpected signing identity')
    incoming, outgoing = Path(sys.argv[1]), Path(sys.argv[2])
    require(json.loads((incoming/'request.json').read_text()) == item, 'Validated artifact/request mismatch')
    apk = incoming/'app.apk'
    require(apk.is_file() and not apk.is_symlink() and 0 < apk.stat().st_size <= 100_000_000, 'Invalid aligned APK')
    alias = os.environ['APK_SIGNING_KEY_ALIAS']
    require(re.fullmatch(r'[A-Za-z0-9_-]{1,80}', alias), 'Invalid key alias')
    encoded = os.environ.pop('APK_SIGNING_KEYSTORE_BASE64')
    require(len(encoded) <= 100_000, 'Keystore exceeds limit')
    private = base64.b64decode(encoded, validate=True)
    require(0 < len(private) <= 65536, 'Invalid keystore size')
    outgoing.mkdir(exist_ok=False)
    with tempfile.TemporaryDirectory(prefix='apk-key-', dir=os.environ['RUNNER_TEMP']) as directory:
        key = Path(directory)/'signing.p12'
        descriptor = os.open(key, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'wb') as target:
            target.write(private)
        del private, encoded
        # Only Java keytool/apksigner see the password environment. No aapt, Gradle,
        # build scripts or unsigned archive extraction runs with these secrets.
        public = Path(directory)/'certificate.der'
        run(['keytool', '-exportcert', '-keystore', key, '-alias', alias,
             '-storepass:env', 'APK_SIGNING_STORE_PASSWORD', '-file', public])
        import hashlib
        require(hashlib.sha256(public.read_bytes()).hexdigest() == item['certificate'], 'Keystore certificate does not match the reviewed fingerprint')
        tool = Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0/apksigner'
        run([tool, 'sign', '--ks', key, '--ks-key-alias', alias,
             '--ks-pass', 'env:APK_SIGNING_STORE_PASSWORD', '--key-pass', 'env:APK_SIGNING_KEY_PASSWORD',
             '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
             '--out', outgoing/'app.apk', apk])
    # Drop private values before verification/output. Temp cleanup also runs on exceptions.
    os.environ.pop('APK_SIGNING_STORE_PASSWORD', None)
    os.environ.pop('APK_SIGNING_KEY_PASSWORD', None)
    (outgoing/'request.json').write_text(json.dumps(item, sort_keys=True), encoding='utf-8')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('APK signing failed; private tool diagnostics withheld', file=sys.stderr)
        sys.exit(1)
