/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/PermissionCache.h>
#include "ServiceUtilities.h"

namespace android {

// This optimization assumes mediaserver process doesn't fork, which it doesn't
const pid_t getpid_cached = getpid();

bool recordingAllowed() {
    if (getpid_cached == IPCThreadState::self()->getCallingPid()) return true;
    static const String16 sRecordAudio("android.permission.RECORD_AUDIO");
    // don't use PermissionCache; this is not a system permission
    bool ok = checkCallingPermission(sRecordAudio);
    if (!ok) ALOGE("Request requires android.permission.RECORD_AUDIO");
    return ok;
}

bool settingsAllowed() {
    if (getpid_cached == IPCThreadState::self()->getCallingPid()) return true;
    static const String16 sAudioSettings("android.permission.MODIFY_AUDIO_SETTINGS");
    // don't use PermissionCache; this is not a system permission
    bool ok = checkCallingPermission(sAudioSettings);
    if (!ok) ALOGE("Request requires android.permission.MODIFY_AUDIO_SETTINGS");
    return ok;
}

bool dumpAllowed() {
    // don't optimize for same pid, since mediaserver never dumps itself
    static const String16 sDump("android.permission.DUMP");
    // OK to use PermissionCache; this is a system permission
    bool ok = PermissionCache::checkCallingPermission(sDump);
    // convention is for caller to dump an error message to fd instead of logging here
    //if (!ok) ALOGE("Request requires android.permission.DUMP");
    return ok;
}

} // namespace android
