/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include <androidfw/ResourceTypes.h>
#include <utils/String8.h>
#include <gtest/gtest.h>

#include "Bundle.h"
#include "pseudolocalize.h"

using android::String8;

// In this context, 'Axis' represents a particular field in the configuration,
// such as language or density.

static void simple_helper(const char* input, const char* expected, PseudolocalizationMethod method) {
    Pseudolocalizer pseudo(method);
    String16 result = pseudo.start() + pseudo.text(String16(String8(input))) + pseudo.end();
    //std::cout << String8(result).string() << std::endl;
    ASSERT_EQ(String8(expected), String8(result));
}

static void compound_helper(const char* in1, const char* in2, const char *in3,
                            const char* expected, PseudolocalizationMethod method) {
    Pseudolocalizer pseudo(method);
    String16 result = pseudo.start() + \
                      pseudo.text(String16(String8(in1))) + \
                      pseudo.text(String16(String8(in2))) + \
                      pseudo.text(String16(String8(in3))) + \
                      pseudo.end();
    ASSERT_EQ(String8(expected), String8(result));
}

TEST(Pseudolocales, NoPseudolocalization) {
  simple_helper("", "", NO_PSEUDOLOCALIZATION);
  simple_helper("Hello, world", "Hello, world", NO_PSEUDOLOCALIZATION);

  compound_helper("Hello,", " world", "",
                  "Hello, world", NO_PSEUDOLOCALIZATION);
}

TEST(Pseudolocales, PlaintextAccent) {
  simple_helper("", "[]", PSEUDO_ACCENTED);
  simple_helper("Hello, world",
                "[Ĥéļļö, ŵöŕļð one two]", PSEUDO_ACCENTED);

  simple_helper("Hello, %1d",
                "[Ĥéļļö, »%1d« one two]", PSEUDO_ACCENTED);

  simple_helper("Battery %1d%%",
                "[βåţţéŕý »%1d«%% one two]", PSEUDO_ACCENTED);

  compound_helper("", "", "", "[]", PSEUDO_ACCENTED);
  compound_helper("Hello,", " world", "",
                  "[Ĥéļļö, ŵöŕļð one two]", PSEUDO_ACCENTED);
}

TEST(Pseudolocales, PlaintextBidi) {
  simple_helper("", "", PSEUDO_BIDI);
  simple_helper("word",
                "\xe2\x80\x8f\xE2\x80\xaeword\xE2\x80\xac\xe2\x80\x8f",
                PSEUDO_BIDI);
  simple_helper("  word  ",
                "  \xe2\x80\x8f\xE2\x80\xaeword\xE2\x80\xac\xe2\x80\x8f  ",
                PSEUDO_BIDI);
  simple_helper("  word  ",
                "  \xe2\x80\x8f\xE2\x80\xaeword\xE2\x80\xac\xe2\x80\x8f  ",
                PSEUDO_BIDI);
  simple_helper("hello\n  world\n",
                "\xe2\x80\x8f\xE2\x80\xaehello\xE2\x80\xac\xe2\x80\x8f\n" \
                "  \xe2\x80\x8f\xE2\x80\xaeworld\xE2\x80\xac\xe2\x80\x8f\n",
                PSEUDO_BIDI);
  compound_helper("hello", "\n ", " world\n",
                "\xe2\x80\x8f\xE2\x80\xaehello\xE2\x80\xac\xe2\x80\x8f\n" \
                "  \xe2\x80\x8f\xE2\x80\xaeworld\xE2\x80\xac\xe2\x80\x8f\n",
                PSEUDO_BIDI);
}

TEST(Pseudolocales, SimpleICU) {
  // Single-fragment messages
  simple_helper("{placeholder}", "[»{placeholder}«]", PSEUDO_ACCENTED);
  simple_helper("{USER} is offline",
              "[»{USER}« îš öƒƒļîñé one two]", PSEUDO_ACCENTED);
  simple_helper("Copy from {path1} to {path2}",
              "[Çöþý ƒŕöḿ »{path1}« ţö »{path2}« one two three]", PSEUDO_ACCENTED);
  simple_helper("Today is {1,date} {1,time}",
              "[Ţöðåý îš »{1,date}« »{1,time}« one two]", PSEUDO_ACCENTED);

  // Multi-fragment messages
  compound_helper("{USER}", " ", "is offline",
                  "[»{USER}« îš öƒƒļîñé one two]",
                  PSEUDO_ACCENTED);
  compound_helper("Copy from ", "{path1}", " to {path2}",
                  "[Çöþý ƒŕöḿ »{path1}« ţö »{path2}« one two three]",
                  PSEUDO_ACCENTED);
}

