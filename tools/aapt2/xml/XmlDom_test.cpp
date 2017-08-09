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

#include "io/StringInputStream.h"
#include "test/Test.h"

using ::aapt::io::StringInputStream;
using ::testing::Eq;
using ::testing::NotNull;
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
      EXPECT_THAT(TransformPackageAlias("one", "local"),
                  Eq(make_value(ExtractedPackage{"com.one", false})));
    } else if (el->name == "View2") {
      EXPECT_THAT(TransformPackageAlias("one", "local"),
                  Eq(make_value(ExtractedPackage{"com.one", false})));
      EXPECT_THAT(TransformPackageAlias("two", "local"),
                  Eq(make_value(ExtractedPackage{"com.two", false})));
    } else if (el->name == "View3") {
      EXPECT_THAT(TransformPackageAlias("one", "local"),
                  Eq(make_value(ExtractedPackage{"com.one", false})));
      EXPECT_THAT(TransformPackageAlias("two", "local"),
                  Eq(make_value(ExtractedPackage{"com.two", false})));
      EXPECT_THAT(TransformPackageAlias("three", "local"),
                  Eq(make_value(ExtractedPackage{"com.three", false})));
    }
  }
};

TEST(XmlDomTest, PackageAwareXmlVisitor) {
  std::unique_ptr<XmlResource> doc = test::BuildXmlDom(R"(
      <View1 xmlns:one="http://schemas.android.com/apk/res/com.one">
        <View2 xmlns:two="http://schemas.android.com/apk/res/com.two">
          <View3 xmlns:three="http://schemas.android.com/apk/res/com.three" />
        </View2>
      </View1>)");

  Debug::DumpXml(doc.get());
  TestVisitor visitor;
  doc->root->Accept(&visitor);
}

}  // namespace xml
}  // namespace aapt
