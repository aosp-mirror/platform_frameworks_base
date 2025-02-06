#!/usr/bin/env python3
#
# Copyright 2016 The Android Open Source Project. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.
#

"""Generate a C++ data table containing locale data."""

import collections
import glob
import os.path
import sys

import xml.etree.ElementTree as ElementTree


def get_locale_parts(locale):
    """Split a locale into three parts, for langauge, script, and region."""
    parts = locale.split('_')
    if len(parts) == 1:
        return (parts[0], None, None)
    elif len(parts) == 2:
        if len(parts[1]) == 4:  # parts[1] is a script
            return (parts[0], parts[1], None)
        else:
            return (parts[0], None, parts[1])
    else:
        assert len(parts) == 3
        return tuple(parts)


def read_likely_subtags(input_file_name):
    """Read and parse ICU's likelySubtags.txt."""
    likely_script_dict = {
        # Android's additions for pseudo-locales. These internal codes make
        # sure that the pseudo-locales would not match other English or
        # Arabic locales. (We can't use private-use ISO 15924 codes, since
        # they may be used by apps for other purposes.)
        "en_XA": "~~~A",
        "ar_XB": "~~~B",
        # Removed data from later versions of ICU
        "ji": "Hebr", # Old code for Yiddish, still used in Java and Android
    }
    representative_locales = {
        # Android's additions
        "en_Latn_GB", # representative for en_Latn_001
        "es_Latn_MX", # representative for es_Latn_419
        "es_Latn_US", # representative for es_Latn_419 (not the best idea,
        # but Android has been shipping with it for quite a
        # while. Fortunately, MX < US, so if both exist, MX
        # would be chosen.)
    }
    xml_tree = ElementTree.parse(input_file_name)
    likely_subtags = xml_tree.find('likelySubtags')
    for child in likely_subtags:
        from_locale = child.get('from')
        to_locale = child.get('to')
        # print(f'from: {from_locale} to: {to_locale}')
        from_lang, from_scr, from_region = get_locale_parts(from_locale)
        _, to_scr, to_region = get_locale_parts(to_locale)
        if to_locale == "FAIL":
            continue # "FAIL" cases are not useful here.
        if from_lang == 'und':
            continue  # not very useful for our purposes
        if from_region is None and to_region not in ['001', 'ZZ']:
            representative_locales.add(to_locale)
        if from_scr is None:
            likely_script_dict[from_locale] = to_scr

    return likely_script_dict, frozenset(representative_locales)


# From packLanguageOrRegion() in ResourceTypes.cpp
def pack_language_or_region(inp, base):
    """Pack langauge or region in a two-byte tuple."""
    if inp is None:
        return (0, 0)
    elif len(inp) == 2:
        return ord(inp[0]), ord(inp[1])
    else:
        assert len(inp) == 3, f'Expects a 3-character string, but "{inp}" '
        base = ord(base)
        first = ord(inp[0]) - base
        second = ord(inp[1]) - base
        third = ord(inp[2]) - base

        return (0x80 | (third << 2) | (second >>3),
                ((second << 5) | first) & 0xFF)


# From packLanguage() in ResourceTypes.cpp
def pack_language(language):
    """Pack language in a two-byte tuple."""
    return pack_language_or_region(language, 'a')


# From packRegion() in ResourceTypes.cpp
def pack_region(region):
    """Pack region in a two-byte tuple."""
    return pack_language_or_region(region, '0')


def pack_to_uint32(locale):
    """Pack language+region of locale into a 32-bit unsigned integer."""
    lang, _, region = get_locale_parts(locale)
    plang = pack_language(lang)
    pregion = pack_region(region)
    return (plang[0] << 24) | (plang[1] << 16) | (pregion[0] << 8) | pregion[1]


def dump_script_codes(all_scripts):
    """Dump the SCRIPT_CODES table."""
    print('constexpr const char SCRIPT_CODES[][4] = {')
    for index, script in enumerate(all_scripts):
        print("    /* %-2d */ {'%c', '%c', '%c', '%c'}," % (
            index, script[0], script[1], script[2], script[3]))
    print('};')
    print()


