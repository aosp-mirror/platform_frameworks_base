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

#include "link/ManifestFixer.h"

#include "test/Test.h"

using ::android::StringPiece;
using ::testing::NotNull;

namespace aapt {

struct ManifestFixerTest : public ::testing::Test {
  std::unique_ptr<IAaptContext> mContext;

  void SetUp() override {
    mContext =
        test::ContextBuilder()
            .SetCompilationPackage("android")
            .SetPackageId(0x01)
            .SetNameManglerPolicy(NameManglerPolicy{"android"})
            .AddSymbolSource(
                test::StaticSymbolSourceBuilder()
                    .AddSymbol(
                        "android:attr/package", ResourceId(0x01010000),
                        test::AttributeBuilder()
                            .SetTypeMask(android::ResTable_map::TYPE_STRING)
                            .Build())
                    .AddSymbol(
                        "android:attr/minSdkVersion", ResourceId(0x01010001),
                        test::AttributeBuilder()
                            .SetTypeMask(android::ResTable_map::TYPE_STRING |
                                         android::ResTable_map::TYPE_INTEGER)
                            .Build())
                    .AddSymbol(
                        "android:attr/targetSdkVersion", ResourceId(0x01010002),
                        test::AttributeBuilder()
                            .SetTypeMask(android::ResTable_map::TYPE_STRING |
                                         android::ResTable_map::TYPE_INTEGER)
                            .Build())
                    .AddSymbol("android:string/str", ResourceId(0x01060000))
                    .Build())
            .Build();
  }

  std::unique_ptr<xml::XmlResource> Verify(const StringPiece& str) {
    return VerifyWithOptions(str, {});
  }

  std::unique_ptr<xml::XmlResource> VerifyWithOptions(
      const StringPiece& str, const ManifestFixerOptions& options) {
    std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(str);
    ManifestFixer fixer(options);
    if (fixer.Consume(mContext.get(), doc.get())) {
      return doc;
    }
    return {};
  }
};

TEST_F(ManifestFixerTest, EnsureManifestIsRootTag) {
  EXPECT_EQ(nullptr, Verify("<other-tag />"));
  EXPECT_EQ(nullptr, Verify("<ns:manifest xmlns:ns=\"com\" />"));
  EXPECT_NE(nullptr, Verify("<manifest package=\"android\"></manifest>"));
}

TEST_F(ManifestFixerTest, EnsureManifestHasPackage) {
  EXPECT_NE(nullptr, Verify("<manifest package=\"android\" />"));
  EXPECT_NE(nullptr, Verify("<manifest package=\"com.android\" />"));
  EXPECT_NE(nullptr, Verify("<manifest package=\"com.android.google\" />"));
  EXPECT_EQ(nullptr,
            Verify("<manifest package=\"com.android.google.Class$1\" />"));
  EXPECT_EQ(nullptr, Verify("<manifest "
                            "xmlns:android=\"http://schemas.android.com/apk/"
                            "res/android\" "
                            "android:package=\"com.android\" />"));
  EXPECT_EQ(nullptr, Verify("<manifest package=\"@string/str\" />"));
}

TEST_F(ManifestFixerTest, AllowMetaData) {
  auto doc = Verify(R"EOF(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="android">
          <meta-data />
          <application>
            <meta-data />
            <activity android:name=".Hi"><meta-data /></activity>
            <activity-alias android:name=".Ho"><meta-data /></activity-alias>
            <receiver android:name=".OffTo"><meta-data /></receiver>
            <provider android:name=".Work"><meta-data /></provider>
            <service android:name=".We"><meta-data /></service>
          </application>
          <instrumentation android:name=".Go"><meta-data /></instrumentation>
        </manifest>)EOF");
  ASSERT_NE(nullptr, doc);
}

TEST_F(ManifestFixerTest, UseDefaultSdkVersionsIfNonePresent) {
  ManifestFixerOptions options = {std::string("8"), std::string("22")};

  std::unique_ptr<xml::XmlResource> doc = VerifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <uses-sdk android:minSdkVersion="7" android:targetSdkVersion="21" />
      </manifest>)EOF",
                                                            options);
  ASSERT_NE(nullptr, doc);

  xml::Element* el;
  xml::Attribute* attr;

  el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, el);
  el = el->FindChild({}, "uses-sdk");
  ASSERT_NE(nullptr, el);
  attr = el->FindAttribute(xml::kSchemaAndroid, "minSdkVersion");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("7", attr->value);
  attr = el->FindAttribute(xml::kSchemaAndroid, "targetSdkVersion");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("21", attr->value);

  doc = VerifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <uses-sdk android:targetSdkVersion="21" />
      </manifest>)EOF",
                          options);
  ASSERT_NE(nullptr, doc);

  el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, el);
  el = el->FindChild({}, "uses-sdk");
  ASSERT_NE(nullptr, el);
  attr = el->FindAttribute(xml::kSchemaAndroid, "minSdkVersion");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("8", attr->value);
  attr = el->FindAttribute(xml::kSchemaAndroid, "targetSdkVersion");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("21", attr->value);

  doc = VerifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <uses-sdk />
      </manifest>)EOF",
                          options);
  ASSERT_NE(nullptr, doc);

  el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, el);
  el = el->FindChild({}, "uses-sdk");
  ASSERT_NE(nullptr, el);
  attr = el->FindAttribute(xml::kSchemaAndroid, "minSdkVersion");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("8", attr->value);
  attr = el->FindAttribute(xml::kSchemaAndroid, "targetSdkVersion");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("22", attr->value);

  doc = VerifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android" />)EOF",
                          options);
  ASSERT_NE(nullptr, doc);

  el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, el);
  el = el->FindChild({}, "uses-sdk");
  ASSERT_NE(nullptr, el);
  attr = el->FindAttribute(xml::kSchemaAndroid, "minSdkVersion");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("8", attr->value);
  attr = el->FindAttribute(xml::kSchemaAndroid, "targetSdkVersion");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("22", attr->value);
}

