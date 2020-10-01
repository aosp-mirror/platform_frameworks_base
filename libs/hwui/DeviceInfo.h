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

#include <apex/display.h>
#include <SkImageInfo.h>

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
    static float getMaxRefreshRate() { return get()->mMaxRefreshRate; }
    static int32_t getWidth() { return get()->mWidth; }
    static int32_t getHeight() { return get()->mHeight; }
    static float getDensity() { return get()->mDensity; }
    static int64_t getVsyncPeriod() { return get()->mVsyncPeriod; }
    static int64_t getCompositorOffset() { return get()->mCompositorOffset; }
    static int64_t getAppOffset() { return get()->mAppOffset; }

    // this value is only valid after the GPU has been initialized and there is a valid graphics
    // context or if you are using the HWUI_NULL_GPU
    int maxTextureSize() const;
    sk_sp<SkColorSpace> getWideColorSpace() const { return mWideColorSpace; }
    SkColorType getWideColorType() const { return mWideColorType; }

    // This method should be called whenever the display refresh rate changes.
    void onRefreshRateChanged(int64_t vsyncPeriod);

private:
    friend class renderthread::RenderThread;
    static void setMaxTextureSize(int maxTextureSize);
    void updateDisplayInfo();

    DeviceInfo();
    ~DeviceInfo();

    int mMaxTextureSize;
    sk_sp<SkColorSpace> mWideColorSpace = SkColorSpace::MakeSRGB();
    SkColorType mWideColorType = SkColorType::kN32_SkColorType;
    ADisplayConfig* mCurrentConfig = nullptr;
    ADisplay** mDisplays = nullptr;
    int mDisplaysSize = 0;
    int mPhysicalDisplayIndex = -1;
    float mMaxRefreshRate = 60.0;
    int32_t mWidth = 1080;
    int32_t mHeight = 1920;
    float mDensity = 2.0;
    int64_t mVsyncPeriod = 16666666;
    int64_t mCompositorOffset = 0;
    int64_t mAppOffset = 0;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DEVICEINFO_H */
