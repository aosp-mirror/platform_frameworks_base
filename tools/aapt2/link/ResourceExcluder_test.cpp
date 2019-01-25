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

#include "link/ResourceExcluder.h"

#include "ResourceTable.h"
#include "test/Test.h"

using ::aapt::test::HasValue;
using ::android::ConfigDescription;
using ::testing::Not;

namespace {

ConfigDescription BuildArg(const std::string arg) {
  ConfigDescription config_description;
  ConfigDescription::Parse(arg, &config_description);
  return config_description;
}

std::vector<ConfigDescription> BuildArgList(const std::string arg) {
  ConfigDescription config_description;
  ConfigDescription::Parse(arg, &config_description);
  return { config_description };
}

} // namespace

namespace aapt {

TEST(ResourceExcluderTest, NonMatchConfigNotExcluded) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  ConfigDescription default_config;
  auto fr_config = test::ParseConfigOrDie("fr");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/test", ResourceId{}, default_config, "default")
          .AddString("android:string/test", ResourceId{}, fr_config, "fr")
          .Build();

  auto args = BuildArgList("en");

  ASSERT_TRUE(ResourceExcluder(args).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/test", default_config));
  EXPECT_THAT(table, HasValue("android:string/test", fr_config));
}

TEST(ResourceExcluderTest, ExactConfigExcluded) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  ConfigDescription default_config;
  auto fr_config = test::ParseConfigOrDie("fr");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/test", ResourceId{}, default_config, "default")
          .AddString("android:string/test", ResourceId{}, fr_config, "fr")
          .Build();

  auto args = BuildArgList("fr");

  ASSERT_TRUE(ResourceExcluder(args).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/test", default_config));
  EXPECT_THAT(table, Not(HasValue("android:string/test", fr_config)));
}

TEST(ResourceExcluderTest, MoreSpecificConfigExcluded) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  ConfigDescription default_config;
  auto fr_land_config = test::ParseConfigOrDie("fr-land");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/test", ResourceId{}, default_config, "default")
          .AddString("android:string/test", ResourceId{}, fr_land_config, "fr-land")
          .Build();

  auto args = BuildArgList("fr");

  ASSERT_TRUE(ResourceExcluder(args).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/test", default_config));
  EXPECT_THAT(table, Not(HasValue("android:string/test", fr_land_config)));
}

TEST(ResourceExcluderTest, MultipleMoreSpecificConfigExcluded) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  ConfigDescription default_config;
  auto night_config = test::ParseConfigOrDie("night");
  auto fr_config = test::ParseConfigOrDie("fr");
  auto fr_land_config = test::ParseConfigOrDie("fr-land");
  auto fr_night_config = test::ParseConfigOrDie("fr-night");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/test", ResourceId{}, default_config, "default")
          .AddString("android:string/test", ResourceId{}, night_config, "night")
          .AddString("android:string/test", ResourceId{}, fr_config, "fr")
          .AddString("android:string/test", ResourceId{}, fr_land_config, "fr-land")
          .AddString("android:string/test", ResourceId{}, fr_night_config, "fr-night")
          .Build();

  auto args = BuildArgList("fr");

  ASSERT_TRUE(ResourceExcluder(args).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/test", default_config));
  EXPECT_THAT(table, HasValue("android:string/test", night_config));
  EXPECT_THAT(table, Not(HasValue("android:string/test", fr_config)));
  EXPECT_THAT(table, Not(HasValue("android:string/test", fr_land_config)));
  EXPECT_THAT(table, Not(HasValue("android:string/test", fr_night_config)));
}

TEST(ResourceExcluderTest, MultipleConfigsExcluded) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  ConfigDescription default_config;
  auto night_config = test::ParseConfigOrDie("night");
  auto fr_config = test::ParseConfigOrDie("fr");
  auto fr_land_config = test::ParseConfigOrDie("fr-land");
  auto fr_night_config = test::ParseConfigOrDie("fr-night");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/test", ResourceId{}, default_config, "default")
          .AddString("android:string/test", ResourceId{}, night_config, "night")
          .AddString("android:string/test", ResourceId{}, fr_config, "fr")
          .AddString("android:string/test", ResourceId{}, fr_land_config, "fr-land")
          .AddString("android:string/test", ResourceId{}, fr_night_config, "fr-night")
          .Build();

  std::vector<ConfigDescription> args;
  args.push_back(BuildArg("land"));
  args.push_back(BuildArg("night"));

  ASSERT_TRUE(ResourceExcluder(args).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/test", default_config));
  EXPECT_THAT(table, Not(HasValue("android:string/test", night_config)));
  EXPECT_THAT(table, HasValue("android:string/test", fr_config));
  EXPECT_THAT(table, Not(HasValue("android:string/test", fr_land_config)));
  EXPECT_THAT(table, Not(HasValue("android:string/test", fr_night_config)));
}

TEST(ResourceExcluderTest, LessSpecificConfigNotExcluded) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  ConfigDescription default_config;
  auto fr_config = test::ParseConfigOrDie("fr");
  auto fr_land_config = test::ParseConfigOrDie("fr-land");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/test", ResourceId{}, default_config, "default")
          .AddString("android:string/test", ResourceId{}, fr_config, "fr")
          .AddString("android:string/test", ResourceId{}, fr_land_config, "fr-land")
          .Build();

  auto args = BuildArgList("fr-land");

  ASSERT_TRUE(ResourceExcluder(args).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/test", default_config));
  EXPECT_THAT(table, HasValue("android:string/test", fr_config));
  EXPECT_THAT(table, Not(HasValue("android:string/test", fr_land_config)));
}

TEST(ResourceExcluderTest, LowerPrecedenceStillExcludes) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  ConfigDescription default_config;
  auto fr_config = test::ParseConfigOrDie("fr");
  auto fr_night_config = test::ParseConfigOrDie("fr-night");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/test", ResourceId{}, default_config, "default")
          .AddString("android:string/test", ResourceId{}, fr_config, "fr")
          .AddString("android:string/test", ResourceId{}, fr_night_config, "fr-night")
          .Build();

  // "night" is lower precedence than "fr"
  auto args = BuildArgList("night");

  ASSERT_TRUE(ResourceExcluder(args).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/test", default_config));
  EXPECT_THAT(table, HasValue("android:string/test", fr_config));
  EXPECT_THAT(table, Not(HasValue("android:string/test", fr_night_config)));
}

TEST(ResourceExcluderTest, OnlyExcludesSpecificTier) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  ConfigDescription default_config;
  auto mdpi_config = test::ParseConfigOrDie("mdpi");
  auto hdpi_config = test::ParseConfigOrDie("hdpi");
  auto xhdpi_config = test::ParseConfigOrDie("xhdpi");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/test", ResourceId{}, default_config, "default")
          .AddString("android:string/test", ResourceId{}, mdpi_config, "mdpi")
          .AddString("android:string/test", ResourceId{}, hdpi_config, "hdpi")
          .AddString("android:string/test", ResourceId{}, xhdpi_config, "xhdpi")
          .Build();

  auto args = BuildArgList("hdpi");

  ASSERT_TRUE(ResourceExcluder(args).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/test", default_config));
  EXPECT_THAT(table, HasValue("android:string/test", mdpi_config));
  EXPECT_THAT(table, Not(HasValue("android:string/test", hdpi_config)));
  EXPECT_THAT(table, HasValue("android:string/test", xhdpi_config));
}

}  // namespace aapt
