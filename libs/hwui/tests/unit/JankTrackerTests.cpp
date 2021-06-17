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

#include <gtest/gtest.h>
#include <gmock/gmock.h>

#include <JankTracker.h>
#include <utils/TimeUtils.h>

using namespace android;
using namespace android::uirenderer;

class TestFrameMetricsObserver : public FrameMetricsObserver {
public:
    void notify(const int64_t*) {}
};

TEST(JankTracker, noJank) {
    std::mutex mutex;
    ProfileDataContainer container(mutex);
    JankTracker jankTracker(&container);
    std::unique_ptr<FrameMetricsReporter> reporter = std::make_unique<FrameMetricsReporter>();

    FrameInfo* info = jankTracker.startFrame();
    info->set(FrameInfoIndex::IntendedVsync) = 100_ms;
    info->set(FrameInfoIndex::Vsync) = 101_ms;
    info->set(FrameInfoIndex::SwapBuffersCompleted) = 115_ms;
    info->set(FrameInfoIndex::GpuCompleted) = 115_ms;
    info->set(FrameInfoIndex::FrameCompleted) = 115_ms;
    info->set(FrameInfoIndex::FrameInterval) = 16_ms;
    info->set(FrameInfoIndex::FrameDeadline) = 120_ms;
    jankTracker.finishFrame(*info, reporter);

    info = jankTracker.startFrame();
    info->set(FrameInfoIndex::IntendedVsync) = 116_ms;
    info->set(FrameInfoIndex::Vsync) = 117_ms;
    info->set(FrameInfoIndex::SwapBuffersCompleted) = 129_ms;
    info->set(FrameInfoIndex::GpuCompleted) = 131_ms;
    info->set(FrameInfoIndex::FrameCompleted) = 131_ms;
    info->set(FrameInfoIndex::FrameInterval) = 16_ms;
    info->set(FrameInfoIndex::FrameDeadline) = 136_ms;
    jankTracker.finishFrame(*info, reporter);

    ASSERT_EQ(2, container.get()->totalFrameCount());
    ASSERT_EQ(0, container.get()->jankFrameCount());
}


TEST(JankTracker, jank) {
    std::mutex mutex;
    ProfileDataContainer container(mutex);
    JankTracker jankTracker(&container);
    std::unique_ptr<FrameMetricsReporter> reporter = std::make_unique<FrameMetricsReporter>();

    FrameInfo* info = jankTracker.startFrame();
    info->set(FrameInfoIndex::IntendedVsync) = 100_ms;
    info->set(FrameInfoIndex::Vsync) = 101_ms;
    info->set(FrameInfoIndex::SwapBuffersCompleted) = 115_ms;
    info->set(FrameInfoIndex::GpuCompleted) = 121_ms;
    info->set(FrameInfoIndex::FrameCompleted) = 121_ms;
    info->set(FrameInfoIndex::FrameInterval) = 16_ms;
    info->set(FrameInfoIndex::FrameDeadline) = 120_ms;
    jankTracker.finishFrame(*info, reporter);

    ASSERT_EQ(1, container.get()->totalFrameCount());
    ASSERT_EQ(1, container.get()->jankFrameCount());
}

TEST(JankTracker, legacyJankButNoRealJank) {
    std::mutex mutex;
    ProfileDataContainer container(mutex);
    JankTracker jankTracker(&container);
    std::unique_ptr<FrameMetricsReporter> reporter = std::make_unique<FrameMetricsReporter>();

    FrameInfo* info = jankTracker.startFrame();
    info->set(FrameInfoIndex::IntendedVsync) = 100_ms;
    info->set(FrameInfoIndex::Vsync) = 101_ms;
    info->set(FrameInfoIndex::SwapBuffersCompleted) = 117_ms;
    info->set(FrameInfoIndex::GpuCompleted) = 118_ms;
    info->set(FrameInfoIndex::FrameCompleted) = 118_ms;
    info->set(FrameInfoIndex::FrameInterval) = 16_ms;
    info->set(FrameInfoIndex::FrameDeadline) = 120_ms;
    jankTracker.finishFrame(*info, reporter);

    ASSERT_EQ(1, container.get()->totalFrameCount());
    ASSERT_EQ(0, container.get()->jankFrameCount());
    ASSERT_EQ(1, container.get()->jankLegacyFrameCount());
}

