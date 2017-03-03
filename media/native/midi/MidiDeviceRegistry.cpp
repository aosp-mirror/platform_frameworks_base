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

#include "MidiDeviceRegistry.h"

namespace android {

ANDROID_SINGLETON_STATIC_INSTANCE(media::midi::MidiDeviceRegistry);

namespace media {
namespace midi {

MidiDeviceRegistry::MidiDeviceRegistry() : mNextDeviceToken(1) {
}

status_t MidiDeviceRegistry::addDevice(sp<BpMidiDeviceServer> server, int32_t deviceId) {
    if (server.get() == nullptr) {
        return -EINVAL;
    }

    std::lock_guard<std::mutex> guard(mMapsLock);
    mServers[deviceId] = server;
    return OK;
}

status_t MidiDeviceRegistry::removeDevice(int32_t deviceId) {
    std::lock_guard<std::mutex> guard(mMapsLock);
    mServers.erase(deviceId);
    const auto& iter = mUidToToken.find(deviceId);
    if (iter != mUidToToken.end()) {
        mTokenToUid.erase(iter->second);
        mUidToToken.erase(iter);
    }
    return OK;
}

//NOTE: This creates an entry if not found, or returns an existing one.
status_t MidiDeviceRegistry::obtainDeviceToken(int32_t deviceId, AMIDI_Device *deviceTokenPtr) {
    std::lock_guard<std::mutex> guard(mMapsLock);
    const auto& serversIter = mServers.find(deviceId);
    if (serversIter == mServers.end()) {
        // Not found.
        return -EINVAL;
    }

    const auto& iter = mUidToToken.find(deviceId);
    if (iter != mUidToToken.end()) {
        *deviceTokenPtr = iter->second;
    } else {
        *deviceTokenPtr = mNextDeviceToken++;
        mTokenToUid[*deviceTokenPtr] = deviceId;
        mUidToToken[deviceId] = *deviceTokenPtr;
    }
    return OK;
}

status_t MidiDeviceRegistry::releaseDevice(AMIDI_Device deviceToken) {
    std::lock_guard<std::mutex> guard(mMapsLock);
    const auto& iter = mTokenToUid.find(deviceToken);
    if (iter == mTokenToUid.end()) {
        // Not found
        return -EINVAL;
    }

    mServers.erase(iter->second);
    mUidToToken.erase(iter->second);
    mTokenToUid.erase(iter);
    return OK;
}

status_t MidiDeviceRegistry::getDeviceByToken(
        AMIDI_Device deviceToken, sp<BpMidiDeviceServer> *devicePtr) {
    std::lock_guard<std::mutex> guard(mMapsLock);
    int32_t id = -1;
    {
        const auto& iter = mTokenToUid.find(deviceToken);
        if (iter == mTokenToUid.end()) {
            return -EINVAL;
        }
        id = iter->second;
    }
    const auto& iter = mServers.find(id);
    if (iter == mServers.end()) {
        return -EINVAL;
    }

    *devicePtr = iter->second;
    return OK;
}

} // namespace midi
} // namespace media
} // namespace android
