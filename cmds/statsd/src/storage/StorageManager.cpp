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
#include <private/android_filesystem_config.h>
#include <fstream>

namespace android {
namespace os {
namespace statsd {

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_MESSAGE;
using std::map;

/**
 * NOTE: these directories are protected by SELinux, any changes here must also update
 * the SELinux policies.
 */
#define STATS_DATA_DIR "/data/misc/stats-data"
#define STATS_SERVICE_DIR "/data/misc/stats-service"
#define TRAIN_INFO_DIR "/data/misc/train-info"
#define TRAIN_INFO_PATH "/data/misc/train-info/train-info.bin"

// Magic word at the start of the train info file, change this if changing the file format
const uint32_t TRAIN_INFO_FILE_MAGIC = 0xfb7447bf;

// for ConfigMetricsReportList
const int FIELD_ID_REPORTS = 2;

std::mutex StorageManager::sTrainInfoMutex;

using android::base::StringPrintf;
using std::unique_ptr;

struct FileName {
    int64_t mTimestampSec;
    int mUid;
    int64_t mConfigId;
    bool mIsHistory;
    string getFullFileName(const char* path) {
        return StringPrintf("%s/%lld_%d_%lld%s", path, (long long)mTimestampSec, (int)mUid,
                            (long long)mConfigId, (mIsHistory ? "_history" : ""));
    };
};

string StorageManager::getDataFileName(long wallClockSec, int uid, int64_t id) {
    return StringPrintf("%s/%ld_%d_%lld", STATS_DATA_DIR, wallClockSec, uid,
                        (long long)id);
}

string StorageManager::getDataHistoryFileName(long wallClockSec, int uid, int64_t id) {
    return StringPrintf("%s/%ld_%d_%lld_history", STATS_DATA_DIR, wallClockSec, uid,
                        (long long)id);
}

static string findTrainInfoFileNameLocked(const string& trainName) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(TRAIN_INFO_DIR), closedir);
    if (dir == NULL) {
        VLOG("Path %s does not exist", TRAIN_INFO_DIR);
        return "";
    }
    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* fileName = de->d_name;
        if (fileName[0] == '.') continue;

        size_t fileNameLength = strlen(fileName);
        if (fileNameLength >= trainName.length()) {
            if (0 == strncmp(fileName + fileNameLength - trainName.length(), trainName.c_str(),
                             trainName.length())) {
              return string(fileName);
            }
        }
    }

    return "";
}

// Returns array of int64_t which contains timestamp in seconds, uid,
// configID and whether the file is a local history file.
static void parseFileName(char* name, FileName* output) {
    int64_t result[3];
    int index = 0;
    char* substr = strtok(name, "_");
    while (substr != nullptr && index < 3) {
        result[index] = StrToInt64(substr);
        index++;
        substr = strtok(nullptr, "_");
    }
    // When index ends before hitting 3, file name is corrupted. We
    // intentionally put -1 at index 0 to indicate the error to caller.
    // TODO(b/110563137): consider removing files with unexpected name format.
    if (index < 3) {
        result[0] = -1;
    }

    output->mTimestampSec = result[0];
    output->mUid = result[1];
    output->mConfigId = result[2];
    // check if the file is a local history.
    output->mIsHistory = (substr != nullptr && strcmp("history", substr) == 0);
}

void StorageManager::writeFile(const char* file, const void* buffer, int numBytes) {
    int fd = open(file, O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR);
    if (fd == -1) {
        VLOG("Attempt to access %s but failed", file);
        return;
    }
    trimToFit(STATS_SERVICE_DIR);
    trimToFit(STATS_DATA_DIR);

    if (android::base::WriteFully(fd, buffer, numBytes)) {
        VLOG("Successfully wrote %s", file);
    } else {
        ALOGE("Failed to write %s", file);
    }

    int result = fchown(fd, AID_STATSD, AID_STATSD);
    if (result) {
        VLOG("Failed to chown %s to statsd", file);
    }

    close(fd);
}

