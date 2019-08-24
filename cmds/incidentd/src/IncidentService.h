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
#pragma once

#ifndef INCIDENT_SERVICE_H
#define INCIDENT_SERVICE_H

#include "Reporter.h"

#include "Broadcaster.h"
#include "Throttler.h"
#include "WorkDirectory.h"

#include <android/os/BnIncidentManager.h>
#include <utils/Looper.h>

#include <vector>
#include <mutex>


namespace android {
namespace os {
namespace incidentd {

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::os;

// ================================================================================
class ReportHandler : public MessageHandler {
public:
    ReportHandler(const sp<WorkDirectory>& workDirectory,
            const sp<Broadcaster>& broadcaster, const sp<Looper>& handlerLooper,
            const sp<Throttler>& throttler);
    virtual ~ReportHandler();

    virtual void handleMessage(const Message& message);

    /**
     * Schedule a report for the "main" report, where it will be delivered to
     * the uploaders and/or dropbox.
     */
    void schedulePersistedReport(const IncidentReportArgs& args);

    /**
     * Adds a ReportRequest to the queue for one that has a listener an and fd
     */
    void scheduleStreamingReport(const IncidentReportArgs& args,
                                    const sp<IIncidentReportStatusListener>& listener,
                                    int streamFd);

    /**
     * Resets mBacklogDelay to the default and schedules sending
     * the messages to dropbox.
     */
    void scheduleSendBacklog();

private:
    mutex mLock;

    sp<WorkDirectory> mWorkDirectory;
    sp<Broadcaster> mBroadcaster;

    sp<Looper> mHandlerLooper;
    nsecs_t mBacklogDelay;
    sp<Throttler> mThrottler;

    sp<ReportBatch> mBatch;

    /**
     * Runs all of the reports that have been queued.
     */
    void take_report();

    /**
     * Schedules permission controller approve the reports.
     */
    void schedule_send_approvals_locked();

    /**
     * Sends the approvals to the PermissionController
     */
    void send_approvals();

    /**
     * Schedules the broadcasts that reports are complete mBacklogDelay nanoseconds from now.
     * The delay is because typically when an incident report is taken, the system is not
     * really in a happy state.  So we wait a bit before sending the report to let things
     * quiet down if they can.  The urgency is in taking the report, not sharing the report.
     * However, we don
     */
    void schedule_send_broadcasts_locked();

    /**
     * Sends the broadcasts to the dropbox service.
     */
    void send_broadcasts();
};

// ================================================================================
class IncidentService : public BnIncidentManager {
public:
    explicit IncidentService(const sp<Looper>& handlerLooper);
    virtual ~IncidentService();

    virtual Status reportIncident(const IncidentReportArgs& args);

    virtual Status reportIncidentToStream(const IncidentReportArgs& args,
                                          const sp<IIncidentReportStatusListener>& listener,
                                          const unique_fd& stream);

    virtual Status reportIncidentToDumpstate(const unique_fd& stream,
            const sp<IIncidentReportStatusListener>& listener);

    virtual Status systemRunning();

    virtual Status getIncidentReportList(const String16& pkg, const String16& cls,
            vector<String16>* result);

    virtual Status getIncidentReport(const String16& pkg, const String16& cls,
            const String16& id, IncidentManager::IncidentReport* result);

    virtual Status deleteIncidentReports(const String16& pkg, const String16& cls,
            const String16& id);

    virtual Status deleteAllIncidentReports(const String16& pkg);

    // Implement commands for debugging purpose.
    virtual status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply,
                                uint32_t flags) override;
    virtual status_t command(FILE* in, FILE* out, FILE* err, Vector<String8>& args);

private:
    sp<WorkDirectory> mWorkDirectory;
    sp<Broadcaster> mBroadcaster;
    sp<ReportHandler> mHandler;
    sp<Throttler> mThrottler;

    /**
     * Commands print out help.
     */
    status_t cmd_help(FILE* out);

    /**
     * Commands related to privacy filtering.
     */
    status_t cmd_privacy(FILE* in, FILE* out, FILE* err, Vector<String8>& args);
};

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // INCIDENT_SERVICE_H
