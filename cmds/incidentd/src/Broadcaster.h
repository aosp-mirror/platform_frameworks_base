/*
 * Copyright (C) 2019 The Android Open Source Project
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
#pragma once

#include "WorkDirectory.h"

#include <android/os/BnIncidentAuthListener.h>
#include <android/os/IIncidentCompanion.h>
#include <frameworks/base/cmds/incidentd/src/report_file.pb.h>

namespace android {
namespace os {
namespace incidentd {

using android::binder::Status;
using android::os::BnIncidentAuthListener;
using android::os::IIncidentCompanion;

class ReportHandler;

class Broadcaster : public virtual RefBase {
public:
    enum broadcast_status_t {
        BROADCASTS_FINISHED = 0,
        BROADCASTS_REPEAT = 1,
        BROADCASTS_BACKOFF = 2
    };

    Broadcaster(const sp<WorkDirectory>& workDirectory);

    void setHandler(const sp<ReportHandler>& handler);

    /**
     * Reset the beginning timestamp for broadcasts.  Call this when
     * the system_server restarts.
     */
    void reset();

    /**
     * Remove the history record for the broadcasts, including pending authorizations
     * if necessary.
     */
    void clearBroadcasts(const string& pkg, const string& cls, const string& id);
    void clearPackageBroadcasts(const string& pkg);

    /**
     * Send whichever broadcasts have been pending.
     */
    broadcast_status_t sendBroadcasts();

private:
    struct ReportId {
        ReportId();
        ReportId(const ReportId& that);
        ReportId(const string& i, const string& p, const string& c);
        ~ReportId();

        bool operator<(const ReportId& that) const;

        string id;
        string pkg;
        string cls;
    };

    class ConsentListener : public BnIncidentAuthListener {
      public:
        ConsentListener(const sp<Broadcaster>& broadcaster, const ReportId& reportId);
        virtual ~ConsentListener();
        virtual Status onReportApproved();
        virtual Status onReportDenied();
      private:
        sp<Broadcaster> mBroadcaster;
        ReportId mId;
    };
    
    struct ReportStatus {
        ReportStatus();
        ReportStatus(const ReportStatus& that);
        ~ReportStatus();

        bool approval_sent;
        bool ready_sent;
        sp<ConsentListener> listener;
    };

    sp<ReportHandler> mReportHandler;
    sp<WorkDirectory> mWorkDirectory;

    // protected by mLock
    mutex mLock;
    map<ReportId,ReportStatus> mHistory; // what we sent so we don't send it again
    int64_t mLastSent;

    void set_last_sent(int64_t timestamp);
    int64_t get_last_sent();
    void print_report_statuses() const;
    status_t send_approval_broadcasts(const string& id, const string& pkg, const string& cls);
    void report_approved(const ReportId& reportId);
    void report_denied(const ReportId& reportId);
    status_t send_report_ready_broadcasts(const string& id, const string& pkg, const string& cls);
    status_t send_to_dropbox(const sp<ReportFile>& file, const IncidentReportArgs& args);
    bool was_approval_sent(const string& id, const string& pkg, const string& cls);
    void set_approval_sent(const string& id, const string& pkg, const string& cls,
            const sp<ConsentListener>& listener);
    bool was_ready_sent(const string& id, const string& pkg, const string& cls);
    void set_ready_sent(const string& id, const string& pkg, const string& cls);
    sp<IIncidentCompanion> get_incident_companion();
};


}  // namespace incidentd
}  // namespace os
}  // namespace android

