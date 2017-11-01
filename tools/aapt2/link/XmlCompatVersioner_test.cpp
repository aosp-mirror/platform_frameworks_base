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

#include "link/XmlCompatVersioner.h"

#include "Linkers.h"
#include "test/Test.h"

namespace aapt {

constexpr auto TYPE_DIMENSION = android::ResTable_map::TYPE_DIMENSION;
constexpr auto TYPE_STRING = android::ResTable_map::TYPE_STRING;

struct R {
  struct attr {
    enum : uint32_t {
      paddingLeft = 0x010100d6u,         // (API 1)
      paddingRight = 0x010100d8u,        // (API 1)
      progressBarPadding = 0x01010319u,  // (API 11)
      paddingStart = 0x010103b3u,        // (API 17)
      paddingHorizontal = 0x0101053du,   // (API 26)
    };
  };
};

class XmlCompatVersionerTest : public ::testing::Test {
 public:
  void SetUp() override {
    context_ =
        test::ContextBuilder()
            .SetCompilationPackage("com.app")
            .SetPackageId(0x7f)
            .SetPackageType(PackageType::kApp)
            .SetMinSdkVersion(SDK_GINGERBREAD)
            .AddSymbolSource(
                test::StaticSymbolSourceBuilder()
                    .AddPublicSymbol("android:attr/paddingLeft", R::attr::paddingLeft,
                                     util::make_unique<Attribute>(false, TYPE_DIMENSION))
                    .AddPublicSymbol("android:attr/paddingRight", R::attr::paddingRight,
                                     util::make_unique<Attribute>(false, TYPE_DIMENSION))
                    .AddPublicSymbol("android:attr/progressBarPadding", R::attr::progressBarPadding,
                                     util::make_unique<Attribute>(false, TYPE_DIMENSION))
                    .AddPublicSymbol("android:attr/paddingStart", R::attr::paddingStart,
                                     util::make_unique<Attribute>(false, TYPE_DIMENSION))
                    .AddPublicSymbol("android:attr/paddingHorizontal", R::attr::paddingHorizontal,
                                     util::make_unique<Attribute>(false, TYPE_DIMENSION))
                    .AddSymbol("com.app:attr/foo", ResourceId(0x7f010000),
                               util::make_unique<Attribute>(false, TYPE_STRING))
                    .Build())
            .Build();
  }

