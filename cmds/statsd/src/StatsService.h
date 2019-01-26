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

#ifndef STATS_SERVICE_H
#define STATS_SERVICE_H

#include <gtest/gtest_prod.h>
#include "StatsLogProcessor.h"
#include "anomaly/AlarmMonitor.h"
#include "config/ConfigManager.h"
#include "external/StatsPullerManager.h"
#include "logd/LogListener.h"
#include "packages/UidMap.h"
#include "shell/ShellSubscriber.h"
#include "statscompanion_util.h"

#include <android/frameworks/stats/1.0/IStats.h>
#include <android/frameworks/stats/1.0/types.h>
#include <android/os/BnStatsManager.h>
#include <android/os/IStatsCompanionService.h>
#include <binder/IResultReceiver.h>
#include <utils/Looper.h>

#include <deque>
#include <mutex>

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::frameworks::stats::V1_0;
using namespace android::os;
using namespace std;

namespace android {
namespace os {
namespace statsd {

using android::hardware::Return;

class StatsService : public BnStatsManager,
                     public LogListener,
                     public IStats,
                     public IBinder::DeathRecipient {
public:
    StatsService(const sp<Looper>& handlerLooper);
    virtual ~StatsService();

    /** The anomaly alarm registered with AlarmManager won't be updated by less than this. */
    const uint32_t MIN_DIFF_TO_UPDATE_REGISTERED_ALARM_SECS = 5;

    virtual status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags);
    virtual status_t dump(int fd, const Vector<String16>& args);
    virtual status_t command(int inFd, int outFd, int err, Vector<String8>& args,
                             sp<IResultReceiver> resultReceiver);

    virtual Status systemRunning();
    virtual Status statsCompanionReady();
    virtual Status informAnomalyAlarmFired();
    virtual Status informPollAlarmFired();
    virtual Status informAlarmForSubscriberTriggeringFired();

    virtual Status informAllUidData(const vector<int32_t>& uid, const vector<int64_t>& version,
                                    const vector<String16>& version_string,
                                    const vector<String16>& app, const vector<String16>& installer);
    virtual Status informOnePackage(const String16& app, int32_t uid, int64_t version,
                                    const String16& version_string, const String16& installer);
    virtual Status informOnePackageRemoved(const String16& app, int32_t uid);
    virtual Status informDeviceShutdown();

    /**
     * Called right before we start processing events.
     */
    void Startup();

    /**
     * Called when terminiation signal received.
     */
    void Terminate();

    /**
     * Called by LogReader when there's a log event to process.
     */
    virtual void OnLogEvent(LogEvent* event);

    /**
     * Binder call for clients to request data for this configuration key.
     */
    virtual Status getData(int64_t key,
                           const String16& packageName,
                           vector<uint8_t>* output) override;


    /**
     * Binder call for clients to get metadata across all configs in statsd.
     */
    virtual Status getMetadata(const String16& packageName,
                               vector<uint8_t>* output) override;


    /**
     * Binder call to let clients send a configuration and indicate they're interested when they
     * should requestData for this configuration.
     */
    virtual Status addConfiguration(int64_t key,
                                    const vector<uint8_t>& config,
                                    const String16& packageName) override;

    /**
     * Binder call to let clients register the data fetch operation for a configuration.
     */
    virtual Status setDataFetchOperation(int64_t key,
                                         const sp<android::IBinder>& intentSender,
                                         const String16& packageName) override;

    /**
     * Binder call to remove the data fetch operation for the specified config key.
     */
    virtual Status removeDataFetchOperation(int64_t key,
                                            const String16& packageName) override;

    /**
     * Binder call to let clients register the active configs changed operation.
     */
    virtual Status setActiveConfigsChangedOperation(const sp<android::IBinder>& intentSender,
                                                    const String16& packageName,
                                                    vector<int64_t>* output) override;

    /**
     * Binder call to remove the active configs changed operation for the specified package..
     */
    virtual Status removeActiveConfigsChangedOperation(const String16& packageName) override;
    /**
     * Binder call to allow clients to remove the specified configuration.
     */
    virtual Status removeConfiguration(int64_t key,
                                       const String16& packageName) override;

