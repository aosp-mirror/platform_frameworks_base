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

#define LOG_TAG "statsd_test"

#include "../src/AnomalyMonitor.h"

#include <gtest/gtest.h>

using namespace android::os::statsd;

#ifdef __ANDROID__
TEST(AnomalyMonitor, popSoonerThan) {
    unordered_set<sp<const AnomalyAlarm>, SpHash<AnomalyAlarm>> set;
    AnomalyMonitor am(2);

    set = am.popSoonerThan(5);
    EXPECT_TRUE(set.empty());

    sp<const AnomalyAlarm> a = new AnomalyAlarm{10};
    sp<const AnomalyAlarm> b = new AnomalyAlarm{20};
    sp<const AnomalyAlarm> c = new AnomalyAlarm{20};
    sp<const AnomalyAlarm> d = new AnomalyAlarm{30};
    sp<const AnomalyAlarm> e = new AnomalyAlarm{40};
    sp<const AnomalyAlarm> f = new AnomalyAlarm{50};

    am.add(a);
    am.add(b);
    am.add(c);
    am.add(d);
    am.add(e);
    am.add(f);

    set = am.popSoonerThan(5);
    EXPECT_TRUE(set.empty());

    set = am.popSoonerThan(30);
    EXPECT_EQ(4u, set.size());
    EXPECT_EQ(1u, set.count(a));
    EXPECT_EQ(1u, set.count(b));
    EXPECT_EQ(1u, set.count(c));
    EXPECT_EQ(1u, set.count(d));

    set = am.popSoonerThan(60);
    EXPECT_EQ(2u, set.size());
    EXPECT_EQ(1u, set.count(e));
    EXPECT_EQ(1u, set.count(f));

    set = am.popSoonerThan(80);
    EXPECT_EQ(0u, set.size());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
