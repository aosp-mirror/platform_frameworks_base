/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_SIM_EGL_SURFACE_H
#define ANDROID_SIM_EGL_SURFACE_H

#include <stdint.h>
#include <errno.h>
#include <sys/types.h>

#include <GLES/eglnatives.h>

#include <linux/fb.h>

typedef struct {
    ssize_t    version;    // always set to sizeof(GGLSurface)
    uint32_t   width;      // width in pixels
    uint32_t   height;     // height in pixels
    int32_t    stride;     // stride in pixels
    uint8_t*   data;       // pointer to the bits
    uint8_t    format;     // pixel format
    uint8_t    rfu[3];     // must be zero
    void*      reserved;
} GGLSurface;

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

template <class TYPE>
class EGLNativeSurface : public egl_native_window_t
{
public:
    EGLNativeSurface() : mCount(0) { 
        memset(egl_native_window_t::reserved, 0, 
                sizeof(egl_native_window_t::reserved));
        memset(egl_native_window_t::reserved_proc, 0, 
                sizeof(egl_native_window_t::reserved_proc));
        memset(egl_native_window_t::oem, 0, 
                sizeof(egl_native_window_t::oem));
    }
    inline void incStrong(void*) const {
        /* in a real implementation, the inc must be atomic */
        mCount++;
    }
    inline void decStrong(void*) const {
        /* in a real implementation, the dec must be atomic */
        if (--mCount == 1) {
             delete static_cast<const TYPE*>(this);
         }
    }
protected:
    EGLNativeSurface& operator = (const EGLNativeSurface& rhs);
    EGLNativeSurface(const EGLNativeSurface& rhs);
    inline ~EGLNativeSurface() { };
    mutable volatile int32_t mCount;
};


class EGLDisplaySurface : public EGLNativeSurface<EGLDisplaySurface>
{
public:
    EGLDisplaySurface();
    ~EGLDisplaySurface();

    int32_t getPageFlipCount() const;

private:
    static void         hook_incRef(NativeWindowType window);
    static void         hook_decRef(NativeWindowType window);
    static uint32_t     hook_swapBuffers(NativeWindowType window);
    static void         hook_setSwapRectangle(NativeWindowType window, int l, int t, int w, int h);
    static uint32_t     hook_nextBuffer(NativeWindowType window);

    uint32_t    swapBuffers();
    uint32_t    nextBuffer();
    void        setSwapRectangle(int l, int t, int w, int h);

    int    mapFrameBuffer();

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
    int32_t             mSwapCount;
    uint32_t            mFeatureFlags;
};

// ---------------------------------------------------------------------------
}; // namespace android
// ---------------------------------------------------------------------------

#endif // ANDROID_SIM_EGL_SURFACE_H

