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

#include "Log.h"

#include "WorkDirectory.h"

#include "proto_util.h"
#include "PrivacyFilter.h"

#include <google/protobuf/io/zero_copy_stream_impl.h>
#include <private/android_filesystem_config.h>

#include <iomanip>
#include <map>
#include <sstream>
#include <thread>
#include <vector>

#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <inttypes.h>

namespace android {
namespace os {
namespace incidentd {

using std::thread;
using google::protobuf::MessageLite;
using google::protobuf::RepeatedPtrField;
using google::protobuf::io::FileInputStream;
using google::protobuf::io::FileOutputStream;

/**
 * Turn off to skip removing files for debugging.
 */
static const bool DO_UNLINK = true;

/**
 * File extension for envelope files.
 */
static const string EXTENSION_ENVELOPE(".envelope");

/**
 * File extension for data files.
 */
static const string EXTENSION_DATA(".data");

/**
 * Send these reports to dropbox.
 */
const ComponentName DROPBOX_SENTINEL("android", "DROPBOX");

/** metadata field id in IncidentProto */
const int FIELD_ID_INCIDENT_METADATA = 2;

/**
 * Read a protobuf from disk into the message.
 */
static status_t read_proto(MessageLite* msg, const string& filename) {
    int fd = open(filename.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -errno;
    }

    FileInputStream stream(fd);
    stream.SetCloseOnDelete(fd);

    if (!msg->ParseFromZeroCopyStream(&stream)) {
        return BAD_VALUE;
    }

    return stream.GetErrno();
}

/**
 * Write a protobuf to disk.
 */
static status_t write_proto(const MessageLite& msg, const string& filename) {
    int fd = open(filename.c_str(), O_CREAT | O_TRUNC | O_RDWR | O_CLOEXEC, 0660);
    if (fd < 0) {
        return -errno;
    }

    FileOutputStream stream(fd);
    stream.SetCloseOnDelete(fd);

    if (!msg.SerializeToZeroCopyStream(&stream)) {
        ALOGW("write_proto: error writing to %s", filename.c_str());
        return BAD_VALUE;
    }

    return stream.GetErrno();
}

static string strip_extension(const string& filename) {
    return filename.substr(0, filename.find('.'));
}

static bool ends_with(const string& str, const string& ending) {
    if (str.length() >= ending.length()) {
        return str.compare(str.length()-ending.length(), ending.length(), ending) == 0;
    } else {
        return false;
    }
}

// Returns true if it was a valid timestamp.
static bool parse_timestamp_ns(const string& id, int64_t* result) {
    char* endptr;
    *result = strtoll(id.c_str(), &endptr, 10);
    return id.length() != 0 && *endptr == '\0';
}

static bool has_section(const ReportFileProto_Report& report, int section) {
    const size_t sectionCount = report.section_size();
    for (int i = 0; i < sectionCount; i++) {
        if (report.section(i) == section) {
            return true;
        }
    }
    return false;
}

status_t create_directory(const char* directory) {
    struct stat st;
    status_t err = NO_ERROR;
    char* dir = strdup(directory);

    // Skip first slash
    char* d = dir + 1;

    // Create directories, assigning them to the system user
    bool last = false;
    while (!last) {
        d = strchr(d, '/');
        if (d != NULL) {
            *d = '\0';
        } else {
            last = true;
        }
        if (stat(dir, &st) == 0) {
            if (!S_ISDIR(st.st_mode)) {
                err = ALREADY_EXISTS;
                goto done;
            }
        } else {
            ALOGE("No such directory %s, something wrong.", dir);
            err = -1;
            goto done;
        }
        if (!last) {
            *d++ = '/';
        }
    }

    // Ensure that the final directory is owned by the system with 0770. If it isn't
    // we won't write into it.
    if (stat(directory, &st) != 0) {
        ALOGE("No incident reports today. Can't stat: %s", directory);
        err = -errno;
        goto done;
    }
    if ((st.st_mode & 0777) != 0770) {
        ALOGE("No incident reports today. Mode is %0o on report directory %s", st.st_mode,
              directory);
        err = BAD_VALUE;
        goto done;
    }
    if (st.st_uid != AID_INCIDENTD || st.st_gid != AID_INCIDENTD) {
        ALOGE("No incident reports today. Owner is %d and group is %d on report directory %s",
              st.st_uid, st.st_gid, directory);
        err = BAD_VALUE;
        goto done;
    }

done:
    free(dir);
    return err;
}

void log_envelope(const ReportFileProto& envelope) {
    ALOGD("Envelope: {");
    for (int i=0; i<envelope.report_size(); i++) {
        ALOGD("  report {");
        ALOGD("    pkg=%s", envelope.report(i).pkg().c_str());
        ALOGD("    cls=%s", envelope.report(i).cls().c_str());
        ALOGD("    share_approved=%d", envelope.report(i).share_approved());
        ALOGD("    privacy_policy=%d", envelope.report(i).privacy_policy());
        ALOGD("    all_sections=%d", envelope.report(i).all_sections());
        for (int j=0; j<envelope.report(i).section_size(); j++) {
            ALOGD("    section[%d]=%d", j, envelope.report(i).section(j));
        }
        ALOGD("  }");
    }
    ALOGD("  data_file=%s", envelope.data_file().c_str());
    ALOGD("  privacy_policy=%d", envelope.privacy_policy());
    ALOGD("  data_file_size=%" PRIi64, (int64_t)envelope.data_file_size());
    ALOGD("  completed=%d", envelope.completed());
    ALOGD("}");
}

// ================================================================================
struct WorkDirectoryEntry {
    WorkDirectoryEntry();
    explicit WorkDirectoryEntry(const WorkDirectoryEntry& that);
    ~WorkDirectoryEntry();

