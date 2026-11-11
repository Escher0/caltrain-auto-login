# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

"""Promote a prepared Windows release, then atomically advance its update feed."""
import argparse
import base64
import json
import subprocess
from urllib.parse import quote, unquote

from release import validate


def promote(github, repository, version):
    validate(repository, version)
    tag = f"caltrain-desktop-v{version}"
    release = github.get_release(tag)
    if release["tag_name"] != tag or release["prerelease"]:
        raise ValueError("Expected the matching stable Windows release")
    assets = {a["name"]: a for a in release["assets"]}

    def require(name):
        asset = assets.get(name)
        if not asset or asset["size"] <= 0 or asset["state"] != "uploaded":
            raise ValueError(f"Release asset is missing, empty or incomplete: {name}")
        return asset

    manifest = json.loads(github.download(require("latest.json")))
    if manifest.get("version") != version or set(manifest.get("platforms", {})) != {"windows-x86_64"}:
        raise ValueError("Manifest version/platform does not match this Windows release")
    platform = manifest["platforms"]["windows-x86_64"]
    url = platform.get("url", "")
    prefix = f"https://github.com/{repository}/releases/download/{tag}/"
    name = unquote(url.removeprefix(prefix))
    if (not url.startswith(prefix) or url != prefix + quote(name, safe="")
            or "/" in name or "\\" in name or not name.endswith(f"_{version}_x64-setup.exe")):
        raise ValueError("Manifest URL must name this release's Windows x64 installer")
    require(name)
    signature = github.download(require(name + ".sig")).decode().strip()
    if not signature or signature != platform.get("signature"):
        raise ValueError("Manifest and installer signatures differ")
    for name in ["source.zip", "THIRD_PARTY_LICENSES.html", "LICENSE", "LICENSING.md"]:
        require(name)

    current, sha = github.read_feed()
    if current is not None:
        validate(repository, current["version"])
        old_version = tuple(map(int, current["version"].split(".")))
        new_version = tuple(map(int, version.split(".")))
        if old_version > new_version or (old_version == new_version and current != manifest):
            raise ValueError("Refusing to downgrade or replace an existing release version")
    if release["draft"]:
        github.publish(release["id"])
    if current != manifest:
        github.write_feed(manifest, sha)


class GitHub:
    def __init__(self, repository):
        self.root = f"repos/{repository}"

    def api(self, path, method="GET", data=None, binary=False, missing_ok=False):
        args = ["gh", "api", path, "--method", method]
        if binary:
            args += ["-H", "Accept: application/octet-stream"]
        if data is not None:
            args += ["--input", "-"]
        result = subprocess.run(args, input=json.dumps(data).encode() if data is not None else None,
                                capture_output=True, check=False)
        if result.returncode:
            if missing_ok and "(HTTP 404)" in result.stderr.decode():
                return None
            raise RuntimeError(result.stderr.decode().strip())
        return result.stdout if binary else json.loads(result.stdout)

    def get_release(self, tag):
        return self.api(f"{self.root}/releases/tags/{tag}")

    def download(self, asset):
        return self.api(f"{self.root}/releases/assets/{asset['id']}", binary=True)

    def read_feed(self):
        result = self.api(f"{self.root}/contents/windows/stable.json?ref=updates", missing_ok=True)
        if result is None:
            return None, None
        return json.loads(base64.b64decode(result["content"])), result["sha"]

    def publish(self, release_id):
        self.api(f"{self.root}/releases/{release_id}", "PATCH",
                 {"draft": False, "make_latest": "false"})

    def write_feed(self, manifest, sha):
        branch = self.api(f"{self.root}/git/ref/heads/updates", missing_ok=True)
        if branch is None:
            main = self.api(f"{self.root}/git/ref/heads/main")
            self.api(f"{self.root}/git/refs", "POST",
                     {"ref": "refs/heads/updates", "sha": main["object"]["sha"]})
        body = {"message": f"Release Windows {manifest['version']}", "branch": "updates",
                "content": base64.b64encode((json.dumps(manifest, indent=2) + "\n").encode()).decode()}
        if sha is not None:
            body["sha"] = sha
        self.api(f"{self.root}/contents/windows/stable.json", "PUT", body)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    validate(args.repository, args.version)
    promote(GitHub(args.repository), args.repository, args.version)
