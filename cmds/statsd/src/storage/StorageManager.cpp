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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "android-base/stringprintf.h"
#include "guardrail/StatsdStats.h"
#include "storage/StorageManager.h"
#include "stats_log_util.h"

#include <android-base/file.h>
#include <dirent.h>
#include <private/android_filesystem_config.h>
#include <fstream>
#include <iostream>

namespace android {
namespace os {
namespace statsd {

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_MESSAGE;
using std::map;

#define STATS_DATA_DIR "/data/misc/stats-data"
#define STATS_SERVICE_DIR "/data/misc/stats-service"

// for ConfigMetricsReportList
const int FIELD_ID_REPORTS = 2;

using android::base::StringPrintf;
using std::unique_ptr;

// Returns array of int64_t which contains timestamp in seconds, uid, and
// configID.
static void parseFileName(char* name, int64_t* result) {
    int index = 0;
    char* substr = strtok(name, "_");
    while (substr != nullptr && index < 3) {
        result[index] = StrToInt64(substr);
        index++;
        substr = strtok(nullptr, "_");
    }
    // When index ends before hitting 3, file name is corrupted. We
    // intentionally put -1 at index 0 to indicate the error to caller.
    // TODO: consider removing files with unexpected name format.
    if (index < 3) {
        result[0] = -1;
    }
}

static string getFilePath(const char* path, int64_t timestamp, int64_t uid, int64_t configID) {
    return StringPrintf("%s/%lld_%d_%lld", path, (long long)timestamp, (int)uid,
                        (long long)configID);
}

void StorageManager::writeFile(const char* file, const void* buffer, int numBytes) {
    int fd = open(file, O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR);
    if (fd == -1) {
        VLOG("Attempt to access %s but failed", file);
        return;
    }
    trimToFit(STATS_SERVICE_DIR);
    trimToFit(STATS_DATA_DIR);

    int result = write(fd, buffer, numBytes);
    if (result == numBytes) {
        VLOG("Successfully wrote %s", file);
    } else {
        VLOG("Failed to write %s", file);
    }

    result = fchown(fd, AID_STATSD, AID_STATSD);
    if (result) {
        VLOG("Failed to chown %s to statsd", file);
    }

    close(fd);
}

void StorageManager::deleteFile(const char* file) {
    if (remove(file) != 0) {
        VLOG("Attempt to delete %s but is not found", file);
    } else {
        VLOG("Successfully deleted %s", file);
    }
}

void StorageManager::deleteAllFiles(const char* path) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(path), closedir);
    if (dir == NULL) {
        VLOG("Directory does not exist: %s", path);
        return;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;
        deleteFile(StringPrintf("%s/%s", path, name).c_str());
    }
}

void StorageManager::deleteSuffixedFiles(const char* path, const char* suffix) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(path), closedir);
    if (dir == NULL) {
        VLOG("Directory does not exist: %s", path);
        return;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') {
            continue;
        }
        size_t nameLen = strlen(name);
        size_t suffixLen = strlen(suffix);
        if (suffixLen <= nameLen && strncmp(name + nameLen - suffixLen, suffix, suffixLen) == 0) {
            deleteFile(StringPrintf("%s/%s", path, name).c_str());
        }
    }
}

void StorageManager::sendBroadcast(const char* path,
                                   const std::function<void(const ConfigKey&)>& sendBroadcast) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(path), closedir);
    if (dir == NULL) {
        VLOG("no stats-data directory on disk");
        return;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;
        VLOG("file %s", name);

        int64_t result[3];
        parseFileName(name, result);
        if (result[0] == -1) continue;
        int64_t uid = result[1];
        int64_t configID = result[2];

        sendBroadcast(ConfigKey((int)uid, configID));
    }
}

