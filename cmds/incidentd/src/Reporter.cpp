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

#include "Reporter.h"

#include "incidentd_util.h"
#include "Privacy.h"
#include "PrivacyFilter.h"
#include "proto_util.h"
#include "report_directory.h"
#include "section_list.h"

#include <android-base/file.h>
#include <android/os/DropBoxManager.h>
#include <android/util/protobuf.h>
#include <android/util/ProtoOutputStream.h>
#include <private/android_filesystem_config.h>
#include <utils/SystemClock.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <string>
#include <time.h>
#include <wait.h>

namespace android {
namespace os {
namespace incidentd {

using namespace android::util;

/**
 * The field id of the metadata section from
 *      frameworks/base/core/proto/android/os/incident.proto
 */
const int FIELD_ID_METADATA = 2;
// Args for exec gzip
static const char* GZIP[] = {"/system/bin/gzip", NULL};

IncidentMetadata_Destination privacy_policy_to_dest(uint8_t privacyPolicy) {
    switch (privacyPolicy) {
        case PRIVACY_POLICY_AUTOMATIC:
            return IncidentMetadata_Destination_AUTOMATIC;
        case PRIVACY_POLICY_EXPLICIT:
            return IncidentMetadata_Destination_EXPLICIT;
        case PRIVACY_POLICY_LOCAL:
            return IncidentMetadata_Destination_LOCAL;
        default:
            // Anything else reverts to automatic
            return IncidentMetadata_Destination_AUTOMATIC;
    }
}


static bool contains_section(const IncidentReportArgs& args, int sectionId) {
    return args.containsSection(sectionId, section_requires_specific_mention(sectionId));
}

static bool contains_section(const sp<ReportRequest>& args, int sectionId) {
    return args->containsSection(sectionId);
}

// ARGS must have a containsSection(int) method
template <typename ARGS>
void make_metadata(IncidentMetadata* result, const IncidentMetadata& full,
        int64_t reportId, int32_t privacyPolicy, ARGS args) {
    result->set_report_id(reportId);
    result->set_dest(privacy_policy_to_dest(privacyPolicy));

    size_t sectionCount = full.sections_size();
    for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
        const IncidentMetadata::SectionStats& sectionStats = full.sections(sectionIndex);
        if (contains_section(args, sectionStats.id())) {
            *result->add_sections() = sectionStats;
        }
    }
}

// ================================================================================
class StreamingFilterFd : public FilterFd {
public:
    StreamingFilterFd(uint8_t privacyPolicy, int fd, const sp<ReportRequest>& request);

    virtual void onWriteError(status_t err);

private:
    sp<ReportRequest> mRequest;
};

StreamingFilterFd::StreamingFilterFd(uint8_t privacyPolicy, int fd,
            const sp<ReportRequest>& request)
        :FilterFd(privacyPolicy, fd),
         mRequest(request) {
}

void StreamingFilterFd::onWriteError(status_t err) {
    mRequest->setStatus(err);
}


// ================================================================================
class PersistedFilterFd : public FilterFd {
public:
    PersistedFilterFd(uint8_t privacyPolicy, int fd, const sp<ReportFile>& reportFile);

