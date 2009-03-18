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

#ifndef ANDROID_EGL_NATIVE_SURFACE_H
#define ANDROID_EGL_NATIVE_SURFACE_H

#include <stdint.h>
#include <sys/types.h>

#include <cutils/atomic.h>
#include <utils/RefBase.h>

#include <EGL/eglnatives.h>

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

template <class TYPE>
class EGLNativeSurface : public egl_native_window_t, public LightRefBase<TYPE>
{
public:
    EGLNativeSurface() { 
        memset(egl_native_window_t::reserved, 0, 
                sizeof(egl_native_window_t::reserved));
        memset(egl_native_window_t::reserved_proc, 0, 
                sizeof(egl_native_window_t::reserved_proc));
        memset(egl_native_window_t::oem, 0, 
                sizeof(egl_native_window_t::oem));
    }
protected:
    EGLNativeSurface& operator = (const EGLNativeSurface& rhs);
    EGLNativeSurface(const EGLNativeSurface& rhs);
    inline ~EGLNativeSurface() { };
};

// ---------------------------------------------------------------------------
}; // namespace android
// ---------------------------------------------------------------------------

#endif // ANDROID_EGL_SURFACE_H

