# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

import unittest

import release


class AndroidReleaseTests(unittest.TestCase):
    def test_compiled_updater_repository_must_match_the_release_repository(self):
        field = '.field public static final UPDATE_REPOSITORY:Ljava/lang/String; = "Escher0/caltrain-auto-login"'
        release.check_repository(field, "Escher0/caltrain-auto-login")
        for dex in ["", field.replace("Escher0/caltrain-auto-login", ""), field.replace("Escher0", "someone-else")]:
            with self.subTest(dex=dex), self.assertRaises(ValueError):
                release.check_repository(dex, "Escher0/caltrain-auto-login")

    def candidate(self, version="0.1.5", code=6, certificate="a" * 64):
        return {"version": version, "version_code": code, "certificate_sha256": certificate,
                "application_id": "dev.brian.caltrainwifi"}

    def test_first_release_and_higher_version_code_with_same_key_are_allowed(self):
        release.check_upgrade(self.candidate(), [])
        release.check_upgrade(self.candidate("0.1.10", 10), [self.candidate("0.1.9", 9)])

    def test_prevents_downgrades_equal_versions_nonincreasing_codes_and_key_changes(self):
        previous = [self.candidate()]
        for version, code, key in [("0.1.4", 7, "a" * 64), ("0.1.5", 7, "a" * 64),
                                   ("0.1.6", 6, "a" * 64), ("0.1.6", 7, "b" * 64)]:
            with self.subTest(version=version, code=code, key=key), self.assertRaises(ValueError):
                release.check_upgrade(self.candidate(version, code, key), previous)

    def test_rejects_invalid_versions_codes_and_certificates(self):
        for document in [self.candidate("0.01.5"), self.candidate(code=0), self.candidate(code=True),
                         self.candidate(code=2100000001), self.candidate(certificate=""),
                         self.candidate("1.0.0-beta")]:
            with self.subTest(document=document), self.assertRaises(ValueError):
                release.check_upgrade(document, [])

    def test_apk_facts_must_match_identity_version_non_debuggable_and_certificate(self):
        facts = {"application_id": "dev.brian.caltrainwifi", "version": "0.1.5", "version_code": 6,
                 "debuggable": False, "certificate_sha256": "a" * 64}
        release.check_apk(facts, "0.1.5", "a" * 64)
        for field, value in [("application_id", "other.app"), ("version", "0.1.4"),
                             ("debuggable", True), ("certificate_sha256", "b" * 64)]:
            changed = dict(facts, **{field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.check_apk(changed, "0.1.5", "a" * 64)

    def test_rejects_multiple_or_missing_apk_signers(self):
        line = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        self.assertEqual(release.signer_digest(line), "a" * 64)
        for output in ["", line + "\nSigner #2 certificate SHA-256 digest: " + "b" * 64]:
            with self.assertRaises(ValueError):
                release.signer_digest(output)


if __name__ == "__main__":
    unittest.main()