    string envelope;
    string data;
    int64_t timestampNs;
    off_t size;
};

WorkDirectoryEntry::WorkDirectoryEntry()
        :envelope(),
         data(),
         size(0) {
}

WorkDirectoryEntry::WorkDirectoryEntry(const WorkDirectoryEntry& that)
        :envelope(that.envelope),
         data(that.data),
         size(that.size) {
}

WorkDirectoryEntry::~WorkDirectoryEntry() {
}

// ================================================================================
ReportFile::ReportFile(const sp<WorkDirectory>& workDirectory, int64_t timestampNs,
            const string& envelopeFileName, const string& dataFileName)
        :mWorkDirectory(workDirectory),
         mTimestampNs(timestampNs),
         mEnvelopeFileName(envelopeFileName),
         mDataFileName(dataFileName),
         mEnvelope(),
         mDataFd(-1),
         mError(NO_ERROR) {
    // might get overwritten when we read but that's ok
    mEnvelope.set_data_file(mDataFileName);
}

ReportFile::~ReportFile() {
    if (mDataFd >= 0) {
        close(mDataFd);
    }
}

int64_t ReportFile::getTimestampNs() const {
    return mTimestampNs;
}

void ReportFile::addReport(const IncidentReportArgs& args) {
    // There is only one report per component.  Merge into an existing one if necessary.
    ReportFileProto_Report* report;
    const int reportCount = mEnvelope.report_size();
    int i = 0;
    for (; i < reportCount; i++) {
        report = mEnvelope.mutable_report(i);
        if (report->pkg() == args.receiverPkg() && report->cls() == args.receiverCls()) {
            if (args.getPrivacyPolicy() < report->privacy_policy()) {
                // Lower privacy policy (less restrictive) wins.
                report->set_privacy_policy(args.getPrivacyPolicy());
            }
            report->set_all_sections(report->all_sections() | args.all());
            for (int section: args.sections()) {
                if (!has_section(*report, section)) {
                    report->add_section(section);
                }
            }
            break;
        }
    }
    if (i >= reportCount) {
        report = mEnvelope.add_report();
        report->set_pkg(args.receiverPkg());
        report->set_cls(args.receiverCls());
        report->set_privacy_policy(args.getPrivacyPolicy());
        report->set_all_sections(args.all());
        for (int section: args.sections()) {
            report->add_section(section);
        }
    }

    for (const vector<uint8_t>& header: args.headers()) {
        report->add_header(header.data(), header.size());
    }
}

void ReportFile::removeReport(const string& pkg, const string& cls) {
    RepeatedPtrField<ReportFileProto_Report>* reports = mEnvelope.mutable_report();
    const int reportCount = reports->size();
    for (int i = 0; i < reportCount; i++) {
        const ReportFileProto_Report& r = reports->Get(i);
        if (r.pkg() == pkg && r.cls() == cls) {
            reports->DeleteSubrange(i, 1);
            return;
        }
    }
}

void ReportFile::removeReports(const string& pkg) {
    RepeatedPtrField<ReportFileProto_Report>* reports = mEnvelope.mutable_report();
    const int reportCount = reports->size();
    for (int i = reportCount-1; i >= 0; i--) {
        const ReportFileProto_Report& r = reports->Get(i);
        if (r.pkg() == pkg) {
            reports->DeleteSubrange(i, 1);
        }
    }
}

void ReportFile::setMetadata(const IncidentMetadata& metadata) {
    *mEnvelope.mutable_metadata() = metadata;
}

void ReportFile::markCompleted() {
    mEnvelope.set_completed(true);
}

status_t ReportFile::markApproved(const string& pkg, const string& cls) {
    size_t const reportCount = mEnvelope.report_size();
    for (int reportIndex = 0; reportIndex < reportCount; reportIndex++) {
        ReportFileProto_Report* report = mEnvelope.mutable_report(reportIndex);
        if (report->pkg() == pkg && report->cls() == cls) {
            report->set_share_approved(true);
            return NO_ERROR;
        }
    }
    return NAME_NOT_FOUND;
}

void ReportFile::setMaxPersistedPrivacyPolicy(int persistedPrivacyPolicy) {
    mEnvelope.set_privacy_policy(persistedPrivacyPolicy);
}

status_t ReportFile::saveEnvelope() {
    return save_envelope_impl(true);
}

status_t ReportFile::trySaveEnvelope() {
    return save_envelope_impl(false);
}

status_t ReportFile::loadEnvelope() {
    return load_envelope_impl(true);
}

status_t ReportFile::tryLoadEnvelope() {
    return load_envelope_impl(false);
}

const ReportFileProto& ReportFile::getEnvelope() {
    return mEnvelope;
}

status_t ReportFile::startWritingDataFile() {
    if (mDataFd >= 0) {
        ALOGW("ReportFile::startWritingDataFile called with the file already open: %s",
                mDataFileName.c_str());
        return ALREADY_EXISTS;
    }
    mDataFd = open(mDataFileName.c_str(), O_CREAT | O_TRUNC | O_RDWR | O_CLOEXEC, 0660);
    if (mDataFd < 0) {
        return -errno;
    }
    return NO_ERROR;
}

void ReportFile::closeDataFile() {
    if (mDataFd >= 0) {
        mEnvelope.set_data_file_size(lseek(mDataFd, 0, SEEK_END));
        close(mDataFd);
        mDataFd = -1;
    }
}

status_t ReportFile::startFilteringData(int writeFd, const IncidentReportArgs& args) {
    // Open data file.
    int dataFd = open(mDataFileName.c_str(), O_RDONLY | O_CLOEXEC);
    if (dataFd < 0) {
        ALOGW("Error opening incident report '%s' %s", getDataFileName().c_str(), strerror(-errno));
        close(writeFd);
        return -errno;
    }

    // Check that the size on disk is what we thought we wrote.
    struct stat st;
    if (fstat(dataFd, &st) != 0) {
        ALOGW("Error running fstat incident report '%s' %s", getDataFileName().c_str(),
              strerror(-errno));
        close(writeFd);
        return -errno;
    }
    if (st.st_size != mEnvelope.data_file_size()) {
        ALOGW("File size mismatch. Envelope says %" PRIi64 " bytes but data file is %" PRIi64
              " bytes: %s",
              (int64_t)mEnvelope.data_file_size(), st.st_size, mDataFileName.c_str());
        ALOGW("Removing incident report");
        mWorkDirectory->remove(this);
        close(writeFd);
        return BAD_VALUE;
    }

    status_t err;

    for (const auto& report : mEnvelope.report()) {
        for (const auto& header : report.header()) {
           write_header_section(writeFd,
               reinterpret_cast<const uint8_t*>(header.c_str()), header.size());
        }
    }

    if (mEnvelope.has_metadata()) {
        write_section(writeFd, FIELD_ID_INCIDENT_METADATA, mEnvelope.metadata());
    }

    err = filter_and_write_report(writeFd, dataFd, mEnvelope.privacy_policy(), args);
    if (err != NO_ERROR) {
        ALOGW("Error writing incident report '%s' to dropbox: %s", getDataFileName().c_str(),
                strerror(-err));
    }

    close(writeFd);
    return NO_ERROR;
}

string ReportFile::getDataFileName() const {
    return mDataFileName;
}

string ReportFile::getEnvelopeFileName() const {
    return mEnvelopeFileName;
}

int ReportFile::getDataFileFd() {
    return mDataFd;
}

void ReportFile::setWriteError(status_t err) {
    mError = err;
}

status_t ReportFile::getWriteError() {
    return mError;
}

string ReportFile::getId() {
    return to_string(mTimestampNs);
}

status_t ReportFile::save_envelope_impl(bool cleanup) {
    status_t err;
    err = write_proto(mEnvelope, mEnvelopeFileName);
    if (err != NO_ERROR) {
        // If there was an error writing the envelope, then delete the whole thing.
        if (cleanup) {
            mWorkDirectory->remove(this);
        }
        return err;
    }
    return NO_ERROR;
}

status_t ReportFile::load_envelope_impl(bool cleanup) {
    status_t err;
    err = read_proto(&mEnvelope, mEnvelopeFileName);
    if (err != NO_ERROR) {
        // If there was an error reading the envelope, then delete the whole thing.
        if (cleanup) {
            mWorkDirectory->remove(this);
        }
        return err;
    }
    return NO_ERROR;
}



// ================================================================================
//

WorkDirectory::WorkDirectory()
        :mDirectory("/data/misc/incidents"),
         mMaxFileCount(100),
         mMaxDiskUsageBytes(100 * 1024 * 1024) {  // Incident reports can take up to 100MB on disk.
                                                 // TODO: Should be a flag.
    create_directory(mDirectory.c_str());
}

WorkDirectory::WorkDirectory(const string& dir, int maxFileCount, long maxDiskUsageBytes)
        :mDirectory(dir),
         mMaxFileCount(maxFileCount),
         mMaxDiskUsageBytes(maxDiskUsageBytes) {
    create_directory(mDirectory.c_str());
}

sp<ReportFile> WorkDirectory::createReportFile() {
    unique_lock<mutex> lock(mLock);
    status_t err;

    clean_directory_locked();

    int64_t timestampNs = make_timestamp_ns_locked();
    string envelopeFileName = make_filename(timestampNs, EXTENSION_ENVELOPE);
    string dataFileName = make_filename(timestampNs, EXTENSION_DATA);

    sp<ReportFile> result = new ReportFile(this, timestampNs, envelopeFileName, dataFileName);

    err = result->trySaveEnvelope();
    if (err != NO_ERROR) {
        ALOGW("Can't save envelope file %s: %s", strerror(-errno), envelopeFileName.c_str());
        return nullptr;
    }

    return result;
}

status_t WorkDirectory::getReports(vector<sp<ReportFile>>* result, int64_t after) {
    unique_lock<mutex> lock(mLock);

    const bool DBG = true;

    if (DBG) {
        ALOGD("WorkDirectory::getReports");
    }

    map<string,WorkDirectoryEntry> files;
    get_directory_contents_locked(&files, after);
    for (map<string,WorkDirectoryEntry>::iterator it = files.begin();
            it != files.end(); it++) {
        sp<ReportFile> reportFile = new ReportFile(this, it->second.timestampNs,
                it->second.envelope, it->second.data);
        if (DBG) {
            ALOGD("  %s", reportFile->getId().c_str());
        }
        result->push_back(reportFile);
    }
    return NO_ERROR;
}

sp<ReportFile> WorkDirectory::getReport(const string& pkg, const string& cls, const string& id,
            IncidentReportArgs* args) {
    unique_lock<mutex> lock(mLock);

    status_t err;
    int64_t timestampNs;
    if (!parse_timestamp_ns(id, &timestampNs)) {
        return nullptr;
    }

    // Make the ReportFile object, and then see if it's valid and for pkg and cls.
    sp<ReportFile> result = new ReportFile(this, timestampNs,
            make_filename(timestampNs, EXTENSION_ENVELOPE),
            make_filename(timestampNs, EXTENSION_DATA));

    err = result->tryLoadEnvelope();
    if (err != NO_ERROR) {
        ALOGW("Can't open envelope file for report %s/%s %s", pkg.c_str(), cls.c_str(), id.c_str());
        return nullptr;
    }

    const ReportFileProto& envelope = result->getEnvelope();
    const size_t reportCount = envelope.report_size();
    for (int i = 0; i < reportCount; i++) {
        const ReportFileProto_Report& report = envelope.report(i);
        if (report.pkg() == pkg && report.cls() == cls) {
            if (args != nullptr) {
                get_args_from_report(args, report);
            }
            return result;
        }

    }

    return nullptr;
}

bool WorkDirectory::hasMore(int64_t after) {
    unique_lock<mutex> lock(mLock);

    map<string,WorkDirectoryEntry> files;
    get_directory_contents_locked(&files, after);
    return files.size() > 0;
}

void WorkDirectory::commit(const sp<ReportFile>& report, const string& pkg, const string& cls) {
    status_t err;
    ALOGI("Committing report %s for %s/%s", report->getId().c_str(), pkg.c_str(), cls.c_str());

    unique_lock<mutex> lock(mLock);

    // Load the envelope here inside the lock.
    err = report->loadEnvelope();

    report->removeReport(pkg, cls);

    delete_files_for_report_if_necessary(report);
}

void WorkDirectory::commitAll(const string& pkg) {
    status_t err;
    ALOGI("All reports for %s", pkg.c_str());

    unique_lock<mutex> lock(mLock);

    map<string,WorkDirectoryEntry> files;
    get_directory_contents_locked(&files, 0);
    
    for (map<string,WorkDirectoryEntry>::iterator it = files.begin();
            it != files.end(); it++) {
        sp<ReportFile> reportFile = new ReportFile(this, it->second.timestampNs,
                it->second.envelope, it->second.data);

        err = reportFile->loadEnvelope();
        if (err != NO_ERROR) {
            continue;
        }

        reportFile->removeReports(pkg);

        delete_files_for_report_if_necessary(reportFile);
    }
}

void WorkDirectory::remove(const sp<ReportFile>& report) {
    unique_lock<mutex> lock(mLock);
    // Set this to false to leave files around for debugging.
    if (DO_UNLINK) {
        unlink(report->getDataFileName().c_str());
        unlink(report->getEnvelopeFileName().c_str());
    }
}

int64_t WorkDirectory::make_timestamp_ns_locked() {
    // Guarantee that we don't have duplicate timestamps.
    // This is a little bit lame, but since reports are created on the
    // same thread and are kinda slow we'll seldomly actually hit the
    // condition.  The bigger risk is the clock getting reset and causing
    // a collision.  In that case, we'll just make incident reporting a
    // little bit slower.  Nobody will notice if we just loop until we
    // have a unique file name.
    int64_t timestampNs = 0;
    do {
        struct timespec spec;
        if (timestampNs > 0) {
            spec.tv_sec = 0;
            spec.tv_nsec = 1;
            nanosleep(&spec, nullptr);
        }
        clock_gettime(CLOCK_REALTIME, &spec);
        timestampNs = (spec.tv_sec) * 1000 + spec.tv_nsec;
    } while (file_exists_locked(timestampNs));
    return timestampNs;
}

/**
 * It is required to hold the lock here so in case someone else adds it
 * our result is still correct for the caller.
 */
bool WorkDirectory::file_exists_locked(int64_t timestampNs) {
    const string filename = make_filename(timestampNs, EXTENSION_ENVELOPE);
    struct stat st;
    return stat(filename.c_str(), &st) == 0;
}

string WorkDirectory::make_filename(int64_t timestampNs, const string& extension) {
    // Zero pad the timestamp so it can also be alpha sorted.
    stringstream result;
    result << mDirectory << '/' << setfill('0') << setw(20) << timestampNs << extension;
    return result.str();
}

off_t WorkDirectory::get_directory_contents_locked(map<string,WorkDirectoryEntry>* files,
        int64_t after) {
    DIR* dir;
    struct dirent* entry;

    if ((dir = opendir(mDirectory.c_str())) == NULL) {
        ALOGE("Couldn't open incident directory: %s", mDirectory.c_str());
        return -1;
    }

    string dirbase(mDirectory);
    if (mDirectory[dirbase.size() - 1] != '/') dirbase += "/";

    off_t totalSize = 0;

    // Enumerate, count and add up size
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        string entryname = entry->d_name;  // local to this dir
        string filename = dirbase + entryname;  // fully qualified

        bool isEnvelope = ends_with(entryname, EXTENSION_ENVELOPE);
        bool isData = ends_with(entryname, EXTENSION_DATA);

        // If the file isn't one of our files, just ignore it.  Otherwise,
        // sum up the sizes.
        if (isEnvelope || isData) {
            string timestamp = strip_extension(entryname);

            int64_t timestampNs;
            if (!parse_timestamp_ns(timestamp, &timestampNs)) {
                continue;
            }

            if (after == 0 || timestampNs > after) {
                struct stat st;
                if (stat(filename.c_str(), &st) != 0) {
                    ALOGE("Unable to stat file %s", filename.c_str());
                    continue;
                }
                if (!S_ISREG(st.st_mode)) {
                    continue;
                }

                WorkDirectoryEntry& entry = (*files)[timestamp];
                if (isEnvelope) {
                    entry.envelope = filename;
                } else if (isData) {
                    entry.data = filename;
                }
                entry.timestampNs = timestampNs;
                entry.size += st.st_size;
                totalSize += st.st_size;
            }
        }
    }

