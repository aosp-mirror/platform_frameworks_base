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

#include "ResourceTable.h"
#include "Diagnostics.h"
#include "ResourceValues.h"
#include "test/Test.h"
#include "util/Util.h"

#include <algorithm>
#include <ostream>
#include <string>

using ::testing::NotNull;

namespace aapt {

TEST(ResourceTableTest, FailToAddResourceWithBadName) {
  ResourceTable table;

  EXPECT_FALSE(table.AddResource(
      test::ParseNameOrDie("android:id/hey,there"), ConfigDescription{}, "",
      test::ValueBuilder<Id>().SetSource("test.xml", 21u).Build(),
      test::GetDiagnostics()));

  EXPECT_FALSE(table.AddResource(
      test::ParseNameOrDie("android:id/hey:there"), ConfigDescription{}, "",
      test::ValueBuilder<Id>().SetSource("test.xml", 21u).Build(),
      test::GetDiagnostics()));
}

TEST(ResourceTableTest, AddResourceWithWeirdNameWhenAddingMangledResources) {
  ResourceTable table;

  EXPECT_TRUE(table.AddResourceAllowMangled(
      test::ParseNameOrDie("android:id/heythere       "), ConfigDescription{}, "",
      test::ValueBuilder<Id>().SetSource("test.xml", 21u).Build(), test::GetDiagnostics()));
}

TEST(ResourceTableTest, AddOneResource) {
  ResourceTable table;

  EXPECT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:attr/id"), ConfigDescription{}, "",
      test::ValueBuilder<Id>().SetSource("test/path/file.xml", 23u).Build(),
      test::GetDiagnostics()));

  EXPECT_THAT(test::GetValue<Id>(&table, "android:attr/id"), NotNull());
}

TEST(ResourceTableTest, AddMultipleResources) {
  ResourceTable table;

  ConfigDescription config;
  ConfigDescription language_config;
  memcpy(language_config.language, "pl", sizeof(language_config.language));

  EXPECT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:attr/layout_width"), config, "",
      test::ValueBuilder<Id>().SetSource("test/path/file.xml", 10u).Build(),
      test::GetDiagnostics()));

  EXPECT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:attr/id"), config, "",
      test::ValueBuilder<Id>().SetSource("test/path/file.xml", 12u).Build(),
      test::GetDiagnostics()));

  EXPECT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/ok"), config, "",
      test::ValueBuilder<Id>().SetSource("test/path/file.xml", 14u).Build(),
      test::GetDiagnostics()));

  EXPECT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/ok"), language_config, "",
      test::ValueBuilder<BinaryPrimitive>(android::Res_value{})
          .SetSource("test/path/file.xml", 20u)
          .Build(),
      test::GetDiagnostics()));

  EXPECT_THAT(test::GetValue<Id>(&table, "android:attr/layout_width"), NotNull());
  EXPECT_THAT(test::GetValue<Id>(&table, "android:attr/id"), NotNull());
  EXPECT_THAT(test::GetValue<Id>(&table, "android:string/ok"), NotNull());
  EXPECT_THAT(test::GetValueForConfig<BinaryPrimitive>(&table, "android:string/ok", language_config), NotNull());
}

TEST(ResourceTableTest, OverrideWeakResourceValue) {
  ResourceTable table;

  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:attr/foo"), ConfigDescription{}, "",
      util::make_unique<Attribute>(true), test::GetDiagnostics()));

  Attribute* attr = test::GetValue<Attribute>(&table, "android:attr/foo");
  ASSERT_THAT(attr, NotNull());
  EXPECT_TRUE(attr->IsWeak());

  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:attr/foo"), ConfigDescription{}, "",
      util::make_unique<Attribute>(false), test::GetDiagnostics()));

  attr = test::GetValue<Attribute>(&table, "android:attr/foo");
  ASSERT_THAT(attr, NotNull());
  EXPECT_FALSE(attr->IsWeak());
}

TEST(ResourceTableTest, ProductVaryingValues) {
  ResourceTable table;

  EXPECT_TRUE(table.AddResource(test::ParseNameOrDie("android:string/foo"),
                                test::ParseConfigOrDie("land"), "tablet",
                                util::make_unique<Id>(),
                                test::GetDiagnostics()));
  EXPECT_TRUE(table.AddResource(test::ParseNameOrDie("android:string/foo"),
                                test::ParseConfigOrDie("land"), "phone",
                                util::make_unique<Id>(),
                                test::GetDiagnostics()));

  EXPECT_THAT(test::GetValueForConfigAndProduct<Id>(&table, "android:string/foo",test::ParseConfigOrDie("land"), "tablet"), NotNull());
  EXPECT_THAT(test::GetValueForConfigAndProduct<Id>(&table, "android:string/foo",test::ParseConfigOrDie("land"), "phone"), NotNull());

  Maybe<ResourceTable::SearchResult> sr =
      table.FindResource(test::ParseNameOrDie("android:string/foo"));
  ASSERT_TRUE(sr);
  std::vector<ResourceConfigValue*> values =
      sr.value().entry->FindAllValues(test::ParseConfigOrDie("land"));
  ASSERT_EQ(2u, values.size());
  EXPECT_EQ(std::string("phone"), values[0]->product);
  EXPECT_EQ(std::string("tablet"), values[1]->product);
}

}  // namespace aapt
