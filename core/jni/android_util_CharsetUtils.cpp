/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "core_jni_helpers.h"
#include "nativehelper/scoped_primitive_array.h"

namespace android {

static jint android_util_CharsetUtils_toModifiedUtf8Bytes(JNIEnv *env, jobject clazz,
        jstring src, jint srcLen, jlong dest, jint destOff, jint destLen) {
    char *destPtr = reinterpret_cast<char*>(dest);

    // Quickly check if destination has plenty of room for worst-case
    // 4-bytes-per-char encoded size
    const size_t worstLen = (srcLen * 4);
    if (destOff >= 0 && destOff + worstLen < destLen) {
        env->GetStringUTFRegion(src, 0, srcLen, destPtr + destOff);
        return strlen(destPtr + destOff + srcLen) + srcLen;
    }

    // String still might fit in destination, but we need to measure
    // its actual encoded size to be sure
    const size_t encodedLen = env->GetStringUTFLength(src);
    if (destOff >= 0 && destOff + encodedLen < destLen) {
        env->GetStringUTFRegion(src, 0, srcLen, destPtr + destOff);
        return encodedLen;
    }

    return -encodedLen;
}

static jstring android_util_CharsetUtils_fromModifiedUtf8Bytes(JNIEnv *env, jobject clazz,
        jlong src, jint srcOff, jint srcLen) {
    char *srcPtr = reinterpret_cast<char*>(src);

    // This is funky, but we need to temporarily swap a null byte so that
    // JNI knows where the string ends; we'll put it back, we promise
    char tmp = srcPtr[srcOff + srcLen];
    srcPtr[srcOff + srcLen] = '\0';
    jstring res = env->NewStringUTF(srcPtr + srcOff);
    srcPtr[srcOff + srcLen] = tmp;
    return res;
}

static const JNINativeMethod methods[] = {
    // @FastNative
    {"toModifiedUtf8Bytes",      "(Ljava/lang/String;IJII)I",
            (void*)android_util_CharsetUtils_toModifiedUtf8Bytes},
    // @FastNative
    {"fromModifiedUtf8Bytes",    "(JII)Ljava/lang/String;",
            (void*)android_util_CharsetUtils_fromModifiedUtf8Bytes},
};

int register_android_util_CharsetUtils(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "android/util/CharsetUtils", methods, NELEM(methods));
}

}
