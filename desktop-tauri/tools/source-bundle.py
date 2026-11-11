# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

"""Archive committed source and locked Rust dependency sources, never local secrets."""
import argparse
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    root = Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory() as temp:
        temp = Path(temp)
        archive = temp / "committed.zip"
        subprocess.run(["git", "archive", "HEAD", "--format=zip", "-o", str(archive)], cwd=root, check=True)
        source = temp / "source"
        with zipfile.ZipFile(archive) as zipped:
            zipped.extractall(source)
        desktop = source / "desktop-tauri"
        result = subprocess.run(["cargo", "vendor", "--locked", "vendor"], cwd=desktop,
                                stdout=subprocess.PIPE, check=True)
        (desktop / ".cargo").mkdir(exist_ok=True)
        (desktop / ".cargo/config.toml").write_bytes(result.stdout)
        shutil.make_archive(str(output.with_suffix("")), "zip", source)


if __name__ == "__main__":
    main()
