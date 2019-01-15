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

#include <cstdint>
#include <cstring>

#include "android-base/logging.h"

namespace android {

/**
 * Stores a sparsely populated array. Has a fixed size of 256
 * (number of entries that a byte can represent).
 */
template <typename T>
class ByteBucketArray {
 public:
  ByteBucketArray() : default_() { memset(buckets_, 0, sizeof(buckets_)); }

  ~ByteBucketArray() {
    for (size_t i = 0; i < kNumBuckets; i++) {
      if (buckets_[i] != NULL) {
        delete[] buckets_[i];
      }
    }
    memset(buckets_, 0, sizeof(buckets_));
  }

  inline size_t size() const { return kNumBuckets * kBucketSize; }

  inline const T& get(size_t index) const { return (*this)[index]; }

  const T& operator[](size_t index) const {
    if (index >= size()) {
      return default_;
    }

    uint8_t bucket_index = static_cast<uint8_t>(index) >> 4;
    T* bucket = buckets_[bucket_index];
    if (bucket == NULL) {
      return default_;
    }
    return bucket[0x0f & static_cast<uint8_t>(index)];
  }

  T& editItemAt(size_t index) {
    CHECK(index < size()) << "ByteBucketArray.editItemAt(index=" << index
                          << ") with size=" << size();

    uint8_t bucket_index = static_cast<uint8_t>(index) >> 4;
    T* bucket = buckets_[bucket_index];
    if (bucket == NULL) {
      bucket = buckets_[bucket_index] = new T[kBucketSize]();
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
  enum { kNumBuckets = 16, kBucketSize = 16 };

  T* buckets_[kNumBuckets];
  T default_;
};

}  // namespace android

#endif  // __BYTE_BUCKET_ARRAY_H
