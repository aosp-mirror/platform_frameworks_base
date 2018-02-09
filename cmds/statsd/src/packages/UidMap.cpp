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

#include "guardrail/StatsdStats.h"
#include "packages/UidMap.h"

#include <android/os/IStatsCompanionService.h>
#include <binder/IServiceManager.h>
#include <utils/Errors.h>

#include <inttypes.h>

using namespace android;

namespace android {
namespace os {
namespace statsd {

UidMap::UidMap() : mBytesUsed(0) {}

UidMap::~UidMap() {}

bool UidMap::hasApp(int uid, const string& packageName) const {
    lock_guard<mutex> lock(mMutex);

    auto range = mMap.equal_range(uid);
    for (auto it = range.first; it != range.second; ++it) {
        if (it->second.packageName == packageName) {
            return true;
        }
    }
    return false;
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
    auto range = mMap.equal_range(uid);
    for (auto it = range.first; it != range.second; ++it) {
        names.insert(returnNormalized ?
            normalizeAppName(it->second.packageName) : it->second.packageName);
    }
    return names;
}

int64_t UidMap::getAppVersion(int uid, const string& packageName) const {
    lock_guard<mutex> lock(mMutex);

    auto range = mMap.equal_range(uid);
    for (auto it = range.first; it != range.second; ++it) {
        if (it->second.packageName == packageName) {
            return it->second.versionCode;
        }
    }
    return 0;
}

void UidMap::updateMap(const vector<int32_t>& uid, const vector<int64_t>& versionCode,
                       const vector<String16>& packageName) {
    updateMap(time(nullptr) * NS_PER_SEC, uid, versionCode, packageName);
}

void UidMap::updateMap(const int64_t& timestamp, const vector<int32_t>& uid,
                       const vector<int64_t>& versionCode, const vector<String16>& packageName) {
    vector<wp<PackageInfoListener>> broadcastList;
    {
        lock_guard<mutex> lock(mMutex);  // Exclusively lock for updates.

        mMap.clear();
        for (size_t j = 0; j < uid.size(); j++) {
            mMap.insert(make_pair(
                    uid[j], AppData(string(String8(packageName[j]).string()), versionCode[j])));
        }

        auto snapshot = mOutput.add_snapshots();
        snapshot->set_timestamp_nanos(timestamp);
        for (size_t j = 0; j < uid.size(); j++) {
            auto t = snapshot->add_package_info();
            t->set_name(string(String8(packageName[j]).string()));
            t->set_version(int(versionCode[j]));
            t->set_uid(uid[j]);
        }
        mBytesUsed += snapshot->ByteSize();
        ensureBytesUsedBelowLimit();
        StatsdStats::getInstance().setCurrentUidMapMemory(mBytesUsed);
        StatsdStats::getInstance().setUidMapSnapshots(mOutput.snapshots_size());
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

void UidMap::updateApp(const String16& app_16, const int32_t& uid, const int64_t& versionCode) {
    updateApp(time(nullptr) * NS_PER_SEC, app_16, uid, versionCode);
}

void UidMap::updateApp(const int64_t& timestamp, const String16& app_16, const int32_t& uid,
                       const int64_t& versionCode) {
    vector<wp<PackageInfoListener>> broadcastList;
    string appName = string(String8(app_16).string());
    {
        lock_guard<mutex> lock(mMutex);

        auto log = mOutput.add_changes();
        log->set_deletion(false);
        log->set_timestamp_nanos(timestamp);
        log->set_app(appName);
        log->set_uid(uid);
        log->set_version(versionCode);
        mBytesUsed += log->ByteSize();
        ensureBytesUsedBelowLimit();
        StatsdStats::getInstance().setCurrentUidMapMemory(mBytesUsed);
        StatsdStats::getInstance().setUidMapChanges(mOutput.changes_size());

        auto range = mMap.equal_range(int(uid));
        bool found = false;
        for (auto it = range.first; it != range.second; ++it) {
            // If we find the exact same app name and uid, update the app version directly.
            if (it->second.packageName == appName) {
                it->second.versionCode = versionCode;
                found = true;
                break;
            }
        }
        if (!found) {
            // Otherwise, we need to add an app at this uid.
            mMap.insert(make_pair(uid, AppData(appName, versionCode)));
        }
        getListenerListCopyLocked(&broadcastList);
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
        VLOG("Bytes used %zu is above limit %zu, need to delete something", mBytesUsed, limit);
        if (mOutput.snapshots_size() > 0) {
            auto snapshots = mOutput.mutable_snapshots();
            snapshots->erase(snapshots->begin());  // Remove first snapshot.
            StatsdStats::getInstance().noteUidMapDropped(1, 0);
        } else if (mOutput.changes_size() > 0) {
            auto changes = mOutput.mutable_changes();
            changes->DeleteSubrange(0, 1);
            StatsdStats::getInstance().noteUidMapDropped(0, 1);
        }
        mBytesUsed = mOutput.ByteSize();
    }
}

void UidMap::removeApp(const String16& app_16, const int32_t& uid) {
    removeApp(time(nullptr) * NS_PER_SEC, app_16, uid);
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

        auto log = mOutput.add_changes();
        log->set_deletion(true);
        log->set_timestamp_nanos(timestamp);
        log->set_app(app);
        log->set_uid(uid);
        mBytesUsed += log->ByteSize();
        ensureBytesUsedBelowLimit();
        StatsdStats::getInstance().setCurrentUidMapMemory(mBytesUsed);
        StatsdStats::getInstance().setUidMapChanges(mOutput.changes_size());

        auto range = mMap.equal_range(int(uid));
        for (auto it = range.first; it != range.second; ++it) {
            if (it->second.packageName == app) {
                mMap.erase(it);
                break;
            }
        }
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
    mOutput.Clear();
    // Also update the guardrail trackers.
    StatsdStats::getInstance().setUidMapChanges(0);
    StatsdStats::getInstance().setUidMapSnapshots(1);
    mBytesUsed = 0;
    StatsdStats::getInstance().setCurrentUidMapMemory(mBytesUsed);
}

int64_t UidMap::getMinimumTimestampNs() {
    int64_t m = 0;
    for (auto it : mLastUpdatePerConfigKey) {
        if (m == 0) {
            m = it.second;
        } else if (it.second < m) {
            m = it.second;
        }
    }
    return m;
}

size_t UidMap::getBytesUsed() const {
    return mBytesUsed;
}

UidMapping UidMap::getOutput(const ConfigKey& key) {
    return getOutput(time(nullptr) * NS_PER_SEC, key);
}

UidMapping UidMap::getOutput(const int64_t& timestamp, const ConfigKey& key) {
    lock_guard<mutex> lock(mMutex);  // Lock for updates

    auto ret = UidMapping(mOutput);  // Copy that will be returned.
    int64_t prevMin = getMinimumTimestampNs();
    mLastUpdatePerConfigKey[key] = timestamp;
    int64_t newMin = getMinimumTimestampNs();

    if (newMin > prevMin) {  // Delete anything possible now that the minimum has moved forward.
        int64_t cutoff_nanos = newMin;
        auto snapshots = mOutput.mutable_snapshots();
        auto it_snapshots = snapshots->cbegin();
        while (it_snapshots != snapshots->cend()) {
            if (it_snapshots->timestamp_nanos() < cutoff_nanos) {
                // it_snapshots points to the following element after erasing.
                it_snapshots = snapshots->erase(it_snapshots);
            } else {
                ++it_snapshots;
            }
        }
        auto deltas = mOutput.mutable_changes();
        auto it_deltas = deltas->cbegin();
        while (it_deltas != deltas->cend()) {
            if (it_deltas->timestamp_nanos() < cutoff_nanos) {
                // it_snapshots points to the following element after erasing.
                it_deltas = deltas->erase(it_deltas);
            } else {
                ++it_deltas;
            }
        }

        if (mOutput.snapshots_size() == 0) {
            // Produce another snapshot. This results in extra data being uploaded but helps
            // ensure we can re-construct the UID->app name, versionCode mapping in server.
            auto snapshot = mOutput.add_snapshots();
            snapshot->set_timestamp_nanos(timestamp);
            for (auto it : mMap) {
                auto t = snapshot->add_package_info();
                t->set_name(it.second.packageName);
                t->set_version(it.second.versionCode);
                t->set_uid(it.first);
            }
        }
    }
    mBytesUsed = mOutput.ByteSize();  // Compute actual size after potential deletions.
    StatsdStats::getInstance().setCurrentUidMapMemory(mBytesUsed);
    StatsdStats::getInstance().setUidMapChanges(mOutput.changes_size());
    StatsdStats::getInstance().setUidMapSnapshots(mOutput.snapshots_size());
    return ret;
}

void UidMap::printUidMap(FILE* out) const {
    lock_guard<mutex> lock(mMutex);

    for (auto it : mMap) {
        fprintf(out, "%s, v%" PRId64 " (%i)\n", it.second.packageName.c_str(),
                it.second.versionCode, it.first);
    }
}

void UidMap::OnConfigUpdated(const ConfigKey& key) {
    mLastUpdatePerConfigKey[key] = -1;

    // Ensure there is at least one snapshot available since this configuration also needs to know
    // what all the uid's represent.
    if (mOutput.snapshots_size() == 0) {
        sp<IStatsCompanionService> statsCompanion = nullptr;
        // Get statscompanion service from service manager
        const sp<IServiceManager> sm(defaultServiceManager());
        if (sm != nullptr) {
            const String16 name("statscompanion");
            statsCompanion = interface_cast<IStatsCompanionService>(sm->checkService(name));
            if (statsCompanion == nullptr) {
                ALOGW("statscompanion service unavailable!");
                return;
            }
            statsCompanion->triggerUidSnapshot();
        }
    }
}

void UidMap::OnConfigRemoved(const ConfigKey& key) {
    mLastUpdatePerConfigKey.erase(key);
}

set<int32_t> UidMap::getAppUid(const string& package) const {
    lock_guard<mutex> lock(mMutex);

    set<int32_t> results;
    for (const auto& pair : mMap) {
        if (pair.second.packageName == package) {
            results.insert(pair.first);
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
