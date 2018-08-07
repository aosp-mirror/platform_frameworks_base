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
#ifndef DEVICEINFO_H
#define DEVICEINFO_H

#include <ui/DisplayInfo.h>

#include "Extensions.h"
#include "utils/Macros.h"

namespace android {
namespace uirenderer {

class DeviceInfo {
    PREVENT_COPY_AND_ASSIGN(DeviceInfo);

public:
    // returns nullptr if DeviceInfo is not initialized yet
    // Note this does not have a memory fence so it's up to the caller
    // to use one if required. Normally this should not be necessary
    static const DeviceInfo* get();

    // only call this after GL has been initialized, or at any point if compiled
    // with HWUI_NULL_GPU
    static void initialize();
    static void initialize(int maxTextureSize);

    int maxTextureSize() const { return mMaxTextureSize; }
    const DisplayInfo& displayInfo() const { return mDisplayInfo; }
    const Extensions& extensions() const { return mExtensions; }

    static uint32_t multiplyByResolution(uint32_t in) {
        auto di = DeviceInfo::get()->displayInfo();
        return di.w * di.h * in;
    }

    static DisplayInfo queryDisplayInfo();

private:
    DeviceInfo() {}
    ~DeviceInfo() {}

    void load();

    int mMaxTextureSize;
    DisplayInfo mDisplayInfo;
    Extensions mExtensions;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DEVICEINFO_H */
