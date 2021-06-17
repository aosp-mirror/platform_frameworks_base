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

using ::aapt::test::ValueEq;
using ::testing::Eq;
using ::testing::IsNull;
using ::testing::NotNull;
using ::testing::Pointee;
using ::testing::SizeIs;

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
                                     util::make_unique<Attribute>(TYPE_DIMENSION))
                    .AddPublicSymbol("android:attr/paddingRight", R::attr::paddingRight,
                                     util::make_unique<Attribute>(TYPE_DIMENSION))
                    .AddPublicSymbol("android:attr/progressBarPadding", R::attr::progressBarPadding,
                                     util::make_unique<Attribute>(TYPE_DIMENSION))
                    .AddPublicSymbol("android:attr/paddingStart", R::attr::paddingStart,
                                     util::make_unique<Attribute>(TYPE_DIMENSION))
                    .AddPublicSymbol("android:attr/paddingHorizontal", R::attr::paddingHorizontal,
                                     util::make_unique<Attribute>(TYPE_DIMENSION))
                    .AddSymbol("com.app:attr/foo", ResourceId(0x7f010000),
                               util::make_unique<Attribute>(TYPE_STRING))
                    .Build())
            .Build();
  }

 protected:
  std::unique_ptr<IAaptContext> context_;
};

TEST_F(XmlCompatVersionerTest, NoRulesOnlyStripsAndCopies) {
  auto doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:app="http://schemas.android.com/apk/res-auto"
          android:paddingHorizontal="24dp"
          app:foo="16dp"
          foo="bar"/>)");

  XmlReferenceLinker linker(nullptr);
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  XmlCompatVersioner::Rules rules;
  const util::Range<ApiVersion> api_range{SDK_GINGERBREAD, SDK_O + 1};

  XmlCompatVersioner versioner(&rules);
  std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs =
      versioner.Process(context_.get(), doc.get(), api_range);
  ASSERT_THAT(versioned_docs, SizeIs(2u));

  xml::Element* el;

  // Source XML file's sdkVersion == 0, so the first one must also have the same sdkVersion.
  EXPECT_THAT(versioned_docs[0]->file.config.sdkVersion, Eq(0u));
  el = versioned_docs[0]->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->attributes, SizeIs(2u));
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"), IsNull());
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAuto, "foo"), NotNull());
  EXPECT_THAT(el->FindAttribute({}, "foo"), NotNull());

  EXPECT_THAT(versioned_docs[1]->file.config.sdkVersion, Eq(SDK_LOLLIPOP_MR1));
  el = versioned_docs[1]->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->attributes, SizeIs(3u));
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"), NotNull());
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAuto, "foo"), NotNull());
  EXPECT_THAT(el->FindAttribute({}, "foo"), NotNull());
}

TEST_F(XmlCompatVersionerTest, SingleRule) {
  auto doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:app="http://schemas.android.com/apk/res-auto"
          android:paddingHorizontal="24dp"
          app:foo="16dp"
          foo="bar"/>)");

  XmlReferenceLinker linker(nullptr);
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  XmlCompatVersioner::Rules rules;
  rules[R::attr::paddingHorizontal] =
      util::make_unique<DegradeToManyRule>(std::vector<ReplacementAttr>(
          {ReplacementAttr{"paddingLeft", R::attr::paddingLeft, Attribute(TYPE_DIMENSION)},
           ReplacementAttr{"paddingRight", R::attr::paddingRight, Attribute(TYPE_DIMENSION)}}));

  const util::Range<ApiVersion> api_range{SDK_GINGERBREAD, SDK_O + 1};

  XmlCompatVersioner versioner(&rules);
  std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs =
      versioner.Process(context_.get(), doc.get(), api_range);
  ASSERT_THAT(versioned_docs, SizeIs(2u));

  xml::Element* el;

  EXPECT_THAT(versioned_docs[0]->file.config.sdkVersion, Eq(0u));
  el = versioned_docs[0]->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->attributes, SizeIs(4u));
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"), IsNull());
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAuto, "foo"), NotNull());
  EXPECT_THAT(el->FindAttribute({}, "foo"), NotNull());

  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "paddingLeft");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingRight");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);

  EXPECT_THAT(versioned_docs[1]->file.config.sdkVersion, Eq(SDK_LOLLIPOP_MR1));
  el = versioned_docs[1]->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->attributes, SizeIs(5u));
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"), NotNull());
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAuto, "foo"), NotNull());
  EXPECT_THAT(el->FindAttribute({}, "foo"), NotNull());

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingLeft");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingRight");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);
}

