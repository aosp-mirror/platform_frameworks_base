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

#include <EGL/egl.h>
#include <EGL/android_natives.h>

#include <utils/threads.h>
#include <ui/Rect.h>

#include <pixelflinger/pixelflinger.h>


extern "C" EGLNativeWindowType android_createDisplaySurface(void);

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

class Surface;


class NativeBuffer 
    : public EGLNativeBase<
        android_native_buffer_t, 
        NativeBuffer, 
        LightRefBase<NativeBuffer>  >
{
public:
    NativeBuffer(int w, int h, int f, int u) : BASE() {
        android_native_buffer_t::width  = w;
        android_native_buffer_t::height = h;
        android_native_buffer_t::format = f;
        android_native_buffer_t::usage  = u;
        android_native_buffer_t::getHandle = getHandle;
    }
public:
    buffer_handle_t handle;
private:
    friend class LightRefBase<NativeBuffer>;    
    ~NativeBuffer() { }; // this class cannot be overloaded
    static int getHandle(android_native_buffer_t const * base, buffer_handle_t* handle) {
        *handle = getSelf(base)->handle;
        return 0;
    }
};

// ---------------------------------------------------------------------------

class FramebufferNativeWindow 
    : public EGLNativeBase<
        android_native_window_t, 
        FramebufferNativeWindow, 
        LightRefBase<FramebufferNativeWindow> >
{
public:
    FramebufferNativeWindow(); 

    framebuffer_device_t const * getDevice() const { return fbDev; } 

private:
    friend class LightRefBase<FramebufferNativeWindow>;    
    ~FramebufferNativeWindow(); // this class cannot be overloaded
    static void connect(android_native_window_t* window);
    static void disconnect(android_native_window_t* window);
    static int setSwapInterval(android_native_window_t* window, int interval);
    static int setSwapRectangle(android_native_window_t* window,
            int l, int t, int w, int h);
    static int dequeueBuffer(android_native_window_t* window, android_native_buffer_t** buffer);
    static int lockBuffer(android_native_window_t* window, android_native_buffer_t* buffer);
    static int queueBuffer(android_native_window_t* window, android_native_buffer_t* buffer);
    

    static inline FramebufferNativeWindow* getSelf(
            android_native_window_t* window) {
        FramebufferNativeWindow* self = 
            static_cast<FramebufferNativeWindow*>(window);
            return self;
    }

    static inline FramebufferNativeWindow* getSelf(
            android_native_base_t* window) {
        return getSelf(reinterpret_cast<android_native_window_t*>(window));
    }

    
    framebuffer_device_t* fbDev;
    alloc_device_t* grDev;

    sp<NativeBuffer> buffers[2];
    sp<NativeBuffer> front;
    
    Rect mDirty;

    mutable Mutex mutex;
    Condition mCondition;
    int32_t mNumBuffers;
    int32_t mNumFreeBuffers;
    int32_t mBufferHead;
};

// ---------------------------------------------------------------------------
}; // namespace android
// ---------------------------------------------------------------------------

#endif // ANDROID_EGL_NATIVE_WINDOW_SURFACE_H

