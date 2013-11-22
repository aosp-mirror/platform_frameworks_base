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

#include "JNIHelp.h"
#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/threads.h>

#include "com_android_server_input_InputApplicationHandle.h"

namespace android {

static struct {
    jfieldID ptr;
    jfieldID name;
    jfieldID dispatchingTimeoutNanos;
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
        releaseInfo();
        return false;
    }

    if (!mInfo) {
        mInfo = new InputApplicationInfo();
    }

    jstring nameObj = jstring(env->GetObjectField(obj,
            gInputApplicationHandleClassInfo.name));
    if (nameObj) {
        const char* nameStr = env->GetStringUTFChars(nameObj, NULL);
        mInfo->name.setTo(nameStr);
        env->ReleaseStringUTFChars(nameObj, nameStr);
        env->DeleteLocalRef(nameObj);
    } else {
        mInfo->name.setTo("<null>");
    }

    mInfo->dispatchingTimeout = env->GetLongField(obj,
            gInputApplicationHandleClassInfo.dispatchingTimeoutNanos);

    env->DeleteLocalRef(obj);
    return true;
}


// --- Global functions ---

sp<InputApplicationHandle> android_server_InputApplicationHandle_getHandle(
        JNIEnv* env, jobject inputApplicationHandleObj) {
    if (!inputApplicationHandleObj) {
        return NULL;
    }

    AutoMutex _l(gHandleMutex);

    int ptr = env->GetIntField(inputApplicationHandleObj, gInputApplicationHandleClassInfo.ptr);
    NativeInputApplicationHandle* handle;
    if (ptr) {
        handle = reinterpret_cast<NativeInputApplicationHandle*>(ptr);
    } else {
        jweak objWeak = env->NewWeakGlobalRef(inputApplicationHandleObj);
        handle = new NativeInputApplicationHandle(objWeak);
        handle->incStrong((void*)android_server_InputApplicationHandle_getHandle);
        env->SetIntField(inputApplicationHandleObj, gInputApplicationHandleClassInfo.ptr,
                reinterpret_cast<int>(handle));
    }
    return handle;
}


// --- JNI ---

static void android_server_InputApplicationHandle_nativeDispose(JNIEnv* env, jobject obj) {
    AutoMutex _l(gHandleMutex);

    int ptr = env->GetIntField(obj, gInputApplicationHandleClassInfo.ptr);
    if (ptr) {
        env->SetIntField(obj, gInputApplicationHandleClassInfo.ptr, 0);

        NativeInputApplicationHandle* handle = reinterpret_cast<NativeInputApplicationHandle*>(ptr);
        handle->decStrong((void*)android_server_InputApplicationHandle_getHandle);
    }
}


static JNINativeMethod gInputApplicationHandleMethods[] = {
    /* name, signature, funcPtr */
    { "nativeDispose", "()V",
            (void*) android_server_InputApplicationHandle_nativeDispose },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_server_InputApplicationHandle(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/input/InputApplicationHandle",
            gInputApplicationHandleMethods, NELEM(gInputApplicationHandleMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/input/InputApplicationHandle");

    GET_FIELD_ID(gInputApplicationHandleClassInfo.ptr, clazz,
            "ptr", "I");

    GET_FIELD_ID(gInputApplicationHandleClassInfo.name, clazz,
            "name", "Ljava/lang/String;");

    GET_FIELD_ID(gInputApplicationHandleClassInfo.dispatchingTimeoutNanos,
            clazz,
            "dispatchingTimeoutNanos", "J");

    return 0;
}

} /* namespace android */
