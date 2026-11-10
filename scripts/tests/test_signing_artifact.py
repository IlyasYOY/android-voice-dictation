"""Real SDK regression for the signer -> trusted artifact consumer boundary.

All APK/key inputs are generated synthetic fixtures, never distribution keys.
"""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'release'))
import apk
import fetch_artifact


class SigningArtifactTest(unittest.TestCase):
    def test_real_signing_output_round_trips_through_strict_artifact_reader(self):
        sdk = os.environ.get('ANDROID_HOME')
        if not sdk:
            self.skipTest('Set ANDROID_HOME to run the SDK signing regression')
        tools = Path(sdk)/'build-tools/35.0.0'
        for name in ('aapt', 'zipalign', 'apksigner'):
            self.assertTrue((tools/name).is_file(), f'Missing pinned SDK tool: {name}')
        self.assertIsNotNone(shutil.which('keytool'), 'JDK keytool is required')
        with tempfile.TemporaryDirectory(prefix='synthetic-signing-') as directory:
            root = Path(directory)
            env = dict(os.environ, RUNNER_TEMP=directory, GITHUB_RUN_ID='synthetic',
                       GITHUB_RUN_ATTEMPT='1', SYNTHETIC_PASSWORD=secrets.token_urlsafe(32))

            def run(args):
                result = subprocess.run([str(x) for x in args], capture_output=True, text=True,
                                        env=env, timeout=90)
                self.assertEqual(0, result.returncode, result.stderr[-1000:])

            # Compile a tiny valid manifest instead of depending on a previous
            # Gradle build, a checked-in APK, or an owner's signing material.
            manifest = root/'AndroidManifest.xml'
            manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                                'package="io.github.ilyasyoy.voicedictation" android:versionCode="43" '
                                'android:versionName="0.19.2-pr.31.43">'
                                '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>'
                                '<application android:label="Synthetic signing test"/></manifest>')
            unsigned = root/'unsigned.apk'
            run([tools/'aapt', 'package', '-f', '-M', manifest, '-I',
                 Path(sdk)/'platforms/android-36/android.jar', '-F', unsigned])
            with zipfile.ZipFile(unsigned, 'a') as archive:
                archive.writestr('META-INF/version-control-info.textproto', 'revision: "' + 'a'*40 + '"')
                for name in apk.verifier.LICENSE_ENTRIES:
                    archive.writestr('assets/licenses/' + name, 'Synthetic license fixture')
            incoming = root/'incoming'
            incoming.mkdir()
            run([tools/'zipalign', '-p', '4', unsigned, incoming/'app.apk'])
            key, certificate = root/'synthetic.p12', root/'certificate.der'
            run(['keytool', '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
                 '-alias', 'synthetic', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1',
                 '-dname', 'CN=Synthetic Artifact Regression',
                 '-storepass:env', 'SYNTHETIC_PASSWORD', '-keypass:env', 'SYNTHETIC_PASSWORD'])
            run(['keytool', '-exportcert', '-keystore', key, '-alias', 'synthetic',
                 '-storepass:env', 'SYNTHETIC_PASSWORD', '-file', certificate])
            fingerprint = hashlib.sha256(certificate.read_bytes()).hexdigest()
            item = dict(mode='pr', source='a'*40, controller='b'*40, code=43,
                        name='0.19.2-pr.31.43', pr=31, run='synthetic', attempt='1', certificate=fingerprint)
            (incoming/'request.json').write_text(json.dumps(item))
            env.update(VD_ITEM=json.dumps(item), VD_CERTIFICATE=fingerprint,
                       APK_SIGNING_KEYSTORE_BASE64=base64.b64encode(key.read_bytes()).decode(),
                       APK_SIGNING_STORE_PASSWORD=env['SYNTHETIC_PASSWORD'],
                       APK_SIGNING_KEY_PASSWORD=env['SYNTHETIC_PASSWORD'], APK_SIGNING_KEY_ALIAS='synthetic')
            signed = root/'signed'
            run([sys.executable, ROOT/'release/sign.py', incoming, signed])
            # Package every produced member, as the workflow's signed/* does.
            # The consumer must accept the result without relaxing its allowlist.
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, 'w') as archive:
                for member in sorted(signed.iterdir()):
                    archive.writestr(member.name, member.read_bytes())
            extracted = root/'extracted'
            fetch_artifact.extract(stream.getvalue(), extracted, ('app.apk', 'request.json'))
            self.assertEqual({'app.apk', 'request.json'}, {p.name for p in signed.iterdir()})
            self.assertEqual(item, json.loads((extracted/'request.json').read_text()))
            report = apk.verify(extracted/'app.apk', item, tools, signed=True)
            self.assertEqual(fingerprint, report['certificate_sha256'])
            self.assertEqual(43, report['version_code'])
            apk.tool([tools/'zipalign', '-c', '-p', '4', extracted/'app.apk'])


if __name__ == '__main__': unittest.main()
