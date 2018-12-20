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

#ifndef REPORTER_H
#define REPORTER_H

#include <android/os/IIncidentReportStatusListener.h>
#include <android/os/IncidentReportArgs.h>

#include <map>
#include <string>
#include <vector>

#include <time.h>

#include "Throttler.h"
#include "frameworks/base/libs/incident/proto/android/os/metadata.pb.h"

namespace android {
namespace os {
namespace incidentd {

// ================================================================================
struct ReportRequest : public virtual RefBase {
    IncidentReportArgs args;
    sp<IIncidentReportStatusListener> listener;
    int fd;
    status_t err;

    ReportRequest(const IncidentReportArgs& args, const sp<IIncidentReportStatusListener>& listener,
                  int fd);
    virtual ~ReportRequest();

    bool ok();  // returns true if the request is ok for write.
};

// ================================================================================
class ReportRequestSet {
public:
    ReportRequestSet();
    ~ReportRequestSet();

    void add(const sp<ReportRequest>& request);
    void setMainFd(int fd);
    void setMainDest(int dest);

    typedef vector<sp<ReportRequest>>::iterator iterator;

    iterator begin() { return mRequests.begin(); }
    iterator end() { return mRequests.end(); }

    int mainFd() { return mMainFd; }
    int mainDest() { return mMainDest; }
    IncidentMetadata& metadata() { return mMetadata; }
    map<int, IncidentMetadata::SectionStats>& allSectionStats() { return mSectionStats; }

    bool containsSection(int id);
    IncidentMetadata::SectionStats* sectionStats(int id);

private:
    vector<sp<ReportRequest>> mRequests;
    IncidentReportArgs mSections;
    int mMainFd;
    int mMainDest;

    IncidentMetadata mMetadata;
    map<int, IncidentMetadata::SectionStats> mSectionStats;
};

// ================================================================================
class Reporter : public virtual RefBase {
public:
    enum run_report_status_t { REPORT_FINISHED = 0, REPORT_NEEDS_DROPBOX = 1 };

    ReportRequestSet batch;

    Reporter();                       // PROD must use this constructor.
    explicit Reporter(const char* directory);  // For testing purpose only.
    virtual ~Reporter();

    // Run the report as described in the batch and args parameters.
    run_report_status_t runReport(size_t* reportByteSize);

    static run_report_status_t upload_backlog();

private:
    String8 mIncidentDirectory;

    string mFilename;
    off_t mMaxSize;
    size_t mMaxCount;
    time_t mStartTime;

    status_t create_file(int* fd);

    bool isTest = true;  // default to true for testing
};

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // REPORTER_H
