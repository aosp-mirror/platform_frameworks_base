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

#ifndef AAPT_UTIL_IMMUTABLEMAP_H
#define AAPT_UTIL_IMMUTABLEMAP_H

#include <utility>
#include <vector>

#include "util/TypeTraits.h"

namespace aapt {

template <typename TKey, typename TValue>
class ImmutableMap {
  static_assert(is_comparable<TKey, TKey>::value, "key is not comparable");

 public:
  using const_iterator =
      typename std::vector<std::pair<TKey, TValue>>::const_iterator;

  ImmutableMap(ImmutableMap&&) noexcept = default;
  ImmutableMap& operator=(ImmutableMap&&) noexcept = default;

  static ImmutableMap<TKey, TValue> CreatePreSorted(
      std::initializer_list<std::pair<TKey, TValue>> list) {
    return ImmutableMap(
        std::vector<std::pair<TKey, TValue>>(list.begin(), list.end()));
  }

  static ImmutableMap<TKey, TValue> CreateAndSort(
      std::initializer_list<std::pair<TKey, TValue>> list) {
    std::vector<std::pair<TKey, TValue>> data(list.begin(), list.end());
    std::sort(data.begin(), data.end());
    return ImmutableMap(std::move(data));
  }

  template <typename TKey2, typename = typename std::enable_if<
                                is_comparable<TKey, TKey2>::value>::type>
  const_iterator find(const TKey2& key) const {
    auto cmp = [](const std::pair<TKey, TValue>& candidate,
                  const TKey2& target) -> bool {
      return candidate.first < target;
    };

    const_iterator end_iter = end();
    auto iter = std::lower_bound(data_.begin(), end_iter, key, cmp);
    if (iter == end_iter || iter->first == key) {
      return iter;
    }
    return end_iter;
  }

  const_iterator begin() const { return data_.begin(); }

  const_iterator end() const { return data_.end(); }

 private:
  DISALLOW_COPY_AND_ASSIGN(ImmutableMap);

  explicit ImmutableMap(std::vector<std::pair<TKey, TValue>> data)
      : data_(std::move(data)) {}

  std::vector<std::pair<TKey, TValue>> data_;
};

}  // namespace aapt

#endif /* AAPT_UTIL_IMMUTABLEMAP_H */
