import importlib.util
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "ci_apk_signing.py"
SPEC = importlib.util.spec_from_file_location("ci_apk_signing", SCRIPT)
signing = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(signing)


def source_run():
    return {
        "id": 123, "repository": {"id": 99, "full_name": "owner/app"},
        "head_repository": {"id": 99}, "status": "completed", "conclusion": "success",
        "path": ".github/workflows/android.yml", "event": "pull_request",
        "head_branch": "feature", "head_sha": "a" * 40,
        "pull_requests": [{"number": 16,
                           "head": {"sha": "a" * 40, "repo": {"id": 99}},
                           "base": {"ref": "main", "repo": {"id": 99}}}],
    }


class SourcePolicyTest(unittest.TestCase):
    def validate(self, run):
        return signing.validate_run(run, "owner/app", 99, "main")

    def test_same_repository_pr_receipt_uses_api_commit(self):
        receipt = self.validate(source_run())
        self.assertEqual(receipt["source_head_sha"], "a" * 40)
        self.assertEqual(receipt["pull_request"], 16)
        self.assertEqual(receipt["source_run_url"],
                         "https://github.com/owner/app/actions/runs/123")

    def test_default_branch_push_is_allowed(self):
        run = source_run()
        run.update(event="push", head_branch="main", pull_requests=[])
        self.assertIsNone(self.validate(run)["pull_request"])

    def test_rejects_other_repository_fork_failed_workflow_and_manual_run(self):
        edits = [
            {"repository": {"id": 99, "full_name": "attacker/app"}},
            {"repository": {"id": 100, "full_name": "owner/app"}},
            {"head_repository": {"id": 100}},
            {"head_repository": None},
            {"status": "in_progress"}, {"conclusion": "failure"},
            {"path": ".github/workflows/impostor.yml"}, {"event": "workflow_dispatch"},
            {"event": "pull_request_target"}, {"head_sha": "not-a-commit"},
            {"event": "push", "head_branch": "feature"},
            {"pull_requests": []},
        ]
        for edit in edits:
            with self.subTest(edit=edit):
                run = source_run()
                run.update(edit)
                with self.assertRaises(signing.SigningError):
                    self.validate(run)

    def test_rejects_fork_disguised_as_same_repository_run(self):
        for side in ("head", "base"):
            run = source_run()
            run["pull_requests"][0][side]["repo"]["id"] = 100
            with self.assertRaises(signing.SigningError):
                self.validate(run)

    def test_rejects_other_target_or_mismatching_pr_commit(self):
        for side, key, value in (("base", "ref", "release"),
                                 ("head", "sha", "b" * 40)):
            run = source_run()
            run["pull_requests"][0][side][key] = value
            with self.assertRaises(signing.SigningError):
                self.validate(run)


class ArtifactPolicyTest(unittest.TestCase):
    def test_only_the_apk_entry_is_read(self):
        with tempfile.TemporaryDirectory() as temp:
            archive, apk = Path(temp) / "data.zip", Path(temp) / "out.apk"
            with zipfile.ZipFile(archive, "w") as data:
                data.writestr("app-debug.apk", b"APK data")
            signing.unpack_apk(archive, apk)
            self.assertEqual(apk.read_bytes(), b"APK data")

    def test_rejects_traversal_scripts_extra_entries_and_symlinks(self):
        archives = [["../app-debug.apk"], ["app-debug.apk", "build.sh"],
                    ["app-debug.apk", "app-debug.apk"], ["run.sh"]]
        for entries in archives:
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as temp:
                archive, apk = Path(temp) / "data.zip", Path(temp) / "out.apk"
                with zipfile.ZipFile(archive, "w") as data:
                    with warnings.catch_warnings():
                        warnings.filterwarnings("ignore", message="Duplicate name:")
                        for entry in entries:
                            data.writestr(entry, b"data")
                with self.assertRaises(signing.SigningError):
                    signing.unpack_apk(archive, apk)
                self.assertFalse(apk.exists())
        with tempfile.TemporaryDirectory() as temp:
            archive, apk = Path(temp) / "data.zip", Path(temp) / "out.apk"
            with zipfile.ZipFile(archive, "w") as data:
                entry = zipfile.ZipInfo("app-debug.apk")
                entry.external_attr = 0o120777 << 16
                data.writestr(entry, b"/tmp/key")
            with self.assertRaises(signing.SigningError):
                signing.unpack_apk(archive, apk)

    def test_rejects_empty_or_oversized_apk(self):
        for content in (b"", b"12345"):
            with self.subTest(content=content), tempfile.TemporaryDirectory() as temp:
                archive, apk = Path(temp) / "data.zip", Path(temp) / "out.apk"
                with zipfile.ZipFile(archive, "w") as data:
                    data.writestr("app-debug.apk", content)
                with patch.object(signing, "MAX_APK_BYTES", 4):
                    with self.assertRaises(signing.SigningError):
                        signing.unpack_apk(archive, apk)


