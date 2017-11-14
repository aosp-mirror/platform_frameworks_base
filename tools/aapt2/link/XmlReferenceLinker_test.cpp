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

#include "link/Linkers.h"

#include "test/Test.h"

using ::testing::IsNull;
using ::testing::NotNull;

namespace aapt {

class XmlReferenceLinkerTest : public ::testing::Test {
 public:
  void SetUp() override {
    context_ = test::ContextBuilder()
                   .SetCompilationPackage("com.app.test")
                   .SetNameManglerPolicy(NameManglerPolicy{"com.app.test", {"com.android.support"}})
                   .AddSymbolSource(
                       test::StaticSymbolSourceBuilder()
                           .AddPublicSymbol("android:attr/layout_width", ResourceId(0x01010000),
                                            test::AttributeBuilder()
                                                .SetTypeMask(android::ResTable_map::TYPE_ENUM |
                                                             android::ResTable_map::TYPE_DIMENSION)
                                                .AddItem("match_parent", 0xffffffff)
                                                .Build())
                           .AddPublicSymbol("android:attr/background", ResourceId(0x01010001),
                                            test::AttributeBuilder()
                                                .SetTypeMask(android::ResTable_map::TYPE_COLOR)
                                                .Build())
                           .AddPublicSymbol("android:attr/attr", ResourceId(0x01010002),
                                            test::AttributeBuilder().Build())
                           .AddPublicSymbol("android:attr/text", ResourceId(0x01010003),
                                            test::AttributeBuilder()
                                                .SetTypeMask(android::ResTable_map::TYPE_STRING)
                                                .Build())

                           // Add one real symbol that was introduces in v21
                           .AddPublicSymbol("android:attr/colorAccent", ResourceId(0x01010435),
                                            test::AttributeBuilder().Build())

                           // Private symbol.
                           .AddSymbol("android:color/hidden", ResourceId(0x01020001))

                           .AddPublicSymbol("android:id/id", ResourceId(0x01030000))
                           .AddSymbol("com.app.test:id/id", ResourceId(0x7f030000))
                           .AddSymbol("com.app.test:color/green", ResourceId(0x7f020000))
                           .AddSymbol("com.app.test:color/red", ResourceId(0x7f020001))
                           .AddSymbol("com.app.test:attr/colorAccent", ResourceId(0x7f010000),
                                      test::AttributeBuilder()
                                          .SetTypeMask(android::ResTable_map::TYPE_COLOR)
                                          .Build())
                           .AddPublicSymbol("com.app.test:attr/com.android.support$colorAccent",
                                            ResourceId(0x7f010001),
                                            test::AttributeBuilder()
                                                .SetTypeMask(android::ResTable_map::TYPE_COLOR)
                                                .Build())
                           .AddPublicSymbol("com.app.test:attr/attr", ResourceId(0x7f010002),
                                            test::AttributeBuilder().Build())
                           .Build())
                   .Build();
  }

 protected:
  std::unique_ptr<IAaptContext> context_;
};

TEST_F(XmlReferenceLinkerTest, LinkBasicAttributes) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:background="@color/green"
            android:text="hello"
            android:attr="\?hello"
            nonAaptAttr="1"
            nonAaptAttrRef="@id/id"
            class="hello" />)");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  xml::Element* view_el = doc->root.get();
  ASSERT_THAT(view_el, NotNull());

  xml::Attribute* xml_attr = view_el->FindAttribute(xml::kSchemaAndroid, "layout_width");
  ASSERT_THAT(xml_attr, NotNull());
  ASSERT_TRUE(xml_attr->compiled_attribute);
  EXPECT_EQ(make_value(ResourceId(0x01010000)), xml_attr->compiled_attribute.value().id);
  EXPECT_THAT(ValueCast<BinaryPrimitive>(xml_attr->compiled_value.get()), NotNull());

  xml_attr = view_el->FindAttribute(xml::kSchemaAndroid, "background");
  ASSERT_THAT(xml_attr, NotNull());
  ASSERT_TRUE(xml_attr->compiled_attribute);
  EXPECT_EQ(make_value(ResourceId(0x01010001)), xml_attr->compiled_attribute.value().id);
  Reference* ref = ValueCast<Reference>(xml_attr->compiled_value.get());
  ASSERT_THAT(ref, NotNull());
  EXPECT_EQ(make_value(test::ParseNameOrDie("color/green")), ref->name);  // Make sure the name
                                                                          // didn't change.
  EXPECT_EQ(make_value(ResourceId(0x7f020000)), ref->id);

  xml_attr = view_el->FindAttribute(xml::kSchemaAndroid, "text");
  ASSERT_THAT(xml_attr, NotNull());
  EXPECT_TRUE(xml_attr->compiled_attribute);
  EXPECT_THAT(xml_attr->compiled_value, IsNull());  // Strings don't get compiled for memory sake.

  xml_attr = view_el->FindAttribute(xml::kSchemaAndroid, "attr");
  ASSERT_THAT(xml_attr, NotNull());
  EXPECT_TRUE(xml_attr->compiled_attribute);
  EXPECT_THAT(xml_attr->compiled_value, IsNull());  // Should be a plain string.

  xml_attr = view_el->FindAttribute("", "nonAaptAttr");
  ASSERT_THAT(xml_attr, NotNull());
  EXPECT_FALSE(xml_attr->compiled_attribute);
  EXPECT_THAT(ValueCast<BinaryPrimitive>(xml_attr->compiled_value.get()), NotNull());

  xml_attr = view_el->FindAttribute("", "nonAaptAttrRef");
  ASSERT_THAT(xml_attr, NotNull());
  EXPECT_FALSE(xml_attr->compiled_attribute);
  EXPECT_THAT(ValueCast<Reference>(xml_attr->compiled_value.get()), NotNull());

  xml_attr = view_el->FindAttribute("", "class");
  ASSERT_THAT(xml_attr, NotNull());
  EXPECT_FALSE(xml_attr->compiled_attribute);
  EXPECT_THAT(xml_attr->compiled_value, IsNull());
}

