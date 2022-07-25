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
#define DEBUG false
#include "Log.h"

#include "IncidentService.h"

#include "FdBuffer.h"
#include "PrivacyFilter.h"
#include "Reporter.h"
#include "incidentd_util.h"
#include "section_list.h"

#include <android/os/IncidentReportArgs.h>
#include <binder/IPCThreadState.h>
#include <binder/IResultReceiver.h>
#include <binder/IServiceManager.h>
#include <binder/IShellCallback.h>
#include <log/log.h>
#include <private/android_filesystem_config.h>
#include <utils/Looper.h>
#include <thread>

#include <unistd.h>

enum {
    WHAT_TAKE_REPORT = 1,
    WHAT_SEND_BROADCASTS = 2
};

#define DEFAULT_DELAY_NS (1000000000LL)

#define DEFAULT_BYTES_SIZE_LIMIT (96 * 1024 * 1024)        // 96MB
#define DEFAULT_REFACTORY_PERIOD_MS (24 * 60 * 60 * 1000)  // 1 Day

// Skip these sections (for dumpstate only)
// Skip logs (1100 - 1108), traces (1200 - 1202), dumpsys (3000 - 3024, 3027 - 3056, 4000 - 4001)
// because they are already in the bug report.
#define SKIPPED_DUMPSTATE_SECTIONS { \
            1100, 1101, 1102, 1103, 1104, 1105, 1106, 1107, 1108, /* Logs */ \
            1200, 1201, 1202, /* Native, hal, java traces */ \
            3000, 3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008, 3009, 3010, 3011, 3012, 3013, \
            3014, 3015, 3016, 3017, 3018, 3019, 3020, 3021, 3022, 3023, 3024, 3027, 3028, 3029, \
            3030, 3031, 3032, 3033, 3034, 3035, 3036, 3037, 3038, 3039, 3040, 3041, 3042, 3043, \
            3044, 3045, 3046, 3047, 3048, 3049, 3050, 3051, 3052, 3053, 3054, 3055, 3056, 4000, \
            4001, /* Dumpsys */ }

