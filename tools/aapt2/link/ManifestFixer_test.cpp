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
#include "test/Builders.h"
#include "test/Context.h"

#include <gtest/gtest.h>

namespace aapt {

struct ManifestFixerTest : public ::testing::Test {
    std::unique_ptr<IAaptContext> mContext;

    void SetUp() override {
        mContext = test::ContextBuilder()
                .setCompilationPackage("android")
                .setPackageId(0x01)
                .setNameManglerPolicy(NameManglerPolicy{ "android" })
                .addSymbolSource(test::StaticSymbolSourceBuilder()
                        .addSymbol("android:attr/package", ResourceId(0x01010000),
                                   test::AttributeBuilder()
                                        .setTypeMask(android::ResTable_map::TYPE_STRING)
                                        .build())
                        .addSymbol("android:attr/minSdkVersion", ResourceId(0x01010001),
                                   test::AttributeBuilder()
                                        .setTypeMask(android::ResTable_map::TYPE_STRING |
                                                     android::ResTable_map::TYPE_INTEGER)
                                        .build())
                        .addSymbol("android:attr/targetSdkVersion", ResourceId(0x01010002),
                                   test::AttributeBuilder()
                                        .setTypeMask(android::ResTable_map::TYPE_STRING |
                                                     android::ResTable_map::TYPE_INTEGER)
                                        .build())
                        .addSymbol("android:string/str", ResourceId(0x01060000))
                        .build())
                .build();
    }

    std::unique_ptr<xml::XmlResource> verify(const StringPiece& str) {
        return verifyWithOptions(str, {});
    }

    std::unique_ptr<xml::XmlResource> verifyWithOptions(const StringPiece& str,
                                                        const ManifestFixerOptions& options) {
        std::unique_ptr<xml::XmlResource> doc = test::buildXmlDom(str);
        ManifestFixer fixer(options);
        if (fixer.consume(mContext.get(), doc.get())) {
            return doc;
        }
        return {};
    }
};

TEST_F(ManifestFixerTest, EnsureManifestIsRootTag) {
    EXPECT_EQ(nullptr, verify("<other-tag />"));
    EXPECT_EQ(nullptr, verify("<ns:manifest xmlns:ns=\"com\" />"));
    EXPECT_NE(nullptr, verify("<manifest package=\"android\"></manifest>"));
}

TEST_F(ManifestFixerTest, EnsureManifestHasPackage) {
    EXPECT_NE(nullptr, verify("<manifest package=\"android\" />"));
    EXPECT_NE(nullptr, verify("<manifest package=\"com.android\" />"));
    EXPECT_NE(nullptr, verify("<manifest package=\"com.android.google\" />"));
    EXPECT_EQ(nullptr, verify("<manifest package=\"com.android.google.Class$1\" />"));
    EXPECT_EQ(nullptr,
              verify("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                     "android:package=\"com.android\" />"));
    EXPECT_EQ(nullptr, verify("<manifest package=\"@string/str\" />"));
}

TEST_F(ManifestFixerTest, UseDefaultSdkVersionsIfNonePresent) {
    ManifestFixerOptions options = { std::string("8"), std::string("22") };

    std::unique_ptr<xml::XmlResource> doc = verifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <uses-sdk android:minSdkVersion="7" android:targetSdkVersion="21" />
      </manifest>)EOF", options);
    ASSERT_NE(nullptr, doc);

    xml::Element* el;
    xml::Attribute* attr;

    el = xml::findRootElement(doc.get());
    ASSERT_NE(nullptr, el);
    el = el->findChild({}, "uses-sdk");
    ASSERT_NE(nullptr, el);
    attr = el->findAttribute(xml::kSchemaAndroid, "minSdkVersion");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ("7", attr->value);
    attr = el->findAttribute(xml::kSchemaAndroid, "targetSdkVersion");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ("21", attr->value);

    doc = verifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <uses-sdk android:targetSdkVersion="21" />
      </manifest>)EOF", options);
    ASSERT_NE(nullptr, doc);

    el = xml::findRootElement(doc.get());
    ASSERT_NE(nullptr, el);
    el = el->findChild({}, "uses-sdk");
    ASSERT_NE(nullptr, el);
    attr = el->findAttribute(xml::kSchemaAndroid, "minSdkVersion");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ("8", attr->value);
    attr = el->findAttribute(xml::kSchemaAndroid, "targetSdkVersion");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ("21", attr->value);

    doc = verifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <uses-sdk />
      </manifest>)EOF", options);
    ASSERT_NE(nullptr, doc);

    el = xml::findRootElement(doc.get());
    ASSERT_NE(nullptr, el);
    el = el->findChild({}, "uses-sdk");
    ASSERT_NE(nullptr, el);
    attr = el->findAttribute(xml::kSchemaAndroid, "minSdkVersion");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ("8", attr->value);
    attr = el->findAttribute(xml::kSchemaAndroid, "targetSdkVersion");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ("22", attr->value);

    doc = verifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android" />)EOF", options);
    ASSERT_NE(nullptr, doc);

    el = xml::findRootElement(doc.get());
    ASSERT_NE(nullptr, el);
    el = el->findChild({}, "uses-sdk");
    ASSERT_NE(nullptr, el);
    attr = el->findAttribute(xml::kSchemaAndroid, "minSdkVersion");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ("8", attr->value);
    attr = el->findAttribute(xml::kSchemaAndroid, "targetSdkVersion");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ("22", attr->value);
}