TEST_F(ManifestFixerTest, UsesSdkMustComeBeforeApplication) {
  ManifestFixerOptions options = {std::string("8"), std::string("22")};
  std::unique_ptr<xml::XmlResource> doc = VerifyWithOptions(R"EOF(
          <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="android">
            <application android:name=".MainApplication" />
          </manifest>)EOF",
                                                            options);
  ASSERT_NE(nullptr, doc);

  xml::Element* manifest_el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, manifest_el);
  ASSERT_EQ("manifest", manifest_el->name);

  xml::Element* application_el = manifest_el->FindChild("", "application");
  ASSERT_NE(nullptr, application_el);

  xml::Element* uses_sdk_el = manifest_el->FindChild("", "uses-sdk");
  ASSERT_NE(nullptr, uses_sdk_el);

  // Check that the uses_sdk_el comes before application_el in the children
  // vector.
  // Since there are no namespaces here, these children are direct descendants
  // of manifest.
  auto uses_sdk_iter =
      std::find_if(manifest_el->children.begin(), manifest_el->children.end(),
                   [&](const std::unique_ptr<xml::Node>& child) {
                     return child.get() == uses_sdk_el;
                   });

  auto application_iter =
      std::find_if(manifest_el->children.begin(), manifest_el->children.end(),
                   [&](const std::unique_ptr<xml::Node>& child) {
                     return child.get() == application_el;
                   });

  ASSERT_NE(manifest_el->children.end(), uses_sdk_iter);
  ASSERT_NE(manifest_el->children.end(), application_iter);

  // The distance should be positive, meaning uses_sdk_iter comes before
  // application_iter.
  EXPECT_GT(std::distance(uses_sdk_iter, application_iter), 0);
}

TEST_F(ManifestFixerTest, RenameManifestPackageAndFullyQualifyClasses) {
  ManifestFixerOptions options;
  options.rename_manifest_package = std::string("com.android");

  std::unique_ptr<xml::XmlResource> doc = VerifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <application android:name=".MainApplication" text="hello">
          <activity android:name=".activity.Start" />
          <receiver android:name="com.google.android.Receiver" />
        </application>
      </manifest>)EOF",
                                                            options);
  ASSERT_NE(nullptr, doc);

  xml::Element* manifestEl = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, manifestEl);

  xml::Attribute* attr = nullptr;

  attr = manifestEl->FindAttribute({}, "package");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(std::string("com.android"), attr->value);

  xml::Element* applicationEl = manifestEl->FindChild({}, "application");
  ASSERT_NE(nullptr, applicationEl);

  attr = applicationEl->FindAttribute(xml::kSchemaAndroid, "name");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(std::string("android.MainApplication"), attr->value);

  attr = applicationEl->FindAttribute({}, "text");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(std::string("hello"), attr->value);

  xml::Element* el;
  el = applicationEl->FindChild({}, "activity");
  ASSERT_NE(nullptr, el);

  attr = el->FindAttribute(xml::kSchemaAndroid, "name");
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(std::string("android.activity.Start"), attr->value);

  el = applicationEl->FindChild({}, "receiver");
  ASSERT_NE(nullptr, el);

  attr = el->FindAttribute(xml::kSchemaAndroid, "name");
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(std::string("com.google.android.Receiver"), attr->value);
}

