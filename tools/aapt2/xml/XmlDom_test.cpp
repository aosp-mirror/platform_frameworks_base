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

#include <sstream>
#include <string>

#include "test/Test.h"

namespace aapt {

constexpr const char* kXmlPreamble =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

TEST(XmlDomTest, Inflate) {
  std::stringstream in(kXmlPreamble);
  in << R"(
      <Layout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="match_parent"
          android:layout_height="wrap_content">
        <TextView android:id="@+id/id"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content" />
      </Layout>)";

  const Source source("test.xml");
  StdErrDiagnostics diag;
  std::unique_ptr<xml::XmlResource> doc = xml::Inflate(&in, &diag, source);
  ASSERT_NE(doc, nullptr);

  xml::Namespace* ns = xml::NodeCast<xml::Namespace>(doc->root.get());
  ASSERT_NE(ns, nullptr);
  EXPECT_EQ(ns->namespace_uri, xml::kSchemaAndroid);
  EXPECT_EQ(ns->namespace_prefix, "android");
}

// Escaping is handled after parsing of the values for resource-specific values.
TEST(XmlDomTest, ForwardEscapes) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(
      <element value="\?hello" pattern="\\d{5}">\\d{5}</element>)");

  xml::Element* el = xml::FindRootElement(doc->root.get());
  ASSERT_NE(nullptr, el);

  xml::Attribute* attr = el->FindAttribute({}, "pattern");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("\\\\d{5}", attr->value);

  attr = el->FindAttribute({}, "value");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("\\?hello", attr->value);

  xml::Text* text = xml::NodeCast<xml::Text>(el->children[0].get());
  ASSERT_NE(nullptr, text);
  EXPECT_EQ("\\\\d{5}", text->text);
}

TEST(XmlDomTest, XmlEscapeSequencesAreParsed) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(<element value="&quot;" />)");

  xml::Element* el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, el);

  xml::Attribute* attr = el->FindAttribute({}, "value");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ("\"", attr->value);
}

}  // namespace aapt
