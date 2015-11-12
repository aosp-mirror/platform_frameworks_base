/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "jni.h"

#include <PathParser.h>
#include <SkPath.h>

#include <android/log.h>
#include "core_jni_helpers.h"

namespace android {

static bool parseStringForPath(JNIEnv* env, jobject, jlong skPathHandle, jstring inputPathStr,
        jint strLength) {
    const char* pathString = env->GetStringUTFChars(inputPathStr, NULL);
    SkPath* skPath = reinterpret_cast<SkPath*>(skPathHandle);

    android::uirenderer::PathParser::ParseResult result;
    android::uirenderer::PathParser::parseStringForSkPath(skPath, &result, pathString, strLength);
    env->ReleaseStringUTFChars(inputPathStr, pathString);
    if (result.failureOccurred) {
        ALOGE(result.failureMessage.c_str());
    }
    return !result.failureOccurred;
}

static const JNINativeMethod gMethods[] = {
    {"nParseStringForPath", "(JLjava/lang/String;I)Z", (void*)parseStringForPath}
};

int register_android_util_PathParser(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/util/PathParser", gMethods, NELEM(gMethods));
}
};
