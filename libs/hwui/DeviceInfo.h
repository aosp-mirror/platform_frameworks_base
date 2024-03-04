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

#include <SkColorSpace.h>
#include <SkImageInfo.h>
#include <SkRefCnt.h>
#include <android/data_space.h>

#include <mutex>

#include "Properties.h"
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
    static int32_t getWidth() { return get()->mWidth; }
    static int32_t getHeight() { return get()->mHeight; }
    // Gets the density in density-independent pixels
    static float getDensity() { return sDensity.load(); }
    static int64_t getVsyncPeriod() { return get()->mVsyncPeriod; }
    static int64_t getCompositorOffset() { return get()->getCompositorOffsetInternal(); }
    static int64_t getAppOffset() { return get()->mAppVsyncOffsetNanos; }
    // Sets the density in density-independent pixels
    static void setDensity(float density) { sDensity.store(density); }
    static void setWidth(int32_t width) { get()->mWidth = width; }
    static void setHeight(int32_t height) { get()->mHeight = height; }
    static void setRefreshRate(float refreshRate) {
        get()->mVsyncPeriod = static_cast<int64_t>(1000000000 / refreshRate);
    }
    static void setPresentationDeadlineNanos(int64_t deadlineNanos) {
        get()->mPresentationDeadlineNanos = deadlineNanos;
    }
    static void setAppVsyncOffsetNanos(int64_t offsetNanos) {
        get()->mAppVsyncOffsetNanos = offsetNanos;
    }
    static void setWideColorDataspace(ADataSpace dataspace);

    static void setSupportFp16ForHdr(bool supportFp16ForHdr);
    static bool isSupportFp16ForHdr() {
        if (!Properties::hdr10bitPlus) {
            return false;
        }

        return get()->mSupportFp16ForHdr;
    };

    static void setSupportRgba10101010ForHdr(bool supportRgba10101010ForHdr);
    static bool isSupportRgba10101010ForHdr() {
        if (!Properties::hdr10bitPlus) {
            return false;
        }

        return get()->mSupportRgba10101010ForHdr;
    };

    static void setSupportMixedColorSpaces(bool supportMixedColorSpaces);
    static bool isSupportMixedColorSpaces() { return get()->mSupportMixedColorSpaces; };

    // this value is only valid after the GPU has been initialized and there is a valid graphics
    // context or if you are using the HWUI_NULL_GPU
    int maxTextureSize() const;
    sk_sp<SkColorSpace> getWideColorSpace() const { return mWideColorSpace; }
    SkColorType getWideColorType() {
        static std::once_flag kFlag;
        // lazily update display info from SF here, so that the call is performed by RenderThread.
        std::call_once(kFlag, [&, this]() { updateDisplayInfo(); });
        return mWideColorType;
    }

    // This method should be called whenever the display refresh rate changes.
    void onRefreshRateChanged(int64_t vsyncPeriod);

private:
    friend class renderthread::RenderThread;
    static void setMaxTextureSize(int maxTextureSize);
    void updateDisplayInfo();
    int64_t getCompositorOffsetInternal() const {
        // Assume that SF takes around a millisecond to latch buffers after
        // waking up
        return mVsyncPeriod - (mPresentationDeadlineNanos - 1000000);
    }

    DeviceInfo();
    ~DeviceInfo() = default;

    int mMaxTextureSize;
    sk_sp<SkColorSpace> mWideColorSpace = SkColorSpace::MakeSRGB();
    bool mSupportFp16ForHdr = false;
    bool mSupportRgba10101010ForHdr = false;
    bool mSupportMixedColorSpaces = false;
    SkColorType mWideColorType = SkColorType::kN32_SkColorType;
    int mDisplaysSize = 0;
    int mPhysicalDisplayIndex = -1;
    int32_t mWidth = 1080;
    int32_t mHeight = 1920;
    int64_t mVsyncPeriod = 16666666;
    // Magically corresponds with an sf offset of 0 for a sane default.
    int64_t mPresentationDeadlineNanos = 17666666;
    int64_t mAppVsyncOffsetNanos = 0;

    // Density is not retrieved from the ADisplay apis, so this may potentially
    // be called on multiple threads.
    // Unit is density-independent pixels
    static std::atomic<float> sDensity;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DEVICEINFO_H */
