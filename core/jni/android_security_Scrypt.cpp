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

#define LOG_TAG "Scrypt"

#include <nativehelper/JNIHelp.h>
#include "jni.h"

#include <android_runtime/Log.h>
#include <utils/Timers.h>
#include <utils/misc.h>
#include <utils/String8.h>
#include <utils/Log.h>

extern "C" {
#include "crypto_scrypt.h"
}

namespace android {

static jbyteArray android_security_Scrypt_nativeScrypt(JNIEnv* env, jobject, jbyteArray password, jbyteArray salt, jint N, jint r, jint p, jint outLen) {
    if (!password || !salt) {
        return NULL;
    }

    int passwordLen = env->GetArrayLength(password);
    int saltLen = env->GetArrayLength(salt);
    jbyteArray ret = env->NewByteArray(outLen);

    jbyte* passwordPtr = (jbyte*)env->GetByteArrayElements(password, NULL);
    jbyte* saltPtr = (jbyte*)env->GetByteArrayElements(salt, NULL);
    jbyte* retPtr = (jbyte*)env->GetByteArrayElements(ret, NULL);

    int rc = crypto_scrypt((const uint8_t *)passwordPtr, passwordLen,
                       (const uint8_t *)saltPtr, saltLen, N, r, p, (uint8_t *)retPtr,
                       outLen);
    env->ReleaseByteArrayElements(password, passwordPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(salt, saltPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(ret, retPtr, 0);

    if (!rc) {
        return ret;
    } else {
        SLOGE("scrypt failed");
        return NULL;
    }
}

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"nativeScrypt", "([B[BIIII)[B", (void*)android_security_Scrypt_nativeScrypt},
};

int register_android_security_Scrypt(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "android/security/Scrypt",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */
