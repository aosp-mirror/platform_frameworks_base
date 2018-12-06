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
#include "PrivacyBuffer.h"
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

#include <unistd.h>

enum { WHAT_RUN_REPORT = 1, WHAT_SEND_BACKLOG_TO_DROPBOX = 2 };

#define DEFAULT_BACKLOG_DELAY_NS (1000000000LL)

#define DEFAULT_BYTES_SIZE_LIMIT (20 * 1024 * 1024)        // 20MB
#define DEFAULT_REFACTORY_PERIOD_MS (24 * 60 * 60 * 1000)  // 1 Day

// Skip these sections for dumpstate only. Dumpstate allows 10s max for each service to dump.
// Skip logs (1100 - 1108) and traces (1200 - 1202) because they are already in the bug report.
// Skip 3018 because it takes too long.
#define SKIPPED_SECTIONS { 1100, 1101, 1102, 1103, 1104, 1105, 1106, 1107, 1108, /* Logs */ \
                           1200, 1201, 1202, /* Native, hal, java traces */ \
                           3018  /* "meminfo -a --proto" */ }

namespace android {
namespace os {
namespace incidentd {

String16 const DUMP_PERMISSION("android.permission.DUMP");
String16 const USAGE_STATS_PERMISSION("android.permission.PACKAGE_USAGE_STATS");

static Status checkIncidentPermissions(const IncidentReportArgs& args) {
    uid_t callingUid = IPCThreadState::self()->getCallingUid();
    pid_t callingPid = IPCThreadState::self()->getCallingPid();
    if (callingUid == AID_ROOT || callingUid == AID_SHELL) {
        // root doesn't have permission.DUMP if don't do this!
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
    switch (args.dest()) {
        case DEST_LOCAL:
            if (callingUid != AID_SHELL && callingUid != AID_ROOT) {
                ALOGW("Calling pid %d and uid %d does not have permission to get local data.",
                      callingPid, callingUid);
                return Status::fromExceptionCode(
                        Status::EX_SECURITY,
                        "Calling process does not have permission to get local data.");
            }
            break;
        case DEST_EXPLICIT:
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

// ================================================================================
ReportRequestQueue::ReportRequestQueue() {}

ReportRequestQueue::~ReportRequestQueue() {}

void ReportRequestQueue::addRequest(const sp<ReportRequest>& request) {
    unique_lock<mutex> lock(mLock);
    mQueue.push_back(request);
}

sp<ReportRequest> ReportRequestQueue::getNextRequest() {
    unique_lock<mutex> lock(mLock);
    if (mQueue.empty()) {
        return NULL;
    } else {
        sp<ReportRequest> front(mQueue.front());
        mQueue.pop_front();
        return front;
    }
}

// ================================================================================
ReportHandler::ReportHandler(const sp<Looper>& handlerLooper, const sp<ReportRequestQueue>& queue,
                             const sp<Throttler>& throttler)
    : mBacklogDelay(DEFAULT_BACKLOG_DELAY_NS),
      mHandlerLooper(handlerLooper),
      mQueue(queue),
      mThrottler(throttler) {}

ReportHandler::~ReportHandler() {}

void ReportHandler::handleMessage(const Message& message) {
    switch (message.what) {
        case WHAT_RUN_REPORT:
            run_report();
            break;
        case WHAT_SEND_BACKLOG_TO_DROPBOX:
            send_backlog_to_dropbox();
            break;
    }
}

void ReportHandler::scheduleRunReport(const sp<ReportRequest>& request) {
    mQueue->addRequest(request);
    mHandlerLooper->removeMessages(this, WHAT_RUN_REPORT);
    mHandlerLooper->sendMessage(this, Message(WHAT_RUN_REPORT));
}

void ReportHandler::scheduleSendBacklogToDropbox() {
    unique_lock<mutex> lock(mLock);
    mBacklogDelay = DEFAULT_BACKLOG_DELAY_NS;
    schedule_send_backlog_to_dropbox_locked();
}

void ReportHandler::schedule_send_backlog_to_dropbox_locked() {
    mHandlerLooper->removeMessages(this, WHAT_SEND_BACKLOG_TO_DROPBOX);
    mHandlerLooper->sendMessageDelayed(mBacklogDelay, this, Message(WHAT_SEND_BACKLOG_TO_DROPBOX));
}

void ReportHandler::run_report() {
    sp<Reporter> reporter = new Reporter();

    // Merge all of the requests into one that has all of the
    // requested fields.
    while (true) {
        sp<ReportRequest> request = mQueue->getNextRequest();
        if (request == NULL) {
            break;
        }
        reporter->batch.add(request);
    }

    if (mThrottler->shouldThrottle()) {
        ALOGW("RunReport got throttled.");
        return;
    }

    // Take the report, which might take a while. More requests might queue
    // up while we're doing this, and we'll handle them in their next batch.
    // TODO: We should further rate-limit the reports to no more than N per time-period.
    size_t reportByteSize = 0;
    Reporter::run_report_status_t reportStatus = reporter->runReport(&reportByteSize);
    mThrottler->addReportSize(reportByteSize);
    if (reportStatus == Reporter::REPORT_NEEDS_DROPBOX) {
        unique_lock<mutex> lock(mLock);
        schedule_send_backlog_to_dropbox_locked();
    }
}

void ReportHandler::send_backlog_to_dropbox() {
    if (Reporter::upload_backlog() == Reporter::REPORT_NEEDS_DROPBOX) {
        // There was a failure. Exponential backoff.
        unique_lock<mutex> lock(mLock);
        mBacklogDelay *= 2;
        ALOGI("Error sending to dropbox. Trying again in %lld minutes",
              (mBacklogDelay / (1000000000LL * 60)));
        schedule_send_backlog_to_dropbox_locked();
    } else {
        mBacklogDelay = DEFAULT_BACKLOG_DELAY_NS;
    }
}

// ================================================================================
IncidentService::IncidentService(const sp<Looper>& handlerLooper)
    : mQueue(new ReportRequestQueue()),
      mThrottler(new Throttler(DEFAULT_BYTES_SIZE_LIMIT, DEFAULT_REFACTORY_PERIOD_MS)) {
    mHandler = new ReportHandler(handlerLooper, mQueue, mThrottler);
}

IncidentService::~IncidentService() {}

Status IncidentService::reportIncident(const IncidentReportArgs& args) {
    ALOGI("reportIncident");

    Status status = checkIncidentPermissions(args);
    if (!status.isOk()) {
        return status;
    }

    mHandler->scheduleRunReport(new ReportRequest(args, NULL, -1));

    return Status::ok();
}

Status IncidentService::reportIncidentToStream(const IncidentReportArgs& args,
                                               const sp<IIncidentReportStatusListener>& listener,
                                               const unique_fd& stream) {
    ALOGI("reportIncidentToStream");

    Status status = checkIncidentPermissions(args);
    if (!status.isOk()) {
        return status;
    }

    int fd = dup(stream.get());
    if (fd < 0) {
        return Status::fromStatusT(-errno);
    }

    mHandler->scheduleRunReport(new ReportRequest(args, listener, fd));

    return Status::ok();
}

Status IncidentService::systemRunning() {
    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call systemRunning");
    }

    // When system_server is up and running, schedule the dropbox task to run.
    mHandler->scheduleSendBacklogToDropbox();

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
    fprintf(out, "%sid:%d, type:%d, dest:%d\n", indent.string(), p->field_id, p->type, p->dest);
    if (p->children == NULL) return;
    for (int i = 0; p->children[i] != NULL; i++) {  // NULL-terminated.
        printPrivacy(p->children[i], out, indent + "  ");
    }
}

status_t IncidentService::cmd_privacy(FILE* in, FILE* out, FILE* err, Vector<String8>& args) {
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
            FdBuffer buf;
            status_t error = buf.read(fileno(in), 60000);
            if (error != NO_ERROR) {
                fprintf(err, "Error reading from stdin\n");
                return error;
            }
            fprintf(err, "Read %zu bytes\n", buf.size());
            PrivacyBuffer pBuf(p, buf.data());

            PrivacySpec spec = PrivacySpec::new_spec(argCount > 3 ? atoi(args[3]) : -1);
            error = pBuf.strip(spec);
            if (error != NO_ERROR) {
                fprintf(err, "Error strip pii fields with spec %d\n", spec.dest);
                return error;
            }
            return pBuf.flush(fileno(out));
        }
    } else {
        return cmd_help(out);
    }
    return NO_ERROR;
}

status_t IncidentService::dump(int fd, const Vector<String16>& args) {
    if (std::find(args.begin(), args.end(), String16("--proto")) == args.end()) {
        ALOGD("Skip dumping incident. Only proto format is supported.");
        dprintf(fd, "Incident dump only supports proto version.\n");
        return NO_ERROR;
    }

    ALOGD("Dump incident proto");
    IncidentReportArgs incidentArgs;
    incidentArgs.setDest(DEST_EXPLICIT);
    int skipped[] = SKIPPED_SECTIONS;
    for (const Section** section = SECTION_LIST; *section; section++) {
        const int id = (*section)->id;
        if (std::find(std::begin(skipped), std::end(skipped), id) == std::end(skipped)) {
            incidentArgs.addSection(id);
        }
    }

    if (!checkIncidentPermissions(incidentArgs).isOk()) {
        return PERMISSION_DENIED;
    }

    int fd1 = dup(fd);
    if (fd1 < 0) {
        return -errno;
    }

    mHandler->scheduleRunReport(new ReportRequest(incidentArgs, NULL, fd1));

    return NO_ERROR;
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
