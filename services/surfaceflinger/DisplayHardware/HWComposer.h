/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_SF_HWCOMPOSER_H
#define ANDROID_SF_HWCOMPOSER_H

#include <stdint.h>
#include <sys/types.h>

#include <EGL/egl.h>

#include <hardware/hwcomposer.h>

#include <utils/StrongPointer.h>

namespace android {
// ---------------------------------------------------------------------------

class String8;
class SurfaceFlinger;

class HWComposer
{
public:

    HWComposer(const sp<SurfaceFlinger>& flinger);
    ~HWComposer();

    status_t initCheck() const;

    // tells the HAL what the framebuffer is
    void setFrameBuffer(EGLDisplay dpy, EGLSurface sur);

    // create a work list for numLayers layer
    status_t createWorkList(size_t numLayers);

    // Asks the HAL what it can do
    status_t prepare() const;

    // commits the list
    status_t commit() const;

    // release hardware resources
    status_t release() const;

    size_t getNumLayers() const;
    hwc_layer_t* getLayers() const;

    // for debugging
    void dump(String8& out, char* scratch, size_t SIZE) const;

private:
    struct cb_context {
        hwc_procs_t procs;
        HWComposer* hwc;
    };
    static void hook_invalidate(struct hwc_procs* procs);
    void invalidate();

    sp<SurfaceFlinger>      mFlinger;
    hw_module_t const*      mModule;
    hwc_composer_device_t*  mHwc;
    hwc_layer_list_t*       mList;
    size_t                  mCapacity;
    hwc_display_t           mDpy;
    hwc_surface_t           mSur;
    cb_context              mCBContext;
};


// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SF_HWCOMPOSER_H
