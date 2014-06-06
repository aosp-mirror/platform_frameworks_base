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

#include <androidfw/ByteBucketArray.h>

#include <gtest/gtest.h>

using android::ByteBucketArray;

TEST(ByteBucketArrayTest, TestSparseInsertion) {
    ByteBucketArray<int> bba;
    ASSERT_TRUE(bba.set(0, 1));
    ASSERT_TRUE(bba.set(10, 2));
    ASSERT_TRUE(bba.set(26, 3));
    ASSERT_TRUE(bba.set(129, 4));
    ASSERT_TRUE(bba.set(234, 5));

    for (size_t i = 0; i < bba.size(); i++) {
        switch (i) {
            case 0: EXPECT_EQ(1, bba[i]); break;
            case 10: EXPECT_EQ(2, bba[i]); break;
            case 26: EXPECT_EQ(3, bba[i]); break;
            case 129: EXPECT_EQ(4, bba[i]); break;
            case 234: EXPECT_EQ(5, bba[i]); break;
            default: EXPECT_EQ(0, bba[i]); break;
        }
    }
}
