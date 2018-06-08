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

#include <fcntl.h>

#include <string>

#include <android-base/file.h>
#include <base/json/json_parser.h>
#include <base/json/json_reader.h>
#include <base/json/json_value_converter.h>
#include <utils/Log.h>

using android::base::RemoveFileIfExists;
using android::base::ReadFileToString;
using base::JSONReader;
using base::JSONValueConverter;
using base::Value;

namespace android {

namespace {

// Brightness and volume are stored as integer strings in next_boot.json.
// They are divided by this constant to produce the actual float values in
// range [0.0, 1.0]. This constant must match its counterpart in
// DeviceManager.
constexpr const float kFloatScaleFactor = 1000.0f;

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

BootParameters::SavedBootParameters::SavedBootParameters()
    : brightness(-kFloatScaleFactor), volume(-kFloatScaleFactor) {}

void BootParameters::SavedBootParameters::RegisterJSONConverter(
        JSONValueConverter<SavedBootParameters>* converter) {
    converter->RegisterIntField("brightness", &SavedBootParameters::brightness);
    converter->RegisterIntField("volume", &SavedBootParameters::volume);
    converter->RegisterRepeatedString("param_names",
                                      &SavedBootParameters::param_names);
    converter->RegisterRepeatedString("param_values",
                                      &SavedBootParameters::param_values);
}

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

    std::unique_ptr<Value> json = JSONReader::Read(contents);
    if (json.get() == nullptr) {
        return;
    }

    JSONValueConverter<SavedBootParameters> converter;
    if (converter.Convert(*(json.get()), &mRawParameters)) {
        mBrightness = mRawParameters.brightness / kFloatScaleFactor;
        mVolume = mRawParameters.volume / kFloatScaleFactor;

        if (mRawParameters.param_names.size() == mRawParameters.param_values.size()) {
            for (size_t i = 0; i < mRawParameters.param_names.size(); i++) {
                mParameters.push_back({
                        .key = mRawParameters.param_names[i]->c_str(),
                        .value = mRawParameters.param_values[i]->c_str()
                });
            }
        } else {
            ALOGW("Parameter names and values size mismatch");
        }
    }
}

}  // namespace android
