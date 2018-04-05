/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, versionCode 2.0 (the "License");
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

#include "stats_log_util.h"
#include "guardrail/StatsdStats.h"
#include "packages/UidMap.h"

#include <android/os/IStatsCompanionService.h>
#include <binder/IServiceManager.h>
#include <utils/Errors.h>

#include <inttypes.h>

using namespace android;

using android::base::StringPrintf;
using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_FLOAT;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;

namespace android {
namespace os {
namespace statsd {

const int FIELD_ID_SNAPSHOT_PACKAGE_NAME = 1;
const int FIELD_ID_SNAPSHOT_PACKAGE_VERSION = 2;
const int FIELD_ID_SNAPSHOT_PACKAGE_UID = 3;
const int FIELD_ID_SNAPSHOT_PACKAGE_DELETED = 4;
const int FIELD_ID_SNAPSHOT_TIMESTAMP = 1;
const int FIELD_ID_SNAPSHOT_PACKAGE_INFO = 2;
const int FIELD_ID_SNAPSHOTS = 1;
const int FIELD_ID_CHANGES = 2;
const int FIELD_ID_CHANGE_DELETION = 1;
const int FIELD_ID_CHANGE_TIMESTAMP = 2;
const int FIELD_ID_CHANGE_PACKAGE = 3;
const int FIELD_ID_CHANGE_UID = 4;
const int FIELD_ID_CHANGE_NEW_VERSION = 5;
const int FIELD_ID_CHANGE_PREV_VERSION = 6;

UidMap::UidMap() : mBytesUsed(0) {}

UidMap::~UidMap() {}

bool UidMap::hasApp(int uid, const string& packageName) const {
    lock_guard<mutex> lock(mMutex);

    auto it = mMap.find(std::make_pair(uid, packageName));
    return it != mMap.end() && !it->second.deleted;
}

string UidMap::normalizeAppName(const string& appName) const {
    string normalizedName = appName;
    std::transform(normalizedName.begin(), normalizedName.end(), normalizedName.begin(), ::tolower);
    return normalizedName;
}

std::set<string> UidMap::getAppNamesFromUid(const int32_t& uid, bool returnNormalized) const {
    lock_guard<mutex> lock(mMutex);
    return getAppNamesFromUidLocked(uid,returnNormalized);
}

std::set<string> UidMap::getAppNamesFromUidLocked(const int32_t& uid, bool returnNormalized) const {
    std::set<string> names;
    for (const auto& kv : mMap) {
        if (kv.first.first == uid && !kv.second.deleted) {
            names.insert(returnNormalized ? normalizeAppName(kv.first.second) : kv.first.second);
        }
    }
    return names;
}

int64_t UidMap::getAppVersion(int uid, const string& packageName) const {
    lock_guard<mutex> lock(mMutex);

    auto it = mMap.find(std::make_pair(uid, packageName));
    if (it == mMap.end() || it->second.deleted) {
        return 0;
    }
    return it->second.versionCode;
}

void UidMap::updateMap(const int64_t& timestamp, const vector<int32_t>& uid,
                       const vector<int64_t>& versionCode, const vector<String16>& packageName) {
    vector<wp<PackageInfoListener>> broadcastList;
    {
        lock_guard<mutex> lock(mMutex);  // Exclusively lock for updates.

        std::unordered_map<std::pair<int, string>, AppData, PairHash> deletedApps;

        // Copy all the deleted apps.
        for (const auto& kv : mMap) {
            if (kv.second.deleted) {
                deletedApps[kv.first] = kv.second;
            }
        }

        mMap.clear();
        for (size_t j = 0; j < uid.size(); j++) {
            string package = string(String8(packageName[j]).string());
            mMap[std::make_pair(uid[j], package)] = AppData(versionCode[j]);
        }

        for (const auto& kv : deletedApps) {
            auto mMapIt = mMap.find(kv.first);
            if (mMapIt != mMap.end()) {
                // Insert this deleted app back into the current map.
                mMap[kv.first] = kv.second;
            }
        }

        ensureBytesUsedBelowLimit();
        StatsdStats::getInstance().setCurrentUidMapMemory(mBytesUsed);
        getListenerListCopyLocked(&broadcastList);
    }
    // To avoid invoking callback while holding the internal lock. we get a copy of the listener
    // list and invoke the callback. It's still possible that after we copy the list, a
    // listener removes itself before we call it. It's then the listener's job to handle it (expect
    // the callback to be called after listener is removed, and the listener should properly
    // ignore it).
    for (auto weakPtr : broadcastList) {
        auto strongPtr = weakPtr.promote();
        if (strongPtr != NULL) {
            strongPtr->onUidMapReceived(timestamp);
        }
    }
}

void UidMap::updateApp(const int64_t& timestamp, const String16& app_16, const int32_t& uid,
                       const int64_t& versionCode) {
    vector<wp<PackageInfoListener>> broadcastList;
    string appName = string(String8(app_16).string());
    {
        lock_guard<mutex> lock(mMutex);
        int32_t prevVersion = 0;
        bool found = false;
        auto it = mMap.find(std::make_pair(uid, appName));
        if (it != mMap.end()) {
            found = true;
            prevVersion = it->second.versionCode;
            it->second.versionCode = versionCode;
            it->second.deleted = false;
        }
        if (!found) {
            // Otherwise, we need to add an app at this uid.
            mMap[std::make_pair(uid, appName)] = AppData(versionCode);
        } else {
            // Only notify the listeners if this is an app upgrade. If this app is being installed
            // for the first time, then we don't notify the listeners.
            getListenerListCopyLocked(&broadcastList);
        }
        mChanges.emplace_back(false, timestamp, appName, uid, versionCode, prevVersion);
        mBytesUsed += kBytesChangeRecord;
        ensureBytesUsedBelowLimit();
        StatsdStats::getInstance().setCurrentUidMapMemory(mBytesUsed);
        StatsdStats::getInstance().setUidMapChanges(mChanges.size());
    }

    for (auto weakPtr : broadcastList) {
        auto strongPtr = weakPtr.promote();
        if (strongPtr != NULL) {
            strongPtr->notifyAppUpgrade(timestamp, appName, uid, versionCode);
        }
    }
}

void UidMap::ensureBytesUsedBelowLimit() {
    size_t limit;
    if (maxBytesOverride <= 0) {
        limit = StatsdStats::kMaxBytesUsedUidMap;
    } else {
        limit = maxBytesOverride;
    }
    while (mBytesUsed > limit) {
        ALOGI("Bytes used %zu is above limit %zu, need to delete something", mBytesUsed, limit);
        if (mChanges.size() > 0) {
            mBytesUsed -= kBytesChangeRecord;
            mChanges.pop_front();
            StatsdStats::getInstance().noteUidMapDropped(1);
        }
    }
}

void UidMap::getListenerListCopyLocked(vector<wp<PackageInfoListener>>* output) {
    for (auto weakIt = mSubscribers.begin(); weakIt != mSubscribers.end();) {
        auto strongPtr = weakIt->promote();
        if (strongPtr != NULL) {
            output->push_back(*weakIt);
            weakIt++;
        } else {
            weakIt = mSubscribers.erase(weakIt);
            VLOG("The UidMap listener is gone, remove it now");
        }
    }
}

void UidMap::removeApp(const int64_t& timestamp, const String16& app_16, const int32_t& uid) {
    vector<wp<PackageInfoListener>> broadcastList;
    string app = string(String8(app_16).string());
    {
        lock_guard<mutex> lock(mMutex);

        int32_t prevVersion = 0;
        auto key = std::make_pair(uid, app);
        auto it = mMap.find(key);
        if (it != mMap.end() && !it->second.deleted) {
            prevVersion = it->second.versionCode;
            it->second.deleted = true;
            mDeletedApps.push_back(key);
        }
        if (mDeletedApps.size() > StatsdStats::kMaxDeletedAppsInUidMap) {
            // Delete the oldest one.
            auto oldest = mDeletedApps.front();
            mDeletedApps.pop_front();
            mMap.erase(oldest);
            StatsdStats::getInstance().noteUidMapAppDeletionDropped();
        }
        mChanges.emplace_back(true, timestamp, app, uid, 0, prevVersion);
        mBytesUsed += kBytesChangeRecord;
        ensureBytesUsedBelowLimit();
        StatsdStats::getInstance().setCurrentUidMapMemory(mBytesUsed);
        StatsdStats::getInstance().setUidMapChanges(mChanges.size());
        getListenerListCopyLocked(&broadcastList);
    }

    for (auto weakPtr : broadcastList) {
        auto strongPtr = weakPtr.promote();
        if (strongPtr != NULL) {
            strongPtr->notifyAppRemoved(timestamp, app, uid);
        }
    }
}

void UidMap::addListener(wp<PackageInfoListener> producer) {
    lock_guard<mutex> lock(mMutex);  // Lock for updates
    mSubscribers.insert(producer);
}

void UidMap::removeListener(wp<PackageInfoListener> producer) {
    lock_guard<mutex> lock(mMutex);  // Lock for updates
    mSubscribers.erase(producer);
}

void UidMap::assignIsolatedUid(int isolatedUid, int parentUid) {
    lock_guard<mutex> lock(mIsolatedMutex);

    mIsolatedUidMap[isolatedUid] = parentUid;
}

void UidMap::removeIsolatedUid(int isolatedUid, int parentUid) {
    lock_guard<mutex> lock(mIsolatedMutex);

    auto it = mIsolatedUidMap.find(isolatedUid);
    if (it != mIsolatedUidMap.end()) {
        mIsolatedUidMap.erase(it);
    }
}

int UidMap::getHostUidOrSelf(int uid) const {
    lock_guard<mutex> lock(mIsolatedMutex);

    auto it = mIsolatedUidMap.find(uid);
    if (it != mIsolatedUidMap.end()) {
        return it->second;
    }
    return uid;
}

void UidMap::clearOutput() {
    mChanges.clear();
    // Also update the guardrail trackers.
    StatsdStats::getInstance().setUidMapChanges(0);
    mBytesUsed = 0;
    StatsdStats::getInstance().setCurrentUidMapMemory(mBytesUsed);
}

int64_t UidMap::getMinimumTimestampNs() {
    int64_t m = 0;
    for (const auto& kv : mLastUpdatePerConfigKey) {
        if (m == 0) {
            m = kv.second;
        } else if (kv.second < m) {
            m = kv.second;
        }
    }
    return m;
}

size_t UidMap::getBytesUsed() const {
    return mBytesUsed;
}

void UidMap::appendUidMap(const int64_t& timestamp, const ConfigKey& key,
                          ProtoOutputStream* proto) {
    lock_guard<mutex> lock(mMutex);  // Lock for updates

    for (const ChangeRecord& record : mChanges) {
        if (record.timestampNs > mLastUpdatePerConfigKey[key]) {
            uint64_t changesToken =
                    proto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_CHANGES);
            proto->write(FIELD_TYPE_BOOL | FIELD_ID_CHANGE_DELETION, (bool)record.deletion);
            proto->write(FIELD_TYPE_INT64 | FIELD_ID_CHANGE_TIMESTAMP,
                         (long long)record.timestampNs);
            proto->write(FIELD_TYPE_STRING | FIELD_ID_CHANGE_PACKAGE, record.package);
            proto->write(FIELD_TYPE_INT32 | FIELD_ID_CHANGE_UID, (int)record.uid);
            proto->write(FIELD_TYPE_INT32 | FIELD_ID_CHANGE_NEW_VERSION, (int)record.version);
            proto->write(FIELD_TYPE_INT32 | FIELD_ID_CHANGE_PREV_VERSION, (int)record.prevVersion);
            proto->end(changesToken);
        }
    }