bool StorageManager::writeTrainInfo(const InstallTrainInfo& trainInfo) {
    std::lock_guard<std::mutex> lock(sTrainInfoMutex);

    if (trainInfo.trainName.empty()) {
      return false;
    }
    deleteSuffixedFiles(TRAIN_INFO_DIR, trainInfo.trainName.c_str());

    std::string fileName =
            StringPrintf("%s/%ld_%s", TRAIN_INFO_DIR, (long) getWallClockSec(),
                         trainInfo.trainName.c_str());

    int fd = open(fileName.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR);
    if (fd == -1) {
        VLOG("Attempt to access %s but failed", fileName.c_str());
        return false;
    }

    size_t result;
    // Write the magic word
    result = write(fd, &TRAIN_INFO_FILE_MAGIC, sizeof(TRAIN_INFO_FILE_MAGIC));
    if (result != sizeof(TRAIN_INFO_FILE_MAGIC)) {
        VLOG("Failed to wrtie train info magic");
        close(fd);
        return false;
    }

    // Write the train version
    const size_t trainVersionCodeByteCount = sizeof(trainInfo.trainVersionCode);
    result = write(fd, &trainInfo.trainVersionCode, trainVersionCodeByteCount);
    if (result != trainVersionCodeByteCount) {
        VLOG("Failed to wrtie train version code");
        close(fd);
        return false;
    }

    // Write # of bytes in trainName to file
    const size_t trainNameSize = trainInfo.trainName.size();
    const size_t trainNameSizeByteCount = sizeof(trainNameSize);
    result = write(fd, (uint8_t*)&trainNameSize, trainNameSizeByteCount);
    if (result != trainNameSizeByteCount) {
        VLOG("Failed to write train name size");
        close(fd);
        return false;
    }

    // Write trainName to file
    result = write(fd, trainInfo.trainName.c_str(), trainNameSize);
    if (result != trainNameSize) {
        VLOG("Failed to write train name");
        close(fd);
        return false;
    }

    // Write status to file
    const size_t statusByteCount = sizeof(trainInfo.status);
    result = write(fd, (uint8_t*)&trainInfo.status, statusByteCount);
    if (result != statusByteCount) {
        VLOG("Failed to write status");
        close(fd);
        return false;
    }

    // Write experiment id count to file.
    const size_t experimentIdsCount = trainInfo.experimentIds.size();
    const size_t experimentIdsCountByteCount = sizeof(experimentIdsCount);
    result = write(fd, (uint8_t*) &experimentIdsCount, experimentIdsCountByteCount);
    if (result != experimentIdsCountByteCount) {
        VLOG("Failed to write experiment id count");
        close(fd);
        return false;
    }

    // Write experimentIds to file
    for (size_t i = 0; i < experimentIdsCount; i++) {
        const int64_t experimentId = trainInfo.experimentIds[i];
        const size_t experimentIdByteCount = sizeof(experimentId);
        result = write(fd, &experimentId, experimentIdByteCount);
        if (result == experimentIdByteCount) {
            VLOG("Successfully wrote experiment IDs");
        } else {
            VLOG("Failed to write experiment ids");
            close(fd);
            return false;
        }
    }

    // Write bools to file
    const size_t boolByteCount = sizeof(trainInfo.requiresStaging);
    result = write(fd, (uint8_t*)&trainInfo.requiresStaging, boolByteCount);
    if (result != boolByteCount) {
      VLOG("Failed to write requires staging");
      close(fd);
      return false;
    }

    result = write(fd, (uint8_t*)&trainInfo.rollbackEnabled, boolByteCount);
    if (result != boolByteCount) {
      VLOG("Failed to write rollback enabled");
      close(fd);
      return false;
    }

    result = write(fd, (uint8_t*)&trainInfo.requiresLowLatencyMonitor, boolByteCount);
    if (result != boolByteCount) {
      VLOG("Failed to write requires log latency monitor");
      close(fd);
      return false;
    }

    close(fd);
    return true;
}