    /**
     * Binder call to associate the given config's subscriberId with the given intentSender.
     * intentSender must be convertible into an IntentSender (in Java) using IntentSender(IBinder).
     */
    virtual Status setBroadcastSubscriber(int64_t configId,
                                          int64_t subscriberId,
                                          const sp<android::IBinder>& intentSender,
                                          const String16& packageName) override;

    /**
     * Binder call to unassociate the given config's subscriberId with any intentSender.
     */
    virtual Status unsetBroadcastSubscriber(int64_t configId,
                                            int64_t subscriberId,
                                            const String16& packageName) override;

    /** Inform statsCompanion that statsd is ready. */
    virtual void sayHiToStatsCompanion();

    /**
     * Binder call to get AppBreadcrumbReported atom.
     */
    virtual Status sendAppBreadcrumbAtom(int32_t label, int32_t state) override;

    /**
     * Binder call to register a callback function for a vendor pulled atom.
     * Note: this atom must NOT have uid as a field.
     */
    virtual Status registerPullerCallback(int32_t atomTag,
        const sp<android::os::IStatsPullerCallback>& pullerCallback,
        const String16& packageName) override;

    /**
     * Binder call to unregister any existing callback function for a vendor pulled atom.
     */
    virtual Status unregisterPullerCallback(int32_t atomTag, const String16& packageName) override;

    /**
     * Binder call to get SpeakerImpedance atom.
     */
    virtual Return<void> reportSpeakerImpedance(const SpeakerImpedance& speakerImpedance) override;

    /**
     * Binder call to get HardwareFailed atom.
     */
    virtual Return<void> reportHardwareFailed(const HardwareFailed& hardwareFailed) override;

    /**
     * Binder call to get PhysicalDropDetected atom.
     */
    virtual Return<void> reportPhysicalDropDetected(
            const PhysicalDropDetected& physicalDropDetected) override;

    /**
     * Binder call to get ChargeCyclesReported atom.
     */
    virtual Return<void> reportChargeCycles(const ChargeCycles& chargeCycles) override;

    /**
     * Binder call to get BatteryHealthSnapshot atom.
     */
    virtual Return<void> reportBatteryHealthSnapshot(
            const BatteryHealthSnapshotArgs& batteryHealthSnapshotArgs) override;

    /**
     * Binder call to get SlowIo atom.
     */
    virtual Return<void> reportSlowIo(const SlowIo& slowIo) override;

    /**
     * Binder call to get BatteryCausedShutdown atom.
     */
    virtual Return<void> reportBatteryCausedShutdown(
            const BatteryCausedShutdown& batteryCausedShutdown) override;

    /**
     * Binder call to get UsbPortOverheatEvent atom.
     */
    virtual Return<void> reportUsbPortOverheatEvent(
            const UsbPortOverheatEvent& usbPortOverheatEvent) override;

    /**
     * Binder call to get Speech DSP state atom.
     */
    virtual Return<void> reportSpeechDspStat(
            const SpeechDspStat& speechDspStat) override;

    /**
     * Binder call to get vendor atom.
     */
    virtual Return<void> reportVendorAtom(const VendorAtom& vendorAtom) override;

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
     * Proto output of statsd report data dumpsys, wrapped in a StatsDataDumpProto.
     */
    void dumpIncidentSection(int outFd);

    /**
     * Text or proto output of statsdStats dumpsys.
     */
    void dumpStatsdStats(int outFd, bool verbose, bool proto);

    /**
     * Print usage information for the commands
     */
    void print_cmd_help(int out);

    /**
     * Trigger a broadcast.
     */
    status_t cmd_trigger_broadcast(int outFd, Vector<String8>& args);

    /**
     * Handle the config sub-command.
     */
    status_t cmd_config(int inFd, int outFd, int err, Vector<String8>& args);

    /**
     * Prints some basic stats to std out.
     */
    status_t cmd_print_stats(int outFd, const Vector<String8>& args);

