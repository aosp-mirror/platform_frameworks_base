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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "StatsService.h"
#include "socket/StatsSocketListener.h"

#include <binder/IInterface.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <binder/Status.h>
#include <hidl/HidlTransportSupport.h>
#include <utils/Looper.h>
#include <utils/StrongPointer.h>

#include <memory>

#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

using namespace android;
using namespace android::os::statsd;

/**
 * Thread function data.
 */
struct log_reader_thread_data {
    sp<StatsService> service;
};


sp<StatsService> gStatsService = nullptr;

void sigHandler(int sig) {
    if (gStatsService != nullptr) {
        gStatsService->Terminate();
    }
}

void registerSigHandler()
{
    struct sigaction sa;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sa.sa_handler = sigHandler;
    sigaction(SIGHUP, &sa, nullptr);
    sigaction(SIGINT, &sa, nullptr);
    sigaction(SIGQUIT, &sa, nullptr);
    sigaction(SIGTERM, &sa, nullptr);
}

int main(int /*argc*/, char** /*argv*/) {
    // Set up the looper
    sp<Looper> looper(Looper::prepare(0 /* opts */));

    // Set up the binder
    sp<ProcessState> ps(ProcessState::self());
    ps->setThreadPoolMaxThreadCount(9);
    ps->startThreadPool();
    ps->giveThreadPoolName();
    IPCThreadState::self()->disableBackgroundScheduling(true);

    ::android::hardware::configureRpcThreadpool(1 /*threads*/, false /*willJoin*/);

    std::shared_ptr<LogEventQueue> eventQueue =
            std::make_shared<LogEventQueue>(2000 /*buffer limit. Buffer is NOT pre-allocated*/);

    // Create the service
    gStatsService = new StatsService(looper, eventQueue);
    if (defaultServiceManager()->addService(String16("stats"), gStatsService, false,
                IServiceManager::DUMP_FLAG_PRIORITY_NORMAL | IServiceManager::DUMP_FLAG_PROTO)
            != 0) {
        ALOGE("Failed to add service as AIDL service");
        return -1;
    }

    auto ret = gStatsService->registerAsService();
    if (ret != ::android::OK) {
        ALOGE("Failed to add service as HIDL service");
        return 1; // or handle error
    }

    registerSigHandler();

    gStatsService->sayHiToStatsCompanion();

    gStatsService->Startup();

    sp<StatsSocketListener> socketListener = new StatsSocketListener(eventQueue);

    ALOGI("Statsd starts to listen to socket.");
    // Backlog and /proc/sys/net/unix/max_dgram_qlen set to large value
    if (socketListener->startListener(600)) {
        exit(1);
    }

    // Loop forever -- the reports run on this thread in a handler, and the
    // binder calls remain responsive in their pool of one thread.
    while (true) {
        looper->pollAll(-1 /* timeoutMillis */);
    }
    ALOGW("statsd escaped from its loop.");

    return 1;
}
