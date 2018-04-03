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

#include <android/os/BnIncidentManager.h>
#include <utils/Looper.h>

#include <deque>
#include <mutex>

#include "Throttler.h"

namespace android {
namespace os {
namespace incidentd {

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::os;

// ================================================================================
class ReportRequestQueue : public virtual RefBase {
public:
    ReportRequestQueue();
    virtual ~ReportRequestQueue();

    void addRequest(const sp<ReportRequest>& request);
    sp<ReportRequest> getNextRequest();

private:
    mutex mLock;
    deque<sp<ReportRequest> > mQueue;
};

// ================================================================================
class ReportHandler : public MessageHandler {
public:
    ReportHandler(const sp<Looper>& handlerLooper, const sp<ReportRequestQueue>& queue,
                  const sp<Throttler>& throttler);
    virtual ~ReportHandler();

    virtual void handleMessage(const Message& message);

    /**
     * Adds a ReportRequest to the queue.
     */
    void scheduleRunReport(const sp<ReportRequest>& request);

    /**
     * Resets mBacklogDelay to the default and schedules sending
     * the messages to dropbox.
     */
    void scheduleSendBacklogToDropbox();

private:
    mutex mLock;
    nsecs_t mBacklogDelay;
    sp<Looper> mHandlerLooper;
    sp<ReportRequestQueue> mQueue;
    sp<Throttler> mThrottler;

    /**
     * Runs all of the reports that have been queued.
     */
    void run_report();

    /**
     * Schedules a dropbox task mBacklogDelay nanoseconds from now.
     */
    void schedule_send_backlog_to_dropbox_locked();

    /**
     * Sends the backlog to the dropbox service.
     */
    void send_backlog_to_dropbox();
};

// ================================================================================
class IncidentService : public BnIncidentManager {
public:
    IncidentService(const sp<Looper>& handlerLooper);
    virtual ~IncidentService();

    virtual Status reportIncident(const IncidentReportArgs& args);

    virtual Status reportIncidentToStream(const IncidentReportArgs& args,
                                          const sp<IIncidentReportStatusListener>& listener,
                                          const unique_fd& stream);

    virtual Status systemRunning();

    // Implement commands for debugging purpose.
    virtual status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply,
                                uint32_t flags) override;
    virtual status_t command(FILE* in, FILE* out, FILE* err, Vector<String8>& args);

private:
    sp<ReportRequestQueue> mQueue;
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
