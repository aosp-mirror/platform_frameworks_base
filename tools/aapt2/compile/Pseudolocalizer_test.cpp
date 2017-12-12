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

#include "test/Test.h"
#include "util/Util.h"

using android::StringPiece;

namespace aapt {

// In this context, 'Axis' represents a particular field in the configuration,
// such as language or density.

static ::testing::AssertionResult SimpleHelper(const char* input,
                                               const char* expected,
                                               Pseudolocalizer::Method method) {
  Pseudolocalizer pseudo(method);
  std::string result = pseudo.Start() + pseudo.Text(input) + pseudo.End();
  if (result != expected) {
    return ::testing::AssertionFailure() << expected << " != " << result;
  }
  return ::testing::AssertionSuccess();
}

static ::testing::AssertionResult CompoundHelper(
    const char* in1, const char* in2, const char* in3, const char* expected,
    Pseudolocalizer::Method method) {
  Pseudolocalizer pseudo(method);
  std::string result = pseudo.Start() + pseudo.Text(in1) + pseudo.Text(in2) +
                       pseudo.Text(in3) + pseudo.End();
  if (result != expected) {
    return ::testing::AssertionFailure() << expected << " != " << result;
  }
  return ::testing::AssertionSuccess();
}

TEST(PseudolocalizerTest, NoPseudolocalization) {
  EXPECT_TRUE(SimpleHelper("", "", Pseudolocalizer::Method::kNone));
  EXPECT_TRUE(SimpleHelper("Hello, world", "Hello, world",
                           Pseudolocalizer::Method::kNone));

  EXPECT_TRUE(CompoundHelper("Hello,", " world", "", "Hello, world",
                             Pseudolocalizer::Method::kNone));
}

TEST(PseudolocalizerTest, PlaintextAccent) {
  EXPECT_TRUE(SimpleHelper("", "[]", Pseudolocalizer::Method::kAccent));
  EXPECT_TRUE(SimpleHelper("Hello, world", "[Ĥéļļö, ŵöŕļð one two]",
                           Pseudolocalizer::Method::kAccent));

  EXPECT_TRUE(SimpleHelper("Hello, %1d", "[Ĥéļļö, »%1d« one two]",
                           Pseudolocalizer::Method::kAccent));

  EXPECT_TRUE(SimpleHelper("Battery %1d%%", "[βåţţéŕý »%1d«%% one two]",
                           Pseudolocalizer::Method::kAccent));
  EXPECT_TRUE(
      SimpleHelper("^1 %", "[^1 % one]", Pseudolocalizer::Method::kAccent));
  EXPECT_TRUE(
      CompoundHelper("", "", "", "[]", Pseudolocalizer::Method::kAccent));
  EXPECT_TRUE(CompoundHelper("Hello,", " world", "", "[Ĥéļļö, ŵöŕļð one two]",
                             Pseudolocalizer::Method::kAccent));
}

TEST(PseudolocalizerTest, PlaintextBidi) {
  EXPECT_TRUE(SimpleHelper("", "", Pseudolocalizer::Method::kBidi));
  EXPECT_TRUE(SimpleHelper(
      "word", "\xe2\x80\x8f\xE2\x80\xaeword\xE2\x80\xac\xe2\x80\x8f",
      Pseudolocalizer::Method::kBidi));
  EXPECT_TRUE(SimpleHelper(
      "  word  ", "  \xe2\x80\x8f\xE2\x80\xaeword\xE2\x80\xac\xe2\x80\x8f  ",
      Pseudolocalizer::Method::kBidi));
  EXPECT_TRUE(SimpleHelper(
      "  word  ", "  \xe2\x80\x8f\xE2\x80\xaeword\xE2\x80\xac\xe2\x80\x8f  ",
      Pseudolocalizer::Method::kBidi));
  EXPECT_TRUE(
      SimpleHelper("hello\n  world\n",
                   "\xe2\x80\x8f\xE2\x80\xaehello\xE2\x80\xac\xe2\x80\x8f\n"
                   "  \xe2\x80\x8f\xE2\x80\xaeworld\xE2\x80\xac\xe2\x80\x8f\n",
                   Pseudolocalizer::Method::kBidi));
  EXPECT_TRUE(CompoundHelper(
      "hello", "\n ", " world\n",
      "\xe2\x80\x8f\xE2\x80\xaehello\xE2\x80\xac\xe2\x80\x8f\n"
      "  \xe2\x80\x8f\xE2\x80\xaeworld\xE2\x80\xac\xe2\x80\x8f\n",
      Pseudolocalizer::Method::kBidi));
  EXPECT_TRUE(
      SimpleHelper("hello\\nworld\\n",
                   "\xe2\x80\x8f\xE2\x80\xaehello\xE2\x80\xac\xe2\x80\x8f\\n"
                   "\xe2\x80\x8f\xE2\x80\xaeworld\xE2\x80\xac\xe2\x80\x8f\\n",
                   Pseudolocalizer::Method::kBidi));
}

TEST(PseudolocalizerTest, SimpleICU) {
  // Single-fragment messages
  EXPECT_TRUE(SimpleHelper("{placeholder}", "[»{placeholder}«]",
                           Pseudolocalizer::Method::kAccent));
  EXPECT_TRUE(SimpleHelper("{USER} is offline", "[»{USER}« îš öƒƒļîñé one two]",
                           Pseudolocalizer::Method::kAccent));
  EXPECT_TRUE(SimpleHelper("Copy from {path1} to {path2}",
                           "[Çöþý ƒŕöḿ »{path1}« ţö »{path2}« one two three]",
                           Pseudolocalizer::Method::kAccent));
  EXPECT_TRUE(SimpleHelper("Today is {1,date} {1,time}",
                           "[Ţöðåý îš »{1,date}« »{1,time}« one two]",
                           Pseudolocalizer::Method::kAccent));

  // Multi-fragment messages
  EXPECT_TRUE(CompoundHelper("{USER}", " ", "is offline",
                             "[»{USER}« îš öƒƒļîñé one two]",
                             Pseudolocalizer::Method::kAccent));
  EXPECT_TRUE(CompoundHelper("Copy from ", "{path1}", " to {path2}",
                             "[Çöþý ƒŕöḿ »{path1}« ţö »{path2}« one two three]",
                             Pseudolocalizer::Method::kAccent));
}

TEST(PseudolocalizerTest, ICUBidi) {
  // Single-fragment messages
  EXPECT_TRUE(SimpleHelper(
      "{placeholder}",
      "\xe2\x80\x8f\xE2\x80\xae{placeholder}\xE2\x80\xac\xe2\x80\x8f",
      Pseudolocalizer::Method::kBidi));
  EXPECT_TRUE(SimpleHelper(
      "{COUNT, plural, one {one} other {other}}",
      "{COUNT, plural, "
      "one {\xe2\x80\x8f\xE2\x80\xaeone\xE2\x80\xac\xe2\x80\x8f} "
      "other {\xe2\x80\x8f\xE2\x80\xaeother\xE2\x80\xac\xe2\x80\x8f}}",
      Pseudolocalizer::Method::kBidi));
}

TEST(PseudolocalizerTest, Escaping) {
  // Single-fragment messages
  EXPECT_TRUE(SimpleHelper("'{USER'} is offline",
                           "['{ÛŠÉŔ'} îš öƒƒļîñé one two three]",
                           Pseudolocalizer::Method::kAccent));

  // Multi-fragment messages
  EXPECT_TRUE(CompoundHelper("'{USER}", " ", "''is offline",
                             "['{ÛŠÉŔ} ''îš öƒƒļîñé one two three]",
                             Pseudolocalizer::Method::kAccent));
}

TEST(PseudolocalizerTest, PluralsAndSelects) {
  EXPECT_TRUE(SimpleHelper(
      "{COUNT, plural, one {Delete a file} other {Delete {COUNT} files}}",
      "[{COUNT, plural, one {Ðéļéţé å ƒîļé one two} "
      "other {Ðéļéţé »{COUNT}« ƒîļéš one two}}]",
      Pseudolocalizer::Method::kAccent));

  EXPECT_TRUE(
      SimpleHelper("Distance is {COUNT, plural, one {# mile} other {# miles}}",
                   "[Ðîšţåñçé îš {COUNT, plural, one {# ḿîļé one two} "
                   "other {# ḿîļéš one two}}]",
                   Pseudolocalizer::Method::kAccent));

  EXPECT_TRUE(SimpleHelper(
      "{1, select, female {{1} added you} "
      "male {{1} added you} other {{1} added you}}",
      "[{1, select, female {»{1}« åððéð ýöû one two} "
      "male {»{1}« åððéð ýöû one two} other {»{1}« åððéð ýöû one two}}]",
      Pseudolocalizer::Method::kAccent));

  EXPECT_TRUE(
      CompoundHelper("{COUNT, plural, one {Delete a file} "
                     "other {Delete ",
                     "{COUNT}", " files}}",
                     "[{COUNT, plural, one {Ðéļéţé å ƒîļé one two} "
                     "other {Ðéļéţé »{COUNT}« ƒîļéš one two}}]",
                     Pseudolocalizer::Method::kAccent));
}

TEST(PseudolocalizerTest, NestedICU) {
  EXPECT_TRUE(
      SimpleHelper("{person, select, "
                   "female {"
                   "{num_circles, plural,"
                   "=0{{person} didn't add you to any of her circles.}"
                   "=1{{person} added you to one of her circles.}"
                   "other{{person} added you to her # circles.}}}"
                   "male {"
                   "{num_circles, plural,"
                   "=0{{person} didn't add you to any of his circles.}"
                   "=1{{person} added you to one of his circles.}"
                   "other{{person} added you to his # circles.}}}"
                   "other {"
                   "{num_circles, plural,"
                   "=0{{person} didn't add you to any of their circles.}"
                   "=1{{person} added you to one of their circles.}"
                   "other{{person} added you to their # circles.}}}}",
                   "[{person, select, "
                   "female {"
                   "{num_circles, plural,"
                   "=0{»{person}« ðîðñ'ţ åðð ýöû ţö åñý öƒ ĥéŕ çîŕçļéš."
                   " one two three four five}"
                   "=1{»{person}« åððéð ýöû ţö öñé öƒ ĥéŕ çîŕçļéš."
                   " one two three four}"
                   "other{»{person}« åððéð ýöû ţö ĥéŕ # çîŕçļéš."
                   " one two three four}}}"
                   "male {"
                   "{num_circles, plural,"
                   "=0{»{person}« ðîðñ'ţ åðð ýöû ţö åñý öƒ ĥîš çîŕçļéš."
                   " one two three four five}"
                   "=1{»{person}« åððéð ýöû ţö öñé öƒ ĥîš çîŕçļéš."
                   " one two three four}"
                   "other{»{person}« åððéð ýöû ţö ĥîš # çîŕçļéš."
                   " one two three four}}}"
                   "other {{num_circles, plural,"
                   "=0{»{person}« ðîðñ'ţ åðð ýöû ţö åñý öƒ ţĥéîŕ çîŕçļéš."
                   " one two three four five}"
                   "=1{»{person}« åððéð ýöû ţö öñé öƒ ţĥéîŕ çîŕçļéš."
                   " one two three four}"
                   "other{»{person}« åððéð ýöû ţö ţĥéîŕ # çîŕçļéš."
                   " one two three four}}}}]",
                   Pseudolocalizer::Method::kAccent));
}

TEST(PseudolocalizerTest, RedefineMethod) {
  Pseudolocalizer pseudo(Pseudolocalizer::Method::kAccent);
  std::string result = pseudo.Text("Hello, ");
  pseudo.SetMethod(Pseudolocalizer::Method::kNone);
  result += pseudo.Text("world!");
  ASSERT_EQ(StringPiece("Ĥéļļö, world!"), result);
}

}  // namespace aapt
