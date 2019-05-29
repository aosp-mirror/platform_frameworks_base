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
#include <statslog.h>
#include <sys/mman.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>

#include <cutils/ashmem.h>
#include <log/log.h>
#include <sstream>

#include "Properties.h"
#include "utils/TimeUtils.h"
#include "utils/Trace.h"

namespace android {
namespace uirenderer {

struct Comparison {
    JankType type;
    std::function<int64_t(nsecs_t)> computeThreadshold;
    FrameInfoIndex start;
    FrameInfoIndex end;
};

static const std::array<Comparison, 4> COMPARISONS{
        Comparison{JankType::kMissedVsync, [](nsecs_t) { return 1; }, FrameInfoIndex::IntendedVsync,
                   FrameInfoIndex::Vsync},

        Comparison{JankType::kSlowUI,
                   [](nsecs_t frameInterval) { return static_cast<int64_t>(.5 * frameInterval); },
                   FrameInfoIndex::Vsync, FrameInfoIndex::SyncStart},

        Comparison{JankType::kSlowSync,
                   [](nsecs_t frameInterval) { return static_cast<int64_t>(.2 * frameInterval); },
                   FrameInfoIndex::SyncStart, FrameInfoIndex::IssueDrawCommandsStart},

        Comparison{JankType::kSlowRT,
                   [](nsecs_t frameInterval) { return static_cast<int64_t>(.75 * frameInterval); },
                   FrameInfoIndex::IssueDrawCommandsStart, FrameInfoIndex::FrameCompleted},
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
    setFrameInterval(frameIntervalNanos);
}

void JankTracker::setFrameInterval(nsecs_t frameInterval) {
    mFrameInterval = frameInterval;

    for (auto& comparison : COMPARISONS) {
        mThresholds[comparison.type] = comparison.computeThreadshold(frameInterval);
    }
}

void JankTracker::finishFrame(const FrameInfo& frame) {
    // Fast-path for jank-free frames
    int64_t totalDuration = frame.duration(sFrameStart, FrameInfoIndex::FrameCompleted);
    if (mDequeueTimeForgiveness && frame[FrameInfoIndex::DequeueBufferDuration] > 500_us) {
        nsecs_t expectedDequeueDuration = mDequeueTimeForgiveness + frame[FrameInfoIndex::Vsync] -
                                          frame[FrameInfoIndex::IssueDrawCommandsStart];
        if (expectedDequeueDuration > 0) {
            // Forgive only up to the expected amount, but not more than
            // the actual time spent blocked.
            nsecs_t forgiveAmount =
                    std::min(expectedDequeueDuration, frame[FrameInfoIndex::DequeueBufferDuration]);
            LOG_ALWAYS_FATAL_IF(forgiveAmount >= totalDuration,
                                "Impossible dequeue duration! dequeue duration reported %" PRId64
                                ", total duration %" PRId64,
                                forgiveAmount, totalDuration);
            totalDuration -= forgiveAmount;
        }
    }

    LOG_ALWAYS_FATAL_IF(totalDuration <= 0, "Impossible totalDuration %" PRId64, totalDuration);
    mData->reportFrame(totalDuration);
    (*mGlobalData)->reportFrame(totalDuration);

    // Only things like Surface.lockHardwareCanvas() are exempt from tracking
    if (CC_UNLIKELY(frame[FrameInfoIndex::Flags] & EXEMPT_FRAMES_FLAGS)) {
        return;
    }

    if (totalDuration > mFrameInterval) {
        mData->reportJank();
        (*mGlobalData)->reportJank();
    }

    bool isTripleBuffered = (mSwapDeadline - frame[FrameInfoIndex::IntendedVsync]) > (mFrameInterval * 0.1);

    mSwapDeadline = std::max(mSwapDeadline + mFrameInterval,
                             frame[FrameInfoIndex::IntendedVsync] + mFrameInterval);

    // If we hit the deadline, cool!
    if (frame[FrameInfoIndex::FrameCompleted] < mSwapDeadline || totalDuration < mFrameInterval) {
        if (isTripleBuffered) {
            mData->reportJankType(JankType::kHighInputLatency);
            (*mGlobalData)->reportJankType(JankType::kHighInputLatency);
        }
        return;
    }

    mData->reportJankType(JankType::kMissedDeadline);
    (*mGlobalData)->reportJankType(JankType::kMissedDeadline);

    // Janked, reset the swap deadline
    nsecs_t jitterNanos = frame[FrameInfoIndex::FrameCompleted] - frame[FrameInfoIndex::Vsync];
    nsecs_t lastFrameOffset = jitterNanos % mFrameInterval;
    mSwapDeadline = frame[FrameInfoIndex::FrameCompleted] - lastFrameOffset + mFrameInterval;

    for (auto& comparison : COMPARISONS) {
        int64_t delta = frame.duration(comparison.start, comparison.end);
        if (delta >= mThresholds[comparison.type] && delta < IGNORE_EXCEEDING) {
            mData->reportJankType(comparison.type);
            (*mGlobalData)->reportJankType(comparison.type);
        }
    }

    // Log daveys since they are weird and we don't know what they are (b/70339576)
    if (totalDuration >= 700_ms) {
        static int sDaveyCount = 0;
        std::stringstream ss;
        ss << "Davey! duration=" << ns2ms(totalDuration) << "ms; ";
        for (size_t i = 0; i < static_cast<size_t>(FrameInfoIndex::NumIndexes); i++) {
            ss << FrameInfoNames[i] << "=" << frame[i] << ", ";
        }
        ALOGI("%s", ss.str().c_str());
        // Just so we have something that counts up, the value is largely irrelevant
        ATRACE_INT(ss.str().c_str(), ++sDaveyCount);
        android::util::stats_write(android::util::DAVEY_OCCURRED, getuid(), ns2ms(totalDuration));
    }
}

void JankTracker::dumpData(int fd, const ProfileDataDescription* description,
                           const ProfileData* data) {
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
    dprintf(fd, "\n\n---PROFILEDATA---\n");
    for (size_t i = 0; i < static_cast<size_t>(FrameInfoIndex::NumIndexes); i++) {
        dprintf(fd, "%s", FrameInfoNames[i].c_str());
        dprintf(fd, ",");
    }
    for (size_t i = 0; i < mFrames.size(); i++) {
        FrameInfo& frame = mFrames[i];
        if (frame[FrameInfoIndex::SyncStart] == 0) {
            continue;
        }
        dprintf(fd, "\n");
        for (int i = 0; i < static_cast<int>(FrameInfoIndex::NumIndexes); i++) {
            dprintf(fd, "%" PRId64 ",", frame[i]);
        }
    }
    dprintf(fd, "\n---PROFILEDATA---\n\n");
}

void JankTracker::reset() {
    mFrames.clear();
    mData->reset();
    (*mGlobalData)->reset();
    sFrameStart = Properties::filterOutTestOverhead ? FrameInfoIndex::HandleInputStart
                                                    : FrameInfoIndex::IntendedVsync;
}

} /* namespace uirenderer */
} /* namespace android */
