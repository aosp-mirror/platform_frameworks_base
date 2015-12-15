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

#include "util/TypeTraits.h"

#include <utility>
#include <vector>

namespace aapt {

template <typename TKey, typename TValue>
class ImmutableMap {
    static_assert(is_comparable<TKey, TKey>::value, "key is not comparable");

private:
    std::vector<std::pair<TKey, TValue>> mData;

    explicit ImmutableMap(std::vector<std::pair<TKey, TValue>> data) : mData(std::move(data)) {
    }

public:
    using const_iterator = typename decltype(mData)::const_iterator;

    ImmutableMap(ImmutableMap&&) = default;
    ImmutableMap& operator=(ImmutableMap&&) = default;

    ImmutableMap(const ImmutableMap&) = delete;
    ImmutableMap& operator=(const ImmutableMap&) = delete;

    static ImmutableMap<TKey, TValue> createPreSorted(
            std::initializer_list<std::pair<TKey, TValue>> list) {
        return ImmutableMap(std::vector<std::pair<TKey, TValue>>(list.begin(), list.end()));
    }

    static ImmutableMap<TKey, TValue> createAndSort(
            std::initializer_list<std::pair<TKey, TValue>> list) {
        std::vector<std::pair<TKey, TValue>> data(list.begin(), list.end());
        std::sort(data.begin(), data.end());
        return ImmutableMap(std::move(data));
    }

    template <typename TKey2,
              typename = typename std::enable_if<is_comparable<TKey, TKey2>::value>::type>
    const_iterator find(const TKey2& key) const {
        auto cmp = [](const std::pair<TKey, TValue>& candidate, const TKey2& target) -> bool {
            return candidate.first < target;
        };

        const_iterator endIter = end();
        auto iter = std::lower_bound(mData.begin(), endIter, key, cmp);
        if (iter == endIter || iter->first == key) {
            return iter;
        }
        return endIter;
    }

    const_iterator begin() const {
        return mData.begin();
    }

    const_iterator end() const {
        return mData.end();
    }
};

} // namespace aapt

#endif /* AAPT_UTIL_IMMUTABLEMAP_H */
