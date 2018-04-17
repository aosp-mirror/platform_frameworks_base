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
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/PermissionController.h>
#include <dirent.h>
#include <frameworks/base/cmds/statsd/src/statsd_config.pb.h>
#include <private/android_filesystem_config.h>
#include <utils/Looper.h>
#include <utils/String16.h>
#include <statslog.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <unistd.h>

using namespace android;

using android::base::StringPrintf;

namespace android {
namespace os {
namespace statsd {

constexpr const char* kPermissionDump = "android.permission.DUMP";
constexpr const char* kPermissionUsage = "android.permission.PACKAGE_USAGE_STATS";

constexpr const char* kOpUsage = "android:get_usage_stats";

#define STATS_SERVICE_DIR "/data/misc/stats-service"

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

StatsService::StatsService(const sp<Looper>& handlerLooper)
    : mAnomalyAlarmMonitor(new AlarmMonitor(MIN_DIFF_TO_UPDATE_REGISTERED_ALARM_SECS,
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
   mPeriodicAlarmMonitor(new AlarmMonitor(MIN_DIFF_TO_UPDATE_REGISTERED_ALARM_SECS,
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

      }))  {
    mUidMap = new UidMap();
    StatsPuller::SetUidMap(mUidMap);
    mConfigManager = new ConfigManager();
    mProcessor = new StatsLogProcessor(mUidMap, mAnomalyAlarmMonitor, mPeriodicAlarmMonitor,
                                       getElapsedRealtimeNs(), [this](const ConfigKey& key) {
        sp<IStatsCompanionService> sc = getStatsCompanionService();
        auto receiver = mConfigManager->GetConfigReceiver(key);
        if (sc == nullptr) {
            VLOG("Could not find StatsCompanionService");
        } else if (receiver == nullptr) {
            VLOG("Statscompanion could not find a broadcast receiver for %s",
                 key.ToString().c_str());
        } else {
            sc->sendDataBroadcast(receiver, mProcessor->getLastReportTimeNs(key));
        }
    }
    );

    mConfigManager->AddListener(mProcessor);

    init_system_properties();
}

StatsService::~StatsService() {
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

            FILE* fin = fdopen(in, "r");
            FILE* fout = fdopen(out, "w");
            FILE* ferr = fdopen(err, "w");

            if (fin == NULL || fout == NULL || ferr == NULL) {
                resultReceiver->send(NO_MEMORY);
            } else {
                err = command(fin, fout, ferr, args);
                resultReceiver->send(err);
            }

            if (fin != NULL) {
                fflush(fin);
                fclose(fin);
            }
            if (fout != NULL) {
                fflush(fout);
                fclose(fout);
            }
            if (fout != NULL) {
                fflush(ferr);
                fclose(ferr);
            }

            return NO_ERROR;
        }
        default: { return BnStatsManager::onTransact(code, data, reply, flags); }
    }
}

/**
 * Write debugging data about statsd.
 */
status_t StatsService::dump(int fd, const Vector<String16>& args) {
    if (!checkCallingPermission(String16(kPermissionDump))) {
        return PERMISSION_DENIED;
    }
    FILE* out = fdopen(fd, "w");
    if (out == NULL) {
        return NO_MEMORY;  // the fd is already open
    }

    bool verbose = false;
    bool proto = false;
    if (args.size() > 0 && !args[0].compare(String16("-v"))) {
        verbose = true;
    }
    if (args.size() > 0 && !args[args.size()-1].compare(String16("--proto"))) {
        proto = true;
    }

    dump_impl(out, verbose, proto);

    fclose(out);
    return NO_ERROR;
}

/**
 * Write debugging data about statsd in text or proto format.
 */
void StatsService::dump_impl(FILE* out, bool verbose, bool proto) {
    if (proto) {
        vector<uint8_t> data;
        StatsdStats::getInstance().dumpStats(&data, false); // does not reset statsdStats.
        for (size_t i = 0; i < data.size(); i ++) {
            fprintf(out, "%c", data[i]);
        }
    } else {
        StatsdStats::getInstance().dumpStats(out);
        mProcessor->dumpStates(out, verbose);
    }
}

/**
 * Implementation of the adb shell cmd stats command.
 */
status_t StatsService::command(FILE* in, FILE* out, FILE* err, Vector<String8>& args) {
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
            return cmd_dump_report(out, err, args);
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

        if (!args[0].compare(String8("clear-puller-cache"))) {
            return cmd_clear_puller_cache(out);
        }
    }

