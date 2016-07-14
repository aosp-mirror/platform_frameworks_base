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

#include "test/Test.h"
#include "util/StringPiece.h"
#include "util/Util.h"

#include <string>

namespace aapt {

TEST(UtilTest, TrimOnlyWhitespace) {
    const std::string full = "\n        ";

    StringPiece trimmed = util::trimWhitespace(full);
    EXPECT_TRUE(trimmed.empty());
    EXPECT_EQ(0u, trimmed.size());
}

TEST(UtilTest, StringEndsWith) {
    EXPECT_TRUE(util::stringEndsWith("hello.xml", ".xml"));
}

TEST(UtilTest, StringStartsWith) {
    EXPECT_TRUE(util::stringStartsWith("hello.xml", "he"));
}

TEST(UtilTest, StringBuilderSplitEscapeSequence) {
    EXPECT_EQ(StringPiece("this is a new\nline."),
              util::StringBuilder().append("this is a new\\")
                                   .append("nline.")
                                   .str());
}

TEST(UtilTest, StringBuilderWhitespaceRemoval) {
    EXPECT_EQ(StringPiece("hey guys this is so cool"),
              util::StringBuilder().append("    hey guys ")
                                   .append(" this is so cool ")
                                   .str());

    EXPECT_EQ(StringPiece(" wow,  so many \t spaces. what?"),
              util::StringBuilder().append(" \" wow,  so many \t ")
                                   .append("spaces. \"what? ")
                                   .str());

    EXPECT_EQ(StringPiece("where is the pie?"),
              util::StringBuilder().append("  where \t ")
                                   .append(" \nis the "" pie?")
                                   .str());
}

TEST(UtilTest, StringBuilderEscaping) {
    EXPECT_EQ(StringPiece("hey guys\n this \t is so\\ cool"),
              util::StringBuilder().append("    hey guys\\n ")
                                   .append(" this \\t is so\\\\ cool ")
                                   .str());

    EXPECT_EQ(StringPiece("@?#\\\'"),
              util::StringBuilder().append("\\@\\?\\#\\\\\\'")
                                   .str());
}

TEST(UtilTest, StringBuilderMisplacedQuote) {
    util::StringBuilder builder{};
    EXPECT_FALSE(builder.append("they're coming!"));
}

TEST(UtilTest, StringBuilderUnicodeCodes) {
    EXPECT_EQ(std::string("\u00AF\u0AF0 woah"),
              util::StringBuilder().append("\\u00AF\\u0AF0 woah")
                                   .str());

    EXPECT_FALSE(util::StringBuilder().append("\\u00 yo"));
}

TEST(UtilTest, TokenizeInput) {
    auto tokenizer = util::tokenize(StringPiece("this| is|the|end"), '|');
    auto iter = tokenizer.begin();
    ASSERT_EQ(*iter, StringPiece("this"));
    ++iter;
    ASSERT_EQ(*iter, StringPiece(" is"));
    ++iter;
    ASSERT_EQ(*iter, StringPiece("the"));
    ++iter;
    ASSERT_EQ(*iter, StringPiece("end"));
    ++iter;
    ASSERT_EQ(tokenizer.end(), iter);
}

TEST(UtilTest, TokenizeEmptyString) {
    auto tokenizer = util::tokenize(StringPiece(""), '|');
    auto iter = tokenizer.begin();
    ASSERT_NE(tokenizer.end(), iter);
    ASSERT_EQ(StringPiece(), *iter);
    ++iter;
    ASSERT_EQ(tokenizer.end(), iter);
}

TEST(UtilTest, TokenizeAtEnd) {
    auto tokenizer = util::tokenize(StringPiece("one."), '.');
    auto iter = tokenizer.begin();
    ASSERT_EQ(*iter, StringPiece("one"));
    ++iter;
    ASSERT_NE(iter, tokenizer.end());
    ASSERT_EQ(*iter, StringPiece());
}

TEST(UtilTest, IsJavaClassName) {
    EXPECT_TRUE(util::isJavaClassName("android.test.Class"));
    EXPECT_TRUE(util::isJavaClassName("android.test.Class$Inner"));
    EXPECT_TRUE(util::isJavaClassName("android_test.test.Class"));
    EXPECT_TRUE(util::isJavaClassName("_android_.test._Class_"));
    EXPECT_FALSE(util::isJavaClassName("android.test.$Inner"));
    EXPECT_FALSE(util::isJavaClassName("android.test.Inner$"));
    EXPECT_FALSE(util::isJavaClassName(".test.Class"));
    EXPECT_FALSE(util::isJavaClassName("android"));
}

TEST(UtilTest, IsJavaPackageName) {
    EXPECT_TRUE(util::isJavaPackageName("android"));
    EXPECT_TRUE(util::isJavaPackageName("android.test"));
    EXPECT_TRUE(util::isJavaPackageName("android.test_thing"));
    EXPECT_FALSE(util::isJavaPackageName("_android"));
    EXPECT_FALSE(util::isJavaPackageName("android_"));
    EXPECT_FALSE(util::isJavaPackageName("android."));
    EXPECT_FALSE(util::isJavaPackageName(".android"));
    EXPECT_FALSE(util::isJavaPackageName("android._test"));
    EXPECT_FALSE(util::isJavaPackageName(".."));
}

TEST(UtilTest, FullyQualifiedClassName) {
    Maybe<std::string> res = util::getFullyQualifiedClassName("android", ".asdf");
    AAPT_ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), "android.asdf");

    res = util::getFullyQualifiedClassName("android", ".a.b");
    AAPT_ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), "android.a.b");

    res = util::getFullyQualifiedClassName("android", "a.b");
    AAPT_ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), "a.b");

    res = util::getFullyQualifiedClassName("", "a.b");
    AAPT_ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), "a.b");

    res = util::getFullyQualifiedClassName("android", "Class");
    AAPT_ASSERT_TRUE(res);
    EXPECT_EQ(res.value(), "android.Class");

    res = util::getFullyQualifiedClassName("", "");
    AAPT_ASSERT_FALSE(res);

    res = util::getFullyQualifiedClassName("android", "./Apple");
    AAPT_ASSERT_FALSE(res);
}

