/*
 * Copyright (C) 2019 The Android Open Source Project
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

#define LOG_TAG "BLASTBufferQueue"

#include <nativehelper/JNIHelp.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include <gui/BLASTBufferQueue.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>

namespace android {

static jlong nativeCreate(JNIEnv* env, jclass clazz, jstring jName, jlong surfaceControl,
                          jlong width, jlong height, jboolean enableTripleBuffering) {
    String8 str8;
    if (jName) {
        const jchar* str16 = env->GetStringCritical(jName, nullptr);
        if (str16) {
            str8 = String8(reinterpret_cast<const char16_t*>(str16), env->GetStringLength(jName));
            env->ReleaseStringCritical(jName, str16);
            str16 = nullptr;
        }
    }
    std::string name = str8.string();
    sp<BLASTBufferQueue> queue =
            new BLASTBufferQueue(name, reinterpret_cast<SurfaceControl*>(surfaceControl), width,
                                 height, enableTripleBuffering);
    queue->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(queue.get());
}

static void nativeDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    queue->decStrong((void*)nativeCreate);
}

static jobject nativeGetSurface(JNIEnv* env, jclass clazz, jlong ptr,
                                jboolean includeSurfaceControlHandle) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    return android_view_Surface_createFromSurface(env,
                                                  queue->getSurface(includeSurfaceControlHandle));
}

static void nativeSetNextTransaction(JNIEnv* env, jclass clazz, jlong ptr, jlong transactionPtr) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionPtr);
    queue->setNextTransaction(transaction);
}

static void nativeUpdate(JNIEnv*env, jclass clazz, jlong ptr, jlong surfaceControl, jlong width, jlong height) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    queue->update(reinterpret_cast<SurfaceControl*>(surfaceControl), width, height);
}

static void nativeFlushShadowQueue(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    queue->flushShadowQueue();
}

static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativeCreate", "(Ljava/lang/String;JJJZ)J", (void*)nativeCreate},
        {"nativeGetSurface", "(JZ)Landroid/view/Surface;", (void*)nativeGetSurface},
        {"nativeDestroy", "(J)V", (void*)nativeDestroy},
        {"nativeSetNextTransaction", "(JJ)V", (void*)nativeSetNextTransaction},
        {"nativeUpdate", "(JJJJ)V", (void*)nativeUpdate},
        {"nativeFlushShadowQueue", "(J)V", (void*)nativeFlushShadowQueue}};

int register_android_graphics_BLASTBufferQueue(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/graphics/BLASTBufferQueue",
            gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    return 0;
}

} // namespace android
