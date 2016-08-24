/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "compile/Pseudolocalizer.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <gtest/gtest.h>

namespace aapt {

// In this context, 'Axis' represents a particular field in the configuration,
// such as language or density.

static ::testing::AssertionResult simpleHelper(const char* input, const char* expected,
                                               Pseudolocalizer::Method method) {
    Pseudolocalizer pseudo(method);
    std::string result = util::utf16ToUtf8(
            pseudo.start() + pseudo.text(util::utf8ToUtf16(input)) + pseudo.end());
    if (StringPiece(expected) != result) {
        return ::testing::AssertionFailure() << expected << " != " << result;
    }
    return ::testing::AssertionSuccess();
}

static ::testing::AssertionResult compoundHelper(const char* in1, const char* in2, const char *in3,
                                                 const char* expected,
                                                 Pseudolocalizer::Method method) {
    Pseudolocalizer pseudo(method);
    std::string result = util::utf16ToUtf8(pseudo.start() +
                                           pseudo.text(util::utf8ToUtf16(in1)) +
                                           pseudo.text(util::utf8ToUtf16(in2)) +
                                           pseudo.text(util::utf8ToUtf16(in3)) +
                                           pseudo.end());
    if (StringPiece(expected) != result) {
        return ::testing::AssertionFailure() << expected << " != " << result;
    }
    return ::testing::AssertionSuccess();
}

TEST(PseudolocalizerTest, NoPseudolocalization) {
    EXPECT_TRUE(simpleHelper("", "", Pseudolocalizer::Method::kNone));
    EXPECT_TRUE(simpleHelper("Hello, world", "Hello, world", Pseudolocalizer::Method::kNone));

    EXPECT_TRUE(compoundHelper("Hello,", " world", "",
                               "Hello, world", Pseudolocalizer::Method::kNone));
}

TEST(PseudolocalizerTest, PlaintextAccent) {
    EXPECT_TRUE(simpleHelper("", "[]", Pseudolocalizer::Method::kAccent));
    EXPECT_TRUE(simpleHelper("Hello, world",
                             "[Ĥéļļö, ŵöŕļð one two]", Pseudolocalizer::Method::kAccent));

    EXPECT_TRUE(simpleHelper("Hello, %1d",
                             "[Ĥéļļö, »%1d« one two]", Pseudolocalizer::Method::kAccent));

    EXPECT_TRUE(simpleHelper("Battery %1d%%",
                             "[βåţţéŕý »%1d«%% one two]", Pseudolocalizer::Method::kAccent));

    EXPECT_TRUE(compoundHelper("", "", "", "[]", Pseudolocalizer::Method::kAccent));
    EXPECT_TRUE(compoundHelper("Hello,", " world", "",
                               "[Ĥéļļö, ŵöŕļð one two]", Pseudolocalizer::Method::kAccent));
}

TEST(PseudolocalizerTest, PlaintextBidi) {
    EXPECT_TRUE(simpleHelper("", "", Pseudolocalizer::Method::kBidi));
    EXPECT_TRUE(simpleHelper("word",
                             "\xe2\x80\x8f\xE2\x80\xaeword\xE2\x80\xac\xe2\x80\x8f",
                             Pseudolocalizer::Method::kBidi));
    EXPECT_TRUE(simpleHelper("  word  ",
                             "  \xe2\x80\x8f\xE2\x80\xaeword\xE2\x80\xac\xe2\x80\x8f  ",
                             Pseudolocalizer::Method::kBidi));
    EXPECT_TRUE(simpleHelper("  word  ",
                             "  \xe2\x80\x8f\xE2\x80\xaeword\xE2\x80\xac\xe2\x80\x8f  ",
                             Pseudolocalizer::Method::kBidi));
    EXPECT_TRUE(simpleHelper("hello\n  world\n",
                             "\xe2\x80\x8f\xE2\x80\xaehello\xE2\x80\xac\xe2\x80\x8f\n" \
                                     "  \xe2\x80\x8f\xE2\x80\xaeworld\xE2\x80\xac\xe2\x80\x8f\n",
                             Pseudolocalizer::Method::kBidi));
    EXPECT_TRUE(compoundHelper("hello", "\n ", " world\n",
                               "\xe2\x80\x8f\xE2\x80\xaehello\xE2\x80\xac\xe2\x80\x8f\n" \
                                       "  \xe2\x80\x8f\xE2\x80\xaeworld\xE2\x80\xac\xe2\x80\x8f\n",
                               Pseudolocalizer::Method::kBidi));
}

TEST(PseudolocalizerTest, SimpleICU) {
    // Single-fragment messages
    EXPECT_TRUE(simpleHelper("{placeholder}", "[»{placeholder}«]",
                             Pseudolocalizer::Method::kAccent));
    EXPECT_TRUE(simpleHelper("{USER} is offline",
                             "[»{USER}« îš öƒƒļîñé one two]", Pseudolocalizer::Method::kAccent));
    EXPECT_TRUE(simpleHelper("Copy from {path1} to {path2}",
                             "[Çöþý ƒŕöḿ »{path1}« ţö »{path2}« one two three]",
                             Pseudolocalizer::Method::kAccent));
    EXPECT_TRUE(simpleHelper("Today is {1,date} {1,time}",
                             "[Ţöðåý îš »{1,date}« »{1,time}« one two]",
                             Pseudolocalizer::Method::kAccent));

    // Multi-fragment messages
    EXPECT_TRUE(compoundHelper("{USER}", " ", "is offline",
                               "[»{USER}« îš öƒƒļîñé one two]",
                               Pseudolocalizer::Method::kAccent));
    EXPECT_TRUE(compoundHelper("Copy from ", "{path1}", " to {path2}",
                               "[Çöþý ƒŕöḿ »{path1}« ţö »{path2}« one two three]",
                               Pseudolocalizer::Method::kAccent));
}

TEST(PseudolocalizerTest, ICUBidi) {
    // Single-fragment messages
    EXPECT_TRUE(simpleHelper("{placeholder}",
                             "\xe2\x80\x8f\xE2\x80\xae{placeholder}\xE2\x80\xac\xe2\x80\x8f",
                             Pseudolocalizer::Method::kBidi));
    EXPECT_TRUE(simpleHelper(
            "{COUNT, plural, one {one} other {other}}",
            "{COUNT, plural, " \
                    "one {\xe2\x80\x8f\xE2\x80\xaeone\xE2\x80\xac\xe2\x80\x8f} " \
                    "other {\xe2\x80\x8f\xE2\x80\xaeother\xE2\x80\xac\xe2\x80\x8f}}",
            Pseudolocalizer::Method::kBidi));
}

TEST(PseudolocalizerTest, Escaping) {
    // Single-fragment messages
    EXPECT_TRUE(simpleHelper("'{USER'} is offline",
                             "['{ÛŠÉŔ'} îš öƒƒļîñé one two three]",
                             Pseudolocalizer::Method::kAccent));

    // Multi-fragment messages
    EXPECT_TRUE(compoundHelper("'{USER}", " ", "''is offline",
                               "['{ÛŠÉŔ} ''îš öƒƒļîñé one two three]",
                               Pseudolocalizer::Method::kAccent));
}

TEST(PseudolocalizerTest, PluralsAndSelects) {
    EXPECT_TRUE(simpleHelper(
            "{COUNT, plural, one {Delete a file} other {Delete {COUNT} files}}",
            "[{COUNT, plural, one {Ðéļéţé å ƒîļé one two} " \
                     "other {Ðéļéţé »{COUNT}« ƒîļéš one two}}]",
            Pseudolocalizer::Method::kAccent));

    EXPECT_TRUE(simpleHelper(
            "Distance is {COUNT, plural, one {# mile} other {# miles}}",
            "[Ðîšţåñçé îš {COUNT, plural, one {# ḿîļé one two} " \
                                 "other {# ḿîļéš one two}}]",
            Pseudolocalizer::Method::kAccent));

    EXPECT_TRUE(simpleHelper(
            "{1, select, female {{1} added you} " \
                    "male {{1} added you} other {{1} added you}}",
            "[{1, select, female {»{1}« åððéð ýöû one two} " \
                    "male {»{1}« åððéð ýöû one two} other {»{1}« åððéð ýöû one two}}]",
            Pseudolocalizer::Method::kAccent));

    EXPECT_TRUE(compoundHelper(
            "{COUNT, plural, one {Delete a file} " \
                    "other {Delete ", "{COUNT}", " files}}",
            "[{COUNT, plural, one {Ðéļéţé å ƒîļé one two} " \
                    "other {Ðéļéţé »{COUNT}« ƒîļéš one two}}]",
            Pseudolocalizer::Method::kAccent));
}

TEST(PseudolocalizerTest, NestedICU) {
    EXPECT_TRUE(simpleHelper(
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
            Pseudolocalizer::Method::kAccent));
}

TEST(PseudolocalizerTest, RedefineMethod) {
    Pseudolocalizer pseudo(Pseudolocalizer::Method::kAccent);
    std::u16string result = pseudo.text(u"Hello, ");
    pseudo.setMethod(Pseudolocalizer::Method::kNone);
    result += pseudo.text(u"world!");
    ASSERT_EQ(StringPiece("Ĥéļļö, world!"), util::utf16ToUtf8(result));
}

} // namespace aapt
