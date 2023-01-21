/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <nativehelper/JNIHelp.h>
#include <utils/hash/farmhash.h>

#include "core_jni_helpers.h"

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

static jclass g_stringClass = nullptr;

/**
 * Class:     com_android_internal_expresslog_Utils
 * Method:    hashString
 * Signature: (Ljava/lang/String;)J
 */
static jlong hashString(JNIEnv* env, jclass /*class*/, jstring metricNameObj) {
    ScopedUtfChars name(env, metricNameObj);
    if (name.c_str() == nullptr) {
        return 0;
    }

    return static_cast<jlong>(farmhash::Fingerprint64(name.c_str(), name.size()));
}

static const JNINativeMethod g_methods[] = {
        {"hashString", "(Ljava/lang/String;)J", (void*)hashString},
};

static const char* const kUtilsPathName = "com/android/internal/expresslog/Utils";

namespace android {

int register_com_android_internal_expresslog_Utils(JNIEnv* env) {
    jclass stringClass = FindClassOrDie(env, "java/lang/String");
    g_stringClass = MakeGlobalRefOrDie(env, stringClass);

    return RegisterMethodsOrDie(env, kUtilsPathName, g_methods, NELEM(g_methods));
}

} // namespace android
