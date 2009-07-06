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

#ifndef BINDER_PERMISSION_H
#define BINDER_PERMISSION_H

#include <stdint.h>
#include <unistd.h>

#include <utils/SortedVector.h>
#include <utils/String16.h>
#include <utils/threads.h>

namespace android {
// ---------------------------------------------------------------------------

/*
 * Permission caches the result of the permission check for the given
 * permission name and the provided uid/pid. It also handles a few
 * known cases efficiently (caller is in the same process or is root).
 * The package manager does something similar but lives in dalvik world
 * and is therefore extremely slow to access.
 */

class Permission
{
public:
            Permission(char const* name);
            Permission(const String16& name);
            Permission(const Permission& rhs);
    virtual ~Permission();

    bool operator < (const Permission& rhs) const;

    // checks the current binder call's caller has access to this permission
    bool checkCalling() const;
    
    // checks the specified pid/uid has access to this permission
    bool check(pid_t pid, uid_t uid) const;
    
protected:
    virtual bool doCheckPermission(pid_t pid, uid_t uid) const;

private:
    Permission& operator = (const Permission& rhs) const;
    const String16 mPermissionName;
    mutable SortedVector<uid_t> mGranted;
    const pid_t mPid;
    mutable Mutex mLock;
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif /* BINDER_PERMISSION_H */
