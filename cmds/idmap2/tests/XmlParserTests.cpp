/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <cstdio>  // fclose
#include <memory>
#include <string>

#include "TestHelpers.h"
#include "androidfw/AssetsProvider.h"
#include "gtest/gtest.h"
#include "idmap2/XmlParser.h"

namespace android::idmap2 {

Result<XmlParser> CreateTestParser(const std::string& test_file) {
  auto zip = ZipAssetsProvider::Create(GetTestDataPath() + "/target/target.apk", 0 /* flags */);
  if (zip == nullptr) {
    return Error("Failed to open zip file");
  }

  auto data = zip->Open(test_file);
  if (data == nullptr) {
    return Error("Failed to open xml file");
  }

  return XmlParser::Create(data->getBuffer(true /* aligned*/), data->getLength(),
                           /* copy_data */ true);
}

TEST(XmlParserTests, Create) {
  auto xml = CreateTestParser("AndroidManifest.xml");
  ASSERT_TRUE(xml) << xml.GetErrorMessage();

  fclose(stderr);  // silence expected warnings from libandroidfw
  const char* not_xml = "foo";
  auto fail = XmlParser::Create(reinterpret_cast<const uint8_t*>(not_xml), strlen(not_xml));
  ASSERT_FALSE(fail);
}

TEST(XmlParserTests, NextChild) {
  auto xml = CreateTestParser("res/xml/test.xml");
  ASSERT_TRUE(xml) << xml.GetErrorMessage();

  auto root_iter = xml->tree_iterator();
  ASSERT_EQ(root_iter->event(), XmlParser::Event::START_TAG);
  ASSERT_EQ(root_iter->name(), "a");

  auto a_iter = root_iter.begin();
  ASSERT_EQ(a_iter->event(), XmlParser::Event::START_TAG);
  ASSERT_EQ(a_iter->name(), "b");

  auto c_iter = a_iter.begin();
  ASSERT_EQ(c_iter->event(), XmlParser::Event::START_TAG);
  ASSERT_EQ(c_iter->name(), "c");

  ++c_iter;
  ASSERT_EQ(c_iter->event(), XmlParser::Event::END_TAG);
  ASSERT_EQ(c_iter, a_iter.end());

  ++a_iter;
  ASSERT_EQ(a_iter->event(), XmlParser::Event::START_TAG);
  ASSERT_EQ(a_iter->name(), "d");

  // Skip the <e> tag.
  ++a_iter;
  ASSERT_EQ(a_iter->event(), XmlParser::Event::END_TAG);
  ASSERT_EQ(a_iter, root_iter.end());
}

TEST(XmlParserTests, AttributeValues) {
  auto xml = CreateTestParser("res/xml/test.xml");
  ASSERT_TRUE(xml) << xml.GetErrorMessage();

  // Start at the <a> tag.
  auto root_iter = xml->tree_iterator();

  // Start at the <b> tag.
  auto a_iter = root_iter.begin();
  auto attribute_str = a_iter->GetAttributeStringValue("type_string");
  ASSERT_TRUE(attribute_str);
  ASSERT_EQ(*attribute_str, "fortytwo");

  auto attribute_value = a_iter->GetAttributeValue("type_int_dec");
  ASSERT_TRUE(attribute_value);
  ASSERT_EQ(attribute_value->data, 42);

  attribute_value = a_iter->GetAttributeValue("type_int_hex");
  ASSERT_TRUE(attribute_value);
  ASSERT_EQ(attribute_value->data, 42);

  attribute_value = a_iter->GetAttributeValue("type_int_boolean");
  ASSERT_TRUE(attribute_value);
  ASSERT_EQ(attribute_value->data, 0xffffffff);
}

TEST(XmlParserTests, IteratorEquality) {
  auto xml = CreateTestParser("res/xml/test.xml");
  ASSERT_TRUE(xml) << xml.GetErrorMessage();

  // Start at the <a> tag.
  auto root_iter_1 = xml->tree_iterator();
  auto root_iter_2 = xml->tree_iterator();
  ASSERT_EQ(root_iter_1, root_iter_2);
  ASSERT_EQ(*root_iter_1, *root_iter_2);

  // Start at the <b> tag.
  auto a_iter_1 = root_iter_1.begin();
  auto a_iter_2 = root_iter_2.begin();
  ASSERT_NE(a_iter_1, root_iter_1.end());
  ASSERT_NE(a_iter_2, root_iter_2.end());
  ASSERT_EQ(a_iter_1, a_iter_2);
  ASSERT_EQ(*a_iter_1, *a_iter_2);

  // Move to the <d> tag.
  ++a_iter_1;
  ++a_iter_2;
  ASSERT_NE(a_iter_1, root_iter_1.end());
  ASSERT_NE(a_iter_2, root_iter_2.end());
  ASSERT_EQ(a_iter_1, a_iter_2);
  ASSERT_EQ(*a_iter_1, *a_iter_2);

  // Move to the end of the <a> tag.
  ++a_iter_1;
  ++a_iter_2;
  ASSERT_EQ(a_iter_1, root_iter_1.end());
  ASSERT_EQ(a_iter_2, root_iter_2.end());
  ASSERT_EQ(a_iter_1, a_iter_2);
  ASSERT_EQ(*a_iter_1, *a_iter_2);
}

TEST(XmlParserTests, Backtracking) {
  auto xml = CreateTestParser("res/xml/test.xml");
  ASSERT_TRUE(xml) << xml.GetErrorMessage();

  // Start at the <a> tag.
  auto root_iter_1 = xml->tree_iterator();

  // Start at the <b> tag.
  auto a_iter_1 = root_iter_1.begin();

  // Start a second iterator at the <a> tag.
  auto root_iter_2 = root_iter_1;
  ASSERT_EQ(root_iter_1, root_iter_2);
  ASSERT_EQ(*root_iter_1, *root_iter_2);

  // Move the first iterator to the end of the <a> tag.
  auto root_iter_end_1 = root_iter_1.end();
  ++root_iter_1;
  ASSERT_NE(root_iter_1, root_iter_2);
  ASSERT_NE(*root_iter_1, *root_iter_2);

  // Move to the <d> tag.
  ++a_iter_1;
  ASSERT_NE(a_iter_1, root_iter_end_1);

  // Move to the end of the <a> tag.
  ++a_iter_1;
  ASSERT_EQ(a_iter_1, root_iter_end_1);
}

}  // namespace android::idmap2
