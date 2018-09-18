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

#include "Properties.h"

#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>

#include <mutex>
#include <thread>

#include <log/log.h>

namespace android {
namespace uirenderer {

static constexpr android::DisplayInfo sDummyDisplay{
        1080,   // w
        1920,   // h
        320.0,  // xdpi
        320.0,  // ydpi
        60.0,   // fps
        2.0,    // density
        0,      // orientation
        false,  // secure?
        0,      // appVsyncOffset
        0,      // presentationDeadline
        1080,   // viewportW
        1920,   // viewportH
};

const DeviceInfo* DeviceInfo::get() {
        static DeviceInfo sDeviceInfo;
        return &sDeviceInfo;
}

DisplayInfo QueryDisplayInfo() {
    if (Properties::isolatedProcess) {
        return sDummyDisplay;
    }

    DisplayInfo displayInfo;
    sp<IBinder> dtoken(SurfaceComposerClient::getBuiltInDisplay(ISurfaceComposer::eDisplayIdMain));
    status_t status = SurfaceComposerClient::getDisplayInfo(dtoken, &displayInfo);
    LOG_ALWAYS_FATAL_IF(status, "Failed to get display info, error %d", status);
    return displayInfo;
}

void QueryCompositionPreference(ui::Dataspace* dataSpace,
                                ui::PixelFormat* pixelFormat) {
    if (Properties::isolatedProcess) {
        *dataSpace = ui::Dataspace::V0_SRGB;
        *pixelFormat = ui::PixelFormat::RGBA_8888;
    }

    status_t status =
            SurfaceComposerClient::getCompositionPreference(dataSpace, pixelFormat);
    LOG_ALWAYS_FATAL_IF(status, "Failed to get composition preference, error %d", status);
}

DeviceInfo::DeviceInfo() {
#if HWUI_NULL_GPU
        mMaxTextureSize = NULL_GPU_MAX_TEXTURE_SIZE;
#else
        mMaxTextureSize = -1;
#endif
    mDisplayInfo = QueryDisplayInfo();
    QueryCompositionPreference(&mTargetDataSpace, &mTargetPixelFormat);
}

int DeviceInfo::maxTextureSize() const {
    LOG_ALWAYS_FATAL_IF(mMaxTextureSize < 0, "MaxTextureSize has not been initialized yet.");
    return mMaxTextureSize;
}

void DeviceInfo::setMaxTextureSize(int maxTextureSize) {
    const_cast<DeviceInfo*>(DeviceInfo::get())->mMaxTextureSize = maxTextureSize;
}

} /* namespace uirenderer */
} /* namespace android */
