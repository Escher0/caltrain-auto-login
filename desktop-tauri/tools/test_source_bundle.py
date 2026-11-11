# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

import os
import tempfile
import unittest
import zipfile
from pathlib import Path

from source_bundle import append_vendor


class SourceArchiveTests(unittest.TestCase):
    def test_preserves_committed_files_and_accepts_cargo_epoch_timestamps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "source.zip"
            with zipfile.ZipFile(archive, "w") as zipped:
                entry = zipfile.ZipInfo("android/gradlew")
                entry.external_attr = 0o100755 << 16
                zipped.writestr(entry, b"wrapper")
            desktop = root / "desktop-tauri"
            (desktop / "vendor/dependency").mkdir(parents=True)
            (desktop / ".cargo").mkdir()
            source = desktop / "vendor/dependency/lib.rs"
            source.write_bytes(b"source")
            os.utime(source, (1, 1))
            (desktop / ".cargo/config.toml").write_text('directory = "vendor"')
            append_vendor(archive, desktop)
            with zipfile.ZipFile(archive) as zipped:
                self.assertEqual(zipped.read("desktop-tauri/vendor/dependency/lib.rs"), b"source")
                self.assertEqual(zipped.getinfo("android/gradlew").external_attr >> 16, 0o100755)