namespace android {
namespace os {
namespace incidentd {

String16 const APPROVE_INCIDENT_REPORTS("android.permission.APPROVE_INCIDENT_REPORTS");
String16 const DUMP_PERMISSION("android.permission.DUMP");
String16 const USAGE_STATS_PERMISSION("android.permission.PACKAGE_USAGE_STATS");

static Status checkIncidentPermissions(const IncidentReportArgs& args) {
    uid_t callingUid = IPCThreadState::self()->getCallingUid();
    pid_t callingPid = IPCThreadState::self()->getCallingPid();
    if (callingUid == AID_ROOT || callingUid == AID_SHELL) {
        // Root and shell are ok.
        return Status::ok();
    }

    if (checkCallingPermission(APPROVE_INCIDENT_REPORTS)) {
        // Permission controller (this is a singleton permission that is always granted
        // exactly for PermissionController) is allowed to access incident reports
        // so it can show the user info about what they are approving.
        return Status::ok();
    }

    // checking calling permission.
    if (!checkCallingPermission(DUMP_PERMISSION)) {
        ALOGW("Calling pid %d and uid %d does not have permission: android.permission.DUMP",
              callingPid, callingUid);
        return Status::fromExceptionCode(
                Status::EX_SECURITY,
                "Calling process does not have permission: android.permission.DUMP");
    }
    if (!checkCallingPermission(USAGE_STATS_PERMISSION)) {
        ALOGW("Calling pid %d and uid %d does not have permission: android.permission.USAGE_STATS",
              callingPid, callingUid);
        return Status::fromExceptionCode(
                Status::EX_SECURITY,
                "Calling process does not have permission: android.permission.USAGE_STATS");
    }

    // checking calling request uid permission.
    switch (args.getPrivacyPolicy()) {
        case PRIVACY_POLICY_LOCAL:
            if (callingUid != AID_SHELL && callingUid != AID_ROOT) {
                ALOGW("Calling pid %d and uid %d does not have permission to get local data.",
                      callingPid, callingUid);
                return Status::fromExceptionCode(
                        Status::EX_SECURITY,
                        "Calling process does not have permission to get local data.");
            }
            break;
        case PRIVACY_POLICY_EXPLICIT:
            if (callingUid != AID_SHELL && callingUid != AID_ROOT && callingUid != AID_STATSD &&
                    callingUid != AID_SYSTEM) {
                ALOGW("Calling pid %d and uid %d does not have permission to get explicit data.",
                      callingPid, callingUid);
                return Status::fromExceptionCode(
                        Status::EX_SECURITY,
                        "Calling process does not have permission to get explicit data.");
            }
            break;
    }
    return Status::ok();
}

static string build_uri(const string& pkg, const string& cls, const string& id) {
    return "content://android.os.IncidentManager/pending?pkg="
        + pkg + "&receiver=" + cls + "&r=" + id;
}

// ================================================================================
ReportHandler::ReportHandler(const sp<WorkDirectory>& workDirectory,
                             const sp<Broadcaster>& broadcaster,
                             const sp<Looper>& handlerLooper,
                             const sp<Throttler>& throttler,
                             const vector<BringYourOwnSection*>& registeredSections)
        :mLock(),
         mWorkDirectory(workDirectory),
         mBroadcaster(broadcaster),
         mHandlerLooper(handlerLooper),
         mBacklogDelay(DEFAULT_DELAY_NS),
         mThrottler(throttler),
         mRegisteredSections(registeredSections),
         mBatch(new ReportBatch()) {
}

ReportHandler::~ReportHandler() {
}

void ReportHandler::handleMessage(const Message& message) {
    switch (message.what) {
        case WHAT_TAKE_REPORT:
            take_report();
            break;
        case WHAT_SEND_BROADCASTS:
            send_broadcasts();
            break;
    }
}

void ReportHandler::schedulePersistedReport(const IncidentReportArgs& args) {
    unique_lock<mutex> lock(mLock);
    mBatch->addPersistedReport(args);
    mHandlerLooper->removeMessages(this, WHAT_TAKE_REPORT);
    mHandlerLooper->sendMessage(this, Message(WHAT_TAKE_REPORT));
}

void ReportHandler::scheduleStreamingReport(const IncidentReportArgs& args,
        const sp<IIncidentReportStatusListener>& listener, int streamFd) {
    unique_lock<mutex> lock(mLock);
    mBatch->addStreamingReport(args, listener, streamFd);
    mHandlerLooper->removeMessages(this, WHAT_TAKE_REPORT);
    mHandlerLooper->sendMessage(this, Message(WHAT_TAKE_REPORT));
}

void ReportHandler::scheduleSendBacklog() {
    unique_lock<mutex> lock(mLock);
    mBacklogDelay = DEFAULT_DELAY_NS;
    schedule_send_broadcasts_locked();
}

void ReportHandler::schedule_send_broadcasts_locked() {
    mHandlerLooper->removeMessages(this, WHAT_SEND_BROADCASTS);
    mHandlerLooper->sendMessageDelayed(mBacklogDelay, this, Message(WHAT_SEND_BROADCASTS));
}

void ReportHandler::take_report() {
    // Cycle the batch and throttle.
    sp<ReportBatch> batch;
    {
        unique_lock<mutex> lock(mLock);
        batch = mThrottler->filterBatch(mBatch);
    }

    if (batch->empty()) {
        // Nothing to do.
        return;
    }

    sp<Reporter> reporter = new Reporter(mWorkDirectory, batch, mRegisteredSections);

    // Take the report, which might take a while. More requests might queue
    // up while we're doing this, and we'll handle them in their next batch.
    // TODO: We should further rate-limit the reports to no more than N per time-period.
    // TODO: Move this inside reporter.
    size_t reportByteSize = 0;
    reporter->runReport(&reportByteSize);

    // Tell the throttler how big it was, for the next throttling.
    // TODO: This still isn't ideal. The throttler really should just track the
    // persisted reqeusts, but changing Reporter::runReport() to track that individually
    // will be a big change.
    if (batch->hasPersistedReports()) {
        mThrottler->addReportSize(reportByteSize);
    }

    // Kick off the next steps, one of which is to send any new or otherwise remaining
    // approvals, and one of which is to send any new or remaining broadcasts.
    {
        unique_lock<mutex> lock(mLock);
        schedule_send_broadcasts_locked();
    }
}

void ReportHandler::send_broadcasts() {
    Broadcaster::broadcast_status_t result = mBroadcaster->sendBroadcasts();
    if (result == Broadcaster::BROADCASTS_FINISHED) {
        // We're done.
        unique_lock<mutex> lock(mLock);
        mBacklogDelay = DEFAULT_DELAY_NS;
    } else if (result == Broadcaster::BROADCASTS_REPEAT) {
        // It worked, but there are more.
        unique_lock<mutex> lock(mLock);
        mBacklogDelay = DEFAULT_DELAY_NS;
        schedule_send_broadcasts_locked();
    } else if (result == Broadcaster::BROADCASTS_BACKOFF) {
        // There was a failure. Exponential backoff.
        unique_lock<mutex> lock(mLock);
        mBacklogDelay *= 2;
        ALOGI("Error sending to dropbox. Trying again in %lld minutes",
              (mBacklogDelay / (1000000000LL * 60)));
        schedule_send_broadcasts_locked();
    }
}

// ================================================================================
IncidentService::IncidentService(const sp<Looper>& handlerLooper) {
    mThrottler = new Throttler(DEFAULT_BYTES_SIZE_LIMIT, DEFAULT_REFACTORY_PERIOD_MS);
    mWorkDirectory = new WorkDirectory();
    mBroadcaster = new Broadcaster(mWorkDirectory);
    mHandler = new ReportHandler(mWorkDirectory, mBroadcaster, handlerLooper,
            mThrottler, mRegisteredSections);
    mBroadcaster->setHandler(mHandler);
}

IncidentService::~IncidentService() {}

Status IncidentService::reportIncident(const IncidentReportArgs& args) {
    IncidentReportArgs argsCopy(args);

    // Validate that the privacy policy is one of the real ones.
    // If it isn't, clamp it to the next more restrictive real one.
    argsCopy.setPrivacyPolicy(cleanup_privacy_policy(args.getPrivacyPolicy()));

    // TODO: Check that the broadcast recevier has the proper permissions
    // TODO: Maybe we should consider relaxing the permissions if it's going to
    // dropbox, but definitely not if it's going to the broadcaster.
    Status status = checkIncidentPermissions(args);
    if (!status.isOk()) {
        return status;
    }

    // If they asked for the LOCAL privacy policy, give them EXPLICT.  LOCAL has to
    // be streamed. (This only applies to shell/root, because everyone else would have
    // been rejected by checkIncidentPermissions()).
    if (argsCopy.getPrivacyPolicy() < PRIVACY_POLICY_EXPLICIT) {
        ALOGI("Demoting privacy policy to EXPLICT for persisted report.");
        argsCopy.setPrivacyPolicy(PRIVACY_POLICY_EXPLICIT);
    }

    // If they didn't specify a component, use dropbox.
    if (argsCopy.receiverPkg().length() == 0 && argsCopy.receiverCls().length() == 0) {
        argsCopy.setReceiverPkg(DROPBOX_SENTINEL.getPackageName());
        argsCopy.setReceiverCls(DROPBOX_SENTINEL.getClassName());
    }

    mHandler->schedulePersistedReport(argsCopy);

    return Status::ok();
}

Status IncidentService::reportIncidentToStream(const IncidentReportArgs& args,
                                               const sp<IIncidentReportStatusListener>& listener,
                                               unique_fd stream) {
    IncidentReportArgs argsCopy(args);

    // Streaming reports can not also be broadcast.
    argsCopy.setReceiverPkg("");
    argsCopy.setReceiverCls("");

    // Validate that the privacy policy is one of the real ones.
    // If it isn't, clamp it to the next more restrictive real one.
    argsCopy.setPrivacyPolicy(cleanup_privacy_policy(args.getPrivacyPolicy()));

    Status status = checkIncidentPermissions(argsCopy);
    if (!status.isOk()) {
        return status;
    }

    // The ReportRequest takes ownership of the fd, so we need to dup it.
    int fd = dup(stream.get());
    if (fd < 0) {
        return Status::fromStatusT(-errno);
    }

    mHandler->scheduleStreamingReport(argsCopy, listener, fd);

    return Status::ok();
}

Status IncidentService::reportIncidentToDumpstate(unique_fd stream,
        const sp<IIncidentReportStatusListener>& listener) {
    uid_t caller = IPCThreadState::self()->getCallingUid();
    if (caller != AID_ROOT && caller != AID_SHELL) {
        ALOGW("Calling uid %d does not have permission: only ROOT or SHELL allowed", caller);
        return Status::fromExceptionCode(Status::EX_SECURITY, "Only ROOT or SHELL allowed");
    }

    ALOGD("Stream incident report to dumpstate");
    IncidentReportArgs incidentArgs;
    // Privacy policy for dumpstate incident reports is always EXPLICIT.
    incidentArgs.setPrivacyPolicy(PRIVACY_POLICY_EXPLICIT);

    int skipped[] = SKIPPED_DUMPSTATE_SECTIONS;
    for (const Section** section = SECTION_LIST; *section; section++) {
        const int id = (*section)->id;
        if (std::find(std::begin(skipped), std::end(skipped), id) == std::end(skipped)
                && !section_requires_specific_mention(id)) {
            incidentArgs.addSection(id);
        }
    }
    for (const Section* section : mRegisteredSections) {
        if (!section_requires_specific_mention(section->id)) {
            incidentArgs.addSection(section->id);
        }
    }

    // The ReportRequest takes ownership of the fd, so we need to dup it.
    int fd = dup(stream.get());
    if (fd < 0) {
        return Status::fromStatusT(-errno);
    }

    mHandler->scheduleStreamingReport(incidentArgs, listener, fd);

    return Status::ok();
}

Status IncidentService::registerSection(const int id, const String16& name16,
        const sp<IIncidentDumpCallback>& callback) {
    const String8 name = String8(name16);
    const uid_t callingUid = IPCThreadState::self()->getCallingUid();
    ALOGI("Uid %d registers section %d '%s'", callingUid, id, name.c_str());
    if (callback == nullptr) {
        return Status::fromExceptionCode(Status::EX_NULL_POINTER);
    }
    for (int i = 0; i < mRegisteredSections.size(); i++) {
        if (mRegisteredSections.at(i)->id == id) {
            if (mRegisteredSections.at(i)->uid != callingUid) {
                ALOGW("Error registering section %d: calling uid does not match", id);
                return Status::fromExceptionCode(Status::EX_SECURITY);
            }
            mRegisteredSections.at(i) = new BringYourOwnSection(id, name.c_str(), callingUid, callback);
            return Status::ok();
        }
    }
    mRegisteredSections.push_back(new BringYourOwnSection(id, name.c_str(), callingUid, callback));
    return Status::ok();
}

Status IncidentService::unregisterSection(const int id) {
    uid_t callingUid = IPCThreadState::self()->getCallingUid();
    ALOGI("Uid %d unregisters section %d", callingUid, id);

    for (auto it = mRegisteredSections.begin(); it != mRegisteredSections.end(); it++) {
        if ((*it)->id == id) {
            if ((*it)->uid != callingUid) {
                ALOGW("Error unregistering section %d: calling uid does not match", id);
                return Status::fromExceptionCode(Status::EX_SECURITY);
            }
            mRegisteredSections.erase(it);
            return Status::ok();
        }
    }
    ALOGW("Section %d not found", id);
    return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE);
}

Status IncidentService::systemRunning() {
    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call systemRunning");
    }

