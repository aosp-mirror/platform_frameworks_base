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

using ::android::ConfigDescription;
using ::android::StringPiece;
using ::testing::Eq;
using ::testing::NotNull;
using ::testing::StrEq;

using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace aapt {

TEST(ResourceTableTest, FailToAddResourceWithBadName) {
  ResourceTable table;

  EXPECT_FALSE(
      table.AddResource(NewResourceBuilder(test::ParseNameOrDie("android:id/hey,there")).Build(),
                        test::GetDiagnostics()));

  EXPECT_FALSE(
      table.AddResource(NewResourceBuilder(test::ParseNameOrDie("android:id/hey:there")).Build(),
                        test::GetDiagnostics()));
}

TEST(ResourceTableTest, AddResourceWithWeirdNameWhenAddingMangledResources) {
  ResourceTable table;

  EXPECT_TRUE(
      table.AddResource(NewResourceBuilder(test::ParseNameOrDie("android:id/heythere       "))
                            .SetAllowMangled(true)
                            .Build(),
                        test::GetDiagnostics()));
}

TEST(ResourceTableTest, AddOneResource) {
  ResourceTable table;

  EXPECT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:attr/id"))
          .SetValue(test::ValueBuilder<Id>().SetSource("test/path/file.xml", 23u).Build())
          .Build(),
      test::GetDiagnostics()));

  EXPECT_THAT(test::GetValue<Id>(&table, "android:attr/id"), NotNull());
}

TEST(ResourceTableTest, AddMultipleResources) {
  ResourceTable table;

  ConfigDescription language_config;
  memcpy(language_config.language, "pl", sizeof(language_config.language));

  EXPECT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:attr/layout_width"))
          .SetValue(test::ValueBuilder<Id>().SetSource("test/path/file.xml", 10u).Build())
          .Build(),
      test::GetDiagnostics()));

  EXPECT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:attr/id"))
          .SetValue(test::ValueBuilder<Id>().SetSource("test/path/file.xml", 12u).Build())
          .Build(),
      test::GetDiagnostics()));

  EXPECT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/ok"))
          .SetValue(test::ValueBuilder<Id>().SetSource("test/path/file.xml", 14u).Build())
          .Build(),
      test::GetDiagnostics()));

  EXPECT_TRUE(
      table.AddResource(NewResourceBuilder(test::ParseNameOrDie("android:string/ok"))
                            .SetValue(test::ValueBuilder<BinaryPrimitive>(android::Res_value{})
                                          .SetSource("test/path/file.xml", 20u)
                                          .Build(),
                                      language_config)
                            .Build(),
                        test::GetDiagnostics()));

  EXPECT_THAT(test::GetValue<Id>(&table, "android:attr/layout_width"), NotNull());
  EXPECT_THAT(test::GetValue<Id>(&table, "android:attr/id"), NotNull());
  EXPECT_THAT(test::GetValue<Id>(&table, "android:string/ok"), NotNull());
  EXPECT_THAT(test::GetValueForConfig<BinaryPrimitive>(&table, "android:string/ok", language_config), NotNull());
}

TEST(ResourceTableTest, OverrideWeakResourceValue) {
  ResourceTable table;
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(test::ParseNameOrDie("android:attr/foo"))
                                    .SetValue(test::AttributeBuilder().SetWeak(true).Build())
                                    .Build(),
                                test::GetDiagnostics()));

  Attribute* attr = test::GetValue<Attribute>(&table, "android:attr/foo");
  ASSERT_THAT(attr, NotNull());
  EXPECT_TRUE(attr->IsWeak());

  ASSERT_TRUE(table.AddResource(NewResourceBuilder(test::ParseNameOrDie("android:attr/foo"))
                                    .SetValue(util::make_unique<Attribute>())
                                    .Build(),
                                test::GetDiagnostics()));

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

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(name).SetValue(util::make_unique<Attribute>(attr_one)).Build(),
      test::GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(name).SetValue(util::make_unique<Attribute>(attr_two)).Build(),
      test::GetDiagnostics()));
}

