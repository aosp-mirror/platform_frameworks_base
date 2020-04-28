/*
 * Copyright (C) 2016 The Android Open Source Project
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
#include "Log.h"

#include "IncidentService.h"

#include <binder/IInterface.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <binder/Status.h>
#include <utils/Looper.h>
#include <utils/StrongPointer.h>

#include <sys/stat.h>
#include <sys/types.h>

using namespace android;
using namespace android::os::incidentd;

// ================================================================================
int main(int /*argc*/, char** /*argv*/) {
    // Set up the looper
    sp<Looper> looper(Looper::prepare(0 /* opts */));

    // Set up the binder
    sp<ProcessState> ps(ProcessState::self());
    ps->setThreadPoolMaxThreadCount(1);  // everything is oneway, let it queue and save ram
    ps->startThreadPool();
    ps->giveThreadPoolName();
    IPCThreadState::self()->disableBackgroundScheduling(true);

    // Create the service
    sp<IncidentService> service = new IncidentService(looper);
    if (defaultServiceManager()->addService(String16("incident"), service, false,
            IServiceManager::DUMP_FLAG_PRIORITY_NORMAL | IServiceManager::DUMP_FLAG_PROTO) != 0) {
        ALOGE("Failed to add service");
        return -1;
    }

    // Loop forever -- the reports run on this thread in a handler, and the
    // binder calls remain responsive in their pool of one thread.
    while (true) {
        looper->pollAll(-1 /* timeoutMillis */);
    }
    ALOGW("incidentd escaped from its loop.");

    return 1;
}
