/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "screencap_utils.h"

#include "gui/SyncScreenCaptureListener.h"

namespace android::screencap {

base::Result<gui::ScreenCaptureResults> capture(const DisplayId displayId,
                                                const gui::CaptureArgs& captureArgs) {
    sp<SyncScreenCaptureListener> captureListener = new SyncScreenCaptureListener();
    auto captureDisplayStatus =
            ScreenshotClient::captureDisplay(displayId, captureArgs, captureListener);

    gui::ScreenCaptureResults captureResults = captureListener->waitForResults();
    if (!captureResults.fenceResult.ok()) {
        status_t captureStatus = fenceStatus(captureResults.fenceResult);
        std::stringstream errorMsg;
        errorMsg << "Failed to take take screenshot. ";
        if (captureStatus == NAME_NOT_FOUND) {
            errorMsg << "Display Id '" << displayId.value << "' is not valid.\n";
        }
        return base::ResultError(errorMsg.str(), captureStatus);
    }

    return captureResults;
}

} // namespace android::screencap