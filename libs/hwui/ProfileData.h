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

#pragma once

#include "Properties.h"
#include "utils/Macros.h"

#include <utils/Timers.h>

#include <array>
#include <functional>
#include <tuple>

namespace android {
namespace uirenderer {

enum JankType {
    kMissedVsync = 0,
    kHighInputLatency,
    kSlowUI,
    kSlowSync,
    kSlowRT,
    kMissedDeadline,

    // must be last
    NUM_BUCKETS,
};

// For testing
class MockProfileData;

// Try to keep as small as possible, should match ASHMEM_SIZE in
// GraphicsStatsService.java
class ProfileData {
    PREVENT_COPY_AND_ASSIGN(ProfileData);

public:
    ProfileData() { reset(); }

    void reset();
    void mergeWith(const ProfileData& other);
    void dump(int fd) const;
    uint32_t findPercentile(int percentile) const;
    uint32_t findGPUPercentile(int percentile) const;

    void reportFrame(int64_t duration);
    void reportGPUFrame(int64_t duration);
    void reportJank() { mJankFrameCount++; }
    void reportJankType(JankType type) { mJankTypeCounts[static_cast<int>(type)]++; }

    uint32_t totalFrameCount() const { return mTotalFrameCount; }
    uint32_t jankFrameCount() const { return mJankFrameCount; }
    nsecs_t statsStartTime() const { return mStatStartTime; }
    uint32_t jankTypeCount(JankType type) const { return mJankTypeCounts[static_cast<int>(type)]; }
    RenderPipelineType pipelineType() const { return mPipelineType; }

    struct HistogramEntry {
        uint32_t renderTimeMs;
        uint32_t frameCount;
    };
    void histogramForEach(const std::function<void(HistogramEntry)>& callback) const;
    void histogramGPUForEach(const std::function<void(HistogramEntry)>& callback) const;

    constexpr static int HistogramSize() {
        return std::tuple_size<decltype(ProfileData::mFrameCounts)>::value +
               std::tuple_size<decltype(ProfileData::mSlowFrameCounts)>::value;
    }

    constexpr static int GPUHistogramSize() {
        return std::tuple_size<decltype(ProfileData::mGPUFrameCounts)>::value;
    }

    // Visible for testing
    static uint32_t frameTimeForFrameCountIndex(uint32_t index);
    static uint32_t frameTimeForSlowFrameCountIndex(uint32_t index);
    static uint32_t GPUFrameTimeForFrameCountIndex(uint32_t index);

private:
    // Open our guts up to unit tests
    friend class MockProfileData;

    std::array<uint32_t, NUM_BUCKETS> mJankTypeCounts;
    // See comments on kBucket* constants for what this holds
    std::array<uint32_t, 57> mFrameCounts;
    // Holds a histogram of frame times in 50ms increments from 150ms to 5s
    std::array<uint16_t, 97> mSlowFrameCounts;
    // Holds a histogram of GPU draw times in 1ms increments. Frames longer than 25ms are placed in
    // last bucket.
    std::array<uint32_t, 26> mGPUFrameCounts;

    uint32_t mTotalFrameCount;
    uint32_t mJankFrameCount;
    nsecs_t mStatStartTime;

    // true if HWUI renders with Vulkan pipeline
    RenderPipelineType mPipelineType;
};

// For testing
class MockProfileData : public ProfileData {
public:
    std::array<uint32_t, NUM_BUCKETS>& editJankTypeCounts() { return mJankTypeCounts; }
    std::array<uint32_t, 57>& editFrameCounts() { return mFrameCounts; }
    std::array<uint16_t, 97>& editSlowFrameCounts() { return mSlowFrameCounts; }
    uint32_t& editTotalFrameCount() { return mTotalFrameCount; }
    uint32_t& editJankFrameCount() { return mJankFrameCount; }
    nsecs_t& editStatStartTime() { return mStatStartTime; }
};

} /* namespace uirenderer */
} /* namespace android */
