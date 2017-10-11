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

#ifndef REPORTER_H
#define REPORTER_H

#include <android/os/IIncidentReportStatusListener.h>
#include <android/os/IncidentReportArgs.h>

#include <string>
#include <vector>

#include <time.h>

using namespace android;
using namespace android::os;
using namespace std;

// ================================================================================
struct ReportRequest : public virtual RefBase
{
    IncidentReportArgs args;
    sp<IIncidentReportStatusListener> listener;
    int fd;
    status_t err;

    ReportRequest(const IncidentReportArgs& args,
            const sp<IIncidentReportStatusListener> &listener, int fd);
    virtual ~ReportRequest();
};

// ================================================================================
class ReportRequestSet
{
public:
    ReportRequestSet();
    ~ReportRequestSet();

    void add(const sp<ReportRequest>& request);
    void setMainFd(int fd);

    // Write to all of the fds for the requests. If a write fails, it stops
    // writing to that fd and returns NO_ERROR. When we are out of fds to write
    // to it returns an error.
    status_t write(uint8_t const* buf, size_t size);

    typedef vector<sp<ReportRequest>>::iterator iterator;

    iterator begin() { return mRequests.begin(); }
    iterator end() { return mRequests.end(); }

private:
    vector<sp<ReportRequest>> mRequests;
    int mWritableCount;
    int mMainFd;
};

// ================================================================================
class Reporter : public virtual RefBase
{
public:
    enum run_report_status_t {
        REPORT_FINISHED = 0,
        REPORT_NEEDS_DROPBOX = 1
    };

    IncidentReportArgs args;
    ReportRequestSet batch;

    Reporter();
    virtual ~Reporter();

    // Run the report as described in the batch and args parameters.
    run_report_status_t runReport();

    static run_report_status_t upload_backlog();

private:
    string mFilename;
    off_t mMaxSize;
    size_t mMaxCount;
    time_t mStartTime;

    status_t create_file(int* fd);
};


#endif // REPORTER_H
