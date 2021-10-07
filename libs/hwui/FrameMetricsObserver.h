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
    FrameMetricsObserver(bool waitForPresentTime) : mWaitForPresentTime(waitForPresentTime) {}

private:
    const bool mWaitForPresentTime;
};

}  // namespace uirenderer
}  // namespace android
