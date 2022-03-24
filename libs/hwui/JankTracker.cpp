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

#include <cutils/ashmem.h>
#include <errno.h>
#include <inttypes.h>
#include <log/log.h>
#include <sys/mman.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>
#include <sstream>

#include "DeviceInfo.h"
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

JankTracker::JankTracker(ProfileDataContainer* globalData)
        : mData(globalData->getDataMutex())
        , mDataMutex(globalData->getDataMutex()) {
    mGlobalData = globalData;
    nsecs_t frameIntervalNanos = DeviceInfo::getVsyncPeriod();
    nsecs_t sfOffset = DeviceInfo::getCompositorOffset();
    nsecs_t offsetDelta = sfOffset - DeviceInfo::getAppOffset();
    // There are two different offset cases. If the offsetDelta is positive
    // and small, then the intention is to give apps extra time by leveraging
    // pipelining between the UI & RT threads. If the offsetDelta is large or
    // negative, the intention is to subtract time from the total duration
    // in which case we can't afford to wait for dequeueBuffer blockage.
    if (offsetDelta <= 4_ms && offsetDelta >= 0) {
        // SF will begin composition at VSYNC-app + offsetDelta. If we are triple
        // buffered, this is the expected time at which dequeueBuffer will
        // return due to the staggering of VSYNC-app & VSYNC-sf.
        mDequeueTimeForgivenessLegacy = offsetDelta + 4_ms;
    }
    mFrameIntervalLegacy = frameIntervalNanos;
}

void JankTracker::calculateLegacyJank(FrameInfo& frame) REQUIRES(mDataMutex) {
    // Fast-path for jank-free frames
    int64_t totalDuration = frame.duration(sFrameStart, FrameInfoIndex::SwapBuffersCompleted);
    if (mDequeueTimeForgivenessLegacy && frame[FrameInfoIndex::DequeueBufferDuration] > 500_us) {
        nsecs_t expectedDequeueDuration = mDequeueTimeForgivenessLegacy
                                          + frame[FrameInfoIndex::Vsync]
                                          - frame[FrameInfoIndex::IssueDrawCommandsStart];
        if (expectedDequeueDuration > 0) {
            // Forgive only up to the expected amount, but not more than
            // the actual time spent blocked.
            nsecs_t forgiveAmount =
                    std::min(expectedDequeueDuration, frame[FrameInfoIndex::DequeueBufferDuration]);
            if (forgiveAmount >= totalDuration) {
                ALOGV("Impossible dequeue duration! dequeue duration reported %" PRId64
                      ", total duration %" PRId64,
                      forgiveAmount, totalDuration);
                return;
            }
            totalDuration -= forgiveAmount;
        }
    }

    if (totalDuration <= 0) {
        ALOGV("Impossible totalDuration %" PRId64 " start=%" PRIi64 " gpuComplete=%" PRIi64,
              totalDuration, frame[FrameInfoIndex::IntendedVsync],
              frame[FrameInfoIndex::GpuCompleted]);
        return;
    }

    // Only things like Surface.lockHardwareCanvas() are exempt from tracking
    if (CC_UNLIKELY(frame[FrameInfoIndex::Flags] & EXEMPT_FRAMES_FLAGS)) {
        return;
    }

    if (totalDuration > mFrameIntervalLegacy) {
        mData->reportJankLegacy();
        (*mGlobalData)->reportJankLegacy();
    }

    if (mSwapDeadlineLegacy < 0) {
        mSwapDeadlineLegacy = frame[FrameInfoIndex::IntendedVsync] + mFrameIntervalLegacy;
    }
    bool isTripleBuffered = (mSwapDeadlineLegacy - frame[FrameInfoIndex::IntendedVsync])
            > (mFrameIntervalLegacy * 0.1);

    mSwapDeadlineLegacy = std::max(mSwapDeadlineLegacy + mFrameIntervalLegacy,
                             frame[FrameInfoIndex::IntendedVsync] + mFrameIntervalLegacy);

    // If we hit the deadline, cool!
    if (frame[FrameInfoIndex::FrameCompleted] < mSwapDeadlineLegacy
            || totalDuration < mFrameIntervalLegacy) {
        if (isTripleBuffered) {
            mData->reportJankType(JankType::kHighInputLatency);
            (*mGlobalData)->reportJankType(JankType::kHighInputLatency);
        }
        return;
    }

    mData->reportJankType(JankType::kMissedDeadlineLegacy);
    (*mGlobalData)->reportJankType(JankType::kMissedDeadlineLegacy);

    // Janked, reset the swap deadline
    nsecs_t jitterNanos = frame[FrameInfoIndex::FrameCompleted] - frame[FrameInfoIndex::Vsync];
    nsecs_t lastFrameOffset = jitterNanos % mFrameIntervalLegacy;
    mSwapDeadlineLegacy = frame[FrameInfoIndex::FrameCompleted]
            - lastFrameOffset + mFrameIntervalLegacy;
}

