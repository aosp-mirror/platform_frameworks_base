/*
 * Copyright (C) 2009 The Android Open Source Project
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


#define LOG_TAG "EGLUtils"

#include <cutils/log.h>
#include <utils/Errors.h>

#include <ui/EGLUtils.h>

#include <EGL/egl.h>

#include <system/graphics.h>

#include <private/ui/android_natives_priv.h>

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

const char *EGLUtils::strerror(EGLint err)
{
    switch (err){
        case EGL_SUCCESS:           return "EGL_SUCCESS";
        case EGL_NOT_INITIALIZED:   return "EGL_NOT_INITIALIZED";
        case EGL_BAD_ACCESS:        return "EGL_BAD_ACCESS";
        case EGL_BAD_ALLOC:         return "EGL_BAD_ALLOC";
        case EGL_BAD_ATTRIBUTE:     return "EGL_BAD_ATTRIBUTE";
        case EGL_BAD_CONFIG:        return "EGL_BAD_CONFIG";
        case EGL_BAD_CONTEXT:       return "EGL_BAD_CONTEXT";
        case EGL_BAD_CURRENT_SURFACE: return "EGL_BAD_CURRENT_SURFACE";
        case EGL_BAD_DISPLAY:       return "EGL_BAD_DISPLAY";
        case EGL_BAD_MATCH:         return "EGL_BAD_MATCH";
        case EGL_BAD_NATIVE_PIXMAP: return "EGL_BAD_NATIVE_PIXMAP";
        case EGL_BAD_NATIVE_WINDOW: return "EGL_BAD_NATIVE_WINDOW";
        case EGL_BAD_PARAMETER:     return "EGL_BAD_PARAMETER";
        case EGL_BAD_SURFACE:       return "EGL_BAD_SURFACE";
        case EGL_CONTEXT_LOST:      return "EGL_CONTEXT_LOST";
        default: return "UNKNOWN";
    }
}

status_t EGLUtils::selectConfigForPixelFormat(
        EGLDisplay dpy,
        EGLint const* attrs,
        PixelFormat format,
        EGLConfig* outConfig)
{
    EGLint numConfigs = -1, n=0;

    if (!attrs)
        return BAD_VALUE;

    if (outConfig == NULL)
        return BAD_VALUE;
    
    // Get all the "potential match" configs...
    if (eglChooseConfig(dpy, attrs, 0, 0, &numConfigs) == EGL_FALSE)
        return BAD_VALUE;

    if (numConfigs) {
        EGLConfig* const configs = new EGLConfig[numConfigs];
        if (eglChooseConfig(dpy, attrs, configs, numConfigs, &n) == EGL_FALSE) {
            delete [] configs;
            return BAD_VALUE;
        }

        bool hasAlpha = false;
        switch (format) {
            case HAL_PIXEL_FORMAT_RGBA_8888:
            case HAL_PIXEL_FORMAT_BGRA_8888:
            case HAL_PIXEL_FORMAT_RGBA_5551:
            case HAL_PIXEL_FORMAT_RGBA_4444:
                hasAlpha = true;
                break;
        }

        // The first config is guaranteed to over-satisfy the constraints
        EGLConfig config = configs[0];

        // go through the list and skip configs that over-satisfy our needs
        int i;
        for (i=0 ; i<n ; i++) {
            if (!hasAlpha) {
                EGLint alphaSize;
                eglGetConfigAttrib(dpy, configs[i], EGL_ALPHA_SIZE, &alphaSize);
                if (alphaSize > 0) {
                    continue;
                }
            }
            config = configs[i];
            break;
        }

        delete [] configs;

        if (i<n) {
            *outConfig = config;
            return NO_ERROR;
        }
    }

    return NAME_NOT_FOUND;
}

status_t EGLUtils::selectConfigForNativeWindow(
        EGLDisplay dpy,
        EGLint const* attrs,
        EGLNativeWindowType window,
        EGLConfig* outConfig)
{
    int err;
    int format;
    
    if (!window)
        return BAD_VALUE;
    
    if ((err = window->query(window, NATIVE_WINDOW_FORMAT, &format)) < 0) {
        return err;
    }

    return selectConfigForPixelFormat(dpy, attrs, format, outConfig);
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------
