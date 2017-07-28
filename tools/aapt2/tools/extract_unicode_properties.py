#!/bin/env python3

"""Extracts the XID_Start and XID_Continue Derived core properties from the ICU data files
and emits a std::array<> for binary searching.
"""

import re
import sys

CharacterPropertyEnumMap = {
        1: "CharacterProperties::kXidStart",
        2: "CharacterProperties::kXidContinue"
}

class CharacterProperty:
    def __init__(self, first_char, last_char, prop_type):
        self.first_char = first_char
        self.last_char = last_char
        self.prop_type = prop_type

    def key(self):
        return self.first_char

    def merge(self, other):
        if self.last_char + 1 == other.first_char and self.prop_type == other.prop_type:
            self.last_char = other.last_char
        else:
            raise KeyError()

    def __repr__(self):
        types = []
        for enum_int, enum_str in CharacterPropertyEnumMap.items():
            if enum_int & self.prop_type:
                types.append(enum_str)
        return "{}0x{:04x}, 0x{:04x}, {}{}".format(
                "{", self.first_char, self.last_char, ' | '.join(types), "}")

def extract_unicode_properties(f, props, chars_out):
    prog = re.compile(r"^(?P<first>\w{4})(..(?P<last>\w{4}))?\W+;\W+(?P<prop>\w+)")
    for line in f:
        result = prog.match(line)
        if result:
            prop_type_str = result.group('prop')
            first_char_str = result.group('first')
            last_char_str = result.group('last')
            if prop_type_str in props:
                start_char = int(first_char_str, 16)
                last_char = (int(last_char_str, 16) if last_char_str else start_char) + 1
                prop_type = props[prop_type_str]
                for char in range(start_char, last_char):
                    if char not in chars_out:
                        chars_out[char] = CharacterProperty(char, char, 0)
                    chars_out[char].prop_type |= prop_type
    return chars_out

def flatten_unicode_properties(chars):
    result = []
    for char_prop in sorted(chars.values(), key=CharacterProperty.key):
        if len(result) == 0:
            result.append(char_prop)
        else:
            try:
                result[len(result) - 1].merge(char_prop)
            except KeyError:
                result.append(char_prop)
    return result

license = """/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"""

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("must specify path to icu DerivedCoreProperties file (e.g:" \
                "external/icu/icu4c/source/data/unidata/DerivedCoreProperties.txt)")
        sys.exit(1)

    props = {"XID_Start": 1, "XID_Continue": 2}
    char_props = {}
    for file_path in sys.argv[1:]:
        with open(file_path) as f:
            extract_unicode_properties(f, props, char_props)
    result = flatten_unicode_properties(char_props)
    print("{}\nconst static std::array<CharacterProperties, {}> sCharacterProperties = {}"
            .format(license, len(result), "{{"))
    for prop in result:
        print("    {},".format(prop))
    print("}};")

