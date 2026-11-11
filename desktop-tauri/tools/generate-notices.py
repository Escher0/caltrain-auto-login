# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

"""Gather Rust licenses and the exact SDK loader's upstream license/notice."""
import argparse
import hashlib
import html
import io
import json
import subprocess
import urllib.request
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    subprocess.run(["cargo", "about", "generate", "tools/licenses.hbs", "--output-file", str(args.output)], check=True)
    metadata = json.loads(subprocess.check_output(["cargo", "metadata", "--locked", "--format-version", "1"]))
    crate = next(p for p in metadata["packages"] if p["name"] == "webview2-com-sys")
    # SDK version from webview2-rs edc2caf886175ccaebe86078c9cfe1ae2a187328.
    # Compare loader bytes, so a dependency update cannot silently reuse old notices.
    url = "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/1.0.3800.47/microsoft.web.webview2.1.0.3800.47.nupkg"
    with urllib.request.urlopen(url, timeout=60) as response:
        sdk = response.read()
    if hashlib.sha256(sdk).hexdigest() != "56c9f26bdd07916a2d1949fb58a5c7e434dfa1173577dca879206050c4e718db":
        raise ValueError("WebView2 SDK archive checksum changed")
    with zipfile.ZipFile(io.BytesIO(sdk)) as package:
        loader = Path(crate["manifest_path"]).parent / "x64/WebView2LoaderStatic.lib"
        if loader.read_bytes() != package.read("build/native/x64/WebView2LoaderStatic.lib"):
            raise ValueError("WebView2 loader changed; update and review its SDK notices")
        notices = "<h2>Microsoft WebView2 SDK 1.0.3800.47</h2>"
        for name in ["LICENSE.txt", "NOTICE.txt"]:
            notices += "<pre>" + html.escape(package.read(name).decode("utf-8-sig")) + "</pre>"
    document = args.output.read_text(encoding="utf-8")
    args.output.write_text(document.replace("</html>", notices + "</html>"), encoding="utf-8")


if __name__ == "__main__":
    main()
