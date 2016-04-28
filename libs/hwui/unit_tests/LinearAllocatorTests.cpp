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

#include <gtest/gtest.h>
#include <utils/LinearAllocator.h>

using namespace android;
using namespace android::uirenderer;

struct SimplePair {
    int one = 1;
    int two = 2;
};

class SignalingDtor {
public:
    SignalingDtor() {
        mDestroyed = nullptr;
    }
    explicit SignalingDtor(bool* destroyedSignal) {
        mDestroyed = destroyedSignal;
        *mDestroyed = false;
    }
    virtual ~SignalingDtor() {
        if (mDestroyed) {
            *mDestroyed = true;
        }
    }
    void setSignal(bool* destroyedSignal) {
        mDestroyed = destroyedSignal;
    }
private:
    bool* mDestroyed;
};

TEST(LinearAllocator, alloc) {
    LinearAllocator la;
    EXPECT_EQ(0u, la.usedSize());
    la.alloc(64);
    // There's some internal tracking as well as padding
    // so the usedSize isn't strictly defined
    EXPECT_LE(64u, la.usedSize());
    EXPECT_GT(80u, la.usedSize());
    auto pair = la.alloc<SimplePair>();
    EXPECT_LE(64u + sizeof(SimplePair), la.usedSize());
    EXPECT_GT(80u + sizeof(SimplePair), la.usedSize());
    EXPECT_EQ(1, pair->one);
    EXPECT_EQ(2, pair->two);
}

TEST(LinearAllocator, dtor) {
    bool destroyed[10];
    {
        LinearAllocator la;
        for (int i = 0; i < 5; i++) {
            la.alloc<SignalingDtor>()->setSignal(destroyed + i);
            la.alloc<SimplePair>();
        }
        la.alloc(100);
        for (int i = 0; i < 5; i++) {
            auto sd = new (la) SignalingDtor(destroyed + 5 + i);
            la.autoDestroy(sd);
            new (la) SimplePair();
        }
        la.alloc(100);
        for (int i = 0; i < 10; i++) {
            EXPECT_FALSE(destroyed[i]);
        }
    }
    for (int i = 0; i < 10; i++) {
        EXPECT_TRUE(destroyed[i]);
    }
}

TEST(LinearAllocator, rewind) {
    bool destroyed;
    {
        LinearAllocator la;
        auto addr = la.alloc(100);
        EXPECT_LE(100u, la.usedSize());
        la.rewindIfLastAlloc(addr, 100);
        EXPECT_GT(16u, la.usedSize());
        size_t emptySize = la.usedSize();
        auto sigdtor = la.alloc<SignalingDtor>();
        sigdtor->setSignal(&destroyed);
        EXPECT_FALSE(destroyed);
        EXPECT_LE(emptySize, la.usedSize());
        la.rewindIfLastAlloc(sigdtor);
        EXPECT_TRUE(destroyed);
        EXPECT_EQ(emptySize, la.usedSize());
        destroyed = false;
    }
    // Checking for a double-destroy case
    EXPECT_EQ(destroyed, false);
}