    /**
     * Print the event log.
     */
    status_t cmd_dump_report(int outFd, const Vector<String8>& args);

    /**
     * Print the mapping of uids to package names.
     */
    status_t cmd_print_uid_map(int outFd, const Vector<String8>& args);

    /**
     * Flush the data to disk.
     */
    status_t cmd_write_data_to_disk(int outFd);

    /**
     * Write an AppBreadcrumbReported event to the StatsLog buffer, as if calling
     * StatsLog.write(APP_BREADCRUMB_REPORTED).
     */
    status_t cmd_log_app_breadcrumb(int outFd, const Vector<String8>& args);

    /**
     * Print contents of a pulled metrics source.
     */
    status_t cmd_print_pulled_metrics(int outFd, const Vector<String8>& args);

    /**
     * Removes all configs stored on disk and on memory.
     */
    status_t cmd_remove_all_configs(int outFd);

    /*
     * Dump memory usage by statsd.
     */
    status_t cmd_dump_memory_info(int outFd);

    /*
     * Clear all puller cached data
     */
    status_t cmd_clear_puller_cache(int outFd);

    /**
     * Print all stats logs received to logcat.
     */
    status_t cmd_print_logs(int outFd, const Vector<String8>& args);

    /**
     * Writes the value of args[uidArgIndex] into uid.
     * Returns whether the uid is reasonable (type uid_t) and whether
     * 1. it is equal to the calling uid, or
     * 2. the device is mEngBuild, or
     * 3. the caller is AID_ROOT and the uid is AID_SHELL (i.e. ROOT can impersonate SHELL).
     */
    bool getUidFromArgs(const Vector<String8>& args, size_t uidArgIndex, int32_t& uid);

    /**
     * Adds a configuration after checking permissions and obtaining UID from binder call.
     */
    bool addConfigurationChecked(int uid, int64_t key, const vector<uint8_t>& config);

    /**
     * Update a configuration.
     */
    void set_config(int uid, const string& name, const StatsdConfig& config);

    /**
     * Tracks the uid <--> package name mapping.
     */
    sp<UidMap> mUidMap;

    /**
     * Fetches external metrics
     */
    sp<StatsPullerManager> mPullerManager;

    /**
     * Tracks the configurations that have been passed to statsd.
     */
    sp<ConfigManager> mConfigManager;

    /**
     * The metrics recorder.
     */
    sp<StatsLogProcessor> mProcessor;

    /**
     * The alarm monitor for anomaly detection.
     */
    const sp<AlarmMonitor> mAnomalyAlarmMonitor;

    /**
     * The alarm monitor for alarms to directly trigger subscriber.
     */
    const sp<AlarmMonitor> mPeriodicAlarmMonitor;

    /**
     * Whether this is an eng build.
     */
    bool mEngBuild;

    sp<ShellSubscriber> mShellSubscriber;

    FRIEND_TEST(StatsServiceTest, TestAddConfig_simple);
    FRIEND_TEST(StatsServiceTest, TestAddConfig_empty);
    FRIEND_TEST(StatsServiceTest, TestAddConfig_invalid);
    FRIEND_TEST(StatsServiceTest, TestGetUidFromArgs);
    FRIEND_TEST(PartialBucketE2eTest, TestCountMetricNoSplitOnNewApp);
    FRIEND_TEST(PartialBucketE2eTest, TestCountMetricSplitOnUpgrade);
    FRIEND_TEST(PartialBucketE2eTest, TestCountMetricSplitOnRemoval);
    FRIEND_TEST(PartialBucketE2eTest, TestCountMetricWithoutSplit);
    FRIEND_TEST(PartialBucketE2eTest, TestValueMetricWithoutMinPartialBucket);
    FRIEND_TEST(PartialBucketE2eTest, TestValueMetricWithMinPartialBucket);
    FRIEND_TEST(PartialBucketE2eTest, TestGaugeMetricWithoutMinPartialBucket);
    FRIEND_TEST(PartialBucketE2eTest, TestGaugeMetricWithMinPartialBucket);
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATS_SERVICE_H