void JankTracker::finishFrame(FrameInfo& frame, std::unique_ptr<FrameMetricsReporter>& reporter,
                              int64_t frameNumber, int32_t surfaceControlId) {
    std::lock_guard lock(mDataMutex);

    calculateLegacyJank(frame);

    // Fast-path for jank-free frames
    int64_t totalDuration = frame.duration(FrameInfoIndex::IntendedVsync,
            FrameInfoIndex::FrameCompleted);

    if (totalDuration <= 0) {
        ALOGV("Impossible totalDuration %" PRId64, totalDuration);
        return;
    }
    mData->reportFrame(totalDuration);
    (*mGlobalData)->reportFrame(totalDuration);

    // Only things like Surface.lockHardwareCanvas() are exempt from tracking
    if (CC_UNLIKELY(frame[FrameInfoIndex::Flags] & EXEMPT_FRAMES_FLAGS)) {
        return;
    }

    int64_t frameInterval = frame[FrameInfoIndex::FrameInterval];

    // If we starter earlier than the intended frame start assuming an unstuffed scenario, it means
    // that we are in a triple buffering situation.
    bool isTripleBuffered = (mNextFrameStartUnstuffed - frame[FrameInfoIndex::IntendedVsync])
                    > (frameInterval * 0.1);

    int64_t deadline = frame[FrameInfoIndex::FrameDeadline];

    // If we are in triple buffering, we have enough buffers in queue to sustain a single frame
    // drop without jank, so adjust the frame interval to the deadline.
    if (isTripleBuffered) {
        deadline += frameInterval;
        frame.set(FrameInfoIndex::FrameDeadline) += frameInterval;
    }

    // If we hit the deadline, cool!
    if (frame[FrameInfoIndex::GpuCompleted] < deadline) {
        if (isTripleBuffered) {
            mData->reportJankType(JankType::kHighInputLatency);
            (*mGlobalData)->reportJankType(JankType::kHighInputLatency);

            // Buffer stuffing state gets carried over to next frame, unless there is a "pause"
            mNextFrameStartUnstuffed += frameInterval;
        }
    } else {
        mData->reportJankType(JankType::kMissedDeadline);
        (*mGlobalData)->reportJankType(JankType::kMissedDeadline);
        mData->reportJank();
        (*mGlobalData)->reportJank();

        // Janked, store the adjust deadline to detect triple buffering in next frame correctly.
        nsecs_t jitterNanos = frame[FrameInfoIndex::GpuCompleted]
                - frame[FrameInfoIndex::Vsync];
        nsecs_t lastFrameOffset = jitterNanos % frameInterval;

        // Note the time when the next frame would start in an unstuffed situation. If it starts
        // earlier, we are in a stuffed situation.
        mNextFrameStartUnstuffed = frame[FrameInfoIndex::GpuCompleted]
                - lastFrameOffset + frameInterval;

        recomputeThresholds(frameInterval);
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
        }
    }

    int64_t totalGPUDrawTime = frame.gpuDrawTime();
    if (totalGPUDrawTime >= 0) {
        mData->reportGPUFrame(totalGPUDrawTime);
        (*mGlobalData)->reportGPUFrame(totalGPUDrawTime);
    }

    if (CC_UNLIKELY(reporter.get() != nullptr)) {
        reporter->reportFrameMetrics(frame.data(), false /* hasPresentTime */, frameNumber,
                                     surfaceControlId);
    }
}

void JankTracker::recomputeThresholds(int64_t frameBudget) REQUIRES(mDataMutex) {
    if (mThresholdsFrameBudget == frameBudget) {
        return;
    }
    mThresholdsFrameBudget = frameBudget;
    for (auto& comparison : COMPARISONS) {
        mThresholds[comparison.type] = comparison.computeThreadshold(frameBudget);
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
        dprintf(fd, "%s", FrameInfoNames[i]);
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

void JankTracker::reset() REQUIRES(mDataMutex) {
    mFrames.clear();
    mData->reset();
    (*mGlobalData)->reset();
    sFrameStart = Properties::filterOutTestOverhead ? FrameInfoIndex::HandleInputStart
                                                    : FrameInfoIndex::IntendedVsync;
}

} /* namespace uirenderer */
} /* namespace android */
