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

#include "util/Util.h"

#include <string>

#include "test/Test.h"

using ::android::StringPiece;
using ::testing::Eq;
using ::testing::Ne;
using ::testing::SizeIs;

namespace aapt {

TEST(UtilTest, TrimOnlyWhitespace) {
  const StringPiece trimmed = util::TrimWhitespace("\n        ");
  EXPECT_TRUE(trimmed.empty());
  EXPECT_THAT(trimmed, SizeIs(0u));
}

TEST(UtilTest, StringEndsWith) {
  EXPECT_TRUE(util::EndsWith("hello.xml", ".xml"));
}

TEST(UtilTest, StringStartsWith) {
  EXPECT_TRUE(util::StartsWith("hello.xml", "he"));
}

TEST(UtilTest, TokenizeInput) {
  auto tokenizer = util::Tokenize(StringPiece("this| is|the|end"), '|');
  auto iter = tokenizer.begin();
  ASSERT_THAT(*iter, Eq("this"));
  ++iter;
  ASSERT_THAT(*iter, Eq(" is"));
  ++iter;
  ASSERT_THAT(*iter, Eq("the"));
  ++iter;
  ASSERT_THAT(*iter, Eq("end"));
  ++iter;
  ASSERT_THAT(iter, Eq(tokenizer.end()));
}

TEST(UtilTest, TokenizeEmptyString) {
  auto tokenizer = util::Tokenize(StringPiece(""), '|');
  auto iter = tokenizer.begin();
  ASSERT_THAT(iter, Ne(tokenizer.end()));
  ASSERT_THAT(*iter, Eq(StringPiece()));
  ++iter;
  ASSERT_THAT(iter, Eq(tokenizer.end()));
}

TEST(UtilTest, TokenizeAtEnd) {
  auto tokenizer = util::Tokenize(StringPiece("one."), '.');
  auto iter = tokenizer.begin();
  ASSERT_THAT(*iter, Eq("one"));
  ++iter;
  ASSERT_THAT(iter, Ne(tokenizer.end()));
  ASSERT_THAT(*iter, Eq(StringPiece()));
}

TEST(UtilTest, IsJavaClassName) {
  EXPECT_TRUE(util::IsJavaClassName("android.test.Class"));
  EXPECT_TRUE(util::IsJavaClassName("android.test.Class$Inner"));
  EXPECT_TRUE(util::IsJavaClassName("android_test.test.Class"));
  EXPECT_TRUE(util::IsJavaClassName("_android_.test._Class_"));
  EXPECT_TRUE(util::IsJavaClassName("android.test.$Inner"));
  EXPECT_TRUE(util::IsJavaClassName("android.test.Inner$"));
  EXPECT_TRUE(util::IsJavaClassName("com.foo.FøøBar"));

  EXPECT_FALSE(util::IsJavaClassName(".test.Class"));
  EXPECT_FALSE(util::IsJavaClassName("android"));
  EXPECT_FALSE(util::IsJavaClassName("FooBar"));
}

TEST(UtilTest, IsJavaPackageName) {
  EXPECT_TRUE(util::IsJavaPackageName("android"));
  EXPECT_TRUE(util::IsJavaPackageName("android.test"));
  EXPECT_TRUE(util::IsJavaPackageName("android.test_thing"));
  EXPECT_TRUE(util::IsJavaPackageName("_android"));
  EXPECT_TRUE(util::IsJavaPackageName("android_"));
  EXPECT_TRUE(util::IsJavaPackageName("android._test"));
  EXPECT_TRUE(util::IsJavaPackageName("cøm.foo"));

  EXPECT_FALSE(util::IsJavaPackageName("android."));
  EXPECT_FALSE(util::IsJavaPackageName(".android"));
  EXPECT_FALSE(util::IsJavaPackageName(".."));
}

TEST(UtilTest, IsAndroidPackageName) {
  EXPECT_TRUE(util::IsAndroidPackageName("android"));
  EXPECT_TRUE(util::IsAndroidPackageName("android.test"));
  EXPECT_TRUE(util::IsAndroidPackageName("com.foo"));
  EXPECT_TRUE(util::IsAndroidPackageName("com.foo.test_thing"));
  EXPECT_TRUE(util::IsAndroidPackageName("com.foo.testing_thing_"));
  EXPECT_TRUE(util::IsAndroidPackageName("com.foo.test_99_"));

  EXPECT_FALSE(util::IsAndroidPackageName("android._test"));
  EXPECT_FALSE(util::IsAndroidPackageName("com"));
  EXPECT_FALSE(util::IsAndroidPackageName("_android"));
  EXPECT_FALSE(util::IsAndroidPackageName("android."));
  EXPECT_FALSE(util::IsAndroidPackageName(".android"));
  EXPECT_FALSE(util::IsAndroidPackageName(".."));
  EXPECT_FALSE(util::IsAndroidPackageName("cøm.foo"));
}

TEST(UtilTest, FullyQualifiedClassName) {
  EXPECT_THAT(util::GetFullyQualifiedClassName("android", ".asdf"), Eq("android.asdf"));
  EXPECT_THAT(util::GetFullyQualifiedClassName("android", ".a.b"), Eq("android.a.b"));
  EXPECT_THAT(util::GetFullyQualifiedClassName("android", "a.b"), Eq("a.b"));
  EXPECT_THAT(util::GetFullyQualifiedClassName("", "a.b"), Eq("a.b"));
  EXPECT_THAT(util::GetFullyQualifiedClassName("android", "Class"), Eq("android.Class"));
  EXPECT_FALSE(util::GetFullyQualifiedClassName("", ""));
  EXPECT_FALSE(util::GetFullyQualifiedClassName("android", "./Apple"));
}

TEST(UtilTest, ExtractResourcePathComponents) {
  StringPiece prefix, entry, suffix;
  ASSERT_TRUE(util::ExtractResFilePathParts("res/xml-sw600dp/entry.xml", &prefix, &entry, &suffix));
  EXPECT_THAT(prefix, Eq("res/xml-sw600dp/"));
  EXPECT_THAT(entry, Eq("entry"));
  EXPECT_THAT(suffix, Eq(".xml"));

  ASSERT_TRUE(util::ExtractResFilePathParts("res/xml-sw600dp/entry.9.png", &prefix, &entry, &suffix));
  EXPECT_THAT(prefix, Eq("res/xml-sw600dp/"));
  EXPECT_THAT(entry, Eq("entry"));
  EXPECT_THAT(suffix, Eq(".9.png"));

  ASSERT_TRUE(util::ExtractResFilePathParts("res//.", &prefix, &entry, &suffix));
  EXPECT_THAT(prefix, Eq("res//"));
  EXPECT_THAT(entry, Eq(""));
  EXPECT_THAT(suffix, Eq("."));

  EXPECT_FALSE(util::ExtractResFilePathParts("AndroidManifest.xml", &prefix, &entry, &suffix));
  EXPECT_FALSE(util::ExtractResFilePathParts("res/.xml", &prefix, &entry, &suffix));
}

TEST(UtilTest, VerifyJavaStringFormat) {
  ASSERT_TRUE(util::VerifyJavaStringFormat("%09.34f"));
  ASSERT_TRUE(util::VerifyJavaStringFormat("%9$.34f %8$"));
  ASSERT_TRUE(util::VerifyJavaStringFormat("%% %%"));
  ASSERT_FALSE(util::VerifyJavaStringFormat("%09$f %f"));
  ASSERT_FALSE(util::VerifyJavaStringFormat("%09f %08s"));
}

}  // namespace aapt