TEST(ResourceTableTest, ProductVaryingValues) {
  ResourceTable table;
  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/foo"))
          .SetValue(util::make_unique<Id>(), test::ParseConfigOrDie("land"), "tablet")
          .Build(),
      test::GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/foo"))
          .SetValue(util::make_unique<Id>(), test::ParseConfigOrDie("land"), "phone")
          .Build(),
      test::GetDiagnostics()));

  EXPECT_THAT(test::GetValueForConfigAndProduct<Id>(&table, "android:string/foo",test::ParseConfigOrDie("land"), "tablet"), NotNull());
  EXPECT_THAT(test::GetValueForConfigAndProduct<Id>(&table, "android:string/foo",test::ParseConfigOrDie("land"), "phone"), NotNull());

  std::optional<ResourceTable::SearchResult> sr =
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
  std::optional<ResourceTable::SearchResult> result = table.FindResource(name);
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
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(name).SetVisibility(visibility).Build(),
                                test::GetDiagnostics()));
  ASSERT_TRUE(VisibilityOfResource(table, name, Level::kPrivate, "private"));

  visibility.level = Visibility::Level::kUndefined;
  visibility.comment = "undefined";
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(name).SetVisibility(visibility).Build(),
                                test::GetDiagnostics()));
  ASSERT_TRUE(VisibilityOfResource(table, name, Level::kPrivate, "private"));

  visibility.level = Visibility::Level::kPublic;
  visibility.comment = "public";
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(name).SetVisibility(visibility).Build(),
                                test::GetDiagnostics()));
  ASSERT_TRUE(VisibilityOfResource(table, name, Level::kPublic, "public"));

  visibility.level = Visibility::Level::kPrivate;
  visibility.comment = "private";
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(name).SetVisibility(visibility).Build(),
                                test::GetDiagnostics()));
  ASSERT_TRUE(VisibilityOfResource(table, name, Level::kPublic, "public"));
}

TEST(ResourceTableTest, SetAllowNew) {
  ResourceTable table;
  const ResourceName name = test::ParseNameOrDie("android:string/foo");

  AllowNew allow_new;
  std::optional<ResourceTable::SearchResult> result;

  allow_new.comment = "first";
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(name).SetAllowNew(allow_new).Build(),
                                test::GetDiagnostics()));
  result = table.FindResource(name);
  ASSERT_TRUE(result);
  ASSERT_TRUE(result.value().entry->allow_new);
  ASSERT_THAT(result.value().entry->allow_new.value().comment, StrEq("first"));

  allow_new.comment = "second";
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(name).SetAllowNew(allow_new).Build(),
                                test::GetDiagnostics()));
  result = table.FindResource(name);
  ASSERT_TRUE(result);
  ASSERT_TRUE(result.value().entry->allow_new);
  ASSERT_THAT(result.value().entry->allow_new.value().comment, StrEq("second"));
}

TEST(ResourceTableTest, SetOverlayable) {
  ResourceTable table;
  auto overlayable = std::make_shared<Overlayable>("Name", "overlay://theme",
                                                   Source("res/values/overlayable.xml", 40));
  OverlayableItem overlayable_item(overlayable);
  overlayable_item.policies |= PolicyFlags::PRODUCT_PARTITION;
  overlayable_item.policies |= PolicyFlags::VENDOR_PARTITION;
  overlayable_item.comment = "comment";
  overlayable_item.source = Source("res/values/overlayable.xml", 42);

  const ResourceName name = test::ParseNameOrDie("android:string/foo");
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(name).SetOverlayable(overlayable_item).Build(),
                                test::GetDiagnostics()));
  std::optional<ResourceTable::SearchResult> search_result = table.FindResource(name);

  ASSERT_TRUE(search_result);
  ASSERT_TRUE(search_result.value().entry->overlayable_item);

  OverlayableItem& result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.overlayable->actor, Eq("overlay://theme"));
  EXPECT_THAT(result_overlayable_item.overlayable->source.path, Eq("res/values/overlayable.xml"));
  EXPECT_THAT(result_overlayable_item.overlayable->source.line, 40);
  EXPECT_THAT(result_overlayable_item.policies, Eq(PolicyFlags::PRODUCT_PARTITION
                                                   | PolicyFlags::VENDOR_PARTITION));
  ASSERT_THAT(result_overlayable_item.comment, StrEq("comment"));
  EXPECT_THAT(result_overlayable_item.source.path, Eq("res/values/overlayable.xml"));
  EXPECT_THAT(result_overlayable_item.source.line, 42);
}

