/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "src/anomaly/indexed_priority_queue.h"

#include <gtest/gtest.h>

using namespace android::os::statsd;

/** struct for template in indexed_priority_queue */
struct AATest : public RefBase {
    AATest(uint32_t val, std::string a, std::string b) : val(val), a(a), b(b) {
    }

    const int val;
    const std::string a;
    const std::string b;

    struct Smaller {
        bool operator()(const sp<const AATest> a, const sp<const AATest> b) const {
            return (a->val < b->val);
        }
    };
};

#ifdef __ANDROID__
TEST(indexed_priority_queue, empty_and_size) {
    std::string emptyMetricId;
    std::string emptyDimensionId;
    indexed_priority_queue<AATest, AATest::Smaller> ipq;
    sp<const AATest> aa4 = new AATest{4, emptyMetricId, emptyDimensionId};
    sp<const AATest> aa8 = new AATest{8, emptyMetricId, emptyDimensionId};

    EXPECT_EQ(0u, ipq.size());
    EXPECT_TRUE(ipq.empty());

    ipq.push(aa4);
    EXPECT_EQ(1u, ipq.size());
    EXPECT_FALSE(ipq.empty());

    ipq.push(aa8);
    EXPECT_EQ(2u, ipq.size());
    EXPECT_FALSE(ipq.empty());

    ipq.remove(aa4);
    EXPECT_EQ(1u, ipq.size());
    EXPECT_FALSE(ipq.empty());

    ipq.remove(aa8);
    EXPECT_EQ(0u, ipq.size());
    EXPECT_TRUE(ipq.empty());
}

TEST(indexed_priority_queue, top) {
    std::string emptyMetricId;
    std::string emptyDimensionId;
    indexed_priority_queue<AATest, AATest::Smaller> ipq;
    sp<const AATest> aa2 = new AATest{2, emptyMetricId, emptyDimensionId};
    sp<const AATest> aa4 = new AATest{4, emptyMetricId, emptyDimensionId};
    sp<const AATest> aa8 = new AATest{8, emptyMetricId, emptyDimensionId};
    sp<const AATest> aa12 = new AATest{12, emptyMetricId, emptyDimensionId};
    sp<const AATest> aa16 = new AATest{16, emptyMetricId, emptyDimensionId};
    sp<const AATest> aa20 = new AATest{20, emptyMetricId, emptyDimensionId};

    EXPECT_EQ(ipq.top(), nullptr);

    // add 8, 4, 12
    ipq.push(aa8);
    EXPECT_EQ(ipq.top(), aa8);

    ipq.push(aa12);
    EXPECT_EQ(ipq.top(), aa8);

    ipq.push(aa4);
    EXPECT_EQ(ipq.top(), aa4);

    // remove 12, 4
    ipq.remove(aa12);
    EXPECT_EQ(ipq.top(), aa4);

    ipq.remove(aa4);
    EXPECT_EQ(ipq.top(), aa8);

    // add 16, 2, 20
    ipq.push(aa16);
    EXPECT_EQ(ipq.top(), aa8);

    ipq.push(aa2);
    EXPECT_EQ(ipq.top(), aa2);

    ipq.push(aa20);
    EXPECT_EQ(ipq.top(), aa2);

    // remove 2, 20, 16, 8
    ipq.remove(aa2);
    EXPECT_EQ(ipq.top(), aa8);

    ipq.remove(aa20);
    EXPECT_EQ(ipq.top(), aa8);

    ipq.remove(aa16);
    EXPECT_EQ(ipq.top(), aa8);

    ipq.remove(aa8);
    EXPECT_EQ(ipq.top(), nullptr);
}

TEST(indexed_priority_queue, push_same_aa) {
    std::string emptyMetricId;
    std::string emptyDimensionId;
    indexed_priority_queue<AATest, AATest::Smaller> ipq;
    sp<const AATest> aa4_a = new AATest{4, emptyMetricId, emptyDimensionId};
    sp<const AATest> aa4_b = new AATest{4, emptyMetricId, emptyDimensionId};

    ipq.push(aa4_a);
    EXPECT_EQ(1u, ipq.size());
    EXPECT_TRUE(ipq.contains(aa4_a));
    EXPECT_FALSE(ipq.contains(aa4_b));

    ipq.push(aa4_a);
    EXPECT_EQ(1u, ipq.size());
    EXPECT_TRUE(ipq.contains(aa4_a));
    EXPECT_FALSE(ipq.contains(aa4_b));

    ipq.push(aa4_b);
    EXPECT_EQ(2u, ipq.size());
    EXPECT_TRUE(ipq.contains(aa4_a));
    EXPECT_TRUE(ipq.contains(aa4_b));
}

