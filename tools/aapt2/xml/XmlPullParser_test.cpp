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

#include "test/Test.h"
#include "util/StringPiece.h"
#include "xml/XmlPullParser.h"

#include <sstream>

namespace aapt {

TEST(XmlPullParserTest, NextChildNodeTraversesCorrectly) {
    std::stringstream str;
    str << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            "<a><b><c xmlns:a=\"http://schema.org\"><d/></c><e/></b></a>";
    xml::XmlPullParser parser(str);

    const size_t depthOuter = parser.getDepth();
    ASSERT_TRUE(xml::XmlPullParser::nextChildNode(&parser, depthOuter));

    EXPECT_EQ(xml::XmlPullParser::Event::kStartElement, parser.getEvent());
    EXPECT_EQ(StringPiece("a"), StringPiece(parser.getElementName()));

    const size_t depthA = parser.getDepth();
    ASSERT_TRUE(xml::XmlPullParser::nextChildNode(&parser, depthA));
    EXPECT_EQ(xml::XmlPullParser::Event::kStartElement, parser.getEvent());
    EXPECT_EQ(StringPiece("b"), StringPiece(parser.getElementName()));

    const size_t depthB = parser.getDepth();
    ASSERT_TRUE(xml::XmlPullParser::nextChildNode(&parser, depthB));
    EXPECT_EQ(xml::XmlPullParser::Event::kStartElement, parser.getEvent());
    EXPECT_EQ(StringPiece("c"), StringPiece(parser.getElementName()));

    ASSERT_TRUE(xml::XmlPullParser::nextChildNode(&parser, depthB));
    EXPECT_EQ(xml::XmlPullParser::Event::kStartElement, parser.getEvent());
    EXPECT_EQ(StringPiece("e"), StringPiece(parser.getElementName()));

    ASSERT_FALSE(xml::XmlPullParser::nextChildNode(&parser, depthOuter));
    EXPECT_EQ(xml::XmlPullParser::Event::kEndDocument, parser.getEvent());
}

} // namespace aapt
