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

#include "java/ManifestClassGenerator.h"

#include "test/Test.h"

using ::testing::HasSubstr;
using ::testing::Not;

namespace aapt {

static ::testing::AssertionResult GetManifestClassText(IAaptContext* context, xml::XmlResource* res,
                                                       std::string* out_str);

TEST(ManifestClassGeneratorTest, NameIsProperlyGeneratedFromSymbol) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> manifest = test::BuildXmlDom(R"(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <permission android:name="android.permission.ACCESS_INTERNET" />
        <permission android:name="android.DO_DANGEROUS_THINGS" />
        <permission android:name="com.test.sample.permission.HUH" />
        <permission-group android:name="foo.bar.PERMISSION" />
      </manifest>)");

  std::string actual;
  ASSERT_TRUE(GetManifestClassText(context.get(), manifest.get(), &actual));

  ASSERT_THAT(actual, HasSubstr("public static final class permission {"));
  ASSERT_THAT(actual, HasSubstr("public static final class permission_group {"));

  const size_t permission_start_pos = actual.find("public static final class permission {");
  const size_t permission_group_start_pos =
      actual.find("public static final class permission_group {");

  //
  // Make sure these permissions are in the permission class.
  //
  const std::string permission_class =
      actual.substr(permission_start_pos, permission_group_start_pos - permission_start_pos);

  EXPECT_THAT(
      permission_class,
      HasSubstr(
          "public static final String ACCESS_INTERNET=\"android.permission.ACCESS_INTERNET\";"));
  EXPECT_THAT(
      permission_class,
      HasSubstr("public static final String DO_DANGEROUS_THINGS=\"android.DO_DANGEROUS_THINGS\";"));
  EXPECT_THAT(permission_class,
              HasSubstr("public static final String HUH=\"com.test.sample.permission.HUH\";"));

  //
  // Make sure these permissions are in the permission_group class
  //
  const std::string permission_group_class = actual.substr(permission_group_start_pos);

  EXPECT_THAT(permission_group_class,
              HasSubstr("public static final String PERMISSION=\"foo.bar.PERMISSION\";"));
}

TEST(ManifestClassGeneratorTest, CommentsAndAnnotationsArePresent) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> manifest = test::BuildXmlDom(R"(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <!-- Required to access the internet.
             Added in API 1. -->
        <permission android:name="android.permission.ACCESS_INTERNET" />
        <!-- @deprecated This permission is for playing outside. -->
        <permission android:name="android.permission.PLAY_OUTSIDE" />
        <!-- This is a private permission for system only!
             @hide
             @SystemApi -->
        <permission android:name="android.permission.SECRET" />
      </manifest>)");

  std::string actual;
  ASSERT_TRUE(GetManifestClassText(context.get(), manifest.get(), &actual));

  const char* expected_access_internet = R"(    /**
     * Required to access the internet.
     * Added in API 1.
     */
    public static final String ACCESS_INTERNET="android.permission.ACCESS_INTERNET";)";
  EXPECT_THAT(actual, HasSubstr(expected_access_internet));

  const char* expected_play_outside = R"(    /**
     * @deprecated This permission is for playing outside.
     */
    @Deprecated
    public static final String PLAY_OUTSIDE="android.permission.PLAY_OUTSIDE";)";
  EXPECT_THAT(actual, HasSubstr(expected_play_outside));

  const char* expected_secret = R"(    /**
     * This is a private permission for system only!
     * @hide
     */
    @android.annotation.SystemApi
    public static final String SECRET="android.permission.SECRET";)";
  EXPECT_THAT(actual, HasSubstr(expected_secret));
}

static ::testing::AssertionResult GetManifestClassText(IAaptContext* context, xml::XmlResource* res,
                                                       std::string* out_str) {
  std::unique_ptr<ClassDefinition> manifest_class =
      GenerateManifestClass(context->GetDiagnostics(), res);
  if (!manifest_class) {
    return ::testing::AssertionFailure() << "manifest_class == nullptr";
  }

  std::stringstream out;
  if (!manifest_class->WriteJavaFile(manifest_class.get(), "android", true, &out)) {
    return ::testing::AssertionFailure() << "failed to write java file";
  }

  *out_str = out.str();
  return ::testing::AssertionSuccess();
}

}  // namespace aapt
