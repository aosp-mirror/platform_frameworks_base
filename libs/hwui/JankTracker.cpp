/*
 * Copyright (C) 2015 The Android Open Source Project
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
#include "JankTracker.h"

#include <algorithm>
#include <cutils/ashmem.h>
#include <cutils/log.h>
#include <cstdio>
#include <errno.h>
#include <inttypes.h>
#include <sys/mman.h>

namespace android {
namespace uirenderer {

static const char* JANK_TYPE_NAMES[] = {
        "Missed Vsync",
        "High input latency",
        "Slow UI thread",
        "Slow bitmap uploads",
        "Slow issue draw commands",
};

struct Comparison {
    FrameInfoIndex start;
    FrameInfoIndex end;
};

static const Comparison COMPARISONS[] = {
        {FrameInfoIndex::IntendedVsync, FrameInfoIndex::Vsync},
        {FrameInfoIndex::OldestInputEvent, FrameInfoIndex::Vsync},
        {FrameInfoIndex::Vsync, FrameInfoIndex::SyncStart},
        {FrameInfoIndex::SyncStart, FrameInfoIndex::IssueDrawCommandsStart},
        {FrameInfoIndex::IssueDrawCommandsStart, FrameInfoIndex::FrameCompleted},
};

// If the event exceeds 10 seconds throw it away, this isn't a jank event
// it's an ANR and will be handled as such
static const int64_t IGNORE_EXCEEDING = seconds_to_nanoseconds(10);

/*
 * Frames that are exempt from jank metrics.
 * First-draw frames, for example, are expected to
 * be slow, this is hidden from the user with window animations and
 * other tricks
 *
 * Similarly, we don't track direct-drawing via Surface:lockHardwareCanvas()
 * for now
 *
 * TODO: kSurfaceCanvas can negatively impact other drawing by using up
 * time on the RenderThread, figure out how to attribute that as a jank-causer
 */
static const int64_t EXEMPT_FRAMES_FLAGS
        = FrameInfoFlags::WindowLayoutChanged
        | FrameInfoFlags::SurfaceCanvas;

// The bucketing algorithm controls so to speak
// If a frame is <= to this it goes in bucket 0
static const uint32_t kBucketMinThreshold = 7;
// If a frame is > this, start counting in increments of 2ms
static const uint32_t kBucket2msIntervals = 32;
// If a frame is > this, start counting in increments of 4ms
static const uint32_t kBucket4msIntervals = 48;

// This will be called every frame, performance sensitive
// Uses bit twiddling to avoid branching while achieving the packing desired
static uint32_t frameCountIndexForFrameTime(nsecs_t frameTime, uint32_t max) {
    uint32_t index = static_cast<uint32_t>(ns2ms(frameTime));
    // If index > kBucketMinThreshold mask will be 0xFFFFFFFF as a result
    // of negating 1 (twos compliment, yaay) else mask will be 0
    uint32_t mask = -(index > kBucketMinThreshold);
    // If index > threshold, this will essentially perform:
    // amountAboveThreshold = index - threshold;
    // index = threshold + (amountAboveThreshold / 2)
    // However if index is <= this will do nothing. It will underflow, do
    // a right shift by 0 (no-op), then overflow back to the original value
    index = ((index - kBucket4msIntervals) >> (index > kBucket4msIntervals))
            + kBucket4msIntervals;
    index = ((index - kBucket2msIntervals) >> (index > kBucket2msIntervals))
            + kBucket2msIntervals;
    // If index was < minThreshold at the start of all this it's going to
    // be a pretty garbage value right now. However, mask is 0 so we'll end
    // up with the desired result of 0.
    index = (index - kBucketMinThreshold) & mask;
    return index < max ? index : max;
}