TEST_F(XmlReferenceLinkerTest, PrivateSymbolsAreNotLinked) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:colorAccent="@android:color/hidden" />)");

  XmlReferenceLinker linker;
  ASSERT_FALSE(linker.Consume(context_.get(), doc.get()));
}

TEST_F(XmlReferenceLinkerTest, PrivateSymbolsAreLinkedWhenReferenceHasStarPrefix) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDomForPackageName(context_.get(), R"(
    <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:colorAccent="@*android:color/hidden" />)");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));
}

TEST_F(XmlReferenceLinkerTest, LinkMangledAttributes) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:support="http://schemas.android.com/apk/res/com.android.support"
          support:colorAccent="#ff0000" />)");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  xml::Element* view_el = doc->root.get();
  ASSERT_THAT(view_el, NotNull());

  xml::Attribute* xml_attr =
      view_el->FindAttribute(xml::BuildPackageNamespace("com.android.support"), "colorAccent");
  ASSERT_THAT(xml_attr, NotNull());
  ASSERT_TRUE(xml_attr->compiled_attribute);
  EXPECT_EQ(make_value(ResourceId(0x7f010001)), xml_attr->compiled_attribute.value().id);
  EXPECT_THAT(ValueCast<BinaryPrimitive>(xml_attr->compiled_value.get()), NotNull());
}

TEST_F(XmlReferenceLinkerTest, LinkAutoResReference) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:app="http://schemas.android.com/apk/res-auto"
          app:colorAccent="@app:color/red" />)");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  xml::Element* view_el = doc->root.get();
  ASSERT_THAT(view_el, NotNull());

  xml::Attribute* xml_attr = view_el->FindAttribute(xml::kSchemaAuto, "colorAccent");
  ASSERT_THAT(xml_attr, NotNull());
  ASSERT_TRUE(xml_attr->compiled_attribute);
  EXPECT_EQ(make_value(ResourceId(0x7f010000)), xml_attr->compiled_attribute.value().id);
  Reference* ref = ValueCast<Reference>(xml_attr->compiled_value.get());
  ASSERT_THAT(ref, NotNull());
  ASSERT_TRUE(ref->name);
  EXPECT_EQ(make_value(ResourceId(0x7f020001)), ref->id);
}

TEST_F(XmlReferenceLinkerTest, LinkViewWithShadowedPackageAlias) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:app="http://schemas.android.com/apk/res/android" app:attr="@app:id/id">
        <View xmlns:app="http://schemas.android.com/apk/res/com.app.test" app:attr="@app:id/id"/>
      </View>)");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  xml::Element* view_el = doc->root.get();
  ASSERT_THAT(view_el, NotNull());

  // All attributes and references in this element should be referring to
  // "android" (0x01).
  xml::Attribute* xml_attr = view_el->FindAttribute(xml::kSchemaAndroid, "attr");
  ASSERT_THAT(xml_attr, NotNull());
  ASSERT_TRUE(xml_attr->compiled_attribute);
  EXPECT_EQ(make_value(ResourceId(0x01010002)), xml_attr->compiled_attribute.value().id);
  Reference* ref = ValueCast<Reference>(xml_attr->compiled_value.get());
  ASSERT_THAT(ref, NotNull());
  EXPECT_EQ(make_value(ResourceId(0x01030000)), ref->id);

  ASSERT_FALSE(view_el->GetChildElements().empty());
  view_el = view_el->GetChildElements().front();
  ASSERT_THAT(view_el, NotNull());

  // All attributes and references in this element should be referring to
  // "com.app.test" (0x7f).
  xml_attr = view_el->FindAttribute(xml::BuildPackageNamespace("com.app.test"), "attr");
  ASSERT_THAT(xml_attr, NotNull());
  ASSERT_TRUE(xml_attr->compiled_attribute);
  EXPECT_EQ(make_value(ResourceId(0x7f010002)), xml_attr->compiled_attribute.value().id);
  ref = ValueCast<Reference>(xml_attr->compiled_value.get());
  ASSERT_THAT(ref, NotNull());
  EXPECT_EQ(make_value(ResourceId(0x7f030000)), ref->id);
}

TEST_F(XmlReferenceLinkerTest, LinkViewWithLocalPackageAndAliasOfTheSameName) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:android="http://schemas.android.com/apk/res/com.app.test"
          android:attr="@id/id"/>)");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  xml::Element* view_el = doc->root.get();
  ASSERT_THAT(view_el, NotNull());

  // All attributes and references in this element should be referring to
  // "com.app.test" (0x7f).
  xml::Attribute* xml_attr = view_el->FindAttribute(xml::BuildPackageNamespace("com.app.test"), "attr");
  ASSERT_THAT(xml_attr, NotNull());
  ASSERT_TRUE(xml_attr->compiled_attribute);
  EXPECT_EQ(make_value(ResourceId(0x7f010002)), xml_attr->compiled_attribute.value().id);
  Reference* ref = ValueCast<Reference>(xml_attr->compiled_value.get());
  ASSERT_THAT(ref, NotNull());
  EXPECT_EQ(make_value(ResourceId(0x7f030000)), ref->id);
}

}  // namespace aapt
