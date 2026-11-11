# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

"""Exercise the real Tauri signer and release verifier with disposable keys."""
import os
import subprocess
import tempfile
from pathlib import Path


def main():
    npm = "npm.cmd" if os.name == "nt" else "npm"
    cli = [npm, "exec", "--yes", "--package=@tauri-apps/cli@2.12.4", "--", "tauri", "signer"]
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        key = root / "test.key"
        env = dict(os.environ, TAURI_SIGNING_PRIVATE_KEY_PASSWORD="")
        # Key generation prints secrets; discard output rather than leaking to CI logs.
        subprocess.run(cli + ["generate", "--ci", "-w", str(key)], env=env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        artifact = root / "setup.exe"
        artifact.write_bytes(b"signature integration test")
        env["TAURI_SIGNING_PRIVATE_KEY"] = key.read_text().strip()
        subprocess.run(cli + ["sign", "--app-version", "0.2.0", str(artifact)], env=env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        verify = ["cargo", "run", "--locked", "--example", "verify-update", "--", str(artifact),
                  str(artifact) + ".sig", str(key) + ".pub"]
        subprocess.run(verify + ["0.2.0"], check=True)
        if subprocess.run(verify + ["0.2.1"], capture_output=True).returncode == 0:
            raise AssertionError("Verifier accepted a different signed version")
        artifact.write_bytes(b"tampered installer")
        if subprocess.run(verify + ["0.2.0"], capture_output=True).returncode == 0:
            raise AssertionError("Verifier accepted a tampered installer")
        print("Real signer/verifier: valid signature accepted; wrong version and tampering rejected.")


if __name__ == "__main__":
    main()
