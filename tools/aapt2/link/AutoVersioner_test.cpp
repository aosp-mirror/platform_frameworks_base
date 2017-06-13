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

#include "link/Linkers.h"

#include "ConfigDescription.h"
#include "test/Test.h"

using ::testing::NotNull;

namespace aapt {

TEST(AutoVersionerTest, GenerateVersionedResources) {
  const ConfigDescription land_config = test::ParseConfigOrDie("land");
  const ConfigDescription sw600dp_land_config = test::ParseConfigOrDie("sw600dp-land");

  ResourceEntry entry("foo");
  entry.values.push_back(util::make_unique<ResourceConfigValue>(ConfigDescription::DefaultConfig(), ""));
  entry.values.push_back(util::make_unique<ResourceConfigValue>(land_config, ""));
  entry.values.push_back(util::make_unique<ResourceConfigValue>(sw600dp_land_config, ""));

  EXPECT_TRUE(ShouldGenerateVersionedResource(&entry, ConfigDescription::DefaultConfig(), 17));
  EXPECT_TRUE(ShouldGenerateVersionedResource(&entry, land_config, 17));
}

TEST(AutoVersionerTest, GenerateVersionedResourceWhenHigherVersionExists) {
  const ConfigDescription sw600dp_v13_config = test::ParseConfigOrDie("sw600dp-v13");
  const ConfigDescription v21_config = test::ParseConfigOrDie("v21");

  ResourceEntry entry("foo");
  entry.values.push_back(util::make_unique<ResourceConfigValue>(ConfigDescription::DefaultConfig(), ""));
  entry.values.push_back(util::make_unique<ResourceConfigValue>(sw600dp_v13_config, ""));
  entry.values.push_back(util::make_unique<ResourceConfigValue>(v21_config, ""));

  EXPECT_TRUE(ShouldGenerateVersionedResource(&entry, ConfigDescription::DefaultConfig(), 17));
  EXPECT_FALSE(ShouldGenerateVersionedResource(&entry, ConfigDescription::DefaultConfig(), 22));
}

TEST(AutoVersionerTest, VersionStylesForTable) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("app", 0x7f)
          .AddValue(
              "app:style/Foo", test::ParseConfigOrDie("v4"),
              ResourceId(0x7f020000),
              test::StyleBuilder()
                  .AddItem("android:attr/onClick", ResourceId(0x0101026f),
                           util::make_unique<Id>())
                  .AddItem("android:attr/paddingStart", ResourceId(0x010103b3),
                           util::make_unique<Id>())
                  .AddItem("android:attr/requiresSmallestWidthDp",
                           ResourceId(0x01010364), util::make_unique<Id>())
                  .AddItem("android:attr/colorAccent", ResourceId(0x01010435),
                           util::make_unique<Id>())
                  .Build())
          .AddValue(
              "app:style/Foo", test::ParseConfigOrDie("v21"),
              ResourceId(0x7f020000),
              test::StyleBuilder()
                  .AddItem("android:attr/paddingEnd", ResourceId(0x010103b4),
                           util::make_unique<Id>())
                  .Build())
          .Build();

  std::unique_ptr<IAaptContext> context = test::ContextBuilder()
                                              .SetCompilationPackage("app")
                                              .SetPackageId(0x7f)
                                              .Build();

  AutoVersioner versioner;
  ASSERT_TRUE(versioner.Consume(context.get(), table.get()));

  Style* style = test::GetValueForConfig<Style>(table.get(), "app:style/Foo", test::ParseConfigOrDie("v4"));
  ASSERT_THAT(style, NotNull());
  ASSERT_EQ(style->entries.size(), 1u);
  EXPECT_EQ(make_value(test::ParseNameOrDie("android:attr/onClick")), style->entries.front().key.name);

  style = test::GetValueForConfig<Style>(table.get(), "app:style/Foo", test::ParseConfigOrDie("v13"));
  ASSERT_THAT(style, NotNull());
  ASSERT_EQ(style->entries.size(), 2u);
  EXPECT_EQ(make_value(test::ParseNameOrDie("android:attr/onClick")),style->entries[0].key.name);
  EXPECT_EQ(make_value(test::ParseNameOrDie("android:attr/requiresSmallestWidthDp")), style->entries[1].key.name);

  style = test::GetValueForConfig<Style>(table.get(), "app:style/Foo", test::ParseConfigOrDie("v17"));
  ASSERT_THAT(style, NotNull());
  ASSERT_EQ(style->entries.size(), 3u);
  EXPECT_EQ(make_value(test::ParseNameOrDie("android:attr/onClick")), style->entries[0].key.name);
  EXPECT_EQ(make_value(test::ParseNameOrDie("android:attr/requiresSmallestWidthDp")), style->entries[1].key.name);
  EXPECT_EQ(make_value(test::ParseNameOrDie("android:attr/paddingStart")), style->entries[2].key.name);

  style = test::GetValueForConfig<Style>(table.get(), "app:style/Foo", test::ParseConfigOrDie("v21"));
  ASSERT_THAT(style, NotNull());
  ASSERT_EQ(1u, style->entries.size());
  EXPECT_EQ(make_value(test::ParseNameOrDie("android:attr/paddingEnd")), style->entries.front().key.name);
}

}  // namespace aapt
