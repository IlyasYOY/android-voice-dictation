#!/usr/bin/env python3
"""Read-only APK verification. Never loads a keystore, signs, uploads or publishes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

LEGACY_CERT = '66088e33b7734f65bc26a0268c63f47875babe65c1a81558bc6f806e23b85a68'
PACKAGE = 'io.github.ilyasyoy.voicedictation'
LICENSE_ENTRIES = ('LICENSE', 'Apache-2.0.txt', 'MPL-2.0.txt', 'NOTICE.txt')


class VerificationError(ValueError):
    pass


def metadata(apk, revision):
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise VerificationError('Expected revision must be a full lowercase Git SHA')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        def bounded(name, limit):
            if names.count(name) != 1 or archive.getinfo(name).file_size > limit:
                raise VerificationError('Required APK metadata/license entry missing, duplicate or oversized')
            return archive.read(name)
        info = bounded('META-INF/version-control-info.textproto', 16384).decode('utf-8')
        found = re.findall(r'\brevision:\s*"([0-9a-f]{40})"', info)
        if found != [revision]:
            raise VerificationError('APK revision metadata does not match expected source')
        for name in LICENSE_ENTRIES:
            if not bounded('assets/licenses/' + name, 131072).strip():
                raise VerificationError('Empty license entry')
    return revision


def certificate(output, expected):
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise VerificationError('Expected certificate must be lowercase SHA-256')
    digests = re.findall(r'certificate SHA-256 digest:\s*([0-9a-f]{64})', output)
    if digests != [expected]:
        raise VerificationError('APK signer certificate mismatch or unexpected signer count')
    for scheme in ('v2', 'v3'):
        if not re.search(r'Verified using ' + scheme + r' scheme[^\n]*: true', output):
            raise VerificationError('Required APK signature scheme not verified')
    return expected


def manifest(output, code, name):
    values = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", output)
    if values is None or values.groups() != (PACKAGE, str(code), name):
        raise VerificationError('APK package/version mismatch')
    if 'application-debuggable' in output:
        raise VerificationError('Debuggable APK is not a release candidate')
    return {'package': PACKAGE, 'version_code': code, 'version_name': name}


def command(args):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True, timeout=60)
    if result.returncode:
        # Do not echo tool stderr or paths; archives/tool output can contain untrusted data.
        raise VerificationError('Android verification tool failed')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--version-code', required=True, type=int)
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--certificate', default=LEGACY_CERT)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    try:
        if not args.apk.is_file() or not 1 <= args.apk.stat().st_size <= 250_000_000:
            raise VerificationError('APK missing or outside the verification size limit')
        if args.report.exists() or args.report.resolve() == args.apk.resolve():
            raise VerificationError('Report destination already exists or names the APK')
        revision = metadata(args.apk, args.revision)
        signer = certificate(command([args.build_tools/'apksigner', 'verify', '--verbose', '--print-certs', args.apk]), args.certificate)
        values = manifest(command([args.build_tools/'aapt', 'dump', 'badging', args.apk]), args.version_code, args.version_name)
        command([args.build_tools/'zipalign', '-c', '-p', '4', args.apk])
        digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        report = {**values, 'source_revision_metadata': revision, 'signer_certificate_sha256': signer,
                  'apk_sha256': digest, 'bytes': args.apk.stat().st_size, 'signatures': ['v2', 'v3'],
                  'zipalign': True, 'license_entries_present': list(LICENSE_ENTRIES),
                  'scope': 'Verification of these bytes and declared metadata, not a reproducible-build or source-provenance attestation'}
        with args.report.open('x', encoding='utf-8') as target:
            json.dump(report, target, indent=2); target.write('\n')
        print(json.dumps(report))
        return 0
    except Exception as error:
        # Only fixed validation messages are emitted; underlying IO exception text is private.
        print(str(error) if isinstance(error, VerificationError) else 'Release verification failed', file=sys.stderr)
        return 1

if __name__ == '__main__':
    sys.exit(main())
