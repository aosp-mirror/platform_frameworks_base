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

#include "BootAction.h"

#define LOG_TAG "BootAction"

#include <dlfcn.h>
#include <fcntl.h>

#include <map>

#include <android-base/file.h>
#include <android-base/strings.h>
#include <base/json/json_parser.h>
#include <base/json/json_value_converter.h>
#include <cpu-features.h>
#include <pio/peripheral_manager_client.h>
#include <utils/Log.h>

using android::base::ReadFileToString;
using android::base::RemoveFileIfExists;
using android::base::Split;
using android::base::Join;
using android::base::StartsWith;
using android::base::EndsWith;
using base::JSONReader;
using base::Value;

namespace android {

// Brightness and volume are stored as integer strings in next_boot.json.
// They are divided by this constant to produce the actual float values in
// range [0.0, 1.0]. This constant must match its counterpart in
// DeviceManager.
constexpr const float kFloatScaleFactor = 1000.0f;

constexpr const char* kNextBootFile = "/data/misc/bootanimation/next_boot.json";
constexpr const char* kLastBootFile = "/data/misc/bootanimation/last_boot.json";

bool loadParameters(BootAction::SavedBootParameters* parameters)
{
    std::string contents;
    if (!ReadFileToString(kLastBootFile, &contents)) {
        if (errno != ENOENT)
            ALOGE("Unable to read from %s: %s", kLastBootFile, strerror(errno));

        return false;
    }

    std::unique_ptr<Value> json = JSONReader::Read(contents);
    if (json.get() == nullptr) return false;

    JSONValueConverter<BootAction::SavedBootParameters> converter;
    if (!converter.Convert(*(json.get()), parameters)) return false;

    return true;
}

void BootAction::SavedBootParameters::RegisterJSONConverter(
        JSONValueConverter<SavedBootParameters> *converter) {
    converter->RegisterIntField("brightness", &SavedBootParameters::brightness);
    converter->RegisterIntField("volume", &SavedBootParameters::volume);
    converter->RegisterRepeatedString("param_names",
                                      &SavedBootParameters::param_names);
    converter->RegisterRepeatedString("param_values",
                                      &SavedBootParameters::param_values);
}

BootAction::~BootAction() {
    if (mLibHandle != nullptr) {
        dlclose(mLibHandle);
    }
}

void BootAction::swapBootConfigs() {
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
        // Make next_boot.json writible to everyone so DeviceManagementService
        // can save parameters there.
        if (fchmod(fd, DEFFILEMODE))
            ALOGE("Unable to set next boot file permissions: %s", strerror(errno));
        close(fd);
    }
}

bool BootAction::init(const std::string& libraryPath) {
    APeripheralManagerClient* client = nullptr;
    ALOGD("Connecting to peripheralmanager");
    // Wait for peripheral manager to come up.
    while (client == nullptr) {
        client = APeripheralManagerClient_new();
        if (client == nullptr) {
          ALOGV("peripheralmanager is not up, sleeping before we check again.");
          usleep(250000);
        }
    }
    ALOGD("Peripheralmanager is up.");
    APeripheralManagerClient_delete(client);

    float brightness = -1.0f;
    float volume = -1.0f;
    std::vector<BootParameter> parameters;
    SavedBootParameters saved_parameters;

    if (loadParameters(&saved_parameters)) {
        // TODO(b/65462981): Do something with brightness and volume?
        brightness = saved_parameters.brightness / kFloatScaleFactor;
        volume = saved_parameters.volume / kFloatScaleFactor;

        if (saved_parameters.param_names.size() == saved_parameters.param_values.size()) {
            for (size_t i = 0; i < saved_parameters.param_names.size(); i++) {
                parameters.push_back({
                        .key = saved_parameters.param_names[i]->c_str(),
                        .value = saved_parameters.param_values[i]->c_str()
                });
            }
        } else {
            ALOGW("Parameter names and values size mismatch");
        }
    }

    ALOGI("Loading boot action %s", libraryPath.c_str());
    mLibHandle = dlopen(libraryPath.c_str(), RTLD_NOW);
    if (mLibHandle == nullptr) {
        ALOGE("Unable to load library at %s :: %s",
              libraryPath.c_str(), dlerror());
        return false;
    }

    void* loaded = nullptr;
    if (!loadSymbol("boot_action_init", &loaded) || loaded == nullptr) {
        return false;
    }
    mLibInit = reinterpret_cast<libInit>(loaded);

    loaded = nullptr;
    if (!loadSymbol("boot_action_shutdown", &loaded) || loaded == nullptr) {
        return false;
    }
    mLibShutdown = reinterpret_cast<libShutdown>(loaded);

    // StartPart is considered optional, if it isn't exported by the library
    // we will still call init and shutdown.
    loaded = nullptr;
    if (!loadSymbol("boot_action_start_part", &loaded) || loaded == nullptr) {
        ALOGI("No boot_action_start_part found, action will not be told when "
              "Animation parts change.");
    } else {
        mLibStartPart = reinterpret_cast<libStartPart>(loaded);
    }

    ALOGD("Entering boot_action_init");
    bool result = mLibInit(parameters.data(), parameters.size());
    ALOGD("Returned from boot_action_init");
    return result;
}

void BootAction::startPart(int partNumber, int playNumber) {
    if (mLibStartPart == nullptr) return;

    ALOGD("Entering boot_action_start_part");
    mLibStartPart(partNumber, playNumber);
    ALOGD("Returned from boot_action_start_part");
}

void BootAction::shutdown() {
    ALOGD("Entering boot_action_shutdown");
    mLibShutdown();
    ALOGD("Returned from boot_action_shutdown");
}

bool BootAction::loadSymbol(const char* symbol, void** loaded) {
    *loaded = dlsym(mLibHandle, symbol);
    if (loaded == nullptr) {
        ALOGE("Unable to load symbol : %s :: %s", symbol, dlerror());
        return false;
    }
    return true;
}

}  // namespace android
