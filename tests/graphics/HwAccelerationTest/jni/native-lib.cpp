/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <jni.h>

struct MyWrapper {
    MyWrapper(ANativeWindow* parent) {
        surfaceControl = ASurfaceControl_createFromWindow(parent, "PenLayer");
    }

    ~MyWrapper() { ASurfaceControl_release(surfaceControl); }

    void setBuffer(AHardwareBuffer* buffer) {
        ASurfaceTransaction* transaction = ASurfaceTransaction_create();
        ASurfaceTransaction_setBuffer(transaction, surfaceControl, buffer, -1);
        ASurfaceTransaction_setVisibility(transaction, surfaceControl,
                                          ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        ASurfaceTransaction_apply(transaction);
        ASurfaceTransaction_delete(transaction);
    }

    ASurfaceControl* surfaceControl = nullptr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_android_test_hwui_FrontBufferedLayer_nCreate(JNIEnv* env, jclass, jobject jSurface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, jSurface);
    MyWrapper* wrapper = new MyWrapper(window);
    ANativeWindow_release(window);
    return reinterpret_cast<jlong>(wrapper);
}

extern "C" JNIEXPORT void JNICALL
Java_com_android_test_hwui_FrontBufferedLayer_nDestroy(JNIEnv*, jclass, jlong ptr) {
    MyWrapper* wrapper = reinterpret_cast<MyWrapper*>(ptr);
    delete wrapper;
}

extern "C" JNIEXPORT void JNICALL Java_com_android_test_hwui_FrontBufferedLayer_nUpdateBuffer(
        JNIEnv* env, jclass, jlong ptr, jobject jbuffer) {
    MyWrapper* wrapper = reinterpret_cast<MyWrapper*>(ptr);
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, jbuffer);
    wrapper->setBuffer(buffer);
}