def dump_script_data(likely_script_dict, all_scripts):
    """Dump the script data."""
    print()
    print('const char* lookupLikelyScript(uint32_t packed_lang_region) {')
    print('    switch(packed_lang_region) {')

    # partition the mapping by the script code
    parts = {}
    for locale in sorted(likely_script_dict.keys()):
        script = likely_script_dict[locale]
        if script in parts:
            l = parts[script]
        else:
            l = []
            parts[script] = l
        l.append(locale)

    for script in sorted(parts.keys()):
        locales = parts[script]
        for locale in locales:
            print('        case 0x%08Xu: // %s -> %s' % (
                pack_to_uint32(locale),
                locale.replace('_', '-'),
                script))
        print('            return SCRIPT_CODES[%2du];' %
              all_scripts.index(script))
    print('        default:')
    print('            return nullptr;')
    print('     }')
    print('}')


def pack_to_uint64(locale):
    """Pack a full locale into a 64-bit unsigned integer."""
    _, script, _ = get_locale_parts(locale)
    return ((pack_to_uint32(locale) << 32) |
            (ord(script[0]) << 24) |
            (ord(script[1]) << 16) |
            (ord(script[2]) << 8) |
            ord(script[3]))

def pack_script_to_uint32(script):
    """Pack a 4-letter script code into a 32-bit unsigned integer."""
    return ((ord(script[0]) << 24) |
            (ord(script[1]) << 16) |
            (ord(script[2]) << 8) |
            ord(script[3]))


def dump_representative_locales(representative_locales):
    """Dump the set of representative locales."""
    print('''
/*
 * TODO: Consider turning the below switch statement into binary search
 *      to save the disk space when the table is larger in the future.
 *      Disassembled code shows that the jump table emitted by clang can be
 *      4x larger than the data in disk size, but it depends on the optimization option.
 *      However, a switch statement will benefit from the future of compiler improvement.
 */''')
    print('bool isLocaleRepresentative(uint32_t language_and_region, const char* script) {')
    print('    const uint64_t packed_locale =')
    print('            ((static_cast<uint64_t>(language_and_region)) << 32u) |')
    print('            (static_cast<uint64_t>(packScript(script)));')
    print('    switch(packed_locale) {')
    for locale in sorted(representative_locales):
        print('        case 0x%08XLLU: // %s' % (
            pack_to_uint64(locale),
            locale))

    print('            return true;')
    print('        default:')
    print('            return false;')
    print('    }')
    print('}')


def read_and_dump_likely_data(cldr_source_dir):
    """Read and dump the likely-script data."""
    likely_subtags_txt = os.path.join(cldr_source_dir,
                                      'common', 'supplemental', 'likelySubtags.xml')
    likely_script_dict, representative_locales = read_likely_subtags(
        likely_subtags_txt)

    all_scripts = list(set(likely_script_dict.values()))
    assert len(all_scripts) <= 256
    all_scripts.sort()

    dump_script_codes(all_scripts)
    dump_script_data(likely_script_dict, all_scripts)
    dump_representative_locales(representative_locales)
    return likely_script_dict

def escape_script_variable_name(script):
    """Escape characters, e.g. '~', in a C++ variable name"""
    return script.replace("~", "0")

def read_parent_data(icu_data_dir):
    """Read locale parent data from ICU data files."""
    all_icu_data_files = glob.glob(os.path.join(icu_data_dir, '*', '*.txt'))
    parent_dict = {}
    for data_file in all_icu_data_files:
        locale = os.path.splitext(os.path.basename(data_file))[0]
        with open(data_file) as input_file:
            for line in input_file:
                if '%%Parent' in line:
                    parent = line[line.index('"')+1:line.rindex('"')]
                    if locale in parent_dict:
                        # Different files shouldn't have different parent info
                        assert parent_dict[locale] == parent
                    else:
                        parent_dict[locale] = parent
                elif locale.startswith('ar_') and 'default{"latn"}' in line:
                    # Arabic parent overrides for ASCII digits. Since
                    # Unicode extensions are not supported in ResourceTypes,
                    # we will use ar-015 (Arabic, Northern Africa) instead
                    # of the more correct ar-u-nu-latn.
                    parent_dict[locale] = 'ar_015'
    return parent_dict


