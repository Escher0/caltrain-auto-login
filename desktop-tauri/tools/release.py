# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

"""Prepare release metadata locally. Does not generate keys, upload, or publish.

The public key is Tauri's base64-encoded .pub file. Private signing keys belong
only in TAURI_SIGNING_PRIVATE_KEY for the build, never in the config overlay.
"""
import argparse
import base64
import json
import re
import shutil
import tomllib
from pathlib import Path
from urllib.parse import quote


def validate(repository, version):
    if not re.fullmatch(r"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+", repository) or repository.split("/")[-1] in {".", ".."}:
        raise ValueError("Repository must be owner/name")
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("Version must be a stable major.minor.patch version")


def check_version(root, version):
    validate("local/check", version)
    root = Path(root)
    config = json.loads((root / "tauri.conf.json").read_text())
    cargo = tomllib.loads((root / "Cargo.toml").read_text())
    lock = tomllib.loads((root / "Cargo.lock").read_text())
    locked = [p["version"] for p in lock["package"] if p["name"] == cargo["package"]["name"]]
    if config["version"] != version or cargo["package"]["version"] != version or locked != [version]:
        raise ValueError("Release version must match tauri.conf.json, Cargo.toml and Cargo.lock")


def stage_installer(artifact, output, version, signed):
    validate("local/check", version)
    artifact = Path(artifact)
    destination = Path(output) / f"caltrain-auto-login_{version}_x64-setup.exe"
    if not artifact.is_file() or artifact.stat().st_size == 0 or artifact.suffix != ".exe":
        raise ValueError("Expected a nonempty Windows installer")
    shutil.copyfile(artifact, destination)
    if signed:
        shutil.copyfile(str(artifact) + ".sig", str(destination) + ".sig")
    return destination


def overlay(repository, version, public_key):
    validate(repository, version)
    public_key = public_key.strip()
    try:
        decoded = base64.b64decode(public_key, validate=True).decode("utf-8").splitlines()
        if len(decoded) != 2 or not decoded[0].startswith("untrusted comment:") or not re.fullmatch(r"[A-Za-z0-9+/]{56}", decoded[1]):
            raise ValueError()
    except (ValueError, UnicodeError) as error:
        raise ValueError("Expected a Tauri base64-encoded public key file") from error
    return {
        "version": version,
        "bundle": {"createUpdaterArtifacts": True},
        "plugins": {"updater": {
            "pubkey": public_key,
            "endpoints": [f"https://raw.githubusercontent.com/{repository}/updates/windows/stable.json"],
            "requireSignedVersion": True,
        }},
    }


def manifest(repository, version, artifact, notes):
    validate(repository, version)
    artifact = Path(artifact)
    signature_path = Path(str(artifact) + ".sig")
    if not artifact.is_file() or artifact.stat().st_size == 0:
        raise ValueError("Installer is missing or empty")
    if artifact.suffix != ".exe":
        raise ValueError("Expected a Windows NSIS .exe installer")
    if not signature_path.is_file() or not (signature := signature_path.read_text().strip()):
        raise ValueError("Updater signature is missing or empty")
    tag = f"caltrain-desktop-v{version}"
    return {"version": version, "notes": notes, "platforms": {
        "windows-x86_64": {
            "signature": signature,
            "url": f"https://github.com/{repository}/releases/download/{tag}/{quote(artifact.name, safe='')}",
        },
    }}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["config", "manifest", "check-version", "stage"])
    parser.add_argument("--repository", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--public-key", type=Path)
    parser.add_argument("--installer", type=Path)
    parser.add_argument("--notes", type=Path)
    parser.add_argument("--signed", action="store_true")
    args = parser.parse_args()
    check_version(args.project, args.version)
    if args.command == "check-version":
        return
    if not args.output:
        parser.error("config, manifest and stage require --output")
    if args.command == "stage":
        if not args.installer:
            parser.error("stage requires --installer")
        print(stage_installer(args.installer, args.output, args.version, args.signed))
        return
    if args.command == "config":
        if not args.public_key:
            parser.error("config requires --public-key")
        result = overlay(args.repository, args.version, args.public_key.read_text())
    else:
        if not args.installer:
            parser.error("manifest requires --installer")
        result = manifest(args.repository, args.version, args.installer,
                          args.notes.read_text() if args.notes else "")
    args.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
