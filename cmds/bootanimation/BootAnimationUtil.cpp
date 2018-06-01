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

#include "BootAnimationUtil.h"

#include <vector>
#include <inttypes.h>

#include <binder/IServiceManager.h>
#include <cutils/properties.h>
#include <utils/Log.h>
#include <utils/SystemClock.h>
#include <android-base/properties.h>

namespace android {
namespace {

static constexpr char PLAY_SOUND_PROP_NAME[] = "persist.sys.bootanim.play_sound";
static constexpr char BOOT_COMPLETED_PROP_NAME[] = "sys.boot_completed";
static constexpr char POWER_CTL_PROP_NAME[] = "sys.powerctl";
static constexpr char BOOTREASON_PROP_NAME[] = "ro.boot.bootreason";
static const std::vector<std::string> PLAY_SOUND_BOOTREASON_BLACKLIST {
  "kernel_panic",
  "Panic",
  "Watchdog",
};

}  // namespace


bool bootAnimationDisabled() {
    char value[PROPERTY_VALUE_MAX];
    property_get("debug.sf.nobootanimation", value, "0");
    if (atoi(value) > 0) {
        return true;
    }

    property_get("ro.boot.quiescent", value, "0");
    return atoi(value) > 0;
}

void waitForSurfaceFlinger() {
    // TODO: replace this with better waiting logic in future, b/35253872
    int64_t waitStartTime = elapsedRealtime();
    sp<IServiceManager> sm = defaultServiceManager();
    const String16 name("SurfaceFlinger");
    const int SERVICE_WAIT_SLEEP_MS = 100;
    const int LOG_PER_RETRIES = 10;
    int retry = 0;
    while (sm->checkService(name) == nullptr) {
        retry++;
        if ((retry % LOG_PER_RETRIES) == 0) {
            ALOGW("Waiting for SurfaceFlinger, waited for %" PRId64 " ms",
                  elapsedRealtime() - waitStartTime);
        }
        usleep(SERVICE_WAIT_SLEEP_MS * 1000);
    };
    int64_t totalWaited = elapsedRealtime() - waitStartTime;
    if (totalWaited > SERVICE_WAIT_SLEEP_MS) {
        ALOGI("Waiting for SurfaceFlinger took %" PRId64 " ms", totalWaited);
    }
}

bool playSoundsAllowed() {
    // Only play sounds for system boots, not runtime restarts.
    if (android::base::GetBoolProperty(BOOT_COMPLETED_PROP_NAME, false)) {
        return false;
    }
    // no audio while shutting down
    if (!android::base::GetProperty(POWER_CTL_PROP_NAME, "").empty()) {
        return false;
    }
    // Read the system property to see if we should play the sound.
    // If it's not present, default to allowed.
    if (!property_get_bool(PLAY_SOUND_PROP_NAME, 1)) {
        return false;
    }

    // Don't play sounds if this is a reboot due to an error.
    char bootreason[PROPERTY_VALUE_MAX];
    if (property_get(BOOTREASON_PROP_NAME, bootreason, nullptr) > 0) {
        for (const auto& str : PLAY_SOUND_BOOTREASON_BLACKLIST) {
            if (strcasecmp(str.c_str(), bootreason) == 0) {
                return false;
            }
        }
    }
    return true;
}

}  // namespace android
