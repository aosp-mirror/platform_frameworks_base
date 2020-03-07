/*
 * Copyright 2015, The Android Open Source Project
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

#ifndef ANDROID_FAT_VECTOR_H
#define ANDROID_FAT_VECTOR_H

#include "utils/Macros.h"

#include <stddef.h>
#include <stdlib.h>
#include <utils/Log.h>
#include <type_traits>

#include <vector>

namespace android {
namespace uirenderer {

template <typename T, size_t SIZE>
class InlineStdAllocator {
public:
    struct Allocation {
        PREVENT_COPY_AND_ASSIGN(Allocation);

    public:
        Allocation(){};
        // char array instead of T array, so memory is uninitialized, with no destructors run
        char array[sizeof(T) * SIZE];
        bool inUse = false;
    };

    typedef T value_type;  // needed to implement std::allocator
    typedef T* pointer;    // needed to implement std::allocator

    explicit InlineStdAllocator(Allocation& allocation) : mAllocation(allocation) {}
    InlineStdAllocator(const InlineStdAllocator& other) : mAllocation(other.mAllocation) {}
    ~InlineStdAllocator() {}

    T* allocate(size_t num, const void* = 0) {
        if (!mAllocation.inUse && num <= SIZE) {
            mAllocation.inUse = true;
            return (T*)mAllocation.array;
        } else {
            return (T*)malloc(num * sizeof(T));
        }
    }

    void deallocate(pointer p, size_t num) {
        if (p == (T*)mAllocation.array) {
            mAllocation.inUse = false;
        } else {
            // 'free' instead of delete here - destruction handled separately
            free(p);
        }
    }
    Allocation& mAllocation;
};

/**
 * std::vector with SIZE elements preallocated into an internal buffer.
 *
 * Useful for avoiding the cost of malloc in cases where only SIZE or
 * fewer elements are needed in the common case.
 */
template <typename T, size_t SIZE>
class FatVector : public std::vector<T, InlineStdAllocator<T, SIZE>> {
public:
    FatVector()
            : std::vector<T, InlineStdAllocator<T, SIZE>>(
                      InlineStdAllocator<T, SIZE>(mAllocation)) {
        this->reserve(SIZE);
    }

    explicit FatVector(size_t capacity) : FatVector() { this->resize(capacity); }

private:
    typename InlineStdAllocator<T, SIZE>::Allocation mAllocation;
};

}  // namespace uirenderer
}  // namespace android

#endif  // ANDROID_FAT_VECTOR_H
