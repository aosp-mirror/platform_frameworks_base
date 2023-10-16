# Copyright 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import pkgutil
import unittest
import xml.etree.ElementTree

class TestLinterReports(unittest.TestCase):
    """Integration tests for the linters used by @EnforcePermission."""

    def _read_report(self, pkg_path):
        report = pkgutil.get_data(pkg_path, "lint/lint-report.xml").decode()
        issues = xml.etree.ElementTree.fromstring(report)
        self.assertEqual(issues.tag, "issues")
        return issues

    def test_no_aidl(self):
        issues = self._read_report("no_aidl")
        self.assertEqual(len(issues), 1)

        issue = issues[0]
        self.assertEqual(issue.attrib["id"], "MisusingEnforcePermissionAnnotation")
        self.assertEqual(issue.attrib["severity"], "Error")

    def test_missing_annotation(self):
        issues = self._read_report("missing_annotation")
        self.assertEqual(len(issues), 1)

        issue = issues[0]
        self.assertEqual(issue.attrib["id"], "MissingEnforcePermissionAnnotation")
        self.assertEqual(issue.attrib["severity"], "Error")


if __name__ == '__main__':
    unittest.main(verbosity=2)