bool StorageManager::hasConfigMetricsReport(const ConfigKey& key) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(STATS_DATA_DIR), closedir);
    if (dir == NULL) {
        VLOG("Path %s does not exist", STATS_DATA_DIR);
        return false;
    }

    string suffix = StringPrintf("%d_%lld", key.GetUid(), (long long)key.GetId());

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;

        size_t nameLen = strlen(name);
        size_t suffixLen = suffix.length();
        if (suffixLen <= nameLen &&
            strncmp(name + nameLen - suffixLen, suffix.c_str(), suffixLen) == 0) {
            // Check again that the file name is parseable.
            int64_t result[3];
            parseFileName(name, result);
            if (result[0] == -1) continue;
            return true;
        }
    }
    return false;
}

void StorageManager::appendConfigMetricsReport(const ConfigKey& key, ProtoOutputStream* proto) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(STATS_DATA_DIR), closedir);
    if (dir == NULL) {
        VLOG("Path %s does not exist", STATS_DATA_DIR);
        return;
    }

    string suffix = StringPrintf("%d_%lld", key.GetUid(), (long long)key.GetId());

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;

        size_t nameLen = strlen(name);
        size_t suffixLen = suffix.length();
        if (suffixLen <= nameLen &&
            strncmp(name + nameLen - suffixLen, suffix.c_str(), suffixLen) == 0) {
            int64_t result[3];
            parseFileName(name, result);
            if (result[0] == -1) continue;
            int64_t timestamp = result[0];
            int64_t uid = result[1];
            int64_t configID = result[2];

            string file_name = getFilePath(STATS_DATA_DIR, timestamp, uid, configID);
            int fd = open(file_name.c_str(), O_RDONLY | O_CLOEXEC);
            if (fd != -1) {
                string content;
                if (android::base::ReadFdToString(fd, &content)) {
                    proto->write(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_REPORTS,
                                content.c_str(), content.size());
                }
                close(fd);
            }

            // Remove file from disk after reading.
            remove(file_name.c_str());
        }
    }
}

bool StorageManager::readFileToString(const char* file, string* content) {
    int fd = open(file, O_RDONLY | O_CLOEXEC);
    bool res = false;
    if (fd != -1) {
        if (android::base::ReadFdToString(fd, content)) {
            res = true;
        } else {
            VLOG("Failed to read file %s\n", file);
        }
        close(fd);
    }
    return res;
}

void StorageManager::readConfigFromDisk(map<ConfigKey, StatsdConfig>& configsMap) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(STATS_SERVICE_DIR), closedir);
    if (dir == NULL) {
        VLOG("no default config on disk");
        return;
    }
    trimToFit(STATS_SERVICE_DIR);

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;
        VLOG("file %s", name);

        int64_t result[3];
        parseFileName(name, result);
        if (result[0] == -1) continue;
        int64_t timestamp = result[0];
        int64_t uid = result[1];
        int64_t configID = result[2];
        string file_name = getFilePath(STATS_SERVICE_DIR, timestamp, uid, configID);
        int fd = open(file_name.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd != -1) {
            string content;
            if (android::base::ReadFdToString(fd, &content)) {
                StatsdConfig config;
                if (config.ParseFromString(content)) {
                    configsMap[ConfigKey(uid, configID)] = config;
                    VLOG("map key uid=%lld|configID=%lld", (long long)uid, (long long)configID);
                }
            }
            close(fd);
        }
    }
}

bool StorageManager::readConfigFromDisk(const ConfigKey& key, StatsdConfig* config) {
    string content;
    return config != nullptr &&
        StorageManager::readConfigFromDisk(key, &content) && config->ParseFromString(content);
}

bool StorageManager::readConfigFromDisk(const ConfigKey& key, string* content) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(STATS_SERVICE_DIR),
                                             closedir);
    if (dir == NULL) {
        VLOG("Directory does not exist: %s", STATS_SERVICE_DIR);
        return false;
    }

    string suffix = StringPrintf("%d_%lld", key.GetUid(), (long long)key.GetId());
    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') {
            continue;
        }
        size_t nameLen = strlen(name);
        size_t suffixLen = suffix.length();
        // There can be at most one file that matches this suffix (config key).
        if (suffixLen <= nameLen &&
            strncmp(name + nameLen - suffixLen, suffix.c_str(), suffixLen) == 0) {
            int fd = open(StringPrintf("%s/%s", STATS_SERVICE_DIR, name).c_str(),
                                  O_RDONLY | O_CLOEXEC);
            if (fd != -1) {
                if (android::base::ReadFdToString(fd, content)) {
                    return true;
                }
                close(fd);
            }
        }
    }
    return false;
}

