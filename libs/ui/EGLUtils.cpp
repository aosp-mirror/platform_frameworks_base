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

#include <utils/Errors.h>

#include <ui/EGLUtils.h>

#include <EGL/egl.h>

#include <private/ui/android_natives_priv.h>

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

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
    
    int err;
    PixelFormatInfo fbFormatInfo;
    if ((err = getPixelFormatInfo(PixelFormat(format), &fbFormatInfo)) < 0) {
        return err;
    }

    // Get all the "potential match" configs...
    if (eglGetConfigs(dpy, NULL, 0, &numConfigs) == EGL_FALSE)
        return BAD_VALUE;

    EGLConfig* const configs = (EGLConfig*)malloc(sizeof(EGLConfig)*numConfigs);
    if (eglChooseConfig(dpy, attrs, configs, numConfigs, &n) == EGL_FALSE) {
        free(configs);
        return BAD_VALUE;
    }

    const int fbSzA = fbFormatInfo.getSize(PixelFormatInfo::INDEX_ALPHA);
    const int fbSzR = fbFormatInfo.getSize(PixelFormatInfo::INDEX_RED);
    const int fbSzG = fbFormatInfo.getSize(PixelFormatInfo::INDEX_GREEN);
    const int fbSzB = fbFormatInfo.getSize(PixelFormatInfo::INDEX_BLUE); 
    
    int i;
    EGLConfig config = NULL;
    for (i=0 ; i<n ; i++) {
        EGLint r,g,b,a;
        eglGetConfigAttrib(dpy, configs[i], EGL_RED_SIZE,   &r);
        eglGetConfigAttrib(dpy, configs[i], EGL_GREEN_SIZE, &g);
        eglGetConfigAttrib(dpy, configs[i], EGL_BLUE_SIZE,  &b);
        eglGetConfigAttrib(dpy, configs[i], EGL_ALPHA_SIZE, &a);
        if (fbSzA == a && fbSzR == r && fbSzG == g && fbSzB  == b) {
            config = configs[i];
            break;
        }
    }

    free(configs);
    
    if (i<n) {
        *outConfig = config;
        return NO_ERROR;
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
