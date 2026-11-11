# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

import unittest
from notices import apache_license


class NoticeTests(unittest.TestCase):
    def test_only_recognized_apache_license_declarations_are_accepted(self):
        for namespace in ["", ' xmlns="http://maven.apache.org/POM/4.0.0"']:
            apache_license(f'<project{namespace}><licenses><license><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses></project>')
        for url in ["https://example.com/licenses/LICENSE-2.0", "https://apache.org/proprietary", ""]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                apache_license(f'<project><licenses><license><url>{url}</url></license></licenses></project>')
        with self.assertRaises(ValueError):
            apache_license("<project/>")
