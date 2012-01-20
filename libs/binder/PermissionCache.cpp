/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "PermissionCache"

#include <stdint.h>
#include <utils/Log.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/PermissionCache.h>
#include <utils/String8.h>

namespace android {

// ----------------------------------------------------------------------------

ANDROID_SINGLETON_STATIC_INSTANCE(PermissionCache) ;

// ----------------------------------------------------------------------------

PermissionCache::PermissionCache() {
}

status_t PermissionCache::check(bool* granted,
        const String16& permission, uid_t uid) const {
    Mutex::Autolock _l(mLock);
    Entry e;
    e.name = permission;
    e.uid  = uid;
    ssize_t index = mCache.indexOf(e);
    if (index >= 0) {
        *granted = mCache.itemAt(index).granted;
        return NO_ERROR;
    }
    return NAME_NOT_FOUND;
}

void PermissionCache::cache(const String16& permission,
        uid_t uid, bool granted) {
    Mutex::Autolock _l(mLock);
    Entry e;
    ssize_t index = mPermissionNamesPool.indexOf(permission);
    if (index > 0) {
        e.name = mPermissionNamesPool.itemAt(index);
    } else {
        mPermissionNamesPool.add(permission);
        e.name = permission;
    }
    // note, we don't need to store the pid, which is not actually used in
    // permission checks
    e.uid  = uid;
    e.granted = granted;
    index = mCache.indexOf(e);
    if (index < 0) {
        mCache.add(e);
    }
}

void PermissionCache::purge() {
    Mutex::Autolock _l(mLock);
    mCache.clear();
}

bool PermissionCache::checkCallingPermission(const String16& permission) {
    return PermissionCache::checkCallingPermission(permission, NULL, NULL);
}

bool PermissionCache::checkCallingPermission(
        const String16& permission, int32_t* outPid, int32_t* outUid) {
    IPCThreadState* ipcState = IPCThreadState::self();
    pid_t pid = ipcState->getCallingPid();
    uid_t uid = ipcState->getCallingUid();
    if (outPid) *outPid = pid;
    if (outUid) *outUid = uid;
    return PermissionCache::checkPermission(permission, pid, uid);
}

bool PermissionCache::checkPermission(
        const String16& permission, pid_t pid, uid_t uid) {
    if ((uid == 0) || (pid == getpid())) {
        // root and ourselves is always okay
        return true;
    }

    PermissionCache& pc(PermissionCache::getInstance());
    bool granted = false;
    if (pc.check(&granted, permission, uid) != NO_ERROR) {
        nsecs_t t = -systemTime();
        granted = android::checkPermission(permission, pid, uid);
        t += systemTime();
        ALOGD("checking %s for uid=%d => %s (%d us)",
                String8(permission).string(), uid,
                granted?"granted":"denied", (int)ns2us(t));
        pc.cache(permission, uid, granted);
    }
    return granted;
}

// ---------------------------------------------------------------------------
}; // namespace android
