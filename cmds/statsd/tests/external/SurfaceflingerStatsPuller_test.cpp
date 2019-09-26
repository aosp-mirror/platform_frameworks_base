/*
 * Copyright 2019 The Android Open Source Project
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

#undef LOG_TAG
#define LOG_TAG "SurfaceflingerStatsPuller_test"

#include "src/external/SurfaceflingerStatsPuller.h"

#include <gtest/gtest.h>
#include <log/log.h>

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

class TestableSurfaceflingerStatsPuller : public SurfaceflingerStatsPuller {
public:
    TestableSurfaceflingerStatsPuller(const int tagId) : SurfaceflingerStatsPuller(tagId){};

    void injectStats(const StatsProvider& statsProvider) {
        mStatsProvider = statsProvider;
    }
};

class SurfaceflingerStatsPullerTest : public ::testing::Test {
public:
    SurfaceflingerStatsPullerTest() {
        const ::testing::TestInfo* const test_info =
                ::testing::UnitTest::GetInstance()->current_test_info();
        ALOGD("**** Setting up for %s.%s\n", test_info->test_case_name(), test_info->name());
    }

    ~SurfaceflingerStatsPullerTest() {
        const ::testing::TestInfo* const test_info =
                ::testing::UnitTest::GetInstance()->current_test_info();
        ALOGD("**** Tearing down after %s.%s\n", test_info->test_case_name(), test_info->name());
    }
};

TEST_F(SurfaceflingerStatsPullerTest, pullGlobalStats) {
    surfaceflinger::SFTimeStatsGlobalProto proto;
    proto.set_total_frames(1);
    proto.set_missed_frames(2);
    proto.set_client_composition_frames(2);
    proto.set_display_on_time(4);

    auto bucketOne = proto.add_present_to_present();
    bucketOne->set_time_millis(2);
    bucketOne->set_frame_count(4);
    auto bucketTwo = proto.add_present_to_present();
    bucketTwo->set_time_millis(4);
    bucketTwo->set_frame_count(1);
    auto bucketThree = proto.add_present_to_present();
    bucketThree->set_time_millis(1000);
    bucketThree->set_frame_count(1);
    static constexpr int64_t expectedAnimationMillis = 12;
    TestableSurfaceflingerStatsPuller puller(android::util::SURFACEFLINGER_STATS_GLOBAL_INFO);

    puller.injectStats([&] {
        return proto.SerializeAsString();
    });
    puller.ForceClearCache();
    vector<std::shared_ptr<LogEvent>> outData;
    puller.Pull(&outData);

    ASSERT_EQ(1, outData.size());
    EXPECT_EQ(android::util::SURFACEFLINGER_STATS_GLOBAL_INFO, outData[0]->GetTagId());
    EXPECT_EQ(proto.total_frames(), outData[0]->getValues()[0].mValue.long_value);
    EXPECT_EQ(proto.missed_frames(), outData[0]->getValues()[1].mValue.long_value);
    EXPECT_EQ(proto.client_composition_frames(), outData[0]->getValues()[2].mValue.long_value);
    EXPECT_EQ(proto.display_on_time(), outData[0]->getValues()[3].mValue.long_value);
    EXPECT_EQ(expectedAnimationMillis, outData[0]->getValues()[4].mValue.long_value);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
