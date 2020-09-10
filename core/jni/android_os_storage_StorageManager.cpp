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

#define LOG_TAG "StorageManager"
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/unique_fd.h>
#include <fcntl.h>
#include <linux/fs.h>

#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include "filesystem_utils.h"

namespace android {

jboolean android_os_storage_StorageManager_setQuotaProjectId(JNIEnv* env, jobject self,
                                                             jstring path, jlong projectId) {
    struct fsxattr fsx;
    ScopedUtfChars utf_chars_path(env, path);

    static bool sdcardFsSupported = IsSdcardfsUsed();
    if (sdcardFsSupported) {
        // sdcardfs doesn't support project ID quota tracking and takes care of quota
        // in a different way.
        return JNI_TRUE;
    }

    if (projectId > UINT32_MAX) {
        LOG(ERROR) << "Invalid project id: " << projectId;
        return JNI_FALSE;
    }

    android::base::unique_fd fd(
            TEMP_FAILURE_RETRY(open(utf_chars_path.c_str(), O_RDONLY | O_CLOEXEC)));
    if (fd == -1) {
        PLOG(ERROR) << "Failed to open " << utf_chars_path.c_str() << " to set project id.";
        return JNI_FALSE;
    }

    int ret = ioctl(fd, FS_IOC_FSGETXATTR, &fsx);
    if (ret == -1) {
        PLOG(ERROR) << "Failed to get extended attributes for " << utf_chars_path.c_str()
                    << " to get project id.";
        return JNI_FALSE;
    }

    fsx.fsx_projid = projectId;
    ret = ioctl(fd, FS_IOC_FSSETXATTR, &fsx);
    if (ret == -1) {
        PLOG(ERROR) << "Failed to set extended attributes for " << utf_chars_path.c_str()
                    << " to set project id.";
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gStorageManagerMethods[] = {
        {"setQuotaProjectId", "(Ljava/lang/String;J)Z",
         (void*)android_os_storage_StorageManager_setQuotaProjectId},
};

const char* const kStorageManagerPathName = "android/os/storage/StorageManager";

int register_android_os_storage_StorageManager(JNIEnv* env) {
    return RegisterMethodsOrDie(env, kStorageManagerPathName, gStorageManagerMethods,
                                NELEM(gStorageManagerMethods));
}

}; // namespace android
