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

#define LOG_TAG "Surface"
#include <utils/Log.h>

#include <android/native_window_jni.h>
#include <gui/Surface.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>

using namespace android;

ANativeWindow* ANativeWindow_fromSurface(JNIEnv* env, jobject surface) {
    sp<ANativeWindow> win = android_view_Surface_getNativeWindow(env, surface);
    if (win != NULL) {
        win->incStrong((void*)ANativeWindow_acquire);
    }
    return win.get();
}

void ANativeWindow_acquire(ANativeWindow* window) {
    window->incStrong((void*)ANativeWindow_acquire);
}

void ANativeWindow_release(ANativeWindow* window) {
    window->decStrong((void*)ANativeWindow_acquire);
}

static int32_t getWindowProp(ANativeWindow* window, int what) {
    int value;
    int res = window->query(window, what, &value);
    return res < 0 ? res : value;
}

int32_t ANativeWindow_getWidth(ANativeWindow* window) {
    return getWindowProp(window, NATIVE_WINDOW_WIDTH);
}

int32_t ANativeWindow_getHeight(ANativeWindow* window) {
    return getWindowProp(window, NATIVE_WINDOW_HEIGHT);
}

int32_t ANativeWindow_getFormat(ANativeWindow* window) {
    return getWindowProp(window, NATIVE_WINDOW_FORMAT);
}

int32_t ANativeWindow_setBuffersGeometry(ANativeWindow* window, int32_t width,
        int32_t height, int32_t format) {
    int32_t err = native_window_set_buffers_format(window, format);
    if (!err) {
        err = native_window_set_buffers_user_dimensions(window, width, height);
        if (!err) {
            int mode = NATIVE_WINDOW_SCALING_MODE_FREEZE;
            if (width && height) {
                mode = NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW;
            }
            err = native_window_set_scaling_mode(window, mode);
         }
    }
    return err;
}

int32_t ANativeWindow_lock(ANativeWindow* window, ANativeWindow_Buffer* outBuffer,
        ARect* inOutDirtyBounds) {
    return window->perform(window, NATIVE_WINDOW_LOCK, outBuffer, inOutDirtyBounds);
}

int32_t ANativeWindow_unlockAndPost(ANativeWindow* window) {
    return window->perform(window, NATIVE_WINDOW_UNLOCK_AND_POST);
}