bool StorageManager::readTrainInfo(const std::string& trainName, InstallTrainInfo& trainInfo) {
    std::lock_guard<std::mutex> lock(sTrainInfoMutex);
    return readTrainInfoLocked(trainName, trainInfo);
}

bool StorageManager::readTrainInfoLocked(const std::string& trainName, InstallTrainInfo& trainInfo) {
    trimToFit(TRAIN_INFO_DIR, /*parseTimestampOnly=*/ true);
    string fileName = findTrainInfoFileNameLocked(trainName);
    if (fileName.empty()) {
        return false;
    }
    int fd = open(StringPrintf("%s/%s", TRAIN_INFO_DIR, fileName.c_str()).c_str(), O_RDONLY | O_CLOEXEC);
    if (fd == -1) {
        VLOG("Failed to open %s", fileName.c_str());
        return false;
    }

    // Read the magic word
    uint32_t magic;
    size_t result = read(fd, &magic, sizeof(magic));
    if (result != sizeof(magic)) {
        VLOG("Failed to read train info magic");
        close(fd);
        return false;
    }

    if (magic != TRAIN_INFO_FILE_MAGIC) {
        VLOG("Train info magic was 0x%08x, expected 0x%08x", magic, TRAIN_INFO_FILE_MAGIC);
        close(fd);
        return false;
    }

    // Read the train version code
    const size_t trainVersionCodeByteCount(sizeof(trainInfo.trainVersionCode));
    result = read(fd, &trainInfo.trainVersionCode, trainVersionCodeByteCount);
    if (result != trainVersionCodeByteCount) {
        VLOG("Failed to read train version code from train info file");
        close(fd);
        return false;
    }

    // Read # of bytes taken by trainName in the file.
    size_t trainNameSize;
    result = read(fd, &trainNameSize, sizeof(size_t));
    if (result != sizeof(size_t)) {
        VLOG("Failed to read train name size from train info file");
        close(fd);
        return false;
    }

    // Read trainName
    trainInfo.trainName.resize(trainNameSize);
    result = read(fd, trainInfo.trainName.data(), trainNameSize);
    if (result != trainNameSize) {
        VLOG("Failed to read train name from train info file");
        close(fd);
        return false;
    }

    // Read status
    const size_t statusByteCount = sizeof(trainInfo.status);
    result = read(fd, &trainInfo.status, statusByteCount);
    if (result != statusByteCount) {
        VLOG("Failed to read train status from train info file");
        close(fd);
        return false;
    }

    // Read experiment ids count.
    size_t experimentIdsCount;
    result = read(fd, &experimentIdsCount, sizeof(size_t));
    if (result != sizeof(size_t)) {
        VLOG("Failed to read train experiment id count from train info file");
        close(fd);
        return false;
    }

    // Read experimentIds
    for (size_t i = 0; i < experimentIdsCount; i++) {
        int64_t experimentId;
        result = read(fd, &experimentId, sizeof(experimentId));
        if (result != sizeof(experimentId)) {
            VLOG("Failed to read train experiment id from train info file");
            close(fd);
            return false;
        }
        trainInfo.experimentIds.push_back(experimentId);
    }

    // Read bools
    const size_t boolByteCount = sizeof(trainInfo.requiresStaging);
    result = read(fd, &trainInfo.requiresStaging, boolByteCount);
    if (result != boolByteCount) {
        VLOG("Failed to read requires requires staging from train info file");
        close(fd);
        return false;
    }

    result = read(fd, &trainInfo.rollbackEnabled, boolByteCount);
    if (result != boolByteCount) {
        VLOG("Failed to read requires rollback enabled from train info file");
        close(fd);
        return false;
    }

    result = read(fd, &trainInfo.requiresLowLatencyMonitor, boolByteCount);
    if (result != boolByteCount) {
        VLOG("Failed to read requires requires low latency monitor from train info file");
        close(fd);
        return false;
    }

    // Expect to be at EOF.
    char c;
    result = read(fd, &c, 1);
    if (result != 0) {
        VLOG("Failed to read train info from file. Did not get expected EOF.");
        close(fd);
        return false;
    }

    VLOG("Read train info file successful");
    close(fd);
    return true;
}

