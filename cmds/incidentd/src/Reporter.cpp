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

#include "Privacy.h"
#include "report_directory.h"
#include "section_list.h"

#include <android-base/properties.h>
#include <android/os/DropBoxManager.h>
#include <private/android_filesystem_config.h>
#include <utils/SystemClock.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <string>

/**
 * The directory where the incident reports are stored.
 */
static const char* INCIDENT_DIRECTORY = "/data/misc/incidents/";

namespace android {
namespace os {
namespace incidentd {

// ================================================================================
ReportRequest::ReportRequest(const IncidentReportArgs& a,
                             const sp<IIncidentReportStatusListener>& l, int f)
    : args(a), listener(l), fd(f), err(NO_ERROR) {}

ReportRequest::~ReportRequest() {
    if (fd >= 0) {
        // clean up the opened file descriptor
        close(fd);
    }
}

bool ReportRequest::ok() { return fd >= 0 && err == NO_ERROR; }

// ================================================================================
ReportRequestSet::ReportRequestSet()
    : mRequests(), mSections(), mMainFd(-1), mMainDest(-1), mMetadata(), mSectionStats() {}

ReportRequestSet::~ReportRequestSet() {}

// TODO: dedup on exact same args and fd, report the status back to listener!
void ReportRequestSet::add(const sp<ReportRequest>& request) {
    mRequests.push_back(request);
    mSections.merge(request->args);
    mMetadata.set_request_size(mMetadata.request_size() + 1);
}

void ReportRequestSet::setMainFd(int fd) {
    mMainFd = fd;
    mMetadata.set_use_dropbox(fd > 0);
}

void ReportRequestSet::setMainDest(int dest) {
    mMainDest = dest;
    PrivacySpec spec = PrivacySpec::new_spec(dest);
    switch (spec.dest) {
        case android::os::DEST_AUTOMATIC:
            mMetadata.set_dest(IncidentMetadata_Destination_AUTOMATIC);
            break;
        case android::os::DEST_EXPLICIT:
            mMetadata.set_dest(IncidentMetadata_Destination_EXPLICIT);
            break;
        case android::os::DEST_LOCAL:
            mMetadata.set_dest(IncidentMetadata_Destination_LOCAL);
            break;
    }
}

bool ReportRequestSet::containsSection(int id) { return mSections.containsSection(id); }

IncidentMetadata::SectionStats* ReportRequestSet::sectionStats(int id) {
    if (mSectionStats.find(id) == mSectionStats.end()) {
        IncidentMetadata::SectionStats stats;
        stats.set_id(id);
        mSectionStats[id] = stats;
    }
    return &mSectionStats[id];
}

// ================================================================================
Reporter::Reporter() : Reporter(INCIDENT_DIRECTORY) { isTest = false; };

Reporter::Reporter(const char* directory) : batch() {
    char buf[100];

    mMaxSize = 30 * 1024 * 1024;  // incident reports can take up to 30MB on disk
    mMaxCount = 100;

    // string ends up with '/' is a directory
    String8 dir = String8(directory);
    if (directory[dir.size() - 1] != '/') dir += "/";
    mIncidentDirectory = dir.string();

    // There can't be two at the same time because it's on one thread.
    mStartTime = time(NULL);
    strftime(buf, sizeof(buf), "incident-%Y%m%d-%H%M%S", localtime(&mStartTime));
    mFilename = mIncidentDirectory + buf;
}

Reporter::~Reporter() {}

Reporter::run_report_status_t Reporter::runReport(size_t* reportByteSize) {
    status_t err = NO_ERROR;
    bool needMainFd = false;
    int mainFd = -1;
    int mainDest = -1;
    int sectionCount = 0;
    HeaderSection headers;
    MetadataSection metadataSection;
    std::string buildType = android::base::GetProperty("ro.build.type", "");
    const bool isUserdebugOrEng = buildType == "userdebug" || buildType == "eng";

    // See if we need the main file
    for (ReportRequestSet::iterator it = batch.begin(); it != batch.end(); it++) {
        if ((*it)->fd < 0 && mainFd < 0) {
            needMainFd = true;
            mainDest = (*it)->args.dest();
            break;
        }
    }
    if (needMainFd) {
        // Create the directory
        if (!isTest) err = create_directory(mIncidentDirectory);
        if (err != NO_ERROR) {
            goto DONE;
        }

        // If there are too many files in the directory (for whatever reason),
        // delete the oldest ones until it's under the limit. Doing this first
        // does mean that we can go over, so the max size is not a hard limit.
        if (!isTest) clean_directory(mIncidentDirectory, mMaxSize, mMaxCount);

        // Open the file.
        err = create_file(&mainFd);
        if (err != NO_ERROR) {
            goto DONE;
        }

        // Add to the set
        batch.setMainFd(mainFd);
        batch.setMainDest(mainDest);
    }

    // Tell everyone that we're starting.
    for (ReportRequestSet::iterator it = batch.begin(); it != batch.end(); it++) {
        if ((*it)->listener != NULL) {
            (*it)->listener->onReportStarted();
        }
    }

    // Write the incident headers
    headers.Execute(&batch);

    // For each of the report fields, see if we need it, and if so, execute the command
    // and report to those that care that we're doing it.
    for (const Section** section = SECTION_LIST; *section; section++) {
        const int id = (*section)->id;
        if ((*section)->userdebugAndEngOnly && !isUserdebugOrEng) {
            VLOG("Skipping incident report section %d '%s' because it's limited to userdebug/eng",
                  id, (*section)->name.string());
            continue;
        }
        if (this->batch.containsSection(id)) {
            VLOG("Taking incident report section %d '%s'", id, (*section)->name.string());
            for (ReportRequestSet::iterator it = batch.begin(); it != batch.end(); it++) {
                if ((*it)->listener != NULL && (*it)->args.containsSection(id)) {
                    (*it)->listener->onReportSectionStatus(
                            id, IIncidentReportStatusListener::STATUS_STARTING);
                }
            }

            // Execute - go get the data and write it into the file descriptors.
            IncidentMetadata::SectionStats* stats = batch.sectionStats(id);
            int64_t startTime = uptimeMillis();
            err = (*section)->Execute(&batch);
            int64_t endTime = uptimeMillis();
            stats->set_exec_duration_ms(endTime - startTime);
            if (err != NO_ERROR) {
                ALOGW("Incident section %s (%d) failed: %s. Stopping report.",
                      (*section)->name.string(), id, strerror(-err));
                // Execute() has already recorded this status. Only update if there's new failure.
                stats->set_success(false);
                goto DONE;
            }
            (*reportByteSize) += stats->report_size_bytes();

            // Notify listener of starting
            for (ReportRequestSet::iterator it = batch.begin(); it != batch.end(); it++) {
                if ((*it)->listener != NULL && (*it)->args.containsSection(id)) {
                    (*it)->listener->onReportSectionStatus(
                            id, IIncidentReportStatusListener::STATUS_FINISHED);
                }
            }
            VLOG("Finish incident report section %d '%s'", id, (*section)->name.string());
            sectionCount++;
        }
    }

DONE:
    ALOGD("Incident reporting took %d sections.", sectionCount);
    // Reports the metdadata when taking the incident report.
    if (!isTest) metadataSection.Execute(&batch);

    // Close the file.
    if (mainFd >= 0) {
        close(mainFd);
    }

    // Tell everyone that we're done.
    for (ReportRequestSet::iterator it = batch.begin(); it != batch.end(); it++) {
        if ((*it)->listener != NULL) {
            if (err == NO_ERROR) {
                (*it)->listener->onReportFinished();
            } else {
                (*it)->listener->onReportFailed();
            }
        }
    }

    // Put the report into dropbox.
    if (needMainFd && err == NO_ERROR) {
        sp<DropBoxManager> dropbox = new DropBoxManager();
        Status status = dropbox->addFile(String16("incident"), mFilename, 0);
        ALOGD("Incident report done. dropbox status=%s\n", status.toString8().string());
        if (!status.isOk()) {
            return REPORT_NEEDS_DROPBOX;
        }

        // If the status was ok, delete the file. If not, leave it around until the next
        // boot or the next checkin. If the directory gets too big older files will
        // be rotated out.
        if (!isTest) unlink(mFilename.c_str());
    }

    return REPORT_FINISHED;
}

/**
 * Create our output file and set the access permissions to -rw-rw----
 */
status_t Reporter::create_file(int* fd) {
    const char* filename = mFilename.c_str();

    *fd = open(filename, O_CREAT | O_TRUNC | O_RDWR | O_CLOEXEC, 0660);
    if (*fd < 0) {
        ALOGE("Couldn't open incident file: %s (%s)", filename, strerror(errno));
        return -errno;
    }

    // Override umask. Not super critical. If it fails go on with life.
    chmod(filename, 0660);

    if (chown(filename, AID_INCIDENTD, AID_INCIDENTD)) {
        ALOGE("Unable to change ownership of incident file %s: %s\n", filename, strerror(errno));
        status_t err = -errno;
        unlink(mFilename.c_str());
        return err;
    }

    return NO_ERROR;
}

Reporter::run_report_status_t Reporter::upload_backlog() {
    DIR* dir;
    struct dirent* entry;
    struct stat st;
    status_t err;

    ALOGD("Start uploading backlogs in %s", INCIDENT_DIRECTORY);
    if ((err = create_directory(INCIDENT_DIRECTORY)) != NO_ERROR) {
        ALOGE("directory doesn't exist: %s", strerror(-err));
        return REPORT_FINISHED;
    }

    if ((dir = opendir(INCIDENT_DIRECTORY)) == NULL) {
        ALOGE("Couldn't open incident directory: %s", INCIDENT_DIRECTORY);
        return REPORT_NEEDS_DROPBOX;
    }

    sp<DropBoxManager> dropbox = new DropBoxManager();

    // Enumerate, count and add up size
    int count = 0;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        String8 filename = String8(INCIDENT_DIRECTORY) + entry->d_name;
        if (stat(filename.string(), &st) != 0) {
            ALOGE("Unable to stat file %s", filename.string());
            continue;
        }
        if (!S_ISREG(st.st_mode)) {
            continue;
        }

        Status status = dropbox->addFile(String16("incident"), filename.string(), 0);
        ALOGD("Incident report done. dropbox status=%s\n", status.toString8().string());
        if (!status.isOk()) {
            return REPORT_NEEDS_DROPBOX;
        }

        // If the status was ok, delete the file. If not, leave it around until the next
        // boot or the next checkin. If the directory gets too big older files will
        // be rotated out.
        unlink(filename.string());
        count++;
    }
    ALOGD("Successfully uploaded %d files to Dropbox.", count);
    closedir(dir);

    return REPORT_FINISHED;
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
