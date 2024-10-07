/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <jni.h>
#include <binder/AppOpsManager.h>
#include <utils/String16.h>

using namespace android;

#include "android/log.h"
#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "AppOpsLoggingTest"

// Note op from native code
extern "C" JNIEXPORT void JNICALL
Java_android_app_AppOpsLoggingTestKt_nativeNoteOp(JNIEnv* env, jobject obj,
        jint op, jint uid, jstring jCallingPackageName, jstring jAttributionTag, jstring jMessage) {
    AppOpsManager appOpsManager;

    const char *nativeCallingPackageName = env->GetStringUTFChars(jCallingPackageName, 0);
    String16 callingPackageName(nativeCallingPackageName);

    const char *nativeAttributionTag;
    std::optional<String16> attributionTag;
    if (jAttributionTag != nullptr) {
        nativeAttributionTag = env->GetStringUTFChars(jAttributionTag, 0);
        attributionTag = String16(nativeAttributionTag);
    }

    const char *nativeMessage;
    String16 message;
    if (jMessage != nullptr) {
        nativeMessage = env->GetStringUTFChars(jMessage, 0);
        message = String16(nativeMessage);
    }

    appOpsManager.noteOp(op, uid, callingPackageName, attributionTag, message);

    env->ReleaseStringUTFChars(jCallingPackageName, nativeCallingPackageName);

    if (jAttributionTag != nullptr) {
        env->ReleaseStringUTFChars(jAttributionTag, nativeAttributionTag);
    }

    if (jMessage != nullptr) {
        env->ReleaseStringUTFChars(jMessage, nativeMessage);
    }
}
