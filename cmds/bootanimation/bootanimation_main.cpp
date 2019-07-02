/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "BootAnimation"

#include <stdint.h>
#include <inttypes.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>
#include <cutils/properties.h>
#include <sys/resource.h>
#include <utils/Log.h>
#include <utils/SystemClock.h>

#include "BootAnimation.h"
#include "BootAnimationUtil.h"
#include "audioplay.h"

using namespace android;

int main()
{
    setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_DISPLAY);

    bool noBootAnimation = bootAnimationDisabled();
    ALOGI_IF(noBootAnimation,  "boot animation disabled");
    if (!noBootAnimation) {

        sp<ProcessState> proc(ProcessState::self());
        ProcessState::self()->startThreadPool();

        // create the boot animation object (may take up to 200ms for 2MB zip)
        sp<BootAnimation> boot = new BootAnimation(audioplay::createAnimationCallbacks());

        waitForSurfaceFlinger();

        boot->run("BootAnimation", PRIORITY_DISPLAY);

        ALOGV("Boot animation set up. Joining pool.");

        IPCThreadState::self()->joinThreadPool();
    }
    return 0;
}