TEST_F(ManifestFixerTest, RenameManifestPackageAndFullyQualifyClasses) {
    ManifestFixerOptions options;
    options.renameManifestPackage = std::string("com.android");

    std::unique_ptr<xml::XmlResource> doc = verifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <application android:name=".MainApplication" text="hello">
          <activity android:name=".activity.Start" />
          <receiver android:name="com.google.android.Receiver" />
        </application>
      </manifest>)EOF", options);
    ASSERT_NE(nullptr, doc);

    xml::Element* manifestEl = xml::findRootElement(doc.get());
    ASSERT_NE(nullptr, manifestEl);

    xml::Attribute* attr = nullptr;

    attr = manifestEl->findAttribute({},"package");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(std::string("com.android"), attr->value);

    xml::Element* applicationEl = manifestEl->findChild({}, "application");
    ASSERT_NE(nullptr, applicationEl);

    attr = applicationEl->findAttribute(xml::kSchemaAndroid, "name");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(std::string("android.MainApplication"), attr->value);

    attr = applicationEl->findAttribute({}, "text");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(std::string("hello"), attr->value);

    xml::Element* el;
    el = applicationEl->findChild({}, "activity");
    ASSERT_NE(nullptr, el);

    attr = el->findAttribute(xml::kSchemaAndroid, "name");
    ASSERT_NE(nullptr, el);
    EXPECT_EQ(std::string("android.activity.Start"), attr->value);

    el = applicationEl->findChild({}, "receiver");
    ASSERT_NE(nullptr, el);

    attr = el->findAttribute(xml::kSchemaAndroid, "name");
    ASSERT_NE(nullptr, el);
    EXPECT_EQ(std::string("com.google.android.Receiver"), attr->value);
}

TEST_F(ManifestFixerTest, RenameManifestInstrumentationPackageAndFullyQualifyTarget) {
    ManifestFixerOptions options;
    options.renameInstrumentationTargetPackage = std::string("com.android");

    std::unique_ptr<xml::XmlResource> doc = verifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android">
        <instrumentation android:targetPackage="android" />
      </manifest>)EOF", options);
    ASSERT_NE(nullptr, doc);

    xml::Element* manifestEl = xml::findRootElement(doc.get());
    ASSERT_NE(nullptr, manifestEl);

    xml::Element* instrumentationEl = manifestEl->findChild({}, "instrumentation");
    ASSERT_NE(nullptr, instrumentationEl);

    xml::Attribute* attr = instrumentationEl->findAttribute(xml::kSchemaAndroid, "targetPackage");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(std::string("com.android"), attr->value);
}

TEST_F(ManifestFixerTest, UseDefaultVersionNameAndCode) {
    ManifestFixerOptions options;
    options.versionNameDefault = std::string("Beta");
    options.versionCodeDefault = std::string("0x10000000");

    std::unique_ptr<xml::XmlResource> doc = verifyWithOptions(R"EOF(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="android" />)EOF", options);
    ASSERT_NE(nullptr, doc);

    xml::Element* manifestEl = xml::findRootElement(doc.get());
    ASSERT_NE(nullptr, manifestEl);

    xml::Attribute* attr = manifestEl->findAttribute(xml::kSchemaAndroid, "versionName");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(std::string("Beta"), attr->value);

    attr = manifestEl->findAttribute(xml::kSchemaAndroid, "versionCode");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(std::string("0x10000000"), attr->value);
}

TEST_F(ManifestFixerTest, EnsureManifestAttributesAreTyped) {
    EXPECT_EQ(nullptr, verify("<manifest package=\"android\" coreApp=\"hello\" />"));
    EXPECT_EQ(nullptr, verify("<manifest package=\"android\" coreApp=\"1dp\" />"));

    std::unique_ptr<xml::XmlResource> doc =
            verify("<manifest package=\"android\" coreApp=\"true\" />");
    ASSERT_NE(nullptr, doc);

    xml::Element* el = xml::findRootElement(doc.get());
    ASSERT_NE(nullptr, el);

    EXPECT_EQ("manifest", el->name);

    xml::Attribute* attr = el->findAttribute("", "coreApp");
    ASSERT_NE(nullptr, attr);

    EXPECT_NE(nullptr, attr->compiledValue);
    EXPECT_NE(nullptr, valueCast<BinaryPrimitive>(attr->compiledValue.get()));
}

} // namespace aapt
