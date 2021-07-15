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
static std::map<uint32_t /* slot */, ANativeWindowBuffer*> sBuffers;

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_setSurface(JNIEnv* env, jclass,
                                                                     jobject surfaceObject) {
    sAnw = ANativeWindow_fromSurface(env, surfaceObject);
    assert(sAnw);
    android::sp<android::Surface> surface = static_cast<android::Surface*>(sAnw);
    surface->enableFrameTimestamps(true);
    surface->connect(NATIVE_WINDOW_API_CPU, nullptr, false);
    native_window_set_usage(sAnw, GRALLOC_USAGE_SW_WRITE_OFTEN);
    native_window_set_buffers_format(sAnw, HAL_PIXEL_FORMAT_RGBA_8888);
    return 0;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_waitUntilBufferDisplayed(
        JNIEnv*, jclass, jlong jFrameNumber, jint timeoutMs) {
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
            if (std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count() >
                timeoutMs) {
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

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_ANativeWindowSetBuffersTransform(
        JNIEnv* /* env */, jclass /* clazz */, jint transform) {
    assert(sAnw);
    return native_window_set_buffers_transform(sAnw, transform);
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_SurfaceSetScalingMode(JNIEnv* /* env */,
                                                                                jclass /* clazz */,
                                                                                jint scalingMode) {
    assert(sAnw);
    android::sp<android::Surface> surface = static_cast<android::Surface*>(sAnw);
    return surface->setScalingMode(scalingMode);
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_SurfaceDequeueBuffer(JNIEnv* /* env */,
                                                                               jclass /* clazz */,
                                                                               jint slot,
                                                                               jint timeoutMs) {
    assert(sAnw);
    ANativeWindowBuffer* anb;
    int fenceFd;
    int result = sAnw->dequeueBuffer(sAnw, &anb, &fenceFd);
    if (result != android::OK) {
        return result;
    }
    sBuffers[slot] = anb;
    if (timeoutMs == 0) {
        return android::OK;
    }
    android::sp<android::Fence> fence(new android::Fence(fenceFd));
    int waitResult = fence->wait(timeoutMs);
    if (waitResult != android::OK) {
        sAnw->cancelBuffer(sAnw, sBuffers[slot], -1);
        sBuffers[slot] = nullptr;
        return waitResult;
    }
    return 0;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_SurfaceCancelBuffer(JNIEnv* /* env */,
                                                                              jclass /* clazz */,
                                                                              jint slot) {
    assert(sAnw);
    assert(sBuffers[slot]);
    int result = sAnw->cancelBuffer(sAnw, sBuffers[slot], -1);
    sBuffers[slot] = nullptr;
    return result;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_drawBuffer(JNIEnv* env,
                                                                     jclass /* clazz */, jint slot,
                                                                     jintArray jintArrayColor) {
    assert(sAnw);
    assert(sBuffers[slot]);

    int* color = env->GetIntArrayElements(jintArrayColor, nullptr);

    ANativeWindowBuffer* buffer = sBuffers[slot];
    android::sp<android::GraphicBuffer> graphicBuffer(static_cast<android::GraphicBuffer*>(buffer));
    const android::Rect bounds(buffer->width, buffer->height);
    android::Region newDirtyRegion;
    newDirtyRegion.set(bounds);

    void* vaddr;
    int fenceFd = -1;
    graphicBuffer->lockAsync(GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN,
                             newDirtyRegion.bounds(), &vaddr, fenceFd);

    for (int32_t row = 0; row < buffer->height; row++) {
        uint8_t* dst = static_cast<uint8_t*>(vaddr) + (buffer->stride * row) * 4;
        for (int32_t column = 0; column < buffer->width; column++) {
            dst[0] = color[0];
            dst[1] = color[1];
            dst[2] = color[2];
            dst[3] = color[3];
            dst += 4;
        }
    }
    graphicBuffer->unlockAsync(&fenceFd);
    env->ReleaseIntArrayElements(jintArrayColor, color, JNI_ABORT);
    return 0;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_SurfaceQueueBuffer(JNIEnv* /* env */,
                                                                             jclass /* clazz */,
                                                                             jint slot,
                                                                             jboolean freeSlot) {
    assert(sAnw);
    assert(sBuffers[slot]);
    int result = sAnw->queueBuffer(sAnw, sBuffers[slot], -1);
    if (freeSlot) {
        sBuffers[slot] = nullptr;
    }
    return result;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_SurfaceSetAsyncMode(JNIEnv* /* env */,
                                                                              jclass /* clazz */,
                                                                              jboolean async) {
    assert(sAnw);
    android::sp<android::Surface> surface = static_cast<android::Surface*>(sAnw);
    return surface->setAsyncMode(async);
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_SurfaceSetDequeueTimeout(
        JNIEnv* /* env */, jclass /* clazz */, jlong timeoutMs) {
    assert(sAnw);
    android::sp<android::Surface> surface = static_cast<android::Surface*>(sAnw);
    return surface->setDequeueTimeout(timeoutMs);
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_SurfaceSetMaxDequeuedBufferCount(
        JNIEnv* /* env */, jclass /* clazz */, jint maxDequeuedBuffers) {
    assert(sAnw);
    android::sp<android::Surface> surface = static_cast<android::Surface*>(sAnw);
    return surface->setMaxDequeuedBufferCount(maxDequeuedBuffers);
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_NativeWindowSetBufferCount(
        JNIEnv* /* env */, jclass /* clazz */, jint count) {
    assert(sAnw);
    android::sp<android::Surface> surface = static_cast<android::Surface*>(sAnw);
    int result = native_window_set_buffer_count(sAnw, count);
    return result;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_NativeWindowSetSharedBufferMode(
        JNIEnv* /* env */, jclass /* clazz */, jboolean shared) {
    assert(sAnw);
    android::sp<android::Surface> surface = static_cast<android::Surface*>(sAnw);
    int result = native_window_set_shared_buffer_mode(sAnw, shared);
    return result;
}

JNIEXPORT jint JNICALL Java_com_android_test_SurfaceProxy_NativeWindowSetAutoRefresh(
        JNIEnv* /* env */, jclass /* clazz */, jboolean autoRefresh) {
    assert(sAnw);
    android::sp<android::Surface> surface = static_cast<android::Surface*>(sAnw);
    int result = native_window_set_auto_refresh(sAnw, autoRefresh);
    return result;
}
}