/*
 * Copyright 2024, The Android Open Source Project
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
#define LOG_TAG "MediaCodec-JNI"

#include "android_runtime/AndroidRuntime.h"
#include "jni.h"

#include <media/AudioCapabilities.h>
#include <media/stagefright/foundation/ADebug.h>
#include <nativehelper/JNIHelp.h>

namespace android {

struct fields_t {
    jfieldID audioCapsContext;
};
static fields_t fields;

// Getters

static AudioCapabilities* getAudioCapabilities(JNIEnv *env, jobject thiz) {
    AudioCapabilities* const p = (AudioCapabilities*)env->GetLongField(
            thiz, fields.audioCapsContext);
    return p;
}

}  // namespace android

// ----------------------------------------------------------------------------

using namespace android;

// AudioCapabilities

static void android_media_AudioCapabilities_native_init(JNIEnv *env, jobject /* thiz */) {
    jclass audioCapsImplClazz
            = env->FindClass("android/media/MediaCodecInfo$AudioCapabilities$AudioCapsNativeImpl");
    if (audioCapsImplClazz == NULL) {
        return;
    }

    fields.audioCapsContext = env->GetFieldID(audioCapsImplClazz, "mNativeContext", "J");
    if (fields.audioCapsContext == NULL) {
        return;
    }

    env->DeleteLocalRef(audioCapsImplClazz);
}

static jint android_media_AudioCapabilities_getMaxInputChannelCount(JNIEnv *env, jobject thiz) {
    AudioCapabilities* const audioCaps = getAudioCapabilities(env, thiz);
    if (audioCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    int32_t maxInputChannelCount = audioCaps->getMaxInputChannelCount();
    return maxInputChannelCount;
}

static jint android_media_AudioCapabilities_getMinInputChannelCount(JNIEnv *env, jobject thiz) {
    AudioCapabilities* const audioCaps = getAudioCapabilities(env, thiz);
    if (audioCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    int32_t minInputChannelCount = audioCaps->getMinInputChannelCount();
    return minInputChannelCount;
}

static jboolean android_media_AudioCapabilities_isSampleRateSupported(JNIEnv *env, jobject thiz,
        int sampleRate) {
    AudioCapabilities* const audioCaps = getAudioCapabilities(env, thiz);
    if (audioCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    bool res = audioCaps->isSampleRateSupported(sampleRate);
    return res;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gAudioCapsMethods[] = {
    {"native_init", "()V", (void *)android_media_AudioCapabilities_native_init},
    {"native_getMaxInputChannelCount", "()I", (void *)android_media_AudioCapabilities_getMaxInputChannelCount},
    {"native_getMinInputChannelCount", "()I", (void *)android_media_AudioCapabilities_getMinInputChannelCount},
    {"native_isSampleRateSupported", "(I)Z", (void *)android_media_AudioCapabilities_isSampleRateSupported}
};

int register_android_media_CodecCapabilities(JNIEnv *env) {
    int result = AndroidRuntime::registerNativeMethods(env,
            "android/media/MediaCodecInfo$AudioCapabilities$AudioCapsNativeImpl",
            gAudioCapsMethods, NELEM(gAudioCapsMethods));
    if (result != JNI_OK) {
        return result;
    }

    return result;
}