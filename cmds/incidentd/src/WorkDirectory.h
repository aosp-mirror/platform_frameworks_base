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

#include <android/content/ComponentName.h>
#include <android/os/IncidentReportArgs.h>
#include <frameworks/base/core/proto/android/os/metadata.pb.h>
#include <frameworks/base/cmds/incidentd/src/report_file.pb.h>

#include <utils/RefBase.h>

#include <mutex>
#include <string>

namespace android {
namespace os {
namespace incidentd {

using android::content::ComponentName;
using android::os::IncidentReportArgs;
using namespace std;

extern const ComponentName DROPBOX_SENTINEL;

class WorkDirectory;
struct WorkDirectoryEntry;

void get_args_from_report(IncidentReportArgs* out, const ReportFileProto_Report& report);

/**
 * A ReportFile object is backed by two files.
 *   - A metadata file, which contains a 
 */
class ReportFile : public virtual RefBase {
public:
    ReportFile(const sp<WorkDirectory>& workDirectory, int64_t timestampNs,
            const string& envelopeFileName, const string& dataFileName);

    virtual ~ReportFile();

    /**
     * Get the timestamp from when this file was added.
     */
    int64_t getTimestampNs() const;

    /**
     * Add an additional report to this ReportFile.
     */
    void addReport(const IncidentReportArgs& args);

    /**
     * Remove the reports for pkg/cls from this file.
     */
    void removeReport(const string& pkg, const string& cls);

    /**
     * Remove all reports for pkg from this file.
     */
    void removeReports(const string& pkg);

    /**
     * Set the metadata for this incident report.
     */
    void setMetadata(const IncidentMetadata& metadata);

    /*
     * Mark this incident report as finished and ready for broadcast.
     */
    void markCompleted();

    /*
     * Mark this incident report as finished and ready for broadcast.
     */
    status_t markApproved(const string& pkg, const string& cls);
    
    /**
     * Set the privacy policy that is being used to pre-filter the data
     * going to disk.
     */
    void setMaxPersistedPrivacyPolicy(int persistedPrivacyPolicy);

    /**
     * Save the metadata (envelope) information about the incident
     * report.  Must be called after addReport, setMetadata markCompleted
     * markApproved to save those changes to disk.
     */
    status_t saveEnvelope();

    /**
     * Like saveEnvelope() but will not clean up if there is an error.
     */
    status_t trySaveEnvelope();

    /**
     * Read the envelope information from disk.  If there was an error, the envelope and
     * data file will be removed.  If the proto can't be loaded, the whole file is deleted.
     */
    status_t loadEnvelope();

    /**
     * Like loadEnvelope() but will not clean up if there is an error.
     */
    status_t tryLoadEnvelope();

    /**
     * Get the envelope information.
     */
    const ReportFileProto& getEnvelope();

    /**
     * Open the file that will contain the contents of the incident report.  Call
     * close() or closeDataFile() on the result of getDataFileFd() when you're done.
     * This is not done automatically in the desctructor.   If there is an error, returns
     * it and you will not get an fd.
     */
    status_t startWritingDataFile();

    /**
     * Close the data file.
     */
    void closeDataFile();

    /**
     * Use the privacy and section configuration from the args parameter to filter data, write
     * to [writeFd] and take the ownership of [writeFd].
     *
     * Note: this call is blocking. When the writeFd is a pipe fd for IPC, caller should make sure
     * it's called on a separate thread so that reader can start to read without waiting for writer
     * to finish writing (which may not happen due to pipe buffer overflow).
     */
    status_t startFilteringData(int writeFd, const IncidentReportArgs& args);

    /**
     * Get the name of the data file on disk.
     */
    string getDataFileName() const;

    /**
     * Get the name of the envelope file on disk.
     */
    string getEnvelopeFileName() const;

    /**
     * Return the file descriptor for the data file, or -1 if it is not
     * currently open.
     */
    int getDataFileFd();

    /**
     * Record that there was an error writing to the data file.
     */
    void setWriteError(status_t err);

    /**
     * Get whether there was previously an error writing to the data file.
     */
    status_t getWriteError();

    /**
     * Get the unique identifier for this file.
     */
    string getId();

private:
    sp<WorkDirectory> mWorkDirectory;
    int64_t mTimestampNs;
    string mEnvelopeFileName;
    string mDataFileName;
    ReportFileProto mEnvelope;
    int mDataFd;
    status_t mError;

    status_t save_envelope_impl(bool cleanup);
    status_t load_envelope_impl(bool cleanup);
};

/**
 * For directory cleanup to work, WorkDirectory must be kept
 * alive for the duration of all of the ReportFiles.  In the real
 * incidentd, WorkDirectory is a singleton.  In tests, it may
 * have a shorter duration.
 */
class WorkDirectory : public virtual RefBase {
public:
    /**
     * Save files to the default location.
     */
    WorkDirectory();

    /**
     * Save files to a specific location (primarily for testing).
     */
    WorkDirectory(const string& dir, int maxFileCount, long maxDiskUsageBytes);

    /**
     * Return a new report file.  Creating this object won't fail, but
     * subsequent actions on the file could, if the disk is full, permissions
     * aren't set correctly, etc.
     */
    sp<ReportFile> createReportFile();

    /**
     * Get the reports that are saved on-disk, with the time after (>) than the
     * given timestamp.  Pass 0 to start at the beginning.  These files
     * will be sorted by timestamp.  The envelope will not have been loaded.
     */
    status_t getReports(vector<sp<ReportFile>>* files, int64_t after);

    /**
     * Get the report with the given package, class and id. Returns nullptr if
     * that can't be found.  The envelope will have been loaded.  Returns the
     * original IncidentReportArgs in *args if args != nullptr.
     */
    sp<ReportFile> getReport(const string& pkg, const string& cls, const string& id,
            IncidentReportArgs* args);

    /**
     * Returns whether there are more reports after the given timestamp.
     */
    bool hasMore(int64_t after);

    /**
     * Confirm that a particular broadcast receiver has received the data.  When all
     * broadcast receivers for a particular report file have finished, the envelope
     * and data files will be deleted.
     */
    void commit(const sp<ReportFile>& report, const string& pkg, const string& cls);

    /**
     * Commit all reports the given package.
     */
    void commitAll(const string& pkg);

    /**
     * Remove the envelope and data file from disk, regardless of whether there are
     * more pending readers or broadcasts, for example in response to an error.
     */
    void remove(const sp<ReportFile>& report);
    
private:
    string mDirectory;
    int mMaxFileCount;
    long mMaxDiskUsageBytes;

    // Held while creating or removing envelope files, which are the file that keeps
    // the directory consistent.
    mutex mLock;

    int64_t make_timestamp_ns_locked();
    bool file_exists_locked(int64_t timestampNs);    
    off_t get_directory_contents_locked(map<string,WorkDirectoryEntry>* files, int64_t after);
    void clean_directory_locked();
    void delete_files_for_report_if_necessary(const sp<ReportFile>& report);

    string make_filename(int64_t timestampNs, const string& extension);
};


}  // namespace incidentd
}  // namespace os
}  // namespace android