TEST(indexed_priority_queue, remove_nonexistant) {
    std::string emptyMetricId;
    std::string emptyDimensionId;
    indexed_priority_queue<AATest, AATest::Smaller> ipq;
    sp<const AATest> aa4 = new AATest{4, emptyMetricId, emptyDimensionId};
    sp<const AATest> aa5 = new AATest{5, emptyMetricId, emptyDimensionId};

    ipq.push(aa4);
    ipq.remove(aa5);
    EXPECT_EQ(1u, ipq.size());
    EXPECT_TRUE(ipq.contains(aa4));
    EXPECT_FALSE(ipq.contains(aa5));
}

TEST(indexed_priority_queue, remove_same_aa) {
    indexed_priority_queue<AATest, AATest::Smaller> ipq;
    std::string emptyMetricId;
    std::string emptyDimensionId;
    sp<const AATest> aa4_a = new AATest{4, emptyMetricId, emptyDimensionId};
    sp<const AATest> aa4_b = new AATest{4, emptyMetricId, emptyDimensionId};

    ipq.push(aa4_a);
    ipq.push(aa4_b);
    EXPECT_EQ(2u, ipq.size());
    EXPECT_TRUE(ipq.contains(aa4_a));
    EXPECT_TRUE(ipq.contains(aa4_b));

    ipq.remove(aa4_b);
    EXPECT_EQ(1u, ipq.size());
    EXPECT_TRUE(ipq.contains(aa4_a));
    EXPECT_FALSE(ipq.contains(aa4_b));

    ipq.remove(aa4_a);
    EXPECT_EQ(0u, ipq.size());
    EXPECT_FALSE(ipq.contains(aa4_a));
    EXPECT_FALSE(ipq.contains(aa4_b));
}

TEST(indexed_priority_queue, nulls) {
    indexed_priority_queue<AATest, AATest::Smaller> ipq;

    EXPECT_TRUE(ipq.empty());
    EXPECT_FALSE(ipq.contains(nullptr));

    ipq.push(nullptr);
    EXPECT_TRUE(ipq.empty());
    EXPECT_FALSE(ipq.contains(nullptr));

    ipq.remove(nullptr);
    EXPECT_TRUE(ipq.empty());
    EXPECT_FALSE(ipq.contains(nullptr));
}

TEST(indexed_priority_queue, pop) {
    indexed_priority_queue<AATest, AATest::Smaller> ipq;
    std::string emptyMetricId;
    std::string emptyDimensionId;
    sp<const AATest> a = new AATest{1, emptyMetricId, emptyDimensionId};
    sp<const AATest> b = new AATest{2, emptyMetricId, emptyDimensionId};
    sp<const AATest> c = new AATest{3, emptyMetricId, emptyDimensionId};

    ipq.push(c);
    ipq.push(b);
    ipq.push(a);
    EXPECT_EQ(3u, ipq.size());

    ipq.pop();
    EXPECT_EQ(2u, ipq.size());
    EXPECT_FALSE(ipq.contains(a));
    EXPECT_TRUE(ipq.contains(b));
    EXPECT_TRUE(ipq.contains(c));

    ipq.pop();
    EXPECT_EQ(1u, ipq.size());
    EXPECT_FALSE(ipq.contains(a));
    EXPECT_FALSE(ipq.contains(b));
    EXPECT_TRUE(ipq.contains(c));

    ipq.pop();
    EXPECT_EQ(0u, ipq.size());
    EXPECT_FALSE(ipq.contains(a));
    EXPECT_FALSE(ipq.contains(b));
    EXPECT_FALSE(ipq.contains(c));
    EXPECT_TRUE(ipq.empty());

    ipq.pop(); // pop an empty queue
    EXPECT_TRUE(ipq.empty());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
