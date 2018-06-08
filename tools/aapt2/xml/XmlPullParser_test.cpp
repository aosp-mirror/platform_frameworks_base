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

#include "androidfw/StringPiece.h"

#include "io/StringStream.h"
#include "test/Test.h"

using ::aapt::io::StringInputStream;
using ::android::StringPiece;
using ::testing::Eq;
using ::testing::StrEq;

using Event = ::aapt::xml::XmlPullParser::Event;

namespace aapt {
namespace xml {

TEST(XmlPullParserTest, NextChildNodeTraversesCorrectly) {
  std::string str =
      R"(<?xml version="1.0" encoding="utf-8"?>
         <a><b><c xmlns:a="http://schema.org"><d/></c><e/></b></a>)";
  StringInputStream input(str);
  XmlPullParser parser(&input);

  const size_t depth_outer = parser.depth();
  ASSERT_TRUE(XmlPullParser::NextChildNode(&parser, depth_outer));

  EXPECT_THAT(parser.event(), Eq(XmlPullParser::Event::kStartElement));
  EXPECT_THAT(parser.element_name(), StrEq("a"));

  const size_t depth_a = parser.depth();
  ASSERT_TRUE(XmlPullParser::NextChildNode(&parser, depth_a));
  EXPECT_THAT(parser.event(), Eq(XmlPullParser::Event::kStartElement));
  EXPECT_THAT(parser.element_name(), StrEq("b"));

  const size_t depth_b = parser.depth();
  ASSERT_TRUE(XmlPullParser::NextChildNode(&parser, depth_b));
  EXPECT_THAT(parser.event(), Eq(XmlPullParser::Event::kStartElement));
  EXPECT_THAT(parser.element_name(), StrEq("c"));

  ASSERT_TRUE(XmlPullParser::NextChildNode(&parser, depth_b));
  EXPECT_THAT(parser.event(), Eq(XmlPullParser::Event::kStartElement));
  EXPECT_THAT(parser.element_name(), StrEq("e"));

  ASSERT_FALSE(XmlPullParser::NextChildNode(&parser, depth_outer));
  EXPECT_THAT(parser.event(), Eq(XmlPullParser::Event::kEndDocument));
}

}  // namespace xml
}  // namespace aapt
