/*
 * Copyright (C) 2018 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "SourceDefaultEffect-JNI"

#include <utils/Errors.h>
#include <utils/Log.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include "media/AudioEffect.h"

#include <nativehelper/ScopedUtfChars.h>

#include "android_media_AudioEffect.h"

using namespace android;

static const char* const kClassPathName = "android/media/audiofx/SourceDefaultEffect";

static jint android_media_SourceDefaultEffect_native_setup(JNIEnv *env,
                                                           jobject /*thiz*/,
                                                           jstring type,
                                                           jstring uuid,
                                                           jint priority,
                                                           jint source,
                                                           jstring opPackageName,
                                                           jintArray jId)
{
    ALOGV("android_media_SourceDefaultEffect_native_setup");
    status_t lStatus = NO_ERROR;
    jint* nId = NULL;
    const char *typeStr = NULL;
    const char *uuidStr = NULL;

    ScopedUtfChars opPackageNameStr(env, opPackageName);

    if (type != NULL) {
        typeStr = env->GetStringUTFChars(type, NULL);
        if (typeStr == NULL) {  // Out of memory
            lStatus = NO_MEMORY;
            jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
            goto setup_exit;
        }
    }

    if (uuid != NULL) {
        uuidStr = env->GetStringUTFChars(uuid, NULL);
        if (uuidStr == NULL) {  // Out of memory
            lStatus = NO_MEMORY;
            jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
            goto setup_exit;
        }
    }

    if (typeStr == NULL && uuidStr == NULL) {
        lStatus = BAD_VALUE;
        goto setup_exit;
    }

    nId = env->GetIntArrayElements(jId, nullptr /* isCopy */);
    if (nId == NULL) {
        ALOGE("setup: Error retrieving id pointer");
        lStatus = BAD_VALUE;
        goto setup_exit;
    }

    // create the native SourceDefaultEffect.
    audio_unique_id_t id;
    lStatus = AudioEffect::addSourceDefaultEffect(typeStr,
                                                  String16(opPackageNameStr.c_str()),
                                                  uuidStr,
                                                  priority,
                                                  static_cast<audio_source_t>(source),
                                                  &id);
    if (lStatus != NO_ERROR) {
        ALOGE("setup: Error adding SourceDefaultEffect");
        goto setup_exit;
    }

    nId[0] = static_cast<jint>(id);

setup_exit:
    // Final cleanup and return.

    if (nId != NULL) {
        env->ReleaseIntArrayElements(jId, nId, 0 /* mode */);
        nId = NULL;
    }

    if (uuidStr != NULL) {
        env->ReleaseStringUTFChars(uuid, uuidStr);
        uuidStr = NULL;
    }

    if (typeStr != NULL) {
        env->ReleaseStringUTFChars(type, typeStr);
        typeStr = NULL;
    }

    return AudioEffectJni::translateNativeErrorToJava(lStatus);
}

static void android_media_SourceDefaultEffect_native_release(JNIEnv */*env*/,
                                                             jobject /*thiz*/,
                                                             jint id) {
    status_t lStatus = AudioEffect::removeSourceDefaultEffect(id);
    if (lStatus != NO_ERROR) {
        ALOGW("Error releasing SourceDefaultEffect: %d", lStatus);
    }
}

// ----------------------------------------------------------------------------

// Dalvik VM type signatures
static const JNINativeMethod gMethods[] = {
    {"native_setup",         "(Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;[I)I",
                                         (void *)android_media_SourceDefaultEffect_native_setup},
    {"native_release",       "(I)V",      (void *)android_media_SourceDefaultEffect_native_release},
};


// ----------------------------------------------------------------------------

int register_android_media_SourceDefaultEffect(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}
