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
#include <cstdio>
#include <inttypes.h>

namespace android {
namespace uirenderer {

static const char* JANK_TYPE_NAMES[] = {
        "Missed Vsync",
        "High input latency",
        "Slow UI thread",
        "Slow bitmap uploads",
        "Slow draw",
};

struct Comparison {
    FrameInfoIndexEnum start;
    FrameInfoIndexEnum end;
};

static const Comparison COMPARISONS[] = {
        {FrameInfoIndex::kIntendedVsync, FrameInfoIndex::kVsync},
        {FrameInfoIndex::kOldestInputEvent, FrameInfoIndex::kVsync},
        {FrameInfoIndex::kVsync, FrameInfoIndex::kSyncStart},
        {FrameInfoIndex::kSyncStart, FrameInfoIndex::kIssueDrawCommandsStart},
        {FrameInfoIndex::kIssueDrawCommandsStart, FrameInfoIndex::kFrameCompleted},
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
        = FrameInfoFlags::kWindowLayoutChanged
        | FrameInfoFlags::kSurfaceCanvas;

JankTracker::JankTracker(nsecs_t frameIntervalNanos) {
    reset();
    setFrameInterval(frameIntervalNanos);
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
    using namespace FrameInfoIndex;
    mTotalFrameCount++;
    // Fast-path for jank-free frames
    int64_t totalDuration = frame[kFrameCompleted] - frame[kIntendedVsync];
    uint32_t framebucket = std::min(
            static_cast<typeof sizeof(mFrameCounts)>(ns2ms(totalDuration)),
            sizeof(mFrameCounts) / sizeof(mFrameCounts[0]));
    // Keep the fast path as fast as possible.
    if (CC_LIKELY(totalDuration < mFrameInterval)) {
        mFrameCounts[framebucket]++;
        return;
    }

    if (frame[kFlags] & EXEMPT_FRAMES_FLAGS) {
        return;
    }

    mFrameCounts[framebucket]++;
    mJankFrameCount++;

    for (int i = 0; i < NUM_BUCKETS; i++) {
        int64_t delta = frame[COMPARISONS[i].end] - frame[COMPARISONS[i].start];
        if (delta >= mThresholds[i] && delta < IGNORE_EXCEEDING) {
            mBuckets[i].count++;
        }
    }
}

void JankTracker::dump(int fd) {
    FILE* file = fdopen(fd, "a");
    fprintf(file, "\nFrame stats:");
    fprintf(file, "\n  Total frames rendered: %u", mTotalFrameCount);
    fprintf(file, "\n  Janky frames: %u (%.2f%%)", mJankFrameCount,
            (float) mJankFrameCount / (float) mTotalFrameCount * 100.0f);
    fprintf(file, "\n  90th percentile: %ums", findPercentile(90));
    fprintf(file, "\n  95th percentile: %ums", findPercentile(95));
    fprintf(file, "\n  99th percentile: %ums", findPercentile(99));
    for (int i = 0; i < NUM_BUCKETS; i++) {
        fprintf(file, "\n   Number %s: %u", JANK_TYPE_NAMES[i], mBuckets[i].count);
    }
    fprintf(file, "\n");
    fflush(file);
}

void JankTracker::reset() {
    memset(mBuckets, 0, sizeof(mBuckets));
    memset(mFrameCounts, 0, sizeof(mFrameCounts));
    mTotalFrameCount = 0;
    mJankFrameCount = 0;
}

uint32_t JankTracker::findPercentile(int percentile) {
    int pos = percentile * mTotalFrameCount / 100;
    int remaining = mTotalFrameCount - pos;
    for (int i = sizeof(mFrameCounts) / sizeof(mFrameCounts[0]) - 1; i >= 0; i--) {
        remaining -= mFrameCounts[i];
        if (remaining <= 0) {
            return i;
        }
    }
    return 0;
}

} /* namespace uirenderer */
} /* namespace android */
