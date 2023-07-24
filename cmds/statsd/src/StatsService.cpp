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
#include "stats_log_util.h"
#include "android-base/stringprintf.h"
#include "config/ConfigKey.h"
#include "config/ConfigManager.h"
#include "guardrail/StatsdStats.h"
#include "storage/StorageManager.h"
#include "subscriber/SubscriberReporter.h"

#include <android-base/file.h>
#include <android-base/strings.h>
#include <cutils/multiuser.h>
#include <frameworks/base/cmds/statsd/src/statsd_config.pb.h>
#include <frameworks/base/cmds/statsd/src/uid_data.pb.h>
#include <private/android_filesystem_config.h>
#include <statslog_statsd.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <utils/String16.h>

using namespace android;

using android::base::StringPrintf;
using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_MESSAGE;

using Status = ::ndk::ScopedAStatus;

namespace android {
namespace os {
namespace statsd {

constexpr const char* kPermissionDump = "android.permission.DUMP";

constexpr const char* kPermissionRegisterPullAtom = "android.permission.REGISTER_STATS_PULL_ATOM";

#define STATS_SERVICE_DIR "/data/misc/stats-service"

// for StatsDataDumpProto
const int FIELD_ID_REPORTS_LIST = 1;

static Status exception(int32_t code, const std::string& msg) {
    ALOGE("%s (%d)", msg.c_str(), code);
    return Status::fromExceptionCodeWithMessage(code, msg.c_str());
}

static bool checkPermission(const char* permission) {
    pid_t pid = AIBinder_getCallingPid();
    uid_t uid = AIBinder_getCallingUid();
    return checkPermissionForIds(permission, pid, uid);
}

Status checkUid(uid_t expectedUid) {
    uid_t uid = AIBinder_getCallingUid();
    if (uid == expectedUid || uid == AID_ROOT) {
        return Status::ok();
    } else {
        return exception(EX_SECURITY,
                         StringPrintf("UID %d is not expected UID %d", uid, expectedUid));
    }
}

#define ENFORCE_UID(uid) {                                        \
    Status status = checkUid((uid));                              \
    if (!status.isOk()) {                                         \
        return status;                                            \
    }                                                             \
}

StatsService::StatsService(const sp<Looper>& handlerLooper, shared_ptr<LogEventQueue> queue)
    : mAnomalyAlarmMonitor(new AlarmMonitor(
              MIN_DIFF_TO_UPDATE_REGISTERED_ALARM_SECS,
              [](const shared_ptr<IStatsCompanionService>& sc, int64_t timeMillis) {
                  if (sc != nullptr) {
                      sc->setAnomalyAlarm(timeMillis);
                      StatsdStats::getInstance().noteRegisteredAnomalyAlarmChanged();
                  }
              },
              [](const shared_ptr<IStatsCompanionService>& sc) {
                  if (sc != nullptr) {
                      sc->cancelAnomalyAlarm();
                      StatsdStats::getInstance().noteRegisteredAnomalyAlarmChanged();
                  }
              })),
      mPeriodicAlarmMonitor(new AlarmMonitor(
              MIN_DIFF_TO_UPDATE_REGISTERED_ALARM_SECS,
              [](const shared_ptr<IStatsCompanionService>& sc, int64_t timeMillis) {
                  if (sc != nullptr) {
                      sc->setAlarmForSubscriberTriggering(timeMillis);
                      StatsdStats::getInstance().noteRegisteredPeriodicAlarmChanged();
                  }
              },
              [](const shared_ptr<IStatsCompanionService>& sc) {
                  if (sc != nullptr) {
                      sc->cancelAlarmForSubscriberTriggering();
                      StatsdStats::getInstance().noteRegisteredPeriodicAlarmChanged();
                  }
              })),
      mEventQueue(queue),
      mBootCompleteTrigger({kBootCompleteTag, kUidMapReceivedTag, kAllPullersRegisteredTag},
                           [this]() { mProcessor->onStatsdInitCompleted(getElapsedRealtimeNs()); }),
      mStatsCompanionServiceDeathRecipient(
              AIBinder_DeathRecipient_new(StatsService::statsCompanionServiceDied)) {
    mUidMap = UidMap::getInstance();
    mPullerManager = new StatsPullerManager();
    StatsPuller::SetUidMap(mUidMap);
    mConfigManager = new ConfigManager();
    mProcessor = new StatsLogProcessor(
            mUidMap, mPullerManager, mAnomalyAlarmMonitor, mPeriodicAlarmMonitor,
            getElapsedRealtimeNs(),
            [this](const ConfigKey& key) {
                shared_ptr<IPendingIntentRef> receiver = mConfigManager->GetConfigReceiver(key);
                if (receiver == nullptr) {
                    VLOG("Could not find a broadcast receiver for %s", key.ToString().c_str());
                    return false;
                } else if (receiver->sendDataBroadcast(
                           mProcessor->getLastReportTimeNs(key)).isOk()) {
                    return true;
                } else {
                    VLOG("Failed to send a broadcast for receiver %s", key.ToString().c_str());
                    return false;
                }
            },
            [this](const int& uid, const vector<int64_t>& activeConfigs) {
                shared_ptr<IPendingIntentRef> receiver =
                    mConfigManager->GetActiveConfigsChangedReceiver(uid);
                if (receiver == nullptr) {
                    VLOG("Could not find receiver for uid %d", uid);
                    return false;
                } else if (receiver->sendActiveConfigsChangedBroadcast(activeConfigs).isOk()) {
                    VLOG("StatsService::active configs broadcast succeeded for uid %d" , uid);
                    return true;
                } else {
                    VLOG("StatsService::active configs broadcast failed for uid %d" , uid);
                    return false;
                }
            });

    mUidMap->setListener(mProcessor);
    mConfigManager->AddListener(mProcessor);

    init_system_properties();

    if (mEventQueue != nullptr) {
        mLogsReaderThread = std::make_unique<std::thread>([this] { readLogs(); });
    }
}

StatsService::~StatsService() {
    if (mEventQueue != nullptr) {
        stopReadingLogs();
        mLogsReaderThread->join();
    }
}

/* Runs on a dedicated thread to process pushed events. */
void StatsService::readLogs() {
    // Read forever..... long live statsd
    while (1) {
        // Block until an event is available.
        auto event = mEventQueue->waitPop();

        // Below flag will be set when statsd is exiting and log event will be pushed to break
        // out of waitPop.
        if (mIsStopRequested) {
            break;
        }

        // Pass it to StatsLogProcess to all configs/metrics
        // At this point, the LogEventQueue is not blocked, so that the socketListener
        // can read events from the socket and write to buffer to avoid data drop.
        mProcessor->OnLogEvent(event.get());
        // The ShellSubscriber is only used by shell for local debugging.
        if (mShellSubscriber != nullptr) {
            mShellSubscriber->onLogEvent(*event);
        }
    }
}

void StatsService::init_system_properties() {
    mEngBuild = false;
    const prop_info* buildType = __system_property_find("ro.build.type");
    if (buildType != NULL) {
        __system_property_read_callback(buildType, init_build_type_callback, this);
    }
}

void StatsService::init_build_type_callback(void* cookie, const char* /*name*/, const char* value,
                                            uint32_t serial) {
    if (0 == strcmp("eng", value) || 0 == strcmp("userdebug", value)) {
        reinterpret_cast<StatsService*>(cookie)->mEngBuild = true;
    }
}

/**
 * Write data from statsd.
 * Format for statsdStats:  adb shell dumpsys stats --metadata [-v] [--proto]
 * Format for data report:  adb shell dumpsys stats [anything other than --metadata] [--proto]
 * Anything ending in --proto will be in proto format.
 * Anything without --metadata as the first argument will be report information.
 *     (bugreports call "adb shell dumpsys stats --dump-priority NORMAL -a --proto")
 * TODO: Come up with a more robust method of enacting <serviceutils/PriorityDumper.h>.
 */
status_t StatsService::dump(int fd, const char** args, uint32_t numArgs) {
    if (!checkPermission(kPermissionDump)) {
        return PERMISSION_DENIED;
    }

    int lastArg = numArgs - 1;
    bool asProto = false;
    if (lastArg >= 0 && string(args[lastArg]) == "--proto") { // last argument
        asProto = true;
        lastArg--;
    }
    if (numArgs > 0 && string(args[0]) == "--metadata") { // first argument
        // Request is to dump statsd stats.
        bool verbose = false;
        if (lastArg >= 0 && string(args[lastArg]) == "-v") {
            verbose = true;
            lastArg--;
        }
        dumpStatsdStats(fd, verbose, asProto);
    } else {
        // Request is to dump statsd report data.
        if (asProto) {
            dumpIncidentSection(fd);
        } else {
            dprintf(fd, "Non-proto format of stats data dump not available; see proto version.\n");
        }
    }

    return NO_ERROR;
}

/**
 * Write debugging data about statsd in text or proto format.
 */
void StatsService::dumpStatsdStats(int out, bool verbose, bool proto) {
    if (proto) {
        vector<uint8_t> data;
        StatsdStats::getInstance().dumpStats(&data, false); // does not reset statsdStats.
        for (size_t i = 0; i < data.size(); i ++) {
            dprintf(out, "%c", data[i]);
        }
    } else {
        StatsdStats::getInstance().dumpStats(out);
        mProcessor->dumpStates(out, verbose);
    }
}

/**
 * Write stats report data in StatsDataDumpProto incident section format.
 */
void StatsService::dumpIncidentSection(int out) {
    ProtoOutputStream proto;
    for (const ConfigKey& configKey : mConfigManager->GetAllConfigKeys()) {
        uint64_t reportsListToken =
                proto.start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_REPORTS_LIST);
        // Don't include the current bucket to avoid skipping buckets.
        // If we need to include the current bucket later, consider changing to NO_TIME_CONSTRAINTS
        // or other alternatives to avoid skipping buckets for pulled metrics.
        mProcessor->onDumpReport(configKey, getElapsedRealtimeNs(),
                                 false /* includeCurrentBucket */, false /* erase_data */,
                                 ADB_DUMP,
                                 FAST,
                                 &proto);
        proto.end(reportsListToken);
        proto.flush(out);
        proto.clear();
    }
}

/**
 * Implementation of the adb shell cmd stats command.
 */
status_t StatsService::handleShellCommand(int in, int out, int err, const char** argv,
                                          uint32_t argc) {
    uid_t uid = AIBinder_getCallingUid();
    if (uid != AID_ROOT && uid != AID_SHELL) {
        return PERMISSION_DENIED;
    }

    Vector<String8> utf8Args;
    utf8Args.setCapacity(argc);
    for (uint32_t i = 0; i < argc; i++) {
        utf8Args.push(String8(argv[i]));
    }

    if (argc >= 1) {
        // adb shell cmd stats config ...
        if (!utf8Args[0].compare(String8("config"))) {
            return cmd_config(in, out, err, utf8Args);
        }

        if (!utf8Args[0].compare(String8("print-uid-map"))) {
            return cmd_print_uid_map(out, utf8Args);
        }

        if (!utf8Args[0].compare(String8("dump-report"))) {
            return cmd_dump_report(out, utf8Args);
        }

        if (!utf8Args[0].compare(String8("pull-source")) && argc > 1) {
            return cmd_print_pulled_metrics(out, utf8Args);
        }

        if (!utf8Args[0].compare(String8("send-broadcast"))) {
            return cmd_trigger_broadcast(out, utf8Args);
        }

        if (!utf8Args[0].compare(String8("print-stats"))) {
            return cmd_print_stats(out, utf8Args);
        }

        if (!utf8Args[0].compare(String8("meminfo"))) {
            return cmd_dump_memory_info(out);
        }

        if (!utf8Args[0].compare(String8("write-to-disk"))) {
            return cmd_write_data_to_disk(out);
        }

        if (!utf8Args[0].compare(String8("log-app-breadcrumb"))) {
            return cmd_log_app_breadcrumb(out, utf8Args);
        }

        if (!utf8Args[0].compare(String8("log-binary-push"))) {
            return cmd_log_binary_push(out, utf8Args);
        }

        if (!utf8Args[0].compare(String8("clear-puller-cache"))) {
            return cmd_clear_puller_cache(out);
        }

        if (!utf8Args[0].compare(String8("print-logs"))) {
            return cmd_print_logs(out, utf8Args);
        }

        if (!utf8Args[0].compare(String8("send-active-configs"))) {
            return cmd_trigger_active_config_broadcast(out, utf8Args);
        }

        if (!utf8Args[0].compare(String8("data-subscribe"))) {
            {
                std::lock_guard<std::mutex> lock(mShellSubscriberMutex);
                if (mShellSubscriber == nullptr) {
                    mShellSubscriber = new ShellSubscriber(mUidMap, mPullerManager);
                }
            }
            int timeoutSec = -1;
            if (argc >= 2) {
                timeoutSec = atoi(utf8Args[1].c_str());
            }
            mShellSubscriber->startNewSubscription(in, out, timeoutSec);
            return NO_ERROR;
        }
    }

    print_cmd_help(out);
    return NO_ERROR;
}

void StatsService::print_cmd_help(int out) {
    dprintf(out,
            "usage: adb shell cmd stats print-stats-log [tag_required] "
            "[timestamp_nsec_optional]\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out, "usage: adb shell cmd stats meminfo\n");
    dprintf(out, "\n");
    dprintf(out, "  Prints the malloc debug information. You need to run the following first: \n");
    dprintf(out, "   # adb shell stop\n");
    dprintf(out, "   # adb shell setprop libc.debug.malloc.program statsd \n");
    dprintf(out, "   # adb shell setprop libc.debug.malloc.options backtrace \n");
    dprintf(out, "   # adb shell start\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out, "usage: adb shell cmd stats print-uid-map [PKG]\n");
    dprintf(out, "\n");
    dprintf(out, "  Prints the UID, app name, version mapping.\n");
    dprintf(out, "  PKG           Optional package name to print the uids of the package\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out, "usage: adb shell cmd stats pull-source ATOM_TAG [PACKAGE] \n");
    dprintf(out, "\n");
    dprintf(out, "  Prints the output of a pulled atom\n");
    dprintf(out, "  UID           The atom to pull\n");
    dprintf(out, "  PACKAGE       The package to pull from. Default is AID_SYSTEM\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out, "usage: adb shell cmd stats write-to-disk \n");
    dprintf(out, "\n");
    dprintf(out, "  Flushes all data on memory to disk.\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out, "usage: adb shell cmd stats log-app-breadcrumb [UID] LABEL STATE\n");
    dprintf(out, "  Writes an AppBreadcrumbReported event to the statslog buffer.\n");
    dprintf(out, "  UID           The uid to use. It is only possible to pass a UID\n");
    dprintf(out, "                parameter on eng builds. If UID is omitted the calling\n");
    dprintf(out, "                uid is used.\n");
    dprintf(out, "  LABEL         Integer in [0, 15], as per atoms.proto.\n");
    dprintf(out, "  STATE         Integer in [0, 3], as per atoms.proto.\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out,
            "usage: adb shell cmd stats log-binary-push NAME VERSION STAGING ROLLBACK_ENABLED "
            "LOW_LATENCY STATE EXPERIMENT_IDS\n");
    dprintf(out, "  Log a binary push state changed event.\n");
    dprintf(out, "  NAME                The train name.\n");
    dprintf(out, "  VERSION             The train version code.\n");
    dprintf(out, "  STAGING             If this train requires a restart.\n");
    dprintf(out, "  ROLLBACK_ENABLED    If rollback should be enabled for this install.\n");
    dprintf(out, "  LOW_LATENCY         If the train requires low latency monitoring.\n");
    dprintf(out, "  STATE               The status of the train push.\n");
    dprintf(out, "                      Integer value of the enum in atoms.proto.\n");
    dprintf(out, "  EXPERIMENT_IDS      Comma separated list of experiment ids.\n");
    dprintf(out, "                      Leave blank for none.\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out, "usage: adb shell cmd stats config remove [UID] [NAME]\n");
    dprintf(out, "usage: adb shell cmd stats config update [UID] NAME\n");
    dprintf(out, "\n");
    dprintf(out, "  Adds, updates or removes a configuration. The proto should be in\n");
    dprintf(out, "  wire-encoded protobuf format and passed via stdin. If no UID and name is\n");
    dprintf(out, "  provided, then all configs will be removed from memory and disk.\n");
    dprintf(out, "\n");
    dprintf(out, "  UID           The uid to use. It is only possible to pass the UID\n");
    dprintf(out, "                parameter on eng builds. If UID is omitted the calling\n");
    dprintf(out, "                uid is used.\n");
    dprintf(out, "  NAME          The per-uid name to use\n");
    dprintf(out, "\n");
    dprintf(out, "\n              *Note: If both UID and NAME are omitted then all configs will\n");
    dprintf(out, "\n                     be removed from memory and disk!\n");
    dprintf(out, "\n");
    dprintf(out,
            "usage: adb shell cmd stats dump-report [UID] NAME [--keep_data] "
            "[--include_current_bucket] [--proto]\n");
    dprintf(out, "  Dump all metric data for a configuration.\n");
    dprintf(out, "  UID           The uid of the configuration. It is only possible to pass\n");
    dprintf(out, "                the UID parameter on eng builds. If UID is omitted the\n");
    dprintf(out, "                calling uid is used.\n");
    dprintf(out, "  NAME          The name of the configuration\n");
    dprintf(out, "  --keep_data   Do NOT erase the data upon dumping it.\n");
    dprintf(out, "  --proto       Print proto binary.\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out, "usage: adb shell cmd stats send-broadcast [UID] NAME\n");
    dprintf(out, "  Send a broadcast that triggers the subscriber to fetch metrics.\n");
    dprintf(out, "  UID           The uid of the configuration. It is only possible to pass\n");
    dprintf(out, "                the UID parameter on eng builds. If UID is omitted the\n");
    dprintf(out, "                calling uid is used.\n");
    dprintf(out, "  NAME          The name of the configuration\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out,
            "usage: adb shell cmd stats send-active-configs [--uid=UID] [--configs] "
            "[NAME1] [NAME2] [NAME3..]\n");
    dprintf(out, "  Send a broadcast that informs the subscriber of the current active configs.\n");
    dprintf(out, "  --uid=UID     The uid of the configurations. It is only possible to pass\n");
    dprintf(out, "                the UID parameter on eng builds. If UID is omitted the\n");
    dprintf(out, "                calling uid is used.\n");
    dprintf(out, "  --configs     Send the list of configs in the name list instead of\n");
    dprintf(out, "                the currently active configs\n");
    dprintf(out, "  NAME LIST     List of configuration names to be included in the broadcast.\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out, "usage: adb shell cmd stats print-stats\n");
    dprintf(out, "  Prints some basic stats.\n");
    dprintf(out, "  --proto       Print proto binary instead of string format.\n");
    dprintf(out, "\n");
    dprintf(out, "\n");
    dprintf(out, "usage: adb shell cmd stats clear-puller-cache\n");
    dprintf(out, "  Clear cached puller data.\n");
    dprintf(out, "\n");
    dprintf(out, "usage: adb shell cmd stats print-logs\n");
    dprintf(out, "      Only works on eng build\n");
}

status_t StatsService::cmd_trigger_broadcast(int out, Vector<String8>& args) {
    string name;
    bool good = false;
    int uid;
    const int argCount = args.size();
    if (argCount == 2) {
        // Automatically pick the UID
        uid = AIBinder_getCallingUid();
        name.assign(args[1].c_str(), args[1].size());
        good = true;
    } else if (argCount == 3) {
        good = getUidFromArgs(args, 1, uid);
        if (!good) {
            dprintf(out, "Invalid UID. Note that the metrics can only be dumped for "
                         "other UIDs on eng or userdebug builds.\n");
        }
        name.assign(args[2].c_str(), args[2].size());
    }
    if (!good) {
        print_cmd_help(out);
        return UNKNOWN_ERROR;
    }
    ConfigKey key(uid, StrToInt64(name));
    shared_ptr<IPendingIntentRef> receiver = mConfigManager->GetConfigReceiver(key);
    if (receiver == nullptr) {
        VLOG("Could not find receiver for %s, %s", args[1].c_str(), args[2].c_str());
        return UNKNOWN_ERROR;
    } else if (receiver->sendDataBroadcast(mProcessor->getLastReportTimeNs(key)).isOk()) {
        VLOG("StatsService::trigger broadcast succeeded to %s, %s", args[1].c_str(),
             args[2].c_str());
    } else {
        VLOG("StatsService::trigger broadcast failed to %s, %s", args[1].c_str(), args[2].c_str());
        return UNKNOWN_ERROR;
    }
    return NO_ERROR;
}

status_t StatsService::cmd_trigger_active_config_broadcast(int out, Vector<String8>& args) {
    const int argCount = args.size();
    int uid;
    vector<int64_t> configIds;
    if (argCount == 1) {
        // Automatically pick the uid and send a broadcast that has no active configs.
        uid = AIBinder_getCallingUid();
        mProcessor->GetActiveConfigs(uid, configIds);
    } else {
        int curArg = 1;
        if(args[curArg].find("--uid=") == 0) {
            string uidArgStr(args[curArg].c_str());
            string uidStr = uidArgStr.substr(6);
            if (!getUidFromString(uidStr.c_str(), uid)) {
                dprintf(out, "Invalid UID. Note that the config can only be set for "
                             "other UIDs on eng or userdebug builds.\n");
                return UNKNOWN_ERROR;
            }
            curArg++;
        } else {
            uid = AIBinder_getCallingUid();
        }
        if (curArg == argCount || args[curArg] != "--configs") {
            VLOG("Reached end of args, or specify configs not set. Sending actual active configs,");
            mProcessor->GetActiveConfigs(uid, configIds);
        } else {
            // Flag specified, use the given list of configs.
            curArg++;
            for (int i = curArg; i < argCount; i++) {
                char* endp;
                int64_t configID = strtoll(args[i].c_str(), &endp, 10);
                if (endp == args[i].c_str() || *endp != '\0') {
                    dprintf(out, "Error parsing config ID.\n");
                    return UNKNOWN_ERROR;
                }
                VLOG("Adding config id %ld", static_cast<long>(configID));
                configIds.push_back(configID);
            }
        }
    }
    shared_ptr<IPendingIntentRef> receiver = mConfigManager->GetActiveConfigsChangedReceiver(uid);
    if (receiver == nullptr) {
        VLOG("Could not find receiver for uid %d", uid);
        return UNKNOWN_ERROR;
    } else if (receiver->sendActiveConfigsChangedBroadcast(configIds).isOk()) {
        VLOG("StatsService::trigger active configs changed broadcast succeeded for uid %d" , uid);
    } else {
        VLOG("StatsService::trigger active configs changed broadcast failed for uid %d", uid);
        return UNKNOWN_ERROR;
    }
    return NO_ERROR;
}

status_t StatsService::cmd_config(int in, int out, int err, Vector<String8>& args) {
    const int argCount = args.size();
    if (argCount >= 2) {
        if (args[1] == "update" || args[1] == "remove") {
            bool good = false;
            int uid = -1;
            string name;

            if (argCount == 3) {
                // Automatically pick the UID
                uid = AIBinder_getCallingUid();
                name.assign(args[2].c_str(), args[2].size());
                good = true;
            } else if (argCount == 4) {
                good = getUidFromArgs(args, 2, uid);
                if (!good) {
                    dprintf(err, "Invalid UID. Note that the config can only be set for "
                                 "other UIDs on eng or userdebug builds.\n");
                }
                name.assign(args[3].c_str(), args[3].size());
            } else if (argCount == 2 && args[1] == "remove") {
                good = true;
            }

            if (!good) {
                // If arg parsing failed, print the help text and return an error.
                print_cmd_help(out);
                return UNKNOWN_ERROR;
            }

            if (args[1] == "update") {
                char* endp;
                int64_t configID = strtoll(name.c_str(), &endp, 10);
                if (endp == name.c_str() || *endp != '\0') {
                    dprintf(err, "Error parsing config ID.\n");
                    return UNKNOWN_ERROR;
                }

                // Read stream into buffer.
                string buffer;
                if (!android::base::ReadFdToString(in, &buffer)) {
                    dprintf(err, "Error reading stream for StatsConfig.\n");
                    return UNKNOWN_ERROR;
                }

                // Parse buffer.
                StatsdConfig config;
                if (!config.ParseFromString(buffer)) {
                    dprintf(err, "Error parsing proto stream for StatsConfig.\n");
                    return UNKNOWN_ERROR;
                }

                // Add / update the config.
                mConfigManager->UpdateConfig(ConfigKey(uid, configID), config);
            } else {
                if (argCount == 2) {
                    cmd_remove_all_configs(out);
                } else {
                    // Remove the config.
                    mConfigManager->RemoveConfig(ConfigKey(uid, StrToInt64(name)));
                }
            }

            return NO_ERROR;
        }
    }
    print_cmd_help(out);
    return UNKNOWN_ERROR;
}

status_t StatsService::cmd_dump_report(int out, const Vector<String8>& args) {
    if (mProcessor != nullptr) {
        int argCount = args.size();
        bool good = false;
        bool proto = false;
        bool includeCurrentBucket = false;
        bool eraseData = true;
        int uid;
        string name;
        if (!std::strcmp("--proto", args[argCount-1].c_str())) {
            proto = true;
            argCount -= 1;
        }
        if (!std::strcmp("--include_current_bucket", args[argCount-1].c_str())) {
            includeCurrentBucket = true;
            argCount -= 1;
        }
        if (!std::strcmp("--keep_data", args[argCount-1].c_str())) {
            eraseData = false;
            argCount -= 1;
        }
        if (argCount == 2) {
            // Automatically pick the UID
            uid = AIBinder_getCallingUid();
            name.assign(args[1].c_str(), args[1].size());
            good = true;
        } else if (argCount == 3) {
            good = getUidFromArgs(args, 1, uid);
            if (!good) {
                dprintf(out, "Invalid UID. Note that the metrics can only be dumped for "
                             "other UIDs on eng or userdebug builds.\n");
            }
            name.assign(args[2].c_str(), args[2].size());
        }
        if (good) {
            vector<uint8_t> data;
            mProcessor->onDumpReport(ConfigKey(uid, StrToInt64(name)), getElapsedRealtimeNs(),
                                     includeCurrentBucket, eraseData, ADB_DUMP,
                                     NO_TIME_CONSTRAINTS,
                                     &data);
            if (proto) {
                for (size_t i = 0; i < data.size(); i ++) {
                    dprintf(out, "%c", data[i]);
                }
            } else {
                dprintf(out, "Non-proto stats data dump not currently supported.\n");
            }
            return android::OK;
        } else {
            // If arg parsing failed, print the help text and return an error.
            print_cmd_help(out);
            return UNKNOWN_ERROR;
        }
    } else {
        dprintf(out, "Log processor does not exist...\n");
        return UNKNOWN_ERROR;
    }
}

status_t StatsService::cmd_print_stats(int out, const Vector<String8>& args) {
    int argCount = args.size();
    bool proto = false;
    if (!std::strcmp("--proto", args[argCount-1].c_str())) {
        proto = true;
        argCount -= 1;
    }
    StatsdStats& statsdStats = StatsdStats::getInstance();
    if (proto) {
        vector<uint8_t> data;
        statsdStats.dumpStats(&data, false); // does not reset statsdStats.
        for (size_t i = 0; i < data.size(); i ++) {
            dprintf(out, "%c", data[i]);
        }

    } else {
        vector<ConfigKey> configs = mConfigManager->GetAllConfigKeys();
        for (const ConfigKey& key : configs) {
            dprintf(out, "Config %s uses %zu bytes\n", key.ToString().c_str(),
                    mProcessor->GetMetricsSize(key));
        }
        statsdStats.dumpStats(out);
    }
    return NO_ERROR;
}

status_t StatsService::cmd_print_uid_map(int out, const Vector<String8>& args) {
    if (args.size() > 1) {
        string pkg;
        pkg.assign(args[1].c_str(), args[1].size());
        auto uids = mUidMap->getAppUid(pkg);
        dprintf(out, "%s -> [ ", pkg.c_str());
        for (const auto& uid : uids) {
            dprintf(out, "%d ", uid);
        }
        dprintf(out, "]\n");
    } else {
        mUidMap->printUidMap(out);
    }
    return NO_ERROR;
}

status_t StatsService::cmd_write_data_to_disk(int out) {
    dprintf(out, "Writing data to disk\n");
    mProcessor->WriteDataToDisk(ADB_DUMP, NO_TIME_CONSTRAINTS);
    return NO_ERROR;
}

status_t StatsService::cmd_log_app_breadcrumb(int out, const Vector<String8>& args) {
    bool good = false;
    int32_t uid;
    int32_t label;
    int32_t state;
    const int argCount = args.size();
    if (argCount == 3) {
        // Automatically pick the UID
        uid = AIBinder_getCallingUid();
        label = atoi(args[1].c_str());
        state = atoi(args[2].c_str());
        good = true;
    } else if (argCount == 4) {
        good = getUidFromArgs(args, 1, uid);
        if (!good) {
            dprintf(out,
                    "Invalid UID. Note that selecting a UID for writing AppBreadcrumb can only be "
                    "done for other UIDs on eng or userdebug builds.\n");
        }
        label = atoi(args[2].c_str());
        state = atoi(args[3].c_str());
    }
    if (good) {
        dprintf(out, "Logging AppBreadcrumbReported(%d, %d, %d) to statslog.\n", uid, label, state);
        android::os::statsd::util::stats_write(
                android::os::statsd::util::APP_BREADCRUMB_REPORTED, uid, label, state);
    } else {
        print_cmd_help(out);
        return UNKNOWN_ERROR;
    }
    return NO_ERROR;
}

status_t StatsService::cmd_log_binary_push(int out, const Vector<String8>& args) {
    // Security checks are done in the sendBinaryPushStateChanged atom.
    const int argCount = args.size();
    if (argCount != 7 && argCount != 8) {
        dprintf(out, "Incorrect number of argument supplied\n");
        return UNKNOWN_ERROR;
    }
    string trainName = string(args[1].c_str());
    int64_t trainVersion = strtoll(args[2].c_str(), nullptr, 10);
    int32_t state = atoi(args[6].c_str());
    vector<int64_t> experimentIds;
    if (argCount == 8) {
        vector<string> experimentIdsString = android::base::Split(string(args[7].c_str()), ",");
        for (string experimentIdString : experimentIdsString) {
            int64_t experimentId = strtoll(experimentIdString.c_str(), nullptr, 10);
            experimentIds.push_back(experimentId);
        }
    }
    dprintf(out, "Logging BinaryPushStateChanged\n");
    vector<uint8_t> experimentIdBytes;
    writeExperimentIdsToProto(experimentIds, &experimentIdBytes);
    LogEvent event(trainName, trainVersion, args[3], args[4], args[5], state, experimentIdBytes, 0);
    mProcessor->OnLogEvent(&event);
    return NO_ERROR;
}

status_t StatsService::cmd_print_pulled_metrics(int out, const Vector<String8>& args) {
    int s = atoi(args[1].c_str());
    vector<int32_t> uids;
    if (args.size() > 2) {
        string package = string(args[2].c_str());
        auto it = UidMap::sAidToUidMapping.find(package);
        if (it != UidMap::sAidToUidMapping.end()) {
            uids.push_back(it->second);
        } else {
            set<int32_t> uids_set = mUidMap->getAppUid(package);
            uids.insert(uids.end(), uids_set.begin(), uids_set.end());
        }
    } else {
        uids.push_back(AID_SYSTEM);
    }
    vector<shared_ptr<LogEvent>> stats;
    if (mPullerManager->Pull(s, uids, getElapsedRealtimeNs(), &stats)) {
        for (const auto& it : stats) {
            dprintf(out, "Pull from %d: %s\n", s, it->ToString().c_str());
        }
        dprintf(out, "Pull from %d: Received %zu elements\n", s, stats.size());
        return NO_ERROR;
    }
    return UNKNOWN_ERROR;
}

status_t StatsService::cmd_remove_all_configs(int out) {
    dprintf(out, "Removing all configs...\n");
    VLOG("StatsService::cmd_remove_all_configs was called");
    mConfigManager->RemoveAllConfigs();
    StorageManager::deleteAllFiles(STATS_SERVICE_DIR);
    return NO_ERROR;
}

status_t StatsService::cmd_dump_memory_info(int out) {
    dprintf(out, "meminfo not available.\n");
    return NO_ERROR;
}

status_t StatsService::cmd_clear_puller_cache(int out) {
    VLOG("StatsService::cmd_clear_puller_cache with Pid %i, Uid %i",
            AIBinder_getCallingPid(), AIBinder_getCallingUid());
    if (checkPermission(kPermissionDump)) {
        int cleared = mPullerManager->ForceClearPullerCache();
        dprintf(out, "Puller removed %d cached data!\n", cleared);
        return NO_ERROR;
    } else {
        return PERMISSION_DENIED;
    }
}

status_t StatsService::cmd_print_logs(int out, const Vector<String8>& args) {
    VLOG("StatsService::cmd_print_logs with Pid %i, Uid %i", AIBinder_getCallingPid(),
         AIBinder_getCallingUid());
    if (checkPermission(kPermissionDump)) {
        bool enabled = true;
        if (args.size() >= 2) {
            enabled = atoi(args[1].c_str()) != 0;
        }
        mProcessor->setPrintLogs(enabled);
        return NO_ERROR;
    } else {
        return PERMISSION_DENIED;
    }
}

bool StatsService::getUidFromArgs(const Vector<String8>& args, size_t uidArgIndex, int32_t& uid) {
    return getUidFromString(args[uidArgIndex].c_str(), uid);
}

bool StatsService::getUidFromString(const char* s, int32_t& uid) {
    if (*s == '\0') {
        return false;
    }
    char* endc = NULL;
    int64_t longUid = strtol(s, &endc, 0);
    if (*endc != '\0') {
        return false;
    }
    int32_t goodUid = static_cast<int32_t>(longUid);
    if (longUid < 0 || static_cast<uint64_t>(longUid) != static_cast<uid_t>(goodUid)) {
        return false;  // It was not of uid_t type.
    }
    uid = goodUid;

    int32_t callingUid = AIBinder_getCallingUid();
    return mEngBuild // UserDebug/EngBuild are allowed to impersonate uids.
            || (callingUid == goodUid) // Anyone can 'impersonate' themselves.
            || (callingUid == AID_ROOT && goodUid == AID_SHELL); // ROOT can impersonate SHELL.
}

Status StatsService::informAllUidData(const ScopedFileDescriptor& fd) {
    ENFORCE_UID(AID_SYSTEM);
    // Read stream into buffer.
    string buffer;
    if (!android::base::ReadFdToString(fd.get(), &buffer)) {
        return exception(EX_ILLEGAL_ARGUMENT, "Failed to read all data from the pipe.");
    }

    // Parse buffer.
    UidData uidData;
    if (!uidData.ParseFromString(buffer)) {
        return exception(EX_ILLEGAL_ARGUMENT, "Error parsing proto stream for UidData.");
    }

    vector<String16> versionStrings;
    vector<String16> installers;
    vector<String16> packageNames;
    vector<int32_t> uids;
    vector<int64_t> versions;

    const auto numEntries = uidData.app_info_size();
    versionStrings.reserve(numEntries);
    installers.reserve(numEntries);
    packageNames.reserve(numEntries);
    uids.reserve(numEntries);
    versions.reserve(numEntries);

    for (const auto& appInfo: uidData.app_info()) {
        packageNames.emplace_back(String16(appInfo.package_name().c_str()));
        uids.push_back(appInfo.uid());
        versions.push_back(appInfo.version());
        versionStrings.emplace_back(String16(appInfo.version_string().c_str()));
        installers.emplace_back(String16(appInfo.installer().c_str()));
    }

    mUidMap->updateMap(getElapsedRealtimeNs(),
                       uids,
                       versions,
                       versionStrings,
                       packageNames,
                       installers);

    mBootCompleteTrigger.markComplete(kUidMapReceivedTag);
    VLOG("StatsService::informAllUidData UidData proto parsed successfully.");
    return Status::ok();
}

Status StatsService::informOnePackage(const string& app, int32_t uid, int64_t version,
                                      const string& versionString, const string& installer) {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::informOnePackage was called");
    String16 utf16App = String16(app.c_str());
    String16 utf16VersionString = String16(versionString.c_str());
    String16 utf16Installer = String16(installer.c_str());

    mUidMap->updateApp(getElapsedRealtimeNs(), utf16App, uid, version, utf16VersionString,
                       utf16Installer);
    return Status::ok();
}

Status StatsService::informOnePackageRemoved(const string& app, int32_t uid) {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::informOnePackageRemoved was called");
    String16 utf16App = String16(app.c_str());
    mUidMap->removeApp(getElapsedRealtimeNs(), utf16App, uid);
    mConfigManager->RemoveConfigs(uid);
    return Status::ok();
}

Status StatsService::informAnomalyAlarmFired() {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::informAnomalyAlarmFired was called");
    int64_t currentTimeSec = getElapsedRealtimeSec();
    std::unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> alarmSet =
            mAnomalyAlarmMonitor->popSoonerThan(static_cast<uint32_t>(currentTimeSec));
    if (alarmSet.size() > 0) {
        VLOG("Found an anomaly alarm that fired.");
        mProcessor->onAnomalyAlarmFired(currentTimeSec * NS_PER_SEC, alarmSet);
    } else {
        VLOG("Cannot find an anomaly alarm that fired. Perhaps it was recently cancelled.");
    }
    return Status::ok();
}

Status StatsService::informAlarmForSubscriberTriggeringFired() {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::informAlarmForSubscriberTriggeringFired was called");
    int64_t currentTimeSec = getElapsedRealtimeSec();
    std::unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> alarmSet =
            mPeriodicAlarmMonitor->popSoonerThan(static_cast<uint32_t>(currentTimeSec));
    if (alarmSet.size() > 0) {
        VLOG("Found periodic alarm fired.");
        mProcessor->onPeriodicAlarmFired(currentTimeSec * NS_PER_SEC, alarmSet);
    } else {
        ALOGW("Cannot find an periodic alarm that fired. Perhaps it was recently cancelled.");
    }
    return Status::ok();
}

Status StatsService::informPollAlarmFired() {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::informPollAlarmFired was called");
    mProcessor->informPullAlarmFired(getElapsedRealtimeNs());
    VLOG("StatsService::informPollAlarmFired succeeded");
    return Status::ok();
}

Status StatsService::systemRunning() {
    ENFORCE_UID(AID_SYSTEM);

    // When system_server is up and running, schedule the dropbox task to run.
    VLOG("StatsService::systemRunning");
    sayHiToStatsCompanion();
    return Status::ok();
}

Status StatsService::informDeviceShutdown() {
    ENFORCE_UID(AID_SYSTEM);
    VLOG("StatsService::informDeviceShutdown");
    mProcessor->WriteDataToDisk(DEVICE_SHUTDOWN, FAST);
    mProcessor->SaveActiveConfigsToDisk(getElapsedRealtimeNs());
    mProcessor->SaveMetadataToDisk(getWallClockNs(), getElapsedRealtimeNs());
    return Status::ok();
}

void StatsService::sayHiToStatsCompanion() {
    shared_ptr<IStatsCompanionService> statsCompanion = getStatsCompanionService();
    if (statsCompanion != nullptr) {
        VLOG("Telling statsCompanion that statsd is ready");
        statsCompanion->statsdReady();
    } else {
        VLOG("Could not access statsCompanion");
    }
}

Status StatsService::statsCompanionReady() {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::statsCompanionReady was called");
    shared_ptr<IStatsCompanionService> statsCompanion = getStatsCompanionService();
    if (statsCompanion == nullptr) {
        return exception(EX_NULL_POINTER,
                         "StatsCompanion unavailable despite it contacting statsd.");
    }
    VLOG("StatsService::statsCompanionReady linking to statsCompanion.");
    AIBinder_linkToDeath(statsCompanion->asBinder().get(),
                         mStatsCompanionServiceDeathRecipient.get(), this);
    mPullerManager->SetStatsCompanionService(statsCompanion);
    mAnomalyAlarmMonitor->setStatsCompanionService(statsCompanion);
    mPeriodicAlarmMonitor->setStatsCompanionService(statsCompanion);
    return Status::ok();
}

Status StatsService::bootCompleted() {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::bootCompleted was called");
    mBootCompleteTrigger.markComplete(kBootCompleteTag);
    return Status::ok();
}

void StatsService::Startup() {
    mConfigManager->Startup();
    mProcessor->LoadActiveConfigsFromDisk();
    mProcessor->LoadMetadataFromDisk(getWallClockNs(), getElapsedRealtimeNs());
}

void StatsService::Terminate() {
    ALOGI("StatsService::Terminating");
    if (mProcessor != nullptr) {
        mProcessor->WriteDataToDisk(TERMINATION_SIGNAL_RECEIVED, FAST);
        mProcessor->SaveActiveConfigsToDisk(getElapsedRealtimeNs());
        mProcessor->SaveMetadataToDisk(getWallClockNs(), getElapsedRealtimeNs());
    }
}

// Test only interface!!!
void StatsService::OnLogEvent(LogEvent* event) {
    mProcessor->OnLogEvent(event);
    if (mShellSubscriber != nullptr) {
        mShellSubscriber->onLogEvent(*event);
    }
}

Status StatsService::getData(int64_t key, const int32_t callingUid, vector<int8_t>* output) {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::getData with Uid %i", callingUid);
    ConfigKey configKey(callingUid, key);
    // TODO(b/149254662): Since libbinder_ndk uses int8_t instead of uint8_t,
    // there are inconsistencies with internal statsd logic. Instead of
    // modifying lots of files, we create a temporary output array of int8_t and
    // copy its data into output. This is a bad hack, but hopefully
    // libbinder_ndk will transition to using uint8_t soon: progress is tracked
    // in b/144957764. Same applies to StatsService::getMetadata.
    vector<uint8_t> unsignedOutput;
    // The dump latency does not matter here since we do not include the current bucket, we do not
    // need to pull any new data anyhow.
    mProcessor->onDumpReport(configKey, getElapsedRealtimeNs(), false /* include_current_bucket*/,
                             true /* erase_data */, GET_DATA_CALLED, FAST, &unsignedOutput);
    *output = vector<int8_t>(unsignedOutput.begin(), unsignedOutput.end());
    return Status::ok();
}

Status StatsService::getMetadata(vector<int8_t>* output) {
    ENFORCE_UID(AID_SYSTEM);

    vector<uint8_t> unsignedOutput;
    StatsdStats::getInstance().dumpStats(&unsignedOutput, false); // Don't reset the counters.
    *output = vector<int8_t>(unsignedOutput.begin(), unsignedOutput.end());
    return Status::ok();
}

Status StatsService::addConfiguration(int64_t key, const vector <int8_t>& config,
                                      const int32_t callingUid) {
    ENFORCE_UID(AID_SYSTEM);

    if (addConfigurationChecked(callingUid, key, config)) {
        return Status::ok();
    } else {
        return exception(EX_ILLEGAL_ARGUMENT, "Could not parse malformatted StatsdConfig.");
    }
}

bool StatsService::addConfigurationChecked(int uid, int64_t key, const vector<int8_t>& config) {
    ConfigKey configKey(uid, key);
    StatsdConfig cfg;
    if (config.size() > 0) {  // If the config is empty, skip parsing.
        if (!cfg.ParseFromArray(&config[0], config.size())) {
            return false;
        }
    }
    mConfigManager->UpdateConfig(configKey, cfg);
    return true;
}

Status StatsService::removeDataFetchOperation(int64_t key,
                                              const int32_t callingUid) {
    ENFORCE_UID(AID_SYSTEM);
    ConfigKey configKey(callingUid, key);
    mConfigManager->RemoveConfigReceiver(configKey);
    return Status::ok();
}

Status StatsService::setDataFetchOperation(int64_t key,
                                           const shared_ptr<IPendingIntentRef>& pir,
                                           const int32_t callingUid) {
    ENFORCE_UID(AID_SYSTEM);

    ConfigKey configKey(callingUid, key);
    mConfigManager->SetConfigReceiver(configKey, pir);
    if (StorageManager::hasConfigMetricsReport(configKey)) {
        VLOG("StatsService::setDataFetchOperation marking configKey %s to dump reports on disk",
             configKey.ToString().c_str());
        mProcessor->noteOnDiskData(configKey);
    }
    return Status::ok();
}

Status StatsService::setActiveConfigsChangedOperation(const shared_ptr<IPendingIntentRef>& pir,
                                                      const int32_t callingUid,
                                                      vector<int64_t>* output) {
    ENFORCE_UID(AID_SYSTEM);

    mConfigManager->SetActiveConfigsChangedReceiver(callingUid, pir);
    if (output != nullptr) {
        mProcessor->GetActiveConfigs(callingUid, *output);
    } else {
        ALOGW("StatsService::setActiveConfigsChanged output was nullptr");
    }
    return Status::ok();
}

Status StatsService::removeActiveConfigsChangedOperation(const int32_t callingUid) {
    ENFORCE_UID(AID_SYSTEM);

    mConfigManager->RemoveActiveConfigsChangedReceiver(callingUid);
    return Status::ok();
}

Status StatsService::removeConfiguration(int64_t key, const int32_t callingUid) {
    ENFORCE_UID(AID_SYSTEM);

    ConfigKey configKey(callingUid, key);
    mConfigManager->RemoveConfig(configKey);
    return Status::ok();
}

Status StatsService::setBroadcastSubscriber(int64_t configId,
                                            int64_t subscriberId,
                                            const shared_ptr<IPendingIntentRef>& pir,
                                            const int32_t callingUid) {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::setBroadcastSubscriber called.");
    ConfigKey configKey(callingUid, configId);
    SubscriberReporter::getInstance()
            .setBroadcastSubscriber(configKey, subscriberId, pir);
    return Status::ok();
}

Status StatsService::unsetBroadcastSubscriber(int64_t configId,
                                              int64_t subscriberId,
                                              const int32_t callingUid) {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::unsetBroadcastSubscriber called.");
    ConfigKey configKey(callingUid, configId);
    SubscriberReporter::getInstance()
            .unsetBroadcastSubscriber(configKey, subscriberId);
    return Status::ok();
}

Status StatsService::allPullersFromBootRegistered() {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::allPullersFromBootRegistered was called");
    mBootCompleteTrigger.markComplete(kAllPullersRegisteredTag);
    return Status::ok();
}

Status StatsService::registerPullAtomCallback(int32_t uid, int32_t atomTag, int64_t coolDownMillis,
                                              int64_t timeoutMillis,
                                              const std::vector<int32_t>& additiveFields,
                                              const shared_ptr<IPullAtomCallback>& pullerCallback) {
    ENFORCE_UID(AID_SYSTEM);
    VLOG("StatsService::registerPullAtomCallback called.");
    mPullerManager->RegisterPullAtomCallback(uid, atomTag, MillisToNano(coolDownMillis),
                                             MillisToNano(timeoutMillis), additiveFields,
                                             pullerCallback);
    return Status::ok();
}

Status StatsService::registerNativePullAtomCallback(
        int32_t atomTag, int64_t coolDownMillis, int64_t timeoutMillis,
        const std::vector<int32_t>& additiveFields,
        const shared_ptr<IPullAtomCallback>& pullerCallback) {
    if (!checkPermission(kPermissionRegisterPullAtom)) {
        return exception(
                EX_SECURITY,
                StringPrintf("Uid %d does not have the %s permission when registering atom %d",
                             AIBinder_getCallingUid(), kPermissionRegisterPullAtom, atomTag));
    }
    VLOG("StatsService::registerNativePullAtomCallback called.");
    int32_t uid = AIBinder_getCallingUid();
    mPullerManager->RegisterPullAtomCallback(uid, atomTag, MillisToNano(coolDownMillis),
                                             MillisToNano(timeoutMillis), additiveFields,
                                             pullerCallback);
    return Status::ok();
}

Status StatsService::unregisterPullAtomCallback(int32_t uid, int32_t atomTag) {
    ENFORCE_UID(AID_SYSTEM);
    VLOG("StatsService::unregisterPullAtomCallback called.");
    mPullerManager->UnregisterPullAtomCallback(uid, atomTag);
    return Status::ok();
}

Status StatsService::unregisterNativePullAtomCallback(int32_t atomTag) {
    if (!checkPermission(kPermissionRegisterPullAtom)) {
        return exception(
                EX_SECURITY,
                StringPrintf("Uid %d does not have the %s permission when unregistering atom %d",
                             AIBinder_getCallingUid(), kPermissionRegisterPullAtom, atomTag));
    }
    VLOG("StatsService::unregisterNativePullAtomCallback called.");
    int32_t uid = AIBinder_getCallingUid();
    mPullerManager->UnregisterPullAtomCallback(uid, atomTag);
    return Status::ok();
}

Status StatsService::getRegisteredExperimentIds(std::vector<int64_t>* experimentIdsOut) {
    ENFORCE_UID(AID_SYSTEM);
    // TODO: add verifier permission

    experimentIdsOut->clear();
    // Read the latest train info
    vector<InstallTrainInfo> trainInfoList = StorageManager::readAllTrainInfo();
    if (trainInfoList.empty()) {
        // No train info means no experiment IDs, return an empty list
        return Status::ok();
    }

    // Copy the experiment IDs to the out vector
    for (InstallTrainInfo& trainInfo : trainInfoList) {
        experimentIdsOut->insert(experimentIdsOut->end(),
                                 trainInfo.experimentIds.begin(),
                                 trainInfo.experimentIds.end());
    }
    return Status::ok();
}

void StatsService::statsCompanionServiceDied(void* cookie) {
    auto thiz = static_cast<StatsService*>(cookie);
    thiz->statsCompanionServiceDiedImpl();
}

void StatsService::statsCompanionServiceDiedImpl() {
    ALOGW("statscompanion service died");
    StatsdStats::getInstance().noteSystemServerRestart(getWallClockSec());
    if (mProcessor != nullptr) {
        ALOGW("Reset statsd upon system server restarts.");
        int64_t systemServerRestartNs = getElapsedRealtimeNs();
        ProtoOutputStream activeConfigsProto;
        mProcessor->WriteActiveConfigsToProtoOutputStream(systemServerRestartNs,
                STATSCOMPANION_DIED, &activeConfigsProto);
        metadata::StatsMetadataList metadataList;
        mProcessor->WriteMetadataToProto(getWallClockNs(),
                systemServerRestartNs, &metadataList);
        mProcessor->WriteDataToDisk(STATSCOMPANION_DIED, FAST);
        mProcessor->resetConfigs();

        std::string serializedActiveConfigs;
        if (activeConfigsProto.serializeToString(&serializedActiveConfigs)) {
            ActiveConfigList activeConfigs;
            if (activeConfigs.ParseFromString(serializedActiveConfigs)) {
                mProcessor->SetConfigsActiveState(activeConfigs, systemServerRestartNs);
            }
        }
        mProcessor->SetMetadataState(metadataList, getWallClockNs(), systemServerRestartNs);
    }
    mAnomalyAlarmMonitor->setStatsCompanionService(nullptr);
    mPeriodicAlarmMonitor->setStatsCompanionService(nullptr);
    mPullerManager->SetStatsCompanionService(nullptr);
}

void StatsService::stopReadingLogs() {
    mIsStopRequested = true;
    // Push this event so that readLogs will process and break out of the loop
    // after the stop is requested.
    int64_t timeStamp;
    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    mEventQueue->push(std::move(logEvent), &timeStamp);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
