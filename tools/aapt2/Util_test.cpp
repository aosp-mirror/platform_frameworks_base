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

#include <gtest/gtest.h>
#include <string>

#include "StringPiece.h"
#include "Util.h"

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

TEST(UtilTest, FullyQualifiedClassName) {
    Maybe<std::u16string> res = util::getFullyQualifiedClassName(u"android", u"asdf");
    ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), u"android.asdf");

    res = util::getFullyQualifiedClassName(u"android", u".asdf");
    ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), u"android.asdf");

    res = util::getFullyQualifiedClassName(u"android", u".a.b");
    ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), u"android.a.b");

    res = util::getFullyQualifiedClassName(u"android", u"a.b");
    ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), u"a.b");

    res = util::getFullyQualifiedClassName(u"", u"a.b");
    ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), u"a.b");

    res = util::getFullyQualifiedClassName(u"", u"");
    ASSERT_FALSE(res);

    res = util::getFullyQualifiedClassName(u"android", u"./Apple");
    ASSERT_FALSE(res);
}


} // namespace aapt
