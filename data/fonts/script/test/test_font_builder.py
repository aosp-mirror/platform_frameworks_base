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

import json
import sys
import unittest

from font_builder import parse_font_from_json_for_sanitization_test, parse_fonts_from_json_for_validation_test


class FontBuilderTest(unittest.TestCase):

  def test_parse_font_invalid_file(self):
    # File must be string
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "file": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "file": -10 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "file": 0.5 }""",
    )

  def test_parse_font_invalid_weight(self):
    # Weight only accept integer or string as integer.
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "weight": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "weight": 0.5 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "weight": "0.5" }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "weight": -10 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "weight": 1001 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "weight": "-10" }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "weight": "1001" }""",
    )

  def test_parse_font_invalid_style(self):
    # Style only accept string "noromal" or "italic"
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "style": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "style": 0 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "style": "foo" }""",
    )

  def test_parse_font_invalid_index(self):
    # Index only accepts integer or string as integer that equals or larger than zero.
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "index": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "index": "foo" }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "index": -1 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "index": "-1" }""",
    )

  def test_parse_font_invalid_supportedAxes(self):
    # The supportedAxes only accepts wght or wght,ital.
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "supportedAxes": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "supportedAxes": 0 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "supportedAxes": 0.5 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "supportedAxes": "1" }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "supportedAxes": "ital" }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "supportedAxes": "wghtital" }""",
    )

  def test_parse_font_invalid_post_script_name(self):
    # The postScriptName only accepts string.
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "postScriptName": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "postScriptName": 1 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "postScriptName": 0.5 }""",
    )

  def test_parse_font_invalid_axes(self):
    # The axes accept OpenType tag to float value.
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "axes": [] }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "axes": "foo" }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "axes": 1 }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{
                        "axes":{
                          "wght": "ital"
                        }
                      }""",
    )
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{
                        "axes":{
                          "weight": 100
                        }
                      }""",
    )

  def test_parse_font_unknown_key(self):
    self.assertRaises(
        AssertionError,
        parse_font_from_json_for_sanitization_test,
        """{ "font": "Roboto-Regular.ttf" }""",
    )

  def test_parse_font_invalid_font(self):
    # empty fonts are not allowed
    self.assertRaises(
        AssertionError, parse_fonts_from_json_for_validation_test, """[]"""
    )
    # At least file should be specified
    self.assertRaises(
        AssertionError, parse_fonts_from_json_for_validation_test, """[{}]"""
    )
    # If supportedAxes is not spccified, weight and style should be specified.
    self.assertRaises(
        AssertionError,
        parse_fonts_from_json_for_validation_test,
        """[{
                        "file": "Roboto-Regular.ttf",
                        "weight": 400
                      }]""",
    )
    self.assertRaises(
        AssertionError,
        parse_fonts_from_json_for_validation_test,
        """[{
                        "file": "Roboto-Regular.ttf",
                        "style": "normal"
                      }]""",
    )

  def test_parse_font(self):
    fonts = parse_fonts_from_json_for_validation_test("""[
      {
        "file": "Roboto-Regular.ttf",
        "weight": 700,
        "style": "normal",
        "axes": {
          "wght": 700
        }
      }, {
        "file": "Roboto-Italic.ttf",
        "weight": 700,
        "style": "italic",
        "axes": {
          "wght": 700
        }
      }
    ]""")
    self.assertEqual(2, len(fonts))

    self.assertEqual("Roboto-Regular.ttf", fonts[0].file)
    self.assertEqual(700, fonts[0].weight)
    self.assertEqual("normal", fonts[0].style)
    self.assertEqual(1, len(fonts[0].axes))
    self.assertEqual(700, fonts[0].axes["wght"])
    self.assertIsNone(fonts[0].index)
    self.assertIsNone(fonts[0].supported_axes)
    self.assertIsNone(fonts[0].post_script_name)

    self.assertEqual("Roboto-Italic.ttf", fonts[1].file)
    self.assertEqual(700, fonts[1].weight)
    self.assertEqual("italic", fonts[1].style)
    self.assertEqual(1, len(fonts[1].axes))
    self.assertEqual(700, fonts[1].axes["wght"])
    self.assertIsNone(fonts[1].index)
    self.assertIsNone(fonts[1].supported_axes)
    self.assertIsNone(fonts[1].post_script_name)

  def test_parse_font2(self):
    fonts = parse_fonts_from_json_for_validation_test("""[
      {
        "file": "RobotoFlex-Regular.ttf",
        "supportedAxes": "wght",
        "axes": {
          "wdth": 100
        }
      }
    ]""")
    self.assertEqual(1, len(fonts))

    self.assertEqual("RobotoFlex-Regular.ttf", fonts[0].file)
    self.assertEqual(1, len(fonts[0].axes))
    self.assertEqual(100, fonts[0].axes["wdth"])
    self.assertIsNone(fonts[0].index)
    self.assertIsNone(fonts[0].weight)
    self.assertIsNone(fonts[0].style)
    self.assertIsNone(fonts[0].post_script_name)

  def test_parse_font3(self):
    fonts = parse_fonts_from_json_for_validation_test("""[
      {
        "file": "SourceSansPro-Regular.ttf",
        "weight": 400,
        "style": "normal"
      }, {
        "file": "SourceSansPro-Italic.ttf",
        "weight": 400,
        "style": "italic"
      }, {
        "file": "SourceSansPro-SemiBold.ttf",
        "weight": 600,
        "style": "normal"
      }, {
        "file": "SourceSansPro-SemiBoldItalic.ttf",
        "weight": 600,
        "style": "italic"
      }, {
        "file": "SourceSansPro-Bold.ttf",
        "weight": 700,
        "style": "normal"
      }, {
        "file": "SourceSansPro-BoldItalic.ttf",
        "weight": 700,
        "style": "italic"
      }
    ]""")

    self.assertEqual(6, len(fonts))

    self.assertEqual("SourceSansPro-Regular.ttf", fonts[0].file)
    self.assertEqual(400, fonts[0].weight)
    self.assertEqual("normal", fonts[0].style)

    self.assertEqual("SourceSansPro-Italic.ttf", fonts[1].file)
    self.assertEqual(400, fonts[1].weight)
    self.assertEqual("italic", fonts[1].style)

    self.assertEqual("SourceSansPro-SemiBold.ttf", fonts[2].file)
    self.assertEqual(600, fonts[2].weight)
    self.assertEqual("normal", fonts[2].style)

    self.assertEqual("SourceSansPro-SemiBoldItalic.ttf", fonts[3].file)
    self.assertEqual(600, fonts[3].weight)
    self.assertEqual("italic", fonts[3].style)

    self.assertEqual("SourceSansPro-Bold.ttf", fonts[4].file)
    self.assertEqual(700, fonts[4].weight)
    self.assertEqual("normal", fonts[4].style)

    self.assertEqual("SourceSansPro-BoldItalic.ttf", fonts[5].file)
    self.assertEqual(700, fonts[5].weight)
    self.assertEqual("italic", fonts[5].style)

  def test_parse_font4(self):
    fonts = parse_fonts_from_json_for_validation_test("""[
      {
        "file": "NotoSerifCJK-Regular.ttc",
        "postScriptName": "NotoSerifCJKjp-Regular",
        "weight": "400",
        "style": "normal",
        "index": "2"
      }
    ]""")
    self.assertEqual(1, len(fonts))

    self.assertEqual("NotoSerifCJK-Regular.ttc", fonts[0].file)
    self.assertEqual("NotoSerifCJKjp-Regular", fonts[0].post_script_name)
    self.assertEqual(400, fonts[0].weight)
    self.assertEqual("normal", fonts[0].style)
    self.assertEqual(2, fonts[0].index)


if __name__ == "__main__":
  unittest.main(verbosity=2)
