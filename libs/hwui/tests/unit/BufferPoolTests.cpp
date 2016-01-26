/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <gtest/gtest.h>

#include <BufferPool.h>
#include <utils/StrongPointer.h>

namespace android {
namespace uirenderer {

TEST(BufferPool, acquireThenRelease) {
    static const int numRuns = 5;

    // 10 buffers of size 1
    static const size_t bufferSize = 1;
    static const size_t bufferCount = 10;
    sp<BufferPool> pool = new BufferPool(bufferSize, bufferCount);

    for (int run = 0; run < numRuns; run++) {
        BufferPool::Buffer* acquiredBuffers[bufferCount];
        for (size_t i = 0; i < bufferCount; i++) {
            ASSERT_EQ(bufferCount - i, pool->getAvailableBufferCount());
            acquiredBuffers[i] = pool->acquire();
            ASSERT_NE(nullptr, acquiredBuffers[i]);
            ASSERT_TRUE(acquiredBuffers[i]->isUniqueRef());
        }

        for (size_t i = 0; i < bufferCount; i++) {
            ASSERT_EQ(i, pool->getAvailableBufferCount());
            acquiredBuffers[i]->release();
            acquiredBuffers[i] = nullptr;
        }

        ASSERT_EQ(bufferCount, pool->getAvailableBufferCount());
    }
}

TEST(BufferPool, acquireReleaseInterleaved) {
    static const int numRuns = 5;

    // 10 buffers of size 1
    static const size_t bufferSize = 1;
    static const size_t bufferCount = 10;

    sp<BufferPool> pool = new BufferPool(bufferSize, bufferCount);

    for (int run = 0; run < numRuns; run++) {
        BufferPool::Buffer* acquiredBuffers[bufferCount];

        // acquire all
        for (size_t i = 0; i < bufferCount; i++) {
            ASSERT_EQ(bufferCount - i, pool->getAvailableBufferCount());
            acquiredBuffers[i] = pool->acquire();
            ASSERT_NE(nullptr, acquiredBuffers[i]);
        }

        // release half
        for (size_t i = 0; i < bufferCount / 2; i++) {
            ASSERT_EQ(i, pool->getAvailableBufferCount());
            acquiredBuffers[i]->release();
            acquiredBuffers[i] = nullptr;
        }

        const size_t expectedRemaining = bufferCount / 2;
        ASSERT_EQ(expectedRemaining, pool->getAvailableBufferCount());

        // acquire half
        for (size_t i = 0; i < bufferCount / 2; i++) {
            ASSERT_EQ(expectedRemaining - i, pool->getAvailableBufferCount());
            acquiredBuffers[i] = pool->acquire();
        }

        // acquire one more, should fail
        ASSERT_EQ(nullptr, pool->acquire());

        // release all
        for (size_t i = 0; i < bufferCount; i++) {
            ASSERT_EQ(i, pool->getAvailableBufferCount());
            acquiredBuffers[i]->release();
            acquiredBuffers[i] = nullptr;
        }

        ASSERT_EQ(bufferCount, pool->getAvailableBufferCount());
    }
}

};
};
