/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "Log.h"

#include "packages/UidMap.h"

#include <utils/Errors.h>

using namespace android;

namespace android {
namespace os {
namespace statsd {

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

int UidMap::getAppVersion(int uid, const string& packageName) const {
    lock_guard<mutex> lock(mMutex);

    auto range = mMap.equal_range(uid);
    for (auto it = range.first; it != range.second; ++it) {
        if (it->second.packageName == packageName) {
            return it->second.versionCode;
        }
    }
    return 0;
}

void UidMap::updateMap(const vector<int32_t>& uid, const vector<int32_t>& versionCode,
                       const vector<String16>& packageName) {
    updateMap(time(nullptr) * 1000000000, uid, versionCode, packageName);
}

void UidMap::updateMap(const int64_t& timestamp, const vector<int32_t>& uid,
                       const vector<int32_t>& versionCode, const vector<String16>& packageName) {
    lock_guard<mutex> lock(mMutex);  // Exclusively lock for updates.

    mMap.clear();
    for (size_t j = 0; j < uid.size(); j++) {
        mMap.insert(make_pair(uid[j],
                              AppData(string(String8(packageName[j]).string()), versionCode[j])));
    }

    auto snapshot = mOutput.add_snapshots();
    snapshot->set_timestamp_nanos(timestamp);
    for (size_t j = 0; j < uid.size(); j++) {
        auto t = snapshot->add_package_info();
        t->set_name(string(String8(packageName[j]).string()));
        t->set_version(int(versionCode[j]));
        t->set_uid(uid[j]);
    }
}

void UidMap::updateApp(const String16& app_16, const int32_t& uid, const int32_t& versionCode) {
    updateApp(time(nullptr) * 1000000000, app_16, uid, versionCode);
}

void UidMap::updateApp(const int64_t& timestamp, const String16& app_16, const int32_t& uid,
                       const int32_t& versionCode) {
    lock_guard<mutex> lock(mMutex);

    string app = string(String8(app_16).string());

    // Notify any interested producers that this app has updated
    for (auto it : mSubscribers) {
        it->notifyAppUpgrade(app, uid, versionCode);
    }

    auto log = mOutput.add_changes();
    log->set_deletion(false);
    log->set_timestamp_nanos(timestamp);
    log->set_app(app);
    log->set_uid(uid);
    log->set_version(versionCode);

    auto range = mMap.equal_range(int(uid));
    for (auto it = range.first; it != range.second; ++it) {
        if (it->second.packageName == app) {
            it->second.versionCode = int(versionCode);
            return;
        }
        ALOGD("updateApp failed to find the app %s with uid %i to update", app.c_str(), uid);
        return;
    }

    // Otherwise, we need to add an app at this uid.
    mMap.insert(make_pair(uid, AppData(app, int(versionCode))));
}

void UidMap::removeApp(const String16& app_16, const int32_t& uid) {
    removeApp(time(nullptr) * 1000000000, app_16, uid);
}
void UidMap::removeApp(const int64_t& timestamp, const String16& app_16, const int32_t& uid) {
    lock_guard<mutex> lock(mMutex);

    string app = string(String8(app_16).string());

    for (auto it : mSubscribers) {
        it->notifyAppRemoved(app, uid);
    }

    auto log = mOutput.add_changes();
    log->set_deletion(true);
    log->set_timestamp_nanos(timestamp);
    log->set_app(app);
    log->set_uid(uid);

    auto range = mMap.equal_range(int(uid));
    for (auto it = range.first; it != range.second; ++it) {
        if (it->second.packageName == app) {
            mMap.erase(it);
            return;
        }
    }
    ALOGD("removeApp failed to find the app %s with uid %i to remove", app.c_str(), uid);
    return;
}

void UidMap::addListener(sp<PackageInfoListener> producer) {
    lock_guard<mutex> lock(mMutex);  // Lock for updates
    mSubscribers.insert(producer);
}

void UidMap::removeListener(sp<PackageInfoListener> producer) {
    lock_guard<mutex> lock(mMutex);  // Lock for updates
    mSubscribers.erase(producer);
}

void UidMap::clearOutput() {
    mOutput.Clear();

    // Re-initialize the initial state for the outputs. This results in extra data being uploaded
    // but helps ensure we can re-construct the UID->app name, versionCode mapping in server.
    auto snapshot = mOutput.add_snapshots();
    for (auto it : mMap) {
        auto t = snapshot->add_package_info();
        t->set_name(it.second.packageName);
        t->set_version(it.second.versionCode);
        t->set_uid(it.first);
    }
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

UidMapping UidMap::getOutput(const ConfigKey& key) {
    return getOutput(time(nullptr) * 1000000000, key);
}

UidMapping UidMap::getOutput(const int64_t& timestamp, const ConfigKey& key) {
    lock_guard<mutex> lock(mMutex);  // Lock for updates

    auto ret = UidMapping(mOutput);  // Copy that will be returned.
    int64_t prevMin = getMinimumTimestampNs();
    mLastUpdatePerConfigKey[key] = timestamp;
    int64_t newMin = getMinimumTimestampNs();

    if (newMin > prevMin) {
        int64_t cutoff_nanos = newMin;
        auto snapshots = mOutput.mutable_snapshots();
        auto it_snapshots = snapshots->cbegin();
        while (it_snapshots != snapshots->cend()) {
            if (it_snapshots->timestamp_nanos() < cutoff_nanos) {
                // it_snapshots now points to the following element.
                it_snapshots = snapshots->erase(it_snapshots);
            } else {
                ++it_snapshots;
            }
        }
        auto deltas = mOutput.mutable_changes();
        auto it_deltas = deltas->cbegin();
        while (it_deltas != deltas->cend()) {
            if (it_deltas->timestamp_nanos() < cutoff_nanos) {
                // it_deltas now points to the following element.
                it_deltas = deltas->erase(it_deltas);
            } else {
                ++it_deltas;
            }
        }
    }
    return ret;
}

void UidMap::printUidMap(FILE* out) {
    lock_guard<mutex> lock(mMutex);

    for (auto it : mMap) {
        fprintf(out, "%s, v%d (%i)\n", it.second.packageName.c_str(), it.second.versionCode,
                it.first);
    }
}

void UidMap::OnConfigUpdated(const ConfigKey& key) {
    mLastUpdatePerConfigKey[key] = -1;
}

void UidMap::OnConfigRemoved(const ConfigKey& key) {
    mLastUpdatePerConfigKey.erase(key);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