    // When system_server is up and running, schedule the dropbox task to run.
    mBroadcaster->reset();
    mHandler->scheduleSendBacklog();

    return Status::ok();
}

Status IncidentService::getIncidentReportList(const String16& pkg16, const String16& cls16,
            vector<String16>* result) {
    status_t err;
    const string pkg(String8(pkg16).string());
    const string cls(String8(cls16).string());

    // List the reports
    vector<sp<ReportFile>> all;
    err = mWorkDirectory->getReports(&all, 0);
    if (err != NO_ERROR) {
        return Status::fromStatusT(err);
    }

    // Find the ones that match pkg and cls.
    for (sp<ReportFile>& file: all) {
        err = file->loadEnvelope();
        if (err != NO_ERROR) {
            continue;
        }
        const ReportFileProto& envelope = file->getEnvelope();
        size_t reportCount = envelope.report_size();
        for (int reportIndex = 0; reportIndex < reportCount; reportIndex++) {
            const ReportFileProto_Report& report = envelope.report(reportIndex);
            if (pkg == report.pkg() && cls == report.cls()) {
                result->push_back(String16(build_uri(pkg, cls, file->getId()).c_str()));
                break;
            }
        }
    }

    return Status::ok();
}

Status IncidentService::getIncidentReport(const String16& pkg16, const String16& cls16,
            const String16& id16, IncidentManager::IncidentReport* result) {
    status_t err;

    const string pkg(String8(pkg16).string());
    const string cls(String8(cls16).string());
    const string id(String8(id16).string());

    IncidentReportArgs args;
    sp<ReportFile> file = mWorkDirectory->getReport(pkg, cls, id, &args);
    if (file != nullptr) {
        // Create pipe
        int fds[2];
        if (pipe(fds) != 0) {
            ALOGW("Error opening pipe to filter incident report: %s",
                  file->getDataFileName().c_str());
            return Status::ok();
        }
        result->setTimestampNs(file->getTimestampNs());
        result->setPrivacyPolicy(file->getEnvelope().privacy_policy());
        result->takeFileDescriptor(fds[0]);
        int writeFd = fds[1];
        // spawn a thread to write the data. Release the writeFd ownership to the thread.
        thread th([file, writeFd, args]() { file->startFilteringData(writeFd, args); });

        th.detach();
    }

    return Status::ok();
}

Status IncidentService::deleteIncidentReports(const String16& pkg16, const String16& cls16,
            const String16& id16) {
    const string pkg(String8(pkg16).string());
    const string cls(String8(cls16).string());
    const string id(String8(id16).string());

    sp<ReportFile> file = mWorkDirectory->getReport(pkg, cls, id, nullptr);
    if (file != nullptr) {
        mWorkDirectory->commit(file, pkg, cls);
    }
    mBroadcaster->clearBroadcasts(pkg, cls, id);

    return Status::ok();
}

Status IncidentService::deleteAllIncidentReports(const String16& pkg16) {
    const string pkg(String8(pkg16).string());

    mWorkDirectory->commitAll(pkg);
    mBroadcaster->clearPackageBroadcasts(pkg);

    return Status::ok();
}

/**
 * Implement our own because the default binder implementation isn't
 * properly handling SHELL_COMMAND_TRANSACTION.
 */
status_t IncidentService::onTransact(uint32_t code, const Parcel& data, Parcel* reply,
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
        } break;
        default: { return BnIncidentManager::onTransact(code, data, reply, flags); }
    }
}

