/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "InputApplicationHandle"

#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/threads.h>

#include "android_hardware_input_InputApplicationHandle.h"
#include "android_util_Binder.h"

namespace android {

static struct {
    jfieldID ptr;
    jfieldID name;
    jfieldID dispatchingTimeoutNanos;
    jfieldID token;
} gInputApplicationHandleClassInfo;

static Mutex gHandleMutex;


// --- NativeInputApplicationHandle ---

NativeInputApplicationHandle::NativeInputApplicationHandle(jweak objWeak) :
        mObjWeak(objWeak) {
}

NativeInputApplicationHandle::~NativeInputApplicationHandle() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteWeakGlobalRef(mObjWeak);
}

jobject NativeInputApplicationHandle::getInputApplicationHandleObjLocalRef(JNIEnv* env) {
    return env->NewLocalRef(mObjWeak);
}

bool NativeInputApplicationHandle::updateInfo() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject obj = env->NewLocalRef(mObjWeak);
    if (!obj) {
        return false;
    }

    mInfo.name = getStringField(env, obj, gInputApplicationHandleClassInfo.name, "<null>");

    mInfo.dispatchingTimeout = env->GetLongField(obj,
            gInputApplicationHandleClassInfo.dispatchingTimeoutNanos);

    jobject tokenObj = env->GetObjectField(obj,
            gInputApplicationHandleClassInfo.token);
    if (tokenObj) {
        mInfo.token = ibinderForJavaObject(env, tokenObj);
        env->DeleteLocalRef(tokenObj);
    } else {
        mInfo.token.clear();
    }

    env->DeleteLocalRef(obj);
    return mInfo.token.get() != nullptr;
}


// --- Global functions ---

sp<InputApplicationHandle> android_view_InputApplicationHandle_getHandle(
        JNIEnv* env, jobject inputApplicationHandleObj) {
    if (!inputApplicationHandleObj) {
        return NULL;
    }

    AutoMutex _l(gHandleMutex);

    jlong ptr = env->GetLongField(inputApplicationHandleObj, gInputApplicationHandleClassInfo.ptr);
    NativeInputApplicationHandle* handle;
    if (ptr) {
        handle = reinterpret_cast<NativeInputApplicationHandle*>(ptr);
    } else {
        jweak objWeak = env->NewWeakGlobalRef(inputApplicationHandleObj);
        handle = new NativeInputApplicationHandle(objWeak);
        handle->incStrong((void*)android_view_InputApplicationHandle_getHandle);
        env->SetLongField(inputApplicationHandleObj, gInputApplicationHandleClassInfo.ptr,
                reinterpret_cast<jlong>(handle));
    }
    return handle;
}


// --- JNI ---

static void android_view_InputApplicationHandle_nativeDispose(JNIEnv* env, jobject obj) {
    AutoMutex _l(gHandleMutex);

    jlong ptr = env->GetLongField(obj, gInputApplicationHandleClassInfo.ptr);
    if (ptr) {
        env->SetLongField(obj, gInputApplicationHandleClassInfo.ptr, 0);

        NativeInputApplicationHandle* handle = reinterpret_cast<NativeInputApplicationHandle*>(ptr);
        handle->decStrong((void*)android_view_InputApplicationHandle_getHandle);
    }
}


static const JNINativeMethod gInputApplicationHandleMethods[] = {
    /* name, signature, funcPtr */
    { "nativeDispose", "()V",
            (void*) android_view_InputApplicationHandle_nativeDispose },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! (var), "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find field " fieldName);

int register_android_view_InputApplicationHandle(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/InputApplicationHandle",
            gInputApplicationHandleMethods, NELEM(gInputApplicationHandleMethods));
    (void) res;  // Faked use when LOG_NDEBUG.
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz;
    FIND_CLASS(clazz, "android/view/InputApplicationHandle");

    GET_FIELD_ID(gInputApplicationHandleClassInfo.ptr, clazz,
            "ptr", "J");

    GET_FIELD_ID(gInputApplicationHandleClassInfo.name, clazz,
            "name", "Ljava/lang/String;");

    GET_FIELD_ID(gInputApplicationHandleClassInfo.dispatchingTimeoutNanos,
            clazz,
            "dispatchingTimeoutNanos", "J");

    GET_FIELD_ID(gInputApplicationHandleClassInfo.token, clazz,
            "token", "Landroid/os/IBinder;");

    return 0;
}

} /* namespace android */
