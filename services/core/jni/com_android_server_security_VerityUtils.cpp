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
#include "jni.h"
#include <utils/Log.h>

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <type_traits>

#include <android-base/unique_fd.h>

// TODO(112037636): Always include once fsverity.h is upstreamed.
#if __has_include(<linux/fsverity.h>)
#include <linux/fsverity.h>
#else

#include <linux/limits.h>
#include <linux/ioctl.h>
#include <linux/types.h>

#define FS_VERITY_HASH_ALG_SHA256	1

struct fsverity_enable_arg {
	__u32 version;
	__u32 hash_algorithm;
	__u32 block_size;
	__u32 salt_size;
	__u64 salt_ptr;
	__u32 sig_size;
	__u32 __reserved1;
	__u64 sig_ptr;
	__u64 __reserved2[11];
};

struct fsverity_digest {
    __u16 digest_algorithm;
    __u16 digest_size; /* input/output */
    __u8 digest[];
};

#define FS_IOC_ENABLE_VERITY	_IOW('f', 133, struct fsverity_enable_arg)
#define FS_IOC_MEASURE_VERITY	_IOWR('f', 134, struct fsverity_digest)

#endif

const int kSha256Bytes = 32;

namespace android {

namespace {

int enableFsverity(JNIEnv* env, jobject /* clazz */, jstring filePath, jbyteArray signature) {
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    ::android::base::unique_fd rfd(open(path, O_RDONLY | O_CLOEXEC));
    env->ReleaseStringUTFChars(filePath, path);
    if (rfd.get() < 0) {
      return errno;
    }

    fsverity_enable_arg arg = {};
    arg.version = 1;
    arg.hash_algorithm = FS_VERITY_HASH_ALG_SHA256;
    arg.block_size = 4096;
    arg.salt_size = 0;
    arg.salt_ptr = reinterpret_cast<uintptr_t>(nullptr);
    arg.sig_size = env->GetArrayLength(signature);
    arg.sig_ptr = reinterpret_cast<uintptr_t>(signature);

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

    const char* path = env->GetStringUTFChars(filePath, nullptr);
    ::android::base::unique_fd rfd(open(path, O_RDONLY | O_CLOEXEC));
    env->ReleaseStringUTFChars(filePath, path);
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
