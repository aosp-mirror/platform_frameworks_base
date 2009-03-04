/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_EGL_DISPLAY_SURFACE_H
#define ANDROID_EGL_DISPLAY_SURFACE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Timers.h>

#include <ui/EGLNativeSurface.h>

#include <pixelflinger/pixelflinger.h>
#include <linux/fb.h>

#include <EGL/egl.h>

struct copybit_device_t;
struct copybit_image_t;

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

class Region;
class Rect;

class EGLDisplaySurface : public EGLNativeSurface<EGLDisplaySurface>
{
public:
    EGLDisplaySurface();
    ~EGLDisplaySurface();
    
    int32_t getPageFlipCount() const;
    void    copyFrontToBack(const Region& copyback);
    void    copyFrontToImage(const copybit_image_t& dst);
    void    copyBackToImage(const copybit_image_t& dst);
    
    void        setSwapRectangle(int l, int t, int w, int h);

private:
    static void         hook_incRef(NativeWindowType window);
    static void         hook_decRef(NativeWindowType window);
    static uint32_t     hook_swapBuffers(NativeWindowType window);
     
            uint32_t    swapBuffers();

            status_t    mapFrameBuffer();

            enum {
                PAGE_FLIP = 0x00000001
            };
    GGLSurface          mFb[2];
    int                 mIndex;
    uint32_t            mFlags;
    size_t              mSize;
    fb_var_screeninfo   mInfo;
    fb_fix_screeninfo   mFinfo;
    int32_t             mPageFlipCount;
    nsecs_t             mTime;
    int32_t             mSwapCount;
    nsecs_t             mSleep;
    uint32_t            mFeatureFlags;
    copybit_device_t*   mBlitEngine;
};

// ---------------------------------------------------------------------------
}; // namespace android
// ---------------------------------------------------------------------------

#endif // ANDROID_EGL_DISPLAY_SURFACE_H

