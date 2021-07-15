/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <android/permission_manager.h>
#include <binder/ActivityManager.h>

namespace android {
namespace permissionmananger {

// Global instance of ActivityManager, service is obtained only on first use.
static ActivityManager gAm;

} // permissionmanager
} // android

using namespace android;
using namespace permissionmananger;

int32_t APermissionManager_checkPermission(const char* permission,
                                           pid_t pid,
                                           uid_t uid,
                                           int32_t* outResult) {
    status_t result = gAm.checkPermission(String16(permission), pid, uid, outResult);
    if (result == DEAD_OBJECT) {
        return PERMISSION_MANAGER_STATUS_SERVICE_UNAVAILABLE;
    } else if (result != NO_ERROR) {
        return PERMISSION_MANAGER_STATUS_ERROR_UNKNOWN;
    }
    return PERMISSION_MANAGER_STATUS_OK;
}
