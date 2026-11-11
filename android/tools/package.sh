#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.
set -euo pipefail

# Run from android/. Credentials are environment variables, never command arguments.
: "${RELEASE_REPOSITORY:?Set owner/repo}"
: "${RELEASE_VERSION:?Set major.minor.patch}"
: "${ANDROID_SIGNING_CERT_SHA256:?Set expected signing certificate digest}"
: "${RUNNER_TEMP:?Set temporary output directory}"

properties=(-PcaltrainRelease=true "-PcaltrainUpdateRepository=$RELEASE_REPOSITORY")
./gradlew :shared:test :app:testDebugUnitTest --console=plain
./gradlew :app:releaseDependencies "${properties[@]}" --console=plain
python3 tools/notices.py --dependencies app/build/release-dependencies.json \
  --output app/build/release-assets/licenses --sources "$RUNNER_TEMP/android-dependency-sources"
./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease "${properties[@]}" --console=plain

extra=()
if [[ "${CALTRAIN_OFFLINE_PACKAGE:-false}" == true ]]; then extra+=(--offline); fi
python3 tools/release.py prepare --repository "$RELEASE_REPOSITORY" --version "$RELEASE_VERSION" \
  --certificate "$ANDROID_SIGNING_CERT_SHA256" --apk app/build/outputs/apk/release/app-release.apk \
  --notices app/build/release-assets/licenses/THIRD_PARTY_LICENSES.html \
  --sources "$RUNNER_TEMP/android-dependency-sources" --output "$RUNNER_TEMP/android-release" "${extra[@]}"
