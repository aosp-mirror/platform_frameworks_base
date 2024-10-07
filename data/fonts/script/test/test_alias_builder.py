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

from alias_builder import parse_alias_from_json


class AliasBuilderTest(unittest.TestCase):

  def test_parse_alias_invalid_name(self):
    self.assertRaises(
        AssertionError, parse_alias_from_json, """{ "name": [], "to": "to" }"""
    )
    self.assertRaises(
        AssertionError, parse_alias_from_json, """{ "name": 1, "to": "to" }"""
    )
    self.assertRaises(
        AssertionError, parse_alias_from_json, """{ "name": 0.5, "to": "to" }"""
    )

  def test_parse_alias_invalid_to(self):
    self.assertRaises(
        AssertionError,
        parse_alias_from_json,
        """{ "name": "name", "to": [] }""",
    )
    self.assertRaises(
        AssertionError, parse_alias_from_json, """{ "name": "name", "to": 1 }"""
    )
    self.assertRaises(
        AssertionError,
        parse_alias_from_json,
        """{ "name": "name", "to": 0.4 }""",
    )

  def test_parse_alias_invalid_id(self):
    self.assertRaises(
        AssertionError,
        parse_alias_from_json,
        """{ "name": "name", "to": "to", "weight": [] }""",
    )

  def test_parse_alias_invalid_to(self):
    self.assertRaises(
        AssertionError,
        parse_alias_from_json,
        """{ "name": "name", "to": "name", "weight": [] }""",
    )

  def test_parse_alias(self):
    alias = parse_alias_from_json("""
    {
      "name": "arial",
      "to": "sans-serif"
    }""")

    self.assertEqual("arial", alias.name)
    self.assertEqual("sans-serif", alias.to)
    self.assertIsNone(alias.weight)

  def test_parse_alias2(self):
    alias = parse_alias_from_json("""
    {
      "name": "sans-serif-thin",
      "to": "sans-serif",
      "weight": 100
    }""")

    self.assertEqual("sans-serif-thin", alias.name)
    self.assertEqual("sans-serif", alias.to)
    self.assertEqual(100, alias.weight)


if __name__ == "__main__":
  unittest.main(verbosity=2)
