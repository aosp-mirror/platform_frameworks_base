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

#define LOG_TAG "IotBootAnimation"

#include <android-base/file.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <cutils/properties.h>
#include <sys/resource.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <BootAnimation.h>

#include "BootAction.h"
#include "BootAnimationUtil.h"

using namespace android;
using android::base::ReadFileToString;

// Create a typedef for readability.
typedef android::BootAnimation::Animation Animation;

namespace {

class BootActionAnimationCallbacks : public android::BootAnimation::Callbacks {public:
    void init(const Vector<Animation::Part>&) override {
        std::string library_path("/oem/lib/");

        // This value is optionally provided by the user and will be written to
        // /oem/oem.prop.
        char property[PROP_VALUE_MAX] = {0};
        if (property_get("ro.oem.bootactions.lib", property, "") < 1) {
            ALOGI("No bootaction specified");
            return;
        }
        library_path += property;

        mBootAction = new BootAction();
        if (!mBootAction->init(library_path)) {
            mBootAction = NULL;
        }
    };

    void playPart(int partNumber, const Animation::Part&, int playNumber) override {
        if (mBootAction != nullptr) {
            mBootAction->startPart(partNumber, playNumber);
        }
    };

    void shutdown() override {
        if (mBootAction != nullptr) {
            // If we have a bootaction we want to wait until we are actually
            // told to shut down. If the animation exits early keep the action
            // running.
            char value[PROPERTY_VALUE_MAX] = {0};
            for (int exitRequested = 0; exitRequested == 0; ) {
                property_get("service.bootanim.exit", value, "0");
                exitRequested = atoi(value);

                // Poll value at 10hz.
                if (exitRequested == 0) {
                  usleep(100000);
                }
            }

            mBootAction->shutdown();
            // Give it two seconds to shut down.
            sleep(2);
            mBootAction = nullptr;
        }
    };

private:
    sp<BootAction> mBootAction = nullptr;
};

}  // namespace

int main() {
    setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_DISPLAY);

    // TODO(b/65462981): Should we set brightness/volume here in case the boot
    // animation is disabled?
    BootAction::swapBootConfigs();

    if (bootAnimationDisabled()) {
        ALOGI("boot animation disabled");
        return 0;
    }

    waitForSurfaceFlinger();

    sp<ProcessState> proc(ProcessState::self());
    ProcessState::self()->startThreadPool();

    sp<BootAnimation> boot = new BootAnimation(new BootActionAnimationCallbacks());

    IPCThreadState::self()->joinThreadPool();
    return 0;
}
