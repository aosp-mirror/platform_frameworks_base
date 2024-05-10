/*
 * Copyright 2023 The Android Open Source Project
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

#include "link/FeatureFlagsFilter.h"

#include <string_view>

#include "test/Test.h"

using ::testing::IsNull;
using ::testing::NotNull;

namespace aapt {

// Returns null if there was an error from FeatureFlagsFilter.
std::unique_ptr<xml::XmlResource> VerifyWithOptions(std::string_view str,
                                                    const FeatureFlagValues& feature_flag_values,
                                                    const FeatureFlagsFilterOptions& options) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(str);
  FeatureFlagsFilter filter(feature_flag_values, options);
  if (filter.Consume(test::ContextBuilder().Build().get(), doc.get())) {
    return doc;
  }
  return {};
}

// Returns null if there was an error from FeatureFlagsFilter.
std::unique_ptr<xml::XmlResource> Verify(std::string_view str,
                                         const FeatureFlagValues& feature_flag_values) {
  return VerifyWithOptions(str, feature_flag_values, {});
}

TEST(FeatureFlagsFilterTest, NoFeatureFlagAttributes) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" />
    </manifest>)EOF",
                    {{"flag", false}});
  ASSERT_THAT(doc, NotNull());
  auto root = doc->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, NotNull());
}
TEST(FeatureFlagsFilterTest, RemoveElementWithDisabledFlag) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag="flag" />
    </manifest>)EOF",
                    {{"flag", false}});
  ASSERT_THAT(doc, NotNull());
  auto root = doc->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, IsNull());
}

TEST(FeatureFlagsFilterTest, RemoveElementWithNegatedEnabledFlag) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag="!flag" />
    </manifest>)EOF",
                    {{"flag", true}});
  ASSERT_THAT(doc, NotNull());
  auto root = doc->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, IsNull());
}

TEST(FeatureFlagsFilterTest, KeepElementWithEnabledFlag) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag="flag" />
    </manifest>)EOF",
                    {{"flag", true}});
  ASSERT_THAT(doc, NotNull());
  auto root = doc->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, NotNull());
}

TEST(FeatureFlagsFilterTest, SideBySideEnabledAndDisabled) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag="!flag"
                  android:protectionLevel="normal" />
      <permission android:name="FOO" android:featureFlag="flag"
                  android:protectionLevel="dangerous" />
    </manifest>)EOF",
                    {{"flag", true}});
  ASSERT_THAT(doc, NotNull());
  auto root = doc->root.get();
  ASSERT_THAT(root, NotNull());
  auto children = root->GetChildElements();
  ASSERT_EQ(children.size(), 1);
  auto attr = children[0]->FindAttribute(xml::kSchemaAndroid, "protectionLevel");
  ASSERT_THAT(attr, NotNull());
  ASSERT_EQ(attr->value, "dangerous");
}

TEST(FeatureFlagsFilterTest, RemoveDeeplyNestedElement) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <application>
        <provider />
        <activity>
          <layout android:featureFlag="!flag" />
        </activity>
      </application>
    </manifest>)EOF",
                    {{"flag", true}});
  ASSERT_THAT(doc, NotNull());
  auto root = doc->root.get();
  ASSERT_THAT(root, NotNull());
  auto application = root->FindChild({}, "application");
  ASSERT_THAT(application, NotNull());
  auto activity = application->FindChild({}, "activity");
  ASSERT_THAT(activity, NotNull());
  auto maybe_removed = activity->FindChild({}, "layout");
  ASSERT_THAT(maybe_removed, IsNull());
}

TEST(FeatureFlagsFilterTest, KeepDeeplyNestedElement) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <application>
        <provider />
        <activity>
          <layout android:featureFlag="flag" />
        </activity>
      </application>
    </manifest>)EOF",
                    {{"flag", true}});
  ASSERT_THAT(doc, NotNull());
  auto root = doc->root.get();
  ASSERT_THAT(root, NotNull());
  auto application = root->FindChild({}, "application");
  ASSERT_THAT(application, NotNull());
  auto activity = application->FindChild({}, "activity");
  ASSERT_THAT(activity, NotNull());
  auto maybe_removed = activity->FindChild({}, "layout");
  ASSERT_THAT(maybe_removed, NotNull());
}

TEST(FeatureFlagsFilterTest, FailOnEmptyFeatureFlagAttribute) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag=" " />
    </manifest>)EOF",
                    {{"flag", false}});
  ASSERT_THAT(doc, IsNull());
}

TEST(FeatureFlagsFilterTest, FailOnFlagWithNoGivenValue) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag="flag" />
    </manifest>)EOF",
                    {{"flag", std::nullopt}});
  ASSERT_THAT(doc, IsNull());
}

TEST(FeatureFlagsFilterTest, FailOnUnrecognizedFlag) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag="unrecognized" />
    </manifest>)EOF",
                    {{"flag", true}});
  ASSERT_THAT(doc, IsNull());
}

TEST(FeatureFlagsFilterTest, FailOnMultipleValidationErrors) {
  auto doc = Verify(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag="bar" />
      <permission android:name="FOO" android:featureFlag="unrecognized" />
    </manifest>)EOF",
                    {{"flag", std::nullopt}});
  ASSERT_THAT(doc, IsNull());
}

TEST(FeatureFlagsFilterTest, OptionRemoveDisabledElementsIsFalse) {
  auto doc = VerifyWithOptions(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag="flag" />
    </manifest>)EOF",
                               {{"flag", false}}, {.remove_disabled_elements = false});
  ASSERT_THAT(doc, NotNull());
  auto root = doc->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, NotNull());
}

TEST(FeatureFlagsFilterTest, OptionFlagsMustHaveValueIsFalse) {
  auto doc = VerifyWithOptions(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag="flag" />
    </manifest>)EOF",
                               {{"flag", std::nullopt}}, {.flags_must_have_value = false});
  ASSERT_THAT(doc, NotNull());
  auto root = doc->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, NotNull());
}

TEST(FeatureFlagsFilterTest, OptionFailOnUnrecognizedFlagsIsFalse) {
  auto doc = VerifyWithOptions(R"EOF(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
      <permission android:name="FOO" android:featureFlag="unrecognized" />
    </manifest>)EOF",
                               {{"flag", true}}, {.fail_on_unrecognized_flags = false});
  ASSERT_THAT(doc, NotNull());
  auto root = doc->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, NotNull());
}

}  // namespace aapt
