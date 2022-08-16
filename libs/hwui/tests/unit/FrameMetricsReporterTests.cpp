/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <FrameMetricsObserver.h>
#include <FrameMetricsReporter.h>
#include <utils/TimeUtils.h>

using namespace android;
using namespace android::uirenderer;

using ::testing::NotNull;

class TestFrameMetricsObserver : public FrameMetricsObserver {
public:
    explicit TestFrameMetricsObserver(bool waitForPresentTime)
            : FrameMetricsObserver(waitForPresentTime){};

    MOCK_METHOD(void, notify, (const int64_t* buffer), (override));
};

// To make sure it is clear that something went wrong if no from frame is set (to make it easier
// to catch bugs were we forget to set the fromFrame).
TEST(FrameMetricsReporter, doesNotReportAnyFrameIfNoFromFrameIsSpecified) {
    auto reporter = std::make_shared<FrameMetricsReporter>();

    auto observer = sp<TestFrameMetricsObserver>::make(false /*waitForPresentTime*/);
    EXPECT_CALL(*observer, notify).Times(0);

    reporter->addObserver(observer.get());

    const int64_t* stats;
    bool hasPresentTime = false;
    uint64_t frameNumber = 1;
    int32_t surfaceControlId = 0;
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);

    frameNumber = 10;
    surfaceControlId = 0;
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);

    frameNumber = 0;
    surfaceControlId = 2;
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);

    frameNumber = 10;
    surfaceControlId = 2;
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);
}

TEST(FrameMetricsReporter, respectsWaitForPresentTimeUnset) {
    const int64_t* stats;
    bool hasPresentTime = false;
    uint64_t frameNumber = 3;
    int32_t surfaceControlId = 0;

    auto reporter = std::make_shared<FrameMetricsReporter>();

    auto observer = sp<TestFrameMetricsObserver>::make(hasPresentTime);
    observer->reportMetricsFrom(frameNumber, surfaceControlId);
    reporter->addObserver(observer.get());

    EXPECT_CALL(*observer, notify).Times(1);
    hasPresentTime = false;
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);

    EXPECT_CALL(*observer, notify).Times(0);
    hasPresentTime = true;
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);
}

TEST(FrameMetricsReporter, respectsWaitForPresentTimeSet) {
    const int64_t* stats;
    bool hasPresentTime = true;
    uint64_t frameNumber = 3;
    int32_t surfaceControlId = 0;

    auto reporter = std::make_shared<FrameMetricsReporter>();

    auto observer = sp<TestFrameMetricsObserver>::make(hasPresentTime);
    observer->reportMetricsFrom(frameNumber, surfaceControlId);
    reporter->addObserver(observer.get());

    EXPECT_CALL(*observer, notify).Times(0);
    hasPresentTime = false;
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);

    EXPECT_CALL(*observer, notify).Times(1);
    hasPresentTime = true;
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);
}

TEST(FrameMetricsReporter, reportsAllFramesAfterSpecifiedFromFrame) {
    const int64_t* stats;
    bool hasPresentTime = false;

    std::vector<uint64_t> frameNumbers{0, 1, 10};
    std::vector<int32_t> surfaceControlIds{0, 1, 10};
    for (uint64_t frameNumber : frameNumbers) {
        for (int32_t surfaceControlId : surfaceControlIds) {
            auto reporter = std::make_shared<FrameMetricsReporter>();

            auto observer =
                    sp<TestFrameMetricsObserver>::make(hasPresentTime /*waitForPresentTime*/);
            observer->reportMetricsFrom(frameNumber, surfaceControlId);
            reporter->addObserver(observer.get());

            EXPECT_CALL(*observer, notify).Times(8);
            reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);
            reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber + 1, surfaceControlId);
            reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber + 10, surfaceControlId);
            reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId + 1);
            reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber - 1,
                                         surfaceControlId + 1);
            reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber + 1,
                                         surfaceControlId + 1);
            reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber + 10,
                                         surfaceControlId + 1);
            reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber + 10,
                                         surfaceControlId + 10);
        }
    }
}

TEST(FrameMetricsReporter, doesNotReportsFramesBeforeSpecifiedFromFrame) {
    const int64_t* stats;
    bool hasPresentTime = false;

    std::vector<uint64_t> frameNumbers{1, 10};
    std::vector<int32_t> surfaceControlIds{0, 1, 10};
    for (uint64_t frameNumber : frameNumbers) {
        for (int32_t surfaceControlId : surfaceControlIds) {
            auto reporter = std::make_shared<FrameMetricsReporter>();

            auto observer =
                    sp<TestFrameMetricsObserver>::make(hasPresentTime /*waitForPresentTime*/);
            observer->reportMetricsFrom(frameNumber, surfaceControlId);
            reporter->addObserver(observer.get());

            EXPECT_CALL(*observer, notify).Times(0);
            reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber - 1, surfaceControlId);
            if (surfaceControlId > 0) {
                reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber,
                                             surfaceControlId - 1);
                reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber - 1,
                                             surfaceControlId - 1);
            }
        }
    }
}

TEST(FrameMetricsReporter, canRemoveObservers) {
    const int64_t* stats;
    bool hasPresentTime = false;
    uint64_t frameNumber = 3;
    int32_t surfaceControlId = 0;

    auto reporter = std::make_shared<FrameMetricsReporter>();

    auto observer = sp<TestFrameMetricsObserver>::make(hasPresentTime /*waitForPresentTime*/);

    observer->reportMetricsFrom(frameNumber, surfaceControlId);
    reporter->addObserver(observer.get());

    EXPECT_CALL(*observer, notify).Times(1);
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);

    ASSERT_TRUE(reporter->removeObserver(observer.get()));

    EXPECT_CALL(*observer, notify).Times(0);
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);
}

TEST(FrameMetricsReporter, canSupportMultipleObservers) {
    const int64_t* stats;
    bool hasPresentTime = false;
    uint64_t frameNumber = 3;
    int32_t surfaceControlId = 0;

    auto reporter = std::make_shared<FrameMetricsReporter>();

    auto observer1 = sp<TestFrameMetricsObserver>::make(hasPresentTime /*waitForPresentTime*/);
    auto observer2 = sp<TestFrameMetricsObserver>::make(hasPresentTime /*waitForPresentTime*/);
    observer1->reportMetricsFrom(frameNumber, surfaceControlId);
    observer2->reportMetricsFrom(frameNumber + 10, surfaceControlId + 1);
    reporter->addObserver(observer1.get());
    reporter->addObserver(observer2.get());

    EXPECT_CALL(*observer1, notify).Times(1);
    EXPECT_CALL(*observer2, notify).Times(0);
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber, surfaceControlId);

    EXPECT_CALL(*observer1, notify).Times(1);
    EXPECT_CALL(*observer2, notify).Times(1);
    reporter->reportFrameMetrics(stats, hasPresentTime, frameNumber + 10, surfaceControlId + 1);
}