vector<InstallTrainInfo> StorageManager::readAllTrainInfo() {
    std::lock_guard<std::mutex> lock(sTrainInfoMutex);
    vector<InstallTrainInfo> trainInfoList;
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(TRAIN_INFO_DIR), closedir);
    if (dir == NULL) {
        VLOG("Directory does not exist: %s", TRAIN_INFO_DIR);
        return trainInfoList;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') {
            continue;
        }

        InstallTrainInfo trainInfo;
        bool readSuccess = StorageManager::readTrainInfoLocked(name, trainInfo);
        if (!readSuccess) {
            continue;
        }
        trainInfoList.push_back(trainInfo);
    }
    return trainInfoList;
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

        FileName output;
        parseFileName(name, &output);
        if (output.mTimestampSec == -1 || output.mIsHistory) continue;
        sendBroadcast(ConfigKey((int)output.mUid, output.mConfigId));
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
            FileName output;
            parseFileName(name, &output);
            if (output.mTimestampSec == -1 || output.mIsHistory) continue;
            return true;
        }
    }
    return false;
}

void StorageManager::appendConfigMetricsReport(const ConfigKey& key, ProtoOutputStream* proto,
                                               bool erase_data, bool isAdb) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(STATS_DATA_DIR), closedir);
    if (dir == NULL) {
        VLOG("Path %s does not exist", STATS_DATA_DIR);
        return;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        string fileName(name);
        if (name[0] == '.') continue;
        FileName output;
        parseFileName(name, &output);

        if (output.mTimestampSec == -1 || (output.mIsHistory && !isAdb) ||
            output.mUid != key.GetUid() || output.mConfigId != key.GetId()) {
            continue;
        }

        auto fullPathName = StringPrintf("%s/%s", STATS_DATA_DIR, fileName.c_str());
        int fd = open(fullPathName.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd != -1) {
            string content;
            if (android::base::ReadFdToString(fd, &content)) {
                proto->write(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_REPORTS,
                             content.c_str(), content.size());
            }
            close(fd);
        } else {
            ALOGE("file cannot be opened");
        }

        if (erase_data) {
            remove(fullPathName.c_str());
        } else if (!output.mIsHistory && !isAdb) {
            // This means a real data owner has called to get this data. But the config says it
            // wants to keep a local history. So now this file must be renamed as a history file.
            // So that next time, when owner calls getData() again, this data won't be uploaded
            // again. rename returns 0 on success
            if (rename(fullPathName.c_str(), (fullPathName + "_history").c_str())) {
                ALOGE("Failed to rename file %s", fullPathName.c_str());
            }
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

        FileName output;
        parseFileName(name, &output);
        if (output.mTimestampSec == -1) continue;
        string file_name = output.getFullFileName(STATS_SERVICE_DIR);
        int fd = open(file_name.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd != -1) {
            string content;
            if (android::base::ReadFdToString(fd, &content)) {
                StatsdConfig config;
                if (config.ParseFromString(content)) {
                    configsMap[ConfigKey(output.mUid, output.mConfigId)] = config;
                    VLOG("map key uid=%lld|configID=%lld", (long long)output.mUid,
                         (long long)output.mConfigId);
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

void StorageManager::sortFiles(vector<FileInfo>* fileNames) {
    // Reverse sort to effectively remove from the back (oldest entries).
    // This will sort files in reverse-chronological order. Local history files have lower
    // priority than regular data files.
    sort(fileNames->begin(), fileNames->end(), [](FileInfo& lhs, FileInfo& rhs) {
        // first consider if the file is a local history
        if (lhs.mIsHistory && !rhs.mIsHistory) {
            return false;
        } else if (rhs.mIsHistory && !lhs.mIsHistory) {
            return true;
        }

        // then consider the age.
        if (lhs.mFileAgeSec < rhs.mFileAgeSec) {
            return true;
        } else if (lhs.mFileAgeSec > rhs.mFileAgeSec) {
            return false;
        }

        // then good luck.... use string::compare
        return lhs.mFileName.compare(rhs.mFileName) > 0;
    });
}

void StorageManager::trimToFit(const char* path, bool parseTimestampOnly) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(path), closedir);
    if (dir == NULL) {
        VLOG("Path %s does not exist", path);
        return;
    }
    dirent* de;
    int totalFileSize = 0;
    vector<FileInfo> fileNames;
    auto nowSec = getWallClockSec();
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;

        FileName output;
        string file_name;
        if (parseTimestampOnly) {
            file_name = StringPrintf("%s/%s", path, name);
            output.mTimestampSec = StrToInt64(strtok(name, "_"));
            output.mIsHistory = false;
        } else {
            parseFileName(name, &output);
            file_name = output.getFullFileName(path);
        }
        if (output.mTimestampSec == -1) continue;

        // Check for timestamp and delete if it's too old.
        long fileAge = nowSec - output.mTimestampSec;
        if (fileAge > StatsdStats::kMaxAgeSecond ||
            (output.mIsHistory && fileAge > StatsdStats::kMaxLocalHistoryAgeSecond)) {
            deleteFile(file_name.c_str());
            continue;
        }

        ifstream file(file_name.c_str(), ifstream::in | ifstream::binary);
        int fileSize = 0;
        if (file.is_open()) {
            file.seekg(0, ios::end);
            fileSize = file.tellg();
            file.close();
            totalFileSize += fileSize;
        }
        fileNames.emplace_back(file_name, output.mIsHistory, fileSize, fileAge);
    }

    if (fileNames.size() > StatsdStats::kMaxFileNumber ||
        totalFileSize > StatsdStats::kMaxFileSize) {
        sortFiles(&fileNames);
    }

    // Start removing files from oldest to be under the limit.
    while (fileNames.size() > 0 && (fileNames.size() > StatsdStats::kMaxFileNumber ||
                                    totalFileSize > StatsdStats::kMaxFileSize)) {
        totalFileSize -= fileNames.at(fileNames.size() - 1).mFileSizeBytes;
        deleteFile(fileNames.at(fileNames.size() - 1).mFileName.c_str());
        fileNames.pop_back();
    }
}

void StorageManager::printStats(int outFd) {
    printDirStats(outFd, STATS_SERVICE_DIR);
    printDirStats(outFd, STATS_DATA_DIR);
}

void StorageManager::printDirStats(int outFd, const char* path) {
    dprintf(outFd, "Printing stats of %s\n", path);
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
        FileName output;
        parseFileName(name, &output);
        if (output.mTimestampSec == -1) continue;
        dprintf(outFd, "\t #%d, Last updated: %lld, UID: %d, Config ID: %lld, %s", fileCount + 1,
                (long long)output.mTimestampSec, output.mUid, (long long)output.mConfigId,
                (output.mIsHistory ? "local history" : ""));
        string file_name = output.getFullFileName(path);
        ifstream file(file_name.c_str(), ifstream::in | ifstream::binary);
        if (file.is_open()) {
            file.seekg(0, ios::end);
            int fileSize = file.tellg();
            file.close();
            dprintf(outFd, ", File Size: %d bytes", fileSize);
            totalFileSize += fileSize;
        }
        dprintf(outFd, "\n");
        fileCount++;
    }
    dprintf(outFd, "\tTotal number of files: %d, Total size of files: %d bytes.\n", fileCount,
            totalFileSize);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
