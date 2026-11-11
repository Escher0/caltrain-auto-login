import base64
import tempfile
import unittest
from pathlib import Path

import release


class ReleaseTests(unittest.TestCase):
    def test_overlay_uses_selected_repository_and_requires_signed_versions(self):
        key = base64.b64encode(b"untrusted comment: test key\nRW" + b"a" * 54 + b"\n").decode()
        config = release.overlay("Example/caltrain", "0.2.0", key)
        self.assertEqual(config["version"], "0.2.0")
        self.assertTrue(config["bundle"]["createUpdaterArtifacts"])
        self.assertEqual(config["plugins"]["updater"]["pubkey"], key)
        self.assertTrue(config["plugins"]["updater"]["requireSignedVersion"])
        self.assertEqual(config["plugins"]["updater"]["endpoints"],
                         ["https://github.com/Example/caltrain/releases/latest/download/latest.json"])

    def test_rejects_bad_release_inputs(self):
        for repository in ["", "a", "a/b/c", "https://github.com/a/b", "a/b?x=y", "a/.."]:
            with self.subTest(repository=repository), self.assertRaises(ValueError):
                release.overlay(repository, "0.2.0", "key")
        for version in ["latest", "v0.2.0", "0.2", "0.2.0/evil", "01.2.0"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.overlay("a/b", version, "key")
        with self.assertRaises(ValueError):
            release.overlay("a/b", "0.2.0", "not a public key")

    def test_manifest_encodes_asset_name_and_preserves_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "Caltrain Auto Login_0.2.0_x64-setup.exe"
            artifact.write_bytes(b"test installer")
            signature = "signed-version-signature=="
            Path(str(artifact) + ".sig").write_text(signature + "\n")
            manifest = release.manifest("a/b", "0.2.0", artifact, "Fixes Wi-Fi reconnects.")
            platform = manifest["platforms"]["windows-x86_64"]
            self.assertEqual(platform["signature"], signature)
            self.assertEqual(platform["url"], "https://github.com/a/b/releases/download/caltrain-desktop-v0.2.0/Caltrain%20Auto%20Login_0.2.0_x64-setup.exe")
            self.assertEqual(manifest["notes"], "Fixes Wi-Fi reconnects.")
            self.assertEqual(manifest["version"], "0.2.0")

    def test_manifest_requires_nonempty_artifact_and_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "setup.exe"
            artifact.write_bytes(b"")
            with self.assertRaises(ValueError):
                release.manifest("a/b", "0.2.0", artifact, "")
            artifact.write_bytes(b"installer")
            with self.assertRaises(ValueError):
                release.manifest("a/b", "0.2.0", artifact, "")
            Path(str(artifact) + ".sig").write_text("\n")
            with self.assertRaises(ValueError):
                release.manifest("a/b", "0.2.0", artifact, "")


if __name__ == "__main__":
    unittest.main()
