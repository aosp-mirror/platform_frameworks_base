#!/usr/bin/env python

# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Enforces common Android string best-practices.  It ignores lint messages from
a previous strings file, if provided.

Usage: stringslint.py strings.xml
Usage: stringslint.py strings.xml old_strings.xml

In general:
* Errors signal issues that must be fixed before submitting, and are only
  used when there are no false-positives.
* Warnings signal issues that might need to be fixed, but need manual
  inspection due to risk of false-positives.
* Info signal issues that should be fixed to match best-practices, such
  as providing comments to aid translation.
"""

import re, sys, codecs
import lxml.etree as ET

reload(sys)
sys.setdefaultencoding('utf8')

BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE = range(8)

def format(fg=None, bg=None, bright=False, bold=False, dim=False, reset=False):
    # manually derived from http://en.wikipedia.org/wiki/ANSI_escape_code#Codes
    codes = []
    if reset: codes.append("0")
    else:
        if not fg is None: codes.append("3%d" % (fg))
        if not bg is None:
            if not bright: codes.append("4%d" % (bg))
            else: codes.append("10%d" % (bg))
        if bold: codes.append("1")
        elif dim: codes.append("2")
        else: codes.append("22")
    return "\033[%sm" % (";".join(codes))

warnings = None

def warn(tag, msg, actual, expected, color=YELLOW):
    global warnings
    key = "%s:%d" % (tag.attrib["name"], hash(msg))
    value = "%sLine %d: '%s':%s %s" % (format(fg=color, bold=True),
                                       tag.sourceline,
                                       tag.attrib["name"],
                                       format(reset=True),
                                       msg)
    if not actual is None: value += "\n\tActual: %s%s%s" % (format(dim=True),
                                                            actual,
                                                            format(reset=True))
    if not expected is None: value += "\n\tExample: %s%s%s" % (format(dim=True),
                                                               expected,
                                                               format(reset=True))
    warnings[key] = value


def error(tag, msg, actual, expected):
    warn(tag, msg, actual, expected, RED)

def info(tag, msg, actual, expected):
    warn(tag, msg, actual, expected, CYAN)

# Escaping logic borrowed from https://stackoverflow.com/a/24519338
ESCAPE_SEQUENCE_RE = re.compile(r'''
    ( \\U........      # 8-digit hex escapes
    | \\u....          # 4-digit hex escapes
    | \\x..            # 2-digit hex escapes
    | \\[0-7]{1,3}     # Octal escapes
    | \\N\{[^}]+\}     # Unicode characters by name
    | \\[\\'"abfnrtv]  # Single-character escapes
    )''', re.UNICODE | re.VERBOSE)

def decode_escapes(s):
    def decode_match(match):
        return codecs.decode(match.group(0), 'unicode-escape')

    s = re.sub(r"\n\s*", " ", s)
    s = ESCAPE_SEQUENCE_RE.sub(decode_match, s)
    s = re.sub(r"%(\d+\$)?[a-z]", "____", s)
    s = re.sub(r"\^\d+", "____", s)
    s = re.sub(r"<br/?>", "\n", s)
    s = re.sub(r"</?[a-z]+>", "", s)
    return s

def sample_iter(tag):
    if not isinstance(tag, ET._Comment) and re.match("{.*xliff.*}g", tag.tag) and "example" in tag.attrib:
        yield tag.attrib["example"]
    elif tag.text:
        yield decode_escapes(tag.text)
    for e in tag:
        for v in sample_iter(e):
            yield v
        if e.tail:
            yield decode_escapes(e.tail)

def lint(path):
    global warnings
    warnings = {}

    with open(path) as f:
        raw = f.read()
        if len(raw.strip()) == 0:
            return warnings
        tree = ET.fromstring(raw)
        root = tree #tree.getroot()

    last_comment = None
    for child in root:
        # TODO: handle plurals
        if isinstance(child, ET._Comment):
            last_comment = child
        elif child.tag == "string":
            # We always consume comment
            comment = last_comment
            last_comment = None

            # Prepare string for analysis
            text = "".join(child.itertext())
            sample = "".join(sample_iter(child)).strip().strip("'\"")

            # Validate comment
            if comment is None:
                info(child, "Missing string comment to aid translation",
                     None, None)
                continue
            if "do not translate" in comment.text.lower():
                continue
            if "translatable" in child.attrib and child.attrib["translatable"].lower() == "false":
                continue

            misspelled_attributes = [
              ("translateable", "translatable"),
            ]
            for misspelling, expected in misspelled_attributes:
                if misspelling in child.attrib:
                    error(child, "Misspelled <string> attribute.", misspelling, expected)

            limit = re.search("CHAR[ _-]LIMIT=(\d+|NONE|none)", comment.text)
            if limit is None:
                info(child, "Missing CHAR LIMIT to aid translation",
                     repr(comment), "<!-- Description of string [CHAR LIMIT=32] -->")
            elif re.match("\d+", limit.group(1)):
                limit = int(limit.group(1))
                if len(sample) > limit:
                    warn(child, "Expanded string length is larger than CHAR LIMIT",
                        sample, None)

            # Look for common mistakes/substitutions
            if "'" in text:
                error(child, "Turned quotation mark glyphs are more polished",
                     text, "This doesn\u2019t need to \u2018happen\u2019 today")
            if '"' in text and not text.startswith('"') and text.endswith('"'):
                error(child, "Turned quotation mark glyphs are more polished",
                     text, "This needs to \u201chappen\u201d today")
            if "..." in text:
                error(child, "Ellipsis glyph is more polished",
                     text, "Loading\u2026")
            if "wi-fi" in text.lower():
                error(child, "Non-breaking glyph is more polished",
                     text, "Wi\u2011Fi")
            if "wifi" in text.lower():
                error(child, "Using non-standard spelling",
                     text, "Wi\u2011Fi")
            if re.search("\d-\d", text):
                warn(child, "Ranges should use en dash glyph",
                     text, "You will find this material in chapters 8\u201312")
            if "--" in text:
                warn(child, "Phrases should use em dash glyph",
                     text, "Upon discovering errors\u2014all 124 of them\u2014they recalled.")
            if ".  " in text:
                warn(child, "Only use single space between sentences",
                     text, "First idea. Second idea.")
            if re.match(r"^[A-Z\s]{5,}$", text):
                warn(child, "Actions should use android:textAllCaps in layout; ignore if acronym",
                     text, "Refresh data")
            if " phone " in text and "product" not in child.attrib:
                warn(child, "Strings mentioning phones should have variants for tablets",
                     text, None)

            # When more than one substitution, require indexes
            if len(re.findall("%[^%]", text)) > 1:
                if len(re.findall("%[^\d]", text)) > 0:
                    error(child, "Substitutions must be indexed",
                         text, "Add %1$s to %2$s")

            # Require xliff substitutions
            for gc in child.iter():
                badsub = False
                if gc.tail and re.search("%[^%]", gc.tail): badsub = True
                if re.match("{.*xliff.*}g", gc.tag):
                    if "id" not in gc.attrib:
                        error(child, "Substitutions must define id attribute",
                             None, "<xliff:g id=\"domain\" example=\"example.com\">%1$s</xliff:g>")
                    if "example" not in gc.attrib:
                        error(child, "Substitutions must define example attribute",
                             None, "<xliff:g id=\"domain\" example=\"example.com\">%1$s</xliff:g>")
                else:
                    if gc.text and re.search("%[^%]", gc.text): badsub = True
                if badsub:
                    error(child, "Substitutions must be inside xliff tags",
                         text, "<xliff:g id=\"domain\" example=\"example.com\">%1$s</xliff:g>")

    return warnings

if len(sys.argv) > 2:
    before = lint(sys.argv[2])
else:
    before = {}
after = lint(sys.argv[1])

for b in before:
    if b in after:
        del after[b]

if len(after) > 0:
    for a in sorted(after.keys()):
        print after[a]
        print
    sys.exit(1)
