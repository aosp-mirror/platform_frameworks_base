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
#include <gui/SurfaceComposerClient.h>
#include <log/log.h>
#include <ui/GraphicTypes.h>

#include <mutex>
#include <thread>

#include "Properties.h"

namespace android {
namespace uirenderer {

DeviceInfo* DeviceInfo::get() {
    static DeviceInfo sDeviceInfo;
    return &sDeviceInfo;
}

static void queryWideColorGamutPreference(sk_sp<SkColorSpace>* colorSpace, SkColorType* colorType) {
    if (Properties::isolatedProcess) {
        *colorSpace = SkColorSpace::MakeSRGB();
        *colorType = SkColorType::kN32_SkColorType;
        return;
    }
    ui::Dataspace defaultDataspace, wcgDataspace;
    ui::PixelFormat defaultPixelFormat, wcgPixelFormat;
    status_t status =
        SurfaceComposerClient::getCompositionPreference(&defaultDataspace, &defaultPixelFormat,
                                                        &wcgDataspace, &wcgPixelFormat);
    LOG_ALWAYS_FATAL_IF(status, "Failed to get composition preference, error %d", status);
    switch (wcgDataspace) {
        case ui::Dataspace::DISPLAY_P3:
            *colorSpace = SkColorSpace::MakeRGB(SkNamedTransferFn::kSRGB, SkNamedGamut::kDCIP3);
            break;
        case ui::Dataspace::V0_SCRGB:
            *colorSpace = SkColorSpace::MakeSRGB();
            break;
        case ui::Dataspace::V0_SRGB:
            // when sRGB is returned, it means wide color gamut is not supported.
            *colorSpace = SkColorSpace::MakeSRGB();
            break;
        default:
            LOG_ALWAYS_FATAL("Unreachable: unsupported wide color space.");
    }
    switch (wcgPixelFormat) {
        case ui::PixelFormat::RGBA_8888:
            *colorType = SkColorType::kN32_SkColorType;
            break;
        case ui::PixelFormat::RGBA_FP16:
            *colorType = SkColorType::kRGBA_F16_SkColorType;
            break;
        default:
            LOG_ALWAYS_FATAL("Unreachable: unsupported pixel format.");
    }
}

DeviceInfo::DeviceInfo() {
#if HWUI_NULL_GPU
        mMaxTextureSize = NULL_GPU_MAX_TEXTURE_SIZE;
#else
        mMaxTextureSize = -1;
#endif
        updateDisplayInfo();
        queryWideColorGamutPreference(&mWideColorSpace, &mWideColorType);
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

void DeviceInfo::onDisplayConfigChanged() {
    updateDisplayInfo();
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
        mMaxRefreshRate = ADisplay_getMaxSupportedFps(mDisplays[mPhysicalDisplayIndex]);
    }
    status_t status = ADisplay_getCurrentConfig(mDisplays[mPhysicalDisplayIndex], &mCurrentConfig);
    LOG_ALWAYS_FATAL_IF(status, "Failed to get display config, error %d", status);
    mWidth = ADisplayConfig_getWidth(mCurrentConfig);
    mHeight = ADisplayConfig_getHeight(mCurrentConfig);
    mDensity = ADisplayConfig_getDensity(mCurrentConfig);
    mRefreshRate = ADisplayConfig_getFps(mCurrentConfig);
    mCompositorOffset = ADisplayConfig_getCompositorOffsetNanos(mCurrentConfig);
    mAppOffset = ADisplayConfig_getAppVsyncOffsetNanos(mCurrentConfig);
}

} /* namespace uirenderer */
} /* namespace android */
