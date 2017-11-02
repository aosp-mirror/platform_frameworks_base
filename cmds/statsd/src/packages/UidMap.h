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

#ifndef STATSD_UIDMAP_H
#define STATSD_UIDMAP_H

#include "config/ConfigKey.h"
#include "config/ConfigListener.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "packages/PackageInfoListener.h"

#include <binder/IResultReceiver.h>
#include <binder/IShellCallback.h>
#include <gtest/gtest_prod.h>
#include <log/logprint.h>
#include <stdio.h>
#include <utils/RefBase.h>
#include <mutex>
#include <set>
#include <string>
#include <unordered_map>

using namespace std;

namespace android {
namespace os {
namespace statsd {

struct AppData {
    const string packageName;
    int versionCode;

    AppData(const string& a, const int v) : packageName(a), versionCode(v){};
};

// UidMap keeps track of what the corresponding app name (APK name) and version code for every uid
// at any given moment. This map must be updated by StatsCompanionService.
class UidMap : public virtual android::RefBase {
public:
    /*
     * All three inputs must be the same size, and the jth element in each array refers to the same
     * tuple, ie. uid[j] corresponds to packageName[j] with versionCode[j].
     */
    // TODO: Add safeguards to call clearOutput if there's too much data already stored.
    void updateMap(const vector<int32_t>& uid, const vector<int32_t>& versionCode,
                   const vector<String16>& packageName);

    // TODO: Add safeguards to call clearOutput if there's too much data already stored.
    void updateApp(const String16& packageName, const int32_t& uid, const int32_t& versionCode);
    void removeApp(const String16& packageName, const int32_t& uid);

    // Returns true if the given uid contains the specified app (eg. com.google.android.gms).
    bool hasApp(int uid, const string& packageName) const;

    int getAppVersion(int uid, const string& packageName) const;

    // Helper for debugging contents of this uid map. Can be triggered with:
    // adb shell cmd stats print-uid-map
    void printUidMap(FILE* out);

    // Commands for indicating to the map that a producer should be notified if an app is updated.
    // This allows the metric producer to distinguish when the same uid or app represents a
    // different version of an app.
    void addListener(sp<PackageInfoListener> producer);
    // Remove the listener from the set of metric producers that subscribe to updates.
    void removeListener(sp<PackageInfoListener> producer);

    // Informs uid map that a config is added/updated. Used for keeping mConfigKeys up to date.
    void OnConfigUpdated(const ConfigKey& key);

    // Informs uid map that a config is removed. Used for keeping mConfigKeys up to date.
    void OnConfigRemoved(const ConfigKey& key);

    // Gets the output. If every config key has received the output, then the output is cleared.
    UidMapping getOutput(const ConfigKey& key);

    // Forces the output to be cleared. We still generate a snapshot based on the current state.
    // This results in extra data uploaded but helps us reconstruct the uid mapping on the server
    // in case we lose a previous upload.
    void clearOutput();

private:
    void updateMap(const int64_t& timestamp, const vector<int32_t>& uid,
                   const vector<int32_t>& versionCode, const vector<String16>& packageName);

    // TODO: Add safeguards to call clearOutput if there's too much data already stored.
    void updateApp(const int64_t& timestamp, const String16& packageName, const int32_t& uid,
                   const int32_t& versionCode);
    void removeApp(const int64_t& timestamp, const String16& packageName, const int32_t& uid);

    UidMapping getOutput(const int64_t& timestamp, const ConfigKey& key);

    // TODO: Use shared_mutex for improved read-locking if a library can be found in Android.
    mutable mutex mMutex;

    // Maps uid to application data. This must be multimap since there is a feature in Android for
    // multiple apps to share the same uid.
    std::unordered_multimap<int, AppData> mMap;

    // We prepare the output proto as apps are updated, so that we can grab the current output.
    UidMapping mOutput;

    // Metric producers that should be notified if there's an upgrade in any app.
    set<sp<PackageInfoListener>> mSubscribers;

    // Mapping of config keys we're aware of to the epoch time they last received an update. This
    // lets us know it's safe to delete events older than the oldest update. The value is nanosec.
    // Value of -1 denotes this config key has never received an upload.
    std::unordered_map<ConfigKey, int64_t> mLastUpdatePerConfigKey;

    // Returns the minimum value from mConfigKeys.
    int64_t getMinimumTimestampNs();

    // Allows unit-test to access private methods.
    FRIEND_TEST(UidMapTest, TestClearingOutput);
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATSD_UIDMAP_H
