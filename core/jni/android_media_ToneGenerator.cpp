/*
 **
 ** Copyright 2008, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#define LOG_TAG "ToneGenerator"

#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

#include <utils/Log.h>
#include <media/AudioSystem.h>
#include <media/ToneGenerator.h>

// ----------------------------------------------------------------------------

using namespace android;

struct fields_t {
    jfieldID context;
};
static fields_t fields;

static jboolean android_media_ToneGenerator_startTone(JNIEnv *env, jobject thiz, jint toneType, jint durationMs) {
    ALOGV("android_media_ToneGenerator_startTone: %p", thiz);

    ToneGenerator *lpToneGen = (ToneGenerator *)env->GetLongField(thiz,
            fields.context);
    if (lpToneGen == NULL) {
        jniThrowRuntimeException(env, "Method called after release()");
        return false;
    }

    return lpToneGen->startTone((ToneGenerator::tone_type) toneType, durationMs);
}

static void android_media_ToneGenerator_stopTone(JNIEnv *env, jobject thiz) {
    ALOGV("android_media_ToneGenerator_stopTone: %p", thiz);

    ToneGenerator *lpToneGen = (ToneGenerator *)env->GetLongField(thiz,
            fields.context);

    ALOGV("ToneGenerator lpToneGen: %p", lpToneGen);
    if (lpToneGen == NULL) {
        jniThrowRuntimeException(env, "Method called after release()");
        return;
    }
    lpToneGen->stopTone();
}

static jint android_media_ToneGenerator_getAudioSessionId(JNIEnv *env, jobject thiz) {
    ToneGenerator *lpToneGen = (ToneGenerator *)env->GetLongField(thiz,
            fields.context);
    if (lpToneGen == NULL) {
        jniThrowRuntimeException(env, "Method called after release()");
        return 0;
    }
    return lpToneGen->getSessionId();
}

static void android_media_ToneGenerator_release(JNIEnv *env, jobject thiz) {
    ToneGenerator *lpToneGen = (ToneGenerator *)env->GetLongField(thiz,
            fields.context);
    ALOGV("android_media_ToneGenerator_release lpToneGen: %p", lpToneGen);

    env->SetLongField(thiz, fields.context, 0);

    delete lpToneGen;
}

static void android_media_ToneGenerator_native_setup(JNIEnv *env, jobject thiz,
        jint streamType, jint volume) {
    ToneGenerator *lpToneGen = new ToneGenerator((audio_stream_type_t) streamType, AudioSystem::linearToLog(volume), true);

    env->SetLongField(thiz, fields.context, 0);

    ALOGV("android_media_ToneGenerator_native_setup jobject: %p", thiz);

    ALOGV("ToneGenerator lpToneGen: %p", lpToneGen);

    if (!lpToneGen->isInited()) {
        ALOGE("ToneGenerator init failed");
        jniThrowRuntimeException(env, "Init failed");
        delete lpToneGen;
        return;
    }

    // Stow our new C++ ToneGenerator in an opaque field in the Java object.
    env->SetLongField(thiz, fields.context, (jlong)lpToneGen);

    ALOGV("ToneGenerator fields.context: %p", (void*) env->GetLongField(thiz, fields.context));
}

static void android_media_ToneGenerator_native_finalize(JNIEnv *env,
        jobject thiz) {
    ALOGV("android_media_ToneGenerator_native_finalize jobject: %p", thiz);

    ToneGenerator *lpToneGen = (ToneGenerator *)env->GetLongField(thiz,
            fields.context);

    if (lpToneGen != NULL) {
        ALOGV("delete lpToneGen: %p", lpToneGen);
        delete lpToneGen;
    }
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMethods[] = {
    { "startTone", "(II)Z", (void *)android_media_ToneGenerator_startTone },
    { "stopTone", "()V", (void *)android_media_ToneGenerator_stopTone },
    { "getAudioSessionId", "()I", (void *)android_media_ToneGenerator_getAudioSessionId},
    { "release", "()V", (void *)android_media_ToneGenerator_release },
    { "native_setup", "(II)V", (void *)android_media_ToneGenerator_native_setup },
    { "native_finalize", "()V", (void *)android_media_ToneGenerator_native_finalize }
};


int register_android_media_ToneGenerator(JNIEnv *env) {
    jclass clazz = FindClassOrDie(env, "android/media/ToneGenerator");

    fields.context = GetFieldIDOrDie(env, clazz, "mNativeContext", "J");
    ALOGV("register_android_media_ToneGenerator ToneGenerator fields.context: %p", fields.context);

    return RegisterMethodsOrDie(env, "android/media/ToneGenerator", gMethods, NELEM(gMethods));
}
