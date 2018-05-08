/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "BootParameters.h"

#define LOG_TAG "BootParameters"

#include <errno.h>
#include <fcntl.h>

#include <android-base/file.h>
#include <utils/Log.h>

using android::base::ReadFileToString;
using android::base::RemoveFileIfExists;
using Json::Reader;
using Json::Value;

namespace android {

namespace {

// Keys for supporting a silent boot and user-defined BootAction parameters.
constexpr const char *kKeySilentBoot = "silent_boot";
constexpr const char* kKeyParams = "params";

constexpr const char* kNextBootFile = "/data/misc/bootanimation/next_boot.json";
constexpr const char* kLastBootFile = "/data/misc/bootanimation/last_boot.json";

void swapBootConfigs() {
    // rename() will fail if next_boot.json doesn't exist, so delete
    // last_boot.json manually first.
    std::string err;
    if (!RemoveFileIfExists(kLastBootFile, &err))
        ALOGE("Unable to delete last boot file: %s", err.c_str());

    if (rename(kNextBootFile, kLastBootFile) && errno != ENOENT)
        ALOGE("Unable to swap boot files: %s", strerror(errno));

    int fd = open(kNextBootFile, O_CREAT, DEFFILEMODE);
    if (fd == -1) {
        ALOGE("Unable to create next boot file: %s", strerror(errno));
    } else {
        // Make next_boot.json writable to everyone so DeviceManagementService
        // can save saved_parameters there.
        if (fchmod(fd, DEFFILEMODE))
            ALOGE("Unable to set next boot file permissions: %s", strerror(errno));
        close(fd);
    }
}

}  // namespace

BootParameters::BootParameters() {
    swapBootConfigs();
    loadParameters();
}

void BootParameters::loadParameters() {
    std::string contents;
    if (!ReadFileToString(kLastBootFile, &contents)) {
        if (errno != ENOENT)
            ALOGE("Unable to read from %s: %s", kLastBootFile, strerror(errno));

        return;
    }

    loadParameters(contents);
}

// If the boot parameters -
// - File is missing, we assume a normal, non-silent boot.
// - Are well-formed, initially assume a normal, non-silent boot and parse.
void BootParameters::loadParameters(const std::string& raw_json) {
  if (!Reader().parse(raw_json, mJson)) {
    return;
  }

  parseBootParameters();
}

void BootParameters::parseBootParameters() {
    // A missing key returns a safe, missing value.
    // Ignore invalid or missing JSON parameters.
    Value &jsonValue = mJson[kKeySilentBoot];
    if (jsonValue.isBool()) {
        mIsSilentBoot = jsonValue.asBool();
    }

    jsonValue = mJson[kKeyParams];
    if (jsonValue.isObject()) {
        // getMemberNames returns a copy of the keys which must be stored.
        mKeys = jsonValue.getMemberNames();
        for (auto &key : mKeys) {
            Value &value = jsonValue[key];
            if (value.isString()) {
                mParameters.push_back(
                    {.key = key.c_str(), .value = value.asCString()});
            }
        }
    }
}

}  // namespace android
