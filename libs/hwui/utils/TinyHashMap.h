/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_TINYHASHMAP_H
#define ANDROID_HWUI_TINYHASHMAP_H

#include <utils/BasicHashtable.h>

namespace android {
namespace uirenderer {

/**
 * A very simple hash map that doesn't allow duplicate keys, overwriting the older entry.
 *
 * Currently, expects simple keys that are handled by hash_t()
 */
template <typename TKey, typename TValue>
class TinyHashMap {
public:
    typedef key_value_pair_t<TKey, TValue> TEntry;

    /**
     * Puts an entry in the hash, removing any existing entry with the same key
     */
    void put(TKey key, TValue value) {
        hash_t hash = hash_t(key);

        ssize_t index = mTable.find(-1, hash, key);
        if (index != -1) {
            mTable.removeAt(index);
        }

        TEntry initEntry(key, value);
        mTable.add(hash, initEntry);
    }

    /**
     * Return true if key is in the map, in which case stores the value in the output ref
     */
    bool get(TKey key, TValue& outValue) {
        hash_t hash = hash_t(key);
        ssize_t index = mTable.find(-1, hash, key);
        if (index == -1) {
            return false;
        }
        outValue = mTable.entryAt(index).value;
        return true;
    }

    void clear() { mTable.clear(); }

private:
    BasicHashtable<TKey, TEntry> mTable;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_TINYHASHMAP_H
