#!/usr/bin/env python

#
# Copyright (C) 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import os
import sys
import tempfile
import unittest

import test_alias_builder
import test_commandline
import test_custom_json
import test_fallback_builder
import test_family_builder
import test_font_builder
import test_xml_builder

if __name__ == "__main__":
  loader = unittest.TestLoader()
  # TODO: can we load all tests from the directory?
  testsuite = unittest.suite.TestSuite()
  testsuite.addTest(loader.loadTestsFromModule(test_alias_builder))
  testsuite.addTest(loader.loadTestsFromModule(test_commandline))
  testsuite.addTest(loader.loadTestsFromModule(test_custom_json))
  testsuite.addTest(loader.loadTestsFromModule(test_fallback_builder))
  testsuite.addTest(loader.loadTestsFromModule(test_family_builder))
  testsuite.addTest(loader.loadTestsFromModule(test_font_builder))
  testsuite.addTest(loader.loadTestsFromModule(test_xml_builder))
  assert testsuite.countTestCases()
  unittest.TextTestRunner(verbosity=2).run(testsuite)
