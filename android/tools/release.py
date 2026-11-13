# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

"""Verify signed Android APKs and prepare or publish platform-specific releases."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

APP_ID = "dev.brian.caltrainwifi"


def version_tuple(value):
    if not isinstance(value, str) or not re.fullmatch(r"(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})", value):
        raise ValueError("Expected a stable major.minor.patch version")
    return tuple(map(int, value.split(".")))


def validate(document):
    version_tuple(document["version"])
    if type(document["version_code"]) is not int or not 0 < document["version_code"] <= 2100000000:
        raise ValueError("Invalid Android version code")
    if not re.fullmatch(r"[0-9a-f]{64}", document["certificate_sha256"]):
        raise ValueError("Expected a lowercase SHA-256 signing certificate digest")
    if document["application_id"] != APP_ID:
        raise ValueError("Unexpected application ID")


def check_upgrade(candidate, previous):
    validate(candidate)
    for old in previous:
        validate(old)
        if version_tuple(candidate["version"]) <= version_tuple(old["version"]):
            raise ValueError("Android version must increase")
        if candidate["version_code"] <= old["version_code"]:
            raise ValueError("Android versionCode must increase")
        if candidate["certificate_sha256"] != old["certificate_sha256"]:
            raise ValueError("Signing certificate changed; existing installations cannot upgrade")


def check_apk(facts, version, certificate):
    validate(facts)
    if facts["version"] != version or facts["debuggable"] is not False or facts["certificate_sha256"] != certificate:
        raise ValueError("APK version, release mode or signing certificate does not match")


def signer_digest(output):
    matches = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", output, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError("Expected exactly one APK signing certificate")
    return matches[0]


def check_repository(dex, repository):
    matches = re.findall(r'^\.field public static final UPDATE_REPOSITORY:Ljava/lang/String; = "([^"]*)"$', dex, re.MULTILINE)
    if matches != [repository]:
        raise ValueError("Compiled APK updater repository does not match the release repository")


def inspect_apk(apk, version, certificate, repository):
    sdk = Path(os.environ["ANDROID_HOME"])
    analyzers = sorted((sdk / "cmdline-tools").glob("*/bin/apkanalyzer"))
    if not analyzers:
        raise ValueError("Install Android command-line tools")
    analyzer = str(analyzers[-1])

    def manifest(field):
        return subprocess.check_output([analyzer, "manifest", field, str(apk)], text=True).strip()

    signer = sdk / "build-tools/35.0.0/apksigner"
    output = subprocess.check_output([str(signer), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    facts = {"application_id": manifest("application-id"), "version": manifest("version-name"),
             "version_code": int(manifest("version-code")), "debuggable": manifest("debuggable") != "false",
             "certificate_sha256": signer_digest(output)}
    check_apk(facts, version, certificate)
    dex = subprocess.check_output([analyzer, "dex", "code", "--class", APP_ID + ".BuildConfig", str(apk)], text=True)
    check_repository(dex, repository)
    facts["updater_repository"] = repository
    return facts


def gh_api(repository, endpoint, method="GET", data=None, binary=False):
    command = ["gh", "api", f"repos/{repository}/{endpoint}", "--method", method]
    if binary:
        command += ["-H", "Accept: application/octet-stream"]
    if data is not None:
        command += ["--input", "-"]
    output = subprocess.check_output(command, input=json.dumps(data).encode() if data is not None else None)
    return output if binary else json.loads(output)


def previous_releases(repository, excluding=None):
    output = subprocess.check_output(["gh", "api", f"repos/{repository}/releases?per_page=100", "--paginate", "--slurp"])
    previous = []
    for page in json.loads(output):
        for item in page:
            tag = item["tag_name"]
            if item["draft"] or item["prerelease"] or not tag.startswith("caltrain-android-v") or tag == excluding:
                continue
            version = tag.removeprefix("caltrain-android-v")
            try:
                version_tuple(version)
            except ValueError:
                continue
            metadata = next((a for a in item["assets"] if a["name"] == "android-release.json"), None)
            if not metadata:
                raise ValueError(f"Previous Android release lacks upgrade metadata: {tag}")
            document = json.loads(gh_api(repository, f"releases/assets/{metadata['id']}", binary=True))
            if document["version"] != version:
                raise ValueError("Previous release tag and metadata differ")
            previous.append(document)
    return previous


def prepare(args):
    facts = inspect_apk(args.apk, args.version, args.certificate, args.repository)
    if not args.offline:
        check_upgrade(facts, previous_releases(args.repository))
    args.output.mkdir(parents=True, exist_ok=True)
    name = f"caltrain-wifi-android-{args.version}.apk"
    shutil.copyfile(args.apk, args.output / name)
    facts["apk"] = {"name": name, "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest()}
    (args.output / "android-release.json").write_text(json.dumps(facts, indent=2) + "\n")
    root = Path(__file__).resolve().parents[2]
    for name in ["LICENSE", "LICENSING.md"]:
        shutil.copyfile(root / name, args.output / name)
    shutil.copyfile(args.notices, args.output / "THIRD_PARTY_LICENSES.html")
    subprocess.run(["git", "archive", "HEAD", "--format=zip", "-o", str((args.output / "source.zip").resolve())], cwd=root, check=True)
    with zipfile.ZipFile(args.output / "source.zip", "a", compression=zipfile.ZIP_DEFLATED, strict_timestamps=False) as archive:
        for source in sorted(args.sources.iterdir()):
            if source.is_file():
                archive.write(source, "android/dependency-sources/" + source.name)


def publish(args):
    tag = f"caltrain-android-v{args.version}"
    # The REST by-tag endpoint excludes drafts; gh resolves both draft and public tags.
    release_id = int(subprocess.check_output([
        "gh", "release", "view", tag, "--repo", args.repository,
        "--json", "databaseId", "--jq", ".databaseId"]))
    release = gh_api(args.repository, f"releases/{release_id}")
    if release["prerelease"] or release["tag_name"] != tag:
        raise ValueError("Expected the matching stable Android release")
    assets = {a["name"]: a for a in release["assets"]}
    apk_name = f"caltrain-wifi-android-{args.version}.apk"
    required = [apk_name, "android-release.json", "source.zip", "LICENSE", "LICENSING.md", "THIRD_PARTY_LICENSES.html"]
    for name in required:
        if name not in assets or assets[name]["state"] != "uploaded" or assets[name]["size"] <= 0:
            raise ValueError(f"Release asset is missing or incomplete: {name}")
    document = json.loads(gh_api(args.repository, f"releases/assets/{assets['android-release.json']['id']}", binary=True))
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / apk_name
        apk.write_bytes(gh_api(args.repository, f"releases/assets/{assets[apk_name]['id']}", binary=True))
        facts = inspect_apk(apk, args.version, args.certificate, args.repository)
        expected = dict(facts, apk={"name": apk_name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()})
        if expected != document:
            raise ValueError("Release metadata does not describe this signed APK")
    check_upgrade(document, previous_releases(args.repository, excluding=tag))
    if release["draft"]:
        gh_api(args.repository, f"releases/{release['id']}", "PATCH", {"draft": False, "make_latest": "false"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "publish"])
    parser.add_argument("--repository", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--certificate", required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--sources", type=Path)
    parser.add_argument("--notices", type=Path)
    parser.add_argument("--offline", action="store_true", help="Skip remote release history for local/PR packaging only")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+", args.repository) or args.repository.split("/")[-1] in {".", ".."}:
        parser.error("Repository must be owner/name")
    version_tuple(args.version)
    if args.command == "prepare":
        if not all([args.apk, args.output, args.sources, args.notices]):
            parser.error("prepare requires --apk, --output, --sources and --notices")
        prepare(args)
    else:
        publish(args)
