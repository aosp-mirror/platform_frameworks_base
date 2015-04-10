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

#include "SourceXmlPullParser.h"
#include "BindingXmlPullParser.h"

#include <gtest/gtest.h>
#include <sstream>
#include <string>

namespace aapt {

constexpr const char16_t* kAndroidNamespaceUri = u"http://schemas.android.com/apk/res/android";

TEST(BindingXmlPullParserTest, SubstituteBindingExpressionsWithTag) {
    std::stringstream input;
    input << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
          << "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
          << "              xmlns:bind=\"http://schemas.android.com/apk/binding\"\n"
          << "              android:id=\"@+id/content\">\n"
          << "  <variable name=\"user\" type=\"com.android.test.User\"/>\n"
          << "  <TextView android:text=\"@{user.name}\" android:layout_width=\"wrap_content\"\n"
          << "            android:layout_height=\"wrap_content\"/>\n"
          << "</LinearLayout>\n";
    std::shared_ptr<XmlPullParser> sourceParser = std::make_shared<SourceXmlPullParser>(input);
    BindingXmlPullParser parser(sourceParser);

    ASSERT_EQ(XmlPullParser::Event::kStartNamespace, parser.next());
    EXPECT_EQ(std::u16string(u"http://schemas.android.com/apk/res/android"),
              parser.getNamespaceUri());

    ASSERT_EQ(XmlPullParser::Event::kStartElement, parser.next());
    EXPECT_EQ(std::u16string(u"LinearLayout"), parser.getElementName());

    while (parser.next() == XmlPullParser::Event::kText) {}

    ASSERT_EQ(XmlPullParser::Event::kStartElement, parser.getEvent());
    EXPECT_EQ(std::u16string(u"TextView"), parser.getElementName());

    ASSERT_EQ(3u, parser.getAttributeCount());
    const auto endAttr = parser.endAttributes();
    EXPECT_NE(endAttr, parser.findAttribute(kAndroidNamespaceUri, u"layout_width"));
    EXPECT_NE(endAttr, parser.findAttribute(kAndroidNamespaceUri, u"layout_height"));
    EXPECT_NE(endAttr, parser.findAttribute(kAndroidNamespaceUri, u"tag"));

    while (parser.next() == XmlPullParser::Event::kText) {}

    ASSERT_EQ(XmlPullParser::Event::kEndElement, parser.getEvent());

    while (parser.next() == XmlPullParser::Event::kText) {}

    ASSERT_EQ(XmlPullParser::Event::kEndElement, parser.getEvent());
    ASSERT_EQ(XmlPullParser::Event::kEndNamespace, parser.next());
}

TEST(BindingXmlPullParserTest, GenerateVariableDeclarations) {
    std::stringstream input;
    input << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
          << "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
          << "              xmlns:bind=\"http://schemas.android.com/apk/binding\"\n"
          << "              android:id=\"@+id/content\">\n"
          << "  <variable name=\"user\" type=\"com.android.test.User\"/>\n"
          << "</LinearLayout>\n";
    std::shared_ptr<XmlPullParser> sourceParser = std::make_shared<SourceXmlPullParser>(input);
    BindingXmlPullParser parser(sourceParser);

    while (XmlPullParser::isGoodEvent(parser.next())) {
        ASSERT_NE(XmlPullParser::Event::kBadDocument, parser.getEvent());
    }

    std::stringstream output;
    ASSERT_TRUE(parser.writeToFile(output));

    std::string result = output.str();
    EXPECT_NE(std::string::npos,
              result.find("<entries name=\"user\" type=\"com.android.test.User\"/>"));
}

TEST(BindingXmlPullParserTest, FailOnMissingNameOrTypeInVariableDeclaration) {
    std::stringstream input;
    input << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
          << "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
          << "              xmlns:bind=\"http://schemas.android.com/apk/binding\"\n"
          << "              android:id=\"@+id/content\">\n"
          << "  <variable name=\"user\"/>\n"
          << "</LinearLayout>\n";
    std::shared_ptr<XmlPullParser> sourceParser = std::make_shared<SourceXmlPullParser>(input);
    BindingXmlPullParser parser(sourceParser);

    while (XmlPullParser::isGoodEvent(parser.next())) {}

    EXPECT_EQ(XmlPullParser::Event::kBadDocument, parser.getEvent());
    EXPECT_FALSE(parser.getLastError().empty());
}


} // namespace aapt
