#!/usr/bin/env python

import collections
import glob
from os import path
import sys
from xml.etree import ElementTree

from fontTools import ttLib

LANG_TO_SCRIPT = {
    'de': 'Latn',
    'en': 'Latn',
    'es': 'Latn',
    'eu': 'Latn',
    'ja': 'Jpan',
    'ko': 'Kore',
    'hu': 'Latn',
    'hy': 'Armn',
    'nb': 'Latn',
    'nn': 'Latn',
    'pt': 'Latn',
}

def lang_to_script(lang_code):
    lang = lang_code.lower()
    while lang not in LANG_TO_SCRIPT:
        hyphen_idx = lang.rfind('-')
        assert hyphen_idx != -1, (
            'We do not know what script the "%s" language is written in.'
            % lang_code)
        assumed_script = lang[hyphen_idx+1:]
        if len(assumed_script) == 4 and assumed_script.isalpha():
            # This is actually the script
            return assumed_script.title()
        lang = lang[:hyphen_idx]
    return LANG_TO_SCRIPT[lang]


def get_best_cmap(font):
    font_file, index = font
    font_path = path.join(_fonts_dir, font_file)
    if index is not None:
        ttfont = ttLib.TTFont(font_path, fontNumber=index)
    else:
        ttfont = ttLib.TTFont(font_path)
    all_unicode_cmap = None
    bmp_cmap = None
    for cmap in ttfont['cmap'].tables:
        specifier = (cmap.format, cmap.platformID, cmap.platEncID)
        if specifier == (4, 3, 1):
            assert bmp_cmap is None, 'More than one BMP cmap in %s' % (font, )
            bmp_cmap = cmap
        elif specifier == (12, 3, 10):
            assert all_unicode_cmap is None, (
                'More than one UCS-4 cmap in %s' % (font, ))
            all_unicode_cmap = cmap

    return all_unicode_cmap.cmap if all_unicode_cmap else bmp_cmap.cmap


def assert_font_supports_any_of_chars(font, chars):
    best_cmap = get_best_cmap(font)
    for char in chars:
        if char in best_cmap:
            return
    sys.exit('None of characters in %s were found in %s' % (chars, font))


def assert_font_supports_all_of_chars(font, chars):
    best_cmap = get_best_cmap(font)
    for char in chars:
        assert char in best_cmap, (
            'U+%04X was not found in %s' % (char, font))


def assert_font_supports_none_of_chars(font, chars):
    best_cmap = get_best_cmap(font)
    for char in chars:
        assert char not in best_cmap, (
            'U+%04X was found in %s' % (char, font))


def check_hyphens(hyphens_dir):
    # Find all the scripts that need automatic hyphenation
    scripts = set()
    for hyb_file in glob.iglob(path.join(hyphens_dir, '*.hyb')):
        hyb_file = path.basename(hyb_file)
        assert hyb_file.startswith('hyph-'), (
            'Unknown hyphenation file %s' % hyb_file)
        lang_code = hyb_file[hyb_file.index('-')+1:hyb_file.index('.')]
        scripts.add(lang_to_script(lang_code))

    HYPHENS = {0x002D, 0x2010}
    for script in scripts:
        fonts = _script_to_font_map[script]
        assert fonts, 'No fonts found for the "%s" script' % script
        for font in fonts:
            assert_font_supports_any_of_chars(font, HYPHENS)


def parse_fonts_xml(fonts_xml_path):
    global _script_to_font_map, _fallback_chain
    _script_to_font_map = collections.defaultdict(set)
    _fallback_chain = []
    tree = ElementTree.parse(fonts_xml_path)
    for family in tree.findall('family'):
        name = family.get('name')
        variant = family.get('variant')
        langs = family.get('lang')
        if name:
            assert variant is None, (
                'No variant expected for LGC font %s.' % name)
            assert langs is None, (
                'No language expected for LGC fonts %s.' % name)
        else:
            assert variant in {None, 'elegant', 'compact'}, (
                'Unexpected value for variant: %s' % variant)

        if langs:
            langs = langs.split()
            scripts = {lang_to_script(lang) for lang in langs}
        else:
            scripts = set()

        for child in family:
            assert child.tag == 'font', (
                'Unknown tag <%s>' % child.tag)
            font_file = child.text
            weight = int(child.get('weight'))
            assert weight % 100 == 0, (
                'Font weight "%d" is not a multiple of 100.' % weight)

            style = child.get('style')
            assert style in {'normal', 'italic'}, (
                'Unknown style "%s"' % style)

            index = child.get('index')
            if index:
                index = int(index)

            _fallback_chain.append((
                name,
                frozenset(scripts),
                variant,
                weight,
                style,
                (font_file, index)))

            if name: # non-empty names are used for default LGC fonts
                map_scripts = {'Latn', 'Grek', 'Cyrl'}
            else:
                map_scripts = scripts
            for script in map_scripts:
                _script_to_font_map[script].add((font_file, index))


