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

#define LOG_TAG "InputWindowHandle"

#include "JNIHelp.h"
#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/threads.h>

#include "com_android_server_InputWindowHandle.h"
#include "com_android_server_InputApplicationHandle.h"

namespace android {

static struct {
    jfieldID ptr;
    jfieldID inputApplicationHandle;
} gInputWindowHandleClassInfo;

static Mutex gHandleMutex;


// --- NativeInputWindowHandle ---

NativeInputWindowHandle::NativeInputWindowHandle(
        const sp<InputApplicationHandle>& inputApplicationHandle, jweak objWeak) :
        InputWindowHandle(inputApplicationHandle),
        mObjWeak(objWeak) {
}

NativeInputWindowHandle::~NativeInputWindowHandle() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteWeakGlobalRef(mObjWeak);
}

jobject NativeInputWindowHandle::getInputWindowHandleObjLocalRef(JNIEnv* env) {
    return env->NewLocalRef(mObjWeak);
}


// --- Global functions ---

sp<NativeInputWindowHandle> android_server_InputWindowHandle_getHandle(
        JNIEnv* env, jobject inputWindowHandleObj) {
    if (!inputWindowHandleObj) {
        return NULL;
    }

    AutoMutex _l(gHandleMutex);

    int ptr = env->GetIntField(inputWindowHandleObj, gInputWindowHandleClassInfo.ptr);
    NativeInputWindowHandle* handle;
    if (ptr) {
        handle = reinterpret_cast<NativeInputWindowHandle*>(ptr);
    } else {
        jobject inputApplicationHandleObj = env->GetObjectField(inputWindowHandleObj,
                gInputWindowHandleClassInfo.inputApplicationHandle);
        sp<InputApplicationHandle> inputApplicationHandle =
                android_server_InputApplicationHandle_getHandle(env, inputApplicationHandleObj);
        env->DeleteLocalRef(inputApplicationHandleObj);

        jweak objWeak = env->NewWeakGlobalRef(inputWindowHandleObj);
        handle = new NativeInputWindowHandle(inputApplicationHandle, objWeak);
        handle->incStrong(inputWindowHandleObj);
        env->SetIntField(inputWindowHandleObj, gInputWindowHandleClassInfo.ptr,
                reinterpret_cast<int>(handle));
    }
    return handle;
}


// --- JNI ---

static void android_server_InputWindowHandle_nativeDispose(JNIEnv* env, jobject obj) {
    AutoMutex _l(gHandleMutex);

    int ptr = env->GetIntField(obj, gInputWindowHandleClassInfo.ptr);
    if (ptr) {
        env->SetIntField(obj, gInputWindowHandleClassInfo.ptr, 0);

        NativeInputWindowHandle* handle = reinterpret_cast<NativeInputWindowHandle*>(ptr);
        handle->decStrong(obj);
    }
}


static JNINativeMethod gInputWindowHandleMethods[] = {
    /* name, signature, funcPtr */
    { "nativeDispose", "()V",
            (void*) android_server_InputWindowHandle_nativeDispose },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_server_InputWindowHandle(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/wm/InputWindowHandle",
            gInputWindowHandleMethods, NELEM(gInputWindowHandleMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/wm/InputWindowHandle");

    GET_FIELD_ID(gInputWindowHandleClassInfo.ptr, clazz,
            "ptr", "I");

    GET_FIELD_ID(gInputWindowHandleClassInfo.inputApplicationHandle,
            clazz,
            "inputApplicationHandle", "Lcom/android/server/wm/InputApplicationHandle;");

    return 0;
}

} /* namespace android */
