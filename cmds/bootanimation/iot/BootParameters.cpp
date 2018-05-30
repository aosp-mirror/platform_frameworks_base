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
#include <json/json.h>
#include <utils/Log.h>

using android::base::ReadFileToString;
using android::base::RemoveFileIfExists;
using android::base::WriteStringToFile;
using Json::ArrayIndex;
using Json::Reader;
using Json::Value;

namespace android {

namespace {

// Keys for deprecated parameters. Devices that OTA from N to O and that used
// the hidden BootParameters API will store these in the JSON blob. To support
// the transition from N to O, these keys are mapped to the new parameters.
constexpr const char *kKeyLegacyVolume = "volume";
constexpr const char *kKeyLegacyAnimationsDisabled = "boot_animation_disabled";
constexpr const char *kKeyLegacyParamNames = "param_names";
constexpr const char *kKeyLegacyParamValues = "param_values";

constexpr const char *kNextBootFile = "/data/misc/bootanimation/next_boot.proto";
constexpr const char *kLastBootFile = "/data/misc/bootanimation/last_boot.proto";

constexpr const char *kLegacyNextBootFile = "/data/misc/bootanimation/next_boot.json";
constexpr const char *kLegacyLastBootFile = "/data/misc/bootanimation/last_boot.json";

void removeLegacyFiles() {
    std::string err;
    if (!RemoveFileIfExists(kLegacyLastBootFile, &err)) {
        ALOGW("Unable to delete %s: %s", kLegacyLastBootFile, err.c_str());
    }

    err.clear();
    if (!RemoveFileIfExists(kLegacyNextBootFile, &err)) {
        ALOGW("Unable to delete %s: %s", kLegacyNextBootFile, err.c_str());
    }
}

void createNextBootFile() {
    errno = 0;
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

// Renames the 'next' boot file to the 'last' file and reads its contents.
bool BootParameters::swapAndLoadBootConfigContents(const char *lastBootFile,
                                                   const char *nextBootFile,
                                                   std::string *contents) {
    if (!ReadFileToString(nextBootFile, contents)) {
        RemoveFileIfExists(lastBootFile);
        return false;
    }

    errno = 0;
    if (rename(nextBootFile, lastBootFile) && errno != ENOENT)
        ALOGE("Unable to swap boot files: %s", strerror(errno));

    return true;
}

BootParameters::BootParameters() {
    loadParameters();
}

// Saves the boot parameters state to disk so the framework can read it.
void BootParameters::storeParameters() {
    errno = 0;
    if (!WriteStringToFile(mProto.SerializeAsString(), kLastBootFile)) {
        ALOGE("Failed to write boot parameters to %s: %s", kLastBootFile, strerror(errno));
    }

    // WriteStringToFile sets the file permissions to 0666, but these are not
    // honored by the system.
    errno = 0;
    if (chmod(kLastBootFile, S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH)) {
        ALOGE("Failed to set permissions for %s: %s", kLastBootFile, strerror(errno));
    }
}

// Load the boot parameters from disk, try the old location and format if the
// file does not exist. Note:
// - Parse errors result in defaults being used (a normal boot).
// - Legacy boot parameters default to a silent boot.
void BootParameters::loadParameters() {
    // Precedence is given to the new file format (.proto).
    std::string contents;
    if (swapAndLoadBootConfigContents(kLastBootFile, kNextBootFile, &contents)) {
        parseBootParameters(contents);
    } else if (swapAndLoadBootConfigContents(kLegacyLastBootFile, kLegacyNextBootFile, &contents)) {
        parseLegacyBootParameters(contents);
        storeParameters();
        removeLegacyFiles();
    }

    createNextBootFile();
}

void BootParameters::parseBootParameters(const std::string &contents) {
    if (!mProto.ParseFromString(contents)) {
        ALOGW("Failed to parse parameters from %s", kLastBootFile);
        return;
    }

    loadStateFromProto();
}

// Parses the JSON in the proto.
void BootParameters::parseLegacyBootParameters(const std::string &contents) {
    Value json;
    if (!Reader().parse(contents, json)) {
        ALOGW("Failed to parse parameters from %s", kLegacyLastBootFile);
        return;
    }

    int volume = 0;
    bool bootAnimationDisabled = true;

    Value &jsonValue = json[kKeyLegacyVolume];
    if (jsonValue.isIntegral()) {
        volume = jsonValue.asInt();
    }

    jsonValue = json[kKeyLegacyAnimationsDisabled];
    if (jsonValue.isIntegral()) {
        bootAnimationDisabled = jsonValue.asInt() == 1;
    }

    // Assume a silent boot unless all of the following are true -
    // 1. The volume is neither 0 nor -1000 (the legacy default value).
    // 2. The boot animations are explicitly enabled.
    // Note: brightness was never used.
    mProto.set_silent_boot((volume == 0) || (volume == -1000) || bootAnimationDisabled);

    Value &keys = json[kKeyLegacyParamNames];
    Value &values = json[kKeyLegacyParamValues];
    if (keys.isArray() && values.isArray() && (keys.size() == values.size())) {
        for (ArrayIndex i = 0; i < keys.size(); ++i) {
            auto &key = keys[i];
            auto &value = values[i];
            if (key.isString() && value.isString()) {
                auto userParameter = mProto.add_user_parameter();
                userParameter->set_key(key.asString());
                userParameter->set_value(value.asString());
            }
        }
    }

    loadStateFromProto();
}

void BootParameters::loadStateFromProto() {
    // A missing key returns a safe, default value.
    // Ignore invalid or missing parameters.
    mIsSilentBoot = mProto.silent_boot();

    for (const auto &param : mProto.user_parameter()) {
        mParameters.push_back({.key = param.key().c_str(), .value = param.value().c_str()});
    }
}

}  // namespace android