// Only called when dumping stats, less performance sensitive
static uint32_t frameTimeForFrameCountIndex(uint32_t index) {
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

JankTracker::JankTracker(nsecs_t frameIntervalNanos) {
    // By default this will use malloc memory. It may be moved later to ashmem
    // if there is shared space for it and a request comes in to do that.
    mData = new ProfileData;
    reset();
    setFrameInterval(frameIntervalNanos);
}

JankTracker::~JankTracker() {
    freeData();
}

void JankTracker::freeData() {
    if (mIsMapped) {
        munmap(mData, sizeof(ProfileData));
    } else {
        delete mData;
    }
    mIsMapped = false;
    mData = nullptr;
}

void JankTracker::switchStorageToAshmem(int ashmemfd) {
    int regionSize = ashmem_get_size_region(ashmemfd);
    if (regionSize < static_cast<int>(sizeof(ProfileData))) {
        ALOGW("Ashmem region is too small! Received %d, required %u",
                regionSize, static_cast<unsigned int>(sizeof(ProfileData)));
        return;
    }
    ProfileData* newData = reinterpret_cast<ProfileData*>(
            mmap(NULL, sizeof(ProfileData), PROT_READ | PROT_WRITE,
            MAP_SHARED, ashmemfd, 0));
    if (newData == MAP_FAILED) {
        int err = errno;
        ALOGW("Failed to move profile data to ashmem fd %d, error = %d",
                ashmemfd, err);
        return;
    }

    // The new buffer may have historical data that we want to build on top of
    // But let's make sure we don't overflow Just In Case
    uint32_t divider = 0;
    if (newData->totalFrameCount > (1 << 24)) {
        divider = 4;
    }
    for (size_t i = 0; i < mData->jankTypeCounts.size(); i++) {
        newData->jankTypeCounts[i] >>= divider;
        newData->jankTypeCounts[i] += mData->jankTypeCounts[i];
    }
    for (size_t i = 0; i < mData->frameCounts.size(); i++) {
        newData->frameCounts[i] >>= divider;
        newData->frameCounts[i] += mData->frameCounts[i];
    }
    newData->jankFrameCount >>= divider;
    newData->jankFrameCount += mData->jankFrameCount;
    newData->totalFrameCount >>= divider;
    newData->totalFrameCount += mData->totalFrameCount;
    if (newData->statStartTime > mData->statStartTime
            || newData->statStartTime == 0) {
        newData->statStartTime = mData->statStartTime;
    }

    freeData();
    mData = newData;
    mIsMapped = true;
}

void JankTracker::setFrameInterval(nsecs_t frameInterval) {
    mFrameInterval = frameInterval;
    mThresholds[kMissedVsync] = 1;
    /*
     * Due to interpolation and sample rate differences between the touch
     * panel and the display (example, 85hz touch panel driving a 60hz display)
     * we call high latency 1.5 * frameinterval
     *
     * NOTE: Be careful when tuning this! A theoretical 1,000hz touch panel
     * on a 60hz display will show kOldestInputEvent - kIntendedVsync of being 15ms
     * Thus this must always be larger than frameInterval, or it will fail
     */
    mThresholds[kHighInputLatency] = static_cast<int64_t>(1.5 * frameInterval);

    // Note that these do not add up to 1. This is intentional. It's to deal
    // with variance in values, and should be sort of an upper-bound on what
    // is reasonable to expect.
    mThresholds[kSlowUI] = static_cast<int64_t>(.5 * frameInterval);
    mThresholds[kSlowSync] = static_cast<int64_t>(.2 * frameInterval);
    mThresholds[kSlowRT] = static_cast<int64_t>(.75 * frameInterval);

}

void JankTracker::addFrame(const FrameInfo& frame) {
    mData->totalFrameCount++;
    // Fast-path for jank-free frames
    int64_t totalDuration =
            frame[FrameInfoIndex::FrameCompleted] - frame[FrameInfoIndex::IntendedVsync];
    uint32_t framebucket = frameCountIndexForFrameTime(
            totalDuration, mData->frameCounts.size());
    // Keep the fast path as fast as possible.
    if (CC_LIKELY(totalDuration < mFrameInterval)) {
        mData->frameCounts[framebucket]++;
        return;
    }

    if (frame[FrameInfoIndex::Flags] & EXEMPT_FRAMES_FLAGS) {
        return;
    }

    mData->frameCounts[framebucket]++;
    mData->jankFrameCount++;

    for (int i = 0; i < NUM_BUCKETS; i++) {
        int64_t delta = frame.duration(COMPARISONS[i].start, COMPARISONS[i].end);
        if (delta >= mThresholds[i] && delta < IGNORE_EXCEEDING) {
            mData->jankTypeCounts[i]++;
        }
    }
}

void JankTracker::dumpBuffer(const void* buffer, size_t bufsize, int fd) {
    if (bufsize < sizeof(ProfileData)) {
        return;
    }
    const ProfileData* data = reinterpret_cast<const ProfileData*>(buffer);
    dumpData(data, fd);
}

void JankTracker::dumpData(const ProfileData* data, int fd) {
    dprintf(fd, "\nStats since: %" PRIu64 "ns", data->statStartTime);
    dprintf(fd, "\nTotal frames rendered: %u", data->totalFrameCount);
    dprintf(fd, "\nJanky frames: %u (%.2f%%)", data->jankFrameCount,
            (float) data->jankFrameCount / (float) data->totalFrameCount * 100.0f);
    dprintf(fd, "\n90th percentile: %ums", findPercentile(data, 90));
    dprintf(fd, "\n95th percentile: %ums", findPercentile(data, 95));
    dprintf(fd, "\n99th percentile: %ums", findPercentile(data, 99));
    for (int i = 0; i < NUM_BUCKETS; i++) {
        dprintf(fd, "\nNumber %s: %u", JANK_TYPE_NAMES[i], data->jankTypeCounts[i]);
    }
    dprintf(fd, "\n");
}

void JankTracker::reset() {
    mData->jankTypeCounts.fill(0);
    mData->frameCounts.fill(0);
    mData->totalFrameCount = 0;
    mData->jankFrameCount = 0;
    mData->statStartTime = systemTime(CLOCK_MONOTONIC);
}

uint32_t JankTracker::findPercentile(const ProfileData* data, int percentile) {
    int pos = percentile * data->totalFrameCount / 100;
    int remaining = data->totalFrameCount - pos;
    for (int i = data->frameCounts.size() - 1; i >= 0; i--) {
        remaining -= data->frameCounts[i];
        if (remaining <= 0) {
            return frameTimeForFrameCountIndex(i);
        }
    }
    return 0;
}

} /* namespace uirenderer */
} /* namespace android */
