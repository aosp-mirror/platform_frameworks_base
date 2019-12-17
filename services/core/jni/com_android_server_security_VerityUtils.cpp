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

#define LOG_TAG "VerityUtils"

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include "jni.h"
#include <utils/Log.h>

#include <errno.h>
#include <fcntl.h>
#include <linux/fsverity.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <type_traits>

#include <android-base/unique_fd.h>

const int kSha256Bytes = 32;

namespace android {

namespace {

int enableFsverity(JNIEnv* env, jobject /* clazz */, jstring filePath, jbyteArray signature) {
    ScopedUtfChars path(env, filePath);
    if (path.c_str() == nullptr) {
        return EINVAL;
    }
    ::android::base::unique_fd rfd(open(path.c_str(), O_RDONLY | O_CLOEXEC));
    if (rfd.get() < 0) {
        return errno;
    }
    ScopedByteArrayRO signature_bytes(env, signature);
    if (signature_bytes.get() == nullptr) {
        return EINVAL;
    }

    fsverity_enable_arg arg = {};
    arg.version = 1;
    arg.hash_algorithm = FS_VERITY_HASH_ALG_SHA256;
    arg.block_size = 4096;
    arg.salt_size = 0;
    arg.salt_ptr = reinterpret_cast<uintptr_t>(nullptr);
    arg.sig_size = signature_bytes.size();
    arg.sig_ptr = reinterpret_cast<uintptr_t>(signature_bytes.get());

    if (ioctl(rfd.get(), FS_IOC_ENABLE_VERITY, &arg) < 0) {
        return errno;
    }
    return 0;
}

int measureFsverity(JNIEnv* env, jobject /* clazz */, jstring filePath) {
    using Storage = std::aligned_storage_t<sizeof(fsverity_digest) + kSha256Bytes>;

    Storage bytes;
    fsverity_digest *data = reinterpret_cast<fsverity_digest *>(&bytes);
    data->digest_size = kSha256Bytes;  // the only input/output parameter

    ScopedUtfChars path(env, filePath);
    if (path.c_str() == nullptr) {
        return EINVAL;
    }
    ::android::base::unique_fd rfd(open(path.c_str(), O_RDONLY | O_CLOEXEC));
    if (rfd.get() < 0) {
        return errno;
    }
    if (ioctl(rfd.get(), FS_IOC_MEASURE_VERITY, data) < 0) {
        return errno;
    }
    return 0;
}

const JNINativeMethod sMethods[] = {
    { "enableFsverityNative", "(Ljava/lang/String;[B)I", (void *)enableFsverity },
    { "measureFsverityNative", "(Ljava/lang/String;)I", (void *)measureFsverity },
};

}  // namespace

int register_android_server_security_VerityUtils(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "com/android/server/security/VerityUtils", sMethods, NELEM(sMethods));
}

}  // namespace android
