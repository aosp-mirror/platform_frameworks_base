/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef H_AAPT_UTIL
#define H_AAPT_UTIL

#include <utils/KeyedVector.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>
#include <utils/Vector.h>

namespace AaptUtil {

android::Vector<android::String8> split(const android::String8& str, const char sep);
android::Vector<android::String8> splitAndLowerCase(const android::String8& str, const char sep);

template <typename KEY, typename VALUE>
void appendValue(android::KeyedVector<KEY, android::Vector<VALUE> >& keyedVector,
        const KEY& key, const VALUE& value);

template <typename KEY, typename VALUE>
void appendValue(android::KeyedVector<KEY, android::SortedVector<VALUE> >& keyedVector,
        const KEY& key, const VALUE& value);

//
// Implementations
//

template <typename KEY, typename VALUE>
void appendValue(android::KeyedVector<KEY, android::Vector<VALUE> >& keyedVector,
        const KEY& key, const VALUE& value) {
    ssize_t idx = keyedVector.indexOfKey(key);
    if (idx < 0) {
        idx = keyedVector.add(key, android::Vector<VALUE>());
    }
    keyedVector.editValueAt(idx).add(value);
}

template <typename KEY, typename VALUE>
void appendValue(android::KeyedVector<KEY, android::SortedVector<VALUE> >& keyedVector,
        const KEY& key, const VALUE& value) {
    ssize_t idx = keyedVector.indexOfKey(key);
    if (idx < 0) {
        idx = keyedVector.add(key, android::SortedVector<VALUE>());
    }
    keyedVector.editValueAt(idx).add(value);
}

} // namespace AaptUtil

#endif // H_AAPT_UTIL
