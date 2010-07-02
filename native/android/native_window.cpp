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

#include <android/native_window.h>
#include <surfaceflinger/Surface.h>

using android::Surface;

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
    native_window_set_buffers_geometry(window, width, height, format);
    return 0;
}
