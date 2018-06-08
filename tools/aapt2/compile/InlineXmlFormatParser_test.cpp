/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "compile/InlineXmlFormatParser.h"

#include "test/Test.h"

using ::testing::Eq;
using ::testing::IsNull;
using ::testing::Not;
using ::testing::NotNull;
using ::testing::SizeIs;
using ::testing::StrEq;

namespace aapt {

TEST(InlineXmlFormatParserTest, PassThrough) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android">
        <View android:text="hey">
          <View android:id="hi" />
        </View>
      </View>)");

  InlineXmlFormatParser parser;
  ASSERT_TRUE(parser.Consume(context.get(), doc.get()));
  EXPECT_THAT(parser.GetExtractedInlineXmlDocuments(), SizeIs(0u));
}

TEST(InlineXmlFormatParserTest, ExtractOneXmlResource) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(
      <View1 xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:aapt="http://schemas.android.com/aapt">
        <aapt:attr name="android:text">
          <View2 android:text="hey">
            <View3 android:id="hi" />
          </View2>
        </aapt:attr>
      </View1>)");

  doc->file.name = test::ParseNameOrDie("layout/main");
  doc->file.type = ResourceFile::Type::kProtoXml;

  InlineXmlFormatParser parser;
  ASSERT_TRUE(parser.Consume(context.get(), doc.get()));

  // One XML resource should have been extracted.
  EXPECT_THAT(parser.GetExtractedInlineXmlDocuments(), SizeIs(1u));

  xml::Element* el = doc->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->name, StrEq("View1"));

  // The <aapt:attr> tag should be extracted.
  EXPECT_THAT(el->FindChild(xml::kSchemaAapt, "attr"), IsNull());

  // The 'android:text' attribute should be set with a reference.
  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "text");
  ASSERT_THAT(attr, NotNull());

  ResourceNameRef name_ref;
  ASSERT_TRUE(ResourceUtils::ParseReference(attr->value, &name_ref));

  xml::XmlResource* extracted_doc = parser.GetExtractedInlineXmlDocuments()[0].get();
  ASSERT_THAT(extracted_doc, NotNull());

  // Make sure the generated reference is correct.
  EXPECT_THAT(extracted_doc->file.name, Eq(name_ref));

  // Make sure the ResourceFile::Type is the same.
  EXPECT_THAT(extracted_doc->file.type, Eq(ResourceFile::Type::kProtoXml));

  // Verify the structure of the extracted XML.
  el = extracted_doc->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->name, StrEq("View2"));
  EXPECT_THAT(el->FindChild({}, "View3"), NotNull());
}

TEST(InlineXmlFormatParserTest, ExtractTwoXmlResources) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(
      <View1 xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:aapt="http://schemas.android.com/aapt">
        <aapt:attr name="android:text">
          <View2 android:text="hey">
            <View3 android:id="hi" />
          </View2>
        </aapt:attr>

        <aapt:attr name="android:drawable">
          <vector />
        </aapt:attr>
      </View1>)");

  doc->file.name = test::ParseNameOrDie("layout/main");

  InlineXmlFormatParser parser;
  ASSERT_TRUE(parser.Consume(context.get(), doc.get()));
  ASSERT_THAT(parser.GetExtractedInlineXmlDocuments(), SizeIs(2u));

  xml::Element* el = doc->root.get();
  ASSERT_THAT(el, NotNull());
  EXPECT_THAT(el->name, StrEq("View1"));

  xml::Attribute* attr_text = el->FindAttribute(xml::kSchemaAndroid, "text");
  ASSERT_THAT(attr_text, NotNull());

  xml::Attribute* attr_drawable = el->FindAttribute(xml::kSchemaAndroid, "drawable");
  ASSERT_THAT(attr_drawable, NotNull());

  // The two extracted resources should have different names.
  EXPECT_THAT(attr_text->value, Not(Eq(attr_drawable->value)));

  // The child <aapt:attr> elements should be gone.
  EXPECT_THAT(el->FindChild(xml::kSchemaAapt, "attr"), IsNull());

  xml::XmlResource* extracted_doc_text = parser.GetExtractedInlineXmlDocuments()[0].get();
  ASSERT_THAT(extracted_doc_text, NotNull());
  ASSERT_THAT(extracted_doc_text->root, NotNull());
  EXPECT_THAT(extracted_doc_text->root->name, StrEq("View2"));

  xml::XmlResource* extracted_doc_drawable = parser.GetExtractedInlineXmlDocuments()[1].get();
  ASSERT_THAT(extracted_doc_drawable, NotNull());
  ASSERT_THAT(extracted_doc_drawable->root, NotNull());
  EXPECT_THAT(extracted_doc_drawable->root->name, StrEq("vector"));
}

TEST(InlineXmlFormatParserTest, ExtractNestedXmlResources) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(
      <base_root xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:aapt="http://schemas.android.com/aapt">
          <aapt:attr name="inline_xml">
              <inline_root>
                  <aapt:attr name="nested_inline_xml">
                      <nested_inline_root/>
                  </aapt:attr>
                  <aapt:attr name="another_nested_inline_xml">
                      <root/>
                  </aapt:attr>
              </inline_root>
          </aapt:attr>
          <aapt:attr name="turtles">
              <root1>
                  <aapt:attr name="all">
                      <root2>
                          <aapt:attr name="the">
                              <root3>
                                  <aapt:attr name="way">
                                      <root4>
                                          <aapt:attr name="down">
                                              <root5/>
                                          </aapt:attr>
                                      </root4>
                                  </aapt:attr>
                              </root3>
                          </aapt:attr>
                      </root2>
                  </aapt:attr>
              </root1>
          </aapt:attr>
      </base_root>)");

  doc->file.name = test::ParseNameOrDie("layout/main");

  InlineXmlFormatParser parser;
  ASSERT_TRUE(parser.Consume(context.get(), doc.get()));
  // Confirm that all of the nested inline xmls are parsed out.
  ASSERT_THAT(parser.GetExtractedInlineXmlDocuments(), SizeIs(8u));
}

TEST(InlineXmlFormatParserTest, ExtractIntoAppAttribute) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(
      <parent xmlns:app="http://schemas.android.com/apk/res-auto"
              xmlns:aapt="http://schemas.android.com/aapt">
            <aapt:attr name="app:foo">
                <child />
            </aapt:attr>
      </parent>)");

  doc->file.name = test::ParseNameOrDie("layout/main");

  InlineXmlFormatParser parser;
  ASSERT_TRUE(parser.Consume(context.get(), doc.get()));

  ASSERT_THAT(doc->root, NotNull());
  EXPECT_THAT(doc->root->FindAttribute(xml::kSchemaAuto, "foo"), NotNull());
}

TEST(InlineXmlFormatParserTest, ExtractIntoNoNamespaceAttribute) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(
      <parent xmlns:aapt="http://schemas.android.com/aapt">
            <aapt:attr name="foo">
                <child />
            </aapt:attr>
      </parent>)");

  doc->file.name = test::ParseNameOrDie("layout/main");

  InlineXmlFormatParser parser;
  ASSERT_TRUE(parser.Consume(context.get(), doc.get()));

  ASSERT_THAT(doc->root, NotNull());
  EXPECT_THAT(doc->root->FindAttribute({}, "foo"), NotNull());
}

}  // namespace aapt
