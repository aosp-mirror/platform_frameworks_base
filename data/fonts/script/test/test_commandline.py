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

import functools
import sys
import unittest

import commandline


class CommandlineTest(unittest.TestCase):

  def fileread(filemap, path):
    return filemap[path]

  def test_commandline(self):
    filemap = {}
    filemap["aliases.json"] = (
        """[{"name": "sans-serif-thin", "to": "sans-serif", "weight": 100}]"""
    )
    filemap["fallbacks.json"] = (
        """[{"lang": "und-Arab"},{"lang": "und-Ethi"}]"""
    )
    filemap["family.json"] = """[{
      "name": "sans-serif",
      "fonts": [{
        "file": "Roboto-Regular.ttf",
        "supportedAxes": "wght,ital",
        "axes": { "wdth": "100" }
      }]
    }, {
      "name": "sans-serif-condensed",
      "fonts": [{
        "file": "Roboto-Regular.ttf",
        "supportedAxes": "wght,ital",
        "axes": { "wdth": "75" }
      }]
    }]"""

    filemap["family2.json"] = """[{
      "name": "roboto-flex",
      "fonts": [{
        "file": "RobotoFlex-Regular.ttf",
        "supportedAxes": "wght",
        "axes": { "wdth": "100" }
      }]
    }]"""

    args = commandline.parse_commandline(
        [
            "-o",
            "output.xml",
            "--alias",
            "aliases.json",
            "--fallback",
            "fallbacks.json",
            "family.json",
            "family2.json",
        ],
        functools.partial(CommandlineTest.fileread, filemap),
    )

    self.assertEquals("output.xml", args.outfile)

    self.assertEquals(1, len(args.aliases))
    self.assertEquals("sans-serif-thin", args.aliases[0].name)
    self.assertEquals("sans-serif", args.aliases[0].to)
    self.assertEquals(100, args.aliases[0].weight)

    self.assertEquals(2, len(args.fallback))
    # Order is not a part of expectation. Check the expected lang is included.
    langs = set(["und-Arab", "und-Ethi"])
    self.assertTrue(args.fallback[0].lang in langs)
    self.assertTrue(args.fallback[1].lang in langs)

    self.assertEquals(3, len(args.families))
    # Order is not a part of expectation. Check the expected name is included.
    names = set(["sans-serif", "sans-serif-condensed", "roboto-flex"])
    self.assertTrue(args.families[0].name in names)
    self.assertTrue(args.families[1].name in names)
    self.assertTrue(args.families[2].name in names)


if __name__ == "__main__":
  unittest.main(verbosity=2)
