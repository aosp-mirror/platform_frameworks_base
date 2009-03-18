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

#ifndef ANDROID_EGL_NATIVE_WINDOW_SURFACE_H
#define ANDROID_EGL_NATIVE_WINDOW_SURFACE_H

#include <stdint.h>
#include <sys/types.h>
#include <ui/EGLNativeSurface.h>
#include <EGL/egl.h>

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

class Surface;

class EGLNativeWindowSurface : public EGLNativeSurface<EGLNativeWindowSurface>
{
public:
    EGLNativeWindowSurface(const sp<Surface>& surface);
    ~EGLNativeWindowSurface();

    void        setSwapRectangle(int l, int t, int w, int h);

private:
    static void         hook_incRef(NativeWindowType window);
    static void         hook_decRef(NativeWindowType window);
    static uint32_t     hook_swapBuffers(NativeWindowType window);
    static void         hook_connect(NativeWindowType window);
    static void         hook_disconnect(NativeWindowType window);

            uint32_t    swapBuffers();
            void        connect();
            void        disconnect();
            
            sp<Surface> mSurface;
            bool        mConnected;
};

// ---------------------------------------------------------------------------
}; // namespace android
// ---------------------------------------------------------------------------

#endif // ANDROID_EGL_NATIVE_WINDOW_SURFACE_H

