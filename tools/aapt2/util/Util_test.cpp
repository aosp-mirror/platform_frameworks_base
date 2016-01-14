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

#include "test/Common.h"
#include "util/StringPiece.h"
#include "util/Util.h"

#include <gtest/gtest.h>
#include <string>

namespace aapt {

TEST(UtilTest, TrimOnlyWhitespace) {
    const std::u16string full = u"\n        ";

    StringPiece16 trimmed = util::trimWhitespace(full);
    EXPECT_TRUE(trimmed.empty());
    EXPECT_EQ(0u, trimmed.size());
}

TEST(UtilTest, StringEndsWith) {
    EXPECT_TRUE(util::stringEndsWith<char>("hello.xml", ".xml"));
}

TEST(UtilTest, StringStartsWith) {
    EXPECT_TRUE(util::stringStartsWith<char>("hello.xml", "he"));
}

TEST(UtilTest, StringBuilderSplitEscapeSequence) {
    EXPECT_EQ(StringPiece16(u"this is a new\nline."),
              util::StringBuilder().append(u"this is a new\\")
                                   .append(u"nline.")
                                   .str());
}

TEST(UtilTest, StringBuilderWhitespaceRemoval) {
    EXPECT_EQ(StringPiece16(u"hey guys this is so cool"),
              util::StringBuilder().append(u"    hey guys ")
                                   .append(u" this is so cool ")
                                   .str());

    EXPECT_EQ(StringPiece16(u" wow,  so many \t spaces. what?"),
              util::StringBuilder().append(u" \" wow,  so many \t ")
                                   .append(u"spaces. \"what? ")
                                   .str());

    EXPECT_EQ(StringPiece16(u"where is the pie?"),
              util::StringBuilder().append(u"  where \t ")
                                   .append(u" \nis the "" pie?")
                                   .str());
}

TEST(UtilTest, StringBuilderEscaping) {
    EXPECT_EQ(StringPiece16(u"hey guys\n this \t is so\\ cool"),
              util::StringBuilder().append(u"    hey guys\\n ")
                                   .append(u" this \\t is so\\\\ cool ")
                                   .str());

    EXPECT_EQ(StringPiece16(u"@?#\\\'"),
              util::StringBuilder().append(u"\\@\\?\\#\\\\\\'")
                                   .str());
}

TEST(UtilTest, StringBuilderMisplacedQuote) {
    util::StringBuilder builder{};
    EXPECT_FALSE(builder.append(u"they're coming!"));
}

TEST(UtilTest, StringBuilderUnicodeCodes) {
    EXPECT_EQ(StringPiece16(u"\u00AF\u0AF0 woah"),
              util::StringBuilder().append(u"\\u00AF\\u0AF0 woah")
                                   .str());

    EXPECT_FALSE(util::StringBuilder().append(u"\\u00 yo"));
}

TEST(UtilTest, TokenizeInput) {
    auto tokenizer = util::tokenize(StringPiece16(u"this| is|the|end"), u'|');
    auto iter = tokenizer.begin();
    ASSERT_EQ(*iter, StringPiece16(u"this"));
    ++iter;
    ASSERT_EQ(*iter, StringPiece16(u" is"));
    ++iter;
    ASSERT_EQ(*iter, StringPiece16(u"the"));
    ++iter;
    ASSERT_EQ(*iter, StringPiece16(u"end"));
    ++iter;
    ASSERT_EQ(tokenizer.end(), iter);
}

TEST(UtilTest, TokenizeEmptyString) {
    auto tokenizer = util::tokenize(StringPiece16(u""), u'|');
    auto iter = tokenizer.begin();
    ASSERT_NE(tokenizer.end(), iter);
    ASSERT_EQ(StringPiece16(), *iter);
    ++iter;
    ASSERT_EQ(tokenizer.end(), iter);
}

TEST(UtilTest, TokenizeAtEnd) {
    auto tokenizer = util::tokenize(StringPiece16(u"one."), u'.');
    auto iter = tokenizer.begin();
    ASSERT_EQ(*iter, StringPiece16(u"one"));
    ++iter;
    ASSERT_NE(iter, tokenizer.end());
    ASSERT_EQ(*iter, StringPiece16());
}

TEST(UtilTest, IsJavaClassName) {
    EXPECT_TRUE(util::isJavaClassName(u"android.test.Class"));
    EXPECT_TRUE(util::isJavaClassName(u"android.test.Class$Inner"));
    EXPECT_TRUE(util::isJavaClassName(u"android_test.test.Class"));
    EXPECT_TRUE(util::isJavaClassName(u"_android_.test._Class_"));
    EXPECT_FALSE(util::isJavaClassName(u"android.test.$Inner"));
    EXPECT_FALSE(util::isJavaClassName(u"android.test.Inner$"));
    EXPECT_FALSE(util::isJavaClassName(u".test.Class"));
    EXPECT_FALSE(util::isJavaClassName(u"android"));
}

TEST(UtilTest, IsJavaPackageName) {
    EXPECT_TRUE(util::isJavaPackageName(u"android"));
    EXPECT_TRUE(util::isJavaPackageName(u"android.test"));
    EXPECT_TRUE(util::isJavaPackageName(u"android.test_thing"));
    EXPECT_FALSE(util::isJavaPackageName(u"_android"));
    EXPECT_FALSE(util::isJavaPackageName(u"android_"));
    EXPECT_FALSE(util::isJavaPackageName(u"android."));
    EXPECT_FALSE(util::isJavaPackageName(u".android"));
    EXPECT_FALSE(util::isJavaPackageName(u"android._test"));
    EXPECT_FALSE(util::isJavaPackageName(u".."));
}

TEST(UtilTest, FullyQualifiedClassName) {
    Maybe<std::u16string> res = util::getFullyQualifiedClassName(u"android", u"asdf");
    AAPT_ASSERT_FALSE(res);

    res = util::getFullyQualifiedClassName(u"android", u".asdf");
    AAPT_ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), u"android.asdf");

    res = util::getFullyQualifiedClassName(u"android", u".a.b");
    AAPT_ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), u"android.a.b");

    res = util::getFullyQualifiedClassName(u"android", u"a.b");
    AAPT_ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), u"a.b");

    res = util::getFullyQualifiedClassName(u"", u"a.b");
    AAPT_ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), u"a.b");

    res = util::getFullyQualifiedClassName(u"", u"");
    AAPT_ASSERT_FALSE(res);

    res = util::getFullyQualifiedClassName(u"android", u"./Apple");
    AAPT_ASSERT_FALSE(res);
}

