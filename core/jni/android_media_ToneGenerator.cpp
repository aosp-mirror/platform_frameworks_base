/* //device/libs/android_runtime/android_media_AudioSystem.cpp
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

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include "utils/Log.h"
#include "media/AudioSystem.h"
#include "media/ToneGenerator.h"

// ----------------------------------------------------------------------------

using namespace android;

struct fields_t {
    jfieldID context;
};
static fields_t fields;

static jboolean android_media_ToneGenerator_startTone(JNIEnv *env, jobject thiz, jint toneType, jint durationMs) {
    ALOGV("android_media_ToneGenerator_startTone: %x\n", (int)thiz);

    ToneGenerator *lpToneGen = (ToneGenerator *)env->GetIntField(thiz,
            fields.context);
    if (lpToneGen == NULL) {
        jniThrowRuntimeException(env, "Method called after release()");
        return false;
    }

    return lpToneGen->startTone(toneType, durationMs);
}

static void android_media_ToneGenerator_stopTone(JNIEnv *env, jobject thiz) {
    ALOGV("android_media_ToneGenerator_stopTone: %x\n", (int)thiz);

    ToneGenerator *lpToneGen = (ToneGenerator *)env->GetIntField(thiz,
            fields.context);

    ALOGV("ToneGenerator lpToneGen: %x\n", (unsigned int)lpToneGen);
    if (lpToneGen == NULL) {
        jniThrowRuntimeException(env, "Method called after release()");
        return;
    }
    lpToneGen->stopTone();
}

static void android_media_ToneGenerator_release(JNIEnv *env, jobject thiz) {
    ToneGenerator *lpToneGen = (ToneGenerator *)env->GetIntField(thiz,
            fields.context);
    ALOGV("android_media_ToneGenerator_release lpToneGen: %x\n", (int)lpToneGen);

    env->SetIntField(thiz, fields.context, 0);

    if (lpToneGen) {
        delete lpToneGen;
    }
}

static void android_media_ToneGenerator_native_setup(JNIEnv *env, jobject thiz,
        jint streamType, jint volume) {
    ToneGenerator *lpToneGen = new ToneGenerator(streamType, AudioSystem::linearToLog(volume), true);

    env->SetIntField(thiz, fields.context, 0);

    ALOGV("android_media_ToneGenerator_native_setup jobject: %x\n", (int)thiz);

    if (lpToneGen == NULL) {
        LOGE("ToneGenerator creation failed \n");
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return;
    }
    ALOGV("ToneGenerator lpToneGen: %x\n", (unsigned int)lpToneGen);

    if (!lpToneGen->isInited()) {
        LOGE("ToneGenerator init failed \n");
        jniThrowRuntimeException(env, "Init failed");
        return;
    }

    // Stow our new C++ ToneGenerator in an opaque field in the Java object.
    env->SetIntField(thiz, fields.context, (int)lpToneGen);

    ALOGV("ToneGenerator fields.context: %x\n", env->GetIntField(thiz, fields.context));
}

static void android_media_ToneGenerator_native_finalize(JNIEnv *env,
        jobject thiz) {
    ALOGV("android_media_ToneGenerator_native_finalize jobject: %x\n", (int)thiz);

    ToneGenerator *lpToneGen = (ToneGenerator *)env->GetIntField(thiz,
            fields.context);

    if (lpToneGen) {
        ALOGV("delete lpToneGen: %x\n", (int)lpToneGen);
        delete lpToneGen;
    }
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    { "startTone", "(II)Z", (void *)android_media_ToneGenerator_startTone },
    { "stopTone", "()V", (void *)android_media_ToneGenerator_stopTone },
    { "release", "()V", (void *)android_media_ToneGenerator_release },
    { "native_setup", "(II)V", (void *)android_media_ToneGenerator_native_setup },
    { "native_finalize", "()V", (void *)android_media_ToneGenerator_native_finalize }
};


int register_android_media_ToneGenerator(JNIEnv *env) {
    jclass clazz;

    clazz = env->FindClass("android/media/ToneGenerator");
    if (clazz == NULL) {
        LOGE("Can't find %s", "android/media/ToneGenerator");
        return -1;
    }

    fields.context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (fields.context == NULL) {
        LOGE("Can't find ToneGenerator.mNativeContext");
        return -1;
    }
    ALOGV("register_android_media_ToneGenerator ToneGenerator fields.context: %x", (unsigned int)fields.context);

    return AndroidRuntime::registerNativeMethods(env,
            "android/media/ToneGenerator", gMethods, NELEM(gMethods));
}
