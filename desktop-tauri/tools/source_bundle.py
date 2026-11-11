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


def append_vendor(archive, desktop):
    # Cargo packages may use Unix epoch timestamps. ZIP starts at 1980.
    # Retain git archive's original entries, including executable permissions.
    with zipfile.ZipFile(archive, "a", compression=zipfile.ZIP_DEFLATED, strict_timestamps=False) as zipped:
        for source in sorted((desktop / "vendor").rglob("*")):
            if source.is_file():
                zipped.write(source, "desktop-tauri/" + source.relative_to(desktop).as_posix())
        zipped.write(desktop / ".cargo/config.toml", "desktop-tauri/.cargo/config.toml")


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
        shutil.copyfile(archive, output)
        append_vendor(output, desktop)


if __name__ == "__main__":
    main()
