# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

import copy
import json
import unittest

import publish


class FakeGitHub:
    """Only the external GitHub boundary is replaced. No network or real writes."""
    def __init__(self):
        self.events = []
        self.release = {"id": 7, "tag_name": "caltrain-desktop-v0.2.0", "draft": True,
                        "prerelease": False, "assets": []}
        self.manifest = {"version": "0.2.0", "notes": "A fix", "platforms": {
            "windows-x86_64": {"signature": "signature", "url":
                "https://github.com/a/b/releases/download/caltrain-desktop-v0.2.0/app_0.2.0_x64-setup.exe"}}}
        self.files = {"latest.json": b"", "app_0.2.0_x64-setup.exe": b"installer",
                      "app_0.2.0_x64-setup.exe.sig": b"signature\n",
                      "source.zip": b"source", "THIRD_PARTY_LICENSES.html": b"licenses",
                      "LICENSE": b"GPL", "LICENSING.md": b"additional permission"}
        self.current = None
        self.sha = None
        self.fail_write = False

    def get_release(self, tag):
        self.release["assets"] = [{"name": name, "size": max(1, len(body)), "state": "uploaded"}
                                  for name, body in self.files.items()]
        return copy.deepcopy(self.release)

    def download(self, asset):
        return json.dumps(self.manifest).encode() if asset["name"] == "latest.json" else self.files[asset["name"]]

    def read_feed(self):
        return self.current, self.sha

    def publish(self, release_id):
        self.events.append("publish")
        self.release["draft"] = False

    def write_feed(self, document, sha):
        self.events.append(("feed", sha))
        if self.fail_write:
            raise RuntimeError("conflict")
        self.current = document
        self.sha = "new-sha"


class PromotionTests(unittest.TestCase):
    def test_publishes_before_advertising_and_repeat_is_noop(self):
        github = FakeGitHub()
        publish.promote(github, "a/b", "0.2.0")
        self.assertEqual(github.events, ["publish", ("feed", None)])
        publish.promote(github, "a/b", "0.2.0")
        self.assertEqual(len(github.events), 2)

    def test_can_resume_after_publication_without_overwriting_assets(self):
        github = FakeGitHub()
        github.fail_write = True
        with self.assertRaises(RuntimeError):
            publish.promote(github, "a/b", "0.2.0")
        github.fail_write = False
        publish.promote(github, "a/b", "0.2.0")
        self.assertEqual(github.events, ["publish", ("feed", None), ("feed", None)])

    def test_preserves_existing_feed_sha_for_concurrent_update_guard(self):
        github = FakeGitHub()
        github.current = {"version": "0.1.9"}
        github.sha = "observed-sha"
        publish.promote(github, "a/b", "0.2.0")
        self.assertEqual(github.events[-1], ("feed", "observed-sha"))

    def test_rejects_downgrade_and_same_version_with_different_content(self):
        for version in ["0.10.0", "0.2.0"]:
            github = FakeGitHub()
            github.current = {"version": version}
            with self.subTest(version=version), self.assertRaises(ValueError):
                publish.promote(github, "a/b", "0.2.0")
            self.assertEqual(github.events, [])

    def test_rejects_wrong_tag_version_platform_url_signature_or_missing_asset_before_publish(self):
        cases = [
            lambda g: g.release.update(tag_name="caltrain-android-v0.2.0"),
            lambda g: g.release.update(prerelease=True),
            lambda g: g.manifest.update(version="0.2.1"),
            lambda g: g.manifest["platforms"].update(android={}),
            lambda g: g.manifest["platforms"]["windows-x86_64"].update(url="https://example.com/file.exe"),
            lambda g: g.manifest["platforms"]["windows-x86_64"].update(signature="wrong"),
            lambda g: g.files.pop("app_0.2.0_x64-setup.exe"),
            lambda g: g.files.pop("source.zip"),
        ]
        for mutate in cases:
            github = FakeGitHub()
            mutate(github)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                publish.promote(github, "a/b", "0.2.0")
            self.assertEqual(github.events, [])


if __name__ == "__main__":
    unittest.main()
