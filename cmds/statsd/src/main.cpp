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

#include <android/binder_interface_utils.h>
#include <android/binder_process.h>
#include <android/binder_manager.h>
#include <utils/Looper.h>

#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

using namespace android;
using namespace android::os::statsd;
using ::ndk::SharedRefBase;
using std::shared_ptr;
using std::make_shared;

shared_ptr<StatsService> gStatsService = nullptr;
sp<StatsSocketListener> gSocketListener = nullptr;

void signalHandler(int sig) {
    if (sig == SIGPIPE) {
        // ShellSubscriber uses SIGPIPE as a signal to detect the end of the
        // client process. Don't prematurely exit(1) here. Instead, ignore the
        // signal and allow the write call to return EPIPE.
        ALOGI("statsd received SIGPIPE. Ignoring signal.");
        return;
    }

    if (gSocketListener != nullptr) gSocketListener->stopListener();
    if (gStatsService != nullptr) gStatsService->Terminate();
    ALOGW("statsd terminated on receiving signal %d.", sig);
    exit(1);
}

void registerSignalHandlers()
{
    struct sigaction sa;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sa.sa_handler = signalHandler;
    sigaction(SIGPIPE, &sa, nullptr);
    sigaction(SIGHUP, &sa, nullptr);
    sigaction(SIGINT, &sa, nullptr);
    sigaction(SIGQUIT, &sa, nullptr);
    sigaction(SIGTERM, &sa, nullptr);
}

int main(int /*argc*/, char** /*argv*/) {
    // Set up the looper
    sp<Looper> looper(Looper::prepare(0 /* opts */));

    // Set up the binder
    ABinderProcess_setThreadPoolMaxThreadCount(9);
    ABinderProcess_startThreadPool();

    std::shared_ptr<LogEventQueue> eventQueue =
            std::make_shared<LogEventQueue>(2000 /*buffer limit. Buffer is NOT pre-allocated*/);

    // Create the service
    gStatsService = SharedRefBase::make<StatsService>(looper, eventQueue);
    // TODO(b/149582373): Set DUMP_FLAG_PROTO once libbinder_ndk supports
    // setting dumpsys priorities.
    binder_status_t status = AServiceManager_addService(gStatsService->asBinder().get(), "stats");
    if (status != STATUS_OK) {
        ALOGE("Failed to add service as AIDL service");
        return -1;
    }

    registerSignalHandlers();

    gStatsService->sayHiToStatsCompanion();

    gStatsService->Startup();

    gSocketListener = new StatsSocketListener(eventQueue);

    ALOGI("Statsd starts to listen to socket.");
    // Backlog and /proc/sys/net/unix/max_dgram_qlen set to large value
    if (gSocketListener->startListener(600)) {
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
