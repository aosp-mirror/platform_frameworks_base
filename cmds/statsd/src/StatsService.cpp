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
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/PermissionController.h>
#include <cutils/multiuser.h>
#include <dirent.h>
#include <frameworks/base/cmds/statsd/src/statsd_config.pb.h>
#include <frameworks/base/cmds/statsd/src/uid_data.pb.h>
#include <private/android_filesystem_config.h>
#include <statslog.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <utils/Looper.h>
#include <utils/String16.h>
#include <chrono>

using namespace android;

using android::base::StringPrintf;
using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::ProtoReader;

namespace android {
namespace os {
namespace statsd {

constexpr const char* kPermissionDump = "android.permission.DUMP";
constexpr const char* kPermissionUsage = "android.permission.PACKAGE_USAGE_STATS";

constexpr const char* kOpUsage = "android:get_usage_stats";

#define STATS_SERVICE_DIR "/data/misc/stats-service"

// for StatsDataDumpProto
const int FIELD_ID_REPORTS_LIST = 1;

static binder::Status ok() {
    return binder::Status::ok();
}

static binder::Status exception(uint32_t code, const std::string& msg) {
    ALOGE("%s (%d)", msg.c_str(), code);
    return binder::Status::fromExceptionCode(code, String8(msg.c_str()));
}

binder::Status checkUid(uid_t expectedUid) {
    uid_t uid = IPCThreadState::self()->getCallingUid();
    if (uid == expectedUid || uid == AID_ROOT) {
        return ok();
    } else {
        return exception(binder::Status::EX_SECURITY,
                StringPrintf("UID %d is not expected UID %d", uid, expectedUid));
    }
}

binder::Status checkDumpAndUsageStats(const String16& packageName) {
    pid_t pid = IPCThreadState::self()->getCallingPid();
    uid_t uid = IPCThreadState::self()->getCallingUid();

    // Root, system, and shell always have access
    if (uid == AID_ROOT || uid == AID_SYSTEM || uid == AID_SHELL) {
        return ok();
    }

    // Caller must be granted these permissions
    if (!checkCallingPermission(String16(kPermissionDump))) {
        return exception(binder::Status::EX_SECURITY,
                StringPrintf("UID %d / PID %d lacks permission %s", uid, pid, kPermissionDump));
    }
    if (!checkCallingPermission(String16(kPermissionUsage))) {
        return exception(binder::Status::EX_SECURITY,
                StringPrintf("UID %d / PID %d lacks permission %s", uid, pid, kPermissionUsage));
    }

    // Caller must also have usage stats op granted
    PermissionController pc;
    switch (pc.noteOp(String16(kOpUsage), uid, packageName)) {
        case PermissionController::MODE_ALLOWED:
        case PermissionController::MODE_DEFAULT:
            return ok();
        default:
            return exception(binder::Status::EX_SECURITY,
                    StringPrintf("UID %d / PID %d lacks app-op %s", uid, pid, kOpUsage));
    }
}

#define ENFORCE_UID(uid) {                                        \
    binder::Status status = checkUid((uid));                      \
    if (!status.isOk()) {                                         \
        return status;                                            \
    }                                                             \
}

#define ENFORCE_DUMP_AND_USAGE_STATS(packageName) {               \
    binder::Status status = checkDumpAndUsageStats(packageName);  \
    if (!status.isOk()) {                                         \
        return status;                                            \
    }                                                             \
}

StatsService::StatsService(const sp<Looper>& handlerLooper, shared_ptr<LogEventQueue> queue)
    : mAnomalyAlarmMonitor(new AlarmMonitor(
              MIN_DIFF_TO_UPDATE_REGISTERED_ALARM_SECS,
              [](const sp<IStatsCompanionService>& sc, int64_t timeMillis) {
                  if (sc != nullptr) {
                      sc->setAnomalyAlarm(timeMillis);
                      StatsdStats::getInstance().noteRegisteredAnomalyAlarmChanged();
                  }
              },
              [](const sp<IStatsCompanionService>& sc) {
                  if (sc != nullptr) {
                      sc->cancelAnomalyAlarm();
                      StatsdStats::getInstance().noteRegisteredAnomalyAlarmChanged();
                  }
              })),
      mPeriodicAlarmMonitor(new AlarmMonitor(
              MIN_DIFF_TO_UPDATE_REGISTERED_ALARM_SECS,
              [](const sp<IStatsCompanionService>& sc, int64_t timeMillis) {
                  if (sc != nullptr) {
                      sc->setAlarmForSubscriberTriggering(timeMillis);
                      StatsdStats::getInstance().noteRegisteredPeriodicAlarmChanged();
                  }
              },
              [](const sp<IStatsCompanionService>& sc) {
                  if (sc != nullptr) {
                      sc->cancelAlarmForSubscriberTriggering();
                      StatsdStats::getInstance().noteRegisteredPeriodicAlarmChanged();
                  }
              })),
      mEventQueue(queue) {
    mUidMap = UidMap::getInstance();
    mPullerManager = new StatsPullerManager();
    StatsPuller::SetUidMap(mUidMap);
    mConfigManager = new ConfigManager();
    mProcessor = new StatsLogProcessor(
            mUidMap, mPullerManager, mAnomalyAlarmMonitor, mPeriodicAlarmMonitor,
            getElapsedRealtimeNs(),
            [this](const ConfigKey& key) {
                sp<IStatsCompanionService> sc = getStatsCompanionService();
                auto receiver = mConfigManager->GetConfigReceiver(key);
                if (sc == nullptr) {
                    VLOG("Could not find StatsCompanionService");
                    return false;
                } else if (receiver == nullptr) {
                    VLOG("Statscompanion could not find a broadcast receiver for %s",
                         key.ToString().c_str());
                    return false;
                } else {
                    sc->sendDataBroadcast(receiver, mProcessor->getLastReportTimeNs(key));
                    return true;
                }
            },
            [this](const int& uid, const vector<int64_t>& activeConfigs) {
                auto receiver = mConfigManager->GetActiveConfigsChangedReceiver(uid);
                sp<IStatsCompanionService> sc = getStatsCompanionService();
                if (sc == nullptr) {
                    VLOG("Could not access statsCompanion");
                    return false;
                } else if (receiver == nullptr) {
                    VLOG("Could not find receiver for uid %d", uid);
                    return false;
                } else {
                    sc->sendActiveConfigsChangedBroadcast(receiver, activeConfigs);
                    VLOG("StatsService::active configs broadcast succeeded for uid %d" , uid);
                    return true;
                }
            });

    mConfigManager->AddListener(mProcessor);

    init_system_properties();

    if (mEventQueue != nullptr) {
        std::thread pushedEventThread([this] { readLogs(); });
        pushedEventThread.detach();
    }
}

StatsService::~StatsService() {
}

/* Runs on a dedicated thread to process pushed events. */
void StatsService::readLogs() {
    // Read forever..... long live statsd
    while (1) {
        // Block until an event is available.
        auto event = mEventQueue->waitPop();
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
 * Implement our own because the default binder implementation isn't
 * properly handling SHELL_COMMAND_TRANSACTION.
 */
status_t StatsService::onTransact(uint32_t code, const Parcel& data, Parcel* reply,
                                  uint32_t flags) {
    switch (code) {
        case SHELL_COMMAND_TRANSACTION: {
            int in = data.readFileDescriptor();
            int out = data.readFileDescriptor();
            int err = data.readFileDescriptor();
            int argc = data.readInt32();
            Vector<String8> args;
            for (int i = 0; i < argc && data.dataAvail() > 0; i++) {
                args.add(String8(data.readString16()));
            }
            sp<IShellCallback> shellCallback = IShellCallback::asInterface(data.readStrongBinder());
            sp<IResultReceiver> resultReceiver =
                    IResultReceiver::asInterface(data.readStrongBinder());

            err = command(in, out, err, args, resultReceiver);
            resultReceiver->send(err);
            return NO_ERROR;
        }
        default: { return BnStatsManager::onTransact(code, data, reply, flags); }
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
status_t StatsService::dump(int fd, const Vector<String16>& args) {
    if (!checkCallingPermission(String16(kPermissionDump))) {
        return PERMISSION_DENIED;
    }
    int lastArg = args.size() - 1;
    bool asProto = false;
    if (lastArg >= 0 && !args[lastArg].compare(String16("--proto"))) { // last argument
        asProto = true;
        lastArg--;
    }
    if (args.size() > 0 && !args[0].compare(String16("--metadata"))) { // first argument
        // Request is to dump statsd stats.
        bool verbose = false;
        if (lastArg >= 0 && !args[lastArg].compare(String16("-v"))) {
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
        mProcessor->onDumpReport(configKey, getElapsedRealtimeNs(),
                                 true /* includeCurrentBucket */, false /* erase_data */,
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
status_t StatsService::command(int in, int out, int err, Vector<String8>& args,
                               sp<IResultReceiver> resultReceiver) {
    uid_t uid = IPCThreadState::self()->getCallingUid();
    if (uid != AID_ROOT && uid != AID_SHELL) {
        return PERMISSION_DENIED;
    }

    const int argCount = args.size();
    if (argCount >= 1) {
        // adb shell cmd stats config ...
        if (!args[0].compare(String8("config"))) {
            return cmd_config(in, out, err, args);
        }

        if (!args[0].compare(String8("print-uid-map"))) {
            return cmd_print_uid_map(out, args);
        }

        if (!args[0].compare(String8("dump-report"))) {
            return cmd_dump_report(out, args);
        }

        if (!args[0].compare(String8("pull-source")) && args.size() > 1) {
            return cmd_print_pulled_metrics(out, args);
        }

        if (!args[0].compare(String8("send-broadcast"))) {
            return cmd_trigger_broadcast(out, args);
        }

        if (!args[0].compare(String8("print-stats"))) {
            return cmd_print_stats(out, args);
        }

        if (!args[0].compare(String8("meminfo"))) {
            return cmd_dump_memory_info(out);
        }

        if (!args[0].compare(String8("write-to-disk"))) {
            return cmd_write_data_to_disk(out);
        }

        if (!args[0].compare(String8("log-app-breadcrumb"))) {
            return cmd_log_app_breadcrumb(out, args);
        }

        if (!args[0].compare(String8("log-binary-push"))) {
            return cmd_log_binary_push(out, args);
        }

        if (!args[0].compare(String8("clear-puller-cache"))) {
            return cmd_clear_puller_cache(out);
        }

        if (!args[0].compare(String8("print-logs"))) {
            return cmd_print_logs(out, args);
        }
        if (!args[0].compare(String8("send-active-configs"))) {
            return cmd_trigger_active_config_broadcast(out, args);
        }
        if (!args[0].compare(String8("data-subscribe"))) {
            if (mShellSubscriber == nullptr) {
                mShellSubscriber = new ShellSubscriber(mUidMap, mPullerManager);
            }
            int timeoutSec = -1;
            if (argCount >= 2) {
                timeoutSec = atoi(args[1].c_str());
            }
            mShellSubscriber->startNewSubscription(in, out, resultReceiver, timeoutSec);
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
    dprintf(out, "usage: adb shell cmd stats pull-source [int] \n");
    dprintf(out, "\n");
    dprintf(out, "  Prints the output of a pulled metrics source (int indicates source)\n");
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
        uid = IPCThreadState::self()->getCallingUid();
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
    auto receiver = mConfigManager->GetConfigReceiver(key);
    sp<IStatsCompanionService> sc = getStatsCompanionService();
    if (sc == nullptr) {
        VLOG("Could not access statsCompanion");
    } else if (receiver == nullptr) {
        VLOG("Could not find receiver for %s, %s", args[1].c_str(), args[2].c_str())
    } else {
        sc->sendDataBroadcast(receiver, mProcessor->getLastReportTimeNs(key));
        VLOG("StatsService::trigger broadcast succeeded to %s, %s", args[1].c_str(),
             args[2].c_str());
    }

    return NO_ERROR;
}

status_t StatsService::cmd_trigger_active_config_broadcast(int out, Vector<String8>& args) {
    const int argCount = args.size();
    int uid;
    vector<int64_t> configIds;
    if (argCount == 1) {
        // Automatically pick the uid and send a broadcast that has no active configs.
        uid = IPCThreadState::self()->getCallingUid();
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
            uid = IPCThreadState::self()->getCallingUid();
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
    auto receiver = mConfigManager->GetActiveConfigsChangedReceiver(uid);
    sp<IStatsCompanionService> sc = getStatsCompanionService();
    if (sc == nullptr) {
        VLOG("Could not access statsCompanion");
    } else if (receiver == nullptr) {
        VLOG("Could not find receiver for uid %d", uid);
    } else {
        sc->sendActiveConfigsChangedBroadcast(receiver, configIds);
        VLOG("StatsService::trigger active configs changed broadcast succeeded for uid %d" , uid);
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
                uid = IPCThreadState::self()->getCallingUid();
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
            uid = IPCThreadState::self()->getCallingUid();
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
        uid = IPCThreadState::self()->getCallingUid();
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
        android::util::stats_write(android::util::APP_BREADCRUMB_REPORTED, uid, label, state);
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
    android::String16 trainName = android::String16(args[1].c_str());
    int64_t trainVersion = strtoll(args[2].c_str(), nullptr, 10);
    int options = 0;
    if (args[3] == "1") {
        options = options | IStatsManager::FLAG_REQUIRE_STAGING;
    }
    if (args[4] == "1") {
        options = options | IStatsManager::FLAG_ROLLBACK_ENABLED;
    }
    if (args[5] == "1") {
        options = options | IStatsManager::FLAG_REQUIRE_LOW_LATENCY_MONITOR;
    }
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
    sendBinaryPushStateChangedAtom(trainName, trainVersion, options, state, experimentIds);
    return NO_ERROR;
}

status_t StatsService::cmd_print_pulled_metrics(int out, const Vector<String8>& args) {
    int s = atoi(args[1].c_str());
    vector<shared_ptr<LogEvent> > stats;
    if (mPullerManager->Pull(s, &stats)) {
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
    IPCThreadState* ipc = IPCThreadState::self();
    VLOG("StatsService::cmd_clear_puller_cache with Pid %i, Uid %i",
            ipc->getCallingPid(), ipc->getCallingUid());
    if (checkCallingPermission(String16(kPermissionDump))) {
        int cleared = mPullerManager->ForceClearPullerCache();
        dprintf(out, "Puller removed %d cached data!\n", cleared);
        return NO_ERROR;
    } else {
        return PERMISSION_DENIED;
    }
}

status_t StatsService::cmd_print_logs(int out, const Vector<String8>& args) {
    IPCThreadState* ipc = IPCThreadState::self();
    VLOG("StatsService::cmd_print_logs with Pid %i, Uid %i", ipc->getCallingPid(),
         ipc->getCallingUid());
    if (checkCallingPermission(String16(kPermissionDump))) {
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

    int32_t callingUid = IPCThreadState::self()->getCallingUid();
    return mEngBuild // UserDebug/EngBuild are allowed to impersonate uids.
            || (callingUid == goodUid) // Anyone can 'impersonate' themselves.
            || (callingUid == AID_ROOT && goodUid == AID_SHELL); // ROOT can impersonate SHELL.
}

Status StatsService::informAllUidData(const ParcelFileDescriptor& fd) {
    ENFORCE_UID(AID_SYSTEM);
    // Read stream into buffer.
    string buffer;
    if (!android::base::ReadFdToString(fd.get(), &buffer)) {
        return exception(Status::EX_ILLEGAL_ARGUMENT, "Failed to read all data from the pipe.");
    }

    // Parse buffer.
    UidData uidData;
    if (!uidData.ParseFromString(buffer)) {
        return exception(Status::EX_ILLEGAL_ARGUMENT, "Error parsing proto stream for UidData.");
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

    VLOG("StatsService::informAllUidData UidData proto parsed successfully.");
    return Status::ok();
}

Status StatsService::informOnePackage(const String16& app, int32_t uid, int64_t version,
                                      const String16& version_string, const String16& installer) {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::informOnePackage was called");
    mUidMap->updateApp(getElapsedRealtimeNs(), app, uid, version, version_string, installer);
    return Status::ok();
}

Status StatsService::informOnePackageRemoved(const String16& app, int32_t uid) {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::informOnePackageRemoved was called");
    mUidMap->removeApp(getElapsedRealtimeNs(), app, uid);
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
    return Status::ok();
}

void StatsService::sayHiToStatsCompanion() {
    sp<IStatsCompanionService> statsCompanion = getStatsCompanionService();
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
    sp<IStatsCompanionService> statsCompanion = getStatsCompanionService();
    if (statsCompanion == nullptr) {
        return Status::fromExceptionCode(
                Status::EX_NULL_POINTER,
                "statscompanion unavailable despite it contacting statsd!");
    }
    VLOG("StatsService::statsCompanionReady linking to statsCompanion.");
    IInterface::asBinder(statsCompanion)->linkToDeath(this);
    mPullerManager->SetStatsCompanionService(statsCompanion);
    mAnomalyAlarmMonitor->setStatsCompanionService(statsCompanion);
    mPeriodicAlarmMonitor->setStatsCompanionService(statsCompanion);
    SubscriberReporter::getInstance().setStatsCompanionService(statsCompanion);
    return Status::ok();
}

void StatsService::Startup() {
    mConfigManager->Startup();
    mProcessor->LoadActiveConfigsFromDisk();
}

void StatsService::Terminate() {
    ALOGI("StatsService::Terminating");
    if (mProcessor != nullptr) {
        mProcessor->WriteDataToDisk(TERMINATION_SIGNAL_RECEIVED, FAST);
        mProcessor->SaveActiveConfigsToDisk(getElapsedRealtimeNs());
    }
}

// Test only interface!!!
void StatsService::OnLogEvent(LogEvent* event) {
    mProcessor->OnLogEvent(event);
    if (mShellSubscriber != nullptr) {
        mShellSubscriber->onLogEvent(*event);
    }
}

Status StatsService::getData(int64_t key, const String16& packageName, vector<uint8_t>* output) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    IPCThreadState* ipc = IPCThreadState::self();
    VLOG("StatsService::getData with Pid %i, Uid %i", ipc->getCallingPid(), ipc->getCallingUid());
    ConfigKey configKey(ipc->getCallingUid(), key);
    // The dump latency does not matter here since we do not include the current bucket, we do not
    // need to pull any new data anyhow.
    mProcessor->onDumpReport(configKey, getElapsedRealtimeNs(), false /* include_current_bucket*/,
                             true /* erase_data */, GET_DATA_CALLED, FAST, output);
    return Status::ok();
}

Status StatsService::getMetadata(const String16& packageName, vector<uint8_t>* output) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    IPCThreadState* ipc = IPCThreadState::self();
    VLOG("StatsService::getMetadata with Pid %i, Uid %i", ipc->getCallingPid(),
         ipc->getCallingUid());
    StatsdStats::getInstance().dumpStats(output, false); // Don't reset the counters.
    return Status::ok();
}

Status StatsService::addConfiguration(int64_t key, const vector <uint8_t>& config,
                                      const String16& packageName) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    IPCThreadState* ipc = IPCThreadState::self();
    if (addConfigurationChecked(ipc->getCallingUid(), key, config)) {
        return Status::ok();
    } else {
        ALOGE("Could not parse malformatted StatsdConfig");
        return Status::fromExceptionCode(binder::Status::EX_ILLEGAL_ARGUMENT,
                                         "config does not correspond to a StatsdConfig proto");
    }
}

bool StatsService::addConfigurationChecked(int uid, int64_t key, const vector<uint8_t>& config) {
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

Status StatsService::removeDataFetchOperation(int64_t key, const String16& packageName) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    IPCThreadState* ipc = IPCThreadState::self();
    ConfigKey configKey(ipc->getCallingUid(), key);
    mConfigManager->RemoveConfigReceiver(configKey);
    return Status::ok();
}

Status StatsService::setDataFetchOperation(int64_t key,
                                           const sp<android::IBinder>& intentSender,
                                           const String16& packageName) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    IPCThreadState* ipc = IPCThreadState::self();
    ConfigKey configKey(ipc->getCallingUid(), key);
    mConfigManager->SetConfigReceiver(configKey, intentSender);
    if (StorageManager::hasConfigMetricsReport(configKey)) {
        VLOG("StatsService::setDataFetchOperation marking configKey %s to dump reports on disk",
             configKey.ToString().c_str());
        mProcessor->noteOnDiskData(configKey);
    }
    return Status::ok();
}

Status StatsService::setActiveConfigsChangedOperation(const sp<android::IBinder>& intentSender,
                                                      const String16& packageName,
                                                      vector<int64_t>* output) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    IPCThreadState* ipc = IPCThreadState::self();
    int uid = ipc->getCallingUid();
    mConfigManager->SetActiveConfigsChangedReceiver(uid, intentSender);
    if (output != nullptr) {
        mProcessor->GetActiveConfigs(uid, *output);
    } else {
        ALOGW("StatsService::setActiveConfigsChanged output was nullptr");
    }
    return Status::ok();
}

Status StatsService::removeActiveConfigsChangedOperation(const String16& packageName) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    IPCThreadState* ipc = IPCThreadState::self();
    mConfigManager->RemoveActiveConfigsChangedReceiver(ipc->getCallingUid());
    return Status::ok();
}

Status StatsService::removeConfiguration(int64_t key, const String16& packageName) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    IPCThreadState* ipc = IPCThreadState::self();
    ConfigKey configKey(ipc->getCallingUid(), key);
    mConfigManager->RemoveConfig(configKey);
    SubscriberReporter::getInstance().removeConfig(configKey);
    return Status::ok();
}

Status StatsService::setBroadcastSubscriber(int64_t configId,
                                            int64_t subscriberId,
                                            const sp<android::IBinder>& intentSender,
                                            const String16& packageName) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    VLOG("StatsService::setBroadcastSubscriber called.");
    IPCThreadState* ipc = IPCThreadState::self();
    ConfigKey configKey(ipc->getCallingUid(), configId);
    SubscriberReporter::getInstance()
            .setBroadcastSubscriber(configKey, subscriberId, intentSender);
    return Status::ok();
}

Status StatsService::unsetBroadcastSubscriber(int64_t configId,
                                              int64_t subscriberId,
                                              const String16& packageName) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    VLOG("StatsService::unsetBroadcastSubscriber called.");
    IPCThreadState* ipc = IPCThreadState::self();
    ConfigKey configKey(ipc->getCallingUid(), configId);
    SubscriberReporter::getInstance()
            .unsetBroadcastSubscriber(configKey, subscriberId);
    return Status::ok();
}

Status StatsService::sendAppBreadcrumbAtom(int32_t label, int32_t state) {
    // Permission check not necessary as it's meant for applications to write to
    // statsd.
    android::util::stats_write(util::APP_BREADCRUMB_REPORTED,
                               IPCThreadState::self()->getCallingUid(), label,
                               state);
    return Status::ok();
}

Status StatsService::registerPullerCallback(int32_t atomTag,
        const sp<android::os::IStatsPullerCallback>& pullerCallback,
        const String16& packageName) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    VLOG("StatsService::registerPullerCallback called.");
    mPullerManager->RegisterPullerCallback(atomTag, pullerCallback);
    return Status::ok();
}

Status StatsService::unregisterPullerCallback(int32_t atomTag, const String16& packageName) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    VLOG("StatsService::unregisterPullerCallback called.");
    mPullerManager->UnregisterPullerCallback(atomTag);
    return Status::ok();
}

Status StatsService::sendBinaryPushStateChangedAtom(const android::String16& trainNameIn,
                                                    const int64_t trainVersionCodeIn,
                                                    const int options,
                                                    const int32_t state,
                                                    const std::vector<int64_t>& experimentIdsIn) {
    // Note: We skip the usage stats op check here since we do not have a package name.
    // This is ok since we are overloading the usage_stats permission.
    // This method only sends data, it does not receive it.
    pid_t pid = IPCThreadState::self()->getCallingPid();
    uid_t uid = IPCThreadState::self()->getCallingUid();
    // Root, system, and shell always have access
    if (uid != AID_ROOT && uid != AID_SYSTEM && uid != AID_SHELL) {
        // Caller must be granted these permissions
        if (!checkCallingPermission(String16(kPermissionDump))) {
            return exception(binder::Status::EX_SECURITY,
                             StringPrintf("UID %d / PID %d lacks permission %s", uid, pid,
                                          kPermissionDump));
        }
        if (!checkCallingPermission(String16(kPermissionUsage))) {
            return exception(binder::Status::EX_SECURITY,
                             StringPrintf("UID %d / PID %d lacks permission %s", uid, pid,
                                          kPermissionUsage));
        }
    }

    bool readTrainInfoSuccess = false;
    InstallTrainInfo trainInfoOnDisk;
    readTrainInfoSuccess = StorageManager::readTrainInfo(trainInfoOnDisk);

    bool resetExperimentIds = false;
    int64_t trainVersionCode = trainVersionCodeIn;
    std::string trainNameUtf8 = std::string(String8(trainNameIn).string());
    if (readTrainInfoSuccess) {
        // Keep the old train version if we received an empty version.
        if (trainVersionCodeIn == -1) {
            trainVersionCode = trainInfoOnDisk.trainVersionCode;
        } else if (trainVersionCodeIn != trainInfoOnDisk.trainVersionCode) {
        // Reset experiment ids if we receive a new non-empty train version.
            resetExperimentIds = true;
        }

        // Keep the old train name if we received an empty train name.
        if (trainNameUtf8.size() == 0) {
            trainNameUtf8 = trainInfoOnDisk.trainName;
        } else if (trainNameUtf8 != trainInfoOnDisk.trainName) {
            // Reset experiment ids if we received a new valid train name.
            resetExperimentIds = true;
        }

        // Reset if we received a different experiment id.
        if (!experimentIdsIn.empty() &&
                (trainInfoOnDisk.experimentIds.empty() ||
                 experimentIdsIn[0] != trainInfoOnDisk.experimentIds[0])) {
            resetExperimentIds = true;
        }
    }

    // Find the right experiment IDs
    std::vector<int64_t> experimentIds;
    if (resetExperimentIds || !readTrainInfoSuccess) {
        experimentIds = experimentIdsIn;
    } else {
        experimentIds = trainInfoOnDisk.experimentIds;
    }

    if (!experimentIds.empty()) {
        int64_t firstId = experimentIds[0];
        switch (state) {
            case android::util::BINARY_PUSH_STATE_CHANGED__STATE__INSTALL_SUCCESS:
                experimentIds.push_back(firstId + 1);
                break;
            case android::util::BINARY_PUSH_STATE_CHANGED__STATE__INSTALLER_ROLLBACK_INITIATED:
                experimentIds.push_back(firstId + 2);
                break;
            case android::util::BINARY_PUSH_STATE_CHANGED__STATE__INSTALLER_ROLLBACK_SUCCESS:
                experimentIds.push_back(firstId + 3);
                break;
        }
    }

    // Flatten the experiment IDs to proto
    vector<uint8_t> experimentIdsProtoBuffer;
    writeExperimentIdsToProto(experimentIds, &experimentIdsProtoBuffer);
    StorageManager::writeTrainInfo(trainVersionCode, trainNameUtf8, state, experimentIds);

    userid_t userId = multiuser_get_user_id(uid);
    bool requiresStaging = options & IStatsManager::FLAG_REQUIRE_STAGING;
    bool rollbackEnabled = options & IStatsManager::FLAG_ROLLBACK_ENABLED;
    bool requiresLowLatencyMonitor = options & IStatsManager::FLAG_REQUIRE_LOW_LATENCY_MONITOR;
    LogEvent event(trainNameUtf8, trainVersionCode, requiresStaging, rollbackEnabled,
                   requiresLowLatencyMonitor, state, experimentIdsProtoBuffer, userId);
    mProcessor->OnLogEvent(&event);
    return Status::ok();
}

Status StatsService::sendWatchdogRollbackOccurredAtom(const int32_t rollbackTypeIn,
                                                      const android::String16& packageNameIn,
                                                      const int64_t packageVersionCodeIn) {
    // Note: We skip the usage stats op check here since we do not have a package name.
    // This is ok since we are overloading the usage_stats permission.
    // This method only sends data, it does not receive it.
    pid_t pid = IPCThreadState::self()->getCallingPid();
    uid_t uid = IPCThreadState::self()->getCallingUid();
    // Root, system, and shell always have access
    if (uid != AID_ROOT && uid != AID_SYSTEM && uid != AID_SHELL) {
        // Caller must be granted these permissions
        if (!checkCallingPermission(String16(kPermissionDump))) {
            return exception(binder::Status::EX_SECURITY,
                             StringPrintf("UID %d / PID %d lacks permission %s", uid, pid,
                                          kPermissionDump));
        }
        if (!checkCallingPermission(String16(kPermissionUsage))) {
            return exception(binder::Status::EX_SECURITY,
                             StringPrintf("UID %d / PID %d lacks permission %s", uid, pid,
                                          kPermissionUsage));
        }
    }

    android::util::stats_write(android::util::WATCHDOG_ROLLBACK_OCCURRED,
            rollbackTypeIn, String8(packageNameIn).string(), packageVersionCodeIn);

    // Fast return to save disk read.
    if (rollbackTypeIn != android::util::WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS
            && rollbackTypeIn !=
                    android::util::WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE) {
        return Status::ok();
    }

    bool readTrainInfoSuccess = false;
    InstallTrainInfo trainInfoOnDisk;
    readTrainInfoSuccess = StorageManager::readTrainInfo(trainInfoOnDisk);

    if (!readTrainInfoSuccess) {
        return Status::ok();
    }
    std::vector<int64_t> experimentIds = trainInfoOnDisk.experimentIds;
    if (experimentIds.empty()) {
        return Status::ok();
    }
    switch (rollbackTypeIn) {
        case android::util::WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE:
            experimentIds.push_back(experimentIds[0] + 4);
            break;
        case android::util::WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS:
            experimentIds.push_back(experimentIds[0] + 5);
            break;
    }
    StorageManager::writeTrainInfo(trainInfoOnDisk.trainVersionCode, trainInfoOnDisk.trainName,
            trainInfoOnDisk.status, experimentIds);
    return Status::ok();
}


Status StatsService::getRegisteredExperimentIds(std::vector<int64_t>* experimentIdsOut) {
    uid_t uid = IPCThreadState::self()->getCallingUid();

    // Caller must be granted these permissions
    if (!checkCallingPermission(String16(kPermissionDump))) {
        return exception(binder::Status::EX_SECURITY,
                         StringPrintf("UID %d lacks permission %s", uid, kPermissionDump));
    }
    if (!checkCallingPermission(String16(kPermissionUsage))) {
        return exception(binder::Status::EX_SECURITY,
                         StringPrintf("UID %d lacks permission %s", uid, kPermissionUsage));
    }
    // TODO: add verifier permission

    // Read the latest train info
    InstallTrainInfo trainInfo;
    if (!StorageManager::readTrainInfo(trainInfo)) {
        // No train info means no experiment IDs, return an empty list
        experimentIdsOut->clear();
        return Status::ok();
    }

    // Copy the experiment IDs to the out vector
    experimentIdsOut->assign(trainInfo.experimentIds.begin(), trainInfo.experimentIds.end());
    return Status::ok();
}

hardware::Return<void> StatsService::reportSpeakerImpedance(
        const SpeakerImpedance& speakerImpedance) {
    android::util::stats_write(android::util::SPEAKER_IMPEDANCE_REPORTED,
            speakerImpedance.speakerLocation, speakerImpedance.milliOhms);

    return hardware::Void();
}

hardware::Return<void> StatsService::reportHardwareFailed(const HardwareFailed& hardwareFailed) {
    android::util::stats_write(android::util::HARDWARE_FAILED, int32_t(hardwareFailed.hardwareType),
            hardwareFailed.hardwareLocation, int32_t(hardwareFailed.errorCode));

    return hardware::Void();
}

hardware::Return<void> StatsService::reportPhysicalDropDetected(
        const PhysicalDropDetected& physicalDropDetected) {
    android::util::stats_write(android::util::PHYSICAL_DROP_DETECTED,
            int32_t(physicalDropDetected.confidencePctg), physicalDropDetected.accelPeak,
            physicalDropDetected.freefallDuration);

    return hardware::Void();
}

hardware::Return<void> StatsService::reportChargeCycles(const ChargeCycles& chargeCycles) {
    std::vector<int32_t> buckets = chargeCycles.cycleBucket;
    int initialSize = buckets.size();
    for (int i = 0; i < 10 - initialSize; i++) {
        buckets.push_back(-1); // Push -1 for buckets that do not exist.
    }
    android::util::stats_write(android::util::CHARGE_CYCLES_REPORTED, buckets[0], buckets[1],
            buckets[2], buckets[3], buckets[4], buckets[5], buckets[6], buckets[7], buckets[8],
            buckets[9]);

    return hardware::Void();
}

hardware::Return<void> StatsService::reportBatteryHealthSnapshot(
        const BatteryHealthSnapshotArgs& batteryHealthSnapshotArgs) {
    android::util::stats_write(android::util::BATTERY_HEALTH_SNAPSHOT,
            int32_t(batteryHealthSnapshotArgs.type), batteryHealthSnapshotArgs.temperatureDeciC,
            batteryHealthSnapshotArgs.voltageMicroV, batteryHealthSnapshotArgs.currentMicroA,
            batteryHealthSnapshotArgs.openCircuitVoltageMicroV,
            batteryHealthSnapshotArgs.resistanceMicroOhm, batteryHealthSnapshotArgs.levelPercent);

    return hardware::Void();
}

hardware::Return<void> StatsService::reportSlowIo(const SlowIo& slowIo) {
    android::util::stats_write(android::util::SLOW_IO, int32_t(slowIo.operation), slowIo.count);

    return hardware::Void();
}

hardware::Return<void> StatsService::reportBatteryCausedShutdown(
        const BatteryCausedShutdown& batteryCausedShutdown) {
    android::util::stats_write(android::util::BATTERY_CAUSED_SHUTDOWN,
            batteryCausedShutdown.voltageMicroV);

    return hardware::Void();
}

hardware::Return<void> StatsService::reportUsbPortOverheatEvent(
        const UsbPortOverheatEvent& usbPortOverheatEvent) {
    android::util::stats_write(android::util::USB_PORT_OVERHEAT_EVENT_REPORTED,
            usbPortOverheatEvent.plugTemperatureDeciC, usbPortOverheatEvent.maxTemperatureDeciC,
            usbPortOverheatEvent.timeToOverheat, usbPortOverheatEvent.timeToHysteresis,
            usbPortOverheatEvent.timeToInactive);

    return hardware::Void();
}

hardware::Return<void> StatsService::reportSpeechDspStat(
        const SpeechDspStat& speechDspStat) {
    android::util::stats_write(android::util::SPEECH_DSP_STAT_REPORTED,
            speechDspStat.totalUptimeMillis, speechDspStat.totalDowntimeMillis,
            speechDspStat.totalCrashCount, speechDspStat.totalRecoverCount);

    return hardware::Void();
}

hardware::Return<void> StatsService::reportVendorAtom(const VendorAtom& vendorAtom) {
    std::string reverseDomainName = (std::string) vendorAtom.reverseDomainName;
    if (vendorAtom.atomId < 100000 || vendorAtom.atomId >= 200000) {
        ALOGE("Atom ID %ld is not a valid vendor atom ID", (long) vendorAtom.atomId);
        return hardware::Void();
    }
    if (reverseDomainName.length() > 50) {
        ALOGE("Vendor atom reverse domain name %s is too long.", reverseDomainName.c_str());
        return hardware::Void();
    }
    LogEvent event(getWallClockSec() * NS_PER_SEC, getElapsedRealtimeNs(), vendorAtom);
    mProcessor->OnLogEvent(&event);

    return hardware::Void();
}

void StatsService::binderDied(const wp <IBinder>& who) {
    ALOGW("statscompanion service died");
    StatsdStats::getInstance().noteSystemServerRestart(getWallClockSec());
    if (mProcessor != nullptr) {
        ALOGW("Reset statsd upon system server restarts.");
        int64_t systemServerRestartNs = getElapsedRealtimeNs();
        ProtoOutputStream proto;
        mProcessor->WriteActiveConfigsToProtoOutputStream(systemServerRestartNs,
                STATSCOMPANION_DIED, &proto);

        mProcessor->WriteDataToDisk(STATSCOMPANION_DIED, FAST);
        mProcessor->resetConfigs();

        std::string serializedActiveConfigs;
        if (proto.serializeToString(&serializedActiveConfigs)) {
            ActiveConfigList activeConfigs;
            if (activeConfigs.ParseFromString(serializedActiveConfigs)) {
                mProcessor->SetConfigsActiveState(activeConfigs, systemServerRestartNs);
            }
        }
    }
    mAnomalyAlarmMonitor->setStatsCompanionService(nullptr);
    mPeriodicAlarmMonitor->setStatsCompanionService(nullptr);
    SubscriberReporter::getInstance().setStatsCompanionService(nullptr);
    mPullerManager->SetStatsCompanionService(nullptr);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
