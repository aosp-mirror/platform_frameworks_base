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

#ifndef ANDROID_DISPLAY_HARDWARE_H
#define ANDROID_DISPLAY_HARDWARE_H

#include <stdlib.h>

#include <ui/PixelFormat.h>
#include <ui/Region.h>

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <pixelflinger/pixelflinger.h>

#include "GLExtensions.h"

#include "DisplayHardware/DisplayHardwareBase.h"

namespace android {

class FramebufferNativeWindow;
class HWComposer;

class DisplayHardware : public DisplayHardwareBase
{
public:
    enum {
        COPY_BITS_EXTENSION         = 0x00000008,
        BUFFER_PRESERVED            = 0x00010000,
        PARTIAL_UPDATES             = 0x00020000,   // video driver feature
        SLOW_CONFIG                 = 0x00040000,   // software
        SWAP_RECTANGLE              = 0x00080000,
    };

    DisplayHardware(
            const sp<SurfaceFlinger>& flinger,
            uint32_t displayIndex);

    ~DisplayHardware();

    void releaseScreen() const;
    void acquireScreen() const;

    // Flip the front and back buffers if the back buffer is "dirty".  Might
    // be instantaneous, might involve copying the frame buffer around.
    void flip(const Region& dirty) const;

    float       getDpiX() const;
    float       getDpiY() const;
    float       getRefreshRate() const;
    float       getDensity() const;
    int         getWidth() const;
    int         getHeight() const;
    PixelFormat getFormat() const;
    uint32_t    getFlags() const;
    void        makeCurrent() const;
    uint32_t    getMaxTextureSize() const;
    uint32_t    getMaxViewportDims() const;

    uint32_t getPageFlipCount() const;
    EGLDisplay getEGLDisplay() const { return mDisplay; }

    void dump(String8& res) const;

    // Hardware Composer
    HWComposer& getHwComposer() const;
    
    status_t compositionComplete() const;
    
    Rect bounds() const {
        return Rect(mWidth, mHeight);
    }

    // only for debugging
    int getCurrentBufferIndex() const;

private:
    void init(uint32_t displayIndex) __attribute__((noinline));
    void fini() __attribute__((noinline));

    sp<SurfaceFlinger> mFlinger;
    EGLDisplay      mDisplay;
    EGLSurface      mSurface;
    EGLContext      mContext;
    EGLConfig       mConfig;
    float           mDpiX;
    float           mDpiY;
    float           mRefreshRate;
    float           mDensity;
    int             mWidth;
    int             mHeight;
    PixelFormat     mFormat;
    uint32_t        mFlags;
    mutable uint32_t mPageFlipCount;
    GLint           mMaxViewportDims[2];
    GLint           mMaxTextureSize;
    
    HWComposer*     mHwc;

    sp<FramebufferNativeWindow> mNativeWindow;
};

}; // namespace android

#endif // ANDROID_DISPLAY_HARDWARE_H
