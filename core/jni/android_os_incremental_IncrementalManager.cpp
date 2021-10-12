/*
 * Copyright (C) 2019 The Android Open Source Project
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

#define LOG_TAG "incremental_manager-jni"

#include "core_jni_helpers.h"
#include "incfs_ndk.h"
#include "jni.h"
#include "nativehelper/JNIHelp.h"

#include <iterator>
#include <memory>

namespace android {

static jboolean nativeIsEnabled(JNIEnv* env, jobject clazz) {
    return IncFs_IsEnabled();
}

static jboolean nativeIsV2Available(JNIEnv* env, jobject clazz) {
    return !!(IncFs_Features() & INCFS_FEATURE_V2);
}

static jboolean nativeIsIncrementalPath(JNIEnv* env,
                                    jobject clazz,
                                    jstring javaPath) {
    ScopedUtfChars path(env, javaPath);
    return (jboolean)IncFs_IsIncFsPath(path.c_str());
}

static jboolean nativeIsIncrementalFd(JNIEnv* env, jobject clazz, jint fd) {
    return (jboolean)IncFs_IsIncFsFd(fd);
}

static jbyteArray nativeUnsafeGetFileSignature(JNIEnv* env, jobject clazz, jstring javaPath) {
    ScopedUtfChars path(env, javaPath);

    char signature[INCFS_MAX_SIGNATURE_SIZE];
    size_t size = sizeof(signature);
    if (IncFs_UnsafeGetSignatureByPath(path.c_str(), signature, &size) < 0) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(size);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, size, (const jbyte*)signature);
    }
    return result;
}

static const JNINativeMethod method_table[] =
        {{"nativeIsEnabled", "()Z", (void*)nativeIsEnabled},
         {"nativeIsV2Available", "()Z", (void*)nativeIsV2Available},
         {"nativeIsIncrementalPath", "(Ljava/lang/String;)Z", (void*)nativeIsIncrementalPath},
         {"nativeIsIncrementalFd", "(I)Z", (void*)nativeIsIncrementalFd},
         {"nativeUnsafeGetFileSignature", "(Ljava/lang/String;)[B",
          (void*)nativeUnsafeGetFileSignature}};

int register_android_os_incremental_IncrementalManager(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "android/os/incremental/IncrementalManager",
                                    method_table, std::size(method_table));
}

} // namespace android
