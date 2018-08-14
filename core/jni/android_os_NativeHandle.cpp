/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "android_os_NativeHandle.h"

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

#include "core_jni_helpers.h"

#define PACKAGE_PATH    "android/os"
#define CLASS_NAME      "NativeHandle"
#define CLASS_PATH      PACKAGE_PATH "/" CLASS_NAME

namespace android {

static struct {
    jclass clazz;
    jmethodID constructID;  // NativeHandle(int[] fds, int[] ints, boolean owns)

    jmethodID getFdsID;  // int[] NativeHandle.getFds()
    jmethodID getIntsID;  // int[] NativeHandle.getInts()
} gNativeHandleFields;

jobject JNativeHandle::MakeJavaNativeHandleObj(
        JNIEnv *env, const native_handle_t *handle) {
    if (handle == nullptr) { return nullptr; }

    const int numFds = handle->numFds;
    ScopedLocalRef<jintArray> fds(env, env->NewIntArray(numFds));
    env->SetIntArrayRegion(fds.get(), 0, numFds, &(handle->data[0]));

    const int numInts = handle->numInts;
    ScopedLocalRef<jintArray> ints(env, env->NewIntArray(numInts));
    env->SetIntArrayRegion(ints.get(), 0, numInts, &(handle->data[numFds]));

    return env->NewObject(gNativeHandleFields.clazz,
            gNativeHandleFields.constructID, fds.get(), ints.get(), false /*own*/);
}

native_handle_t *JNativeHandle::MakeCppNativeHandle(
        JNIEnv *env, jobject jHandle, EphemeralStorage *storage) {
    if (jHandle == nullptr) { return nullptr; }

    if (!env->IsInstanceOf(jHandle, gNativeHandleFields.clazz)) {
        jniThrowException(env, "java/lang/ClassCastException",
                "jHandle must be an instance of NativeHandle.");
        return nullptr;
    }

    ScopedLocalRef<jintArray> fds(env, (jintArray) env->CallObjectMethod(
            jHandle, gNativeHandleFields.getFdsID));

    ScopedLocalRef<jintArray> ints(env, (jintArray) env->CallObjectMethod(
            jHandle, gNativeHandleFields.getIntsID));

    const int numFds = (int) env->GetArrayLength(fds.get());
    const int numInts = (int) env->GetArrayLength(ints.get());

    native_handle_t *handle = (storage == nullptr)
            ? native_handle_create(numFds, numInts)
            : storage->allocTemporaryNativeHandle(numFds, numInts);

    if (handle != nullptr) {
        env->GetIntArrayRegion(fds.get(), 0, numFds, &(handle->data[0]));
        env->GetIntArrayRegion(ints.get(), 0, numInts, &(handle->data[numFds]));
    } else {
        jniThrowException(env, "java/lang/OutOfMemoryError",
                "Failed to allocate memory for native_handle_t.");
    }

    return handle;
}

jobjectArray JNativeHandle::AllocJavaNativeHandleObjArray(JNIEnv *env, jsize length) {
    return env->NewObjectArray(length, gNativeHandleFields.clazz, nullptr);
}

int register_android_os_NativeHandle(JNIEnv *env) {
    jclass clazz = FindClassOrDie(env, CLASS_PATH);
    gNativeHandleFields.clazz = MakeGlobalRefOrDie(env, clazz);

    gNativeHandleFields.constructID = GetMethodIDOrDie(env, clazz, "<init>", "([I[IZ)V");
    gNativeHandleFields.getFdsID = GetMethodIDOrDie(env, clazz, "getFdsAsIntArray", "()[I");
    gNativeHandleFields.getIntsID = GetMethodIDOrDie(env, clazz, "getInts", "()[I");

    return 0;
}

}
