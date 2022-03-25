/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <utils/RefBase.h>

namespace android {
namespace uirenderer {

class FrameMetricsObserver : public VirtualLightRefBase {
public:
    virtual void notify(const int64_t* buffer) = 0;
    bool waitForPresentTime() const { return mWaitForPresentTime; };

    void reportMetricsFrom(uint64_t frameNumber, int32_t surfaceControlId) {
        mAttachedFrameNumber = frameNumber;
        mSurfaceControlId = surfaceControlId;
    };
    uint64_t attachedFrameNumber() const { return mAttachedFrameNumber; };
    int32_t attachedSurfaceControlId() const { return mSurfaceControlId; };

    /**
     * Create a new metrics observer. An observer that watches present time gets notified at a
     * different time than the observer that doesn't.
     *
     * The observer that doesn't want present time is notified about metrics just after the frame
     * is completed. This is the default behaviour that's used by public API's.
     *
     * An observer that watches present time is notified about metrics after the actual display
     * present time is known.
     * WARNING! This observer may not receive metrics for the last several frames that the app
     * produces.
     */
    FrameMetricsObserver(bool waitForPresentTime)
            : mWaitForPresentTime(waitForPresentTime)
            , mSurfaceControlId(INT32_MAX)
            , mAttachedFrameNumber(UINT64_MAX) {}

private:
    const bool mWaitForPresentTime;

    // The id of the surface control (mSurfaceControlGenerationId in CanvasContext)
    // for which the mAttachedFrameNumber applies to. We rely on this value being
    // an increasing counter. We will report metrics:
    // - for all frames if the frame comes from a surface with a surfaceControlId
    //   that is strictly greater than mSurfaceControlId.
    // - for all frames with a frame number greater than or equal to mAttachedFrameNumber
    //   if the frame comes from a surface with a surfaceControlId that is equal to the
    //   mSurfaceControlId.
    // We will never report metrics if the frame comes from a surface with a surfaceControlId
    // that is strictly smaller than mSurfaceControlId.
    int32_t mSurfaceControlId;

    // The frame number the metrics observer was attached on. Metrics will be sent from this frame
    // number (inclusive) onwards in the case that the surface id is equal to mSurfaceControlId.
    uint64_t mAttachedFrameNumber;
};

}  // namespace uirenderer
}  // namespace android
