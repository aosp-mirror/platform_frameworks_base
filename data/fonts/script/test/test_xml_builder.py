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

import random
import sys
import unittest

from alias_builder import parse_aliases_from_json
from commandline import CommandlineArgs
from fallback_builder import parse_fallback_from_json
from family_builder import parse_family_from_json
from xml_builder import FallbackOrder
from xml_builder import generate_xml

_SANS_SERIF = parse_family_from_json("""{
  "name": "sans-serif",
  "fonts": [{
    "file": "Roboto-Regular.ttf",
    "supportedAxes": "wght,ital",
    "axes": { "wdth": "100" }
  }]
}""")

_SERIF = parse_family_from_json("""{
  "name": "serif",
  "fonts": [{
    "file": "NotoSerif-Regular.ttf",
    "postScriptName": "NotoSerif",
    "weight": "400",
    "style": "normal"
  }, {
    "file": "NotoSerif-Bold.ttf",
    "weight": "700",
    "style": "normal"
  }, {
    "file": "NotoSerif-Italic.ttf",
    "weight": "400",
    "style": "italic"
  }, {
    "file": "NotoSerif-BoldItalic.ttf",
    "weight": "700",
    "style": "italic"
  }]
}""")

_ROBOTO_FLEX = parse_family_from_json("""{
  "name": "roboto-flex",
  "fonts": [{
    "file": "RobotoFlex-Regular.ttf",
    "supportedAxes": "wght",
    "axes": { "wdth": "100" }
  }]
}""")

_ARABIC = parse_family_from_json("""{
  "lang": "und-Arab",
  "variant": "elegant",
  "fonts": [{
    "file": "NotoNaskhArabic-Regular.ttf",
    "postScriptName": "NotoNaskhArabic",
    "weight": "400",
    "style": "normal"
  }, {
    "file": "NotoNaskhArabic-Bold.ttf",
    "weight": "700",
    "style": "normal"
  }]
}""")

