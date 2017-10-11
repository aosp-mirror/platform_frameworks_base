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

namespace aapt {

TEST(ResourceDeduperTest, SameValuesAreDeduped) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};
  const ConfigDescription en_config = test::ParseConfigOrDie("en");
  const ConfigDescription en_v21_config = test::ParseConfigOrDie("en-v21");
  // Chosen because this configuration is compatible with en.
  const ConfigDescription land_config = test::ParseConfigOrDie("land");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/dedupe", ResourceId{}, default_config,
                     "dedupe")
          .AddString("android:string/dedupe", ResourceId{}, en_config, "dedupe")
          .AddString("android:string/dedupe", ResourceId{}, land_config,
                     "dedupe")
          .AddString("android:string/dedupe2", ResourceId{}, default_config,
                     "dedupe")
          .AddString("android:string/dedupe2", ResourceId{}, en_config,
                     "dedupe")
          .AddString("android:string/dedupe2", ResourceId{}, en_v21_config,
                     "keep")
          .AddString("android:string/dedupe2", ResourceId{}, land_config,
                     "dedupe")
          .Build();

  ASSERT_TRUE(ResourceDeduper().Consume(context.get(), table.get()));
  EXPECT_EQ(nullptr, test::GetValueForConfig<String>(
                         table.get(), "android:string/dedupe", en_config));
  EXPECT_EQ(nullptr, test::GetValueForConfig<String>(
                         table.get(), "android:string/dedupe", land_config));
  EXPECT_EQ(nullptr, test::GetValueForConfig<String>(
                         table.get(), "android:string/dedupe2", en_config));
  EXPECT_NE(nullptr, test::GetValueForConfig<String>(
                         table.get(), "android:string/dedupe2", en_v21_config));
}

TEST(ResourceDeduperTest, DifferentValuesAreKept) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};
  const ConfigDescription en_config = test::ParseConfigOrDie("en");
  const ConfigDescription en_v21_config = test::ParseConfigOrDie("en-v21");
  // Chosen because this configuration is compatible with en.
  const ConfigDescription land_config = test::ParseConfigOrDie("land");

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/keep", ResourceId{}, default_config,
                     "keep")
          .AddString("android:string/keep", ResourceId{}, en_config, "keep")
          .AddString("android:string/keep", ResourceId{}, en_v21_config,
                     "keep2")
          .AddString("android:string/keep", ResourceId{}, land_config, "keep2")
          .Build();

  ASSERT_TRUE(ResourceDeduper().Consume(context.get(), table.get()));
  EXPECT_NE(nullptr, test::GetValueForConfig<String>(
                         table.get(), "android:string/keep", en_config));
  EXPECT_NE(nullptr, test::GetValueForConfig<String>(
                         table.get(), "android:string/keep", en_v21_config));
  EXPECT_NE(nullptr, test::GetValueForConfig<String>(
                         table.get(), "android:string/keep", land_config));
}

}  // namespace aapt