TEST(Pseudolocales, ICUBidi) {
  // Single-fragment messages
  simple_helper("{placeholder}",
                "\xe2\x80\x8f\xE2\x80\xae{placeholder}\xE2\x80\xac\xe2\x80\x8f",
                PSEUDO_BIDI);
  simple_helper(
      "{COUNT, plural, one {one} other {other}}",
      "{COUNT, plural, " \
          "one {\xe2\x80\x8f\xE2\x80\xaeone\xE2\x80\xac\xe2\x80\x8f} " \
        "other {\xe2\x80\x8f\xE2\x80\xaeother\xE2\x80\xac\xe2\x80\x8f}}",
      PSEUDO_BIDI
  );
}

TEST(Pseudolocales, Escaping) {
  // Single-fragment messages
  simple_helper("'{USER'} is offline",
                "['{ÛŠÉŔ'} îš öƒƒļîñé one two three]", PSEUDO_ACCENTED);

  // Multi-fragment messages
  compound_helper("'{USER}", " ", "''is offline",
                  "['{ÛŠÉŔ} ''îš öƒƒļîñé one two three]", PSEUDO_ACCENTED);
}

TEST(Pseudolocales, PluralsAndSelects) {
  simple_helper(
      "{COUNT, plural, one {Delete a file} other {Delete {COUNT} files}}",
      "[{COUNT, plural, one {Ðéļéţé å ƒîļé one two} " \
                     "other {Ðéļéţé »{COUNT}« ƒîļéš one two}}]",
      PSEUDO_ACCENTED
  );
  simple_helper(
      "Distance is {COUNT, plural, one {# mile} other {# miles}}",
      "[Ðîšţåñçé îš {COUNT, plural, one {# ḿîļé one two} " \
                                 "other {# ḿîļéš one two}}]",
      PSEUDO_ACCENTED
  );
  simple_helper(
      "{1, select, female {{1} added you} " \
        "male {{1} added you} other {{1} added you}}",
      "[{1, select, female {»{1}« åððéð ýöû one two} " \
        "male {»{1}« åððéð ýöû one two} other {»{1}« åððéð ýöû one two}}]",
      PSEUDO_ACCENTED
  );

  compound_helper(
      "{COUNT, plural, one {Delete a file} " \
        "other {Delete ", "{COUNT}", " files}}",
      "[{COUNT, plural, one {Ðéļéţé å ƒîļé one two} " \
        "other {Ðéļéţé »{COUNT}« ƒîļéš one two}}]",
      PSEUDO_ACCENTED
  );
}

TEST(Pseudolocales, NestedICU) {
  simple_helper(
      "{person, select, " \
        "female {" \
          "{num_circles, plural," \
            "=0{{person} didn't add you to any of her circles.}" \
            "=1{{person} added you to one of her circles.}" \
            "other{{person} added you to her # circles.}}}" \
        "male {" \
          "{num_circles, plural," \
            "=0{{person} didn't add you to any of his circles.}" \
            "=1{{person} added you to one of his circles.}" \
            "other{{person} added you to his # circles.}}}" \
        "other {" \
          "{num_circles, plural," \
            "=0{{person} didn't add you to any of their circles.}" \
            "=1{{person} added you to one of their circles.}" \
            "other{{person} added you to their # circles.}}}}",
      "[{person, select, " \
        "female {" \
          "{num_circles, plural," \
            "=0{»{person}« ðîðñ'ţ åðð ýöû ţö åñý öƒ ĥéŕ çîŕçļéš." \
              " one two three four five}" \
            "=1{»{person}« åððéð ýöû ţö öñé öƒ ĥéŕ çîŕçļéš." \
              " one two three four}" \
            "other{»{person}« åððéð ýöû ţö ĥéŕ # çîŕçļéš." \
              " one two three four}}}" \
        "male {" \
          "{num_circles, plural," \
            "=0{»{person}« ðîðñ'ţ åðð ýöû ţö åñý öƒ ĥîš çîŕçļéš." \
              " one two three four five}" \
            "=1{»{person}« åððéð ýöû ţö öñé öƒ ĥîš çîŕçļéš." \
              " one two three four}" \
            "other{»{person}« åððéð ýöû ţö ĥîš # çîŕçļéš." \
              " one two three four}}}" \
        "other {{num_circles, plural," \
          "=0{»{person}« ðîðñ'ţ åðð ýöû ţö åñý öƒ ţĥéîŕ çîŕçļéš." \
            " one two three four five}" \
          "=1{»{person}« åððéð ýöû ţö öñé öƒ ţĥéîŕ çîŕçļéš." \
            " one two three four}" \
          "other{»{person}« åððéð ýöû ţö ţĥéîŕ # çîŕçļéš." \
            " one two three four}}}}]",
      PSEUDO_ACCENTED
  );
}

TEST(Pseudolocales, RedefineMethod) {
  Pseudolocalizer pseudo(PSEUDO_ACCENTED);
  String16 result = pseudo.text(String16(String8("Hello, ")));
  pseudo.setMethod(NO_PSEUDOLOCALIZATION);
  result.append(pseudo.text(String16(String8("world!"))));
  ASSERT_EQ(String8("Ĥéļļö, world!"), String8(result));
}
