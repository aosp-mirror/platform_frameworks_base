/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "InputTarget-JNI"

#include "JNIHelp.h"

#include <utils/Log.h>
#include <ui/InputDispatchPolicy.h>
#include <ui/InputTransport.h>
#include "android_view_InputTarget.h"
#include "android_view_InputChannel.h"

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jfieldID mInputChannel;
    jfieldID mFlags;
    jfieldID mTimeoutNanos;
    jfieldID mXOffset;
    jfieldID mYOffset;
} gInputTargetClassInfo;

// ----------------------------------------------------------------------------

void android_view_InputTarget_toNative(JNIEnv* env, jobject inputTargetObj,
        InputTarget* outInputTarget) {
    jobject inputChannelObj = env->GetObjectField(inputTargetObj,
            gInputTargetClassInfo.mInputChannel);
    jint flags = env->GetIntField(inputTargetObj,
            gInputTargetClassInfo.mFlags);
    jlong timeoutNanos = env->GetLongField(inputTargetObj,
            gInputTargetClassInfo.mTimeoutNanos);
    jfloat xOffset = env->GetFloatField(inputTargetObj,
            gInputTargetClassInfo.mXOffset);
    jfloat yOffset = env->GetFloatField(inputTargetObj,
            gInputTargetClassInfo.mYOffset);

    outInputTarget->inputChannel = android_view_InputChannel_getInputChannel(env, inputChannelObj);
    outInputTarget->flags = flags;
    outInputTarget->timeout = timeoutNanos;
    outInputTarget->xOffset = xOffset;
    outInputTarget->yOffset = yOffset;

    env->DeleteLocalRef(inputChannelObj);
}

// ----------------------------------------------------------------------------

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_view_InputTarget(JNIEnv* env) {
    FIND_CLASS(gInputTargetClassInfo.clazz, "android/view/InputTarget");

    GET_FIELD_ID(gInputTargetClassInfo.mInputChannel, gInputTargetClassInfo.clazz,
            "mInputChannel", "Landroid/view/InputChannel;");

    GET_FIELD_ID(gInputTargetClassInfo.mFlags, gInputTargetClassInfo.clazz,
            "mFlags", "I");

    GET_FIELD_ID(gInputTargetClassInfo.mTimeoutNanos, gInputTargetClassInfo.clazz,
            "mTimeoutNanos", "J");

    GET_FIELD_ID(gInputTargetClassInfo.mXOffset, gInputTargetClassInfo.clazz,
            "mXOffset", "F");

    GET_FIELD_ID(gInputTargetClassInfo.mYOffset, gInputTargetClassInfo.clazz,
            "mYOffset", "F");
    
    return 0;
}

} // namespace android
