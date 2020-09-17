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

#include "Optimize.h"

#include "AppInfo.h"
#include "Diagnostics.h"
#include "LoadedApk.h"
#include "Resource.h"
#include "test/Test.h"

using testing::Contains;
using testing::Eq;

namespace aapt {

bool ParseConfig(const std::string&, IAaptContext*, OptimizeOptions*);

using OptimizeTest = CommandTestFixture;

TEST_F(OptimizeTest, ParseConfigWithNoCollapseExemptions) {
  const std::string& content = R"(
string/foo#no_collapse
dimen/bar#no_collapse
)";
  aapt::test::Context context;
  OptimizeOptions options;
  ParseConfig(content, &context, &options);

  const std::set<ResourceName>& name_collapse_exemptions =
      options.table_flattener_options.name_collapse_exemptions;

  ASSERT_THAT(name_collapse_exemptions.size(), Eq(2));
  EXPECT_THAT(name_collapse_exemptions, Contains(ResourceName({}, ResourceType::kString, "foo")));
  EXPECT_THAT(name_collapse_exemptions, Contains(ResourceName({}, ResourceType::kDimen, "bar")));
}

TEST_F(OptimizeTest, ParseConfigWithNoObfuscateExemptions) {
  const std::string& content = R"(
string/foo#no_obfuscate
dimen/bar#no_obfuscate
)";
  aapt::test::Context context;
  OptimizeOptions options;
  ParseConfig(content, &context, &options);

  const std::set<ResourceName>& name_collapse_exemptions =
      options.table_flattener_options.name_collapse_exemptions;

  ASSERT_THAT(name_collapse_exemptions.size(), Eq(2));
  EXPECT_THAT(name_collapse_exemptions, Contains(ResourceName({}, ResourceType::kString, "foo")));
  EXPECT_THAT(name_collapse_exemptions, Contains(ResourceName({}, ResourceType::kDimen, "bar")));
}

}  // namespace aapt
