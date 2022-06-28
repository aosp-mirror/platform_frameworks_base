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

#include "xml/XmlDom.h"

#include <string>

#include "format/binary/XmlFlattener.h"
#include "io/StringStream.h"
#include "test/Test.h"

using ::aapt::io::StringInputStream;
using ::aapt::test::ValueEq;
using ::testing::Eq;
using ::testing::NotNull;
using ::testing::Pointee;
using ::testing::SizeIs;
using ::testing::StrEq;

namespace aapt {
namespace xml {

TEST(XmlDomTest, Inflate) {
  std::string input = R"(<?xml version="1.0" encoding="utf-8"?>
      <Layout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="match_parent"
          android:layout_height="wrap_content">
        <TextView android:id="@+id/id"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content" />
      </Layout>)";

  StdErrDiagnostics diag;
  StringInputStream in(input);
  std::unique_ptr<XmlResource> doc = Inflate(&in, &diag, Source("test.xml"));
  ASSERT_THAT(doc, NotNull());

  Element* el = doc->root.get();
  EXPECT_THAT(el->namespace_decls, SizeIs(1u));
  EXPECT_THAT(el->namespace_decls[0].uri, StrEq(xml::kSchemaAndroid));
  EXPECT_THAT(el->namespace_decls[0].prefix, StrEq("android"));
}

TEST(XmlDomTest, BinaryInflate) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<XmlResource> doc = util::make_unique<XmlResource>();
  doc->root = util::make_unique<Element>();
  doc->root->name = "Layout";
  doc->root->line_number = 2u;

  xml::Attribute attr;
  attr.name = "text";
  attr.namespace_uri = kSchemaAndroid;
  attr.compiled_attribute = AaptAttribute(
      aapt::Attribute(android::ResTable_map::TYPE_REFERENCE | android::ResTable_map::TYPE_STRING),
      ResourceId(0x01010001u));
  attr.value = "@string/foo";
  attr.compiled_value = test::BuildReference("string/foo", ResourceId(0x7f010000u));
  doc->root->attributes.push_back(std::move(attr));

  NamespaceDecl decl;
  decl.uri = kSchemaAndroid;
  decl.prefix = "android";
  decl.line_number = 2u;
  doc->root->namespace_decls.push_back(decl);

  BigBuffer buffer(4096);
  XmlFlattenerOptions options;
  options.keep_raw_values = true;
  XmlFlattener flattener(&buffer, options);
  ASSERT_TRUE(flattener.Consume(context.get(), doc.get()));

  auto block = util::Copy(buffer);
  std::unique_ptr<XmlResource> new_doc = Inflate(block.get(), buffer.size(), nullptr);
  ASSERT_THAT(new_doc, NotNull());

  EXPECT_THAT(new_doc->root->name, StrEq("Layout"));
  EXPECT_THAT(new_doc->root->line_number, Eq(2u));

  ASSERT_THAT(new_doc->root->attributes, SizeIs(1u));
  EXPECT_THAT(new_doc->root->attributes[0].name, StrEq("text"));
  EXPECT_THAT(new_doc->root->attributes[0].namespace_uri, StrEq(kSchemaAndroid));

  // We only check that the resource ID was preserved. There is no where to encode the types that
  // the Attribute accepts (eg: string|reference).
  ASSERT_TRUE(new_doc->root->attributes[0].compiled_attribute);
  EXPECT_THAT(new_doc->root->attributes[0].compiled_attribute.value().id,
              Eq(ResourceId(0x01010001u)));

  EXPECT_THAT(new_doc->root->attributes[0].value, StrEq("@string/foo"));
  EXPECT_THAT(new_doc->root->attributes[0].compiled_value,
              Pointee(ValueEq(Reference(ResourceId(0x7f010000u)))));

  ASSERT_THAT(new_doc->root->namespace_decls, SizeIs(1u));
  EXPECT_THAT(new_doc->root->namespace_decls[0].uri, StrEq(kSchemaAndroid));
  EXPECT_THAT(new_doc->root->namespace_decls[0].prefix, StrEq("android"));
  EXPECT_THAT(new_doc->root->namespace_decls[0].line_number, Eq(2u));
}

// Escaping is handled after parsing of the values for resource-specific values.
TEST(XmlDomTest, ForwardEscapes) {
  std::unique_ptr<XmlResource> doc = test::BuildXmlDom(R"(
      <element value="\?hello" pattern="\\d{5}">\\d{5}</element>)");

  Element* el = doc->root.get();

  Attribute* attr = el->FindAttribute({}, "pattern");
  ASSERT_THAT(attr, NotNull());
  EXPECT_THAT(attr->value, Eq("\\\\d{5}"));

  attr = el->FindAttribute({}, "value");
  ASSERT_THAT(attr, NotNull());
  EXPECT_THAT(attr->value, Eq("\\?hello"));

  ASSERT_THAT(el->children, SizeIs(1u));

  Text* text = xml::NodeCast<xml::Text>(el->children[0].get());
  ASSERT_THAT(text, NotNull());
  EXPECT_THAT(text->text, Eq("\\\\d{5}"));
}

TEST(XmlDomTest, XmlEscapeSequencesAreParsed) {
  std::unique_ptr<XmlResource> doc = test::BuildXmlDom(R"(<element value="&quot;" />)");
  Attribute* attr = doc->root->FindAttribute({}, "value");
  ASSERT_THAT(attr, NotNull());
  EXPECT_THAT(attr->value, Eq("\""));
}

class TestVisitor : public PackageAwareVisitor {
 public:
  using PackageAwareVisitor::Visit;

  void Visit(Element* el) override {
    if (el->name == "View1") {
      EXPECT_THAT(TransformPackageAlias("one"), Eq(ExtractedPackage{"com.one", false}));
    } else if (el->name == "View2") {
      EXPECT_THAT(TransformPackageAlias("one"), Eq(ExtractedPackage{"com.one", false}));
      EXPECT_THAT(TransformPackageAlias("two"), Eq(ExtractedPackage{"com.two", false}));
    } else if (el->name == "View3") {
      EXPECT_THAT(TransformPackageAlias("one"), Eq(ExtractedPackage{"com.one", false}));
      EXPECT_THAT(TransformPackageAlias("two"), Eq(ExtractedPackage{"com.two", false}));
      EXPECT_THAT(TransformPackageAlias("three"), Eq(ExtractedPackage{"com.three", false}));
    } else if (el->name == "View4") {
      EXPECT_THAT(TransformPackageAlias("one"), Eq(ExtractedPackage{"com.one", false}));
      EXPECT_THAT(TransformPackageAlias("two"), Eq(ExtractedPackage{"com.two", false}));
      EXPECT_THAT(TransformPackageAlias("three"), Eq(ExtractedPackage{"com.three", false}));
      EXPECT_THAT(TransformPackageAlias("four"), Eq(ExtractedPackage{"", true}));
    }
  }
};

TEST(XmlDomTest, PackageAwareXmlVisitor) {
  std::unique_ptr<XmlResource> doc = test::BuildXmlDom(R"(
      <View1 xmlns:one="http://schemas.android.com/apk/res/com.one">
        <View2 xmlns:two="http://schemas.android.com/apk/res/com.two">
          <View3 xmlns:three="http://schemas.android.com/apk/res/com.three">
            <View4 xmlns:four="http://schemas.android.com/apk/res-auto" />
          </View3>
        </View2>
      </View1>)");

  TestVisitor visitor;
  doc->root->Accept(&visitor);
}

}  // namespace xml
}  // namespace aapt