TEST(UtilTest, ExtractResourcePathComponents) {
    StringPiece prefix, entry, suffix;
    ASSERT_TRUE(util::extractResFilePathParts("res/xml-sw600dp/entry.xml", &prefix, &entry,
                                              &suffix));
    EXPECT_EQ(prefix, "res/xml-sw600dp/");
    EXPECT_EQ(entry, "entry");
    EXPECT_EQ(suffix, ".xml");

    ASSERT_TRUE(util::extractResFilePathParts("res/xml-sw600dp/entry.9.png", &prefix, &entry,
                                              &suffix));

    EXPECT_EQ(prefix, "res/xml-sw600dp/");
    EXPECT_EQ(entry, "entry");
    EXPECT_EQ(suffix, ".9.png");

    EXPECT_FALSE(util::extractResFilePathParts("AndroidManifest.xml", &prefix, &entry, &suffix));
    EXPECT_FALSE(util::extractResFilePathParts("res/.xml", &prefix, &entry, &suffix));

    ASSERT_TRUE(util::extractResFilePathParts("res//.", &prefix, &entry, &suffix));
    EXPECT_EQ(prefix, "res//");
    EXPECT_EQ(entry, "");
    EXPECT_EQ(suffix, ".");
}

TEST(UtilTest, VerifyJavaStringFormat) {
    ASSERT_TRUE(util::verifyJavaStringFormat("%09.34f"));
    ASSERT_TRUE(util::verifyJavaStringFormat("%9$.34f %8$"));
    ASSERT_TRUE(util::verifyJavaStringFormat("%% %%"));
    ASSERT_FALSE(util::verifyJavaStringFormat("%09$f %f"));
    ASSERT_FALSE(util::verifyJavaStringFormat("%09f %08s"));
}

} // namespace aapt
