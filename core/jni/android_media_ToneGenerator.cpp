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
#include <nativehelper/ScopedUtfChars.h>
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

static sp<ToneGenerator> getNativeToneGenerator(JNIEnv *env, jobject thiz) {
    auto toneGen = sp<ToneGenerator>::fromExisting(
            reinterpret_cast<ToneGenerator *>(env->GetLongField(thiz, fields.context)));
    if (toneGen == nullptr) {
        jniThrowRuntimeException(env, "Method called after release()");
    }
    ALOGV("ToneGenerator address %p", toneGen.get());
    return toneGen;
}

static sp<ToneGenerator> setNativeToneGenerator(JNIEnv *env, jobject thiz,
                                                const sp<ToneGenerator> &toneGen) {
    auto oldToneGen = sp<ToneGenerator>::fromExisting(
            reinterpret_cast<ToneGenerator *>(env->GetLongField(thiz, fields.context)));
    ALOGV("ToneGenerator address changed from %p to %p", oldToneGen.get(), toneGen.get());
    auto id = reinterpret_cast<void *>(setNativeToneGenerator);
    if (toneGen != nullptr) {
        toneGen->incStrong(id);
    }
    if (oldToneGen != nullptr) {
        oldToneGen->decStrong(id);
    }
    env->SetLongField(thiz, fields.context, (jlong)toneGen.get());
    return oldToneGen;
}

static jboolean android_media_ToneGenerator_startTone(JNIEnv *env, jobject thiz, jint toneType,
                                                      jint durationMs) {
    ALOGV("%s jobject: %p", __func__, thiz);
    auto lpToneGen = getNativeToneGenerator(env, thiz);
    return (lpToneGen != nullptr)
            ? lpToneGen->startTone((ToneGenerator::tone_type)toneType, durationMs)
            : false;
}

static void android_media_ToneGenerator_stopTone(JNIEnv *env, jobject thiz) {
    ALOGV("%s jobject: %p", __func__, thiz);
    auto lpToneGen = getNativeToneGenerator(env, thiz);
    if (lpToneGen != nullptr) lpToneGen->stopTone();
}

static jint android_media_ToneGenerator_getAudioSessionId(JNIEnv *env, jobject thiz) {
    ALOGV("%s jobject: %p", __func__, thiz);
    auto lpToneGen = getNativeToneGenerator(env, thiz);
    return (lpToneGen != nullptr) ? lpToneGen->getSessionId() : 0;
}

static void android_media_ToneGenerator_release(JNIEnv *env, jobject thiz) {
    ALOGV("%s jobject: %p", __func__, thiz);
    setNativeToneGenerator(env, thiz, nullptr);
}

static void android_media_ToneGenerator_native_setup(JNIEnv *env, jobject thiz, jint streamType,
                                                     jint volume, jstring opPackageName) {
    ALOGV("%s jobject: %p", __func__, thiz);
    ScopedUtfChars opPackageNameStr{env, opPackageName};
    sp<ToneGenerator> lpToneGen = sp<ToneGenerator>::make((audio_stream_type_t)streamType,
                                    AudioSystem::linearToLog(volume),
                                    true /*threadCanCallJava*/,
                                    opPackageNameStr.c_str());
    if (!lpToneGen->isInited()) {
        ALOGE("ToneGenerator init failed");
        jniThrowRuntimeException(env, "Init failed");
        return;
    }
    // Stow our new C++ ToneGenerator in an opaque field in the Java object.
    setNativeToneGenerator(env, thiz, lpToneGen);
}

static void android_media_ToneGenerator_native_finalize(JNIEnv *env, jobject thiz) {
    ALOGV("%s jobject: %p", __func__, thiz);
    android_media_ToneGenerator_release(env, thiz);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMethods[] =
        {{"startTone", "(II)Z", (void *)android_media_ToneGenerator_startTone},
         {"stopTone", "()V", (void *)android_media_ToneGenerator_stopTone},
         {"getAudioSessionId", "()I", (void *)android_media_ToneGenerator_getAudioSessionId},
         {"release", "()V", (void *)android_media_ToneGenerator_release},
         {"native_setup", "(IILjava/lang/String;)V",
          (void *)android_media_ToneGenerator_native_setup},
         {"native_finalize", "()V", (void *)android_media_ToneGenerator_native_finalize}};

int register_android_media_ToneGenerator(JNIEnv *env) {
    jclass clazz = FindClassOrDie(env, "android/media/ToneGenerator");

    fields.context = GetFieldIDOrDie(env, clazz, "mNativeContext", "J");
    ALOGV("register_android_media_ToneGenerator ToneGenerator fields.context: %p", fields.context);

    return RegisterMethodsOrDie(env, "android/media/ToneGenerator", gMethods, NELEM(gMethods));
}
