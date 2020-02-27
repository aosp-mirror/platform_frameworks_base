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
  auto overlayable = std::make_shared<Overlayable>("Name", "overlay://theme",
                                                   Source("res/values/overlayable.xml", 40));
  OverlayableItem overlayable_item(overlayable);
  overlayable_item.policies |= PolicyFlags::PRODUCT_PARTITION;
  overlayable_item.policies |= PolicyFlags::VENDOR_PARTITION;
  overlayable_item.comment = "comment";
  overlayable_item.source = Source("res/values/overlayable.xml", 42);

  const ResourceName name = test::ParseNameOrDie("android:string/foo");
  ASSERT_TRUE(table.SetOverlayable(name, overlayable_item, test::GetDiagnostics()));
  Maybe<ResourceTable::SearchResult> search_result = table.FindResource(name);

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
  ASSERT_TRUE(table.SetOverlayable(foo, overlayable, test::GetDiagnostics()));

  const ResourceName bar = test::ParseNameOrDie("android:string/bar");
  OverlayableItem overlayable2(group);
  overlayable2.policies = PolicyFlags::PRODUCT_PARTITION;
  ASSERT_TRUE(table.SetOverlayable(bar, overlayable2, test::GetDiagnostics()));

  const ResourceName baz = test::ParseNameOrDie("android:string/baz");
  OverlayableItem overlayable3(group);
  overlayable3.policies = PolicyFlags::VENDOR_PARTITION;
  ASSERT_TRUE(table.SetOverlayable(baz, overlayable3, test::GetDiagnostics()));
}

TEST(ResourceTableTest, SetOverlayableDifferentResourcesDifferentName) {
  ResourceTable table;

  const ResourceName foo = test::ParseNameOrDie("android:string/foo");
  OverlayableItem overlayable_item(std::make_shared<Overlayable>("Name", "overlay://theme"));
  overlayable_item.policies = PolicyFlags::PRODUCT_PARTITION;
  ASSERT_TRUE(table.SetOverlayable(foo, overlayable_item, test::GetDiagnostics()));

  const ResourceName bar = test::ParseNameOrDie("android:string/bar");
  OverlayableItem overlayable_item2(std::make_shared<Overlayable>("Name2",  "overlay://theme"));
  overlayable_item2.policies = PolicyFlags::PRODUCT_PARTITION;
  ASSERT_TRUE(table.SetOverlayable(bar, overlayable_item2, test::GetDiagnostics()));
}

TEST(ResourceTableTest, SetOverlayableSameResourcesFail) {
  ResourceTable table;
  const ResourceName name = test::ParseNameOrDie("android:string/foo");

  auto overlayable = std::make_shared<Overlayable>("Name", "overlay://theme");
  OverlayableItem overlayable_item(overlayable);
  ASSERT_TRUE(table.SetOverlayable(name, overlayable_item, test::GetDiagnostics()));

  OverlayableItem overlayable_item2(overlayable);
  ASSERT_FALSE(table.SetOverlayable(name, overlayable_item2, test::GetDiagnostics()));
}

TEST(ResourceTableTest,  SetOverlayableSameResourcesDifferentNameFail) {
  ResourceTable table;
  const ResourceName name = test::ParseNameOrDie("android:string/foo");

  auto overlayable = std::make_shared<Overlayable>("Name", "overlay://theme");
  OverlayableItem overlayable_item(overlayable);
  ASSERT_TRUE(table.SetOverlayable(name, overlayable_item, test::GetDiagnostics()));

  auto overlayable2 = std::make_shared<Overlayable>("Other", "overlay://theme");
  OverlayableItem overlayable_item2(overlayable2);
  ASSERT_FALSE(table.SetOverlayable(name, overlayable_item2, test::GetDiagnostics()));
}

TEST(ResourceTableTest, AllowDuplictaeResourcesNames) {
  ResourceTable table(/* validate_resources */ false);

  const ResourceName foo_name = test::ParseNameOrDie("android:bool/foo");
  ASSERT_TRUE(table.AddResourceWithId(foo_name, ResourceId(0x7f0100ff), ConfigDescription{} , "",
                                      test::BuildPrimitive(android::Res_value::TYPE_INT_BOOLEAN, 0),
                                      test::GetDiagnostics()));
  ASSERT_TRUE(table.AddResourceWithId(foo_name, ResourceId(0x7f010100), ConfigDescription{} , "",
                                      test::BuildPrimitive(android::Res_value::TYPE_INT_BOOLEAN, 1),
                                      test::GetDiagnostics()));

  ASSERT_TRUE(table.SetVisibilityWithId(foo_name, Visibility{Visibility::Level::kPublic},
                                        ResourceId(0x7f0100ff), test::GetDiagnostics()));
  ASSERT_TRUE(table.SetVisibilityWithId(foo_name, Visibility{Visibility::Level::kPrivate},
                                        ResourceId(0x7f010100), test::GetDiagnostics()));

  auto package = table.FindPackageById(0x7f);
  ASSERT_THAT(package, NotNull());
  auto type = package->FindType(ResourceType::kBool);
  ASSERT_THAT(type, NotNull());

  auto entry1 = type->FindEntry("foo", 0x00ff);
  ASSERT_THAT(entry1, NotNull());
  ASSERT_THAT(entry1->id, Eq(0x00ff));
  ASSERT_THAT(entry1->values[0], NotNull());
  ASSERT_THAT(entry1->values[0]->value, NotNull());
  ASSERT_THAT(ValueCast<BinaryPrimitive>(entry1->values[0]->value.get()), NotNull());
  ASSERT_THAT(ValueCast<BinaryPrimitive>(entry1->values[0]->value.get())->value.data, Eq(0u));
  ASSERT_THAT(entry1->visibility.level, Visibility::Level::kPublic);

  auto entry2 = type->FindEntry("foo", 0x0100);
  ASSERT_THAT(entry2, NotNull());
  ASSERT_THAT(entry2->id, Eq(0x0100));
  ASSERT_THAT(entry2->values[0], NotNull());
  ASSERT_THAT(entry1->values[0]->value, NotNull());
  ASSERT_THAT(ValueCast<BinaryPrimitive>(entry2->values[0]->value.get()), NotNull());
  ASSERT_THAT(ValueCast<BinaryPrimitive>(entry2->values[0]->value.get())->value.data, Eq(1u));
  ASSERT_THAT(entry2->visibility.level, Visibility::Level::kPrivate);
}

}  // namespace aapt
