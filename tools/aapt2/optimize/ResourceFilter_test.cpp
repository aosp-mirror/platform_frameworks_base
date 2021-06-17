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
using ::android::ConfigDescription;
using ::testing::Not;

namespace aapt {

TEST(ResourceFilterTest, SomeValuesAreFilteredOut) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/notexclude_listed", ResourceId{}, default_config, "value")
          .AddString("android:string/exclude_listed", ResourceId{}, default_config, "value")
          .AddString("android:string/notexclude_listed2", ResourceId{}, default_config, "value")
          .AddString("android:string/exclude_listed2", ResourceId{}, default_config, "value")
          .Build();

  std::unordered_set<ResourceName> exclude_list = {
    ResourceName({}, ResourceType::kString, "exclude_listed"),
    ResourceName({}, ResourceType::kString, "exclude_listed2"),
  };

  ASSERT_TRUE(ResourceFilter(exclude_list).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/notexclude_listed", default_config));
  EXPECT_THAT(table, HasValue("android:string/notexclude_listed2", default_config));
  EXPECT_THAT(table, Not(HasValue("android:string/exclude_listed", default_config)));
  EXPECT_THAT(table, Not(HasValue("android:string/exclude_listed2", default_config)));
}

TEST(ResourceFilterTest, TypeIsCheckedBeforeFiltering) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const ConfigDescription default_config = {};

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/notexclude_listed", ResourceId{}, default_config, "value")
          .AddString("android:string/exclude_listed", ResourceId{}, default_config, "value")
          .AddString("android:drawable/notexclude_listed", ResourceId{}, default_config, "value")
          .AddString("android:drawable/exclude_listed", ResourceId{}, default_config, "value")
          .Build();

  std::unordered_set<ResourceName> exclude_list = {
    ResourceName({}, ResourceType::kString, "exclude_listed"),
  };

  ASSERT_TRUE(ResourceFilter(exclude_list).Consume(context.get(), table.get()));
  EXPECT_THAT(table, HasValue("android:string/notexclude_listed", default_config));
  EXPECT_THAT(table, HasValue("android:drawable/exclude_listed", default_config));
  EXPECT_THAT(table, HasValue("android:drawable/notexclude_listed", default_config));
  EXPECT_THAT(table, Not(HasValue("android:string/exclude_listed", default_config)));
}

}  // namespace aapt
