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

#include <SkImageInfo.h>
#include <ui/DisplayInfo.h>

#include "utils/Macros.h"

namespace android {
namespace uirenderer {

namespace renderthread {
    class RenderThread;
}

class DeviceInfo {
    PREVENT_COPY_AND_ASSIGN(DeviceInfo);

public:
    static DeviceInfo* get();

    // this value is only valid after the GPU has been initialized and there is a valid graphics
    // context or if you are using the HWUI_NULL_GPU
    int maxTextureSize() const;
    const DisplayInfo& displayInfo() const { return mDisplayInfo; }
    sk_sp<SkColorSpace> getWideColorSpace() const { return mWideColorSpace; }
    SkColorType getWideColorType() const { return mWideColorType; }
    float getMaxRefreshRate() const { return mMaxRefreshRate; }

    void onDisplayConfigChanged();

private:
    friend class renderthread::RenderThread;
    static void setMaxTextureSize(int maxTextureSize);

    DeviceInfo();

    int mMaxTextureSize;
    DisplayInfo mDisplayInfo;
    sk_sp<SkColorSpace> mWideColorSpace;
    SkColorType mWideColorType;
    const float mMaxRefreshRate;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DEVICEINFO_H */
