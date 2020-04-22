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

#include <aidl/android/os/BnStatsd.h>
#include <aidl/android/os/IPendingIntentRef.h>
#include <aidl/android/os/IPullAtomCallback.h>
#include <gtest/gtest_prod.h>
#include <utils/Looper.h>

#include <mutex>

#include "StatsLogProcessor.h"
#include "anomaly/AlarmMonitor.h"
#include "config/ConfigManager.h"
#include "external/StatsPullerManager.h"
#include "logd/LogEventQueue.h"
#include "packages/UidMap.h"
#include "shell/ShellSubscriber.h"
#include "statscompanion_util.h"
#include "utils/MultiConditionTrigger.h"

using namespace android;
using namespace android::os;
using namespace std;

using Status = ::ndk::ScopedAStatus;
using aidl::android::os::BnStatsd;
using aidl::android::os::IPendingIntentRef;
using aidl::android::os::IPullAtomCallback;
using ::ndk::ScopedAIBinder_DeathRecipient;
using ::ndk::ScopedFileDescriptor;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

class StatsService : public BnStatsd {
public:
    StatsService(const sp<Looper>& handlerLooper, std::shared_ptr<LogEventQueue> queue);
    virtual ~StatsService();

    /** The anomaly alarm registered with AlarmManager won't be updated by less than this. */
    const uint32_t MIN_DIFF_TO_UPDATE_REGISTERED_ALARM_SECS = 5;

    virtual status_t dump(int fd, const char** args, uint32_t numArgs) override;
    virtual status_t handleShellCommand(int in, int out, int err, const char** argv,
                                        uint32_t argc) override;

    virtual Status systemRunning();
    virtual Status statsCompanionReady();
    virtual Status bootCompleted();
    virtual Status informAnomalyAlarmFired();
    virtual Status informPollAlarmFired();
    virtual Status informAlarmForSubscriberTriggeringFired();

    virtual Status informAllUidData(const ScopedFileDescriptor& fd);
    virtual Status informOnePackage(const string& app, int32_t uid, int64_t version,
                                    const string& versionString, const string& installer);
    virtual Status informOnePackageRemoved(const string& app, int32_t uid);
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
     * Test ONLY interface. In real world, StatsService reads from LogEventQueue.
     */
    virtual void OnLogEvent(LogEvent* event);

    /**
     * Binder call for clients to request data for this configuration key.
     */
    virtual Status getData(int64_t key,
                           const int32_t callingUid,
                           vector<int8_t>* output) override;


    /**
     * Binder call for clients to get metadata across all configs in statsd.
     */
    virtual Status getMetadata(vector<int8_t>* output) override;


    /**
     * Binder call to let clients send a configuration and indicate they're interested when they
     * should requestData for this configuration.
     */
    virtual Status addConfiguration(int64_t key,
                                    const vector<int8_t>& config,
                                    const int32_t callingUid) override;

    /**
     * Binder call to let clients register the data fetch operation for a configuration.
     */
    virtual Status setDataFetchOperation(int64_t key,
                                         const shared_ptr<IPendingIntentRef>& pir,
                                         const int32_t callingUid) override;

    /**
     * Binder call to remove the data fetch operation for the specified config key.
     */
    virtual Status removeDataFetchOperation(int64_t key,
                                            const int32_t callingUid) override;

    /**
     * Binder call to let clients register the active configs changed operation.
     */
    virtual Status setActiveConfigsChangedOperation(const shared_ptr<IPendingIntentRef>& pir,
                                                    const int32_t callingUid,
                                                    vector<int64_t>* output) override;

    /**
     * Binder call to remove the active configs changed operation for the specified package..
     */
    virtual Status removeActiveConfigsChangedOperation(const int32_t callingUid) override;
    /**
     * Binder call to allow clients to remove the specified configuration.
     */
    virtual Status removeConfiguration(int64_t key,
                                       const int32_t callingUid) override;

    /**
     * Binder call to associate the given config's subscriberId with the given pendingIntentRef.
     */
    virtual Status setBroadcastSubscriber(int64_t configId,
                                          int64_t subscriberId,
                                          const shared_ptr<IPendingIntentRef>& pir,
                                          const int32_t callingUid) override;

    /**
     * Binder call to unassociate the given config's subscriberId with any pendingIntentRef.
     */
    virtual Status unsetBroadcastSubscriber(int64_t configId,
                                            int64_t subscriberId,
                                            const int32_t callingUid) override;

    /** Inform statsCompanion that statsd is ready. */
    virtual void sayHiToStatsCompanion();

    /**
     * Binder call to notify statsd that all pullers from boot have been registered.
     */
    virtual Status allPullersFromBootRegistered();

