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
#include "core_jni_helpers.h"

namespace android {

static jlong nativeCreate(JNIEnv* env, jclass clazz, jstring jName) {
    ScopedUtfChars name(env, jName);
    sp<BLASTBufferQueue> queue = new BLASTBufferQueue(name.c_str());
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

static void nativeSetSyncTransaction(JNIEnv* env, jclass clazz, jlong ptr, jlong transactionPtr,
                                     jboolean acquireSingleBuffer) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionPtr);
    queue->setSyncTransaction(transaction, acquireSingleBuffer);
}

static void nativeUpdate(JNIEnv* env, jclass clazz, jlong ptr, jlong surfaceControl, jlong width,
                         jlong height, jint format, jlong transactionPtr) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionPtr);
    queue->update(reinterpret_cast<SurfaceControl*>(surfaceControl), width, height, format,
                  transaction);
}

static void nativeMergeWithNextTransaction(JNIEnv*, jclass clazz, jlong ptr, jlong transactionPtr,
                                           jlong framenumber) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionPtr);
    queue->mergeWithNextTransaction(transaction, CC_UNLIKELY(framenumber < 0) ? 0 : framenumber);
}

static jlong nativeGetLastAcquiredFrameNum(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    return queue->getLastAcquiredFrameNum();
}

static void nativeApplyPendingTransactions(JNIEnv* env, jclass clazz, jlong ptr, jlong frameNum) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    queue->applyPendingTransactions(frameNum);
}

static bool nativeIsSameSurfaceControl(JNIEnv* env, jclass clazz, jlong ptr, jlong surfaceControl) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    return queue->isSameSurfaceControl(reinterpret_cast<SurfaceControl*>(surfaceControl));
}

static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        // clang-format off
        {"nativeCreate", "(Ljava/lang/String;)J", (void*)nativeCreate},
        {"nativeGetSurface", "(JZ)Landroid/view/Surface;", (void*)nativeGetSurface},
        {"nativeDestroy", "(J)V", (void*)nativeDestroy},
        {"nativeSetSyncTransaction", "(JJZ)V", (void*)nativeSetSyncTransaction},
        {"nativeUpdate", "(JJJJIJ)V", (void*)nativeUpdate},
        {"nativeMergeWithNextTransaction", "(JJJ)V", (void*)nativeMergeWithNextTransaction},
        {"nativeGetLastAcquiredFrameNum", "(J)J", (void*)nativeGetLastAcquiredFrameNum},
        {"nativeApplyPendingTransactions", "(JJ)V", (void*)nativeApplyPendingTransactions},
        {"nativeIsSameSurfaceControl", "(JJ)Z", (void*)nativeIsSameSurfaceControl},
        // clang-format on
};

int register_android_graphics_BLASTBufferQueue(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/graphics/BLASTBufferQueue",
            gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
    return 0;
}

} // namespace android
