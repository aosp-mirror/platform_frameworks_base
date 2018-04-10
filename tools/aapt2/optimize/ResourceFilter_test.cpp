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

#include "optimize/ResourceFilter.h"

#include "ResourceTable.h"
#include "test/Test.h"

using ::aapt::test::HasValue;
using ::testing::Not;

namespace aapt {

TEST(ResourceFilterTest, SomeValuesAreFilteredOut) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/notblacklisted", ResourceId{}, default_config, "value")
          .AddString("android:string/blacklisted", ResourceId{}, default_config, "value")
          .AddString("android:string/notblacklisted2", ResourceId{}, default_config, "value")
          .AddString("android:string/blacklisted2", ResourceId{}, default_config, "value")
          .Build();

  std::unordered_set<ResourceName> blacklist = {
    ResourceName({}, ResourceType::kString, "blacklisted"),
    ResourceName({}, ResourceType::kString, "blacklisted2"),
  };

  ASSERT_TRUE(ResourceFilter(blacklist).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/notblacklisted", default_config));
  EXPECT_THAT(table, HasValue("android:string/notblacklisted2", default_config));
  EXPECT_THAT(table, Not(HasValue("android:string/blacklisted", default_config)));
  EXPECT_THAT(table, Not(HasValue("android:string/blacklisted2", default_config)));
}

TEST(ResourceFilterTest, TypeIsCheckedBeforeFiltering) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/notblacklisted", ResourceId{}, default_config, "value")
          .AddString("android:string/blacklisted", ResourceId{}, default_config, "value")
          .AddString("android:drawable/notblacklisted", ResourceId{}, default_config, "value")
          .AddString("android:drawable/blacklisted", ResourceId{}, default_config, "value")
          .Build();

  std::unordered_set<ResourceName> blacklist = {
    ResourceName({}, ResourceType::kString, "blacklisted"),
  };

  ASSERT_TRUE(ResourceFilter(blacklist).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/notblacklisted", default_config));
  EXPECT_THAT(table, HasValue("android:drawable/blacklisted", default_config));
  EXPECT_THAT(table, HasValue("android:drawable/notblacklisted", default_config));
  EXPECT_THAT(table, Not(HasValue("android:string/blacklisted", default_config)));
}

}  // namespace aapt
