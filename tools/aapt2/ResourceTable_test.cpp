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

using ::android::StringPiece;
using ::testing::Eq;
using ::testing::NotNull;
using ::testing::StrEq;

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

  EXPECT_TRUE(table.AddResourceMangled(
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

  ASSERT_TRUE(table.AddResource(test::ParseNameOrDie("android:attr/foo"), ConfigDescription{}, "",
                                test::AttributeBuilder().SetWeak(true).Build(),
                                test::GetDiagnostics()));

  Attribute* attr = test::GetValue<Attribute>(&table, "android:attr/foo");
  ASSERT_THAT(attr, NotNull());
  EXPECT_TRUE(attr->IsWeak());

  ASSERT_TRUE(table.AddResource(test::ParseNameOrDie("android:attr/foo"), ConfigDescription{}, "",
                                util::make_unique<Attribute>(), test::GetDiagnostics()));

  attr = test::GetValue<Attribute>(&table, "android:attr/foo");
  ASSERT_THAT(attr, NotNull());
  EXPECT_FALSE(attr->IsWeak());
}

TEST(ResourceTableTest, AllowCompatibleDuplicateAttributes) {
  ResourceTable table;

  const ResourceName name = test::ParseNameOrDie("android:attr/foo");
  Attribute attr_one(android::ResTable_map::TYPE_STRING);
  attr_one.SetWeak(true);
  Attribute attr_two(android::ResTable_map::TYPE_STRING | android::ResTable_map::TYPE_REFERENCE);
  attr_two.SetWeak(true);

  ASSERT_TRUE(table.AddResource(name, ConfigDescription{}, "",
                                util::make_unique<Attribute>(attr_one), test::GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(name, ConfigDescription{}, "",
                                util::make_unique<Attribute>(attr_two), test::GetDiagnostics()));
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

static StringPiece LevelToString(Visibility::Level level) {
  switch (level) {
    case Visibility::Level::kPrivate:
      return "private";
    case Visibility::Level::kPublic:
      return "private";
    default:
      return "undefined";
  }
}

static ::testing::AssertionResult VisibilityOfResource(const ResourceTable& table,
                                                       const ResourceNameRef& name,
                                                       Visibility::Level level,
                                                       const StringPiece& comment) {
  Maybe<ResourceTable::SearchResult> result = table.FindResource(name);
  if (!result) {
    return ::testing::AssertionFailure() << "no resource '" << name << "' found in table";
  }

  const Visibility& visibility = result.value().entry->visibility;
  if (visibility.level != level) {
    return ::testing::AssertionFailure() << "expected visibility " << LevelToString(level)
                                         << " but got " << LevelToString(visibility.level);
  }

  if (visibility.comment != comment) {
    return ::testing::AssertionFailure() << "expected visibility comment '" << comment
                                         << "' but got '" << visibility.comment << "'";
  }
  return ::testing::AssertionSuccess();
}

TEST(ResourceTableTest, SetVisibility) {
  using Level = Visibility::Level;

  ResourceTable table;
  const ResourceName name = test::ParseNameOrDie("android:string/foo");

  Visibility visibility;
  visibility.level = Visibility::Level::kPrivate;
  visibility.comment = "private";
  ASSERT_TRUE(table.SetVisibility(name, visibility, test::GetDiagnostics()));
  ASSERT_TRUE(VisibilityOfResource(table, name, Level::kPrivate, "private"));

  visibility.level = Visibility::Level::kUndefined;
  visibility.comment = "undefined";
  ASSERT_TRUE(table.SetVisibility(name, visibility, test::GetDiagnostics()));
  ASSERT_TRUE(VisibilityOfResource(table, name, Level::kPrivate, "private"));

  visibility.level = Visibility::Level::kPublic;
  visibility.comment = "public";
  ASSERT_TRUE(table.SetVisibility(name, visibility, test::GetDiagnostics()));
  ASSERT_TRUE(VisibilityOfResource(table, name, Level::kPublic, "public"));

  visibility.level = Visibility::Level::kPrivate;
  visibility.comment = "private";
  ASSERT_TRUE(table.SetVisibility(name, visibility, test::GetDiagnostics()));
  ASSERT_TRUE(VisibilityOfResource(table, name, Level::kPublic, "public"));
}

TEST(ResourceTableTest, SetAllowNew) {
  ResourceTable table;
  const ResourceName name = test::ParseNameOrDie("android:string/foo");

  AllowNew allow_new;
  Maybe<ResourceTable::SearchResult> result;

  allow_new.comment = "first";
  ASSERT_TRUE(table.SetAllowNew(name, allow_new, test::GetDiagnostics()));
  result = table.FindResource(name);
  ASSERT_TRUE(result);
  ASSERT_TRUE(result.value().entry->allow_new);
  ASSERT_THAT(result.value().entry->allow_new.value().comment, StrEq("first"));

  allow_new.comment = "second";
  ASSERT_TRUE(table.SetAllowNew(name, allow_new, test::GetDiagnostics()));
  result = table.FindResource(name);
  ASSERT_TRUE(result);
  ASSERT_TRUE(result.value().entry->allow_new);
  ASSERT_THAT(result.value().entry->allow_new.value().comment, StrEq("second"));
}

TEST(ResourceTableTest, SetOverlayable) {
  ResourceTable table;
  const ResourceName name = test::ParseNameOrDie("android:string/foo");

  Overlayable overlayable;

  overlayable.comment = "first";
  ASSERT_TRUE(table.SetOverlayable(name, overlayable, test::GetDiagnostics()));
  Maybe<ResourceTable::SearchResult> result = table.FindResource(name);
  ASSERT_TRUE(result);
  ASSERT_TRUE(result.value().entry->overlayable);
  ASSERT_THAT(result.value().entry->overlayable.value().comment, StrEq("first"));

  overlayable.comment = "second";
  ASSERT_FALSE(table.SetOverlayable(name, overlayable, test::GetDiagnostics()));
}

}  // namespace aapt
