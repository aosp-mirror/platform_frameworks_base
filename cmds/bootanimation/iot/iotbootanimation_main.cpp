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
        // Create and initialize BootActions if we have a boot_action.conf.
        std::string bootActionConf;
        if (ReadFileToString("/oem/app/etc/boot_action.conf", &bootActionConf)) {
            mBootAction = new BootAction();
            if (!mBootAction->init("/oem/app/lib", bootActionConf)) {
                mBootAction = NULL;
            }
        } else {
            ALOGI("No boot actions specified");
        }

    };

    void playPart(int partNumber, const Animation::Part&, int playNumber) override {
        if (mBootAction != nullptr) {
            mBootAction->startPart(partNumber, playNumber);
        }
    };

    void shutdown() override {
        if (mBootAction != nullptr) {
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
