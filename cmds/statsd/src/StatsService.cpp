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

#define DEBUG true
#include "Log.h"

#include "StatsService.h"
#include "storage/DropboxReader.h"

#include <android-base/file.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <frameworks/base/cmds/statsd/src/statsd_config.pb.h>
#include <private/android_filesystem_config.h>
#include <utils/Looper.h>
#include <utils/String16.h>

#include <stdio.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <unistd.h>

using namespace android;

namespace android {
namespace os {
namespace statsd {

// ======================================================================
/**
 * Watches for the death of the stats companion (system process).
 */
class CompanionDeathRecipient : public IBinder::DeathRecipient {
public:
    CompanionDeathRecipient(const sp<AnomalyMonitor>& anomalyMonitor);
    virtual void binderDied(const wp<IBinder>& who);

private:
    const sp<AnomalyMonitor> mAnomalyMonitor;
};

CompanionDeathRecipient::CompanionDeathRecipient(const sp<AnomalyMonitor>& anomalyMonitor)
    : mAnomalyMonitor(anomalyMonitor) {
}

void CompanionDeathRecipient::binderDied(const wp<IBinder>& who) {
    ALOGW("statscompanion service died");
    mAnomalyMonitor->setStatsCompanionService(nullptr);
}

// ======================================================================
StatsService::StatsService(const sp<Looper>& handlerLooper)
    : mStatsPullerManager(),

      mAnomalyMonitor(new AnomalyMonitor(2))  // TODO: Put this comment somewhere better
{
    mUidMap = new UidMap();
    mConfigManager = new ConfigManager();
    mProcessor = new StatsLogProcessor(mUidMap);

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
    if (0 == strcmp("eng", value)) {
        reinterpret_cast<StatsService*>(cookie)->mEngBuild = true;
    }
}

/**
 * Implement our own because the default binder implementation isn't
 * properly handling SHELL_COMMAND_TRANSACTION.
 */
status_t StatsService::onTransact(uint32_t code, const Parcel& data, Parcel* reply,
                                  uint32_t flags) {
    status_t err;

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
    FILE* out = fdopen(fd, "w");
    if (out == NULL) {
        return NO_MEMORY;  // the fd is already open
    }

    // TODO: Proto format for incident reports
    dump_impl(out);

    fclose(out);
    return NO_ERROR;
}

/**
 * Write debugging data about statsd in text format.
 */
void StatsService::dump_impl(FILE* out) {
    mConfigManager->Dump(out);
}

/**
 * Implementation of the adb shell cmd stats command.
 */
status_t StatsService::command(FILE* in, FILE* out, FILE* err, Vector<String8>& args) {
    // TODO: Permission check

    const int argCount = args.size();
    if (argCount >= 1) {
        // adb shell cmd stats config ...
        if (!args[0].compare(String8("config"))) {
            return cmd_config(in, out, err, args);
        }

        // adb shell cmd stats print-stats-log
        if (!args[0].compare(String8("print-stats-log")) && args.size() > 1) {
            return cmd_print_stats_log(out, args);
        }

        // adb shell cmd stats print-stats-log
        if (!args[0].compare(String8("print-uid-map"))) {
            return cmd_print_uid_map(out);
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
    fprintf(out, "usage: adb shell cmd stats print-uid-map \n");
    fprintf(out, "\n");
    fprintf(out, "  Prints the UID, app name, version mapping.\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats config remove [UID] NAME\n");
    fprintf(out, "usage: adb shell cmd stats config update [UID] NAME\n");
    fprintf(out, "\n");
    fprintf(out, "  Adds, updates or removes a configuration. The proto should be in\n");
    fprintf(out, "  wire-encoded protobuf format and passed via stdin.\n");
    fprintf(out, "\n");
    fprintf(out, "  UID           The uid to use. It is only possible to pass the UID\n");
    fprintf(out, "                parameter on eng builds.  If UID is omitted the calling\n");
    fprintf(out, "                uid is used.\n");
    fprintf(out, "  NAME          The per-uid name to use\n");
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
                    fprintf(err, "The config can only be set for other UIDs on eng builds.\n");
                }
            }

            if (!good) {
                // If arg parsing failed, print the help text and return an error.
                print_cmd_help(out);
                return UNKNOWN_ERROR;
            }

            if (args[1] == "update") {
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
                mConfigManager->UpdateConfig(ConfigKey(uid, name), config);
            } else {
                // Remove the config.
                mConfigManager->RemoveConfig(ConfigKey(uid, name));
            }

            return NO_ERROR;
        }
    }
    print_cmd_help(out);
    return UNKNOWN_ERROR;
}

status_t StatsService::cmd_print_stats_log(FILE* out, const Vector<String8>& args) {
    long msec = 0;

    if (args.size() > 2) {
        msec = strtol(args[2].string(), NULL, 10);
    }
    return DropboxReader::readStatsLogs(out, args[1].string(), msec);
}

status_t StatsService::cmd_print_uid_map(FILE* out) {
    mUidMap->printUidMap(out);
    return NO_ERROR;
}

Status StatsService::informAllUidData(const vector<int32_t>& uid, const vector<int32_t>& version,
                                      const vector<String16>& app) {
    if (DEBUG) ALOGD("StatsService::informAllUidData was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call informAllUidData");
    }