_ARABIC_UI = parse_family_from_json("""{
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

_HANS = parse_family_from_json("""{
 "lang": "zh-Hans",
  "fonts": [{
    "file": "NotoSansCJK-Regular.ttc",
    "postScriptName": "NotoSansCJKJP-Regular",
    "weight": "400",
    "style": "normal",
    "supportedAxes": "wght",
    "axes": { "wght": "400" },
    "index": "2"
  }],
  "id": "NotoSansCJK_zh-Hans"
}""")

_JA = parse_family_from_json("""{
  "lang": "ja",
  "fonts": [{
    "file": "NotoSansCJK-Regular.ttc",
    "postScriptName": "NotoSansCJKJP-Regular",
    "weight": "400",
    "style": "normal",
    "supportedAxes": "wght",
    "axes": { "wght": "400" },
    "index": "0"
  }],
  "id": "NotoSansCJK_ja"
}""")

_JA_HENTAIGANA = parse_family_from_json("""{
  "lang": "ja",
  "priority": 100,
  "fonts": [{
    "file": "NotoSerifHentaigana.ttf",
    "postScriptName": "NotoSerifHentaigana-ExtraLight",
    "supportedAxes": "wght",
    "axes": { "wght": "400" }
  }]
}""")

_HANS_SERIF = parse_family_from_json("""{
  "lang": "zh-Hans",
  "fonts": [{
    "file": "NotoSerifCJK-Regular.ttc",
    "postScriptName": "NotoSerifCJKjp-Regular",
    "weight": "400",
    "style": "normal",
    "index": "2"
  }],
  "fallbackFor": "serif",
  "target": "NotoSansCJK_zh-Hans"
}""")

_JA_SERIF = parse_family_from_json("""{
  "lang": "ja",
  "fonts": [{
    "file": "NotoSerifCJK-Regular.ttc",
    "postScriptName": "NotoSerifCJKjp-Regular",
    "weight": "400",
    "style": "normal",
    "index": "0"
  }],
  "fallbackFor": "serif",
  "target": "NotoSansCJK_ja"
}""")

_FALLBACK = parse_fallback_from_json("""[
  { "lang": "und-Arab" },
  { "lang": "zh-Hans" },
  { "lang": "ja" }
]""")

_ALIASES = parse_aliases_from_json("""[
  {
    "name": "sans-serif-thin",
    "to" : "sans-serif",
    "weight": 100
  }
]""")


class FallbackOrderTest(unittest.TestCase):

  def test_fallback_order(self):
    order = FallbackOrder(_FALLBACK)

    # Arabic and Arabic UI are prioritized over Simplified Chinese
    self.assertTrue(order(_ARABIC) < order(_HANS))
    self.assertTrue(order(_ARABIC_UI) < order(_HANS))

    # Simplified Chinese is prioritized over Japanese
    self.assertTrue(order(_HANS) < order(_JA))

  def test_fallback_order_variant(self):
    order = FallbackOrder(_FALLBACK)

    # Arabic is prioritize over Arabic UI
    self.assertTrue(order(_ARABIC) < order(_ARABIC_UI))

  def test_fallback_order_unknown_priority(self):
    order = FallbackOrder(parse_fallback_from_json("""[
      { "lang": "zh-Hans" }
    ]"""))

    self.assertRaises(AssertionError, order, _ARABIC)

  def test_fallback_order_id_and_lang(self):
    order = FallbackOrder(_FALLBACK)

    # If both ID and lang matches the fallback, the ID is used.
    self.assertTrue(order(_HANS) < order(_JA))


class XmlBuilderTest(unittest.TestCase):

  def test_no_duplicate_families(self):
    self.assertRaises(
        AssertionError,
        generate_xml,
        fallback=_FALLBACK,
        aliases=[],
        families=[_SANS_SERIF, _ROBOTO_FLEX, _ROBOTO_FLEX],
    )

  def test_mandatory_sans_serif(self):
    self.assertRaises(
        AssertionError,
        generate_xml,
        fallback=_FALLBACK,
        aliases=[],
        families=[_ARABIC, _ARABIC_UI, _HANS, _JA],
    )

  def test_missing_fallback_target(self):
    # serif family is necessary for fallback.
    self.assertRaises(
        AssertionError,
        generate_xml,
        fallback=_FALLBACK,
        aliases=[],
        families=[_SANS_SERIF, _HANS_SERIF],
    )

    # target family is necessary for fallback.
    self.assertRaises(
        AssertionError,
        generate_xml,
        fallback=_FALLBACK,
        aliases=[],
        families=[_SANS_SERIF, _SERIF, _HANS_SERIF],
    )

  def test_missing_alias_target(self):
    self.assertRaises(
        AssertionError,
        generate_xml,
        fallback=_FALLBACK,
        aliases=parse_aliases_from_json("""[{
        "name": "serif-thin",
        "to" : "serif",
        "weight": 100
      }]"""),
        families=[_SANS_SERIF, _HANS_SERIF],
    )

  def test_duplicated_alias(self):
    self.assertRaises(
        AssertionError,
        generate_xml,
        fallback=_FALLBACK,
        aliases=parse_aliases_from_json("""[{
        "name": "serif-thin",
        "to" : "serif",
        "weight": 100
      },{
        "name": "serif-thin",
        "to" : "serif",
        "weight": 100
      }]"""),
        families=[_SANS_SERIF, _SERIF, _HANS_SERIF],
    )

  def test_same_priority(self):
    self.assertRaises(
        AssertionError,
        generate_xml,
        fallback=_FALLBACK,
        aliases=[],
        families=[_SANS_SERIF, _JA, _JA],
    )

  def test_generate_xml(self):
    xml = generate_xml(
        fallback=_FALLBACK,
        aliases=_ALIASES,
        families=[
            _SANS_SERIF,
            _SERIF,
            _ARABIC,
            _ARABIC_UI,
            _HANS,
            _HANS_SERIF,
            _JA,
            _JA_SERIF,
            _JA_HENTAIGANA,
        ],
    )

    self.expect_xml(xml)

  def test_generate_xml_reordered(self):
    families = [
        _SANS_SERIF,
        _SERIF,
        _ARABIC,
        _ARABIC_UI,
        _HANS,
        _HANS_SERIF,
        _JA,
        _JA_SERIF,
        _JA_HENTAIGANA,
    ]

    for i in range(0, 10):
      random.shuffle(families)
      xml = generate_xml(
          fallback=_FALLBACK, aliases=_ALIASES, families=families
      )

      self.expect_xml(xml)

  def expect_xml(self, xml):
    self.assertEquals("sans-serif", xml.families[0].name)  # _SANS_SERIF
    self.assertEquals("serif", xml.families[1].name)  # _SERIF
    self.assertEquals("und-Arab", xml.families[2].lang)  # __ARABIC
    self.assertEquals("elegant", xml.families[2].variant)
    self.assertEquals("und-Arab", xml.families[3].lang)  # _ARABIC_UI
    self.assertEquals("zh-Hans", xml.families[4].lang)  # _HANS (_HANS_SERIF)
    self.assertEquals(2, len(xml.families[4].fonts))
    self.assertEquals("serif", xml.families[4].fonts[1].fallback_for)
    self.assertEquals("ja", xml.families[5].lang)  # _HANS (_HANS_SERIF)
    self.assertEquals("serif", xml.families[5].fonts[1].fallback_for)


if __name__ == "__main__":
  unittest.main(verbosity=2)