def get_likely_script(locale, likely_script_dict):
    """Find the likely script for a locale, given the likely-script dictionary.
    """
    if locale.count('_') == 2:
        # it already has a script
        return locale.split('_')[1]
    elif locale in likely_script_dict:
        return likely_script_dict[locale]
    else:
        language = locale.split('_')[0]
        return likely_script_dict[language]


def dump_parent_data(script_organized_dict):
    """Dump information for parents of locales."""
    sorted_scripts = sorted(script_organized_dict.keys())
    print()

    for script in sorted_scripts:
        parent_dict = script_organized_dict[script]

        # partition the mapping by the parent's value
        parts = {}
        for locale in sorted(parent_dict.keys()):
            parent = parent_dict[locale]
            if parent in parts:
                l = parts[parent]
            else:
                l = []
                parts[parent] = l
            l.append(locale)

        print('static uint32_t find%sParent(uint32_t packed_lang_region) {' % escape_script_variable_name(script))
        print('    switch(packed_lang_region) {')
        for parent in sorted(parts.keys()):
            locales = parts[parent]
            for locale in locales:
                print('        case 0x%08Xu: // %s -> %s' % (
                    pack_to_uint32(locale),
                    locale.replace('_', '-'),
                    parent.replace('_', '-')))

            print('            return 0x%08Xu;' % pack_to_uint32(parent))

        print('        default:')
        print('            return 0;')
        print('    }')
        print('}')
        print()

    print('uint32_t findParentLocalePackedKey(const char* script, uint32_t packed_lang_region) {')
    print('    uint32_t packedScript = packScript(script);')
    print('    switch (packedScript) {')

    for script in sorted_scripts:
        print('        case 0x%08Xu: // %s' % (pack_script_to_uint32(script), script))
        print('            return find%sParent(packed_lang_region);' %
              escape_script_variable_name(script))

    print('        default:')
    print('            return 0;')
    print('    }')
    print('}')


def dump_parent_tree_depth(parent_dict):
    """Find and dump the depth of the parent tree."""
    max_depth = 1
    for locale, _ in parent_dict.items():
        depth = 1
        while locale in parent_dict:
            locale = parent_dict[locale]
            depth += 1
        max_depth = max(max_depth, depth)
    assert max_depth < 5 # Our algorithms assume small max_depth
    print()
    print('uint32_t getMaxAncestorTreeDepth() {')
    print('    return %d;' % max_depth)
    print('}')


def read_and_dump_parent_data(icu_data_dir, likely_script_dict):
    """Read parent data from ICU and dump it."""
    parent_dict = read_parent_data(icu_data_dir)
    script_organized_dict = collections.defaultdict(dict)
    for locale in parent_dict:
        parent = parent_dict[locale]
        if parent == 'root':
            continue
        script = get_likely_script(locale, likely_script_dict)
        script_organized_dict[script][locale] = parent_dict[locale]
    dump_parent_data(script_organized_dict)
    dump_parent_tree_depth(parent_dict)


def main():
    """Read the data files from ICU and dump the output to a C++ file."""
    source_root = sys.argv[1]
    icu_data_dir = os.path.join(
        source_root,
        'external', 'icu', 'icu4c', 'source', 'data')
    cldr_source_dir = os.path.join(source_root, 'external', 'cldr')

    print('''/*
 * Copyright (C) 2025 The Android Open Source Project
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

''')
    print('// Auto-generated by %s' % sys.argv[0])
    print('''
#include <androidfw/LocaleDataLookup.h>

namespace android {
''')
    likely_script_dict = read_and_dump_likely_data(cldr_source_dir)
    read_and_dump_parent_data(icu_data_dir, likely_script_dict)
    print()
    print('} // namespace android')


if __name__ == '__main__':
    main()
