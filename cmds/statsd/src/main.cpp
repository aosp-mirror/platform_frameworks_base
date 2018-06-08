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
#include "logd/LogReader.h"
#include "socket/StatsSocketListener.h"

#include <binder/IInterface.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <binder/Status.h>
#include <utils/Looper.h>
#include <utils/StrongPointer.h>

#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

using namespace android;
using namespace android::os::statsd;

const bool kUseLogd = false;
const bool kUseStatsdSocket = true;

/**
 * Thread function data.
 */
struct log_reader_thread_data {
    sp<StatsService> service;
};

/**
 * Thread func for where the log reader runs.
 */
static void* log_reader_thread_func(void* cookie) {
    log_reader_thread_data* data = static_cast<log_reader_thread_data*>(cookie);
    sp<LogReader> reader = new LogReader(data->service);

    // Run the read loop. Never returns.
    reader->Run();

    ALOGW("statsd LogReader.Run() is not supposed to return.");

    delete data;
    return NULL;
}

/**
 * Creates and starts the thread to own the LogReader.
 */
static status_t start_log_reader_thread(const sp<StatsService>& service) {
    status_t err;
    pthread_attr_t attr;
    pthread_t thread;

    // Thread data.
    log_reader_thread_data* data = new log_reader_thread_data();
    data->service = service;

    // Create the thread
    err = pthread_attr_init(&attr);
    if (err != NO_ERROR) {
        return err;
    }
    // TODO: Do we need to tweak thread priority?
    err = pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (err != NO_ERROR) {
        pthread_attr_destroy(&attr);
        return err;
    }
    err = pthread_create(&thread, &attr, log_reader_thread_func, static_cast<void*>(data));
    if (err != NO_ERROR) {
        pthread_attr_destroy(&attr);
        return err;
    }
    pthread_attr_destroy(&attr);

    return NO_ERROR;
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

    // Create the service
    sp<StatsService> service = new StatsService(looper);
    if (defaultServiceManager()->addService(String16("stats"), service) != 0) {
        ALOGE("Failed to add service");
        return -1;
    }
    service->sayHiToStatsCompanion();

    service->Startup();

    sp<StatsSocketListener> socketListener = new StatsSocketListener(service);

    if (kUseLogd) {
        ALOGI("using logd");
        // Start the log reader thread
        status_t err = start_log_reader_thread(service);
        if (err != NO_ERROR) {
            return 1;
        }
    }

    if (kUseStatsdSocket) {
        ALOGI("using statsd socket");
        // Backlog and /proc/sys/net/unix/max_dgram_qlen set to large value
        if (socketListener->startListener(600)) {
            exit(1);
        }
    }

    // Loop forever -- the reports run on this thread in a handler, and the
    // binder calls remain responsive in their pool of one thread.
    while (true) {
        looper->pollAll(-1 /* timeoutMillis */);
    }
    ALOGW("statsd escaped from its loop.");

    return 1;
}
