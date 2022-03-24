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

#include "FrameMetricsReporter.h"

namespace android {
namespace uirenderer {

void FrameMetricsReporter::reportFrameMetrics(const int64_t* stats, bool hasPresentTime,
                                              uint64_t frameNumber, int32_t surfaceControlId) {
    FatVector<sp<FrameMetricsObserver>, 10> copy;
    {
        std::lock_guard lock(mObserversLock);
        copy.reserve(mObservers.size());
        for (size_t i = 0; i < mObservers.size(); i++) {
            auto observer = mObservers[i];

            if (CC_UNLIKELY(surfaceControlId < observer->attachedSurfaceControlId())) {
                // Don't notify if the metrics are from a frame that was run on an old
                // surface (one from before the observer was attached).
                ALOGV("skipped reporting metrics from old surface %d", surfaceControlId);
                continue;
            } else if (CC_UNLIKELY(surfaceControlId == observer->attachedSurfaceControlId() &&
                                   frameNumber < observer->attachedFrameNumber())) {
                // Don't notify if the metrics are from a frame that was queued by the
                // BufferQueueProducer on the render thread before the observer was attached.
                ALOGV("skipped reporting metrics from old frame %ld", (long)frameNumber);
                continue;
            }

            const bool wantsPresentTime = observer->waitForPresentTime();
            if (hasPresentTime == wantsPresentTime) {
                copy.push_back(observer);
            }
        }
    }
    for (size_t i = 0; i < copy.size(); i++) {
        copy[i]->notify(stats);
    }
}

}  // namespace uirenderer
}  // namespace android
