/*
 * Copyright (C) 2017 The Android Open Source Project
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
#include <android/os/DropBoxManager.h>
#include <android-base/file.h>
#include <cutils/log.h>
#include <androidfw/ZipUtils.h>
#include <stdio.h>

#include "DropboxReader.h"

using android::sp;
using android::String16;
using android::binder::Status;
using android::base::unique_fd;
using android::os::DropBoxManager;
using android::os::statsd::StatsLogEntry;
using android::ZipUtils;
using std::vector;

status_t DropboxReader::readStatsLogs(FILE* out, const string& tag, long msec) {
    sp<DropBoxManager> dropbox = new DropBoxManager();
    StatsLogList logList;

    long timestamp = msec;
    // instead of while(true), put a hard limit 1000. Dropbox won't have more than 1000 files.
    for(int i = 0; i < 1000; i++ ) {
        DropBoxManager::Entry entry;
        Status status = dropbox->getNextEntry(String16(tag.c_str()),
                timestamp, &entry);
        if (!status.isOk()) {
            ALOGD("No more entries, or failed to read. We can't tell unfortunately.");
            return android::OK;
        }

        const unique_fd& fd = entry.getFd();

        // use this timestamp for next query.
        timestamp = entry.getTimestamp();

        if (entry.getFlags() & DropBoxManager::IS_GZIPPED) {
            if (!parseFromGzipFile(fd, logList)) {
                // Failed to parse from the file. Continue to fetch the next entry.
                continue;
            }
        } else {
            if (!parseFromFile(fd, logList)) {
                // Failed to parse from the file. Continue to fetch the next entry.
                continue;
            }
        }

        printLog(out, logList);
    }
    return android::OK;
}

bool DropboxReader::parseFromGzipFile(const unique_fd& fd, StatsLogList& list) {
    FILE *file = fdopen(fd, "r");
    bool result = false;
    bool scanResult;
    int method;
    long compressedLen;
    long uncompressedLen;
    unsigned long crc32;
    scanResult = ZipUtils::examineGzip(file, &method, &uncompressedLen,
            &compressedLen, &crc32);
    if (scanResult && method == kCompressDeflated) {
        vector<uint8_t> buf(uncompressedLen);
        if (ZipUtils::inflateToBuffer(file, &buf[0], uncompressedLen, compressedLen)) {
            if (list.ParseFromArray(&buf[0], uncompressedLen)) {
                result = true;
            }
        }
    } else {
        ALOGE("This isn't a valid deflated gzip file");
    }
    fclose(file);
    return result;
}

// parse a non zipped file.
bool DropboxReader::parseFromFile(const unique_fd& fd, StatsLogList& list) {
    string content;
    if (!android::base::ReadFdToString(fd, &content)) {
        ALOGE("Failed to read file");
        return false;
    }
    if (!list.ParseFromString(content)) {
        ALOGE("failed to parse log entry from data");
        return false;
    }
    return true;
}

void DropboxReader::printLog(FILE* out, const StatsLogList& list) {
    for (int i = 0; i < list.stats_log_entry_size(); i++) {
        const StatsLogEntry entry = list.stats_log_entry(i);
        // TODO: print pretty
        fprintf(out, "time_msec=%lld, type=%d, aggregate_type=%d, uid=%d, pid=%d ",
                entry.start_report_millis(), entry.type(), entry.aggregate_type(),
                entry.uid(), entry.pid());
        for (int j = 0; j < entry.pairs_size(); j++) {
            fprintf(out, "msg=%s ", entry.pairs(j).value_str().c_str());
        }
        fprintf(out, "\n");
    }
}
