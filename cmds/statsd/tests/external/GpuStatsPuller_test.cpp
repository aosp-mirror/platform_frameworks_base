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
#define LOG_TAG "GpuStatsPuller_test"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <graphicsenv/GpuStatsInfo.h>
#include <log/log.h>

#include "src/external/GpuStatsPuller.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

// clang-format off
static const std::string DRIVER_PACKAGE_NAME    = "TEST_DRIVER";
static const std::string DRIVER_VERSION_NAME    = "TEST_DRIVER_VERSION";
static const std::string APP_PACKAGE_NAME       = "TEST_APP";
static const int64_t TIMESTAMP_WALLCLOCK        = 111;
static const int64_t TIMESTAMP_ELAPSED          = 222;
static const int64_t DRIVER_VERSION_CODE        = 333;
static const int64_t DRIVER_BUILD_TIME          = 444;
static const int64_t GL_LOADING_COUNT           = 3;
static const int64_t GL_LOADING_FAILURE_COUNT   = 1;
static const int64_t VK_LOADING_COUNT           = 4;
static const int64_t VK_LOADING_FAILURE_COUNT   = 0;
static const int64_t GL_DRIVER_LOADING_TIME_0   = 555;
static const int64_t GL_DRIVER_LOADING_TIME_1   = 666;
static const int64_t VK_DRIVER_LOADING_TIME_0   = 777;
static const int64_t VK_DRIVER_LOADING_TIME_1   = 888;
static const int64_t VK_DRIVER_LOADING_TIME_2   = 999;
static const size_t NUMBER_OF_VALUES_GLOBAL     = 8;
static const size_t NUMBER_OF_VALUES_APP        = 4;
// clang-format on

class MockGpuStatsPuller : public GpuStatsPuller {
public:
    MockGpuStatsPuller(const int tagId, vector<std::shared_ptr<LogEvent>>* data)
        : GpuStatsPuller(tagId), mData(data){};

private:
    bool PullInternal(vector<std::shared_ptr<LogEvent>>* data) override {
        *data = *mData;
        return true;
    }

    vector<std::shared_ptr<LogEvent>>* mData;
};

class GpuStatsPuller_test : public ::testing::Test {
public:
    GpuStatsPuller_test() {
        const ::testing::TestInfo* const test_info =
                ::testing::UnitTest::GetInstance()->current_test_info();
        ALOGD("**** Setting up for %s.%s\n", test_info->test_case_name(), test_info->name());
    }

    ~GpuStatsPuller_test() {
        const ::testing::TestInfo* const test_info =
                ::testing::UnitTest::GetInstance()->current_test_info();
        ALOGD("**** Tearing down after %s.%s\n", test_info->test_case_name(), test_info->name());
    }
};

TEST_F(GpuStatsPuller_test, PullGpuStatsGlobalInfo) {
    vector<std::shared_ptr<LogEvent>> inData, outData;
    std::shared_ptr<LogEvent> event = make_shared<LogEvent>(android::util::GPU_STATS_GLOBAL_INFO,
                                                            TIMESTAMP_WALLCLOCK, TIMESTAMP_ELAPSED);
    EXPECT_TRUE(event->write(DRIVER_PACKAGE_NAME));
    EXPECT_TRUE(event->write(DRIVER_VERSION_NAME));
    EXPECT_TRUE(event->write(DRIVER_VERSION_CODE));
    EXPECT_TRUE(event->write(DRIVER_BUILD_TIME));
    EXPECT_TRUE(event->write(GL_LOADING_COUNT));
    EXPECT_TRUE(event->write(GL_LOADING_FAILURE_COUNT));
    EXPECT_TRUE(event->write(VK_LOADING_COUNT));
    EXPECT_TRUE(event->write(VK_LOADING_FAILURE_COUNT));
    event->init();
    inData.emplace_back(event);
    MockGpuStatsPuller mockPuller(android::util::GPU_STATS_GLOBAL_INFO, &inData);
    mockPuller.ForceClearCache();
    mockPuller.Pull(&outData);

    ASSERT_EQ(1, outData.size());
    EXPECT_EQ(android::util::GPU_STATS_GLOBAL_INFO, outData[0]->GetTagId());
    ASSERT_EQ(NUMBER_OF_VALUES_GLOBAL, outData[0]->size());
    EXPECT_EQ(DRIVER_PACKAGE_NAME, outData[0]->getValues()[0].mValue.str_value);
    EXPECT_EQ(DRIVER_VERSION_NAME, outData[0]->getValues()[1].mValue.str_value);
    EXPECT_EQ(DRIVER_VERSION_CODE, outData[0]->getValues()[2].mValue.long_value);
    EXPECT_EQ(DRIVER_BUILD_TIME, outData[0]->getValues()[3].mValue.long_value);
    EXPECT_EQ(GL_LOADING_COUNT, outData[0]->getValues()[4].mValue.long_value);
    EXPECT_EQ(GL_LOADING_FAILURE_COUNT, outData[0]->getValues()[5].mValue.long_value);
    EXPECT_EQ(VK_LOADING_COUNT, outData[0]->getValues()[6].mValue.long_value);
    EXPECT_EQ(VK_LOADING_FAILURE_COUNT, outData[0]->getValues()[7].mValue.long_value);
}

TEST_F(GpuStatsPuller_test, PullGpuStatsAppInfo) {
    vector<std::shared_ptr<LogEvent>> inData, outData;
    std::shared_ptr<LogEvent> event = make_shared<LogEvent>(android::util::GPU_STATS_APP_INFO,
                                                            TIMESTAMP_WALLCLOCK, TIMESTAMP_ELAPSED);
    EXPECT_TRUE(event->write(APP_PACKAGE_NAME));
    EXPECT_TRUE(event->write(DRIVER_VERSION_CODE));
    std::vector<int64_t> glDriverLoadingTime;
    glDriverLoadingTime.emplace_back(GL_DRIVER_LOADING_TIME_0);
    glDriverLoadingTime.emplace_back(GL_DRIVER_LOADING_TIME_1);
    std::vector<int64_t> vkDriverLoadingTime;
    vkDriverLoadingTime.emplace_back(VK_DRIVER_LOADING_TIME_0);
    vkDriverLoadingTime.emplace_back(VK_DRIVER_LOADING_TIME_1);
    vkDriverLoadingTime.emplace_back(VK_DRIVER_LOADING_TIME_2);
    EXPECT_TRUE(event->write(int64VectorToProtoByteString(glDriverLoadingTime)));
    EXPECT_TRUE(event->write(int64VectorToProtoByteString(vkDriverLoadingTime)));
    event->init();
    inData.emplace_back(event);
    MockGpuStatsPuller mockPuller(android::util::GPU_STATS_APP_INFO, &inData);
    mockPuller.ForceClearCache();
    mockPuller.Pull(&outData);

    ASSERT_EQ(1, outData.size());
    EXPECT_EQ(android::util::GPU_STATS_APP_INFO, outData[0]->GetTagId());
    ASSERT_EQ(NUMBER_OF_VALUES_APP, outData[0]->size());
    EXPECT_EQ(APP_PACKAGE_NAME, outData[0]->getValues()[0].mValue.str_value);
    EXPECT_EQ(DRIVER_VERSION_CODE, outData[0]->getValues()[1].mValue.long_value);
    EXPECT_EQ(int64VectorToProtoByteString(glDriverLoadingTime),
              outData[0]->getValues()[2].mValue.str_value);
    EXPECT_EQ(int64VectorToProtoByteString(vkDriverLoadingTime),
              outData[0]->getValues()[3].mValue.str_value);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
