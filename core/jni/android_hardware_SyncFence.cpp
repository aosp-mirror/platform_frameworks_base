/*
 * Copyright (C) 2022 The Android Open Source Project
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

#define LOG_TAG "SyncFence"

#include <nativehelper/JNIHelp.h>
#include <ui/Fence.h>

#include "core_jni_helpers.h"
#include "jni.h"

using namespace android;

template <typename T>
jlong toJlong(T* ptr) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr));
}

template <typename T>
T* fromJlong(jlong jPtr) {
    return reinterpret_cast<T*>(static_cast<uintptr_t>(jPtr));
}

static void destroyFence(Fence* fence) {
    fence->decStrong(0);
}

static jlong SyncFence_getDestructor(JNIEnv*, jobject) {
    return toJlong(&destroyFence);
}

static jlong SyncFence_create(JNIEnv*, jobject, int fd) {
    Fence* fence = new Fence(fd);
    fence->incStrong(0);
    return toJlong(fence);
}

static jboolean SyncFence_isValid(JNIEnv*, jobject, jlong jPtr) {
    return fromJlong<Fence>(jPtr)->isValid();
}

static jint SyncFence_getFd(JNIEnv*, jobject, jlong jPtr) {
    return fromJlong<Fence>(jPtr)->get();
}

static jboolean SyncFence_wait(JNIEnv* env, jobject, jlong jPtr, jlong timeoutNanos) {
    Fence* fence = fromJlong<Fence>(jPtr);
    int err = fence->wait(timeoutNanos);
    return err == OK;
}

static jlong SyncFence_getSignalTime(JNIEnv* env, jobject, jlong jPtr) {
    return fromJlong<Fence>(jPtr)->getSignalTime();
}

static void SyncFence_incRef(JNIEnv*, jobject, jlong jPtr) {
    fromJlong<Fence>(jPtr)->incStrong((void*)SyncFence_incRef);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/hardware/SyncFence";

// clang-format off
static const JNINativeMethod gMethods[] = {
        { "nGetDestructor", "()J", (void*) SyncFence_getDestructor },
        { "nCreate", "(I)J", (void*) SyncFence_create },
        { "nIsValid", "(J)Z", (void*) SyncFence_isValid },
        { "nGetFd", "(J)I", (void*) SyncFence_getFd },
        { "nWait",  "(JJ)Z", (void*) SyncFence_wait },
        { "nGetSignalTime", "(J)J", (void*) SyncFence_getSignalTime },
        { "nIncRef", "(J)V", (void*) SyncFence_incRef },
};
// clang-format on

int register_android_hardware_SyncFence(JNIEnv* env) {
    int err = RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
    return err;
}