status_t IncidentService::command(FILE* in, FILE* out, FILE* err, Vector<String8>& args) {
    const int argCount = args.size();

    if (argCount >= 1) {
        if (!args[0].compare(String8("privacy"))) {
            return cmd_privacy(in, out, err, args);
        }
        if (!args[0].compare(String8("throttler"))) {
            mThrottler->dump(out);
            return NO_ERROR;
        }
        if (!args[0].compare(String8("section"))) {
            if (argCount == 1) {
                fprintf(out, "Not enough arguments for section\n");
                return NO_ERROR;
            }
            int id = atoi(args[1]);
            int idx = 0;
            while (SECTION_LIST[idx] != NULL) {
                const Section* section = SECTION_LIST[idx];
                if (section->id == id) {
                    fprintf(out, "Section[%d] %s\n", id, section->name.string());
                    break;
                }
                idx++;
            }
            return NO_ERROR;
        }
    }
    return cmd_help(out);
}

status_t IncidentService::cmd_help(FILE* out) {
    fprintf(out, "usage: adb shell cmd incident privacy print <section_id>\n");
    fprintf(out, "usage: adb shell cmd incident privacy parse <section_id> < proto.txt\n");
    fprintf(out, "    Prints/parses for the section id.\n\n");
    fprintf(out, "usage: adb shell cmd incident section <section_id>\n");
    fprintf(out, "    Prints section id and its name.\n\n");
    fprintf(out, "usage: adb shell cmd incident throttler\n");
    fprintf(out, "    Prints the current throttler state\n");
    return NO_ERROR;
}

