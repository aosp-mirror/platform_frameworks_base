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

#ifndef STATS_SERVICE_H
#define STATS_SERVICE_H

#include "StatsLogProcessor.h"

#include <android/os/BnStatsManager.h>
#include <binder/IResultReceiver.h>
#include <binder/IShellCallback.h>
#include <frameworks/base/cmds/statsd/src/statsd_config.pb.h>
#include <utils/Looper.h>

#include <deque>
#include <mutex>

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::os;
using namespace std;
using android::os::statsd::StatsdConfig;

// ================================================================================
class StatsService : public BnStatsManager {
public:
    StatsService(const sp<Looper>& handlerLooper);
    virtual ~StatsService();

    virtual status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags);

    virtual status_t dump(int fd, const Vector<String16>& args);

    virtual status_t command(FILE* in, FILE* out, FILE* err, Vector<String8>& args);

    virtual Status systemRunning();

    virtual Status informAnomalyAlarmFired();

    virtual Status informPollAlarmFired();

    virtual status_t setProcessor(const sp<StatsLogProcessor>& main_processor);

private:
    sp<StatsLogProcessor> m_processor; // Reference to the processor for updating configs.
    status_t doPrintStatsLog(FILE* out, const Vector<String8>& args);
    void printCmdHelp(FILE* out);
    status_t doLoadConfig(FILE* in);
};

#endif // STATS_SERVICE_H
