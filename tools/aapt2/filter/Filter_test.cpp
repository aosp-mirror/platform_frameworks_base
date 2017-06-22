/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "filter/Filter.h"

#include <string>

#include "io/Util.h"

#include "gtest/gtest.h"

namespace aapt {
namespace {

TEST(FilterTest, FilterChain) {
  FilterChain chain;
  ASSERT_TRUE(chain.Keep("some/random/path"));

  chain.AddFilter(util::make_unique<PrefixFilter>("keep/"));

  ASSERT_FALSE(chain.Keep("removed/path"));
  ASSERT_TRUE(chain.Keep("keep/path/1"));
  ASSERT_TRUE(chain.Keep("keep/path/2"));

  chain.AddFilter(util::make_unique<PrefixFilter>("keep/"));
  chain.AddFilter(util::make_unique<PrefixFilter>("keep/really/"));

  ASSERT_FALSE(chain.Keep("removed/path"));
  ASSERT_FALSE(chain.Keep("/keep/really/wrong/prefix"));
  ASSERT_FALSE(chain.Keep("keep/maybe/1"));
  ASSERT_TRUE(chain.Keep("keep/really/1"));
}

}  // namespace
}  // namespace aapt
