/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "optimize/ResourceDeduper.h"

#include "ResourceTable.h"
#include "test/Test.h"

using ::aapt::test::HasValue;
using ::android::ConfigDescription;
using ::testing::Not;

namespace aapt {

TEST(ResourceDeduperTest, SameValuesAreDeduped) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};
  const ConfigDescription ldrtl_config = test::ParseConfigOrDie("ldrtl");
  const ConfigDescription ldrtl_v21_config = test::ParseConfigOrDie("ldrtl-v21");
  const ConfigDescription en_config = test::ParseConfigOrDie("en");
  const ConfigDescription en_v21_config = test::ParseConfigOrDie("en-v21");
  // Chosen because this configuration is compatible with ldrtl/en.
  const ConfigDescription land_config = test::ParseConfigOrDie("land");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/dedupe", ResourceId{}, default_config, "dedupe")
          .AddString("android:string/dedupe", ResourceId{}, ldrtl_config, "dedupe")
          .AddString("android:string/dedupe", ResourceId{}, land_config, "dedupe")

          .AddString("android:string/dedupe2", ResourceId{}, default_config, "dedupe")
          .AddString("android:string/dedupe2", ResourceId{}, ldrtl_config, "dedupe")
          .AddString("android:string/dedupe2", ResourceId{}, ldrtl_v21_config, "keep")
          .AddString("android:string/dedupe2", ResourceId{}, land_config, "dedupe")

          .AddString("android:string/dedupe3", ResourceId{}, default_config, "dedupe")
          .AddString("android:string/dedupe3", ResourceId{}, en_config, "dedupe")
          .AddString("android:string/dedupe3", ResourceId{}, en_v21_config, "dedupe")
          .Build();

  ASSERT_TRUE(ResourceDeduper().Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/dedupe", default_config));
  EXPECT_THAT(table, Not(HasValue("android:string/dedupe", ldrtl_config)));
  EXPECT_THAT(table, Not(HasValue("android:string/dedupe", land_config)));

  EXPECT_THAT(table, HasValue("android:string/dedupe2", default_config));
  EXPECT_THAT(table, HasValue("android:string/dedupe2", ldrtl_v21_config));
  EXPECT_THAT(table, Not(HasValue("android:string/dedupe2", ldrtl_config)));

  EXPECT_THAT(table, HasValue("android:string/dedupe3", default_config));
  EXPECT_THAT(table, HasValue("android:string/dedupe3", en_config));
  EXPECT_THAT(table, Not(HasValue("android:string/dedupe3", en_v21_config)));
}

TEST(ResourceDeduperTest, DifferentValuesAreKept) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};
  const ConfigDescription ldrtl_config = test::ParseConfigOrDie("ldrtl");
  const ConfigDescription ldrtl_v21_config = test::ParseConfigOrDie("ldrtl-v21");
  // Chosen because this configuration is compatible with ldrtl.
  const ConfigDescription land_config = test::ParseConfigOrDie("land");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/keep", ResourceId{}, default_config, "keep")
          .AddString("android:string/keep", ResourceId{}, ldrtl_config, "keep")
          .AddString("android:string/keep", ResourceId{}, ldrtl_v21_config, "keep2")
          .AddString("android:string/keep", ResourceId{}, land_config, "keep2")
          .Build();

  ASSERT_TRUE(ResourceDeduper().Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/keep", default_config));
  EXPECT_THAT(table, HasValue("android:string/keep", ldrtl_config));
  EXPECT_THAT(table, HasValue("android:string/keep", ldrtl_v21_config));
  EXPECT_THAT(table, HasValue("android:string/keep", land_config));
}

TEST(ResourceDeduperTest, SameValuesAreDedupedIncompatibleSiblings) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};
  const ConfigDescription ldrtl_config = test::ParseConfigOrDie("ldrtl");
  const ConfigDescription ldrtl_night_config = test::ParseConfigOrDie("ldrtl-night");
  // Chosen because this configuration is not compatible with ldrtl-night.
  const ConfigDescription ldrtl_notnight_config = test::ParseConfigOrDie("ldrtl-notnight");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/keep", ResourceId{}, default_config, "keep")
          .AddString("android:string/keep", ResourceId{}, ldrtl_config, "dedupe")
          .AddString("android:string/keep", ResourceId{}, ldrtl_night_config, "dedupe")
          .AddString("android:string/keep", ResourceId{}, ldrtl_notnight_config, "keep2")
          .Build();

  ASSERT_TRUE(ResourceDeduper().Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/keep", default_config));
  EXPECT_THAT(table, HasValue("android:string/keep", ldrtl_config));
  EXPECT_THAT(table, Not(HasValue("android:string/keep", ldrtl_night_config)));
  EXPECT_THAT(table, HasValue("android:string/keep", ldrtl_notnight_config));
}

TEST(ResourceDeduperTest, SameValuesAreDedupedCompatibleNonSiblings) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};
  const ConfigDescription ldrtl_config = test::ParseConfigOrDie("ldrtl");
  const ConfigDescription ldrtl_night_config = test::ParseConfigOrDie("ldrtl-night");
  // Chosen because this configuration is compatible with ldrtl.
  const ConfigDescription land_config = test::ParseConfigOrDie("land");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/keep", ResourceId{}, default_config, "keep")
          .AddString("android:string/keep", ResourceId{}, ldrtl_config, "dedupe")
          .AddString("android:string/keep", ResourceId{}, ldrtl_night_config, "dedupe")
          .AddString("android:string/keep", ResourceId{}, land_config, "keep2")
          .Build();

  ASSERT_TRUE(ResourceDeduper().Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/keep", default_config));
  EXPECT_THAT(table, HasValue("android:string/keep", ldrtl_config));
  EXPECT_THAT(table, Not(HasValue("android:string/keep", ldrtl_night_config)));
  EXPECT_THAT(table, HasValue("android:string/keep", land_config));
}

TEST(ResourceDeduperTest, LocalesValuesAreKept) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};
  const ConfigDescription fr_config = test::ParseConfigOrDie("fr");
  const ConfigDescription fr_rCA_config = test::ParseConfigOrDie("fr-rCA");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/keep", ResourceId{}, default_config, "keep")
          .AddString("android:string/keep", ResourceId{}, fr_config, "keep")
          .AddString("android:string/keep", ResourceId{}, fr_rCA_config, "keep")
          .Build();

  ASSERT_TRUE(ResourceDeduper().Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/keep", default_config));
  EXPECT_THAT(table, HasValue("android:string/keep", fr_config));
  EXPECT_THAT(table, HasValue("android:string/keep", fr_rCA_config));
}

TEST(ResourceDeduperTest, MccMncValuesAreKept) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};
  const ConfigDescription mcc_config = test::ParseConfigOrDie("mcc262");
  const ConfigDescription mnc_config = test::ParseConfigOrDie("mnc2");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/keep", ResourceId{}, default_config, "keep")
          .AddString("android:string/keep", ResourceId{}, mcc_config, "keep")
          .AddString("android:string/keep", ResourceId{}, mnc_config, "keep")
          .Build();

  ASSERT_TRUE(ResourceDeduper().Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/keep", default_config));
  EXPECT_THAT(table, HasValue("android:string/keep", mcc_config));
  EXPECT_THAT(table, HasValue("android:string/keep", mnc_config));
}


}  // namespace aapt
