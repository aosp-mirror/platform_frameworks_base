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

// --- InputWindowHandle ---

bool InputWindowHandle::touchableRegionContainsPoint(int32_t x, int32_t y) const {
    return touchableRegion.contains(x, y);
}

bool InputWindowHandle::frameContainsPoint(int32_t x, int32_t y) const {
    return x >= frameLeft && x <= frameRight
            && y >= frameTop && y <= frameBottom;
}

bool InputWindowHandle::isTrustedOverlay() const {
    return layoutParamsType == TYPE_INPUT_METHOD
            || layoutParamsType == TYPE_INPUT_METHOD_DIALOG
            || layoutParamsType == TYPE_SECURE_SYSTEM_OVERLAY;
}

bool InputWindowHandle::supportsSplitTouch() const {
    return layoutParamsFlags & FLAG_SPLIT_TOUCH;
}

} // namespace android