TEST(JankTracker, doubleStuffed) {
    std::mutex mutex;
    ProfileDataContainer container(mutex);
    JankTracker jankTracker(&container);
    std::unique_ptr<FrameMetricsReporter> reporter = std::make_unique<FrameMetricsReporter>();

    // First frame janks
    FrameInfo* info = jankTracker.startFrame();
    info->set(FrameInfoIndex::IntendedVsync) = 100_ms;
    info->set(FrameInfoIndex::Vsync) = 101_ms;
    info->set(FrameInfoIndex::SwapBuffersCompleted) = 115_ms;
    info->set(FrameInfoIndex::GpuCompleted) = 121_ms;
    info->set(FrameInfoIndex::FrameCompleted) = 121_ms;
    info->set(FrameInfoIndex::FrameInterval) = 16_ms;
    info->set(FrameInfoIndex::FrameDeadline) = 120_ms;
    jankTracker.finishFrame(*info, reporter);

    ASSERT_EQ(1, container.get()->jankFrameCount());

    // Second frame is long, but doesn't jank because double-stuffed.
    info = jankTracker.startFrame();
    info->set(FrameInfoIndex::IntendedVsync) = 116_ms;
    info->set(FrameInfoIndex::Vsync) = 122_ms;
    info->set(FrameInfoIndex::SwapBuffersCompleted) = 129_ms;
    info->set(FrameInfoIndex::GpuCompleted) = 137_ms;
    info->set(FrameInfoIndex::FrameCompleted) = 137_ms;
    info->set(FrameInfoIndex::FrameInterval) = 16_ms;
    info->set(FrameInfoIndex::FrameDeadline) = 136_ms;
    jankTracker.finishFrame(*info, reporter);

    ASSERT_EQ(2, container.get()->totalFrameCount());
    ASSERT_EQ(1, container.get()->jankFrameCount());
}

TEST(JankTracker, doubleStuffedThenPauseThenJank) {
    std::mutex mutex;
    ProfileDataContainer container(mutex);
    JankTracker jankTracker(&container);
    std::unique_ptr<FrameMetricsReporter> reporter = std::make_unique<FrameMetricsReporter>();

    // First frame janks
    FrameInfo* info = jankTracker.startFrame();
    info->set(FrameInfoIndex::IntendedVsync) = 100_ms;
    info->set(FrameInfoIndex::Vsync) = 101_ms;
    info->set(FrameInfoIndex::SwapBuffersCompleted) = 115_ms;
    info->set(FrameInfoIndex::GpuCompleted) = 121_ms;
    info->set(FrameInfoIndex::FrameCompleted) = 121_ms;
    info->set(FrameInfoIndex::FrameInterval) = 16_ms;
    info->set(FrameInfoIndex::FrameDeadline) = 120_ms;
    jankTracker.finishFrame(*info, reporter);

    ASSERT_EQ(1, container.get()->jankFrameCount());

    // Second frame is long, but doesn't jank because double-stuffed.
    info = jankTracker.startFrame();
    info->set(FrameInfoIndex::IntendedVsync) = 116_ms;
    info->set(FrameInfoIndex::Vsync) = 122_ms;
    info->set(FrameInfoIndex::SwapBuffersCompleted) = 129_ms;
    info->set(FrameInfoIndex::GpuCompleted) = 137_ms;
    info->set(FrameInfoIndex::FrameCompleted) = 137_ms;
    info->set(FrameInfoIndex::FrameInterval) = 16_ms;
    info->set(FrameInfoIndex::FrameDeadline) = 136_ms;
    jankTracker.finishFrame(*info, reporter);

    ASSERT_EQ(1, container.get()->jankFrameCount());

    // Thirdframe is long and skips one frame some double stuffed logic gets reset
    info = jankTracker.startFrame();
    info->set(FrameInfoIndex::IntendedVsync) = 148_ms;
    info->set(FrameInfoIndex::Vsync) = 148_ms;
    info->set(FrameInfoIndex::SwapBuffersCompleted) = 160_ms;
    info->set(FrameInfoIndex::GpuCompleted) = 169_ms;
    info->set(FrameInfoIndex::FrameCompleted) = 169_ms;
    info->set(FrameInfoIndex::FrameInterval) = 16_ms;
    info->set(FrameInfoIndex::FrameDeadline) = 168_ms;
    jankTracker.finishFrame(*info, reporter);

    ASSERT_EQ(3, container.get()->totalFrameCount());
    ASSERT_EQ(2, container.get()->jankFrameCount());
}