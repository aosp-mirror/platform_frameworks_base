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
#include "anomaly/AnomalyMonitor.h"
#include "config/ConfigManager.h"
#include "external/StatsPullerManager.h"
#include "packages/UidMap.h"

#include <android/os/BnStatsManager.h>
#include <android/os/IStatsCallbacks.h>
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

class StatsService : public BnStatsManager, public LogListener, public IBinder::DeathRecipient {
public:
    StatsService(const sp<Looper>& handlerLooper);
    virtual ~StatsService();

    virtual status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags);
    virtual status_t dump(int fd, const Vector<String16>& args);
    virtual status_t command(FILE* in, FILE* out, FILE* err, Vector<String8>& args);

    virtual Status systemRunning();
    virtual Status statsCompanionReady();
    virtual Status informAnomalyAlarmFired();
    virtual Status informPollAlarmFired();
    virtual Status informAllUidData(const vector<int32_t>& uid, const vector<int32_t>& version,
                                    const vector<String16>& app);
    virtual Status informOnePackage(const String16& app, int32_t uid, int32_t version);
    virtual Status informOnePackageRemoved(const String16& app, int32_t uid);

    /**
     * Called right before we start processing events.
     */
    void Startup();

    /**
     * Called by LogReader when there's a log event to process.
     */
    virtual void OnLogEvent(const LogEvent& event);

    /**
     * Binder call to force trigger pushLog. This would be called by callback
     * clients.
     */
    virtual Status requestPush() override;

    /**
     * Pushes stats log entries from statsd to callback clients.
     */
    Status pushLog(const vector<uint8_t>& log);

    /**
     * Binder call to listen to statsd to send stats log entries.
     */
    virtual Status subscribeStatsLog(const sp<IStatsCallbacks>& callbacks) override;

    // TODO: public for testing since statsd doesn't run when system starts. Change to private
    // later.
    /** Inform statsCompanion that statsd is ready. */
    virtual void sayHiToStatsCompanion();

    /** Fetches and returns the StatsCompanionService. */
    static sp<IStatsCompanionService> getStatsCompanionService();

    /** IBinder::DeathRecipient */
    virtual void binderDied(const wp<IBinder>& who) override;

private:
    /**
     * Load system properties at init.
     */
    void init_system_properties();

    /**
     * Helper for loading system properties.
     */
    static void init_build_type_callback(void* cookie, const char* name, const char* value,
                                         uint32_t serial);

    /**
     * Text output of dumpsys.
     */
    void dump_impl(FILE* out);

    /**
     * Print usage information for the commands
     */
    void print_cmd_help(FILE* out);

    /**
     * Handle the config sub-command.
     */
    status_t cmd_config(FILE* in, FILE* out, FILE* err, Vector<String8>& args);

    /**
     * Print the event log.
     */
    status_t cmd_print_stats_log(FILE* out, const Vector<String8>& args);

    /**
     * Print the event log.
     */
    status_t cmd_dump_report(FILE* out, FILE* err, const Vector<String8>& args);

    /**
     * Print the mapping of uids to package names.
     */
    status_t cmd_print_uid_map(FILE* out);

    /**
     * Print contents of a pulled metrics source.
     */
    status_t cmd_print_pulled_metrics(FILE* out, const Vector<String8>& args);

    /**
     * Update a configuration.
     */
    void set_config(int uid, const string& name, const StatsdConfig& config);

    /**
     * Tracks the uid <--> package name mapping.
     */
    sp<UidMap> mUidMap;

    /**
     * Fetches external metrics.
     */
    sp<StatsPullerManager> mStatsPullerManager;

    /**
     * Tracks the configurations that have been passed to statsd.
     */
    sp<ConfigManager> mConfigManager;

    /**
     * The metrics recorder.
     */
    sp<StatsLogProcessor> mProcessor;

    /**
     * The anomaly detector.
     */
    const sp<AnomalyMonitor> mAnomalyMonitor;

    /**
     * Whether this is an eng build.
     */
    bool mEngBuild;

    /**
     * Lock for callback handling.
     */
    std::mutex mLock;

    /**
     * Vector maintaining the list of callbacks for clients.
     */
    Vector< sp<IStatsCallbacks> > mCallbacks;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATS_SERVICE_H
