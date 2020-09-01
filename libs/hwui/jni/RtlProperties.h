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

#ifndef _ANDROID_GRAPHICS_RTL_PROPERTIES_H_
#define _ANDROID_GRAPHICS_RTL_PROPERTIES_H_

#include <cutils/properties.h>
#include <stdlib.h>

namespace android {

/**
 * Debug level for app developers.
 */
#define RTL_PROPERTY_DEBUG "rtl.debug_level"

/**
 * Debug levels. Debug levels are used as flags.
 */
enum RtlDebugLevel {
    kRtlDebugDisabled = 0,
    kRtlDebugMemory = 1,
    kRtlDebugCaches = 2,
    kRtlDebugAllocations = 3
};

static RtlDebugLevel readRtlDebugLevel() {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(RTL_PROPERTY_DEBUG, property, NULL) > 0) {
        return (RtlDebugLevel) atoi(property);
    }
    return kRtlDebugDisabled;
}

} // namespace android
#endif // _ANDROID_GRAPHICS_RTL_PROPERTIES_H_
