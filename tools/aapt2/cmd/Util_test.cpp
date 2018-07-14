/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "Util.h"

#include "AppInfo.h"
#include "split/TableSplitter.h"
#include "test/Builders.h"
#include "test/Test.h"

namespace aapt {

TEST(UtilTest, SplitNamesAreSanitized) {
    AppInfo app_info{"com.pkg"};
    SplitConstraints split_constraints{
        {test::ParseConfigOrDie("en-rUS-land"), test::ParseConfigOrDie("b+sr+Latn")}};

    const auto doc = GenerateSplitManifest(app_info, split_constraints);
    const auto &root = doc->root;
    EXPECT_EQ(root->name, "manifest");
    // split names cannot contain hyphens or plus signs.
    EXPECT_EQ(root->FindAttribute("", "split")->value, "config.b_sr_Latn_en_rUS_land");
    // but we should use resource qualifiers verbatim in 'targetConfig'.
    EXPECT_EQ(root->FindAttribute("", "targetConfig")->value, "b+sr+Latn,en-rUS-land");
}

TEST (UtilTest, LongVersionCodeDefined) {
  auto doc = test::BuildXmlDom(R"(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="com.android.aapt.test" android:versionCode="0x1" android:versionCodeMajor="0x1">
      </manifest>)");
  SetLongVersionCode(doc->root.get(), 42);

  auto version_code = doc->root->FindAttribute(xml::kSchemaAndroid, "versionCode");
  ASSERT_NE(version_code, nullptr);
  EXPECT_EQ(version_code->value, "0x0000002a");

  ASSERT_NE(version_code->compiled_value, nullptr);
  auto compiled_version_code = ValueCast<BinaryPrimitive>(version_code->compiled_value.get());
  ASSERT_NE(compiled_version_code, nullptr);
  EXPECT_EQ(compiled_version_code->value.data, 42U);

  auto version_code_major = doc->root->FindAttribute(xml::kSchemaAndroid, "versionCodeMajor");
  EXPECT_EQ(version_code_major, nullptr);
}

TEST (UtilTest, LongVersionCodeUndefined) {
  auto doc = test::BuildXmlDom(R"(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="com.android.aapt.test">
        </manifest>)");
  SetLongVersionCode(doc->root.get(), 420000000000);

  auto version_code = doc->root->FindAttribute(xml::kSchemaAndroid, "versionCode");
  ASSERT_NE(version_code, nullptr);
  EXPECT_EQ(version_code->value, "0xc9f36800");

  ASSERT_NE(version_code->compiled_value, nullptr);
  auto compiled_version_code = ValueCast<BinaryPrimitive>(version_code->compiled_value.get());
  ASSERT_NE(compiled_version_code, nullptr);
  EXPECT_EQ(compiled_version_code->value.data, 0xc9f36800);

  auto version_code_major = doc->root->FindAttribute(xml::kSchemaAndroid, "versionCodeMajor");
  ASSERT_NE(version_code_major, nullptr);
  EXPECT_EQ(version_code_major->value, "0x00000061");

  ASSERT_NE(version_code_major->compiled_value, nullptr);
  auto compiled_version_code_major = ValueCast<BinaryPrimitive>(
      version_code_major->compiled_value.get());
  ASSERT_NE(compiled_version_code_major, nullptr);
  EXPECT_EQ(compiled_version_code_major->value.data, 0x61);
}

}  // namespace aapt
