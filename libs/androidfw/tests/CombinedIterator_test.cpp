/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "androidfw/CombinedIterator.h"

#include <algorithm>
#include <string>
#include <strstream>
#include <utility>
#include <vector>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

namespace android {

template <class Coll>
std::string toString(const Coll& coll) {
  std::stringstream res;
  res << "(" << std::size(coll) << ")";
  if (std::size(coll)) {
    res << "{" << coll[0];
    for (int i = 1; i != std::size(coll); ++i) {
      res << "," << coll[i];
    }
    res << "}";
  }
  return res.str();
}

template <class Coll>
void AssertCollectionEq(const Coll& first, const Coll& second) {
  ASSERT_EQ(std::size(first), std::size(second))
      << "first: " << toString(first) << ", second: " << toString(second);
  for (int i = 0; i != std::size(first); ++i) {
    ASSERT_EQ(first[i], second[i])
        << "index: " << i << " first: " << toString(first) << ", second: " << toString(second);
  }
}

TEST(CombinedIteratorTest, Sorting) {
  std::vector<int> v1 = {2, 1, 3, 4, 0};
  std::vector<int> v2 = {20, 10, 30, 40, 0};

  std::sort(CombinedIterator(v1.begin(), v2.begin()), CombinedIterator(v1.end(), v2.end()));

  ASSERT_EQ(v1.size(), v2.size());
  ASSERT_TRUE(std::is_sorted(v1.begin(), v1.end()));
  ASSERT_TRUE(std::is_sorted(v2.begin(), v2.end()));
  AssertCollectionEq(v1, {0, 1, 2, 3, 4});
  AssertCollectionEq(v2, {0, 10, 20, 30, 40});
}

TEST(CombinedIteratorTest, Removing) {
  std::vector<int> v1 = {1, 2, 3, 4, 5, 5, 5, 6};
  std::vector<int> v2 = {10, 20, 30, 40, 50, 50, 50, 60};

  auto newEnd =
      std::remove_if(CombinedIterator(v1.begin(), v2.begin()), CombinedIterator(v1.end(), v2.end()),
                     [](auto&& pair) { return pair.first >= 3 && pair.first <= 5; });

  ASSERT_EQ(newEnd.it1, v1.begin() + 3);
  ASSERT_EQ(newEnd.it2, v2.begin() + 3);

  v1.erase(newEnd.it1, v1.end());
  AssertCollectionEq(v1, {1, 2, 6});
  v2.erase(newEnd.it2, v2.end());
  AssertCollectionEq(v2, {10, 20, 60});
}

TEST(CombinedIteratorTest, InplaceMerge) {
  std::vector<int> v1 = {1, 3, 4, 7, 2, 5, 6};
  std::vector<int> v2 = {10, 30, 40, 70, 20, 50, 60};

  std::inplace_merge(CombinedIterator(v1.begin(), v2.begin()),
                     CombinedIterator(v1.begin() + 4, v2.begin() + 4),
                     CombinedIterator(v1.end(), v2.end()));
  ASSERT_TRUE(std::is_sorted(v1.begin(), v1.end()));
  ASSERT_TRUE(std::is_sorted(v2.begin(), v2.end()));

  AssertCollectionEq(v1, {1, 2, 3, 4, 5, 6, 7});
  AssertCollectionEq(v2, {10, 20, 30, 40, 50, 60, 70});
}

}  // namespace android
