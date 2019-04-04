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

#include "Log.h"

#include "Broadcaster.h"

#include "IncidentService.h"

#include <android/os/DropBoxManager.h>
#include <binder/IServiceManager.h>
#include <thread>

namespace android {
namespace os {
namespace incidentd {

using android::os::IIncidentCompanion;
using binder::Status;

// ============================================================
Broadcaster::ConsentListener::ConsentListener(const sp<Broadcaster>& broadcaster,
        const ReportId& reportId)
    :mBroadcaster(broadcaster),
     mId(reportId) {
}

Broadcaster::ConsentListener::~ConsentListener() {
}

Status Broadcaster::ConsentListener::onReportApproved() {
    mBroadcaster->report_approved(mId);
    return Status::ok();
}

Status Broadcaster::ConsentListener::onReportDenied() {
    mBroadcaster->report_denied(mId);
    return Status::ok();
}

// ============================================================
Broadcaster::ReportId::ReportId()
    :id(),
     pkg(),
     cls() {
}

Broadcaster::ReportId::ReportId(const ReportId& that)
    :id(that.id),
     pkg(that.pkg),
     cls(that.cls) {
}

Broadcaster::ReportId::ReportId(const string& i, const string& p, const string& c)
    :id(i),
     pkg(p),
     cls(c) {
}

Broadcaster::ReportId::~ReportId() {
}

bool Broadcaster::ReportId::operator<(const ReportId& that) const {
    if (id < that.id) {
        return true;
    }
    if (id > that.id) {
        return false;
    }
    if (pkg < that.pkg) {
        return true;
    }
    if (pkg > that.pkg) {
        return false;
    }
    if (cls < that.cls) {
        return true;
    }
    return false;
}

// ============================================================
Broadcaster::ReportStatus::ReportStatus()
    :approval_sent(false),
     ready_sent(false),
     listener(nullptr) {
}

Broadcaster::ReportStatus::ReportStatus(const ReportStatus& that)
    :approval_sent(that.approval_sent),
     ready_sent(that.ready_sent),
     listener(that.listener) {
}

Broadcaster::ReportStatus::~ReportStatus() {
}

// ============================================================
Broadcaster::Broadcaster(const sp<WorkDirectory>& workDirectory)
        :mReportHandler(),
         mWorkDirectory(workDirectory) {
}

void Broadcaster::setHandler(const sp<ReportHandler>& handler) {
    mReportHandler = handler;
}

void Broadcaster::reset() {
    unique_lock<mutex> lock(mLock);
    mLastSent = 0;
    mHistory.clear();
    // Could cancel the listeners, but this happens when
    // the system process crashes, so don't bother.
}

void Broadcaster::clearBroadcasts(const string& pkg, const string& cls, const string& id) {
    unique_lock<mutex> lock(mLock);

    map<ReportId,ReportStatus>::const_iterator found = mHistory.find(ReportId(id, pkg, cls));
    if (found != mHistory.end()) {
        if (found->second.listener != nullptr) {
            sp<IIncidentCompanion> ics = get_incident_companion();
            if (ics != nullptr) {
                ics->cancelAuthorization(found->second.listener);
            }
        }
        mHistory.erase(found);
    }
}

void Broadcaster::clearPackageBroadcasts(const string& pkg) {
    unique_lock<mutex> lock(mLock);

    map<ReportId,ReportStatus>::iterator it = mHistory.begin();
    while (it != mHistory.end()) {
        if (it->first.pkg == pkg) {
            if (it->second.listener != nullptr) {
                sp<IIncidentCompanion> ics = get_incident_companion();
                if (ics != nullptr) {
                    ics->cancelAuthorization(it->second.listener);
                }
            }
            it = mHistory.erase(it);
        } else {
            it++;
        }
    }
}

Broadcaster::broadcast_status_t Broadcaster::sendBroadcasts() {
    int err;
    int64_t lastSent = get_last_sent();

    vector<sp<ReportFile>> files;
    mWorkDirectory->getReports(&files, 0); //lastSent);

    // Don't send multiple broadcasts to the same receiver.
    set<ReportId> reportReadyBroadcasts;

    for (const sp<ReportFile>& file: files) {
        err = file->loadEnvelope();
        if (err != NO_ERROR) {
            ALOGW("Error (%s) loading envelope from %s", strerror(-err),
                    file->getEnvelopeFileName().c_str());
            continue;
        }

        const ReportFileProto& envelope = file->getEnvelope();

        if (!envelope.completed()) {
            ALOGI("Incident report not completed skipping it: %s",
                    file->getEnvelopeFileName().c_str());
            continue;
        }

        // When one of the broadcast functions in this loop fails, it's almost
        // certainly because the system process is crashing or has crashed.  Rather
        // than continuing to pound on the system process and potentially make things
        // worse, we bail right away, return BROADCASTS_BACKOFF, and we will try
        // again later.  In the meantime, if the system process did crash, it might
        // clear out mHistory, which means we'll be back here again to send the
        // backlog.
        size_t reportCount = envelope.report_size();
        bool hasApprovalPending = false;
        for (int reportIndex = 0; reportIndex < reportCount; reportIndex++) {

            const ReportFileProto_Report& report = envelope.report(reportIndex);
            status_t err;
            if (report.privacy_policy() == PRIVACY_POLICY_AUTOMATIC || report.share_approved()) {
                // It's privacy policy is AUTO, or it's been approved,
                // so send the actual broadcast.
                if (!was_ready_sent(file->getId(), report.pkg(), report.cls())) {
                    if (report.pkg() == DROPBOX_SENTINEL.getPackageName()
                            && report.cls() == DROPBOX_SENTINEL.getClassName()) {
                        IncidentReportArgs args;
                        get_args_from_report(&args, report);
                        err = send_to_dropbox(file, args);
                        if (err != NO_ERROR) {
                            return BROADCASTS_BACKOFF;
                        }
                    } else {
                        reportReadyBroadcasts.insert(ReportId(file->getId(), report.pkg(),
                                    report.cls()));
                    }
                }
            } else {
                // It's not approved yet, so send the approval.
                if (!was_approval_sent(file->getId(), report.pkg(), report.cls())) {
                    err = send_approval_broadcasts(file->getId(), report.pkg(), report.cls());
                    if (err != NO_ERROR) {
                        return BROADCASTS_BACKOFF;
                    }
                    hasApprovalPending = true;
                }
            }
        }

        lastSent = file->getTimestampNs();
        if (!hasApprovalPending) {
            set_last_sent(lastSent);
        }
    }

    for (const ReportId& report: reportReadyBroadcasts) {
        err = send_report_ready_broadcasts(report.id, report.pkg, report.cls);
        if (err != NO_ERROR) {
            return BROADCASTS_BACKOFF;
        }
    }

    return mWorkDirectory->hasMore(lastSent) ? BROADCASTS_REPEAT : BROADCASTS_FINISHED;
}

void Broadcaster::set_last_sent(int64_t timestamp) {
    unique_lock<mutex> lock(mLock);
    mLastSent = timestamp;
}

int64_t Broadcaster::get_last_sent() {
    unique_lock<mutex> lock(mLock);
    return mLastSent;
}

/*
void Broadcaster::printReportStatuses() const {
    ALOGD("mHistory {");
    for (map<ReportId,ReportStatus>::const_iterator it = mHistory.begin();
            it != mHistory.end(); it++) {
        ALOGD("   [%s %s] --> [%d %d]", it->first.id.c_str(), it->first.pkg.c_str(),
                it->second.approval_sent, it->second.ready_sent);
    }
    ALOGD("}");
}
*/

bool Broadcaster::was_approval_sent(const string& id, const string& pkg, const string& cls) {
    unique_lock<mutex> lock(mLock);
    map<ReportId,ReportStatus>::const_iterator found = mHistory.find(ReportId(id, pkg, cls));
    if (found != mHistory.end()) {
        return found->second.approval_sent;
    }
    return false;
}

void Broadcaster::set_approval_sent(const string& id, const string& pkg, const string& cls,
        const sp<ConsentListener>& listener) {
    unique_lock<mutex> lock(mLock);
    ReportStatus& reportStatus = mHistory[ReportId(id, pkg, cls)];
    reportStatus.approval_sent = true;
    reportStatus.listener = listener;
}

bool Broadcaster::was_ready_sent(const string& id, const string& pkg, const string& cls) {
    unique_lock<mutex> lock(mLock);
    map<ReportId,ReportStatus>::const_iterator found = mHistory.find(ReportId(id, pkg, cls));
    if (found != mHistory.end()) {
        return found->second.ready_sent;
    }
    return false;
}

void Broadcaster::set_ready_sent(const string& id, const string& pkg, const string& cls) {
    unique_lock<mutex> lock(mLock);
    mHistory[ReportId(id, pkg, cls)].ready_sent = true;
}

status_t Broadcaster::send_approval_broadcasts(const string& id, const string& pkg,
        const string& cls) {
    sp<IIncidentCompanion> ics = get_incident_companion();
    if (ics == nullptr) {
        return NAME_NOT_FOUND;
    }

    sp<ConsentListener> listener = new ConsentListener(this, ReportId(id, pkg, cls));

    ALOGI("send_approval_broadcasts for %s %s/%s", id.c_str(), pkg.c_str(), cls.c_str());

    Status status = ics->authorizeReport(0, String16(pkg.c_str()),
            String16(cls.c_str()), String16(id.c_str()), 0, listener);

    if (!status.isOk()) {
        // authorizeReport is oneway, so any error is a transaction error.
        return status.transactionError();
    }

    set_approval_sent(id, pkg, cls, listener);

    return NO_ERROR;
}

void Broadcaster::report_approved(const ReportId& reportId) {
    status_t err;

    // Kick off broadcaster to do send the ready broadcasts.
    ALOGI("The user approved the report, so kicking off another broadcast pass. %s %s/%s",
            reportId.id.c_str(), reportId.pkg.c_str(), reportId.cls.c_str());
    sp<ReportFile> file = mWorkDirectory->getReport(reportId.pkg, reportId.cls, reportId.id,
            nullptr);
    if (file != nullptr) {
        err = file->loadEnvelope();
        if (err != NO_ERROR) {
            return;
        }

        err = file->markApproved(reportId.pkg, reportId.cls);
        if (err != NO_ERROR) {
            ALOGI("Couldn't find report that was just approved: %s %s/%s",
                    reportId.id.c_str(), reportId.pkg.c_str(), reportId.cls.c_str());
            return;
        }

        file->saveEnvelope();
        if (err != NO_ERROR) {
            return;
        }
    }
    mReportHandler->scheduleSendBacklog();
}

void Broadcaster::report_denied(const ReportId& reportId) {
    // The user didn't approve the report, so remove it from the WorkDirectory.
    ALOGI("The user denied the report, so deleting it. %s %s/%s",
            reportId.id.c_str(), reportId.pkg.c_str(), reportId.cls.c_str());
    sp<ReportFile> file = mWorkDirectory->getReport(reportId.pkg, reportId.cls, reportId.id,
            nullptr);
    if (file != nullptr) {
        mWorkDirectory->commit(file, reportId.pkg, reportId.cls);
    }
}

status_t Broadcaster::send_report_ready_broadcasts(const string& id, const string& pkg,
        const string& cls) {
    sp<IIncidentCompanion> ics = get_incident_companion();
    if (ics == nullptr) {
        return NAME_NOT_FOUND;
    }

    ALOGI("send_report_ready_broadcasts for %s %s/%s", id.c_str(), pkg.c_str(), cls.c_str());

    Status status = ics->sendReportReadyBroadcast(String16(pkg.c_str()), String16(cls.c_str()));

    if (!status.isOk()) {
        // sendReportReadyBroadcast is oneway, so any error is a transaction error.
        return status.transactionError();
    }

    set_ready_sent(id, pkg, cls);

    return NO_ERROR;
}

status_t Broadcaster::send_to_dropbox(const sp<ReportFile>& file,
        const IncidentReportArgs& args) {
    status_t err;

    sp<DropBoxManager> dropbox = new DropBoxManager();
    if (dropbox == nullptr) {
        ALOGW("Can't reach dropbox now, so we won't be able to write the incident report to there");
        return NO_ERROR;
    }

    int fds[2];
    if (pipe(fds) != 0) {
        ALOGW("Error opening pipe to filter incident report: %s", file->getDataFileName().c_str());
        return NO_ERROR;
    }

    int readFd = fds[0];
    int writeFd = fds[1];

    // spawn a thread to write the data. Release the writeFd ownership to the thread.
    thread th([file, writeFd, args]() { file->startFilteringData(writeFd, args); });

    th.detach();

    // Takes ownership of readFd.
    Status status = dropbox->addFile(String16("incident"), readFd, 0);
    if (!status.isOk()) {
        // TODO: This may or may not leak the readFd, depending on where it failed.
        // Not sure how to fix this given the dropbox API.
        ALOGW("Error sending incident report to dropbox.");
        return -errno;
    }

    // On successful write, tell the working directory that this file is done.
    mWorkDirectory->commit(file, DROPBOX_SENTINEL.getPackageName(),
            DROPBOX_SENTINEL.getClassName());

    // Don't need to call set_ready_sent, because we just removed it from the ReportFile,
    // so we'll never hear about it again.

    return NO_ERROR;
}

sp<IIncidentCompanion> Broadcaster::get_incident_companion() {
    sp<IBinder> binder = defaultServiceManager()->getService(String16("incidentcompanion"));
    if (binder == nullptr) {
        ALOGI("Can not find IIncidentCompanion service to send broadcast. Will try again later.");
        return nullptr;
    }

    sp<IIncidentCompanion> ics = interface_cast<IIncidentCompanion>(binder);
    if (ics == nullptr) {
        ALOGI("The incidentcompanion service is not an IIncidentCompanion. Will try again later.");
        return nullptr;
    }

    return ics;
}

}  // namespace incidentd
}  // namespace os
}  // namespace android


