// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "anomaly/AlarmMonitor.h"

#include <gtest/gtest.h>

using namespace android::os::statsd;
using std::shared_ptr;

#ifdef __ANDROID__
TEST(AlarmMonitor, popSoonerThan) {
    std::string emptyMetricId;
    std::string emptyDimensionId;
    unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> set;
    AlarmMonitor am(2,
                    [](const shared_ptr<IStatsCompanionService>&, int64_t){},
                    [](const shared_ptr<IStatsCompanionService>&){});

    set = am.popSoonerThan(5);
    EXPECT_TRUE(set.empty());

    sp<const InternalAlarm> a = new InternalAlarm{10};
    sp<const InternalAlarm> b = new InternalAlarm{20};
    sp<const InternalAlarm> c = new InternalAlarm{20};
    sp<const InternalAlarm> d = new InternalAlarm{30};
    sp<const InternalAlarm> e = new InternalAlarm{40};
    sp<const InternalAlarm> f = new InternalAlarm{50};

    am.add(a);
    am.add(b);
    am.add(c);
    am.add(d);
    am.add(e);
    am.add(f);

    set = am.popSoonerThan(5);
    EXPECT_TRUE(set.empty());

    set = am.popSoonerThan(30);
    ASSERT_EQ(4u, set.size());
    EXPECT_EQ(1u, set.count(a));
    EXPECT_EQ(1u, set.count(b));
    EXPECT_EQ(1u, set.count(c));
    EXPECT_EQ(1u, set.count(d));

    set = am.popSoonerThan(60);
    ASSERT_EQ(2u, set.size());
    EXPECT_EQ(1u, set.count(e));
    EXPECT_EQ(1u, set.count(f));

    set = am.popSoonerThan(80);
    ASSERT_EQ(0u, set.size());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
