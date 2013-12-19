/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "InputWindow"

#include "InputWindow.h"

#include <cutils/log.h>

namespace android {

// --- InputWindowInfo ---

bool InputWindowInfo::touchableRegionContainsPoint(int32_t x, int32_t y) const {
    return touchableRegion.contains(x, y);
}

bool InputWindowInfo::frameContainsPoint(int32_t x, int32_t y) const {
    return x >= frameLeft && x <= frameRight
            && y >= frameTop && y <= frameBottom;
}

bool InputWindowInfo::isTrustedOverlay() const {
    return layoutParamsType == TYPE_INPUT_METHOD
            || layoutParamsType == TYPE_INPUT_METHOD_DIALOG
            || layoutParamsType == TYPE_SECURE_SYSTEM_OVERLAY;
}

bool InputWindowInfo::supportsSplitTouch() const {
    return layoutParamsFlags & FLAG_SPLIT_TOUCH;
}


// --- InputWindowHandle ---

InputWindowHandle::InputWindowHandle(const sp<InputApplicationHandle>& inputApplicationHandle) :
    inputApplicationHandle(inputApplicationHandle), mInfo(NULL) {
}

InputWindowHandle::~InputWindowHandle() {
    delete mInfo;
}

void InputWindowHandle::releaseInfo() {
    if (mInfo) {
        delete mInfo;
        mInfo = NULL;
    }
}

} // namespace android
