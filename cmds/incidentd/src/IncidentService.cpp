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

#define LOG_TAG "incidentd"

#include "IncidentService.h"

#include "Reporter.h"

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <cutils/log.h>
#include <private/android_filesystem_config.h>
#include <utils/Looper.h>

#include <unistd.h>

using namespace android;

enum {
    WHAT_RUN_REPORT = 1,
    WHAT_SEND_BACKLOG_TO_DROPBOX = 2
};

//#define DEFAULT_BACKLOG_DELAY_NS (1000000000LL * 60 * 5)
#define DEFAULT_BACKLOG_DELAY_NS (1000000000LL)

// ================================================================================
String16 const DUMP_PERMISSION("android.permission.DUMP");
String16 const USAGE_STATS_PERMISSION("android.permission.PACKAGE_USAGE_STATS");

static Status
checkIncidentPermissions(const IncidentReportArgs& args)
{
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
        return Status::fromExceptionCode(Status::EX_SECURITY,
                "Calling process does not have permission: android.permission.DUMP");
    }
    if (!checkCallingPermission(USAGE_STATS_PERMISSION)) {
        ALOGW("Calling pid %d and uid %d does not have permission: android.permission.USAGE_STATS",
                callingPid, callingUid);
        return Status::fromExceptionCode(Status::EX_SECURITY,
                "Calling process does not have permission: android.permission.USAGE_STATS");
    }

    // checking calling request uid permission.
    switch (args.dest()) {
        case DEST_LOCAL:
            if (callingUid != AID_SHELL && callingUid != AID_ROOT) {
                ALOGW("Calling pid %d and uid %d does not have permission to get local data.",
                        callingPid, callingUid);
                return Status::fromExceptionCode(Status::EX_SECURITY,
                    "Calling process does not have permission to get local data.");
            }
        case DEST_EXPLICIT:
            if (callingUid != AID_SHELL && callingUid != AID_ROOT &&
                callingUid != AID_STATSD && callingUid != AID_SYSTEM) {
                ALOGW("Calling pid %d and uid %d does not have permission to get explicit data.",
                        callingPid, callingUid);
                return Status::fromExceptionCode(Status::EX_SECURITY,
                    "Calling process does not have permission to get explicit data.");
            }
    }
    return Status::ok();
}
// ================================================================================
ReportRequestQueue::ReportRequestQueue()
{
}

ReportRequestQueue::~ReportRequestQueue()
{
}

void
ReportRequestQueue::addRequest(const sp<ReportRequest>& request)
{
    unique_lock<mutex> lock(mLock);
    mQueue.push_back(request);
}

sp<ReportRequest>
ReportRequestQueue::getNextRequest()
{
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
ReportHandler::ReportHandler(const sp<Looper>& handlerLooper, const sp<ReportRequestQueue>& queue)
    :mBacklogDelay(DEFAULT_BACKLOG_DELAY_NS),
     mHandlerLooper(handlerLooper),
     mQueue(queue)
{
}

ReportHandler::~ReportHandler()
{
}

void
ReportHandler::handleMessage(const Message& message)
{
    switch (message.what) {
        case WHAT_RUN_REPORT:
            run_report();
            break;
        case WHAT_SEND_BACKLOG_TO_DROPBOX:
            send_backlog_to_dropbox();
            break;
    }
}

void
ReportHandler::scheduleRunReport(const sp<ReportRequest>& request)
{
    mQueue->addRequest(request);
    mHandlerLooper->removeMessages(this, WHAT_RUN_REPORT);
    mHandlerLooper->sendMessage(this, Message(WHAT_RUN_REPORT));
}

void
ReportHandler::scheduleSendBacklogToDropbox()
{
    unique_lock<mutex> lock(mLock);
    mBacklogDelay = DEFAULT_BACKLOG_DELAY_NS;
    schedule_send_backlog_to_dropbox_locked();
}

void
ReportHandler::schedule_send_backlog_to_dropbox_locked()
{
    mHandlerLooper->removeMessages(this, WHAT_SEND_BACKLOG_TO_DROPBOX);
    mHandlerLooper->sendMessageDelayed(mBacklogDelay, this,
            Message(WHAT_SEND_BACKLOG_TO_DROPBOX));
}

void
ReportHandler::run_report()
{
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

    // Take the report, which might take a while. More requests might queue
    // up while we're doing this, and we'll handle them in their next batch.
    // TODO: We should further rate-limit the reports to no more than N per time-period.
    Reporter::run_report_status_t reportStatus = reporter->runReport();
    if (reportStatus == Reporter::REPORT_NEEDS_DROPBOX) {
        unique_lock<mutex> lock(mLock);
        schedule_send_backlog_to_dropbox_locked();
    }
}

void
ReportHandler::send_backlog_to_dropbox()
{
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
    :mQueue(new ReportRequestQueue())
{
    mHandler = new ReportHandler(handlerLooper, mQueue);
}

IncidentService::~IncidentService()
{
}

Status
IncidentService::reportIncident(const IncidentReportArgs& args)
{
    ALOGI("reportIncident");

    Status status = checkIncidentPermissions(args);
    if (!status.isOk()) {
        return status;
    }

    mHandler->scheduleRunReport(new ReportRequest(args, NULL, -1));

    return Status::ok();
}

Status
IncidentService::reportIncidentToStream(const IncidentReportArgs& args,
            const sp<IIncidentReportStatusListener>& listener, const unique_fd& stream)
{
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

Status
IncidentService::systemRunning()
{
    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                "Only system uid can call systemRunning");
    }

    // When system_server is up and running, schedule the dropbox task to run.
    mHandler->scheduleSendBacklogToDropbox();

    return Status::ok();
}

