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
#include <android/hardware_buffer.h>
#include <apex/display.h>
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
}

void DeviceInfo::updateDisplayInfo() {
    if (Properties::isolatedProcess) {
        return;
    }

    ADisplay** displays;
    int size = ADisplay_acquirePhysicalDisplays(&displays);

    if (size <= 0) {
        LOG_ALWAYS_FATAL("Failed to acquire physical displays for WCG support!");
    }

    for (int i = 0; i < size; ++i) {
        // Pick the first internal display for querying the display type
        // In practice this is controlled by a sysprop so it doesn't really
        // matter which display we use.
        if (ADisplay_getDisplayType(displays[i]) == DISPLAY_TYPE_INTERNAL) {
            // We get the dataspace from DisplayManager already. Allocate space
            // for the result here but we don't actually care about using it.
            ADataSpace dataspace;
            AHardwareBuffer_Format pixelFormat;
            ADisplay_getPreferredWideColorFormat(displays[i], &dataspace, &pixelFormat);

            if (pixelFormat == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
                mWideColorType = SkColorType::kN32_SkColorType;
            } else if (pixelFormat == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) {
                mWideColorType = SkColorType::kRGBA_F16_SkColorType;
            } else {
                LOG_ALWAYS_FATAL("Unreachable: unsupported pixel format: %d", pixelFormat);
            }
            ADisplay_release(displays);
            return;
        }
    }
    LOG_ALWAYS_FATAL("Failed to find a valid physical display for WCG support!");
}

int DeviceInfo::maxTextureSize() const {
    LOG_ALWAYS_FATAL_IF(mMaxTextureSize < 0, "MaxTextureSize has not been initialized yet.");
    return mMaxTextureSize;
}

void DeviceInfo::setMaxTextureSize(int maxTextureSize) {
    DeviceInfo::get()->mMaxTextureSize = maxTextureSize;
}

void DeviceInfo::setWideColorDataspace(ADataSpace dataspace) {
    switch (dataspace) {
        case ADATASPACE_DISPLAY_P3:
            get()->mWideColorSpace =
                    SkColorSpace::MakeRGB(SkNamedTransferFn::kSRGB, SkNamedGamut::kDisplayP3);
            break;
        case ADATASPACE_SCRGB:
            get()->mWideColorSpace = SkColorSpace::MakeSRGB();
            break;
        default:
            ALOGW("Unknown dataspace %d", dataspace);
            // Treat unknown dataspaces as sRGB, so fall through
            [[fallthrough]];
        case ADATASPACE_SRGB:
            // when sRGB is returned, it means wide color gamut is not supported.
            get()->mWideColorSpace = SkColorSpace::MakeSRGB();
            break;
    }
}

void DeviceInfo::setSupportFp16ForHdr(bool supportFp16ForHdr) {
    get()->mSupportFp16ForHdr = supportFp16ForHdr;
}

void DeviceInfo::setSupportMixedColorSpaces(bool supportMixedColorSpaces) {
    get()->mSupportMixedColorSpaces = supportMixedColorSpaces;
}

void DeviceInfo::onRefreshRateChanged(int64_t vsyncPeriod) {
    mVsyncPeriod = vsyncPeriod;
}

std::atomic<float> DeviceInfo::sDensity = 2.0;

} /* namespace uirenderer */
} /* namespace android */
