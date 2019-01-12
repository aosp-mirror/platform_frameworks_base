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

#include <android-base/unique_fd.h>

// TODO(112037636): Always include once fsverity.h is upstreamed and backported.
#define HAS_FSVERITY 0

#if HAS_FSVERITY
#include <linux/fsverity.h>

const int kSha256Bytes = 32;
#endif

namespace android {

namespace {

class JavaByteArrayHolder {
  public:
    static JavaByteArrayHolder* newArray(JNIEnv* env, jsize size) {
        return new JavaByteArrayHolder(env, size);
    }

    jbyte* getRaw() {
        return mElements;
    }

    jbyteArray release() {
        mEnv->ReleaseByteArrayElements(mBytes, mElements, 0);
        mElements = nullptr;
        return mBytes;
    }

  private:
    JavaByteArrayHolder(JNIEnv* env, jsize size) {
        mEnv = env;
        mBytes = mEnv->NewByteArray(size);
        mElements = mEnv->GetByteArrayElements(mBytes, nullptr);
        memset(mElements, 0, size);
    }

    virtual ~JavaByteArrayHolder() {
        LOG_ALWAYS_FATAL_IF(mElements == nullptr, "Elements are not released");
    }

    JNIEnv* mEnv;
    jbyteArray mBytes;
    jbyte* mElements;
};

int enableFsverity(JNIEnv* env, jobject /* clazz */, jstring filePath) {
#if HAS_FSVERITY
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    ::android::base::unique_fd rfd(open(path, O_RDONLY | O_CLOEXEC));
    if (rfd.get() < 0) {
      return errno;
    }
    if (ioctl(rfd.get(), FS_IOC_ENABLE_VERITY, nullptr) < 0) {
      return errno;
    }
    return 0;
#else
    LOG_ALWAYS_FATAL("fs-verity is used while not enabled");
    return ENOSYS;
#endif  // HAS_FSVERITY
}

int measureFsverity(JNIEnv* env, jobject /* clazz */, jstring filePath) {
#if HAS_FSVERITY
    auto raii = JavaByteArrayHolder::newArray(env, sizeof(fsverity_digest) + kSha256Bytes);
    fsverity_digest* data = reinterpret_cast<fsverity_digest*>(raii->getRaw());
    data->digest_size = kSha256Bytes;  // the only input/output parameter

    const char* path = env->GetStringUTFChars(filePath, nullptr);
    ::android::base::unique_fd rfd(open(path, O_RDONLY | O_CLOEXEC));
    if (rfd.get() < 0) {
      return errno;
    }
    if (ioctl(rfd.get(), FS_IOC_MEASURE_VERITY, data) < 0) {
      return errno;
    }
    return 0;
#else
    LOG_ALWAYS_FATAL("fs-verity is used while not enabled");
    return ENOSYS;
#endif  // HAS_FSVERITY
}

jbyteArray constructFsveritySignedData(JNIEnv* env, jobject /* clazz */, jbyteArray digest) {
#if HAS_FSVERITY
    auto raii = JavaByteArrayHolder::newArray(env, sizeof(fsverity_digest_disk) + kSha256Bytes);
    fsverity_digest_disk* data = reinterpret_cast<fsverity_digest_disk*>(raii->getRaw());

    data->digest_algorithm = FS_VERITY_ALG_SHA256;
    data->digest_size = kSha256Bytes;
    if (env->GetArrayLength(digest) != kSha256Bytes) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", "Invalid hash size of %d",
                          env->GetArrayLength(digest));
        return 0;
    }
    const jbyte* src = env->GetByteArrayElements(digest, nullptr);
    memcpy(data->digest, src, kSha256Bytes);

    return raii->release();
#else
    LOG_ALWAYS_FATAL("fs-verity is used while not enabled");
    return 0;
#endif  // HAS_FSVERITY
}


jbyteArray constructFsverityDescriptor(JNIEnv* env, jobject /* clazz */, jlong fileSize) {
#if HAS_FSVERITY
    auto raii = JavaByteArrayHolder::newArray(env, sizeof(fsverity_descriptor));
    fsverity_descriptor* desc = reinterpret_cast<fsverity_descriptor*>(raii->getRaw());

    memcpy(desc->magic, FS_VERITY_MAGIC, sizeof(desc->magic));
    desc->major_version = 1;
    desc->minor_version = 0;
    desc->log_data_blocksize = 12;
    desc->log_tree_blocksize = 12;
    desc->data_algorithm = FS_VERITY_ALG_SHA256;
    desc->tree_algorithm = FS_VERITY_ALG_SHA256;
    desc->flags = 0;
    desc->orig_file_size = fileSize;
    desc->auth_ext_count = 1;

    return raii->release();
#else
    LOG_ALWAYS_FATAL("fs-verity is used while not enabled");
    return 0;
#endif  // HAS_FSVERITY
}

jbyteArray constructFsverityExtension(JNIEnv* env, jobject /* clazz */, jshort extensionId,
        jint extensionDataSize) {
#if HAS_FSVERITY
    auto raii = JavaByteArrayHolder::newArray(env, sizeof(fsverity_extension));
    fsverity_extension* ext = reinterpret_cast<fsverity_extension*>(raii->getRaw());

    ext->length = sizeof(fsverity_extension) + extensionDataSize;
    ext->type = extensionId;

    return raii->release();
#else
    LOG_ALWAYS_FATAL("fs-verity is used while not enabled");
    return 0;
#endif  // HAS_FSVERITY
}

jbyteArray constructFsverityFooter(JNIEnv* env, jobject /* clazz */,
        jint offsetToDescriptorHead) {
#if HAS_FSVERITY
    auto raii = JavaByteArrayHolder::newArray(env, sizeof(fsverity_footer));
    fsverity_footer* footer = reinterpret_cast<fsverity_footer*>(raii->getRaw());

    footer->desc_reverse_offset = offsetToDescriptorHead + sizeof(fsverity_footer);
    memcpy(footer->magic, FS_VERITY_MAGIC, sizeof(footer->magic));

    return raii->release();
#else
    LOG_ALWAYS_FATAL("fs-verity is used while not enabled");
    return 0;
#endif  // HAS_FSVERITY
}

const JNINativeMethod sMethods[] = {
    { "enableFsverityNative", "(Ljava/lang/String;)I", (void *)enableFsverity },
    { "measureFsverityNative", "(Ljava/lang/String;)I", (void *)measureFsverity },
    { "constructFsveritySignedDataNative", "([B)[B", (void *)constructFsveritySignedData },
    { "constructFsverityDescriptorNative", "(J)[B", (void *)constructFsverityDescriptor },
    { "constructFsverityExtensionNative", "(SI)[B", (void *)constructFsverityExtension },
    { "constructFsverityFooterNative", "(I)[B", (void *)constructFsverityFooter },
};

}  // namespace

int register_android_server_security_VerityUtils(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "com/android/server/security/VerityUtils", sMethods, NELEM(sMethods));
}

}  // namespace android
