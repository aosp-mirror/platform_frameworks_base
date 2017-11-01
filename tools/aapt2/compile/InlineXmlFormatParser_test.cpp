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

namespace aapt {

TEST(InlineXmlFormatParserTest, PassThrough) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"EOF(
      <View xmlns:android="http://schemas.android.com/apk/res/android">
        <View android:text="hey">
          <View android:id="hi" />
        </View>
      </View>)EOF");

  InlineXmlFormatParser parser;
  ASSERT_TRUE(parser.Consume(context.get(), doc.get()));
  EXPECT_EQ(0u, parser.GetExtractedInlineXmlDocuments().size());
}

TEST(InlineXmlFormatParserTest, ExtractOneXmlResource) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"EOF(
      <View1 xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:aapt="http://schemas.android.com/aapt">
        <aapt:attr name="android:text">
          <View2 android:text="hey">
            <View3 android:id="hi" />
          </View2>
        </aapt:attr>
      </View1>)EOF");

  doc->file.name = test::ParseNameOrDie("layout/main");

  InlineXmlFormatParser parser;
  ASSERT_TRUE(parser.Consume(context.get(), doc.get()));

  // One XML resource should have been extracted.
  EXPECT_EQ(1u, parser.GetExtractedInlineXmlDocuments().size());

  xml::Element* el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, el);

  EXPECT_EQ("View1", el->name);

  // The <aapt:attr> tag should be extracted.
  EXPECT_EQ(nullptr, el->FindChild(xml::kSchemaAapt, "attr"));

  // The 'android:text' attribute should be set with a reference.
  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "text");
  ASSERT_NE(nullptr, attr);

  ResourceNameRef name_ref;
  ASSERT_TRUE(ResourceUtils::ParseReference(attr->value, &name_ref));

  xml::XmlResource* extracted_doc =
      parser.GetExtractedInlineXmlDocuments()[0].get();
  ASSERT_NE(nullptr, extracted_doc);

  // Make sure the generated reference is correct.
  EXPECT_EQ(name_ref.package, extracted_doc->file.name.package);
  EXPECT_EQ(name_ref.type, extracted_doc->file.name.type);
  EXPECT_EQ(name_ref.entry, extracted_doc->file.name.entry);

  // Verify the structure of the extracted XML.
  el = xml::FindRootElement(extracted_doc);
  ASSERT_NE(nullptr, el);
  EXPECT_EQ("View2", el->name);
  EXPECT_NE(nullptr, el->FindChild({}, "View3"));
}

TEST(InlineXmlFormatParserTest, ExtractTwoXmlResources) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"EOF(
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
      </View1>)EOF");

  doc->file.name = test::ParseNameOrDie("layout/main");

  InlineXmlFormatParser parser;
  ASSERT_TRUE(parser.Consume(context.get(), doc.get()));
  ASSERT_EQ(2u, parser.GetExtractedInlineXmlDocuments().size());

  xml::Element* el = xml::FindRootElement(doc.get());
  ASSERT_NE(nullptr, el);

  EXPECT_EQ("View1", el->name);

  xml::Attribute* attr_text = el->FindAttribute(xml::kSchemaAndroid, "text");
  ASSERT_NE(nullptr, attr_text);

  xml::Attribute* attr_drawable =
      el->FindAttribute(xml::kSchemaAndroid, "drawable");
  ASSERT_NE(nullptr, attr_drawable);

  // The two extracted resources should have different names.
  EXPECT_NE(attr_text->value, attr_drawable->value);

  // The child <aapt:attr> elements should be gone.
  EXPECT_EQ(nullptr, el->FindChild(xml::kSchemaAapt, "attr"));

  xml::XmlResource* extracted_doc_text =
      parser.GetExtractedInlineXmlDocuments()[0].get();
  ASSERT_NE(nullptr, extracted_doc_text);
  el = xml::FindRootElement(extracted_doc_text);
  ASSERT_NE(nullptr, el);
  EXPECT_EQ("View2", el->name);

  xml::XmlResource* extracted_doc_drawable =
      parser.GetExtractedInlineXmlDocuments()[1].get();
  ASSERT_NE(nullptr, extracted_doc_drawable);
  el = xml::FindRootElement(extracted_doc_drawable);
  ASSERT_NE(nullptr, el);
  EXPECT_EQ("vector", el->name);
}

}  // namespace aapt
