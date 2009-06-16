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

#include <stdint.h>
#include <utils/Log.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/Permission.h>

namespace android {
// ---------------------------------------------------------------------------

Permission::Permission(char const* name)
    : mPermissionName(name), mPid(getpid())
{
}

Permission::Permission(const String16& name)
    : mPermissionName(name), mPid(getpid())
{
}

Permission::Permission(const Permission& rhs)
    : mPermissionName(rhs.mPermissionName),
    mGranted(rhs.mGranted),
    mPid(rhs.mPid)
{
}

Permission::~Permission()
{
}

bool Permission::operator < (const Permission& rhs) const
{
    return mPermissionName < rhs.mPermissionName;
}

bool Permission::checkCalling() const
{
    IPCThreadState* ipcState = IPCThreadState::self();
    pid_t pid = ipcState->getCallingPid();
    uid_t uid = ipcState->getCallingUid();
    return doCheckPermission(pid, uid);
}

bool Permission::check(pid_t pid, uid_t uid) const
{
    return doCheckPermission(pid, uid);
}

bool Permission::doCheckPermission(pid_t pid, uid_t uid) const
{
    if ((uid == 0) || (pid == mPid)) {
        // root and ourselves is always okay
        return true;
    } else {
        // see if we already granted this permission for this uid
        Mutex::Autolock _l(mLock);
        if (mGranted.indexOf(uid) >= 0)
            return true;
    }

    bool granted = checkPermission(mPermissionName, pid, uid);
    if (granted) {
        Mutex::Autolock _l(mLock);
        // no need to check again, the old item will be replaced if it is
        // already there.
        mGranted.add(uid);
    }
    return granted;
}

// ---------------------------------------------------------------------------
}; // namespace android