    mUidMap->updateMap(uid, version, app);
    if (DEBUG) ALOGD("StatsService::informAllUidData succeeded");

    return Status::ok();
}

Status StatsService::informOnePackage(const String16& app, int32_t uid, int32_t version) {
    if (DEBUG) ALOGD("StatsService::informOnePackage was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call informOnePackage");
    }
    mUidMap->updateApp(app, uid, version);
    return Status::ok();
}

Status StatsService::informOnePackageRemoved(const String16& app, int32_t uid) {
    if (DEBUG) ALOGD("StatsService::informOnePackageRemoved was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call informOnePackageRemoved");
    }
    mUidMap->removeApp(app, uid);
    return Status::ok();
}

Status StatsService::informAnomalyAlarmFired() {
    if (DEBUG) ALOGD("StatsService::informAnomalyAlarmFired was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call informAnomalyAlarmFired");
    }

    if (DEBUG) ALOGD("StatsService::informAnomalyAlarmFired succeeded");
    // TODO: check through all counters/timers and see if an anomaly has indeed occurred.

    return Status::ok();
}

Status StatsService::informPollAlarmFired() {
    if (DEBUG) ALOGD("StatsService::informPollAlarmFired was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call informPollAlarmFired");
    }

    if (DEBUG) ALOGD("StatsService::informPollAlarmFired succeeded");
    // TODO: determine what services to poll and poll (or ask StatsCompanionService to poll) them.
    String16 output = mStatsPullerManager.pull(StatsPullerManager::KERNEL_WAKELOCKS);
    // TODO: do something useful with the output instead of writing a string to screen.
    ALOGD("%s", String8(output).string());
    ALOGD("%d", int(output.size()));

    return Status::ok();
}

Status StatsService::systemRunning() {
    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call systemRunning");
    }

    // When system_server is up and running, schedule the dropbox task to run.
    ALOGD("StatsService::systemRunning");

    sayHiToStatsCompanion();

    return Status::ok();
}

void StatsService::sayHiToStatsCompanion() {
    // TODO: This method needs to be private. It is temporarily public and unsecured for testing
    // purposes.
    sp<IStatsCompanionService> statsCompanion = getStatsCompanionService();
    if (statsCompanion != nullptr) {
        if (DEBUG) ALOGD("Telling statsCompanion that statsd is ready");
        statsCompanion->statsdReady();
    } else {
        if (DEBUG) ALOGD("Could not access statsCompanion");
    }
}

sp<IStatsCompanionService> StatsService::getStatsCompanionService() {
    sp<IStatsCompanionService> statsCompanion = nullptr;
    // Get statscompanion service from service manager
    const sp<IServiceManager> sm(defaultServiceManager());
    if (sm != nullptr) {
        const String16 name("statscompanion");
        statsCompanion = interface_cast<IStatsCompanionService>(sm->checkService(name));
        if (statsCompanion == nullptr) {
            ALOGW("statscompanion service unavailable!");
            return nullptr;
        }
    }
    return statsCompanion;
}

Status StatsService::statsCompanionReady() {
    if (DEBUG) ALOGD("StatsService::statsCompanionReady was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call statsCompanionReady");
    }

    sp<IStatsCompanionService> statsCompanion = getStatsCompanionService();
    if (statsCompanion == nullptr) {
        return Status::fromExceptionCode(
                Status::EX_NULL_POINTER,
                "statscompanion unavailable despite it contacting statsd!");
    }
    if (DEBUG) ALOGD("StatsService::statsCompanionReady linking to statsCompanion.");
    IInterface::asBinder(statsCompanion)->linkToDeath(new CompanionDeathRecipient(mAnomalyMonitor));
    mAnomalyMonitor->setStatsCompanionService(statsCompanion);

    return Status::ok();
}

void StatsService::Startup() {
    mConfigManager->Startup();
}

void StatsService::OnLogEvent(const LogEvent& event) {
    mProcessor->OnLogEvent(event);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
