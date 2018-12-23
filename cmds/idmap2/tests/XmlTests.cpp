/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "idmap2/Xml.h"
#include "idmap2/ZipFile.h"

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "TestHelpers.h"

using ::testing::IsNull;
using ::testing::NotNull;

namespace android::idmap2 {

TEST(XmlTests, Create) {
  auto zip = ZipFile::Open(GetTestDataPath() + "/target/target.apk");
  ASSERT_THAT(zip, NotNull());

  auto data = zip->Uncompress("AndroidManifest.xml");
  ASSERT_THAT(data, NotNull());

  auto xml = Xml::Create(data->buf, data->size);
  ASSERT_THAT(xml, NotNull());

  fclose(stderr);  // silence expected warnings from libandroidfw
  const char* not_xml = "foo";
  auto fail = Xml::Create(reinterpret_cast<const uint8_t*>(not_xml), strlen(not_xml));
  ASSERT_THAT(fail, IsNull());
}

TEST(XmlTests, FindTag) {
  auto zip = ZipFile::Open(GetTestDataPath() + "/target/target.apk");
  ASSERT_THAT(zip, NotNull());

  auto data = zip->Uncompress("res/xml/test.xml");
  ASSERT_THAT(data, NotNull());

  auto xml = Xml::Create(data->buf, data->size);
  ASSERT_THAT(xml, NotNull());

  auto attrs = xml->FindTag("c");
  ASSERT_THAT(attrs, NotNull());
  ASSERT_EQ(attrs->size(), 4U);
  ASSERT_EQ(attrs->at("type_string"), "fortytwo");
  ASSERT_EQ(std::stoi(attrs->at("type_int_dec")), 42);
  ASSERT_EQ(std::stoi(attrs->at("type_int_hex")), 42);
  ASSERT_NE(std::stoul(attrs->at("type_int_boolean")), 0U);

  auto fail = xml->FindTag("does-not-exist");
  ASSERT_THAT(fail, IsNull());
}

}  // namespace android::idmap2
