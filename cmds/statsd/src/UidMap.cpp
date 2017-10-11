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

#include "UidMap.h"
#include <cutils/log.h>
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

void UidMap::updateMap(const vector <int32_t> &uid, const vector <int32_t> &versionCode,
                       const vector <String16> &packageName) {
    lock_guard<mutex> lock(mMutex); // Exclusively lock for updates.

    mMap.clear();
    for (unsigned long j=0; j<uid.size(); j++) {
        mMap.insert(make_pair(uid[j], AppData(string(String8(packageName[j]).string()),
                                              versionCode[j])));
    }

    if (mOutput.initial_size() == 0) { // Provide the initial states in the mOutput proto
        for (unsigned long j=0; j<uid.size(); j++) {
            auto t = mOutput.add_initial();
            t->set_app(string(String8(packageName[j]).string()));
            t->set_version(int(versionCode[j]));
            t->set_uid(uid[j]);
        }
    }
}

void UidMap::updateApp(const String16& app_16, const int32_t& uid, const int32_t& versionCode){
    lock_guard<mutex> lock(mMutex);

    string app = string(String8(app_16).string());

    // Notify any interested producers that this app has updated
    for (auto it : mSubscribers) {
        it->notifyAppUpgrade(app, uid, versionCode);
    }

    auto log = mOutput.add_changes();
    log->set_deletion(false);
    //log.timestamp = TODO: choose how timestamps are computed
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


void UidMap::removeApp(const String16& app_16, const int32_t& uid){
    lock_guard<mutex> lock(mMutex);

    string app = string(String8(app_16).string());

    auto log = mOutput.add_changes();
    log->set_deletion(true);
    //log.timestamp = TODO: choose how timestamps are computed
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
    lock_guard<mutex> lock(mMutex); // Lock for updates
    mSubscribers.insert(producer);
}

void UidMap::removeListener(sp<PackageInfoListener> producer) {
    lock_guard<mutex> lock(mMutex); // Lock for updates
    mSubscribers.erase(producer);
}

UidMapping UidMap::getAndClearOutput() {
    lock_guard<mutex> lock(mMutex); // Lock for updates

    auto ret = UidMapping(mOutput); // Copy that will be returned.
    mOutput.Clear();

    // Re-initialize the initial state for the outputs. This results in extra data being uploaded
    // but helps ensure we can't re-construct the UID->app name, versionCode mapping in server.
    for (auto it : mMap) {
        auto t = mOutput.add_initial();
        t->set_app(it.second.packageName);
        t->set_version(it.second.versionCode);
        t->set_uid(it.first);
    }

    return ret;
}

void UidMap::printUidMap(FILE* out) {
    lock_guard<mutex> lock(mMutex);

    for (auto it : mMap) {
        fprintf(out, "%s, v%d (%i)\n", it.second.packageName.c_str(), it.second.versionCode, it.first);
    }
}


}  // namespace statsd
}  // namespace os
}  // namespace android
