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

#include <tests/common/TestUtils.h>

using namespace android;
using namespace android::uirenderer;

struct SimplePair {
    int one = 1;
    int two = 2;
};

TEST(LinearAllocator, create) {
    LinearAllocator la;
    EXPECT_EQ(0u, la.usedSize());
    la.alloc<char>(64);
    // There's some internal tracking as well as padding
    // so the usedSize isn't strictly defined
    EXPECT_LE(64u, la.usedSize());
    EXPECT_GT(80u, la.usedSize());
    auto pair = la.create<SimplePair>();
    EXPECT_LE(64u + sizeof(SimplePair), la.usedSize());
    EXPECT_GT(80u + sizeof(SimplePair), la.usedSize());
    EXPECT_EQ(1, pair->one);
    EXPECT_EQ(2, pair->two);
}

TEST(LinearAllocator, dtor) {
    int destroyed[10] = { 0 };
    {
        LinearAllocator la;
        for (int i = 0; i < 5; i++) {
            la.create<TestUtils::SignalingDtor>()->setSignal(destroyed + i);
            la.create<SimplePair>();
        }
        la.alloc<char>(100);
        for (int i = 0; i < 5; i++) {
            la.create<TestUtils::SignalingDtor>(destroyed + 5 + i);
            la.create_trivial<SimplePair>();
        }
        la.alloc<char>(100);
        for (int i = 0; i < 10; i++) {
            EXPECT_EQ(0, destroyed[i]);
        }
    }
    for (int i = 0; i < 10; i++) {
        EXPECT_EQ(1, destroyed[i]);
    }
}

TEST(LinearAllocator, rewind) {
    int destroyed = 0;
    {
        LinearAllocator la;
        auto addr = la.alloc<char>(100);
        EXPECT_LE(100u, la.usedSize());
        la.rewindIfLastAlloc(addr, 100);
        EXPECT_GT(16u, la.usedSize());
        size_t emptySize = la.usedSize();
        auto sigdtor = la.create<TestUtils::SignalingDtor>();
        sigdtor->setSignal(&destroyed);
        EXPECT_EQ(0, destroyed);
        EXPECT_LE(emptySize, la.usedSize());
        la.rewindIfLastAlloc(sigdtor);
        EXPECT_EQ(1, destroyed);
        EXPECT_EQ(emptySize, la.usedSize());
    }
    // Checking for a double-destroy case
    EXPECT_EQ(1, destroyed);
}

TEST(LinearStdAllocator, simpleAllocate) {
    LinearAllocator la;
    LinearStdAllocator<void*> stdAllocator(la);

    std::vector<char, LinearStdAllocator<char> > v(stdAllocator);
    v.push_back(0);
    char* initialLocation = &v[0];
    v.push_back(10);
    v.push_back(20);
    v.push_back(30);

    // expect to have allocated (since no space reserved), so [0] will have moved to
    // slightly further down in the same LinearAllocator page
    EXPECT_LT(initialLocation, &v[0]);
    EXPECT_GT(initialLocation + 20, &v[0]);

    // expect to have allocated again inserting 4 more entries
    char* lastLocation = &v[0];
    v.push_back(40);
    v.push_back(50);
    v.push_back(60);
    v.push_back(70);

    EXPECT_LT(lastLocation, &v[0]);
    EXPECT_GT(lastLocation + 20, &v[0]);

}

TEST(LsaVector, dtorCheck) {
    LinearAllocator allocator;
    LinearStdAllocator<void*> stdAllocator(allocator);

    for (int size : {1, 2, 3, 500}) {
        int destroyed = 0;
        {
            LsaVector<std::unique_ptr<TestUtils::SignalingDtor> > vector(stdAllocator);
            for (int i = 0; i < size; i++) {
                vector.emplace_back(new TestUtils::SignalingDtor(&destroyed));
            }
            EXPECT_EQ(0, destroyed);
            EXPECT_EQ(size, (int) vector.size());
        }
        EXPECT_EQ(size, destroyed);
    }
}