bool StorageManager::hasIdenticalConfig(const ConfigKey& key,
                                        const vector<uint8_t>& config) {
    string content;
    if (StorageManager::readConfigFromDisk(key, &content)) {
        vector<uint8_t> vec(content.begin(), content.end());
        if (vec == config) {
            return true;
        }
    }
    return false;
}

void StorageManager::trimToFit(const char* path) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(path), closedir);
    if (dir == NULL) {
        VLOG("Path %s does not exist", path);
        return;
    }
    dirent* de;
    int totalFileSize = 0;
    vector<string> fileNames;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;

        int64_t result[3];
        parseFileName(name, result);
        if (result[0] == -1) continue;
        int64_t timestamp = result[0];
        int64_t uid = result[1];
        int64_t configID = result[2];
        string file_name = getFilePath(path, timestamp, uid, configID);

        // Check for timestamp and delete if it's too old.
        long fileAge = getWallClockSec() - timestamp;
        if (fileAge > StatsdStats::kMaxAgeSecond) {
            deleteFile(file_name.c_str());
        }

        fileNames.push_back(file_name);
        ifstream file(file_name.c_str(), ifstream::in | ifstream::binary);
        if (file.is_open()) {
            file.seekg(0, ios::end);
            int fileSize = file.tellg();
            file.close();
            totalFileSize += fileSize;
        }
    }

    if (fileNames.size() > StatsdStats::kMaxFileNumber ||
        totalFileSize > StatsdStats::kMaxFileSize) {
        // Reverse sort to effectively remove from the back (oldest entries).
        // This will sort files in reverse-chronological order.
        sort(fileNames.begin(), fileNames.end(), std::greater<std::string>());
    }

    // Start removing files from oldest to be under the limit.
    while (fileNames.size() > 0 && (fileNames.size() > StatsdStats::kMaxFileNumber ||
                                    totalFileSize > StatsdStats::kMaxFileSize)) {
        string file_name = fileNames.at(fileNames.size() - 1);
        ifstream file(file_name.c_str(), ifstream::in | ifstream::binary);
        if (file.is_open()) {
            file.seekg(0, ios::end);
            int fileSize = file.tellg();
            file.close();
            totalFileSize -= fileSize;
        }

        deleteFile(file_name.c_str());
        fileNames.pop_back();
    }
}

void StorageManager::printStats(FILE* out) {
    printDirStats(out, STATS_SERVICE_DIR);
    printDirStats(out, STATS_DATA_DIR);
}

void StorageManager::printDirStats(FILE* out, const char* path) {
    fprintf(out, "Printing stats of %s\n", path);
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(path), closedir);
    if (dir == NULL) {
        VLOG("Path %s does not exist", path);
        return;
    }
    dirent* de;
    int fileCount = 0;
    int totalFileSize = 0;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') {
            continue;
        }
        int64_t result[3];
        parseFileName(name, result);
        if (result[0] == -1) continue;
        int64_t timestamp = result[0];
        int64_t uid = result[1];
        int64_t configID = result[2];
        fprintf(out, "\t #%d, Last updated: %lld, UID: %d, Config ID: %lld",
                fileCount + 1,
                (long long)timestamp,
                (int)uid,
                (long long)configID);
        string file_name = getFilePath(path, timestamp, uid, configID);
        ifstream file(file_name.c_str(), ifstream::in | ifstream::binary);
        if (file.is_open()) {
            file.seekg(0, ios::end);
            int fileSize = file.tellg();
            file.close();
            fprintf(out, ", File Size: %d bytes", fileSize);
            totalFileSize += fileSize;
        }
        fprintf(out, "\n");
        fileCount++;
    }
    fprintf(out, "\tTotal number of files: %d, Total size of files: %d bytes.\n",
            fileCount, totalFileSize);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
