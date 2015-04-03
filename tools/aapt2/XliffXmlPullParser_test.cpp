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
#include "XliffXmlPullParser.h"

#include <gtest/gtest.h>
#include <sstream>
#include <string>

namespace aapt {

TEST(XliffXmlPullParserTest, IgnoreXliffTags) {
    std::stringstream input;
    input << "<?xml version=\"1.0\" encoding=\"utf-8\"?>" << std::endl
          << "<resources xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\">" << std::endl
          << "<string name=\"foo\">"
          << "Hey <xliff:g><xliff:it>there</xliff:it></xliff:g> world</string>" << std::endl
          << "</resources>" << std::endl;
    std::shared_ptr<XmlPullParser> sourceParser = std::make_shared<SourceXmlPullParser>(input);
    XliffXmlPullParser parser(sourceParser);
    EXPECT_EQ(XmlPullParser::Event::kStartDocument, parser.getEvent());

    EXPECT_EQ(XmlPullParser::Event::kStartNamespace, parser.next());
    EXPECT_EQ(parser.getNamespaceUri(), u"urn:oasis:names:tc:xliff:document:1.2");
    EXPECT_EQ(parser.getNamespacePrefix(), u"xliff");

    EXPECT_EQ(XmlPullParser::Event::kStartElement, parser.next());
    EXPECT_EQ(parser.getElementNamespace(), u"");
    EXPECT_EQ(parser.getElementName(), u"resources");
    EXPECT_EQ(XmlPullParser::Event::kText, parser.next()); // Account for newline/whitespace.

    EXPECT_EQ(XmlPullParser::Event::kStartElement, parser.next());
    EXPECT_EQ(parser.getElementNamespace(), u"");
    EXPECT_EQ(parser.getElementName(), u"string");

    EXPECT_EQ(XmlPullParser::Event::kText, parser.next());
    EXPECT_EQ(parser.getText(), u"Hey ");

    EXPECT_EQ(XmlPullParser::Event::kText, parser.next());
    EXPECT_EQ(parser.getText(), u"there");

    EXPECT_EQ(XmlPullParser::Event::kText, parser.next());
    EXPECT_EQ(parser.getText(), u" world");

    EXPECT_EQ(XmlPullParser::Event::kEndElement, parser.next());
    EXPECT_EQ(parser.getElementNamespace(), u"");
    EXPECT_EQ(parser.getElementName(), u"string");
    EXPECT_EQ(XmlPullParser::Event::kText, parser.next()); // Account for newline/whitespace.

    EXPECT_EQ(XmlPullParser::Event::kEndElement, parser.next());
    EXPECT_EQ(parser.getElementNamespace(), u"");
    EXPECT_EQ(parser.getElementName(), u"resources");

    EXPECT_EQ(XmlPullParser::Event::kEndNamespace, parser.next());
    EXPECT_EQ(parser.getNamespacePrefix(), u"xliff");
    EXPECT_EQ(parser.getNamespaceUri(), u"urn:oasis:names:tc:xliff:document:1.2");

    EXPECT_EQ(XmlPullParser::Event::kEndDocument, parser.next());
}

} // namespace aapt