    // Write snapshot from current uid map state.
    uint64_t snapshotsToken =
            proto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_SNAPSHOTS);
    proto->write(FIELD_TYPE_INT64 | FIELD_ID_SNAPSHOT_TIMESTAMP, (long long)timestamp);
    for (const auto& kv : mMap) {
        uint64_t token = proto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED |
                                      FIELD_ID_SNAPSHOT_PACKAGE_INFO);
        proto->write(FIELD_TYPE_STRING | FIELD_ID_SNAPSHOT_PACKAGE_NAME, kv.first.second);
        proto->write(FIELD_TYPE_INT32 | FIELD_ID_SNAPSHOT_PACKAGE_VERSION,
                     (int)kv.second.versionCode);
        proto->write(FIELD_TYPE_INT32 | FIELD_ID_SNAPSHOT_PACKAGE_UID, kv.first.first);
        proto->write(FIELD_TYPE_BOOL | FIELD_ID_SNAPSHOT_PACKAGE_DELETED, kv.second.deleted);
        proto->end(token);
    }
    proto->end(snapshotsToken);

    int64_t prevMin = getMinimumTimestampNs();
    mLastUpdatePerConfigKey[key] = timestamp;
    int64_t newMin = getMinimumTimestampNs();

    if (newMin > prevMin) {  // Delete anything possible now that the minimum has
                             // moved forward.
        int64_t cutoff_nanos = newMin;
        for (auto it_changes = mChanges.begin(); it_changes != mChanges.end();) {
            if (it_changes->timestampNs < cutoff_nanos) {
                mBytesUsed -= kBytesChangeRecord;
                it_changes = mChanges.erase(it_changes);
            } else {
                ++it_changes;
            }
        }
    }
    StatsdStats::getInstance().setCurrentUidMapMemory(mBytesUsed);
    StatsdStats::getInstance().setUidMapChanges(mChanges.size());
}

