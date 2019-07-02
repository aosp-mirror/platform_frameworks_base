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

#include <pio/peripheral_manager_client.h>
#include <utils/Log.h>

namespace android {

BootAction::~BootAction() {
    if (mLibHandle != nullptr) {
        dlclose(mLibHandle);
    }
}

bool BootAction::init(const std::string& libraryPath,
                      const std::unique_ptr<BootParameters>& bootParameters) {
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

    // SilentBoot is considered optional, if it isn't exported by the library
    // and the boot is silent, no method is called.
    loaded = nullptr;
    if (!loadSymbol("boot_action_silent_boot", &loaded) || loaded == nullptr) {
        ALOGW("No boot_action_silent_boot found, boot action will not be "
              "executed during a silent boot.");
    } else {
        mLibSilentBoot = reinterpret_cast<libInit>(loaded);
    }

    bool result = true;
    const auto& parameters = bootParameters->getParameters();
    if (bootParameters->isSilentBoot()) {
        if (mLibSilentBoot != nullptr) {
            ALOGD("Entering boot_action_silent_boot");
            result = mLibSilentBoot(parameters.data(), parameters.size());
            ALOGD("Returned from boot_action_silent_boot");
        } else {
            ALOGW("Skipping missing boot_action_silent_boot");
        }
    } else {
        ALOGD("Entering boot_action_init");
        result = mLibInit(parameters.data(), parameters.size());
        ALOGD("Returned from boot_action_init");
    }

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
    if (*loaded == nullptr) {
        ALOGE("Unable to load symbol : %s :: %s", symbol, dlerror());
        return false;
    }
    return true;
}

}  // namespace android