class IdentityPolicyTest(unittest.TestCase):
    def test_current_identity(self):
        identity = signing.apk_identity(
            f"package: name='{signing.EXPECTED_PACKAGE}' versionCode='31' versionName='0.15.0'")
        self.assertEqual(identity["version_code"], 31)

    def test_wrong_package_invalid_code_or_unsafe_version_is_rejected(self):
        for package, code, version in (("another.app", 31, "0.15.0"),
                                       (signing.EXPECTED_PACKAGE, 0, "0.0.0"),
                                       (signing.EXPECTED_PACKAGE, 31, "../../key")):
            with self.assertRaises(signing.SigningError):
                signing.apk_identity(
                    f"package: name='{package}' versionCode='{code}' versionName='{version}'")

    def test_rejects_wrong_or_multiple_signers_and_missing_v2(self):
        valid = (f"Signer #1 certificate SHA-256 digest: {signing.EXPECTED_CERT}\n"
                 "Verified using v2 scheme (APK Signature Scheme v2): true\n")
        signing.verify_certificate(valid)
        for output in (valid.replace(signing.EXPECTED_CERT, "b" * 64),
                       valid + f"Signer #2 certificate SHA-256 digest: {signing.EXPECTED_CERT}\n",
                       valid.replace(": true", ": false")):
            with self.assertRaises(signing.SigningError):
                signing.verify_certificate(output)

    def test_missing_secrets_cannot_fall_back_to_random_debug_key(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {}, clear=True):
            output = Path(temp) / "public"
            with self.assertRaises(signing.SigningError):
                signing.sign_apk(Path(temp) / "input.apk", output)
            self.assertFalse(output.exists())

    def test_wrong_key_is_rejected_before_signing_or_publishing(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "public"
            env = {"ANDROID_HOME": temp, "APK_SIGNING_STORE_PASSWORD": "test-value",
                   "APK_SIGNING_KEY_PASSWORD": "test-value"}
            badging = (f"package: name='{signing.EXPECTED_PACKAGE}' "
                       "versionCode='31' versionName='0.15.0'")
            responses = [type("Result", (), {"stdout": badging})(),
                         type("Result", (), {"stdout": b"different public certificate"})()]
            with patch.dict(os.environ, env, clear=True), \
                    patch.object(signing, "run_tool", side_effect=responses) as command:
                with self.assertRaises(signing.SigningError):
                    signing.sign_apk(Path(temp) / "input.apk", output,
                                     keystore=Path(temp) / "existing.keystore")
                self.assertEqual(command.call_count, 2)
                self.assertFalse(output.exists())


class WorkflowEntryPolicyTest(unittest.TestCase):
    def test_prepare_checks_download_digest_before_exposing_apk(self):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as data:
            data.writestr("app-debug.apk", b"APK data")
        blob = archive.getvalue()
        digest = hashlib.sha256(blob).hexdigest()
        for artifact_name, correct_digest in ((signing.BUILD_ARTIFACT, True),
                                              ("app-debug", True),
                                              (signing.BUILD_ARTIFACT, False)):
            with self.subTest(name=artifact_name, correct_digest=correct_digest), \
                    tempfile.TemporaryDirectory() as temp:
                event = Path(temp) / "event.json"
                event.write_text(json.dumps({"repository": {"default_branch": "main"},
                                            "workflow_run": {"id": 123}}))
                env = {"GITHUB_EVENT_NAME": "workflow_run", "GITHUB_EVENT_PATH": str(event),
                       "GITHUB_REPOSITORY": "owner/app", "GITHUB_REPOSITORY_ID": "99",
                       "GITHUB_REF": "refs/heads/main"}
                artifacts = {"total_count": 1, "artifacts": [{"id": 456,
                             "name": artifact_name, "expired": False,
                             "size_in_bytes": len(blob),
                             "digest": "sha256:" + (digest if correct_digest else "0" * 64)}]}
                destination = Path(temp) / "prepared"
                def download(args, **kwargs):
                    kwargs["stdout"].write(blob)
                with patch.dict(os.environ, env, clear=True), \
                        patch.object(signing, "github_json", side_effect=[source_run(), artifacts]), \
                        patch.object(signing, "run_tool", side_effect=download):
                    if correct_digest:
                        signing.prepare(destination)
                        self.assertEqual((destination / "app-debug.apk").read_bytes(), b"APK data")
                        receipt = json.loads((destination / "source.json").read_text())
                        self.assertEqual(receipt["source_head_sha"], "a" * 40)
                        self.assertEqual(receipt["source_artifact_sha256"], digest)
                    else:
                        with self.assertRaises(signing.SigningError):
                            signing.prepare(destination)
                        self.assertFalse((destination / "app-debug.apk").exists())

    def test_temporary_secret_file_is_removed_on_signing_failure(self):
        # Inert test bytes, not a generated key or credential.
        with tempfile.TemporaryDirectory() as temp:
            env = {"ANDROID_HOME": temp, "APK_SIGNING_STORE_PASSWORD": "test-value",
                   "APK_SIGNING_KEY_PASSWORD": "test-value",
                   "APK_SIGNING_KEYSTORE_BASE64": base64.b64encode(b"inert fixture").decode()}
            badging = (f"package: name='{signing.EXPECTED_PACKAGE}' "
                       "versionCode='31' versionName='0.15.0'")
            responses = [type("Result", (), {"stdout": badging})(),
                         type("Result", (), {"stdout": b"wrong public certificate"})()]
            output = Path(temp) / "public"
            with patch.dict(os.environ, env, clear=True), \
                    patch.object(signing, "run_tool", side_effect=responses) as command:
                with self.assertRaises(signing.SigningError):
                    signing.sign_apk(Path(temp) / "input.apk", output)
                certificate_args = command.call_args_list[1].args[0]
                key_path = Path(certificate_args[certificate_args.index("-keystore") + 1])
                self.assertFalse(key_path.exists())
                self.assertFalse(output.exists())

    def test_pull_request_cannot_call_prepare_directly(self):
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "pull_request"}, clear=True):
            with patch.object(signing, "github_json") as api:
                with self.assertRaises(signing.SigningError):
                    signing.prepare(Path("unused-output"))
                api.assert_not_called()

    def test_non_default_workflow_ref_is_rejected_before_api_calls(self):
        with tempfile.TemporaryDirectory() as temp:
            event = Path(temp) / "event.json"
            event.write_text('{"repository":{"default_branch":"main"}}')
            env = {"GITHUB_EVENT_NAME": "workflow_run", "GITHUB_EVENT_PATH": str(event),
                   "GITHUB_REPOSITORY": "owner/app", "GITHUB_REF": "refs/pull/16/merge"}
            with patch.dict(os.environ, env, clear=True):
                with patch.object(signing, "github_json") as api:
                    with self.assertRaises(signing.SigningError):
                        signing.prepare(Path(temp) / "output")
                    api.assert_not_called()


if __name__ == "__main__":
    unittest.main()
