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

#include <DeviceInfo.h>
#include <log/log.h>
#include <utils/Errors.h>

#include "Properties.h"

namespace android {
namespace uirenderer {

DeviceInfo* DeviceInfo::get() {
    static DeviceInfo sDeviceInfo;
    return &sDeviceInfo;
}

DeviceInfo::DeviceInfo() {
#if HWUI_NULL_GPU
        mMaxTextureSize = NULL_GPU_MAX_TEXTURE_SIZE;
#else
        mMaxTextureSize = -1;
#endif
        updateDisplayInfo();
}
DeviceInfo::~DeviceInfo() {
    ADisplay_release(mDisplays);
}

int DeviceInfo::maxTextureSize() const {
    LOG_ALWAYS_FATAL_IF(mMaxTextureSize < 0, "MaxTextureSize has not been initialized yet.");
    return mMaxTextureSize;
}

void DeviceInfo::setMaxTextureSize(int maxTextureSize) {
    DeviceInfo::get()->mMaxTextureSize = maxTextureSize;
}

void DeviceInfo::onRefreshRateChanged(int64_t vsyncPeriod) {
    mVsyncPeriod = vsyncPeriod;
}

void DeviceInfo::updateDisplayInfo() {
    if (Properties::isolatedProcess) {
        return;
    }

    if (mCurrentConfig == nullptr) {
        mDisplaysSize = ADisplay_acquirePhysicalDisplays(&mDisplays);
        LOG_ALWAYS_FATAL_IF(mDisplays == nullptr || mDisplaysSize <= 0,
                            "Failed to get physical displays: no connected display: %d!", mDisplaysSize);
        for (size_t i = 0; i < mDisplaysSize; i++) {
            ADisplayType type = ADisplay_getDisplayType(mDisplays[i]);
            if (type == ADisplayType::DISPLAY_TYPE_INTERNAL) {
                mPhysicalDisplayIndex = i;
                break;
            }
        }
        LOG_ALWAYS_FATAL_IF(mPhysicalDisplayIndex < 0, "Failed to find a connected physical display!");


        // Since we now just got the primary display for the first time, then
        // store the primary display metadata here.
        ADisplay* primaryDisplay = mDisplays[mPhysicalDisplayIndex];
        mMaxRefreshRate = ADisplay_getMaxSupportedFps(primaryDisplay);
        ADataSpace dataspace;
        AHardwareBuffer_Format format;
        ADisplay_getPreferredWideColorFormat(primaryDisplay, &dataspace, &format);
        switch (dataspace) {
            case ADATASPACE_DISPLAY_P3:
                mWideColorSpace =
                        SkColorSpace::MakeRGB(SkNamedTransferFn::kSRGB, SkNamedGamut::kDCIP3);
                break;
            case ADATASPACE_SCRGB:
                mWideColorSpace = SkColorSpace::MakeSRGB();
                break;
            case ADATASPACE_SRGB:
                // when sRGB is returned, it means wide color gamut is not supported.
                mWideColorSpace = SkColorSpace::MakeSRGB();
                break;
            default:
                LOG_ALWAYS_FATAL("Unreachable: unsupported wide color space.");
        }
        switch (format) {
            case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
                mWideColorType = SkColorType::kN32_SkColorType;
                break;
            case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:
                mWideColorType = SkColorType::kRGBA_F16_SkColorType;
                break;
            default:
                LOG_ALWAYS_FATAL("Unreachable: unsupported pixel format.");
        }
    }
    // This method may have been called when the display config changed, so
    // sync with the current configuration.
    ADisplay* primaryDisplay = mDisplays[mPhysicalDisplayIndex];
    status_t status = ADisplay_getCurrentConfig(primaryDisplay, &mCurrentConfig);
    LOG_ALWAYS_FATAL_IF(status, "Failed to get display config, error %d", status);

    mWidth = ADisplayConfig_getWidth(mCurrentConfig);
    mHeight = ADisplayConfig_getHeight(mCurrentConfig);
    mDensity = ADisplayConfig_getDensity(mCurrentConfig);
    mVsyncPeriod = static_cast<int64_t>(1000000000 / ADisplayConfig_getFps(mCurrentConfig));
    mCompositorOffset = ADisplayConfig_getCompositorOffsetNanos(mCurrentConfig);
    mAppOffset = ADisplayConfig_getAppVsyncOffsetNanos(mCurrentConfig);
}

} /* namespace uirenderer */
} /* namespace android */