    print_cmd_help(out);
    return NO_ERROR;
}

void StatsService::print_cmd_help(FILE* out) {
    fprintf(out,
            "usage: adb shell cmd stats print-stats-log [tag_required] "
            "[timestamp_nsec_optional]\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats meminfo\n");
    fprintf(out, "\n");
    fprintf(out, "  Prints the malloc debug information. You need to run the following first: \n");
    fprintf(out, "   # adb shell stop\n");
    fprintf(out, "   # adb shell setprop libc.debug.malloc.program statsd \n");
    fprintf(out, "   # adb shell setprop libc.debug.malloc.options backtrace \n");
    fprintf(out, "   # adb shell start\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats print-uid-map [PKG]\n");
    fprintf(out, "\n");
    fprintf(out, "  Prints the UID, app name, version mapping.\n");
    fprintf(out, "  PKG           Optional package name to print the uids of the package\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats pull-source [int] \n");
    fprintf(out, "\n");
    fprintf(out, "  Prints the output of a pulled metrics source (int indicates source)\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats write-to-disk \n");
    fprintf(out, "\n");
    fprintf(out, "  Flushes all data on memory to disk.\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats log-app-breadcrumb [UID] LABEL STATE\n");
    fprintf(out, "  Writes an AppBreadcrumbReported event to the statslog buffer.\n");
    fprintf(out, "  UID           The uid to use. It is only possible to pass a UID\n");
    fprintf(out, "                parameter on eng builds. If UID is omitted the calling\n");
    fprintf(out, "                uid is used.\n");
    fprintf(out, "  LABEL         Integer in [0, 15], as per atoms.proto.\n");
    fprintf(out, "  STATE         Integer in [0, 3], as per atoms.proto.\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats config remove [UID] [NAME]\n");
    fprintf(out, "usage: adb shell cmd stats config update [UID] NAME\n");
    fprintf(out, "\n");
    fprintf(out, "  Adds, updates or removes a configuration. The proto should be in\n");
    fprintf(out, "  wire-encoded protobuf format and passed via stdin. If no UID and name is\n");
    fprintf(out, "  provided, then all configs will be removed from memory and disk.\n");
    fprintf(out, "\n");
    fprintf(out, "  UID           The uid to use. It is only possible to pass the UID\n");
    fprintf(out, "                parameter on eng builds. If UID is omitted the calling\n");
    fprintf(out, "                uid is used.\n");
    fprintf(out, "  NAME          The per-uid name to use\n");
    fprintf(out, "\n");
    fprintf(out, "\n              *Note: If both UID and NAME are omitted then all configs will\n");
    fprintf(out, "\n                     be removed from memory and disk!\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats dump-report [UID] NAME [--proto]\n");
    fprintf(out, "  Dump all metric data for a configuration.\n");
    fprintf(out, "  UID           The uid of the configuration. It is only possible to pass\n");
    fprintf(out, "                the UID parameter on eng builds. If UID is omitted the\n");
    fprintf(out, "                calling uid is used.\n");
    fprintf(out, "  NAME          The name of the configuration\n");
    fprintf(out, "  --proto       Print proto binary.\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats send-broadcast [UID] NAME\n");
    fprintf(out, "  Send a broadcast that triggers the subscriber to fetch metrics.\n");
    fprintf(out, "  UID           The uid of the configuration. It is only possible to pass\n");
    fprintf(out, "                the UID parameter on eng builds. If UID is omitted the\n");
    fprintf(out, "                calling uid is used.\n");
    fprintf(out, "  NAME          The name of the configuration\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats print-stats\n");
    fprintf(out, "  Prints some basic stats.\n");
    fprintf(out, "  --proto       Print proto binary instead of string format.\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats clear-puller-cache\n");
    fprintf(out, "  Clear cached puller data.\n");
}

status_t StatsService::cmd_trigger_broadcast(FILE* out, Vector<String8>& args) {
    string name;
    bool good = false;
    int uid;
    const int argCount = args.size();
    if (argCount == 2) {
        // Automatically pick the UID
        uid = IPCThreadState::self()->getCallingUid();
        // TODO: What if this isn't a binder call? Should we fail?
        name.assign(args[1].c_str(), args[1].size());
        good = true;
    } else if (argCount == 3) {
        // If it's a userdebug or eng build, then the shell user can
        // impersonate other uids.
        if (mEngBuild) {
            const char* s = args[1].c_str();
            if (*s != '\0') {
                char* end = NULL;
                uid = strtol(s, &end, 0);
                if (*end == '\0') {
                    name.assign(args[2].c_str(), args[2].size());
                    good = true;
                }
            }
        } else {
            fprintf(out,
                    "The metrics can only be dumped for other UIDs on eng or userdebug "
                            "builds.\n");
        }
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

status_t StatsService::cmd_config(FILE* in, FILE* out, FILE* err, Vector<String8>& args) {
    const int argCount = args.size();
    if (argCount >= 2) {
        if (args[1] == "update" || args[1] == "remove") {
            bool good = false;
            int uid = -1;
            string name;

            if (argCount == 3) {
                // Automatically pick the UID
                uid = IPCThreadState::self()->getCallingUid();
                // TODO: What if this isn't a binder call? Should we fail?
                name.assign(args[2].c_str(), args[2].size());
                good = true;
            } else if (argCount == 4) {
                // If it's a userdebug or eng build, then the shell user can
                // impersonate other uids.
                if (mEngBuild) {
                    const char* s = args[2].c_str();
                    if (*s != '\0') {
                        char* end = NULL;
                        uid = strtol(s, &end, 0);
                        if (*end == '\0') {
                            name.assign(args[3].c_str(), args[3].size());
                            good = true;
                        }
                    }
                } else {
                    fprintf(err,
                            "The config can only be set for other UIDs on eng or userdebug "
                            "builds.\n");
                }
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
                    fprintf(err, "Error parsing config ID.\n");
                    return UNKNOWN_ERROR;
                }

                // Read stream into buffer.
                string buffer;
                if (!android::base::ReadFdToString(fileno(in), &buffer)) {
                    fprintf(err, "Error reading stream for StatsConfig.\n");
                    return UNKNOWN_ERROR;
                }

                // Parse buffer.
                StatsdConfig config;
                if (!config.ParseFromString(buffer)) {
                    fprintf(err, "Error parsing proto stream for StatsConfig.\n");
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

status_t StatsService::cmd_dump_report(FILE* out, FILE* err, const Vector<String8>& args) {
    if (mProcessor != nullptr) {
        int argCount = args.size();
        bool good = false;
        bool proto = false;
        int uid;
        string name;
        if (!std::strcmp("--proto", args[argCount-1].c_str())) {
            proto = true;
            argCount -= 1;
        }
        if (argCount == 2) {
            // Automatically pick the UID
            uid = IPCThreadState::self()->getCallingUid();
            // TODO: What if this isn't a binder call? Should we fail?
            name.assign(args[1].c_str(), args[1].size());
            good = true;
        } else if (argCount == 3) {
            // If it's a userdebug or eng build, then the shell user can
            // impersonate other uids.
            if (mEngBuild) {
                const char* s = args[1].c_str();
                if (*s != '\0') {
                    char* end = NULL;
                    uid = strtol(s, &end, 0);
                    if (*end == '\0') {
                        name.assign(args[2].c_str(), args[2].size());
                        good = true;
                    }
                }
            } else {
                fprintf(out,
                        "The metrics can only be dumped for other UIDs on eng or userdebug "
                        "builds.\n");
            }
        }
        if (good) {
            vector<uint8_t> data;
            mProcessor->onDumpReport(ConfigKey(uid, StrToInt64(name)), getElapsedRealtimeNs(),
                                     false /* include_current_bucket*/, &data);
            // TODO: print the returned StatsLogReport to file instead of printing to logcat.
            if (proto) {
                for (size_t i = 0; i < data.size(); i ++) {
                    fprintf(out, "%c", data[i]);
                }
            } else {
                fprintf(out, "Dump report for Config [%d,%s]\n", uid, name.c_str());
                fprintf(out, "See the StatsLogReport in logcat...\n");
            }
            return android::OK;
        } else {
            // If arg parsing failed, print the help text and return an error.
            print_cmd_help(out);
            return UNKNOWN_ERROR;
        }
    } else {
        fprintf(out, "Log processor does not exist...\n");
        return UNKNOWN_ERROR;
    }
}

status_t StatsService::cmd_print_stats(FILE* out, const Vector<String8>& args) {
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
            fprintf(out, "%c", data[i]);
        }

    } else {
        vector<ConfigKey> configs = mConfigManager->GetAllConfigKeys();
        for (const ConfigKey& key : configs) {
            fprintf(out, "Config %s uses %zu bytes\n", key.ToString().c_str(),
                    mProcessor->GetMetricsSize(key));
        }
        statsdStats.dumpStats(out);
    }
    return NO_ERROR;
}

status_t StatsService::cmd_print_uid_map(FILE* out, const Vector<String8>& args) {
    if (args.size() > 1) {
        string pkg;
        pkg.assign(args[1].c_str(), args[1].size());
        auto uids = mUidMap->getAppUid(pkg);
        fprintf(out, "%s -> [ ", pkg.c_str());
        for (const auto& uid : uids) {
            fprintf(out, "%d ", uid);
        }
        fprintf(out, "]\n");
    } else {
        mUidMap->printUidMap(out);
    }
    return NO_ERROR;
}

status_t StatsService::cmd_write_data_to_disk(FILE* out) {
    fprintf(out, "Writing data to disk\n");
    mProcessor->WriteDataToDisk();
    return NO_ERROR;
}

status_t StatsService::cmd_log_app_breadcrumb(FILE* out, const Vector<String8>& args) {
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
        uid = atoi(args[1].c_str());
        // If it's a userdebug or eng build, then the shell user can impersonate other uids.
        // Otherwise, the uid must match the actual caller's uid.
        if (mEngBuild || (uid >= 0 && (uid_t)uid == IPCThreadState::self()->getCallingUid())) {
            label = atoi(args[2].c_str());
            state = atoi(args[3].c_str());
            good = true;
        } else {
            fprintf(out,
                    "Selecting a UID for writing AppBreadcrumb can only be done for other UIDs "
                            "on eng or userdebug builds.\n");
        }
    }
    if (good) {
        fprintf(out, "Logging AppBreadcrumbReported(%d, %d, %d) to statslog.\n", uid, label, state);
        android::util::stats_write(android::util::APP_BREADCRUMB_REPORTED, uid, label, state);
    } else {
        print_cmd_help(out);
        return UNKNOWN_ERROR;
    }
    return NO_ERROR;
}

status_t StatsService::cmd_print_pulled_metrics(FILE* out, const Vector<String8>& args) {
    int s = atoi(args[1].c_str());
    vector<shared_ptr<LogEvent> > stats;
    if (mStatsPullerManager.Pull(s, getElapsedRealtimeNs(), &stats)) {
        for (const auto& it : stats) {
            fprintf(out, "Pull from %d: %s\n", s, it->ToString().c_str());
        }
        fprintf(out, "Pull from %d: Received %zu elements\n", s, stats.size());
        return NO_ERROR;
    }
    return UNKNOWN_ERROR;
}

status_t StatsService::cmd_remove_all_configs(FILE* out) {
    fprintf(out, "Removing all configs...\n");
    VLOG("StatsService::cmd_remove_all_configs was called");
    mConfigManager->RemoveAllConfigs();
    StorageManager::deleteAllFiles(STATS_SERVICE_DIR);
    return NO_ERROR;
}

status_t StatsService::cmd_dump_memory_info(FILE* out) {
    fprintf(out, "meminfo not available.\n");
    return NO_ERROR;
}

status_t StatsService::cmd_clear_puller_cache(FILE* out) {
    IPCThreadState* ipc = IPCThreadState::self();
    VLOG("StatsService::cmd_clear_puller_cache with Pid %i, Uid %i",
            ipc->getCallingPid(), ipc->getCallingUid());
    if (checkCallingPermission(String16(kPermissionDump))) {
        int cleared = mStatsPullerManager.ForceClearPullerCache();
        fprintf(out, "Puller removed %d cached data!\n", cleared);
        return NO_ERROR;
    } else {
        return PERMISSION_DENIED;
    }
}

Status StatsService::informAllUidData(const vector<int32_t>& uid, const vector<int64_t>& version,
                                      const vector<String16>& app) {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::informAllUidData was called");
    mUidMap->updateMap(getElapsedRealtimeNs(), uid, version, app);
    VLOG("StatsService::informAllUidData succeeded");

    return Status::ok();
}

Status StatsService::informOnePackage(const String16& app, int32_t uid, int64_t version) {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::informOnePackage was called");
    mUidMap->updateApp(getElapsedRealtimeNs(), app, uid, version);
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

Status StatsService::writeDataToDisk() {
    ENFORCE_UID(AID_SYSTEM);

    VLOG("StatsService::writeDataToDisk");
    mProcessor->WriteDataToDisk();
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
    mStatsPullerManager.SetStatsCompanionService(statsCompanion);
    mAnomalyAlarmMonitor->setStatsCompanionService(statsCompanion);
    mPeriodicAlarmMonitor->setStatsCompanionService(statsCompanion);
    SubscriberReporter::getInstance().setStatsCompanionService(statsCompanion);
    return Status::ok();
}

void StatsService::Startup() {
    mConfigManager->Startup();
}

void StatsService::OnLogEvent(LogEvent* event, bool reconnectionStarts) {
    mProcessor->OnLogEvent(event, reconnectionStarts);
}

Status StatsService::getData(int64_t key, const String16& packageName, vector<uint8_t>* output) {
    ENFORCE_DUMP_AND_USAGE_STATS(packageName);

    IPCThreadState* ipc = IPCThreadState::self();
    VLOG("StatsService::getData with Pid %i, Uid %i", ipc->getCallingPid(), ipc->getCallingUid());
    ConfigKey configKey(ipc->getCallingUid(), key);
    mProcessor->onDumpReport(configKey, getElapsedRealtimeNs(),
                             false /* include_current_bucket*/, output);
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

void StatsService::binderDied(const wp <IBinder>& who) {
    ALOGW("statscompanion service died");
    mProcessor->WriteDataToDisk();
    mAnomalyAlarmMonitor->setStatsCompanionService(nullptr);
    mPeriodicAlarmMonitor->setStatsCompanionService(nullptr);
    SubscriberReporter::getInstance().setStatsCompanionService(nullptr);
    mStatsPullerManager.SetStatsCompanionService(nullptr);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
