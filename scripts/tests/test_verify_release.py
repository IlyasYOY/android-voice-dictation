import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('release_verifier', Path(__file__).parents[1] / 'verify-release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseVerificationTest(unittest.TestCase):
    revision = 'a' * 40
    def apk(self, root, revision=None, omit=None, duplicate=False):
        path = Path(root) / 'candidate.apk'
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('META-INF/version-control-info.textproto', 'revision: "' + (revision or self.revision) + '"')
            for name in release.LICENSE_ENTRIES:
                if name != omit:
                    z.writestr('assets/licenses/' + name, 'license fixture')
            if duplicate:
                z.writestr('META-INF/version-control-info.textproto', 'revision: "' + self.revision + '"')
        return path

    def test_source_mismatch_and_missing_license_are_rejected(self):
        for revision, omitted in [('b' * 40, None), (None, 'NOTICE.txt')]:
            with self.subTest(revision=revision, omitted=omitted), tempfile.TemporaryDirectory() as d:
                with self.assertRaises(ValueError):
                    release.metadata(self.apk(d, revision, omitted), self.revision)

    def test_valid_metadata_has_required_licenses(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(self.revision, release.metadata(self.apk(d), self.revision))

    def test_ambiguous_or_oversized_metadata_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertWarns(UserWarning):
                p = self.apk(d, duplicate=True)
            with self.assertRaises(ValueError):release.metadata(p, self.revision)
        with tempfile.TemporaryDirectory() as d:
            p = self.apk(d)
            with zipfile.ZipFile(p, 'a') as z:
                with self.assertWarns(UserWarning):z.writestr('assets/licenses/LICENSE', 'x' * 131073)
            with self.assertRaises(ValueError):release.metadata(p, self.revision)

    def test_wrong_or_multiple_signers_and_missing_scheme_are_rejected(self):
        base = 'Signer #1 certificate SHA-256 digest: ' + release.LEGACY_CERT + '\nVerified using v2 scheme: true\nVerified using v3 scheme: true\n'
        self.assertEqual(release.LEGACY_CERT, release.certificate(base, release.LEGACY_CERT))
        for output in [base.replace(release.LEGACY_CERT, 'b' * 64), base + base, base.replace('v3 scheme: true', 'v3 scheme: false')]:
            with self.assertRaises(ValueError):release.certificate(output, release.LEGACY_CERT)

    def test_debuggable_wrong_package_and_wrong_version_are_rejected(self):
        base = "package: name='" + release.PACKAGE + "' versionCode='42' versionName='0.19.2'"
        self.assertEqual(42, release.manifest(base, 42, '0.19.2')['version_code'])
        for output in [base + '\napplication-debuggable', base.replace(release.PACKAGE, 'other.app'), base.replace("'42'", "'43'")]:
            with self.assertRaises(ValueError):release.manifest(output, 42, '0.19.2')

if __name__ == '__main__':unittest.main()