 protected:
  std::unique_ptr<IAaptContext> context_;
};

TEST_F(XmlCompatVersionerTest, NoRulesOnlyStripsAndCopies) {
  auto doc = test::BuildXmlDomForPackageName(context_.get(), R"EOF(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:app="http://schemas.android.com/apk/res-auto"
          android:paddingHorizontal="24dp"
          app:foo="16dp"
          foo="bar"/>)EOF");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  XmlCompatVersioner::Rules rules;
  const util::Range<ApiVersion> api_range{SDK_GINGERBREAD, SDK_O + 1};

  XmlCompatVersioner versioner(&rules);
  std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs =
      versioner.Process(context_.get(), doc.get(), api_range);
  ASSERT_EQ(2u, versioned_docs.size());

  xml::Element* el;

  // Source XML file's sdkVersion == 0, so the first one must also have the same sdkVersion.
  EXPECT_EQ(static_cast<uint16_t>(0), versioned_docs[0]->file.config.sdkVersion);
  el = xml::FindRootElement(versioned_docs[0].get());
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(2u, el->attributes.size());
  EXPECT_EQ(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"));
  EXPECT_NE(nullptr, el->FindAttribute(xml::kSchemaAuto, "foo"));
  EXPECT_NE(nullptr, el->FindAttribute({}, "foo"));

  EXPECT_EQ(static_cast<uint16_t>(SDK_LOLLIPOP_MR1), versioned_docs[1]->file.config.sdkVersion);
  el = xml::FindRootElement(versioned_docs[1].get());
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(3u, el->attributes.size());
  EXPECT_NE(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"));
  EXPECT_NE(nullptr, el->FindAttribute(xml::kSchemaAuto, "foo"));
  EXPECT_NE(nullptr, el->FindAttribute({}, "foo"));
}

TEST_F(XmlCompatVersionerTest, SingleRule) {
  auto doc = test::BuildXmlDomForPackageName(context_.get(), R"EOF(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:app="http://schemas.android.com/apk/res-auto"
          android:paddingHorizontal="24dp"
          app:foo="16dp"
          foo="bar"/>)EOF");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  XmlCompatVersioner::Rules rules;
  rules[R::attr::paddingHorizontal] =
      util::make_unique<DegradeToManyRule>(std::vector<ReplacementAttr>(
          {ReplacementAttr{"paddingLeft", R::attr::paddingLeft, Attribute(false, TYPE_DIMENSION)},
           ReplacementAttr{"paddingRight", R::attr::paddingRight,
                           Attribute(false, TYPE_DIMENSION)}}));

  const util::Range<ApiVersion> api_range{SDK_GINGERBREAD, SDK_O + 1};

  XmlCompatVersioner versioner(&rules);
  std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs =
      versioner.Process(context_.get(), doc.get(), api_range);
  ASSERT_EQ(2u, versioned_docs.size());

  xml::Element* el;

  EXPECT_EQ(static_cast<uint16_t>(0), versioned_docs[0]->file.config.sdkVersion);
  el = xml::FindRootElement(versioned_docs[0].get());
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(4u, el->attributes.size());
  EXPECT_EQ(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"));
  EXPECT_NE(nullptr, el->FindAttribute(xml::kSchemaAuto, "foo"));
  EXPECT_NE(nullptr, el->FindAttribute({}, "foo"));

  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "paddingLeft");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingRight");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(attr->compiled_attribute);

  EXPECT_EQ(static_cast<uint16_t>(SDK_LOLLIPOP_MR1), versioned_docs[1]->file.config.sdkVersion);
  el = xml::FindRootElement(versioned_docs[1].get());
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(5u, el->attributes.size());
  EXPECT_NE(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"));
  EXPECT_NE(nullptr, el->FindAttribute(xml::kSchemaAuto, "foo"));
  EXPECT_NE(nullptr, el->FindAttribute({}, "foo"));

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingLeft");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingRight");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(attr->compiled_attribute);
}

TEST_F(XmlCompatVersionerTest, ChainedRule) {
  auto doc = test::BuildXmlDomForPackageName(context_.get(), R"EOF(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:paddingHorizontal="24dp" />)EOF");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  XmlCompatVersioner::Rules rules;
  rules[R::attr::progressBarPadding] =
      util::make_unique<DegradeToManyRule>(std::vector<ReplacementAttr>(
          {ReplacementAttr{"paddingLeft", R::attr::paddingLeft, Attribute(false, TYPE_DIMENSION)},
           ReplacementAttr{"paddingRight", R::attr::paddingRight,
                           Attribute(false, TYPE_DIMENSION)}}));
  rules[R::attr::paddingHorizontal] =
      util::make_unique<DegradeToManyRule>(std::vector<ReplacementAttr>({ReplacementAttr{
          "progressBarPadding", R::attr::progressBarPadding, Attribute(false, TYPE_DIMENSION)}}));

  const util::Range<ApiVersion> api_range{SDK_GINGERBREAD, SDK_O + 1};

  XmlCompatVersioner versioner(&rules);
  std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs =
      versioner.Process(context_.get(), doc.get(), api_range);
  ASSERT_EQ(3u, versioned_docs.size());

  xml::Element* el;

  EXPECT_EQ(static_cast<uint16_t>(0), versioned_docs[0]->file.config.sdkVersion);
  el = xml::FindRootElement(versioned_docs[0].get());
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(2u, el->attributes.size());
  EXPECT_EQ(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"));

  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "paddingLeft");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingRight");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(attr->compiled_attribute);

  EXPECT_EQ(static_cast<uint16_t>(SDK_HONEYCOMB), versioned_docs[1]->file.config.sdkVersion);
  el = xml::FindRootElement(versioned_docs[1].get());
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(1u, el->attributes.size());
  EXPECT_EQ(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"));
  EXPECT_EQ(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingLeft"));
  EXPECT_EQ(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingRight"));

  attr = el->FindAttribute(xml::kSchemaAndroid, "progressBarPadding");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(attr->compiled_attribute);

  EXPECT_EQ(static_cast<uint16_t>(SDK_LOLLIPOP_MR1), versioned_docs[2]->file.config.sdkVersion);
  el = xml::FindRootElement(versioned_docs[2].get());
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(2u, el->attributes.size());
  EXPECT_EQ(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingLeft"));
  EXPECT_EQ(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingRight"));

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "progressBarPadding");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(attr->compiled_attribute);
}

TEST_F(XmlCompatVersionerTest, DegradeRuleOverridesExistingAttribute) {
  auto doc = test::BuildXmlDomForPackageName(context_.get(), R"EOF(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:paddingHorizontal="24dp"
          android:paddingLeft="16dp"
          android:paddingRight="16dp"/>)EOF");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  Item* padding_horizontal_value = xml::FindRootElement(doc.get())
                                       ->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal")
                                       ->compiled_value.get();
  ASSERT_NE(nullptr, padding_horizontal_value);

  XmlCompatVersioner::Rules rules;
  rules[R::attr::paddingHorizontal] =
      util::make_unique<DegradeToManyRule>(std::vector<ReplacementAttr>(
          {ReplacementAttr{"paddingLeft", R::attr::paddingLeft, Attribute(false, TYPE_DIMENSION)},
           ReplacementAttr{"paddingRight", R::attr::paddingRight,
                           Attribute(false, TYPE_DIMENSION)}}));

  const util::Range<ApiVersion> api_range{SDK_GINGERBREAD, SDK_O + 1};

  XmlCompatVersioner versioner(&rules);
  std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs =
      versioner.Process(context_.get(), doc.get(), api_range);
  ASSERT_EQ(2u, versioned_docs.size());

  xml::Element* el;

  EXPECT_EQ(static_cast<uint16_t>(0), versioned_docs[0]->file.config.sdkVersion);
  el = xml::FindRootElement(versioned_docs[0].get());
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(2u, el->attributes.size());
  EXPECT_EQ(nullptr, el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"));

  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "paddingLeft");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(padding_horizontal_value->Equals(attr->compiled_value.get()));
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingRight");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(padding_horizontal_value->Equals(attr->compiled_value.get()));
  ASSERT_TRUE(attr->compiled_attribute);

  EXPECT_EQ(static_cast<uint16_t>(SDK_LOLLIPOP_MR1), versioned_docs[1]->file.config.sdkVersion);
  el = xml::FindRootElement(versioned_docs[1].get());
  ASSERT_NE(nullptr, el);
  EXPECT_EQ(3u, el->attributes.size());

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(padding_horizontal_value->Equals(attr->compiled_value.get()));
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingLeft");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(padding_horizontal_value->Equals(attr->compiled_value.get()));
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingRight");
  ASSERT_NE(nullptr, attr);
  ASSERT_NE(nullptr, attr->compiled_value);
  ASSERT_TRUE(padding_horizontal_value->Equals(attr->compiled_value.get()));
  ASSERT_TRUE(attr->compiled_attribute);
}

}  // namespace aapt
