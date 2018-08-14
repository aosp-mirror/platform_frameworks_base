/*
 * Copyright 2015, The Android Open Source Project
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
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

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_FAT_VECTOR_H
