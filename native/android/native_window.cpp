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
#include <surfaceflinger/Surface.h>
#include <android_runtime/android_view_Surface.h>

using namespace android;

ANativeWindow* ANativeWindow_fromSurface(JNIEnv* env, jobject surface) {
    sp<ANativeWindow> win = android_Surface_getNativeWindow(env, surface);
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
        int32_t height) {
    native_window_set_buffers_geometry(window, width, height, 0);
    return 0;
}

int32_t ANativeWindow_lock(ANativeWindow* window, ANativeWindow_Buffer* outBuffer,
        ARect* inOutDirtyBounds) {
    Region dirtyRegion;
    Region* dirtyParam = NULL;
    if (inOutDirtyBounds != NULL) {
        dirtyRegion.set(*(Rect*)inOutDirtyBounds);
        dirtyParam = &dirtyRegion;
    }
    
    Surface::SurfaceInfo info;
    status_t res = static_cast<Surface*>(window)->lock(&info, dirtyParam);
    if (res != OK) {
        return -1;
    }
    
    outBuffer->width = (int32_t)info.w;
    outBuffer->height = (int32_t)info.h;
    outBuffer->stride = (int32_t)info.s;
    outBuffer->format = (int32_t)info.format;
    outBuffer->bits = info.bits;
    
    if (inOutDirtyBounds != NULL) {
        *inOutDirtyBounds = dirtyRegion.getBounds();
    }
    
    return 0;
}

int32_t ANativeWindow_unlockAndPost(ANativeWindow* window) {
    status_t res = static_cast<Surface*>(window)->unlockAndPost();
    return res == android::OK ? 0 : -1;
}
