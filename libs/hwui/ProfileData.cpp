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

#include "ProfileData.h"

#include <cinttypes>

namespace android {
namespace uirenderer {

static const char* JANK_TYPE_NAMES[] = {
        "Missed Vsync",        "High input latency",       "Slow UI thread",
        "Slow bitmap uploads", "Slow issue draw commands", "Frame deadline missed"};

// The bucketing algorithm controls so to speak
// If a frame is <= to this it goes in bucket 0
static const uint32_t kBucketMinThreshold = 5;
// If a frame is > this, start counting in increments of 2ms
static const uint32_t kBucket2msIntervals = 32;
// If a frame is > this, start counting in increments of 4ms
static const uint32_t kBucket4msIntervals = 48;

// The interval of the slow frame histogram
static const uint32_t kSlowFrameBucketIntervalMs = 50;
// The start point of the slow frame bucket in ms
static const uint32_t kSlowFrameBucketStartMs = 150;

// This will be called every frame, performance sensitive
// Uses bit twiddling to avoid branching while achieving the packing desired
static uint32_t frameCountIndexForFrameTime(nsecs_t frameTime) {
    uint32_t index = static_cast<uint32_t>(ns2ms(frameTime));
    // If index > kBucketMinThreshold mask will be 0xFFFFFFFF as a result
    // of negating 1 (twos compliment, yaay) else mask will be 0
    uint32_t mask = -(index > kBucketMinThreshold);
    // If index > threshold, this will essentially perform:
    // amountAboveThreshold = index - threshold;
    // index = threshold + (amountAboveThreshold / 2)
    // However if index is <= this will do nothing. It will underflow, do
    // a right shift by 0 (no-op), then overflow back to the original value
    index = ((index - kBucket4msIntervals) >> (index > kBucket4msIntervals)) + kBucket4msIntervals;
    index = ((index - kBucket2msIntervals) >> (index > kBucket2msIntervals)) + kBucket2msIntervals;
    // If index was < minThreshold at the start of all this it's going to
    // be a pretty garbage value right now. However, mask is 0 so we'll end
    // up with the desired result of 0.
    index = (index - kBucketMinThreshold) & mask;
    return index;
}

// Only called when dumping stats, less performance sensitive
uint32_t ProfileData::frameTimeForFrameCountIndex(uint32_t index) {
    index = index + kBucketMinThreshold;
    if (index > kBucket2msIntervals) {
        index += (index - kBucket2msIntervals);
    }
    if (index > kBucket4msIntervals) {
        // This works because it was already doubled by the above if
        // 1 is added to shift slightly more towards the middle of the bucket
        index += (index - kBucket4msIntervals) + 1;
    }
    return index;
}

uint32_t ProfileData::frameTimeForSlowFrameCountIndex(uint32_t index) {
    return (index * kSlowFrameBucketIntervalMs) + kSlowFrameBucketStartMs;
}

void ProfileData::mergeWith(const ProfileData& other) {
    // Make sure we don't overflow Just In Case
    uint32_t divider = 0;
    if (mTotalFrameCount > (1 << 24)) {
        divider = 4;
    }
    for (size_t i = 0; i < other.mJankTypeCounts.size(); i++) {
        mJankTypeCounts[i] >>= divider;
        mJankTypeCounts[i] += other.mJankTypeCounts[i];
    }
    for (size_t i = 0; i < other.mFrameCounts.size(); i++) {
        mFrameCounts[i] >>= divider;
        mFrameCounts[i] += other.mFrameCounts[i];
    }
    mJankFrameCount >>= divider;
    mJankFrameCount += other.mJankFrameCount;
    mTotalFrameCount >>= divider;
    mTotalFrameCount += other.mTotalFrameCount;
    if (mStatStartTime > other.mStatStartTime || mStatStartTime == 0) {
        mStatStartTime = other.mStatStartTime;
    }
}

void ProfileData::dump(int fd) const {
    dprintf(fd, "\nStats since: %" PRIu64 "ns", mStatStartTime);
    dprintf(fd, "\nTotal frames rendered: %u", mTotalFrameCount);
    dprintf(fd, "\nJanky frames: %u (%.2f%%)", mJankFrameCount,
            mTotalFrameCount == 0 ? 0.0f :
                (float)mJankFrameCount / (float)mTotalFrameCount * 100.0f);
    dprintf(fd, "\n50th percentile: %ums", findPercentile(50));
    dprintf(fd, "\n90th percentile: %ums", findPercentile(90));
    dprintf(fd, "\n95th percentile: %ums", findPercentile(95));
    dprintf(fd, "\n99th percentile: %ums", findPercentile(99));
    for (int i = 0; i < NUM_BUCKETS; i++) {
        dprintf(fd, "\nNumber %s: %u", JANK_TYPE_NAMES[i], mJankTypeCounts[i]);
    }
    dprintf(fd, "\nHISTOGRAM:");
    histogramForEach([fd](HistogramEntry entry) {
        dprintf(fd, " %ums=%u", entry.renderTimeMs, entry.frameCount);
    });
}

uint32_t ProfileData::findPercentile(int percentile) const {
    int pos = percentile * mTotalFrameCount / 100;
    int remaining = mTotalFrameCount - pos;
    for (int i = mSlowFrameCounts.size() - 1; i >= 0; i--) {
        remaining -= mSlowFrameCounts[i];
        if (remaining <= 0) {
            return (i * kSlowFrameBucketIntervalMs) + kSlowFrameBucketStartMs;
        }
    }
    for (int i = mFrameCounts.size() - 1; i >= 0; i--) {
        remaining -= mFrameCounts[i];
        if (remaining <= 0) {
            return frameTimeForFrameCountIndex(i);
        }
    }
    return 0;
}

void ProfileData::reset() {
    mJankTypeCounts.fill(0);
    mFrameCounts.fill(0);
    mSlowFrameCounts.fill(0);
    mTotalFrameCount = 0;
    mJankFrameCount = 0;
    mStatStartTime = systemTime(CLOCK_MONOTONIC);
}

void ProfileData::reportFrame(int64_t duration) {
    mTotalFrameCount++;
    uint32_t framebucket = frameCountIndexForFrameTime(duration);
    if (framebucket <= mFrameCounts.size()) {
        mFrameCounts[framebucket]++;
    } else {
        framebucket = (ns2ms(duration) - kSlowFrameBucketStartMs) / kSlowFrameBucketIntervalMs;
        framebucket = std::min(framebucket, static_cast<uint32_t>(mSlowFrameCounts.size() - 1));
        mSlowFrameCounts[framebucket]++;
    }
}

void ProfileData::histogramForEach(const std::function<void(HistogramEntry)>& callback) const {
    for (size_t i = 0; i < mFrameCounts.size(); i++) {
        callback(HistogramEntry{frameTimeForFrameCountIndex(i), mFrameCounts[i]});
    }
    for (size_t i = 0; i < mSlowFrameCounts.size(); i++) {
        callback(HistogramEntry{frameTimeForSlowFrameCountIndex(i), mSlowFrameCounts[i]});
    }
}

} /* namespace uirenderer */
} /* namespace android */