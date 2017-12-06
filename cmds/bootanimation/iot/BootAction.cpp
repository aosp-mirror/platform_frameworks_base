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

#include <android-base/strings.h>
#include <cpu-features.h>
#include <dlfcn.h>
#include <pio/peripheral_manager_client.h>
#include <utils/Log.h>

using android::base::Split;
using android::base::Join;
using android::base::StartsWith;
using android::base::EndsWith;

namespace android {

BootAction::~BootAction() {
    if (mLibHandle != nullptr) {
        dlclose(mLibHandle);
    }
}

bool BootAction::init(const std::string& libraryPath, const std::string& config) {
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

    std::string path_to_lib = libraryPath;
    if (!parseConfig(config, &path_to_lib)) {
        return false;
    }

    ALOGI("Loading boot action %s", path_to_lib.c_str());
    mLibHandle = dlopen(path_to_lib.c_str(), RTLD_NOW);
    if (mLibHandle == nullptr) {
        ALOGE("Unable to load library at %s :: %s",
              path_to_lib.c_str(), dlerror());
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
    bool result = mLibInit();
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


bool BootAction::parseConfig(const std::string& config, std::string* path) {
    auto lines = Split(config, "\n");

    if (lines.size() < 1) {
        ALOGE("Config format invalid, expected one line, found %d",
              lines.size());
        return false;
    }

    size_t lineNumber = 0;
    auto& line1 = lines.at(lineNumber);
    while (StartsWith(line1, "#")) {
      if (lines.size() < ++lineNumber) {
        ALOGE("Config file contains no non-comment lines.");
        return false;
      }
      line1 = lines.at(lineNumber);
    }

    const std::string libraryNameToken("LIBRARY_NAME=");
    if (!StartsWith(line1, libraryNameToken.c_str())) {
        ALOGE("Invalid config format, expected second line to start  with %s "
              "Instead found: %s", libraryNameToken.c_str(), line1.c_str());
        return false;
    }

    std::string libraryName = line1.substr(libraryNameToken.length());

    *path += "/";
    *path += architectureDirectory();
    *path += "/";
    *path += libraryName;

    return true;
}

const char* BootAction::architectureDirectory() {
  switch(android_getCpuFamily()) {
      case ANDROID_CPU_FAMILY_ARM:
          return "arm";
      case ANDROID_CPU_FAMILY_X86:
          return "x86";
      case ANDROID_CPU_FAMILY_MIPS:
          return "mips";
      case ANDROID_CPU_FAMILY_ARM64:
          return "arm64";
      case ANDROID_CPU_FAMILY_X86_64:
          return "x86_64";
      case ANDROID_CPU_FAMILY_MIPS64:
          return "mips64";
      default:
          ALOGE("Unsupported cpu family: %d", android_getCpuFamily());
          return "";
  }
}

}  // namespace android
