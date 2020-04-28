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

#include "jni.h"
#include "core_jni_helpers.h"

#include <openssl/crypto.h>

namespace {

static jint runSelfTest(JNIEnv* env, jobject /* clazz */) {
    return BORINGSSL_self_test();
}

static const JNINativeMethod methods[] = {
    /* name, signature, funcPtr */
    {"runSelfTest", "()I", (void*) runSelfTest}
};

} // anonymous namespace

namespace android {

int register_android_server_devicepolicy_CryptoTestHelper(JNIEnv *env) {
    return jniRegisterNativeMethods(
            env, "com/android/server/devicepolicy/CryptoTestHelper", methods, NELEM(methods));
}

} // namespace android