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

from family_builder import parse_family_from_json
from family_builder import parse_family_from_json_for_sanitization_test

_VALID_FONT_JSON = """[{ "file": "a.ttf", "weight": 400, "style": "normal" }]"""


class FamilyBuilderTest(unittest.TestCase):

  def test_parse_family_invalid_id(self):
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "id": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "id": 1 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "id": 0.5 }""",
    )

  def test_parse_family_invalid_lang(self):
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "lang": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "lang": 1 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "lang": 0.5 }""",
    )

  def test_parse_family_invalid_name(self):
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "name": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "name": 1 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "name": 0.5 }""",
    )

  def test_parse_family_invalid_variant(self):
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "variant": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "variant": 1 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "variant": 0.5 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "variant": "default" }""",
    )

  def test_parse_family_invalid_fallback_for(self):
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "fallbackFor": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "fallbackFor": 1 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json_for_sanitization_test,
        """{ "name": 0.5 }""",
    )

  def test_parse_invalid_family(self):
    # fallbackFor and target should be specified altogether
    self.assertRaises(
        AssertionError,
        parse_family_from_json,
        """{ "fallbackFor": "serif", "fonts": %s } """ % _VALID_FONT_JSON,
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json,
        """{ "target": "Roboto", "fonts": %s } """ % _VALID_FONT_JSON,
    )

    # Invalid fonts
    self.assertRaises(AssertionError, parse_family_from_json, """{} """)
    self.assertRaises(
        AssertionError,
        parse_family_from_json,
        """{ "fonts": [] } """,
    )
    self.assertRaises(
        AssertionError,
        parse_family_from_json,
        """{ "fonts": {} } """,
    )

  def test_parse_family(self):
    family = parse_family_from_json("""
    {
      "lang": "und-Arab",
      "variant": "compact",
      "fonts": [{
        "file": "NotoNaskhArabicUI-Regular.ttf",
        "postScriptName": "NotoNaskhArabicUI",
        "weight": "400",
        "style": "normal"
      }, {
        "file": "NotoNaskhArabicUI-Bold.ttf",
        "weight": "700",
        "style": "normal"
      }]
    }""")

    self.assertEqual("und-Arab", family.lang)
    self.assertEqual("compact", family.variant)
    self.assertEqual(2, len(family.fonts))
    self.assertIsNone(family.id)
    self.assertIsNone(family.name)
    self.assertIsNone(family.fallback_for)
    self.assertIsNone(family.target)

  def test_parse_family2(self):
    family = parse_family_from_json("""
    {
      "id": "NotoSansCJK_zh-Hans",
      "lang": "zh-Hans",
      "fonts": [{
        "file": "NotoSansCJK-Regular.ttc",
        "postScriptName": "NotoSansCJKJP-Regular",
        "weight": "400",
        "style": "normal",
        "supportedAxes": "wght",
        "axes": {
          "wght": "400"
        },
        "index": "2"
      }]
    }""")

    self.assertEqual("NotoSansCJK_zh-Hans", family.id)
    self.assertEqual("zh-Hans", family.lang)
    self.assertEqual(1, len(family.fonts))
    self.assertIsNone(family.name)
    self.assertIsNone(family.target)

  def test_parse_family3(self):
    family = parse_family_from_json("""
    {
      "lang": "zh-Hans",
      "fonts": [{
        "file": "NotoSerifCJK-Regular.ttc",
        "postScriptName": "NotoSerifCJKjp-Regular",
        "weight": "400",
        "style": "normal",
        "index": "2"
      }],
      "target": "NotoSansCJK_zh-Hans",
      "fallbackFor": "serif"
    }
    """)

    self.assertEqual("zh-Hans", family.lang)
    self.assertEqual(1, len(family.fonts))
    self.assertEqual("serif", family.fallback_for)
    self.assertEqual("NotoSansCJK_zh-Hans", family.target)
    self.assertIsNone(family.name)
    self.assertIsNone(family.variant)

  def test_parse_family4(self):
    family = parse_family_from_json("""
    {
      "name": "sans-serif",
      "fonts": [{
        "file": "Roboto-Regular.ttf",
        "supportedAxes": "wght,ital",
        "axes": {
          "wdth": "100"
        }
      }]
    }
    """)

    self.assertEqual("sans-serif", family.name)
    self.assertEqual(1, len(family.fonts))
    self.assertIsNone(family.lang)
    self.assertIsNone(family.fallback_for)
    self.assertIsNone(family.target)
    self.assertIsNone(family.variant)


if __name__ == "__main__":
  unittest.main(verbosity=2)
