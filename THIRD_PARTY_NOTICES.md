# Third-party notices

Original project material is covered by [LICENSING.md](LICENSING.md).
Third-party components retain their own licenses.

## Gradle wrapper

The Gradle wrapper scripts and `android/gradle/wrapper/gradle-wrapper.jar` are
from Gradle and licensed under the Apache License, Version 2.0. Copyright notices
are retained in the wrapper scripts. A copy of the license is included at
[LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).
See [Gradle](https://github.com/gradle/gradle).

## Dependencies fetched during builds

Rust dependencies and versions are recorded in `desktop-tauri/Cargo.lock`;
Android dependencies are declared in the Gradle files. These libraries are fetched
by the build tools, not vendored in this source snapshot. Their individual license
and notice obligations still apply to redistributed application binaries.

Windows builds use Microsoft WebView2. The project's additional permission in
[LICENSING.md](LICENSING.md) addresses linking/combining with its loader and
runtime; Microsoft license terms still apply.

No public application binaries accompany this initial source snapshot. Before
publishing binaries, generate and review notices for the resolved dependency set,
include required license texts, and provide corresponding source for each release.
