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

#include "incidentd_util.h"
#include "FdBuffer.h"
#include "WorkDirectory.h"

#include "frameworks/base/core/proto/android/os/metadata.pb.h"
#include <android/content/ComponentName.h>
#include <android/os/IIncidentReportStatusListener.h>
#include <android/os/IIncidentDumpCallback.h>
#include <android/os/IncidentReportArgs.h>
#include <android/util/protobuf.h>

#include <map>
#include <string>
#include <vector>

#include <time.h>
#include <stdarg.h>

namespace android {
namespace os {
namespace incidentd {

using namespace std;
using namespace android::content;
using namespace android::os;

class BringYourOwnSection;
class Section;

// ================================================================================
class ReportRequest : public virtual RefBase {
public:
    IncidentReportArgs args;

    ReportRequest(const IncidentReportArgs& args, const sp<IIncidentReportStatusListener>& listener,
                  int fd);
    virtual ~ReportRequest();

    bool isStreaming() { return mIsStreaming; }

    void setStatus(status_t err) { mStatus = err; }
    status_t getStatus() const { return mStatus; }

    bool ok();  // returns true if the request is ok for write.

    bool containsSection(int sectionId) const;

    sp<IIncidentReportStatusListener> getListener() { return mListener; }

    int getFd();

    int setPersistedFd(int fd);

    status_t initGzipIfNecessary();

    void closeFd();

private:
    sp<IIncidentReportStatusListener> mListener;
    int mFd;
    bool mIsStreaming;
    status_t mStatus;
    pid_t mZipPid;
    Fpipe mZipPipe;
};

// ================================================================================
class ReportBatch : public virtual RefBase {
public:
    ReportBatch();
    virtual ~ReportBatch();

    // TODO: Should there be some kind of listener associated with the
    // component? Could be good for getting status updates e.g. in the ui,
    // as it progresses.  But that's out of scope for now.

    /**
     * Schedule a report for the "main" report, where it will be delivered to
     * the uploaders and/or dropbox.
     */
    void addPersistedReport(const IncidentReportArgs& args);

    /**
     * Adds a ReportRequest to the queue for one that has a listener an and fd
     */
    void addStreamingReport(const IncidentReportArgs& args,
           const sp<IIncidentReportStatusListener>& listener, int streamFd);

    /**
     * Returns whether both queues are empty.
     */
    bool empty() const;

    /**
     * Returns whether there are any persisted records.
     */
    bool hasPersistedReports() const { return mPersistedRequests.size() > 0; }

    /**
     * Return the persisted request for the given component, or nullptr.
     */
    sp<ReportRequest> getPersistedRequest(const ComponentName& component);

    /**
     * Call func(request) for each Request.
     */
    void forEachPersistedRequest(const function<void (const sp<ReportRequest>&)>& func);

    /**
     * Call func(request) for each Request.
     */
    void forEachStreamingRequest(const function<void (const sp<ReportRequest>&)>& func);

    /**
     * Call func(request) for each file descriptor.
     */
    void forEachFd(int sectionId, const function<void (const sp<ReportRequest>&)>& func);

    /**
     * Call func(listener) for every listener in this batch.
     */
    void forEachListener(const function<void (const sp<IIncidentReportStatusListener>&)>& func);

    /**
     * Call func(listener) for every listener in this batch that requests
     * sectionId.
     */
    void forEachListener(int sectionId,
            const function<void (const sp<IIncidentReportStatusListener>&)>& func);
    /**
     * Get an IncidentReportArgs that represents the combined args for the
     * persisted requests.
     */
    void getCombinedPersistedArgs(IncidentReportArgs* results);

    /**
     * Return whether any of the requests contain the section.
     */
    bool containsSection(int id);

    /**
     * Remove all of the broadcast (persisted) requests.
     */
    void clearPersistedRequests();

    /**
     * Move the streaming requests in this batch to that batch.  After this call there
     * will be no streaming requests in this batch.
     */
    void transferStreamingRequests(const sp<ReportBatch>& that);

    /**
     * Move the persisted requests in this batch to that batch.  After this call there
     * will be no streaming requests in this batch.
     */
    void transferPersistedRequests(const sp<ReportBatch>& that);

    /**
     * Get the requests that have encountered errors.
     */
    void getFailedRequests(vector<sp<ReportRequest>>* requests);

    /**
     * Remove the request from whichever list it's in.
     */
    void removeRequest(const sp<ReportRequest>& request);


private:
    map<ComponentName, sp<ReportRequest>> mPersistedRequests;
    vector<sp<ReportRequest>> mStreamingRequests;
};

// ================================================================================
class ReportWriter {
public:
    ReportWriter(const sp<ReportBatch>& batch);
    ~ReportWriter();

    void setPersistedFile(sp<ReportFile> file);
    void setMaxPersistedPrivacyPolicy(uint8_t privacyPolicy);

    void startSection(int sectionId);
    void endSection(IncidentMetadata::SectionStats* sectionStats);

    void setSectionStats(const FdBuffer& buffer);

    void warning(const Section* section, status_t err, const char* format, ...);
    void error(const Section* section, status_t err, const char* format, ...);

    status_t writeSection(const FdBuffer& buffer);

private:
    // Data about all requests
    sp<ReportBatch> mBatch;

    /**
     * The file on disk where we will store the persisted file.
     */
    sp<ReportFile> mPersistedFile;

    /**
     * The least restricted privacy policy of all of the perstited
     * requests. We pre-filter to that to save disk space.
     */
    uint8_t mMaxPersistedPrivacyPolicy;

    /**
     * The current section that is being written.
     */
    int mCurrentSectionId;

    /**
     * The time that that the current section was started.
     */
    int64_t mSectionStartTimeMs;

    /**
     * The last section that setSectionStats was called for, so if someone misses
     * it we can log that.
     */
    int mSectionStatsCalledForSectionId;

    /*
     * Fields for IncidentMetadata.SectionStats.  Set by setSectionStats.  Accessed by
     * getSectionStats.
     */
    int32_t mDumpSizeBytes;
    int64_t mDumpDurationMs;
    bool mSectionTimedOut;
    bool mSectionTruncated;
    bool mSectionBufferSuccess;
    bool mHadError;
    string mSectionErrors;
    size_t mMaxSectionDataFilteredSize;

    void vflog(const Section* section, status_t err, int level, const char* levelText,
        const char* format, va_list args);
};

// ================================================================================
class Reporter : public virtual RefBase {
public:
    Reporter(const sp<WorkDirectory>& workDirectory,
             const sp<ReportBatch>& batch,
             const vector<BringYourOwnSection*>& registeredSections);

    virtual ~Reporter();

    // Run the report as described in the batch and args parameters.
    void runReport(size_t* reportByteSize);

private:
    sp<WorkDirectory> mWorkDirectory;
    ReportWriter mWriter;
    sp<ReportBatch> mBatch;
    sp<ReportFile> mPersistedFile;
    const vector<BringYourOwnSection*>& mRegisteredSections;

    status_t execute_section(const Section* section, IncidentMetadata* metadata,
        size_t* reportByteSize);

    void cancel_and_remove_failed_requests();
};

}  // namespace incidentd
}  // namespace os
}  // namespace android
