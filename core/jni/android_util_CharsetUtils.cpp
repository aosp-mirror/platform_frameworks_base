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

static jint android_util_CharsetUtils_toUtf8Bytes(JNIEnv *env, jobject clazz,
        jstring src, jint srcLen, jlong dest, jint destOff, jint destLen) {
    char *destPtr = reinterpret_cast<char*>(dest);

    // Quickly check if destination has plenty of room for worst-case
    // 4-bytes-per-char encoded size
    if (destOff >= 0 && destOff + (srcLen * 4) < destLen) {
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

    return -1;
}

static const JNINativeMethod methods[] = {
    // @FastNative
    {"toUtf8Bytes",      "(Ljava/lang/String;IJII)I",
            (void*)android_util_CharsetUtils_toUtf8Bytes},
};

int register_android_util_CharsetUtils(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "android/util/CharsetUtils", methods, NELEM(methods));
}

}