TEST_F(ManifestFixerTest,
       RenameManifestInstrumentationPackageAndFullyQualifyTarget) {
  ManifestFixerOptions options;
  options.rename_instrumentation_target_package = std::string("com.android");

  std::unique_ptr<xml::XmlResource> doc = VerifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <instrumentation android:name=".TestRunner" android:targetPackage="android" />
      </manifest>)EOF",
                                                            options);
  ASSERT_NE(nullptr, doc);

  xml::Element* manifest_el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, manifest_el);

  xml::Element* instrumentation_el =
      manifest_el->FindChild({}, "instrumentation");
  ASSERT_NE(nullptr, instrumentation_el);

  xml::Attribute* attr =
      instrumentation_el->FindAttribute(xml::kSchemaAndroid, "targetPackage");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(std::string("com.android"), attr->value);
}

TEST_F(ManifestFixerTest, UseDefaultVersionNameAndCode) {
  ManifestFixerOptions options;
  options.version_name_default = std::string("Beta");
  options.version_code_default = std::string("0x10000000");

  std::unique_ptr<xml::XmlResource> doc = VerifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android" />)EOF",
                                                            options);
  ASSERT_NE(nullptr, doc);

  xml::Element* manifest_el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, manifest_el);

  xml::Attribute* attr =
      manifest_el->FindAttribute(xml::kSchemaAndroid, "versionName");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(std::string("Beta"), attr->value);

  attr = manifest_el->FindAttribute(xml::kSchemaAndroid, "versionCode");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(std::string("0x10000000"), attr->value);
}

TEST_F(ManifestFixerTest, EnsureManifestAttributesAreTyped) {
  EXPECT_EQ(nullptr,
            Verify("<manifest package=\"android\" coreApp=\"hello\" />"));
  EXPECT_EQ(nullptr,
            Verify("<manifest package=\"android\" coreApp=\"1dp\" />"));

  std::unique_ptr<xml::XmlResource> doc =
      Verify("<manifest package=\"android\" coreApp=\"true\" />");
  ASSERT_NE(nullptr, doc);

  xml::Element* el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, el);

  EXPECT_EQ("manifest", el->name);

  xml::Attribute* attr = el->FindAttribute("", "coreApp");
  ASSERT_NE(nullptr, attr);

  EXPECT_NE(nullptr, attr->compiled_value);
  EXPECT_NE(nullptr, ValueCast<BinaryPrimitive>(attr->compiled_value.get()));
}

TEST_F(ManifestFixerTest, UsesFeatureMustHaveNameOrGlEsVersion) {
  std::string input = R"EOF(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="android">
          <uses-feature android:name="feature" />
          <uses-feature android:glEsVersion="1" />
          <feature-group />
          <feature-group>
            <uses-feature android:name="feature_in_group" />
            <uses-feature android:glEsVersion="2" />
          </feature-group>
        </manifest>)EOF";
  EXPECT_NE(nullptr, Verify(input));

  input = R"EOF(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="android">
          <uses-feature android:name="feature" android:glEsVersion="1" />
        </manifest>)EOF";
  EXPECT_EQ(nullptr, Verify(input));

  input = R"EOF(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="android">
          <uses-feature />
        </manifest>)EOF";
  EXPECT_EQ(nullptr, Verify(input));

  input = R"EOF(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="android">
          <feature-group>
            <uses-feature android:name="feature" android:glEsVersion="1" />
          </feature-group>
        </manifest>)EOF";
  EXPECT_EQ(nullptr, Verify(input));

  input = R"EOF(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="android">
          <feature-group>
            <uses-feature />
          </feature-group>
        </manifest>)EOF";
  EXPECT_EQ(nullptr, Verify(input));
}

TEST_F(ManifestFixerTest, IgnoreNamespacedElements) {
  std::string input = R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <special:tag whoo="true" xmlns:special="http://google.com" />
      </manifest>)EOF";
  EXPECT_NE(nullptr, Verify(input));
}

TEST_F(ManifestFixerTest, DoNotIgnoreNonNamespacedElements) {
  std::string input = R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <tag whoo="true" />
      </manifest>)EOF";
  EXPECT_EQ(nullptr, Verify(input));
}

TEST_F(ManifestFixerTest, SupportKeySets) {
  std::string input = R"(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="android">
        <key-sets>
          <key-set android:name="old-set">
            <public-key android:name="old-key" android:value="some+old+key" />
          </key-set>
          <key-set android:name="new-set">
            <public-key android:name="new-key" android:value="some+new+key" />
          </key-set>
          <upgrade-key-set android:name="old-set" />
          <upgrade-key-set android:name="new-set" />
        </key-sets>
      </manifest>)";
  EXPECT_THAT(Verify(input), NotNull());
}

}  // namespace aapt