    closedir(dir);

    // Now check if there are any data files that don't have envelope files.
    // If there are, then just go ahead and delete them now.  Don't wait for
    // a cleaning.

    if (DO_UNLINK) {
        map<string,WorkDirectoryEntry>::iterator it = files->begin();
        while (it != files->end()) {
            if (it->second.envelope.length() == 0) {
                unlink(it->second.data.c_str());
                it = files->erase(it);
            } else {
                it++;
            }
        }
    }

    return totalSize;
}

void WorkDirectory::clean_directory_locked() {
    DIR* dir;
    struct dirent* entry;
    struct stat st;

    // Map of filename without extension to the entries about it.  Conveniently,
    // this also keeps the list sorted by filename, which is a timestamp.
    map<string,WorkDirectoryEntry> files;
    off_t totalSize = get_directory_contents_locked(&files, 0);
    if (totalSize < 0) {
        return;
    }
    int totalCount = files.size();

    // Count or size is less than max, then we're done.
    if (totalSize < mMaxDiskUsageBytes && totalCount < mMaxFileCount) {
        return;
    }

    // Remove files until we're under our limits.
    if (DO_UNLINK) {
        for (map<string, WorkDirectoryEntry>::const_iterator it = files.begin();
                it != files.end() && (totalSize >= mMaxDiskUsageBytes
                    || totalCount >= mMaxFileCount);
                it++) {
            unlink(it->second.envelope.c_str());
            unlink(it->second.data.c_str());
            totalSize -= it->second.size;
            totalCount--;
        }
    }
}

void WorkDirectory::delete_files_for_report_if_necessary(const sp<ReportFile>& report) {
    if (report->getEnvelope().report_size() == 0) {
        ALOGI("Report %s is finished. Deleting from storage.", report->getId().c_str());
        if (DO_UNLINK) {
            unlink(report->getDataFileName().c_str());
            unlink(report->getEnvelopeFileName().c_str());
        }
    }
}

// ================================================================================
void get_args_from_report(IncidentReportArgs* out, const ReportFileProto_Report& report) {
    out->setPrivacyPolicy(report.privacy_policy());
    out->setAll(report.all_sections());
    out->setReceiverPkg(report.pkg());
    out->setReceiverCls(report.cls());

    const int sectionCount = report.section_size();
    for (int i = 0; i < sectionCount; i++) {
        out->addSection(report.section(i));
    }

    const int headerCount = report.header_size();
    for (int i = 0; i < headerCount; i++) {
        const string& header  = report.header(i);
        vector<uint8_t> vec(header.begin(), header.end());
        out->addHeader(vec);
    }
}


}  // namespace incidentd
}  // namespace os
}  // namespace android

