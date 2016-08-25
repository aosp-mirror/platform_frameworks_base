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
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDom(R"EOF(
      <View xmlns:android="http://schemas.android.com/apk/res/android">
        <View android:text="hey">
          <View android:id="hi" />
        </View>
      </View>)EOF");

    InlineXmlFormatParser parser;
    ASSERT_TRUE(parser.consume(context.get(), doc.get()));
    EXPECT_EQ(0u, parser.getExtractedInlineXmlDocuments().size());
}

TEST(InlineXmlFormatParserTest, ExtractOneXmlResource) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDom(R"EOF(
      <View1 xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:aapt="http://schemas.android.com/aapt">
        <aapt:attr name="android:text">
          <View2 android:text="hey">
            <View3 android:id="hi" />
          </View2>
        </aapt:attr>
      </View1>)EOF");

    doc->file.name = test::parseNameOrDie("layout/main");

    InlineXmlFormatParser parser;
    ASSERT_TRUE(parser.consume(context.get(), doc.get()));

    // One XML resource should have been extracted.
    EXPECT_EQ(1u, parser.getExtractedInlineXmlDocuments().size());

    xml::Element* el = xml::findRootElement(doc.get());
    ASSERT_NE(nullptr, el);

    EXPECT_EQ("View1", el->name);

    // The <aapt:attr> tag should be extracted.
    EXPECT_EQ(nullptr, el->findChild(xml::kSchemaAapt, "attr"));

    // The 'android:text' attribute should be set with a reference.
    xml::Attribute* attr = el->findAttribute(xml::kSchemaAndroid, "text");
    ASSERT_NE(nullptr, attr);

    ResourceNameRef nameRef;
    ASSERT_TRUE(ResourceUtils::parseReference(attr->value, &nameRef));

    xml::XmlResource* extractedDoc = parser.getExtractedInlineXmlDocuments()[0].get();
    ASSERT_NE(nullptr, extractedDoc);

    // Make sure the generated reference is correct.
    EXPECT_EQ(nameRef.package, extractedDoc->file.name.package);
    EXPECT_EQ(nameRef.type, extractedDoc->file.name.type);
    EXPECT_EQ(nameRef.entry, extractedDoc->file.name.entry);

    // Verify the structure of the extracted XML.
    el = xml::findRootElement(extractedDoc);
    ASSERT_NE(nullptr, el);
    EXPECT_EQ("View2", el->name);
    EXPECT_NE(nullptr, el->findChild({}, "View3"));
}

TEST(InlineXmlFormatParserTest, ExtractTwoXmlResources) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDom(R"EOF(
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

    doc->file.name = test::parseNameOrDie("layout/main");

    InlineXmlFormatParser parser;
    ASSERT_TRUE(parser.consume(context.get(), doc.get()));
    ASSERT_EQ(2u, parser.getExtractedInlineXmlDocuments().size());

    xml::Element* el = xml::findRootElement(doc.get());
    ASSERT_NE(nullptr, el);

    EXPECT_EQ("View1", el->name);

    xml::Attribute* attrText = el->findAttribute(xml::kSchemaAndroid, "text");
    ASSERT_NE(nullptr, attrText);

    xml::Attribute* attrDrawable = el->findAttribute(xml::kSchemaAndroid, "drawable");
    ASSERT_NE(nullptr, attrDrawable);

    // The two extracted resources should have different names.
    EXPECT_NE(attrText->value, attrDrawable->value);

    // The child <aapt:attr> elements should be gone.
    EXPECT_EQ(nullptr, el->findChild(xml::kSchemaAapt, "attr"));

    xml::XmlResource* extractedDocText = parser.getExtractedInlineXmlDocuments()[0].get();
    ASSERT_NE(nullptr, extractedDocText);
    el = xml::findRootElement(extractedDocText);
    ASSERT_NE(nullptr, el);
    EXPECT_EQ("View2", el->name);

    xml::XmlResource* extractedDocDrawable = parser.getExtractedInlineXmlDocuments()[1].get();
    ASSERT_NE(nullptr, extractedDocDrawable);
    el = xml::findRootElement(extractedDocDrawable);
    ASSERT_NE(nullptr, el);
    EXPECT_EQ("vector", el->name);
}

} // namespace aapt