def check_emoji_availability():
    emoji_fonts = [font[5] for font in _fallback_chain if 'Zsye' in font[1]]
    emoji_chars = _emoji_properties['Emoji']
    for emoji_font in emoji_fonts:
        assert_font_supports_all_of_chars(emoji_font, emoji_chars)


def check_emoji_defaults():
    default_emoji_chars = _emoji_properties['Emoji_Presentation']
    missing_text_chars = _emoji_properties['Emoji'] - default_emoji_chars
    emoji_font_seen = False
    for name, scripts, variant, weight, style, font in _fallback_chain:
        if 'Zsye' in scripts:
            emoji_font_seen = True
            # No need to check the emoji font
            continue
        # For later fonts, we only check them if they have a script
        # defined, since the defined script may get them to a higher
        # score even if they appear after the emoji font.
        if emoji_font_seen and not scripts:
            continue

        # Check default emoji-style characters
        assert_font_supports_none_of_chars(font, sorted(default_emoji_chars))

        # Mark default text-style characters appearing in fonts above the emoji
        # font as seen
        if not emoji_font_seen:
            missing_text_chars -= set(get_best_cmap(font))

    # Noto does not have monochrome symbols for Unicode 7.0 wingdings and
    # webdings
    missing_text_chars -= _chars_by_age['7.0']
    # TODO: Remove these after b/26113320 is fixed
    missing_text_chars -= {
        0x263A, # WHITE SMILING FACE
        0x270C, # VICTORY HAND
        0x2744, # SNOWFLAKE
        0x2764, # HEAVY BLACK HEART
    }
    assert missing_text_chars == set(), (
        'Text style version of some emoji characters are missing.')


# Setting reverse to true returns a dictionary that maps the values to sets of
# characters, useful for some binary properties. Otherwise, we get a
# dictionary that maps characters to the property values, assuming there's only
# one property in the file.
def parse_unicode_datafile(file_path, reverse=False):
    if reverse:
        output_dict = collections.defaultdict(set)
    else:
        output_dict = {}
    with open(file_path) as datafile:
        for line in datafile:
            if '#' in line:
                line = line[:line.index('#')]
            line = line.strip()
            if not line:
                continue
            char_range, prop = line.split(';')
            char_range = char_range.strip()
            prop = prop.strip()
            if '..' in char_range:
                char_start, char_end = char_range.split('..')
            else:
                char_start = char_end = char_range
            char_start = int(char_start, 16)
            char_end = int(char_end, 16)
            char_range = xrange(char_start, char_end+1)
            if reverse:
                output_dict[prop].update(char_range)
            else:
                for char in char_range:
                    assert char not in output_dict
                    output_dict[char] = prop
    return output_dict


def parse_ucd(ucd_path):
    global _emoji_properties, _chars_by_age
    _emoji_properties = parse_unicode_datafile(
        path.join(ucd_path, 'emoji-data.txt'), reverse=True)
    _chars_by_age = parse_unicode_datafile(
        path.join(ucd_path, 'DerivedAge.txt'), reverse=True)


def main():
    target_out = sys.argv[1]
    global _fonts_dir
    _fonts_dir = path.join(target_out, 'fonts')

    fonts_xml_path = path.join(target_out, 'etc', 'fonts.xml')
    parse_fonts_xml(fonts_xml_path)

    hyphens_dir = path.join(target_out, 'usr', 'hyphen-data')
    check_hyphens(hyphens_dir)

    ucd_path = sys.argv[2]
    parse_ucd(ucd_path)
    check_emoji_availability()
    check_emoji_defaults()


if __name__ == '__main__':
    main()
