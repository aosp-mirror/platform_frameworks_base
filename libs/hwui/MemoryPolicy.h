/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"),
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

#include "utils/TimeUtils.h"

namespace android::uirenderer {

// Values mirror those from ComponentCallbacks2.java
enum class TrimLevel {
    COMPLETE = 80,
    MODERATE = 60,
    BACKGROUND = 40,
    UI_HIDDEN = 20,
    RUNNING_CRITICAL = 15,
    RUNNING_LOW = 10,
    RUNNING_MODERATE = 5,
};

enum class CacheTrimLevel {
    ALL_CACHES = 0,
    FONT_CACHE = 1,
    RESOURCE_CACHE = 2,
};

struct MemoryPolicy {
    // The initial scale factor applied to the display resolution. The default is 1, but
    // lower values may be used to start with a smaller initial cache size. The cache will
    // be adjusted if larger frames are actually rendered
    float initialMaxSurfaceAreaScale = 1.0f;
    // The foreground cache size multiplier. The surface area of the screen will be multiplied
    // by this
    float surfaceSizeMultiplier = 12.0f * 4.0f;
    // How much of the foreground cache size should be preserved when going into the background
    float backgroundRetentionPercent = 0.5f;
    // How long after the last renderer goes away before the GPU context is released. A value
    // of 0 means only drop the context on background TRIM signals
    nsecs_t contextTimeout = 10_s;
    // The minimum amount of time to hold onto items in the resource cache
    // The actual time used will be the max of this & when frames were actually rendered
    nsecs_t minimumResourceRetention = 10_s;
    // The maximum amount of time to hold onto items in the resource cache
    nsecs_t maximumResourceRetention = 100000_s;
    // If false, use only TRIM_UI_HIDDEN to drive background cache limits;
    // If true, use all signals (such as all contexts are stopped) to drive the limits
    bool useAlternativeUiHidden = true;
    // Whether or not to only purge scratch resources when triggering UI Hidden or background
    // collection
    bool purgeScratchOnly = true;
    // EXPERIMENTAL: Whether or not to trigger releasing GPU context when all contexts are stopped
    // WARNING: Enabling this option can lead to instability, see b/266626090
    bool releaseContextOnStoppedOnly = false;
};

const MemoryPolicy& loadMemoryPolicy();

}  // namespace android::uirenderer