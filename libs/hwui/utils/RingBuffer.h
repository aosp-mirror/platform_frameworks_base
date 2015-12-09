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
#ifndef RINGBUFFER_H_
#define RINGBUFFER_H_

#include "utils/Macros.h"

#include <stddef.h>

namespace android {
namespace uirenderer {

template<class T, size_t SIZE>
class RingBuffer {
    PREVENT_COPY_AND_ASSIGN(RingBuffer);

public:
    RingBuffer() {}
    ~RingBuffer() {}

    constexpr size_t capacity() const { return SIZE; }
    size_t size() const { return mCount; }

    T& next() {
        mHead = (mHead + 1) % SIZE;
        if (mCount < SIZE) {
            mCount++;
        }
        return mBuffer[mHead];
    }

    T& front() {
        return (*this)[0];
    }

    T& back() {
        return (*this)[size() - 1];
    }

    T& operator[](size_t index) {
        return mBuffer[(mHead + index + 1) % mCount];
    }

    const T& operator[](size_t index) const {
        return mBuffer[(mHead + index + 1) % mCount];
    }

    void clear() {
        mCount = 0;
        mHead = -1;
    }

private:
    T mBuffer[SIZE];
    int mHead = -1;
    size_t mCount = 0;
};

}; // namespace uirenderer
}; // namespace android

#endif /* RINGBUFFER_H_ */
