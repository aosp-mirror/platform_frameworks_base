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

#ifndef FRAMEWORKS_BASE_CORE_JNI_MISC_UTILS_H_
#define FRAMEWORKS_BASE_CORE_JNI_MISC_UTILS_H_

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <fcntl.h>
#include <linux/fs.h>

namespace {
static constexpr const char* kExternalStorageSdcardfs = "external_storage.sdcardfs.enabled";

static bool IsFilesystemSupported(const std::string& fsType) {
    std::string supported;
    if (!android::base::ReadFileToString("/proc/filesystems", &supported)) {
        ALOGE("Failed to read supported filesystems");
        return false;
    }
    return supported.find(fsType + "\n") != std::string::npos;
}

static inline bool IsSdcardfsUsed() {
    return IsFilesystemSupported("sdcardfs") &&
            android::base::GetBoolProperty(kExternalStorageSdcardfs, true);
}
} // namespace
#endif // FRAMEWORKS_BASE_CORE_JNI_MISC_UTILS_H_
