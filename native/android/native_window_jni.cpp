/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <android/native_window_jni.h>
#include <android/native_window.h>
#include <system/window.h>

#include <gui/Surface.h>
#include <utils/StrongPointer.h>

#include <android_runtime/android_view_Surface.h>

using namespace android;

ANativeWindow* ANativeWindow_fromSurface(JNIEnv* env, jobject surface) {
    sp<ANativeWindow> win = android_view_Surface_getNativeWindow(env, surface);
    if (win != NULL) {
        win->incStrong((void*)ANativeWindow_fromSurface);
    }
    return win.get();
}

jobject ANativeWindow_toSurface(JNIEnv* env, ANativeWindow* window) {
    if (window == NULL) {
        return NULL;
    }
    sp<Surface> surface = static_cast<Surface*>(window);
    return android_view_Surface_createFromSurface(env, surface);
}
