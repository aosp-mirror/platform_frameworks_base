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

#include "xml/XmlPullParser.h"

#include <sstream>

#include "androidfw/StringPiece.h"

#include "test/Test.h"

using android::StringPiece;

namespace aapt {

TEST(XmlPullParserTest, NextChildNodeTraversesCorrectly) {
  std::stringstream str;
  str << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
         "<a><b><c xmlns:a=\"http://schema.org\"><d/></c><e/></b></a>";
  xml::XmlPullParser parser(str);

  const size_t depth_outer = parser.depth();
  ASSERT_TRUE(xml::XmlPullParser::NextChildNode(&parser, depth_outer));

  EXPECT_EQ(xml::XmlPullParser::Event::kStartElement, parser.event());
  EXPECT_EQ(StringPiece("a"), StringPiece(parser.element_name()));

  const size_t depth_a = parser.depth();
  ASSERT_TRUE(xml::XmlPullParser::NextChildNode(&parser, depth_a));
  EXPECT_EQ(xml::XmlPullParser::Event::kStartElement, parser.event());
  EXPECT_EQ(StringPiece("b"), StringPiece(parser.element_name()));

  const size_t depth_b = parser.depth();
  ASSERT_TRUE(xml::XmlPullParser::NextChildNode(&parser, depth_b));
  EXPECT_EQ(xml::XmlPullParser::Event::kStartElement, parser.event());
  EXPECT_EQ(StringPiece("c"), StringPiece(parser.element_name()));

  ASSERT_TRUE(xml::XmlPullParser::NextChildNode(&parser, depth_b));
  EXPECT_EQ(xml::XmlPullParser::Event::kStartElement, parser.event());
  EXPECT_EQ(StringPiece("e"), StringPiece(parser.element_name()));

  ASSERT_FALSE(xml::XmlPullParser::NextChildNode(&parser, depth_outer));
  EXPECT_EQ(xml::XmlPullParser::Event::kEndDocument, parser.event());
}

}  // namespace aapt
