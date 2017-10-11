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

namespace aapt {

static ::testing::AssertionResult GetManifestClassText(IAaptContext* context,
                                                       xml::XmlResource* res,
                                                       std::string* out_str) {
  std::unique_ptr<ClassDefinition> manifest_class =
      GenerateManifestClass(context->GetDiagnostics(), res);
  if (!manifest_class) {
    return ::testing::AssertionFailure() << "manifest_class == nullptr";
  }

  std::stringstream out;
  if (!manifest_class->WriteJavaFile(manifest_class.get(), "android", true,
                                     &out)) {
    return ::testing::AssertionFailure() << "failed to write java file";
  }

  *out_str = out.str();
  return ::testing::AssertionSuccess();
}

TEST(ManifestClassGeneratorTest, NameIsProperlyGeneratedFromSymbol) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> manifest = test::BuildXmlDom(R"EOF(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <permission android:name="android.permission.ACCESS_INTERNET" />
          <permission android:name="android.DO_DANGEROUS_THINGS" />
          <permission android:name="com.test.sample.permission.HUH" />
          <permission-group android:name="foo.bar.PERMISSION" />
        </manifest>)EOF");

  std::string actual;
  ASSERT_TRUE(GetManifestClassText(context.get(), manifest.get(), &actual));

  const size_t permission_class_pos =
      actual.find("public static final class permission {");
  const size_t permission_croup_class_pos =
      actual.find("public static final class permission_group {");
  ASSERT_NE(std::string::npos, permission_class_pos);
  ASSERT_NE(std::string::npos, permission_croup_class_pos);

  //
  // Make sure these permissions are in the permission class.
  //

  size_t pos = actual.find(
      "public static final String ACCESS_INTERNET="
      "\"android.permission.ACCESS_INTERNET\";");
  EXPECT_GT(pos, permission_class_pos);
  EXPECT_LT(pos, permission_croup_class_pos);

  pos = actual.find(
      "public static final String DO_DANGEROUS_THINGS="
      "\"android.DO_DANGEROUS_THINGS\";");
  EXPECT_GT(pos, permission_class_pos);
  EXPECT_LT(pos, permission_croup_class_pos);

  pos = actual.find(
      "public static final String HUH=\"com.test.sample.permission.HUH\";");
  EXPECT_GT(pos, permission_class_pos);
  EXPECT_LT(pos, permission_croup_class_pos);

  //
  // Make sure these permissions are in the permission_group class
  //

  pos = actual.find(
      "public static final String PERMISSION="
      "\"foo.bar.PERMISSION\";");
  EXPECT_GT(pos, permission_croup_class_pos);
  EXPECT_LT(pos, std::string::npos);
}

TEST(ManifestClassGeneratorTest, CommentsAndAnnotationsArePresent) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> manifest = test::BuildXmlDom(R"EOF(
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
        </manifest>)EOF");

  std::string actual;
  ASSERT_TRUE(GetManifestClassText(context.get(), manifest.get(), &actual));

  const char* expected_access_internet =
      R"EOF(    /**
     * Required to access the internet.
     * Added in API 1.
     */
    public static final String ACCESS_INTERNET="android.permission.ACCESS_INTERNET";)EOF";

  EXPECT_NE(std::string::npos, actual.find(expected_access_internet));

  const char* expected_play_outside =
      R"EOF(    /**
     * @deprecated This permission is for playing outside.
     */
    @Deprecated
    public static final String PLAY_OUTSIDE="android.permission.PLAY_OUTSIDE";)EOF";

  EXPECT_NE(std::string::npos, actual.find(expected_play_outside));

  const char* expected_secret =
      R"EOF(    /**
     * This is a private permission for system only!
     * @hide
     */
    @android.annotation.SystemApi
    public static final String SECRET="android.permission.SECRET";)EOF";

  EXPECT_NE(std::string::npos, actual.find(expected_secret));
}

}  // namespace aapt
