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
#include <utils/threads.h>

#include "BootAnimation.h"

using namespace android;

// ---------------------------------------------------------------------------

int main()
{
    setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_DISPLAY);

    char value[PROPERTY_VALUE_MAX];
    property_get("debug.sf.nobootanimation", value, "0");
    int noBootAnimation = atoi(value);
    if (!noBootAnimation) {
        property_get("ro.boot.quiescent", value, "0");
        noBootAnimation = atoi(value);
    }
    ALOGI_IF(noBootAnimation,  "boot animation disabled");
    if (!noBootAnimation) {

        sp<ProcessState> proc(ProcessState::self());
        ProcessState::self()->startThreadPool();

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

        // create the boot animation object
        sp<BootAnimation> boot = new BootAnimation();

        IPCThreadState::self()->joinThreadPool();
    }
    return 0;
}
