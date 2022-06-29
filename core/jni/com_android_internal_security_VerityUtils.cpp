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

#include <android-base/unique_fd.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <linux/fsverity.h>
#include <linux/stat.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <utils/Log.h>

#include <type_traits>

#include "jni.h"

namespace android {

namespace {

int enableFsverity(JNIEnv *env, jobject /* clazz */, jstring filePath, jbyteArray signature) {
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
    arg.hash_algorithm = FS_VERITY_HASH_ALG_SHA256; // hardcoded in measureFsverity below
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

// Returns whether the file has fs-verity enabled.
// 0 if it is not present, 1 if is present, and -errno if there was an error.
int statxForFsverity(JNIEnv *env, jobject /* clazz */, jstring filePath) {
    ScopedUtfChars path(env, filePath);

    // Call statx and check STATX_ATTR_VERITY.
    struct statx out = {};
    if (statx(AT_FDCWD, path.c_str(), 0 /* flags */, STATX_ALL, &out) != 0) {
        return -errno;
    }

    if (out.stx_attributes_mask & STATX_ATTR_VERITY) {
        return (out.stx_attributes & STATX_ATTR_VERITY) != 0;
    }

    // STATX_ATTR_VERITY is not supported for the file path.
    // In this case, call ioctl(FS_IOC_GETFLAGS) and check FS_VERITY_FL.
    ::android::base::unique_fd rfd(open(path.c_str(), O_RDONLY | O_CLOEXEC));
    if (rfd.get() < 0) {
        ALOGE("open failed at %s", path.c_str());
        return -errno;
    }

    unsigned int flags;
    if (ioctl(rfd.get(), FS_IOC_GETFLAGS, &flags) < 0) {
        ALOGE("ioctl(FS_IOC_GETFLAGS) failed at %s", path.c_str());
        return -errno;
    }

    return (flags & FS_VERITY_FL) != 0;
}

int measureFsverity(JNIEnv *env, jobject /* clazz */, jstring filePath, jbyteArray digest) {
    static constexpr auto kDigestSha256 = 32;
    using Storage = std::aligned_storage_t<sizeof(fsverity_digest) + kDigestSha256>;

    Storage bytes;
    fsverity_digest *data = reinterpret_cast<fsverity_digest *>(&bytes);
    data->digest_size = kDigestSha256; // the only input/output parameter

    ScopedUtfChars path(env, filePath);
    ::android::base::unique_fd rfd(open(path.c_str(), O_RDONLY | O_CLOEXEC));
    if (rfd.get() < 0) {
        return rfd.get();
    }
    if (auto err = ioctl(rfd.get(), FS_IOC_MEASURE_VERITY, data); err < 0) {
        return err;
    }

    if (data->digest_algorithm != FS_VERITY_HASH_ALG_SHA256) {
        return -EINVAL;
    }

    if (digest != nullptr && data->digest_size > 0) {
        auto digestSize = env->GetArrayLength(digest);
        if (data->digest_size > digestSize) {
            return -E2BIG;
        }
        env->SetByteArrayRegion(digest, 0, data->digest_size, (const jbyte *)data->digest);
    }

    return 0;
}
const JNINativeMethod sMethods[] = {
        {"enableFsverityNative", "(Ljava/lang/String;[B)I", (void *)enableFsverity},
        {"statxForFsverityNative", "(Ljava/lang/String;)I", (void *)statxForFsverity},
        {"measureFsverityNative", "(Ljava/lang/String;[B)I", (void *)measureFsverity},
};

} // namespace

int register_com_android_internal_security_VerityUtils(JNIEnv *env) {
    return jniRegisterNativeMethods(env, "com/android/internal/security/VerityUtils", sMethods,
                                    NELEM(sMethods));
}

} // namespace android
