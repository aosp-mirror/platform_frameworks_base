/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/window.h>
#include <gui/Surface.h>
#include <jni.h>
#include <system/window.h>
#include <utils/RefBase.h>
#include <cassert>
#include <chrono>
#include <thread>

#define TAG "SurfaceViewBufferTests"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {
int i = 0;
static ANativeWindow* sAnw;

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_setSurface(JNIEnv* env, jclass,
                                                                     jobject surfaceObject) {
    sAnw = ANativeWindow_fromSurface(env, surfaceObject);
    assert(sAnw);
    android::sp<android::Surface> surface = static_cast<android::Surface*>(sAnw);
    surface->enableFrameTimestamps(true);
    return 0;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_waitUntilBufferDisplayed(
        JNIEnv*, jclass, jint jFrameNumber, jint timeoutSec) {
    using namespace std::chrono_literals;
    assert(sAnw);
    android::sp<android::Surface> surface = static_cast<android::Surface*>(sAnw);

    uint64_t frameNumber = static_cast<uint64_t>(jFrameNumber);
    nsecs_t outRequestedPresentTime, outAcquireTime, outLatchTime, outFirstRefreshStartTime;
    nsecs_t outLastRefreshStartTime, outGlCompositionDoneTime, outDequeueReadyTime;
    nsecs_t outDisplayPresentTime = -1;
    nsecs_t outReleaseTime;

    auto start = std::chrono::steady_clock::now();
    while (outDisplayPresentTime < 0) {
        std::this_thread::sleep_for(8ms);
        surface->getFrameTimestamps(frameNumber, &outRequestedPresentTime, &outAcquireTime,
                                    &outLatchTime, &outFirstRefreshStartTime,
                                    &outLastRefreshStartTime, &outGlCompositionDoneTime,
                                    &outDisplayPresentTime, &outDequeueReadyTime, &outReleaseTime);
        if (outDisplayPresentTime < 0) {
            auto end = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::seconds>(end - start).count() >
                timeoutSec) {
                return -1;
            }
        }
    }
    return 0;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_draw(JNIEnv*, jclass) {
    assert(sAnw);
    ANativeWindow_Buffer outBuffer;
    ANativeWindow_lock(sAnw, &outBuffer, nullptr);
    return 0;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_ANativeWindowLock(JNIEnv*, jclass) {
    assert(sAnw);
    ANativeWindow_Buffer outBuffer;
    ANativeWindow_lock(sAnw, &outBuffer, nullptr);
    return 0;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_ANativeWindowUnlockAndPost(JNIEnv*,
                                                                                     jclass) {
    assert(sAnw);
    ANativeWindow_unlockAndPost(sAnw);
    return 0;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_ANativeWindowSetBuffersGeometry(
        JNIEnv* /* env */, jclass /* clazz */, jobject /* surfaceObject */, jint w, jint h,
        jint format) {
    assert(sAnw);
    return ANativeWindow_setBuffersGeometry(sAnw, w, h, format);
}
}