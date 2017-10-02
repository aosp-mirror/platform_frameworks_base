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

#include "AnomalyMonitor.h"
#include "StatsLogProcessor.h"
#include "StatsPuller.h"

#include <android/os/BnStatsManager.h>
#include <android/os/IStatsCompanionService.h>
#include <binder/IResultReceiver.h>
#include <binder/IShellCallback.h>
#include <utils/Looper.h>

#include <deque>
#include <mutex>

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::os;
using namespace std;

namespace android {
namespace os {
namespace statsd {

class StatsService : public BnStatsManager {
public:
    StatsService(const sp<Looper>& handlerLooper);
    virtual ~StatsService();

    virtual status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags);

    virtual status_t dump(int fd, const Vector<String16>& args);

    virtual status_t command(FILE* in, FILE* out, FILE* err, Vector<String8>& args);

    virtual Status systemRunning();

    // Inform statsd that statsCompanion is ready.
    virtual Status statsCompanionReady();

    virtual Status informAnomalyAlarmFired();

    virtual Status informPollAlarmFired();

    virtual status_t setProcessor(const sp<StatsLogProcessor>& main_processor);

    // TODO: public for testing since statsd doesn't run when system starts. Change to private
    // later.
    /** Inform statsCompanion that statsd is ready. */
    virtual void sayHiToStatsCompanion();

    // TODO: Move this to a more logical file/class
    // TODO: Should be private. Temporarily public for testing purposes only.
    const sp<AnomalyMonitor> mAnomalyMonitor;

    /** Fetches and returns the StatsCompanionService. */
    static sp<IStatsCompanionService> getStatsCompanionService();

 private:
    sp<StatsLogProcessor> m_processor;  // Reference to the processor for updating configs.

    status_t doPrintStatsLog(FILE* out, const Vector<String8>& args);

    void printCmdHelp(FILE* out);

    status_t doLoadConfig(FILE* in);
};

// --- StatsdDeathRecipient ---
class StatsdDeathRecipient : public IBinder::DeathRecipient {
public:
    StatsdDeathRecipient(sp<AnomalyMonitor> anomalyMonitor) : mAnmlyMntr(anomalyMonitor) {
    }

    virtual void binderDied(const wp<IBinder>& who);

private:
    const sp<AnomalyMonitor> mAnmlyMntr;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATS_SERVICE_H
