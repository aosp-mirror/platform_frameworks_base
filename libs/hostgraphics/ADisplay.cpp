/*
 * Copyright 2024 The Android Open Source Project
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

#include <apex/display.h>
#include <utils/Errors.h>

namespace android::display::impl {

/**
 * Implementation of ADisplayConfig
 */
struct DisplayConfigImpl {
    /**
     * The width in pixels of the display configuration.
     */
    int32_t width{1080};

    /**
     * The height in pixels of the display configuration.
     */

    int32_t height{1920};

    /**
     * The refresh rate of the display configuration, in frames per second.
     */
    float fps{60.0};

    /**
     * The vsync offset at which surfaceflinger runs, in nanoseconds.
     */
    int64_t sfOffset{0};

    /**
     * The vsync offset at which applications run, in nanoseconds.
     */
    int64_t appOffset{0};
};

// DisplayConfigImpl allocation is not managed through C++ memory apis, so
// preventing calling the destructor here.
static_assert(std::is_trivially_destructible<DisplayConfigImpl>::value);

/**
 * Implementation of ADisplay
 */
struct DisplayImpl {
    /**
     * The type of the display, i.e. whether it is an internal or external
     * display.
     */
    ADisplayType type;

    /**
     * The preferred WCG dataspace
     */
    ADataSpace wcgDataspace;

    /**
     * The preferred WCG pixel format
     */
    AHardwareBuffer_Format wcgPixelFormat;

    /**
     * The config for this display.
     */
    DisplayConfigImpl config;
};

// DisplayImpl allocation is not managed through C++ memory apis, so
// preventing calling the destructor here.
static_assert(std::is_trivially_destructible<DisplayImpl>::value);

} // namespace android::display::impl

using namespace android;
using namespace android::display::impl;

namespace android {

int ADisplay_acquirePhysicalDisplays(ADisplay*** outDisplays) {
    // This is running on host, so there are no physical displays available.
    // Create 1 fake display instead.
    DisplayImpl** const impls =
            reinterpret_cast<DisplayImpl**>(malloc(sizeof(DisplayImpl*) + sizeof(DisplayImpl)));
    DisplayImpl* const displayData = reinterpret_cast<DisplayImpl*>(impls + 1);

    displayData[0] =
            DisplayImpl{ADisplayType::DISPLAY_TYPE_INTERNAL, ADataSpace::ADATASPACE_UNKNOWN,
                        AHardwareBuffer_Format::AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
                        DisplayConfigImpl()};
    impls[0] = displayData;
    *outDisplays = reinterpret_cast<ADisplay**>(impls);
    return 1;
}

void ADisplay_release(ADisplay** displays) {
    if (displays == nullptr) {
        return;
    }
    free(displays);
}

float ADisplay_getMaxSupportedFps(ADisplay* display) {
    DisplayImpl* impl = reinterpret_cast<DisplayImpl*>(display);
    return impl->config.fps;
}

ADisplayType ADisplay_getDisplayType(ADisplay* display) {
    return reinterpret_cast<DisplayImpl*>(display)->type;
}

void ADisplay_getPreferredWideColorFormat(ADisplay* display, ADataSpace* outDataspace,
                                          AHardwareBuffer_Format* outPixelFormat) {
    DisplayImpl* impl = reinterpret_cast<DisplayImpl*>(display);
    *outDataspace = impl->wcgDataspace;
    *outPixelFormat = impl->wcgPixelFormat;
}

int ADisplay_getCurrentConfig(ADisplay* display, ADisplayConfig** outConfig) {
    DisplayImpl* impl = reinterpret_cast<DisplayImpl*>(display);
    *outConfig = reinterpret_cast<ADisplayConfig*>(&impl->config);
    return OK;
}

int32_t ADisplayConfig_getWidth(ADisplayConfig* config) {
    return reinterpret_cast<DisplayConfigImpl*>(config)->width;
}

int32_t ADisplayConfig_getHeight(ADisplayConfig* config) {
    return reinterpret_cast<DisplayConfigImpl*>(config)->height;
}

float ADisplayConfig_getFps(ADisplayConfig* config) {
    return reinterpret_cast<DisplayConfigImpl*>(config)->fps;
}

int64_t ADisplayConfig_getCompositorOffsetNanos(ADisplayConfig* config) {
    return reinterpret_cast<DisplayConfigImpl*>(config)->sfOffset;
}

int64_t ADisplayConfig_getAppVsyncOffsetNanos(ADisplayConfig* config) {
    return reinterpret_cast<DisplayConfigImpl*>(config)->appOffset;
}

} // namespace android