    /**
     * Binder call to register a callback function for a pulled atom.
     */
    virtual Status registerPullAtomCallback(
            int32_t uid, int32_t atomTag, int64_t coolDownMillis, int64_t timeoutMillis,
            const std::vector<int32_t>& additiveFields,
            const shared_ptr<IPullAtomCallback>& pullerCallback) override;

    /**
     * Binder call to register a callback function for a pulled atom.
     */
    virtual Status registerNativePullAtomCallback(
            int32_t atomTag, int64_t coolDownMillis, int64_t timeoutMillis,
            const std::vector<int32_t>& additiveFields,
            const shared_ptr<IPullAtomCallback>& pullerCallback) override;

    /**
     * Binder call to unregister any existing callback for the given uid and atom.
     */
    virtual Status unregisterPullAtomCallback(int32_t uid, int32_t atomTag) override;

    /**
     * Binder call to unregister any existing callback for the given atom and calling uid.
     */
    virtual Status unregisterNativePullAtomCallback(int32_t atomTag) override;

    /**
     * Binder call to get registered experiment IDs.
     */
    virtual Status getRegisteredExperimentIds(std::vector<int64_t>* expIdsOut);

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

    /* Runs on its dedicated thread to process pushed stats event from socket. */
    void readLogs();

    /**
     * Trigger a broadcast.
     */
    status_t cmd_trigger_broadcast(int outFd, Vector<String8>& args);


    /**
     * Trigger an active configs changed broadcast.
     */
    status_t cmd_trigger_active_config_broadcast(int outFd, Vector<String8>& args);

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
     * Write an BinaryPushStateChanged event, as if calling StatsLog.logBinaryPushStateChanged().
     */
    status_t cmd_log_binary_push(int outFd, const Vector<String8>& args);

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
     * Writes the value of uidSting into uid.
     * Returns whether the uid is reasonable (type uid_t) and whether
     * 1. it is equal to the calling uid, or
     * 2. the device is mEngBuild, or
     * 3. the caller is AID_ROOT and the uid is AID_SHELL (i.e. ROOT can impersonate SHELL).
     */
     bool getUidFromString(const char* uidString, int32_t& uid);

    /**
     * Adds a configuration after checking permissions and obtaining UID from binder call.
     */
    bool addConfigurationChecked(int uid, int64_t key, const vector<int8_t>& config);

    /**
     * Update a configuration.
     */
    void set_config(int uid, const string& name, const StatsdConfig& config);

    /**
     * Death recipient callback that is called when StatsCompanionService dies.
     * The cookie is a pointer to a StatsService object.
     */
    static void statsCompanionServiceDied(void* cookie);

    /**
     * Implementation of statsCompanionServiceDied.
     */
    void statsCompanionServiceDiedImpl();

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

    /**
     * Mutex for setting the shell subscriber
     */
    mutable mutex mShellSubscriberMutex;
    std::shared_ptr<LogEventQueue> mEventQueue;

    MultiConditionTrigger mBootCompleteTrigger;
    static const inline string kBootCompleteTag = "BOOT_COMPLETE";
    static const inline string kUidMapReceivedTag = "UID_MAP";
    static const inline string kAllPullersRegisteredTag = "PULLERS_REGISTERED";

    ScopedAIBinder_DeathRecipient mStatsCompanionServiceDeathRecipient;

    FRIEND_TEST(StatsLogProcessorTest, TestActivationsPersistAcrossSystemServerRestart);
    FRIEND_TEST(StatsServiceTest, TestAddConfig_simple);
    FRIEND_TEST(StatsServiceTest, TestAddConfig_empty);
    FRIEND_TEST(StatsServiceTest, TestAddConfig_invalid);
    FRIEND_TEST(StatsServiceTest, TestGetUidFromArgs);
    FRIEND_TEST(PartialBucketE2eTest, TestCountMetricNoSplitOnNewApp);
    FRIEND_TEST(PartialBucketE2eTest, TestCountMetricSplitOnBoot);
    FRIEND_TEST(PartialBucketE2eTest, TestCountMetricSplitOnUpgrade);
    FRIEND_TEST(PartialBucketE2eTest, TestCountMetricSplitOnRemoval);
    FRIEND_TEST(PartialBucketE2eTest, TestCountMetricWithoutSplit);
    FRIEND_TEST(PartialBucketE2eTest, TestValueMetricOnBootWithoutMinPartialBucket);
    FRIEND_TEST(PartialBucketE2eTest, TestValueMetricWithoutMinPartialBucket);
    FRIEND_TEST(PartialBucketE2eTest, TestValueMetricWithMinPartialBucket);
    FRIEND_TEST(PartialBucketE2eTest, TestGaugeMetricOnBootWithoutMinPartialBucket);
    FRIEND_TEST(PartialBucketE2eTest, TestGaugeMetricWithoutMinPartialBucket);
    FRIEND_TEST(PartialBucketE2eTest, TestGaugeMetricWithMinPartialBucket);
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATS_SERVICE_H
