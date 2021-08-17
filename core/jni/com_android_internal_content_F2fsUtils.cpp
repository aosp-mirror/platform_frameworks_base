/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define LOG_TAG "F2fsUtils"

#include "core_jni_helpers.h"

#include <nativehelper/ScopedUtfChars.h>
#include <nativehelper/jni_macros.h>

#include <sys/ioctl.h>
#include <sys/types.h>

#include <linux/f2fs.h>
#include <linux/fs.h>

#include <android-base/unique_fd.h>

#include <utils/Log.h>

#include <errno.h>
#include <fcntl.h>

#include <array>

using namespace std::literals;

namespace android {

static jlong com_android_internal_content_F2fsUtils_nativeReleaseCompressedBlocks(JNIEnv *env,
                                                                                  jclass clazz,
                                                                                  jstring path) {
    unsigned long long blkcnt;
    int ret;
    ScopedUtfChars filePath(env, path);

    android::base::unique_fd fd(open(filePath.c_str(), O_RDONLY | O_CLOEXEC, 0));
    if (fd < 0) {
        ALOGW("Failed to open file: %s (%d)\n", filePath.c_str(), errno);
        return 0;
    }

    long flags = 0;
    ret = ioctl(fd, FS_IOC_GETFLAGS, &flags);
    if (ret < 0) {
        ALOGW("Failed to get flags for file: %s (%d)\n", filePath.c_str(), errno);
        return 0;
    }
    if ((flags & FS_COMPR_FL) == 0) {
        return 0;
    }

    ret = ioctl(fd, F2FS_IOC_RELEASE_COMPRESS_BLOCKS, &blkcnt);
    if (ret < 0) {
        return -errno;
    }
    return blkcnt;
}

static const std::array gMethods = {
        MAKE_JNI_NATIVE_METHOD(
                "nativeReleaseCompressedBlocks", "(Ljava/lang/String;)J",
                com_android_internal_content_F2fsUtils_nativeReleaseCompressedBlocks),
};

int register_com_android_internal_content_F2fsUtils(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/content/F2fsUtils", gMethods.data(),
                                gMethods.size());
}

}; // namespace android
