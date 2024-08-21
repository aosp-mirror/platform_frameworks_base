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

"""A main module for generating XML from font config JSONs.

The following is a JSON format of the font configuration.

[  // Top level element is a list to be able to hold multiple families
    { // Dict for defining single family entry

        // Optional String: unique identifier.
        // This can be used for identifying this family instance.
        // Currently this is ued only for specifying the target of the fallback
        // family.
        "id": "Roboto",

        // Optional String: name of this family if this family creates a new
        // fallback. If multiple families define the same name, it is a build
        // error.
        "name": "sans-serif",

        // Optional String: language tag of this family if this family is a
        // fallback family. Only language tags declared in fallback_order.json
        // can be used. Specifying unknown language tags is a build error.
        "lang": "und-Latn",

        // Optional String: variant of the family
        // Currently only “compact”, “elegant” are supported.
        "variant": "compact",

        // Optional String: specify the fallback target used for this family.
        // If this key is specified, "target" attribute must also be specified.
        // If this key is specified, "name" and "lang" must not be specified.
        // If the specified fallback target is not defined, it is a build error.
        "fallbackFor": "roboto-flex",

        // Optional String: specify the family target to include this family.
        // If this key is specified, "fallbackFor" attribute must also be
        // specified. If this key is specified, "name" and "lang" must not be
        // specified. If the specified family target is not defined, it is a
        // build error.
        "target": "RobotoMain",

        // Optional Integer: specify the priority of the family.
        // The priority order is determined by fallback_order.json.
        // This priority is only used when two or more font families are
        // assigned to the same rank: e.g. NotoColorEmoji.ttf and
        // NotoColorEmojiFlags.ttf.
        // All families have priority 0 by default and any value from -100 to
        // 100 is valid. Lowering priority value increases the priority.
        "priority": 0,

        // Mandatory List: specify list of fonts. At least one font is required.
        "fonts": [
            {  // Dict for defining a single font entry.

                // Mandatory String: specify font file name in the system.
                // This must be the file name in the system image.
                "file": "Roboto-Regular.ttf",

                // Optional String: specify the PostScript name of the font.
                // This can be optional if the filename without extension is the
                // same as the PostScript name.
                "postScriptName": "Roboto",

                // Optional String: specify weight of the font.
                "weight": "100",

                // Optional String: specify style of the font.
                // Currently, only "normal" or "italic" is supported.
                "style": "normal",

                // Optional String: specify supported axes for automatic
                // adjustment. Currently, only "wght" or "wght,ital" is
                // supported.
                "supportedAxes": "wght"

                // Optional Dict: specify variation settings for this font.
                "axes": {
                    // Optional key to float dictionaty entry for speicying axis
                    // values.
                    "wdth": 100.0,
                }
            },
        ]
    }
]
"""

import sys

from commandline import parse_commandline
from xml_builder import main

if __name__ == "__main__":
  args = parse_commandline(sys.argv[1:])
  main(args)
