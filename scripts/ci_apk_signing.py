#!/usr/bin/env python3
"""Sign CI APK data with the existing update identity, without running PR code."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import zipfile

EXPECTED_CERT = "66088e33b7734f65bc26a0268c63f47875babe65c1a81558bc6f806e23b85a68"
EXPECTED_PACKAGE = "io.github.ilyasyoy.voicedictation"
BUILD_ARTIFACT = "app-debug-build-only"
MAX_APK_BYTES = 128 * 1024 * 1024


class SigningError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise SigningError(message)


def validate_run(run, repository, repository_id, default_branch):
    """Use GitHub API metadata, never provenance supplied by a PR artifact."""
    require(run.get("repository", {}).get("full_name") == repository,
            "Build belongs to another repository")
    require(run["repository"].get("id") == repository_id,
            "Build repository ID differs")
    require((run.get("head_repository") or {}).get("id") == repository_id,
            "Fork builds cannot use the signing key")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "Only successful completed builds can be signed")
    require(run.get("path") == ".github/workflows/android.yml",
            "Unexpected source workflow")
    require(re.fullmatch(r"[0-9a-f]{40}", run.get("head_sha", "")),
            "Invalid source commit")
    event = run.get("event")
    pr_number = None
    if event == "push":
        require(run.get("head_branch") == default_branch,
                "Only pushes to the default branch can be signed")
    elif event == "pull_request":
        prs = run.get("pull_requests", [])
        require(len(prs) == 1, "Build must identify exactly one source PR")
        pr = prs[0]
        require(pr.get("head", {}).get("repo", {}).get("id") == repository_id
                and pr.get("base", {}).get("repo", {}).get("id") == repository_id,
                "PR must originate in this repository")
        require(pr["base"].get("ref") == default_branch,
                "PR must target the default branch")
        require(pr["head"].get("sha") == run["head_sha"],
                "PR source commit differs from build commit")
        require(isinstance(pr.get("number"), int) and pr["number"] > 0,
                "Invalid PR number")
        pr_number = pr["number"]
    else:
        raise SigningError("Unsupported build event")
    return {"repository": repository, "source_head_sha": run["head_sha"],
            "source_event": event, "pull_request": pr_number,
            "source_run_id": run["id"],
            "source_run_url": f"https://github.com/{repository}/actions/runs/{run['id']}"}


def run_tool(args, **kwargs):
    # Capture diagnostics: tools must never echo passwords or keystore data to CI.
    result = subprocess.run(args, stderr=subprocess.PIPE, check=False, **kwargs)
    require(result.returncode == 0, f"{Path(args[0]).name} failed; no APK published")
    return result


def github_json(repository, suffix):
    result = run_tool(["gh", "api", f"repos/{repository}/{suffix}"],
                      stdout=subprocess.PIPE)
    return json.loads(result.stdout)


def unpack_apk(archive, destination):
    with zipfile.ZipFile(archive) as data:
        require(data.namelist() == ["app-debug.apk"],
                "Build archive must contain only app-debug.apk")
        item = data.getinfo("app-debug.apk")
        require(not stat.S_ISLNK(item.external_attr >> 16)
                and not item.flag_bits & 1
                and 0 < item.file_size <= MAX_APK_BYTES,
                "Unsafe APK archive entry")
        # Read one exact entry as data. Never extract paths or run artifact content.
        destination.write_bytes(data.read(item))


def prepare(destination):
    require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_run",
            "Signing must run through the default-branch workflow_run listener")
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    repository = os.environ["GITHUB_REPOSITORY"]
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository),
            "Invalid repository name")
    default_branch = event["repository"]["default_branch"]
    require(os.environ.get("GITHUB_REF") == f"refs/heads/{default_branch}",
            "Signing workflow must come from the default branch")
    run_id = event["workflow_run"]["id"]
    require(isinstance(run_id, int) and run_id > 0, "Invalid build run ID")
    run = github_json(repository, f"actions/runs/{run_id}")
    source = validate_run(run, repository, int(os.environ["GITHUB_REPOSITORY_ID"]),
                          default_branch)
    require(run["id"] == run_id, "Build run ID differs")
    # Re-fetch the complete artifact list for exactly the validated run.
    artifacts = github_json(repository, f"actions/runs/{run_id}/artifacts?per_page=100")
    require(artifacts["total_count"] <= 100, "Unexpected artifact count")
    matches = [a for a in artifacts["artifacts"] if a["name"] == BUILD_ARTIFACT]
    require(len(matches) == 1, "Expected exactly one build-only APK artifact")
    artifact = matches[0]
    require(not artifact["expired"] and 0 < artifact["size_in_bytes"] <= MAX_APK_BYTES,
            "Build artifact expired or exceeds the size limit")
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", artifact.get("digest", "")),
            "Build artifact has no SHA-256 digest")
    destination.mkdir(parents=True, exist_ok=False)
    archive = destination / "build.zip"
    with archive.open("wb") as stream:
        run_tool(["gh", "api", f"repos/{repository}/actions/artifacts/{artifact['id']}/zip"],
                 stdout=stream)
    require(hashlib.sha256(archive.read_bytes()).hexdigest() == artifact["digest"][7:],
            "Build artifact digest differs")
    unpack_apk(archive, destination / "app-debug.apk")
    archive.unlink()
    source["source_artifact_id"] = artifact["id"]
    source["source_artifact_sha256"] = artifact["digest"][7:]
    (destination / "source.json").write_text(json.dumps(source, indent=2) + "\n")
    print(f"Validated source {source['source_head_sha']} from run {run_id}")


def apk_identity(badging):
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' "
                      r"versionName='([^']+)'", badging, re.MULTILINE)
    require(match is not None, "APK identity cannot be read")
    package, code, version = match.groups()
    require(package == EXPECTED_PACKAGE, "Unexpected APK package")
    require(int(code) > 0, "APK versionCode must be positive")
    require(re.fullmatch(r"[A-Za-z0-9.+_-]{1,80}", version), "Invalid APK version name")
    return {"package": package, "version_code": int(code), "version_name": version}


def verify_certificate(output):
    certs = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$",
                       output, re.MULTILINE)
    require(certs == [EXPECTED_CERT], "APK update signing certificate differs")
    require("Verified using v2 scheme (APK Signature Scheme v2): true" in output,
            "APK v2 signature is missing")


def sign_apk(apk, destination, source_file=None, keystore=None):
    for name in ("APK_SIGNING_STORE_PASSWORD", "APK_SIGNING_KEY_PASSWORD"):
        require(bool(os.environ.get(name)), f"Missing {name}; no fallback signing")
    require(keystore is not None or bool(os.environ.get("APK_SIGNING_KEYSTORE_BASE64")),
            "Missing APK_SIGNING_KEYSTORE_BASE64; no fallback signing")
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
    badging = run_tool([str(sdk / "aapt"), "dump", "badging", str(apk)],
                       stdout=subprocess.PIPE, text=True).stdout
    identity = apk_identity(badging)
    require(not destination.exists(), "Output directory must not already exist")
    with tempfile.TemporaryDirectory(prefix="apk-signing-") as temp:
        private = Path(temp)
        if keystore is None:
            # This temporary file stays outside checkout, caches and artifact paths.
            keystore = private / "update.keystore"
            try:
                key_data = base64.b64decode(os.environ["APK_SIGNING_KEYSTORE_BASE64"],
                                           validate=True)
            except ValueError as error:
                raise SigningError("Invalid signing secret encoding") from error
            with keystore.open("xb") as stream:
                os.chmod(keystore, 0o600)
                stream.write(key_data)
            del key_data
        # Export only the public certificate into memory to check identity before use.
        certificate = run_tool([
            "keytool", "-exportcert", "-keystore", str(keystore),
            "-alias", "androiddebugkey", "-storepass:env", "APK_SIGNING_STORE_PASSWORD"
        ], stdout=subprocess.PIPE).stdout
        require(hashlib.sha256(certificate).hexdigest() == EXPECTED_CERT,
                "Configured key has the wrong update certificate")
        run_tool([str(sdk / "zipalign"), "-c", "-P", "16", "4", str(apk)],
                 stdout=subprocess.PIPE)
        candidate = private / "signed.apk"
        run_tool([
            str(sdk / "apksigner"), "sign", "--ks", str(keystore),
            "--ks-key-alias", "androiddebugkey",
            "--ks-pass", "env:APK_SIGNING_STORE_PASSWORD",
            "--key-pass", "env:APK_SIGNING_KEY_PASSWORD",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "false", "--v4-signing-enabled", "false",
            "--out", str(candidate), str(apk)
        ], stdout=subprocess.PIPE)
        verification = run_tool([str(sdk / "apksigner"), "verify", "--verbose",
                                 "--print-certs", str(candidate)],
                                stdout=subprocess.PIPE, text=True).stdout
        verify_certificate(verification)
        # Nothing is exposed to the upload step until every validation has passed.
        signed_bytes = candidate.read_bytes()
        receipt = json.loads(source_file.read_text()) if source_file else {}
        receipt.update(identity)
        receipt.update({"certificate_sha256": EXPECTED_CERT,
                        "apk_sha256": hashlib.sha256(signed_bytes).hexdigest(),
                        "signing_workflow_sha": os.environ.get("GITHUB_SHA")})
        destination.mkdir(parents=True)
        name = f"voice-dictation-{identity['version_name']}-{identity['version_code']}.apk"
        (destination / name).write_bytes(signed_bytes)
        (destination / "SHA256SUMS").write_text(f"{receipt['apk_sha256']}  {name}\n")
        (destination / "build-info.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Verified update APK {identity['version_name']} ({identity['version_code']})")
    print(f"Certificate SHA-256: {EXPECTED_CERT}")


def summary(directory):
    receipt = json.loads((directory / "build-info.json").read_text())
    url = os.environ["APK_ARTIFACT_URL"]
    repository = receipt["repository"]
    require(re.fullmatch(rf"https://github\.com/{re.escape(repository)}/actions/"
                         r"runs/[0-9]+/artifacts/[0-9]+", url), "Unexpected artifact URL")
    sha = receipt["source_head_sha"]
    text = (f"[Download update APK (ZIP)]({url})\n\n"
            f"Version: {receipt['version_name']} ({receipt['version_code']})\n\n"
            f"Source head: [{sha}](https://github.com/{repository}/commit/{sha})\n\n"
            f"[Successful source checks]({receipt['source_run_url']})\n\n"
            f"Certificate SHA-256: `{EXPECTED_CERT}`\n\n"
            f"APK SHA-256: `{receipt['apk_sha256']}`\n\n"
            "Keep the installed app and its data. This APK uses the existing update key.\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
        stream.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--destination", type=Path, required=True)
    signing = sub.add_parser("sign")
    signing.add_argument("--apk", type=Path, required=True)
    signing.add_argument("--destination", type=Path, required=True)
    signing.add_argument("--source", type=Path)
    signing.add_argument("--keystore", type=Path,
                         help="Existing local key; CI uses the approved environment secret")
    report = sub.add_parser("summary")
    report.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args.destination)
        elif args.command == "sign":
            sign_apk(args.apk, args.destination, args.source, args.keystore)
        else:
            summary(args.directory)
    except SigningError as error:
        # These messages are fixed validation text, never raw tool/input output.
        print(f"APK signing failed: {error}. No fallback APK will be uploaded.",
              file=sys.stderr)
        return 1
    except (KeyError, ValueError, OSError, zipfile.BadZipFile):
        # Fail closed, without echoing subprocess diagnostics, secrets or input data.
        print("APK signing failed; check trusted source, required secrets and certificate. "
              "No fallback APK will be uploaded.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
