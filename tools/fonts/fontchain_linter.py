#!/usr/bin/env python

import collections
import copy
import glob
from os import path
import re
import sys
from xml.etree import ElementTree

from fontTools import ttLib

EMOJI_VS = 0xFE0F

LANG_TO_SCRIPT = {
    'af': 'Latn',
    'as': 'Beng',
    'am': 'Latn',
    'be': 'Cyrl',
    'bg': 'Cyrl',
    'bn': 'Beng',
    'cs': 'Latn',
    'cu': 'Cyrl',
    'cy': 'Latn',
    'da': 'Latn',
    'de': 'Latn',
    'el': 'Latn',
    'en': 'Latn',
    'es': 'Latn',
    'et': 'Latn',
    'eu': 'Latn',
    'fr': 'Latn',
    'ga': 'Latn',
    'gl': 'Latn',
    'gu': 'Gujr',
    'hi': 'Deva',
    'hr': 'Latn',
    'hu': 'Latn',
    'hy': 'Armn',
    'it': 'Latn',
    'ja': 'Jpan',
    'ka': 'Latn',
    'kn': 'Knda',
    'ko': 'Kore',
    'la': 'Latn',
    'lt': 'Latn',
    'lv': 'Latn',
    'ml': 'Mlym',
    'mn': 'Cyrl',
    'mr': 'Deva',
    'nb': 'Latn',
    'nl': 'Latn',
    'nn': 'Latn',
    'or': 'Orya',
    'pa': 'Guru',
    'pt': 'Latn',
    'ru': 'Latn',
    'sk': 'Latn',
    'sl': 'Latn',
    'sq': 'Latn',
    'sv': 'Latn',
    'ta': 'Taml',
    'te': 'Telu',
    'tk': 'Latn',
    'uk': 'Latn',
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


def printable(inp):
    if type(inp) is set:  # set of character sequences
        return '{' + ', '.join([printable(seq) for seq in inp]) + '}'
    if type(inp) is tuple:  # character sequence
        return '<' + (', '.join([printable(ch) for ch in inp])) + '>'
    else:  # single character
        return 'U+%04X' % inp


def open_font(font):
    font_file, index = font
    font_path = path.join(_fonts_dir, font_file)
    if index is not None:
        return ttLib.TTFont(font_path, fontNumber=index)
    else:
        return ttLib.TTFont(font_path)


def get_best_cmap(font):
    ttfont = open_font(font)
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


def get_variation_sequences_cmap(font):
    ttfont = open_font(font)
    vs_cmap = None
    for cmap in ttfont['cmap'].tables:
        specifier = (cmap.format, cmap.platformID, cmap.platEncID)
        if specifier == (14, 0, 5):
            assert vs_cmap is None, 'More than one VS cmap in %s' % (font, )
            vs_cmap = cmap
    return vs_cmap


def get_emoji_map(font):
    # Add normal characters
    emoji_map = copy.copy(get_best_cmap(font))
    reverse_cmap = {glyph: code for code, glyph in emoji_map.items() if not contains_pua(code) }

    # Add variation sequences
    vs_cmap = get_variation_sequences_cmap(font)
    if vs_cmap:
        for vs in vs_cmap.uvsDict:
            for base, glyph in vs_cmap.uvsDict[vs]:
                if glyph is None:
                    emoji_map[(base, vs)] = emoji_map[base]
                else:
                    emoji_map[(base, vs)] = glyph

    # Add GSUB rules
    ttfont = open_font(font)
    for lookup in ttfont['GSUB'].table.LookupList.Lookup:
        if lookup.LookupType != 4:
            # Other lookups are used in the emoji font for fallback.
            # We ignore them for now.
            continue
        for subtable in lookup.SubTable:
            ligatures = subtable.ligatures
            for first_glyph in ligatures:
                for ligature in ligatures[first_glyph]:
                    sequence = [first_glyph] + ligature.Component
                    sequence = [reverse_cmap[glyph] for glyph in sequence]
                    sequence = tuple(sequence)
                    # Make sure no starting subsequence of 'sequence' has been
                    # seen before.
                    for sub_len in range(2, len(sequence)+1):
                        subsequence = sequence[:sub_len]
                        assert subsequence not in emoji_map
                    emoji_map[sequence] = ligature.LigGlyph

    return emoji_map


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


def assert_font_supports_none_of_chars(font, chars, fallbackName):
    best_cmap = get_best_cmap(font)
    for char in chars:
        if fallbackName:
            assert char not in best_cmap, 'U+%04X was found in %s' % (char, font)
        else:
            assert char not in best_cmap, (
                'U+%04X was found in %s in fallback %s' % (char, font, fallbackName))


def assert_font_supports_all_sequences(font, sequences):
    vs_dict = get_variation_sequences_cmap(font).uvsDict
    for base, vs in sorted(sequences):
        assert vs in vs_dict and (base, None) in vs_dict[vs], (
            '<U+%04X, U+%04X> was not found in %s' % (base, vs, font))


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


class FontRecord(object):
    def __init__(self, name, psName, scripts, variant, weight, style, fallback_for, font):
        self.name = name
        self.psName = psName
        self.scripts = scripts
        self.variant = variant
        self.weight = weight
        self.style = style
        self.fallback_for = fallback_for
        self.font = font


def parse_fonts_xml(fonts_xml_path):
    global _script_to_font_map, _fallback_chains, _all_fonts
    _script_to_font_map = collections.defaultdict(set)
    _fallback_chains = {}
    _all_fonts = []
    tree = ElementTree.parse(fonts_xml_path)
    families = tree.findall('family')
    # Minikin supports up to 254 but users can place their own font at the first
    # place. Thus, 253 is the maximum allowed number of font families in the
    # default collection.
    assert len(families) < 254, (
        'System font collection can contains up to 253 font families.')
    for family in families:
        name = family.get('name')
        variant = family.get('variant')
        langs = family.get('lang')
        ignoreAttr = family.get('ignore')

        if name:
            assert variant is None, (
                'No variant expected for LGC font %s.' % name)
            assert langs is None, (
                'No language expected for LGC fonts %s.' % name)
            assert name not in _fallback_chains, 'Duplicated name entry %s' % name
            _fallback_chains[name] = []
        else:
            assert variant in {None, 'elegant', 'compact'}, (
                'Unexpected value for variant: %s' % variant)

    trim_re = re.compile(r"^[ \n\r\t]*(.+)[ \n\r\t]*$")
    for family in families:
        name = family.get('name')
        variant = family.get('variant')
        langs = family.get('lang')
        ignoreAttr = family.get('ignore')
        ignore = ignoreAttr == 'true' or ignoreAttr == '1'

        if ignore:
            continue

        if langs:
            langs = langs.split()
            scripts = {lang_to_script(lang) for lang in langs}
        else:
            scripts = set()

        for child in family:
            assert child.tag == 'font', (
                'Unknown tag <%s>' % child.tag)
            font_file = child.text.rstrip()

            m = trim_re.match(font_file)
            font_file = m.group(1)

            weight = int(child.get('weight'))
            assert weight % 100 == 0, (
                'Font weight "%d" is not a multiple of 100.' % weight)

            style = child.get('style')
            assert style in {'normal', 'italic'}, (
                'Unknown style "%s"' % style)

            fallback_for = child.get('fallbackFor')

            assert not name or not fallback_for, (
                'name and fallbackFor cannot be present at the same time')
            assert not fallback_for or fallback_for in _fallback_chains, (
                'Unknown fallback name: %s' % fallback_for)

            index = child.get('index')
            if index:
                index = int(index)

            if not path.exists(path.join(_fonts_dir, m.group(1))):
                continue # Missing font is a valid case. Just ignore the missing font files.

            record = FontRecord(
                name,
                child.get('postScriptName'),
                frozenset(scripts),
                variant,
                weight,
                style,
                fallback_for,
                (font_file, index))

            _all_fonts.append(record)

            if not fallback_for:
                if not name or name == 'sans-serif':
                    for _, fallback in _fallback_chains.items():
                        fallback.append(record)
                else:
                    _fallback_chains[name].append(record)
            else:
                _fallback_chains[fallback_for].append(record)

            if name: # non-empty names are used for default LGC fonts
                map_scripts = {'Latn', 'Grek', 'Cyrl'}
            else:
                map_scripts = scripts
            for script in map_scripts:
                _script_to_font_map[script].add((font_file, index))


def check_emoji_coverage(all_emoji, equivalent_emoji):
    emoji_fonts = get_emoji_fonts()
    check_emoji_font_coverage(emoji_fonts, all_emoji, equivalent_emoji)


def get_emoji_fonts():
    return [ record.font for record in _all_fonts if 'Zsye' in record.scripts ]

def seq_any(sequence, pred):
  if type(sequence) is tuple:
    return any([pred(x) for x in sequence])
  else:
    return pred(sequence)

def seq_all(sequence, pred):
  if type(sequence) is tuple:
    return all([pred(x) for x in sequence])
  else:
    return pred(sequence)

def is_regional_indicator(x):
    # regional indicator A..Z
    return 0x1F1E6 <= x <= 0x1F1FF

def is_tag(x):
    # tag block
    return 0xE0000 <= x <= 0xE007F

def is_pua(x):
    return 0xE000 <= x <= 0xF8FF or 0xF0000 <= x <= 0xFFFFD or 0x100000 <= x <= 0x10FFFD

def contains_pua(sequence):
    return seq_any(sequence, is_pua)

def contains_regional_indicator(sequence):
    return seq_any(sequence, is_regional_indicator)

def only_tags(sequence):
    return seq_all(sequence, is_tag)

def get_psname(ttf):
    return str(next(x for x in ttf['name'].names
        if x.platformID == 3 and x.platEncID == 1 and x.nameID == 6))

def hex_strs(sequence):
    if type(sequence) is tuple:
        return tuple(f"{s:X}" for s in sequence)
    return hex(sequence)

def check_plausible_compat_pua(coverage, all_emoji, equivalent_emoji):
    # A PUA should point to every RGI emoji and that PUA should be unique to the
    # set of equivalent sequences for the emoji.
    problems = []
    for seq in all_emoji:
        # We're looking to match not-PUA with PUA so filter out existing PUA
        if contains_pua(seq):
            continue

        # Filter out non-RGI things that end up in all_emoji
        if only_tags(seq) or seq in {ZWJ, COMBINING_KEYCAP, EMPTY_FLAG_SEQUENCE}:
            continue

        equivalents = [seq]
        if seq in equivalent_emoji:
            equivalents.append(equivalent_emoji[seq])

        # If there are problems the hex code is much more useful
        log_equivalents = [hex_strs(s) for s in equivalents]

        # The system compat font should NOT include regional indicators as these have been split out
        if contains_regional_indicator(seq):
            assert not any(s in coverage for s in equivalents), f"Regional indicators not expected in compat font, found {log_equivalents}"
            continue

        glyph = {coverage[e] for e in equivalents}
        if len(glyph) != 1:
            problems.append(f"{log_equivalents} should all point to the same glyph")
            continue
        glyph = next(iter(glyph))

        pua = {s for s, g in coverage.items() if contains_pua(s) and g == glyph}
        if not pua:
            problems.append(f"Expected PUA for {log_equivalents} but none exist")
            continue

    assert not problems, "\n".join(sorted(problems)) + f"\n{len(problems)} PUA problems"

def check_emoji_compat(all_emoji, equivalent_emoji):
    compat_psnames = set()
    for emoji_font in get_emoji_fonts():
        ttf = open_font(emoji_font)
        psname = get_psname(ttf)

        is_compat_font = "meta" in ttf and 'Emji' in ttf["meta"].data
        if not is_compat_font:
            continue
        compat_psnames.add(psname)

        # If the font has compat metadata it should have PUAs for emoji sequences
        coverage = get_emoji_map(emoji_font)
        check_plausible_compat_pua(coverage, all_emoji, equivalent_emoji)


    # NotoColorEmoji must be a Compat font.
    assert 'NotoColorEmoji' in compat_psnames, 'NotoColorEmoji MUST be a compat font'


def check_emoji_font_coverage(emoji_fonts, all_emoji, equivalent_emoji):
    coverages = []
    for emoji_font in emoji_fonts:
        coverages.append(get_emoji_map(emoji_font))

    errors = []

    for sequence in all_emoji:
        if all([sequence not in coverage for coverage in coverages]):
            errors.append('%s is not supported in the emoji font.' % printable(sequence))

    for coverage in coverages:
        for sequence in coverage:
            if sequence in {0x0000, 0x000D, 0x0020}:
                # The font needs to support a few extra characters, which is OK
                continue

            if contains_pua(sequence):
                # The font needs to have some PUA for EmojiCompat library.
                continue

            if sequence not in all_emoji:
                errors.append('%s support unexpected in the emoji font.' % printable(sequence))

    for first, second in equivalent_emoji.items():
        for coverage in coverages:
            if first not in coverage or second not in coverage:
                continue  # sequence will be reported missing
            if coverage[first] != coverage[second]:
                errors.append('%s and %s should map to the same glyph.' % (
                    printable(first),
                    printable(second)))

    for coverage in coverages:
        for glyph in set(coverage.values()):
            maps_to_glyph = [
                seq for seq in coverage if coverage[seq] == glyph and not contains_pua(seq) ]
            if len(maps_to_glyph) > 1:
                # There are more than one sequences mapping to the same glyph. We
                # need to make sure they were expected to be equivalent.
                equivalent_seqs = set()
                for seq in maps_to_glyph:
                    equivalent_seq = seq
                    while equivalent_seq in equivalent_emoji:
                        equivalent_seq = equivalent_emoji[equivalent_seq]
                    equivalent_seqs.add(equivalent_seq)
                if len(equivalent_seqs) != 1:
                    errors.append('The sequences %s should not result in the same glyph %s' % (
                        printable(equivalent_seqs),
                        glyph))

    assert not errors, '%d emoji font errors:\n%s\n%d emoji font coverage errors' % (len(errors), '\n'.join(errors), len(errors))


def check_emoji_defaults(default_emoji):
    missing_text_chars = _emoji_properties['Emoji'] - default_emoji
    for name, fallback_chain in _fallback_chains.items():
        emoji_font_seen = False
        for record in fallback_chain:
            if 'Zsye' in record.scripts:
                emoji_font_seen = True
                # No need to check the emoji font
                continue
            # For later fonts, we only check them if they have a script
            # defined, since the defined script may get them to a higher
            # score even if they appear after the emoji font. However,
            # we should skip checking the text symbols font, since
            # symbol fonts should be able to override the emoji display
            # style when 'Zsym' is explicitly specified by the user.
            if emoji_font_seen and (not record.scripts or 'Zsym' in record.scripts):
                continue

            # Check default emoji-style characters
            assert_font_supports_none_of_chars(record.font, default_emoji, name)

            # Mark default text-style characters appearing in fonts above the emoji
            # font as seen
            if not emoji_font_seen:
                missing_text_chars -= set(get_best_cmap(record.font))

        # Noto does not have monochrome glyphs for Unicode 7.0 wingdings and
        # webdings yet.
        missing_text_chars -= _chars_by_age['7.0']
        assert missing_text_chars == set(), (
            'Text style version of some emoji characters are missing: ' +
                repr(missing_text_chars))


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

            chars, prop = line.split(';')[:2]
            chars = chars.strip()
            prop = prop.strip()

            if ' ' in chars:  # character sequence
                sequence = [int(ch, 16) for ch in chars.split(' ')]
                additions = [tuple(sequence)]
            elif '..' in chars:  # character range
                char_start, char_end = chars.split('..')
                char_start = int(char_start, 16)
                char_end = int(char_end, 16)
                additions = range(char_start, char_end+1)
            else:  # singe character
                additions = [int(chars, 16)]
            if reverse:
                output_dict[prop].update(additions)
            else:
                for addition in additions:
                    assert addition not in output_dict
                    output_dict[addition] = prop
    return output_dict


def parse_emoji_variants(file_path):
    emoji_set = set()
    text_set = set()
    with open(file_path) as datafile:
        for line in datafile:
            if '#' in line:
                line = line[:line.index('#')]
            line = line.strip()
            if not line:
                continue
            sequence, description, _ = line.split(';')
            sequence = sequence.strip().split(' ')
            base = int(sequence[0], 16)
            vs = int(sequence[1], 16)
            description = description.strip()
            if description == 'text style':
                text_set.add((base, vs))
            elif description == 'emoji style':
                emoji_set.add((base, vs))
    return text_set, emoji_set


def parse_ucd(ucd_path):
    global _emoji_properties, _chars_by_age
    global _text_variation_sequences, _emoji_variation_sequences
    global _emoji_sequences, _emoji_zwj_sequences
    _emoji_properties = parse_unicode_datafile(
        path.join(ucd_path, 'emoji-data.txt'), reverse=True)
    emoji_properties_additions = parse_unicode_datafile(
        path.join(ucd_path, 'additions', 'emoji-data.txt'), reverse=True)
    for prop in emoji_properties_additions.keys():
        _emoji_properties[prop].update(emoji_properties_additions[prop])

    _chars_by_age = parse_unicode_datafile(
        path.join(ucd_path, 'DerivedAge.txt'), reverse=True)
    sequences = parse_emoji_variants(
        path.join(ucd_path, 'emoji-variation-sequences.txt'))
    _text_variation_sequences, _emoji_variation_sequences = sequences
    _emoji_sequences = parse_unicode_datafile(
        path.join(ucd_path, 'emoji-sequences.txt'))
    _emoji_sequences.update(parse_unicode_datafile(
        path.join(ucd_path, 'additions', 'emoji-sequences.txt')))
    _emoji_zwj_sequences = parse_unicode_datafile(
        path.join(ucd_path, 'emoji-zwj-sequences.txt'))
    _emoji_zwj_sequences.update(parse_unicode_datafile(
        path.join(ucd_path, 'additions', 'emoji-zwj-sequences.txt')))

    exclusions = parse_unicode_datafile(path.join(ucd_path, 'additions', 'emoji-exclusions.txt'))
    _emoji_sequences = remove_emoji_exclude(_emoji_sequences, exclusions)
    _emoji_zwj_sequences = remove_emoji_exclude(_emoji_zwj_sequences, exclusions)
    _emoji_variation_sequences = remove_emoji_variation_exclude(_emoji_variation_sequences, exclusions)
    # Unicode 12.0 adds Basic_Emoji in emoji-sequences.txt. We ignore them here since we are already
    # checking the emoji presentations with emoji-variation-sequences.txt.
    # Please refer to http://unicode.org/reports/tr51/#def_basic_emoji_set .
    _emoji_sequences = {k: v for k, v in _emoji_sequences.items() if not v == 'Basic_Emoji' }


def remove_emoji_variation_exclude(source, items):
    return source.difference(items.keys())

def remove_emoji_exclude(source, items):
    return {k: v for k, v in source.items() if k not in items}

def flag_sequence(territory_code):
    return tuple(0x1F1E6 + ord(ch) - ord('A') for ch in territory_code)

EQUIVALENT_FLAGS = {
    flag_sequence('BV'): flag_sequence('NO'),
    flag_sequence('CP'): flag_sequence('FR'),
    flag_sequence('HM'): flag_sequence('AU'),
    flag_sequence('SJ'): flag_sequence('NO'),
    flag_sequence('UM'): flag_sequence('US'),
}

COMBINING_KEYCAP = 0x20E3

LEGACY_ANDROID_EMOJI = {
    0xFE4E5: flag_sequence('JP'),
    0xFE4E6: flag_sequence('US'),
    0xFE4E7: flag_sequence('FR'),
    0xFE4E8: flag_sequence('DE'),
    0xFE4E9: flag_sequence('IT'),
    0xFE4EA: flag_sequence('GB'),
    0xFE4EB: flag_sequence('ES'),
    0xFE4EC: flag_sequence('RU'),
    0xFE4ED: flag_sequence('CN'),
    0xFE4EE: flag_sequence('KR'),
    0xFE82C: (ord('#'), COMBINING_KEYCAP),
    0xFE82E: (ord('1'), COMBINING_KEYCAP),
    0xFE82F: (ord('2'), COMBINING_KEYCAP),
    0xFE830: (ord('3'), COMBINING_KEYCAP),
    0xFE831: (ord('4'), COMBINING_KEYCAP),
    0xFE832: (ord('5'), COMBINING_KEYCAP),
    0xFE833: (ord('6'), COMBINING_KEYCAP),
    0xFE834: (ord('7'), COMBINING_KEYCAP),
    0xFE835: (ord('8'), COMBINING_KEYCAP),
    0xFE836: (ord('9'), COMBINING_KEYCAP),
    0xFE837: (ord('0'), COMBINING_KEYCAP),
}

# This is used to define the emoji that should have the same glyph.
# i.e. previously we had gender based Kiss (0x1F48F), which had the same glyph
# with Kiss: Woman, Man (0x1F469, 0x200D, 0x2764, 0x200D, 0x1F48B, 0x200D, 0x1F468)
# in that case a valid row would be:
# (0x1F469, 0x200D, 0x2764, 0x200D, 0x1F48B, 0x200D, 0x1F468): 0x1F48F,
ZWJ_IDENTICALS = {
}

SAME_FLAG_MAPPINGS = [
    # Diego Garcia and British Indian Ocean Territory
    ((0x1F1EE, 0x1F1F4), (0x1F1E9, 0x1F1EC)),
    # St. Martin and France
    ((0x1F1F2, 0x1F1EB), (0x1F1EB, 0x1F1F7)),
    # Spain and Ceuta & Melilla
    ((0x1F1EA, 0x1F1F8), (0x1F1EA, 0x1F1E6)),
]

ZWJ = 0x200D

EMPTY_FLAG_SEQUENCE = (0x1F3F4, 0xE007F)

def is_fitzpatrick_modifier(cp):
    return 0x1F3FB <= cp <= 0x1F3FF


def reverse_emoji(seq):
    rev = list(reversed(seq))
    # if there are fitzpatrick modifiers in the sequence, keep them after
    # the emoji they modify
    for i in range(1, len(rev)):
        if is_fitzpatrick_modifier(rev[i-1]):
            rev[i], rev[i-1] = rev[i-1], rev[i]
    return tuple(rev)


def compute_expected_emoji():
    equivalent_emoji = {}
    sequence_pieces = set()
    all_sequences = set()
    all_sequences.update(_emoji_variation_sequences)

    # add zwj sequences not in the current emoji-zwj-sequences.txt
    adjusted_emoji_zwj_sequences = dict(_emoji_zwj_sequences)
    adjusted_emoji_zwj_sequences.update(_emoji_zwj_sequences)

    # Add empty flag tag sequence that is supported as fallback
    _emoji_sequences[EMPTY_FLAG_SEQUENCE] = 'Emoji_Tag_Sequence'

    for sequence in _emoji_sequences.keys():
        sequence = tuple(ch for ch in sequence if ch != EMOJI_VS)
        all_sequences.add(sequence)
        sequence_pieces.update(sequence)

    for sequence in adjusted_emoji_zwj_sequences.keys():
        sequence = tuple(ch for ch in sequence if ch != EMOJI_VS)
        all_sequences.add(sequence)
        sequence_pieces.update(sequence)

    for first, second in SAME_FLAG_MAPPINGS:
        equivalent_emoji[first] = second

    # Add all tag characters used in flags
    sequence_pieces.update(range(0xE0030, 0xE0039 + 1))
    sequence_pieces.update(range(0xE0061, 0xE007A + 1))

    all_emoji = (
        _emoji_properties['Emoji'] |
        all_sequences |
        sequence_pieces |
        set(LEGACY_ANDROID_EMOJI.keys()))
    default_emoji = (
        _emoji_properties['Emoji_Presentation'] |
        all_sequences |
        set(LEGACY_ANDROID_EMOJI.keys()))

    equivalent_emoji.update(EQUIVALENT_FLAGS)
    equivalent_emoji.update(LEGACY_ANDROID_EMOJI)
    equivalent_emoji.update(ZWJ_IDENTICALS)

    for seq in _emoji_variation_sequences:
        equivalent_emoji[seq] = seq[0]

    return all_emoji, default_emoji, equivalent_emoji


def check_compact_only_fallback():
    for name, fallback_chain in _fallback_chains.items():
        for record in fallback_chain:
            if record.variant == 'compact':
                same_script_elegants = [x for x in fallback_chain
                    if x.scripts == record.scripts and x.variant == 'elegant']
                assert same_script_elegants, (
                    '%s must be in elegant of %s as fallback of "%s" too' % (
                    record.font, record.scripts, record.fallback_for),)


def check_vertical_metrics():
    for record in _all_fonts:
        if record.name in ['sans-serif', 'sans-serif-condensed']:
            font = open_font(record.font)
            assert font['head'].yMax == 2163 and font['head'].yMin == -555, (
                'yMax and yMin of %s do not match expected values.' % (
                record.font,))

        if record.name in ['sans-serif', 'sans-serif-condensed',
                           'serif', 'monospace']:
            font = open_font(record.font)
            assert (font['hhea'].ascent == 1900 and
                    font['hhea'].descent == -500), (
                        'ascent and descent of %s do not match expected '
                        'values.' % (record.font,))


def check_cjk_punctuation():
    cjk_scripts = {'Hans', 'Hant', 'Jpan', 'Kore'}
    cjk_punctuation = range(0x3000, 0x301F + 1)
    for name, fallback_chain in _fallback_chains.items():
        for record in fallback_chain:
            if record.scripts.intersection(cjk_scripts):
                # CJK font seen. Stop checking the rest of the fonts.
                break
            assert_font_supports_none_of_chars(record.font, cjk_punctuation, name)

def getPostScriptName(font):
  font_file, index = font
  font_path = path.join(_fonts_dir, font_file)
  if index is not None:
      # Use the first font file in the collection for resolving post script name.
      ttf = ttLib.TTFont(font_path, fontNumber=0)
  else:
      ttf = ttLib.TTFont(font_path)

  nameTable = ttf['name']
  for name in nameTable.names:
      if (name.nameID == 6 and name.platformID == 3 and name.platEncID == 1
          and name.langID == 0x0409):
          return str(name)

def check_canonical_name():
    for record in _all_fonts:
        file_name, index = record.font

        psName = getPostScriptName(record.font)
        if record.psName:
            # If fonts element has postScriptName attribute, it should match with the PostScript
            # name in the name table.
            assert psName == record.psName, ('postScriptName attribute %s should match with %s' % (
                record.psName, psName))
        else:
            # If fonts element doesn't have postScriptName attribute, the file name should match
            # with the PostScript name in the name table.
            assert psName == file_name[:-4], ('file name %s should match with %s' % (
                file_name, psName))


def main():
    global _fonts_dir
    target_out = sys.argv[1]
    _fonts_dir = path.join(target_out, 'fonts')

    fonts_xml_path = path.join(target_out, 'etc', 'fonts.xml')

    parse_fonts_xml(fonts_xml_path)

    check_compact_only_fallback()

    check_vertical_metrics()

    hyphens_dir = path.join(target_out, 'usr', 'hyphen-data')
    check_hyphens(hyphens_dir)

    check_cjk_punctuation()

    check_canonical_name()

    check_emoji = sys.argv[2]
    if check_emoji == 'true':
        ucd_path = sys.argv[3]
        parse_ucd(ucd_path)
        all_emoji, default_emoji, equivalent_emoji = compute_expected_emoji()
        check_emoji_compat(all_emoji, equivalent_emoji)
        check_emoji_coverage(all_emoji, equivalent_emoji)
        check_emoji_defaults(default_emoji)


if __name__ == '__main__':
    main()
