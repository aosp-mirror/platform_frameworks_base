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

import sys
import tempfile
import unittest

from custom_json import _load_json_with_comment


class JsonParseTest(unittest.TestCase):

  def test_json_with_comment(self):
    self.assertEqual(
        [],
        _load_json_with_comment("""
    // The line comment can be used in font JSON configuration.
    []
    """),
    )

  def test_json_with_comment_double_line_comment(self):
    self.assertEqual(
        [],
        _load_json_with_comment("""
    // The double line comment // should work.
    []
    """),
    )


if __name__ == "__main__":
  unittest.main(verbosity=2)
