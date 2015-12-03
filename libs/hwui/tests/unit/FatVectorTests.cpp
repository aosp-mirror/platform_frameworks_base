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
#include <utils/FatVector.h>

#include <tests/common/TestUtils.h>

using namespace android;
using namespace android::uirenderer;

template<class VectorType>
static bool allocationIsInternal(VectorType& v) {
    // allocation array (from &v[0] to &v[0] + v.capacity) is
    // located within the vector object itself
    return (char*)(&v) <= (char*)(&v[0])
            && (char*)(&v + 1) >= (char*)(&v[0] + v.capacity());
}

TEST(FatVector, baseline) {
    // Verify allocation behavior FatVector contrasts against - allocations are always external
    std::vector<int> v;
    for (int i = 0; i < 50; i++) {
        v.push_back(i);
        EXPECT_FALSE(allocationIsInternal(v));
    }
}

TEST(FatVector, simpleAllocate) {
    FatVector<int, 4> v;
    EXPECT_EQ(4u, v.capacity());

    // can insert 4 items into internal buffer
    for (int i = 0; i < 4; i++) {
        v.push_back(i);
        EXPECT_TRUE(allocationIsInternal(v));
    }

    // then will fall back to external allocation
    for (int i = 5; i < 50; i++) {
        v.push_back(i);
        EXPECT_FALSE(allocationIsInternal(v));
    }
}

TEST(FatVector, preSizeConstructor) {
    {
        FatVector<int, 4> v(32);
        EXPECT_EQ(32u, v.capacity());
        EXPECT_EQ(32u, v.size());
        EXPECT_FALSE(allocationIsInternal(v));
    }
    {
        FatVector<int, 4> v(4);
        EXPECT_EQ(4u, v.capacity());
        EXPECT_EQ(4u, v.size());
        EXPECT_TRUE(allocationIsInternal(v));
    }
    {
        FatVector<int, 4> v(2);
        EXPECT_EQ(4u, v.capacity());
        EXPECT_EQ(2u, v.size());
        EXPECT_TRUE(allocationIsInternal(v));
    }
}

TEST(FatVector, shrink) {
    FatVector<int, 10> v;
    EXPECT_TRUE(allocationIsInternal(v));

    // push into external alloc
    v.resize(11);
    EXPECT_FALSE(allocationIsInternal(v));

    // shrinking back to internal alloc succeeds
    // note that shrinking further will succeed, but is a waste
    v.resize(10);
    v.shrink_to_fit();
    EXPECT_TRUE(allocationIsInternal(v));
}

TEST(FatVector, destructorInternal) {
    int count = 0;
    {
        // push 1 into external allocation, verify destruction happens once
        FatVector<TestUtils::SignalingDtor, 0> v;
        v.emplace_back(&count);
        EXPECT_FALSE(allocationIsInternal(v));
        EXPECT_EQ(0, count) << "Destruction shouldn't have happened yet";
    }
    EXPECT_EQ(1, count) << "Destruction should happen exactly once";
}

TEST(FatVector, destructorExternal) {
    int count = 0;
    {
        // push 10 into internal allocation, verify 10 destructors called
        FatVector<TestUtils::SignalingDtor, 10> v;
        for (int i = 0; i < 10; i++) {
            v.emplace_back(&count);
            EXPECT_TRUE(allocationIsInternal(v));
        }
        EXPECT_EQ(0, count) << "Destruction shouldn't have happened yet";
    }
    EXPECT_EQ(10, count) << "Destruction should happen exactly once";
}
