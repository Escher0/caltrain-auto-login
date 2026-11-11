# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

"""Collect declared licenses, upstream notices and sources for resolved libraries."""
import argparse
import hashlib
import html
import io
import json
import re
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from urllib.parse import urlparse


def apache_license(pom):
    root = ET.fromstring(pom)
    urls = [item.text for item in root.findall("{*}licenses/{*}license/{*}url")]
    if not any(url and urlparse(url).hostname in {"apache.org", "www.apache.org"}
               and urlparse(url).path.rstrip("/") in {"/licenses/LICENSE-2.0", "/licenses/LICENSE-2.0.txt"} for url in urls):
        raise ValueError("New dependency license requires review; expected Apache-2.0 in POM")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--sources", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    args.sources.mkdir(parents=True, exist_ok=True)
    document = ["<!doctype html><html lang='en'><meta charset='utf-8'><title>Dependency licenses</title>",
                "<h1>Caltrain Auto Login dependency licenses</h1>"]
    manifest = []
    dependencies = json.loads(args.dependencies.read_text())
    if not dependencies:
        raise ValueError("Resolved runtime dependencies are empty")
    for coordinate in dependencies:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+", coordinate):
            raise ValueError("Invalid dependency coordinate")
        group, artifact, version = coordinate.split(":")
        prefix = f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}"

        def fetch(suffix):
            with urllib.request.urlopen(prefix + suffix, timeout=60) as response:
                return response.read()

        pom = fetch(".pom")
        apache_license(pom)
        source = fetch("-sources.jar")
        basename = f"{group}.{artifact}-{version}"
        (args.sources / (basename + ".pom")).write_bytes(pom)
        (args.sources / (basename + "-sources.jar")).write_bytes(source)
        manifest.append({"coordinate": coordinate, "source_url": prefix + "-sources.jar",
                         "source_sha256": hashlib.sha256(source).hexdigest(), "license": "Apache-2.0"})
        document.append(f"<h2>{html.escape(coordinate)}</h2><p>Apache License 2.0. Source: {html.escape(prefix + '-sources.jar')}</p>")
        # Preserve notices from both source and binary archives, not just the POM.
        notices = set()
        for data in [source, fetch(".jar")]:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for name in archive.namelist():
                    if re.search(r"(^|/)(license|notice|copyright)([._-]|$)", name, re.I) and not name.endswith("/"):
                        notices.add(archive.read(name).decode("utf-8"))
        for notice in sorted(notices):
            document.append("<pre>" + html.escape(notice) + "</pre>")
    root = Path(__file__).resolve().parents[2]
    document.append("<h2>Apache License 2.0</h2><pre>" + html.escape((root / "LICENSES/Apache-2.0.txt").read_text()) + "</pre></html>")
    (args.output / "THIRD_PARTY_LICENSES.html").write_text("\n".join(document), encoding="utf-8")
    (args.sources / "dependencies.json").write_text(json.dumps(manifest, indent=2) + "\n")
    for name in ["LICENSE", "LICENSING.md"]:
        shutil.copyfile(root / name, args.output / name)


if __name__ == "__main__":
    main()