static void printPrivacy(const Privacy* p, FILE* out, String8 indent) {
    if (p == NULL) return;
    fprintf(out, "%sid:%d, type:%d, dest:%d\n", indent.string(), p->field_id, p->type, p->policy);
    if (p->children == NULL) return;
    for (int i = 0; p->children[i] != NULL; i++) {  // NULL-terminated.
        printPrivacy(p->children[i], out, indent + "  ");
    }
}

status_t IncidentService::cmd_privacy(FILE* in, FILE* out, FILE* err, Vector<String8>& args) {
    (void)in;

    const int argCount = args.size();
    if (argCount >= 3) {
        String8 opt = args[1];
        int sectionId = atoi(args[2].string());

        const Privacy* p = get_privacy_of_section(sectionId);
        if (p == NULL) {
            fprintf(err, "Can't find section id %d\n", sectionId);
            return NO_ERROR;
        }
        fprintf(err, "Get privacy for %d\n", sectionId);
        if (opt == "print") {
            printPrivacy(p, out, String8(""));
        } else if (opt == "parse") {
            /*
            FdBuffer buf;
            status_t error = buf.read(fileno(in), 60000);
            if (error != NO_ERROR) {
                fprintf(err, "Error reading from stdin\n");
                return error;
            }
            fprintf(err, "Read %zu bytes\n", buf.size());
            PrivacyFilter pBuf(p, buf.data());

            PrivacySpec spec = PrivacySpec::new_spec(argCount > 3 ? atoi(args[3]) : -1);
            error = pBuf.strip(spec);
            if (error != NO_ERROR) {
                fprintf(err, "Error strip pii fields with spec %d\n", spec.policy);
                return error;
            }
            return pBuf.flush(fileno(out));
            */
            return -1;
        }
    } else {
        return cmd_help(out);
    }
    return NO_ERROR;
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