TEST(UtilTest, ExtractResourcePathComponents) {
    StringPiece16 prefix, entry, suffix;
    ASSERT_TRUE(util::extractResFilePathParts(u"res/xml-sw600dp/entry.xml", &prefix, &entry,
                                              &suffix));
    EXPECT_EQ(prefix, u"res/xml-sw600dp/");
    EXPECT_EQ(entry, u"entry");
    EXPECT_EQ(suffix, u".xml");

    ASSERT_TRUE(util::extractResFilePathParts(u"res/xml-sw600dp/entry.9.png", &prefix, &entry,
                                              &suffix));

    EXPECT_EQ(prefix, u"res/xml-sw600dp/");
    EXPECT_EQ(entry, u"entry");
    EXPECT_EQ(suffix, u".9.png");

    EXPECT_FALSE(util::extractResFilePathParts(u"AndroidManifest.xml", &prefix, &entry, &suffix));
    EXPECT_FALSE(util::extractResFilePathParts(u"res/.xml", &prefix, &entry, &suffix));

    ASSERT_TRUE(util::extractResFilePathParts(u"res//.", &prefix, &entry, &suffix));
    EXPECT_EQ(prefix, u"res//");
    EXPECT_EQ(entry, u"");
    EXPECT_EQ(suffix, u".");
}

TEST(UtilTest, VerifyJavaStringFormat) {
    ASSERT_TRUE(util::verifyJavaStringFormat(u"%09.34f"));
    ASSERT_TRUE(util::verifyJavaStringFormat(u"%9$.34f %8$"));
    ASSERT_TRUE(util::verifyJavaStringFormat(u"%% %%"));
    ASSERT_FALSE(util::verifyJavaStringFormat(u"%09$f %f"));
    ASSERT_FALSE(util::verifyJavaStringFormat(u"%09f %08s"));
}

} // namespace aapt
