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
import unittest

from fallback_builder import parse_fallback_from_json


class FallbackBuilderTest(unittest.TestCase):

  def test_parse_fallback_invalid_lang(self):
    self.assertRaises(
        AssertionError, parse_fallback_from_json, """[{ "lang": [] }]"""
    )
    self.assertRaises(
        AssertionError, parse_fallback_from_json, """[{ "lang": 1 }]"""
    )
    self.assertRaises(
        AssertionError, parse_fallback_from_json, """[{ "lang": 0.5 }]"""
    )

  def test_parse_fallback_invalid_id(self):
    self.assertRaises(
        AssertionError, parse_fallback_from_json, """[{ "id": [] }]"""
    )
    self.assertRaises(
        AssertionError, parse_fallback_from_json, """[{ "id": 1 }]"""
    )
    self.assertRaises(
        AssertionError, parse_fallback_from_json, """[{ "id": 0.5 }]"""
    )

  def test_parse_fallback_invalid(self):
    self.assertRaises(
        AssertionError,
        parse_fallback_from_json,
        """[{ "lang": "ja", "id": "Roboto-Regular.ttf" }]""",
    )
    self.assertRaises(AssertionError, parse_fallback_from_json, """[]""")
    self.assertRaises(AssertionError, parse_fallback_from_json, """[{}]""")

  def test_parse_fallback(self):
    fallback = parse_fallback_from_json("""[
    { "lang": "und-Arab" },
    { "id": "NotoSansSymbols-Regular-Subsetted.ttf" },
    { "lang": "ja" }
    ]""")

    self.assertEqual(3, len(fallback))

    self.assertEqual("und-Arab", fallback[0].lang)
    self.assertIsNone(fallback[0].id)

    self.assertIsNone(fallback[1].lang)
    self.assertEqual("NotoSansSymbols-Regular-Subsetted.ttf", fallback[1].id)

    self.assertEqual("ja", fallback[2].lang)
    self.assertIsNone(fallback[2].id)


if __name__ == "__main__":
  unittest.main(verbosity=2)
