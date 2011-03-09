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

#define LOG_TAG "InputApplication"

#include "JNIHelp.h"
#include "jni.h"
#include <android_runtime/AndroidRuntime.h>

#include "com_android_server_InputApplication.h"
#include "com_android_server_InputApplicationHandle.h"

namespace android {

static struct {
    jfieldID inputApplicationHandle;
    jfieldID name;
    jfieldID dispatchingTimeoutNanos;
} gInputApplicationClassInfo;


// --- Global functions ---

void android_server_InputApplication_toNative(
        JNIEnv* env, jobject inputApplicationObj, InputApplication* outInputApplication) {
    jobject inputApplicationHandleObj = env->GetObjectField(inputApplicationObj,
            gInputApplicationClassInfo.inputApplicationHandle);
    if (inputApplicationHandleObj) {
        outInputApplication->inputApplicationHandle =
                android_server_InputApplicationHandle_getHandle(env, inputApplicationHandleObj);
        env->DeleteLocalRef(inputApplicationHandleObj);
    } else {
        outInputApplication->inputApplicationHandle = NULL;
    }

    jstring nameObj = jstring(env->GetObjectField(inputApplicationObj,
            gInputApplicationClassInfo.name));
    if (nameObj) {
        const char* nameStr = env->GetStringUTFChars(nameObj, NULL);
        outInputApplication->name.setTo(nameStr);
        env->ReleaseStringUTFChars(nameObj, nameStr);
        env->DeleteLocalRef(nameObj);
    } else {
        LOGE("InputApplication.name should not be null.");
        outInputApplication->name.setTo("unknown");
    }

    outInputApplication->dispatchingTimeout = env->GetLongField(inputApplicationObj,
            gInputApplicationClassInfo.dispatchingTimeoutNanos);
}


// --- JNI ---

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_server_InputApplication(JNIEnv* env) {
    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/wm/InputApplication");

    GET_FIELD_ID(gInputApplicationClassInfo.inputApplicationHandle,
            clazz,
            "inputApplicationHandle", "Lcom/android/server/wm/InputApplicationHandle;");

    GET_FIELD_ID(gInputApplicationClassInfo.name, clazz,
            "name", "Ljava/lang/String;");

    GET_FIELD_ID(gInputApplicationClassInfo.dispatchingTimeoutNanos,
            clazz,
            "dispatchingTimeoutNanos", "J");
    return 0;
}

} /* namespace android */
