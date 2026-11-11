#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.
set -euo pipefail
umask 077
: "${RUNNER_TEMP:?Set temporary output directory}"
signing_directory=$(mktemp -d "$RUNNER_TEMP/android-signing.XXXXXX")
trap 'rm -rf "$signing_directory"' EXIT
export CALTRAIN_KEYSTORE="$signing_directory/test.p12"
CALTRAIN_STORE_PASSWORD=$(openssl rand -hex 24)
export CALTRAIN_STORE_PASSWORD
export CALTRAIN_KEY_PASSWORD="$CALTRAIN_STORE_PASSWORD"
export CALTRAIN_KEY_ALIAS=caltrain-ci
keytool -genkeypair -keystore "$CALTRAIN_KEYSTORE" -storetype PKCS12 -alias "$CALTRAIN_KEY_ALIAS" \
  -storepass:env CALTRAIN_STORE_PASSWORD -keypass:env CALTRAIN_KEY_PASSWORD -keyalg RSA -keysize 3072 \
  -validity 3 -dname 'CN=Disposable CI key' > /dev/null 2>&1
ANDROID_SIGNING_CERT_SHA256=$(keytool -exportcert -keystore "$CALTRAIN_KEYSTORE" \
  -alias "$CALTRAIN_KEY_ALIAS" -storepass:env CALTRAIN_STORE_PASSWORD | sha256sum | cut -d ' ' -f 1)
export ANDROID_SIGNING_CERT_SHA256
RELEASE_VERSION=$(python3 -c 'import pathlib,re; print(re.search(r"versionName = \"([^\"]+)\"", pathlib.Path("app/build.gradle.kts").read_text())[1])')
export RELEASE_VERSION
export CALTRAIN_OFFLINE_PACKAGE=true
bash tools/package.sh
