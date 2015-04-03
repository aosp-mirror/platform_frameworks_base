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

#include "ScopedXmlPullParser.h"
#include "SourceXmlPullParser.h"

#include <gtest/gtest.h>
#include <sstream>
#include <string>

namespace aapt {

TEST(ScopedXmlPullParserTest, StopIteratingAtNoNZeroDepth) {
    std::stringstream input;
    input << "<?xml version=\"1.0\" encoding=\"utf-8\"?>" << std::endl
          << "<resources><string></string></resources>" << std::endl;

    SourceXmlPullParser sourceParser(input);
    EXPECT_EQ(XmlPullParser::Event::kStartElement, sourceParser.next());
    EXPECT_EQ(std::u16string(u"resources"), sourceParser.getElementName());

    EXPECT_EQ(XmlPullParser::Event::kStartElement, sourceParser.next());
    EXPECT_EQ(std::u16string(u"string"), sourceParser.getElementName());

    {
        ScopedXmlPullParser scopedParser(&sourceParser);
        EXPECT_EQ(XmlPullParser::Event::kEndElement, scopedParser.next());
        EXPECT_EQ(std::u16string(u"string"), sourceParser.getElementName());

        EXPECT_EQ(XmlPullParser::Event::kEndDocument, scopedParser.next());
    }

    EXPECT_EQ(XmlPullParser::Event::kEndElement, sourceParser.next());
    EXPECT_EQ(std::u16string(u"resources"), sourceParser.getElementName());

    EXPECT_EQ(XmlPullParser::Event::kEndDocument, sourceParser.next());
}

TEST(ScopedXmlPullParserTest, FinishCurrentElementOnDestruction) {
    std::stringstream input;
    input << "<?xml version=\"1.0\" encoding=\"utf-8\"?>" << std::endl
          << "<resources><string></string></resources>" << std::endl;

    SourceXmlPullParser sourceParser(input);
    EXPECT_EQ(XmlPullParser::Event::kStartElement, sourceParser.next());
    EXPECT_EQ(std::u16string(u"resources"), sourceParser.getElementName());

    EXPECT_EQ(XmlPullParser::Event::kStartElement, sourceParser.next());
    EXPECT_EQ(std::u16string(u"string"), sourceParser.getElementName());

    {
        ScopedXmlPullParser scopedParser(&sourceParser);
        EXPECT_EQ(std::u16string(u"string"), sourceParser.getElementName());
    }

    EXPECT_EQ(XmlPullParser::Event::kEndElement, sourceParser.next());
    EXPECT_EQ(std::u16string(u"resources"), sourceParser.getElementName());

    EXPECT_EQ(XmlPullParser::Event::kEndDocument, sourceParser.next());
}

TEST(ScopedXmlPullParserTest, NestedParsersOperateCorrectly) {
    std::stringstream input;
    input << "<?xml version=\"1.0\" encoding=\"utf-8\"?>" << std::endl
          << "<resources><string><foo></foo></string></resources>" << std::endl;

    SourceXmlPullParser sourceParser(input);
    EXPECT_EQ(XmlPullParser::Event::kStartElement, sourceParser.next());
    EXPECT_EQ(std::u16string(u"resources"), sourceParser.getElementName());

    EXPECT_EQ(XmlPullParser::Event::kStartElement, sourceParser.next());
    EXPECT_EQ(std::u16string(u"string"), sourceParser.getElementName());

    {
        ScopedXmlPullParser scopedParser(&sourceParser);
        EXPECT_EQ(std::u16string(u"string"), scopedParser.getElementName());
        while (XmlPullParser::isGoodEvent(scopedParser.next())) {
            if (scopedParser.getEvent() != XmlPullParser::Event::kStartElement) {
                continue;
            }

            ScopedXmlPullParser subScopedParser(&scopedParser);
            EXPECT_EQ(std::u16string(u"foo"), subScopedParser.getElementName());
        }
    }

    EXPECT_EQ(XmlPullParser::Event::kEndElement, sourceParser.next());
    EXPECT_EQ(std::u16string(u"resources"), sourceParser.getElementName());

    EXPECT_EQ(XmlPullParser::Event::kEndDocument, sourceParser.next());
}

} // namespace aapt