TEST(ResourceTableTest, SetMultipleOverlayableResources) {
  ResourceTable table;

  const ResourceName foo = test::ParseNameOrDie("android:string/foo");
  auto group = std::make_shared<Overlayable>("Name", "overlay://theme");
  OverlayableItem overlayable(group);
  overlayable.policies = PolicyFlags::PRODUCT_PARTITION;
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(foo).SetOverlayable(overlayable).Build(),
                                test::GetDiagnostics()));

  const ResourceName bar = test::ParseNameOrDie("android:string/bar");
  OverlayableItem overlayable2(group);
  overlayable2.policies = PolicyFlags::PRODUCT_PARTITION;
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(bar).SetOverlayable(overlayable2).Build(),
                                test::GetDiagnostics()));

  const ResourceName baz = test::ParseNameOrDie("android:string/baz");
  OverlayableItem overlayable3(group);
  overlayable3.policies = PolicyFlags::VENDOR_PARTITION;
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(baz).SetOverlayable(overlayable3).Build(),
                                test::GetDiagnostics()));
}

TEST(ResourceTableTest, SetOverlayableDifferentResourcesDifferentName) {
  ResourceTable table;

  const ResourceName foo = test::ParseNameOrDie("android:string/foo");
  OverlayableItem overlayable_item(std::make_shared<Overlayable>("Name", "overlay://theme"));
  overlayable_item.policies = PolicyFlags::PRODUCT_PARTITION;
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(foo).SetOverlayable(overlayable_item).Build(),
                                test::GetDiagnostics()));

  const ResourceName bar = test::ParseNameOrDie("android:string/bar");
  OverlayableItem overlayable_item2(std::make_shared<Overlayable>("Name2",  "overlay://theme"));
  overlayable_item2.policies = PolicyFlags::PRODUCT_PARTITION;
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(bar).SetOverlayable(overlayable_item2).Build(),
                                test::GetDiagnostics()));
}

TEST(ResourceTableTest, SetOverlayableSameResourcesFail) {
  ResourceTable table;
  const ResourceName name = test::ParseNameOrDie("android:string/foo");

  auto overlayable = std::make_shared<Overlayable>("Name", "overlay://theme");
  OverlayableItem overlayable_item(overlayable);
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(name).SetOverlayable(overlayable_item).Build(),
                                test::GetDiagnostics()));

  OverlayableItem overlayable_item2(overlayable);
  ASSERT_FALSE(table.AddResource(NewResourceBuilder(name).SetOverlayable(overlayable_item2).Build(),
                                 test::GetDiagnostics()));
}

TEST(ResourceTableTest,  SetOverlayableSameResourcesDifferentNameFail) {
  ResourceTable table;
  const ResourceName name = test::ParseNameOrDie("android:string/foo");

  auto overlayable = std::make_shared<Overlayable>("Name", "overlay://theme");
  OverlayableItem overlayable_item(overlayable);
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(name).SetOverlayable(overlayable_item).Build(),
                                test::GetDiagnostics()));

  auto overlayable2 = std::make_shared<Overlayable>("Other", "overlay://theme");
  OverlayableItem overlayable_item2(overlayable2);
  ASSERT_FALSE(table.AddResource(NewResourceBuilder(name).SetOverlayable(overlayable_item2).Build(),
                                 test::GetDiagnostics()));
}

TEST(ResourceTableTest, ConflictingIds) {
  ResourceTable table;
  const ResourceName name = test::ParseNameOrDie("android:string/foo");
  ASSERT_TRUE(table.AddResource(NewResourceBuilder(name).SetId(0x01010000).Build(),
                                test::GetDiagnostics()));
  ASSERT_FALSE(table.AddResource(NewResourceBuilder(name).SetId(0x01010001).Build(),
                                 test::GetDiagnostics()));
}

TEST(ResourceTableTest, ConflictingIdsCreateEntry) {
  ResourceTable table;
  const ResourceName name = test::ParseNameOrDie("android:string/foo");
  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(name).SetId(0x01010000, OnIdConflict::CREATE_ENTRY).Build(),
      test::GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(name).SetId(0x01010001, OnIdConflict::CREATE_ENTRY).Build(),
      test::GetDiagnostics()));

  // Non-ambiguous query
  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(name).SetId(0x01010001).SetValue(std::make_unique<Id>()).Build(),
      test::GetDiagnostics()));
}

}  // namespace aapt
