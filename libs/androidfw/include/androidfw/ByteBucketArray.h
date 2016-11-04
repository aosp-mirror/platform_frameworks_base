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

#ifndef __BYTE_BUCKET_ARRAY_H
#define __BYTE_BUCKET_ARRAY_H

#include <utils/Log.h>
#include <stdint.h>
#include <string.h>

namespace android {

/**
 * Stores a sparsely populated array. Has a fixed size of 256
 * (number of entries that a byte can represent).
 */
template<typename T>
class ByteBucketArray {
public:
    ByteBucketArray() : mDefault() {
        memset(mBuckets, 0, sizeof(mBuckets));
    }

    ~ByteBucketArray() {
        for (size_t i = 0; i < NUM_BUCKETS; i++) {
            if (mBuckets[i] != NULL) {
                delete [] mBuckets[i];
            }
        }
        memset(mBuckets, 0, sizeof(mBuckets));
    }

    inline size_t size() const {
        return NUM_BUCKETS * BUCKET_SIZE;
    }

    inline const T& get(size_t index) const {
        return (*this)[index];
    }

    const T& operator[](size_t index) const {
        if (index >= size()) {
            return mDefault;
        }

        uint8_t bucketIndex = static_cast<uint8_t>(index) >> 4;
        T* bucket = mBuckets[bucketIndex];
        if (bucket == NULL) {
            return mDefault;
        }
        return bucket[0x0f & static_cast<uint8_t>(index)];
    }

    T& editItemAt(size_t index) {
        ALOG_ASSERT(index < size(), "ByteBucketArray.getOrCreate(index=%u) with size=%u",
                (uint32_t) index, (uint32_t) size());

        uint8_t bucketIndex = static_cast<uint8_t>(index) >> 4;
        T* bucket = mBuckets[bucketIndex];
        if (bucket == NULL) {
            bucket = mBuckets[bucketIndex] = new T[BUCKET_SIZE]();
        }
        return bucket[0x0f & static_cast<uint8_t>(index)];
    }

    bool set(size_t index, const T& value) {
        if (index >= size()) {
            return false;
        }

        editItemAt(index) = value;
        return true;
    }

private:
    enum { NUM_BUCKETS = 16, BUCKET_SIZE = 16 };

    T*  mBuckets[NUM_BUCKETS];
    T   mDefault;
};

} // namespace android

#endif // __BYTE_BUCKET_ARRAY_H