    virtual void onWriteError(status_t err);

private:
    sp<ReportFile> mReportFile;
};

PersistedFilterFd::PersistedFilterFd(uint8_t privacyPolicy, int fd,
            const sp<ReportFile>& reportFile)
        :FilterFd(privacyPolicy, fd),
         mReportFile(reportFile) {
}

void PersistedFilterFd::onWriteError(status_t err) {
    mReportFile->setWriteError(err);
}


// ================================================================================
ReportRequest::ReportRequest(const IncidentReportArgs& a,
                             const sp<IIncidentReportStatusListener>& listener, int fd)
        :args(a),
         mListener(listener),
         mFd(fd),
         mIsStreaming(fd >= 0),
         mStatus(OK),
         mZipPid(-1) {
}

ReportRequest::~ReportRequest() {
    if (mIsStreaming && mFd >= 0) {
        // clean up the opened file descriptor
        close(mFd);
    }
}

bool ReportRequest::ok() {
    if (mStatus != OK) {
        return false;
    }
    if (!args.gzip()) {
        return mFd >= 0;
    }
    // Send a blank signal to check if mZipPid is alive
    return mZipPid > 0 && kill(mZipPid, 0) == 0;
}

bool ReportRequest::containsSection(int sectionId) const {
    return args.containsSection(sectionId, section_requires_specific_mention(sectionId));
}

void ReportRequest::closeFd() {
    if (!mIsStreaming) {
        return;
    }
    if (mFd >= 0) {
        close(mFd);
        mFd = -1;
    }
    if (mZipPid > 0) {
        mZipPipe.close();
        // Gzip may take some time.
        status_t err = wait_child(mZipPid, /* timeout_ms= */ 10 * 1000);
        if (err != 0) {
            ALOGW("[ReportRequest] abnormal child process: %s", strerror(-err));
        }
    }
}

int ReportRequest::getFd() {
    return mZipPid > 0 ? mZipPipe.writeFd().get() : mFd;
}

status_t ReportRequest::initGzipIfNecessary() {
    if (!mIsStreaming || !args.gzip()) {
        return OK;
    }
    if (!mZipPipe.init()) {
        ALOGE("[ReportRequest] Failed to setup pipe for gzip");
        mStatus = -errno;
        return mStatus;
    }
    int status = 0;
    pid_t pid = fork_execute_cmd((char* const*)GZIP, mZipPipe.readFd().release(), mFd, &status);
    if (pid < 0 || status != 0) {
        mStatus = status;
        return mStatus;
    }
    mZipPid = pid;
    mFd = -1;
    return OK;
}

// ================================================================================
ReportBatch::ReportBatch() {}

ReportBatch::~ReportBatch() {}

void ReportBatch::addPersistedReport(const IncidentReportArgs& args) {
    ComponentName component(args.receiverPkg(), args.receiverCls());
    map<ComponentName, sp<ReportRequest>>::iterator found = mPersistedRequests.find(component);
    if (found == mPersistedRequests.end()) {
        // not found
        mPersistedRequests[component] = new ReportRequest(args, nullptr, -1);
    } else {
        // found
        sp<ReportRequest> request = found->second;
        request->args.merge(args);
    }
}

void ReportBatch::addStreamingReport(const IncidentReportArgs& args,
        const sp<IIncidentReportStatusListener>& listener, int streamFd) {
    mStreamingRequests.push_back(new ReportRequest(args, listener, streamFd));
}

bool ReportBatch::empty() const {
    return mPersistedRequests.size() == 0 && mStreamingRequests.size() == 0;
}

sp<ReportRequest> ReportBatch::getPersistedRequest(const ComponentName& component) {
    map<ComponentName, sp<ReportRequest>>::iterator it = mPersistedRequests.find(component);
    if (it != mPersistedRequests.find(component)) {
        return it->second;
    } else {
        return nullptr;
    }
}

void ReportBatch::forEachPersistedRequest(const function<void (const sp<ReportRequest>&)>& func) {
    for (map<ComponentName, sp<ReportRequest>>::iterator it = mPersistedRequests.begin();
            it != mPersistedRequests.end(); it++) {
        func(it->second);
    }
}

void ReportBatch::forEachStreamingRequest(const function<void (const sp<ReportRequest>&)>& func) {
    for (vector<sp<ReportRequest>>::iterator request = mStreamingRequests.begin();
            request != mStreamingRequests.end(); request++) {
        func(*request);
    }
}

void ReportBatch::forEachListener(
        const function<void (const sp<IIncidentReportStatusListener>&)>& func) {
    for (map<ComponentName, sp<ReportRequest>>::iterator it = mPersistedRequests.begin();
            it != mPersistedRequests.end(); it++) {
        sp<IIncidentReportStatusListener> listener = it->second->getListener();
        if (listener != nullptr) {
            func(listener);
        }
    }
    for (vector<sp<ReportRequest>>::iterator request = mStreamingRequests.begin();
            request != mStreamingRequests.end(); request++) {
        sp<IIncidentReportStatusListener> listener = (*request)->getListener();
        if (listener != nullptr) {
            func(listener);
        }
    }
}

void ReportBatch::forEachListener(int sectionId,
        const function<void (const sp<IIncidentReportStatusListener>&)>& func) {
    for (map<ComponentName, sp<ReportRequest>>::iterator it = mPersistedRequests.begin();
            it != mPersistedRequests.end(); it++) {
        if (it->second->containsSection(sectionId)) {
            sp<IIncidentReportStatusListener> listener = it->second->getListener();
            if (listener != nullptr) {
                func(listener);
            }
        }
    }
    for (vector<sp<ReportRequest>>::iterator request = mStreamingRequests.begin();
            request != mStreamingRequests.end(); request++) {
        if ((*request)->containsSection(sectionId)) {
            sp<IIncidentReportStatusListener> listener = (*request)->getListener();
            if (listener != nullptr) {
                func(listener);
            }
        }
    }
}

void ReportBatch::getCombinedPersistedArgs(IncidentReportArgs* result) {
    for (map<ComponentName, sp<ReportRequest>>::iterator it = mPersistedRequests.begin();
            it != mPersistedRequests.end(); it++) {
        result->merge(it->second->args);
    }
}

bool ReportBatch::containsSection(int sectionId) {
    // We don't cache this, because in case of error, we remove requests
    // from the batch, and this is easier than recomputing the set.
    for (map<ComponentName, sp<ReportRequest>>::iterator it = mPersistedRequests.begin();
            it != mPersistedRequests.end(); it++) {
        if (it->second->containsSection(sectionId)) {
            return true;
        }
    }
    for (vector<sp<ReportRequest>>::iterator request = mStreamingRequests.begin();
            request != mStreamingRequests.end(); request++) {
        if ((*request)->containsSection(sectionId)) {
            return true;
        }
    }
    return false;
}

void ReportBatch::clearPersistedRequests() {
    mPersistedRequests.clear();
}

void ReportBatch::transferStreamingRequests(const sp<ReportBatch>& that) {
    for (vector<sp<ReportRequest>>::iterator request = mStreamingRequests.begin();
            request != mStreamingRequests.end(); request++) {
        that->mStreamingRequests.push_back(*request);
    }
    mStreamingRequests.clear();
}

void ReportBatch::transferPersistedRequests(const sp<ReportBatch>& that) {
    for (map<ComponentName, sp<ReportRequest>>::iterator it = mPersistedRequests.begin();
            it != mPersistedRequests.end(); it++) {
        that->mPersistedRequests[it->first] = it->second;
    }
    mPersistedRequests.clear();
}

void ReportBatch::getFailedRequests(vector<sp<ReportRequest>>* requests) {
    for (map<ComponentName, sp<ReportRequest>>::iterator it = mPersistedRequests.begin();
            it != mPersistedRequests.end(); it++) {
        if (it->second->getStatus() != NO_ERROR) {
            requests->push_back(it->second);
        }
    }
    for (vector<sp<ReportRequest>>::iterator request = mStreamingRequests.begin();
            request != mStreamingRequests.end(); request++) {
        if ((*request)->getStatus() != NO_ERROR) {
            requests->push_back(*request);
        }
    }
}

void ReportBatch::removeRequest(const sp<ReportRequest>& request) {
    for (map<ComponentName, sp<ReportRequest>>::iterator it = mPersistedRequests.begin();
            it != mPersistedRequests.end(); it++) {
        if (it->second == request) {
            mPersistedRequests.erase(it);
            return;
        }
    }
    for (vector<sp<ReportRequest>>::iterator it = mStreamingRequests.begin();
            it != mStreamingRequests.end(); it++) {
        if (*it == request) {
            mStreamingRequests.erase(it);
            return;
        }
    }
}

// ================================================================================
ReportWriter::ReportWriter(const sp<ReportBatch>& batch)
        :mBatch(batch),
         mPersistedFile(),
         mMaxPersistedPrivacyPolicy(PRIVACY_POLICY_UNSET) {
}

ReportWriter::~ReportWriter() {
}

void ReportWriter::setPersistedFile(sp<ReportFile> file) {
    mPersistedFile = file;
}

void ReportWriter::setMaxPersistedPrivacyPolicy(uint8_t privacyPolicy) {
    mMaxPersistedPrivacyPolicy = privacyPolicy;
}

void ReportWriter::startSection(int sectionId) {
    mCurrentSectionId = sectionId;
    mSectionStartTimeMs = uptimeMillis();

    mSectionStatsCalledForSectionId = -1;
    mDumpSizeBytes = 0;
    mDumpDurationMs = 0;
    mSectionTimedOut = false;
    mSectionTruncated = false;
    mSectionBufferSuccess = false;
    mHadError = false;
    mSectionErrors.clear();
}

void ReportWriter::setSectionStats(const FdBuffer& buffer) {
    mSectionStatsCalledForSectionId = mCurrentSectionId;
    mDumpSizeBytes = buffer.size();
    mDumpDurationMs = buffer.durationMs();
    mSectionTimedOut = buffer.timedOut();
    mSectionTruncated = buffer.truncated();
    mSectionBufferSuccess = !buffer.timedOut() && !buffer.truncated();
}

void ReportWriter::endSection(IncidentMetadata::SectionStats* sectionMetadata) {
    long endTime = uptimeMillis();

    if (mSectionStatsCalledForSectionId != mCurrentSectionId) {
        ALOGW("setSectionStats not called for section %d", mCurrentSectionId);
    }

    sectionMetadata->set_id(mCurrentSectionId);
    sectionMetadata->set_success((!mHadError) && mSectionBufferSuccess);
    sectionMetadata->set_report_size_bytes(mMaxSectionDataFilteredSize);
    sectionMetadata->set_exec_duration_ms(endTime - mSectionStartTimeMs);
    sectionMetadata->set_dump_size_bytes(mDumpSizeBytes);
    sectionMetadata->set_dump_duration_ms(mDumpDurationMs);
    sectionMetadata->set_timed_out(mSectionTimedOut);
    sectionMetadata->set_is_truncated(mSectionTruncated);
    sectionMetadata->set_error_msg(mSectionErrors);
}

void ReportWriter::warning(const Section* section, status_t err, const char* format, ...) {
    va_list args;
    va_start(args, format);
    vflog(section, err, ANDROID_LOG_ERROR, "error", format, args);
    va_end(args);
}

void ReportWriter::error(const Section* section, status_t err, const char* format, ...) {
    va_list args;
    va_start(args, format);
    vflog(section, err, ANDROID_LOG_WARN, "warning", format, args);
    va_end(args);
}

void ReportWriter::vflog(const Section* section, status_t err, int level, const char* levelText,
        const char* format, va_list args) {
    const char* prefixFormat = "%s in section %d (%d) '%s': ";
    int prefixLen = snprintf(NULL, 0, prefixFormat, levelText, section->id,
            err, strerror(-err));

    va_list measureArgs;
    va_copy(measureArgs, args);
    int messageLen = vsnprintf(NULL, 0, format, args);
    va_end(measureArgs);

    char* line = (char*)malloc(prefixLen + messageLen + 1);
    if (line == NULL) {
        // All hope is lost, just give up.
        return;
    }

    sprintf(line, prefixFormat, levelText, section->id, err, strerror(-err));

    vsprintf(line + prefixLen, format, args);

    __android_log_write(level, LOG_TAG, line);

    if (mSectionErrors.length() == 0) {
        mSectionErrors = line;
    } else {
        mSectionErrors += '\n';
        mSectionErrors += line;
    }

    free(line);

    if (level >= ANDROID_LOG_ERROR) {
        mHadError = true;
    }
}

// Reads data from FdBuffer and writes it to the requests file descriptor.
status_t ReportWriter::writeSection(const FdBuffer& buffer) {
    PrivacyFilter filter(mCurrentSectionId, get_privacy_of_section(mCurrentSectionId));

    // Add the fd for the persisted requests
    if (mPersistedFile != nullptr) {
        filter.addFd(new PersistedFilterFd(mMaxPersistedPrivacyPolicy,
                    mPersistedFile->getDataFileFd(), mPersistedFile));
    }

    // Add the fds for the streamed requests
    mBatch->forEachStreamingRequest([&filter, this](const sp<ReportRequest>& request) {
        if (request->ok()
                && request->args.containsSection(mCurrentSectionId,
                    section_requires_specific_mention(mCurrentSectionId))) {
            filter.addFd(new StreamingFilterFd(request->args.getPrivacyPolicy(),
                        request->getFd(), request));
        }
    });

    return filter.writeData(buffer, PRIVACY_POLICY_LOCAL, &mMaxSectionDataFilteredSize);
}


// ================================================================================
Reporter::Reporter(const sp<WorkDirectory>& workDirectory,
                   const sp<ReportBatch>& batch,
                   const vector<BringYourOwnSection*>& registeredSections)
        :mWorkDirectory(workDirectory),
         mWriter(batch),
         mBatch(batch),
         mRegisteredSections(registeredSections) {
}

Reporter::~Reporter() {
}

void Reporter::runReport(size_t* reportByteSize) {
    status_t err = NO_ERROR;

    IncidentMetadata metadata;
    int persistedPrivacyPolicy = PRIVACY_POLICY_UNSET;

    (*reportByteSize) = 0;

    // Tell everyone that we're starting.
    ALOGI("Starting incident report");
    mBatch->forEachListener([](const auto& listener) { listener->onReportStarted(); });

    if (mBatch->hasPersistedReports()) {
        // Open a work file to contain the contents of all of the persisted reports.
        // For this block, if we can't initialize the report file for some reason,
        // then we will remove the persisted ReportRequests from the report, but
        // continue with the streaming ones.
        mPersistedFile = mWorkDirectory->createReportFile();
        ALOGI("Report will be persisted: envelope: %s  data: %s",
                mPersistedFile->getEnvelopeFileName().c_str(),
                mPersistedFile->getDataFileName().c_str());

        // Record all of the metadata to the persisted file's metadata file.
        // It will be read from there and reconstructed as the actual reports
        // are sent out.
        if (mPersistedFile != nullptr) {
            mBatch->forEachPersistedRequest([this, &persistedPrivacyPolicy](
                        const sp<ReportRequest>& request) {
                mPersistedFile->addReport(request->args);
                if (request->args.getPrivacyPolicy() < persistedPrivacyPolicy) {
                    persistedPrivacyPolicy = request->args.getPrivacyPolicy();
                }
            });
            mPersistedFile->setMaxPersistedPrivacyPolicy(persistedPrivacyPolicy);
            err = mPersistedFile->saveEnvelope();
            if (err != NO_ERROR) {
                mWorkDirectory->remove(mPersistedFile);
                mPersistedFile = nullptr;
            }
            mWriter.setMaxPersistedPrivacyPolicy(persistedPrivacyPolicy);
        }

        if (mPersistedFile != nullptr) {
            err = mPersistedFile->startWritingDataFile();
            if (err != NO_ERROR) {
                mWorkDirectory->remove(mPersistedFile);
                mPersistedFile = nullptr;
            }
        }

        if (mPersistedFile != nullptr) {
            mWriter.setPersistedFile(mPersistedFile);
        } else {
            ALOGW("Error creating the persisted file, so clearing persisted reports.");
            // If we couldn't open the file (permissions err, etc), then
            // we still want to proceed with any streaming reports, but
            // cancel all of the persisted ones.
            mBatch->forEachPersistedRequest([](const sp<ReportRequest>& request) {
                sp<IIncidentReportStatusListener> listener = request->getListener();
                if (listener != nullptr) {
                    listener->onReportFailed();
                }
            });
            mBatch->clearPersistedRequests();
        }
    }

    // If we have a persisted ID, then we allow all the readers to see that.  There's
    // enough in the data to allow for a join, and nothing in here that intrisincally
    // could ever prevent that, so just give them the ID.  If we don't have that then we
    // make and ID that's extremely likely to be unique, but clock resetting could allow
    // it to be duplicate.
    int64_t reportId;
    if (mPersistedFile != nullptr) {
        reportId = mPersistedFile->getTimestampNs();
    } else {
        struct timespec spec;
        clock_gettime(CLOCK_REALTIME, &spec);
        reportId = (spec.tv_sec) * 1000 + spec.tv_nsec;
    }

    mBatch->forEachStreamingRequest([](const sp<ReportRequest>& request) {
        status_t err = request->initGzipIfNecessary();
        if (err != 0) {
            ALOGW("Error forking gzip: %s", strerror(err));
        }
    });

    // Write the incident report headers - each request gets its own headers.  It's different
    // from the other top-level fields in IncidentReport that are the sections where the rest
    // is all shared data (although with their own individual privacy filtering).
    mBatch->forEachStreamingRequest([](const sp<ReportRequest>& request) {
        const vector<vector<uint8_t>>& headers = request->args.headers();
        for (vector<vector<uint8_t>>::const_iterator buf = headers.begin(); buf != headers.end();
             buf++) {
            // If there was an error now, there will be an error later and we will remove
            // it from the list then.
            write_header_section(request->getFd(), buf->data(), buf->size());
        }
    });

    // If writing to any of the headers failed, we don't want to keep processing
    // sections for it.
    cancel_and_remove_failed_requests();

    // For each of the report fields, see if we need it, and if so, execute the command
    // and report to those that care that we're doing it.
    for (const Section** section = SECTION_LIST; *section; section++) {
        if (execute_section(*section, &metadata, reportByteSize) != NO_ERROR) {
            goto DONE;
        }
    }

    for (const Section* section : mRegisteredSections) {
        if (execute_section(section, &metadata, reportByteSize) != NO_ERROR) {
            goto DONE;
        }
    }

DONE:
    // Finish up the persisted file.
    if (mPersistedFile != nullptr) {
        mPersistedFile->closeDataFile();

        // Set the stored metadata
        IncidentReportArgs combinedArgs;
        mBatch->getCombinedPersistedArgs(&combinedArgs);
        IncidentMetadata persistedMetadata;
        make_metadata(&persistedMetadata, metadata, mPersistedFile->getTimestampNs(),
                persistedPrivacyPolicy, combinedArgs);
        mPersistedFile->setMetadata(persistedMetadata);

        mPersistedFile->markCompleted();
        err = mPersistedFile->saveEnvelope();
        if (err != NO_ERROR) {
            ALOGW("mPersistedFile->saveEnvelope returned %s. Won't send broadcast",
                    strerror(-err));
            // Abandon ship.
            mWorkDirectory->remove(mPersistedFile);
        }
    }

    // Write the metadata to the streaming ones
    mBatch->forEachStreamingRequest([reportId, &metadata](const sp<ReportRequest>& request) {
        IncidentMetadata streamingMetadata;
        make_metadata(&streamingMetadata, metadata, reportId,
                request->args.getPrivacyPolicy(), request);
        status_t nonFatalErr = write_section(request->getFd(), FIELD_ID_METADATA,
                streamingMetadata);
        if (nonFatalErr != NO_ERROR) {
            ALOGW("Error writing the metadata to streaming incident report.  This is the last"
                    " thing so we won't return an error: %s", strerror(nonFatalErr));
        }
    });

    // Finish up the streaming ones.
    mBatch->forEachStreamingRequest([](const sp<ReportRequest>& request) {
        request->closeFd();
    });

    // Tell the listeners that we're done.
    if (err == NO_ERROR) {
        mBatch->forEachListener([](const auto& listener) {
            listener->onReportFinished();
        });
    } else {
        mBatch->forEachListener([](const auto& listener) {
            listener->onReportFailed();
        });
    }
    clear_buffer_pool();
    ALOGI("Done taking incident report err=%s", strerror(-err));
}

status_t Reporter::execute_section(const Section* section, IncidentMetadata* metadata,
        size_t* reportByteSize) {
    const int sectionId = section->id;

    // If nobody wants this section, skip it.
    if (!mBatch->containsSection(sectionId)) {
        return NO_ERROR;
    }

    ALOGD("Start incident report section %d '%s'", sectionId, section->name.c_str());
    IncidentMetadata::SectionStats* sectionMetadata = metadata->add_sections();

    // Notify listener of starting
    mBatch->forEachListener(sectionId, [sectionId](const auto& listener) {
        listener->onReportSectionStatus(
                sectionId, IIncidentReportStatusListener::STATUS_STARTING);
    });

    // Go get the data and write it into the file descriptors.
    mWriter.startSection(sectionId);
    status_t err = section->Execute(&mWriter);
    mWriter.endSection(sectionMetadata);

    // Sections returning errors are fatal. Most errors should not be fatal.
    if (err != NO_ERROR) {
        mWriter.error(section, err, "Section failed. Stopping report.");
        return err;
    }

    // The returned max data size is used for throttling too many incident reports.
    (*reportByteSize) += sectionMetadata->report_size_bytes();

    // For any requests that failed during this section, remove them now.  We do this
    // before calling back about section finished, so listeners do not erroniously get the
    // impression that the section succeeded.  But we do it here instead of inside
    // writeSection so that the callback is done from a known context and not from the
    // bowels of a section, where changing the batch could cause odd errors.
    cancel_and_remove_failed_requests();

    // Notify listener of finishing
    mBatch->forEachListener(sectionId, [sectionId](const auto& listener) {
            listener->onReportSectionStatus(
                    sectionId, IIncidentReportStatusListener::STATUS_FINISHED);
    });

    ALOGD("Finish incident report section %d '%s'", sectionId, section->name.c_str());
    return NO_ERROR;
}

void Reporter::cancel_and_remove_failed_requests() {
    // Handle a failure in the persisted file
    if (mPersistedFile != nullptr) {
        if (mPersistedFile->getWriteError() != NO_ERROR) {
            ALOGW("Error writing to the persisted file (%s). Closing it and canceling.",
                    strerror(-mPersistedFile->getWriteError()));
            mBatch->forEachPersistedRequest([this](const sp<ReportRequest>& request) {
                sp<IIncidentReportStatusListener> listener = request->getListener();
                if (listener != nullptr) {
                    listener->onReportFailed();
                }
                mBatch->removeRequest(request);
            });
            mWriter.setPersistedFile(nullptr);
            mPersistedFile->closeDataFile();
            mWorkDirectory->remove(mPersistedFile);
            mPersistedFile = nullptr;
        }
    }

    // Handle failures in the streaming files
    vector<sp<ReportRequest>> failed;
    mBatch->getFailedRequests(&failed);
    for (sp<ReportRequest>& request: failed) {
        ALOGW("Error writing to a request stream (%s). Closing it and canceling.",
                strerror(-request->getStatus()));
        sp<IIncidentReportStatusListener> listener = request->getListener();
        if (listener != nullptr) {
            listener->onReportFailed();
        }
        request->closeFd();  // Will only close the streaming ones.
        mBatch->removeRequest(request);
    }
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
