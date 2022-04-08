/*
 * Copyright 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
//#define LOG_NDEBUG 0
#define LOG_TAG "MediaTranscodeManager_JNI"

#include "android_runtime/AndroidRuntime.h"
#include "jni.h"

#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>

namespace {

// NOTE: Keep these enums in sync with their equivalents in MediaTranscodeManager.java.
enum {
    ID_INVALID = -1
};

enum {
    EVENT_JOB_STARTED = 1,
    EVENT_JOB_PROGRESSED = 2,
    EVENT_JOB_FINISHED = 3,
};

enum {
    RESULT_NONE = 1,
    RESULT_SUCCESS = 2,
    RESULT_ERROR = 3,
    RESULT_CANCELED = 4,
};

struct {
    jmethodID postEventFromNative;
} gMediaTranscodeManagerClassInfo;

using namespace android;

void android_media_MediaTranscodeManager_native_init(JNIEnv *env, jclass clazz) {
    ALOGV("android_media_MediaTranscodeManager_native_init");

    gMediaTranscodeManagerClassInfo.postEventFromNative = env->GetMethodID(
            clazz, "postEventFromNative", "(IJI)V");
    LOG_ALWAYS_FATAL_IF(gMediaTranscodeManagerClassInfo.postEventFromNative == NULL,
                        "can't find android/media/MediaTranscodeManager.postEventFromNative");
}

jlong android_media_MediaTranscodeManager_requestUniqueJobID(
        JNIEnv *env __unused, jobject thiz __unused) {
    ALOGV("android_media_MediaTranscodeManager_reserveUniqueJobID");
    static std::atomic_int32_t sJobIDCounter{0};
    jlong id = (jlong)++sJobIDCounter;
    return id;
}

jboolean android_media_MediaTranscodeManager_enqueueTranscodingRequest(
        JNIEnv *env, jobject thiz, jlong id, jobject request, jobject context __unused) {
    ALOGV("android_media_MediaTranscodeManager_enqueueTranscodingRequest");
    if (!request) {
        return ID_INVALID;
    }

    env->CallVoidMethod(thiz, gMediaTranscodeManagerClassInfo.postEventFromNative,
                        EVENT_JOB_FINISHED, id, RESULT_ERROR);
    return true;
}

void android_media_MediaTranscodeManager_cancelTranscodingRequest(
        JNIEnv *env __unused, jobject thiz __unused, jlong jobID __unused) {
    ALOGV("android_media_MediaTranscodeManager_cancelTranscodingRequest");
}

const JNINativeMethod gMethods[] = {
    { "native_init", "()V",
        (void *)android_media_MediaTranscodeManager_native_init },
    { "native_requestUniqueJobID", "()J",
        (void *)android_media_MediaTranscodeManager_requestUniqueJobID },
    { "native_enqueueTranscodingRequest",
        "(JLandroid/media/MediaTranscodeManager$TranscodingRequest;Landroid/content/Context;)Z",
        (void *)android_media_MediaTranscodeManager_enqueueTranscodingRequest },
    { "native_cancelTranscodingRequest", "(J)V",
        (void *)android_media_MediaTranscodeManager_cancelTranscodingRequest },
};

} // namespace anonymous

int register_android_media_MediaTranscodeManager(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaTranscodeManager", gMethods, NELEM(gMethods));
}