void UidMap::printUidMap(FILE* out) const {
    lock_guard<mutex> lock(mMutex);

    for (const auto& kv : mMap) {
        if (!kv.second.deleted) {
            fprintf(out, "%s, v%" PRId64 " (%i)\n", kv.first.second.c_str(), kv.second.versionCode,
                    kv.first.first);
        }
    }
}

void UidMap::OnConfigUpdated(const ConfigKey& key) {
    mLastUpdatePerConfigKey[key] = -1;
}

void UidMap::OnConfigRemoved(const ConfigKey& key) {
    mLastUpdatePerConfigKey.erase(key);
}

set<int32_t> UidMap::getAppUid(const string& package) const {
    lock_guard<mutex> lock(mMutex);

    set<int32_t> results;
    for (const auto& kv : mMap) {
        if (kv.first.second == package && !kv.second.deleted) {
            results.insert(kv.first.first);
        }
    }
    return results;
}

// Note not all the following AIDs are used as uids. Some are used only for gids.
// It's ok to leave them in the map, but we won't ever see them in the log's uid field.
// App's uid starts from 10000, and will not overlap with the following AIDs.
const std::map<string, uint32_t> UidMap::sAidToUidMapping = {{"AID_ROOT", 0},
                                                             {"AID_SYSTEM", 1000},
                                                             {"AID_RADIO", 1001},
                                                             {"AID_BLUETOOTH", 1002},
                                                             {"AID_GRAPHICS", 1003},
                                                             {"AID_INPUT", 1004},
                                                             {"AID_AUDIO", 1005},
                                                             {"AID_CAMERA", 1006},
                                                             {"AID_LOG", 1007},
                                                             {"AID_COMPASS", 1008},
                                                             {"AID_MOUNT", 1009},
                                                             {"AID_WIFI", 1010},
                                                             {"AID_ADB", 1011},
                                                             {"AID_INSTALL", 1012},
                                                             {"AID_MEDIA", 1013},
                                                             {"AID_DHCP", 1014},
                                                             {"AID_SDCARD_RW", 1015},
                                                             {"AID_VPN", 1016},
                                                             {"AID_KEYSTORE", 1017},
                                                             {"AID_USB", 1018},
                                                             {"AID_DRM", 1019},
                                                             {"AID_MDNSR", 1020},
                                                             {"AID_GPS", 1021},
                                                             // {"AID_UNUSED1", 1022},
                                                             {"AID_MEDIA_RW", 1023},
                                                             {"AID_MTP", 1024},
                                                             // {"AID_UNUSED2", 1025},
                                                             {"AID_DRMRPC", 1026},
                                                             {"AID_NFC", 1027},
                                                             {"AID_SDCARD_R", 1028},
                                                             {"AID_CLAT", 1029},
                                                             {"AID_LOOP_RADIO", 1030},
                                                             {"AID_MEDIA_DRM", 1031},
                                                             {"AID_PACKAGE_INFO", 1032},
                                                             {"AID_SDCARD_PICS", 1033},
                                                             {"AID_SDCARD_AV", 1034},
                                                             {"AID_SDCARD_ALL", 1035},
                                                             {"AID_LOGD", 1036},
                                                             {"AID_SHARED_RELRO", 1037},
                                                             {"AID_DBUS", 1038},
                                                             {"AID_TLSDATE", 1039},
                                                             {"AID_MEDIA_EX", 1040},
                                                             {"AID_AUDIOSERVER", 1041},
                                                             {"AID_METRICS_COLL", 1042},
                                                             {"AID_METRICSD", 1043},
                                                             {"AID_WEBSERV", 1044},
                                                             {"AID_DEBUGGERD", 1045},
                                                             {"AID_MEDIA_CODEC", 1046},
                                                             {"AID_CAMERASERVER", 1047},
                                                             {"AID_FIREWALL", 1048},
                                                             {"AID_TRUNKS", 1049},
                                                             {"AID_NVRAM", 1050},
                                                             {"AID_DNS", 1051},
                                                             {"AID_DNS_TETHER", 1052},
                                                             {"AID_WEBVIEW_ZYGOTE", 1053},
                                                             {"AID_VEHICLE_NETWORK", 1054},
                                                             {"AID_MEDIA_AUDIO", 1055},
                                                             {"AID_MEDIA_VIDEO", 1056},
                                                             {"AID_MEDIA_IMAGE", 1057},
                                                             {"AID_TOMBSTONED", 1058},
                                                             {"AID_MEDIA_OBB", 1059},
                                                             {"AID_ESE", 1060},
                                                             {"AID_OTA_UPDATE", 1061},
                                                             {"AID_AUTOMOTIVE_EVS", 1062},
                                                             {"AID_LOWPAN", 1063},
                                                             {"AID_HSM", 1064},
                                                             {"AID_RESERVED_DISK", 1065},
                                                             {"AID_STATSD", 1066},
                                                             {"AID_INCIDENTD", 1067},
                                                             {"AID_SHELL", 2000},
                                                             {"AID_CACHE", 2001},
                                                             {"AID_DIAG", 2002}};

}  // namespace statsd
}  // namespace os
}  // namespace android
