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

// Before fs-verity is upstreamed, use the current snapshot for development.
// https://git.kernel.org/pub/scm/linux/kernel/git/ebiggers/linux.git/tree/include/uapi/linux/fsverity.h?h=fsverity

#include <linux/limits.h>
#include <linux/ioctl.h>
#include <linux/types.h>

struct fsverity_digest {
    __u16 digest_algorithm;
    __u16 digest_size; /* input/output */
    __u8 digest[];
};

#define FS_IOC_ENABLE_VERITY	_IO('f', 133)
#define FS_IOC_MEASURE_VERITY	_IOWR('f', 134, struct fsverity_digest)

#define FS_VERITY_MAGIC		"FSVerity"

#define FS_VERITY_ALG_SHA256	1

struct fsverity_descriptor {
    __u8 magic[8];		/* must be FS_VERITY_MAGIC */
    __u8 major_version;	/* must be 1 */
    __u8 minor_version;	/* must be 0 */
    __u8 log_data_blocksize;/* log2(data-bytes-per-hash), e.g. 12 for 4KB */
    __u8 log_tree_blocksize;/* log2(tree-bytes-per-hash), e.g. 12 for 4KB */
    __le16 data_algorithm;	/* hash algorithm for data blocks */
    __le16 tree_algorithm;	/* hash algorithm for tree blocks */
    __le32 flags;		/* flags */
    __le32 __reserved1;	/* must be 0 */
    __le64 orig_file_size;	/* size of the original file data */
    __le16 auth_ext_count;	/* number of authenticated extensions */
    __u8 __reserved2[30];	/* must be 0 */
};

#define FS_VERITY_EXT_ROOT_HASH		1
#define FS_VERITY_EXT_PKCS7_SIGNATURE	3

struct fsverity_extension {
    __le32 length;
    __le16 type;		/* Type of this extension (see codes above) */
    __le16 __reserved;	/* Reserved, must be 0 */
};

struct fsverity_digest_disk {
    __le16 digest_algorithm;
    __le16 digest_size;
    __u8 digest[];
};

struct fsverity_footer {
    __le32 desc_reverse_offset;	/* distance to fsverity_descriptor */
    __u8 magic[8];			/* FS_VERITY_MAGIC */
} __packed;

#endif

const int kSha256Bytes = 32;

namespace android {

namespace {

class JavaByteArrayHolder {
  public:
    JavaByteArrayHolder(const JavaByteArrayHolder &other) = delete;
    JavaByteArrayHolder(JavaByteArrayHolder &&other)
          : mEnv(other.mEnv), mBytes(other.mBytes), mElements(other.mElements) {
        other.mElements = nullptr;
    }

    static JavaByteArrayHolder newArray(JNIEnv* env, jsize size) {
        return JavaByteArrayHolder(env, size);
    }

    jbyte* getRaw() {
        return mElements;
    }

    jbyteArray release() {
        mEnv->ReleaseByteArrayElements(mBytes, mElements, 0);
        mElements = nullptr;
        return mBytes;
    }

    ~JavaByteArrayHolder() {
        LOG_ALWAYS_FATAL_IF(mElements != nullptr, "Elements are not released");
    }

  private:
    JavaByteArrayHolder(JNIEnv* env, jsize size) {
        mEnv = env;
        mBytes = mEnv->NewByteArray(size);
        mElements = mEnv->GetByteArrayElements(mBytes, nullptr);
        memset(mElements, 0, size);
    }

    JNIEnv* mEnv;
    jbyteArray mBytes;
    jbyte* mElements;
};

int enableFsverity(JNIEnv* env, jobject /* clazz */, jstring filePath) {
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    ::android::base::unique_fd rfd(open(path, O_RDONLY | O_CLOEXEC));
    if (rfd.get() < 0) {
      return errno;
    }
    if (ioctl(rfd.get(), FS_IOC_ENABLE_VERITY, nullptr) < 0) {
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
    if (rfd.get() < 0) {
      return errno;
    }
    if (ioctl(rfd.get(), FS_IOC_MEASURE_VERITY, data) < 0) {
      return errno;
    }
    return 0;
}

jbyteArray constructFsveritySignedData(JNIEnv* env, jobject /* clazz */, jbyteArray digest) {
    auto raii = JavaByteArrayHolder::newArray(env, sizeof(fsverity_digest_disk) + kSha256Bytes);
    fsverity_digest_disk* data = reinterpret_cast<fsverity_digest_disk*>(raii.getRaw());

    data->digest_algorithm = FS_VERITY_ALG_SHA256;
    data->digest_size = kSha256Bytes;
    if (env->GetArrayLength(digest) != kSha256Bytes) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", "Invalid hash size of %d",
                          env->GetArrayLength(digest));
        return 0;
    }
    const jbyte* src = env->GetByteArrayElements(digest, nullptr);
    memcpy(data->digest, src, kSha256Bytes);

    return raii.release();
}


jbyteArray constructFsverityDescriptor(JNIEnv* env, jobject /* clazz */, jlong fileSize) {
    auto raii = JavaByteArrayHolder::newArray(env, sizeof(fsverity_descriptor));
    fsverity_descriptor* desc = reinterpret_cast<fsverity_descriptor*>(raii.getRaw());

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

    return raii.release();
}

jbyteArray constructFsverityExtension(JNIEnv* env, jobject /* clazz */, jshort extensionId,
        jint extensionDataSize) {
    auto raii = JavaByteArrayHolder::newArray(env, sizeof(fsverity_extension));
    fsverity_extension* ext = reinterpret_cast<fsverity_extension*>(raii.getRaw());

    ext->length = sizeof(fsverity_extension) + extensionDataSize;
    ext->type = extensionId;

    return raii.release();
}

jbyteArray constructFsverityFooter(JNIEnv* env, jobject /* clazz */,
        jint offsetToDescriptorHead) {
    auto raii = JavaByteArrayHolder::newArray(env, sizeof(fsverity_footer));
    fsverity_footer* footer = reinterpret_cast<fsverity_footer*>(raii.getRaw());

    footer->desc_reverse_offset = offsetToDescriptorHead + sizeof(fsverity_footer);
    memcpy(footer->magic, FS_VERITY_MAGIC, sizeof(footer->magic));

    return raii.release();
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
