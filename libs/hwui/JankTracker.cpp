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

#include <errno.h>
#include <inttypes.h>
#include <sys/mman.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>

#include <cutils/ashmem.h>
#include <log/log.h>

#include "Properties.h"
#include "utils/TimeUtils.h"

namespace android {
namespace uirenderer {

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
 * We don't track direct-drawing via Surface:lockHardwareCanvas()
 * for now
 *
 * TODO: kSurfaceCanvas can negatively impact other drawing by using up
 * time on the RenderThread, figure out how to attribute that as a jank-causer
 */
static const int64_t EXEMPT_FRAMES_FLAGS = FrameInfoFlags::SurfaceCanvas;

// For testing purposes to try and eliminate test infra overhead we will
// consider any unknown delay of frame start as part of the test infrastructure
// and filter it out of the frame profile data
static FrameInfoIndex sFrameStart = FrameInfoIndex::IntendedVsync;

JankTracker::JankTracker(ProfileDataContainer* globalData, const DisplayInfo& displayInfo) {
    mGlobalData = globalData;
    nsecs_t frameIntervalNanos = static_cast<nsecs_t>(1_s / displayInfo.fps);
#if USE_HWC2
    nsecs_t sfOffset = frameIntervalNanos - (displayInfo.presentationDeadline - 1_ms);
    nsecs_t offsetDelta = sfOffset - displayInfo.appVsyncOffset;
    // There are two different offset cases. If the offsetDelta is positive
    // and small, then the intention is to give apps extra time by leveraging
    // pipelining between the UI & RT threads. If the offsetDelta is large or
    // negative, the intention is to subtract time from the total duration
    // in which case we can't afford to wait for dequeueBuffer blockage.
    if (offsetDelta <= 4_ms && offsetDelta >= 0) {
        // SF will begin composition at VSYNC-app + offsetDelta. If we are triple
        // buffered, this is the expected time at which dequeueBuffer will
        // return due to the staggering of VSYNC-app & VSYNC-sf.
        mDequeueTimeForgiveness = offsetDelta + 4_ms;
    }
#endif
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

void JankTracker::finishFrame(const FrameInfo& frame) {
    // Fast-path for jank-free frames
    int64_t totalDuration = frame.duration(sFrameStart, FrameInfoIndex::FrameCompleted);
    if (mDequeueTimeForgiveness
            && frame[FrameInfoIndex::DequeueBufferDuration] > 500_us) {
        nsecs_t expectedDequeueDuration =
                mDequeueTimeForgiveness + frame[FrameInfoIndex::Vsync]
                - frame[FrameInfoIndex::IssueDrawCommandsStart];
        if (expectedDequeueDuration > 0) {
            // Forgive only up to the expected amount, but not more than
            // the actual time spent blocked.
            nsecs_t forgiveAmount = std::min(expectedDequeueDuration,
                    frame[FrameInfoIndex::DequeueBufferDuration]);
            LOG_ALWAYS_FATAL_IF(forgiveAmount >= totalDuration,
                    "Impossible dequeue duration! dequeue duration reported %" PRId64
                    ", total duration %" PRId64, forgiveAmount, totalDuration);
            totalDuration -= forgiveAmount;
        }
    }
    LOG_ALWAYS_FATAL_IF(totalDuration <= 0, "Impossible totalDuration %" PRId64, totalDuration);
    mData->reportFrame(totalDuration);
    (*mGlobalData)->reportFrame(totalDuration);

    // Keep the fast path as fast as possible.
    if (CC_LIKELY(totalDuration < mFrameInterval)) {
        return;
    }

    // Only things like Surface.lockHardwareCanvas() are exempt from tracking
    if (frame[FrameInfoIndex::Flags] & EXEMPT_FRAMES_FLAGS) {
        return;
    }

    mData->reportJank();
    (*mGlobalData)->reportJank();

    for (int i = 0; i < NUM_BUCKETS; i++) {
        int64_t delta = frame.duration(COMPARISONS[i].start, COMPARISONS[i].end);
        if (delta >= mThresholds[i] && delta < IGNORE_EXCEEDING) {
            mData->reportJankType((JankType) i);
            (*mGlobalData)->reportJankType((JankType) i);
        }
    }
}

void JankTracker::dumpData(int fd, const ProfileDataDescription* description, const ProfileData* data) {
    if (description) {
        switch (description->type) {
            case JankTrackerType::Generic:
                break;
            case JankTrackerType::Package:
                dprintf(fd, "\nPackage: %s", description->name.c_str());
                break;
            case JankTrackerType::Window:
                dprintf(fd, "\nWindow: %s", description->name.c_str());
                break;
        }
    }
    if (sFrameStart != FrameInfoIndex::IntendedVsync) {
        dprintf(fd, "\nNote: Data has been filtered!");
    }
    data->dump(fd);
    dprintf(fd, "\n");
}

void JankTracker::dumpFrames(int fd) {
    FILE* file = fdopen(fd, "a");
    fprintf(file, "\n\n---PROFILEDATA---\n");
    for (size_t i = 0; i < static_cast<size_t>(FrameInfoIndex::NumIndexes); i++) {
        fprintf(file, "%s", FrameInfoNames[i].c_str());
        fprintf(file, ",");
    }
    for (size_t i = 0; i < mFrames.size(); i++) {
        FrameInfo& frame = mFrames[i];
        if (frame[FrameInfoIndex::SyncStart] == 0) {
            continue;
        }
        fprintf(file, "\n");
        for (int i = 0; i < static_cast<int>(FrameInfoIndex::NumIndexes); i++) {
            fprintf(file, "%" PRId64 ",", frame[i]);
        }
    }
    fprintf(file, "\n---PROFILEDATA---\n\n");
    fflush(file);
}

void JankTracker::reset() {
    mFrames.clear();
    mData->reset();
    (*mGlobalData)->reset();
    sFrameStart = Properties::filterOutTestOverhead
            ? FrameInfoIndex::HandleInputStart
            : FrameInfoIndex::IntendedVsync;
}

} /* namespace uirenderer */
} /* namespace android */