TEST_F(XmlCompatVersionerTest, ChainedRule) {
  auto doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:paddingHorizontal="24dp" />)");

  XmlReferenceLinker linker(nullptr);
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  XmlCompatVersioner::Rules rules;
  rules[R::attr::progressBarPadding] =
      util::make_unique<DegradeToManyRule>(std::vector<ReplacementAttr>(
          {ReplacementAttr{"paddingLeft", R::attr::paddingLeft, Attribute(TYPE_DIMENSION)},
           ReplacementAttr{"paddingRight", R::attr::paddingRight, Attribute(TYPE_DIMENSION)}}));
  rules[R::attr::paddingHorizontal] =
      util::make_unique<DegradeToManyRule>(std::vector<ReplacementAttr>({ReplacementAttr{
          "progressBarPadding", R::attr::progressBarPadding, Attribute(TYPE_DIMENSION)}}));

  const util::Range<ApiVersion> api_range{SDK_GINGERBREAD, SDK_O + 1};

  XmlCompatVersioner versioner(&rules);
  std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs =
      versioner.Process(context_.get(), doc.get(), api_range);
  ASSERT_THAT(versioned_docs, SizeIs(3u));

  xml::Element* el;

  EXPECT_THAT(versioned_docs[0]->file.config.sdkVersion, Eq(0u));
  el = versioned_docs[0]->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->attributes, SizeIs(2u));
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"), IsNull());

  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "paddingLeft");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingRight");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);

  EXPECT_THAT(versioned_docs[1]->file.config.sdkVersion, Eq(SDK_HONEYCOMB));
  el = versioned_docs[1]->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->attributes, SizeIs(1u));
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"), IsNull());
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingLeft"), IsNull());
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingRight"), IsNull());

  attr = el->FindAttribute(xml::kSchemaAndroid, "progressBarPadding");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);

  EXPECT_THAT(versioned_docs[2]->file.config.sdkVersion, Eq(SDK_LOLLIPOP_MR1));
  el = versioned_docs[2]->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->attributes, SizeIs(2u));
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingLeft"), IsNull());
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingRight"), IsNull());

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);

  attr = el->FindAttribute(xml::kSchemaAndroid, "progressBarPadding");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);
}

TEST_F(XmlCompatVersionerTest, DegradeRuleOverridesExistingAttribute) {
  auto doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:paddingHorizontal="24dp"
          android:paddingLeft="16dp"
          android:paddingRight="16dp"/>)");

  XmlReferenceLinker linker(nullptr);
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  Item* padding_horizontal_value =
      doc->root->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal")->compiled_value.get();
  ASSERT_THAT(padding_horizontal_value, NotNull());

  XmlCompatVersioner::Rules rules;
  rules[R::attr::paddingHorizontal] =
      util::make_unique<DegradeToManyRule>(std::vector<ReplacementAttr>(
          {ReplacementAttr{"paddingLeft", R::attr::paddingLeft, Attribute(TYPE_DIMENSION)},
           ReplacementAttr{"paddingRight", R::attr::paddingRight, Attribute(TYPE_DIMENSION)}}));

  const util::Range<ApiVersion> api_range{SDK_GINGERBREAD, SDK_O + 1};

  XmlCompatVersioner versioner(&rules);
  std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs =
      versioner.Process(context_.get(), doc.get(), api_range);
  ASSERT_THAT(versioned_docs, SizeIs(2u));

  xml::Element* el;

  EXPECT_THAT(versioned_docs[0]->file.config.sdkVersion, Eq(0u));
  el = versioned_docs[0]->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->attributes, SizeIs(2u));
  EXPECT_THAT(el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal"), IsNull());

  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "paddingLeft");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);
  ASSERT_THAT(attr->compiled_value, Pointee(ValueEq(padding_horizontal_value)));

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingRight");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);
  ASSERT_THAT(attr->compiled_value, Pointee(ValueEq(padding_horizontal_value)));

  EXPECT_THAT(versioned_docs[1]->file.config.sdkVersion, Eq(SDK_LOLLIPOP_MR1));
  el = versioned_docs[1]->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->attributes, SizeIs(3u));

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingHorizontal");
  ASSERT_THAT(attr, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);
  ASSERT_THAT(attr->compiled_value, Pointee(ValueEq(padding_horizontal_value)));

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingLeft");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);
  ASSERT_THAT(attr->compiled_value, Pointee(ValueEq(padding_horizontal_value)));

  attr = el->FindAttribute(xml::kSchemaAndroid, "paddingRight");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->compiled_value, NotNull());
  ASSERT_TRUE(attr->compiled_attribute);
  ASSERT_THAT(attr->compiled_value, Pointee(ValueEq(padding_horizontal_value)));
}

}  // namespace aapt
