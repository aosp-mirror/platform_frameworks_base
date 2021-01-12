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

#include "IncrementalServiceValidation.h"

#include <android-base/stringprintf.h>
#include <binder/IPCThreadState.h>
#include <binder/PermissionCache.h>
#include <binder/PermissionController.h>
#include <errno.h>
#include <utils/String16.h>

namespace android::incremental {

binder::Status Ok() {
    return binder::Status::ok();
}

binder::Status Exception(uint32_t code, const std::string& msg) {
    return binder::Status::fromExceptionCode(code, String8(msg.c_str()));
}

int fromBinderStatus(const binder::Status& status) {
    return status.exceptionCode() == binder::Status::EX_SERVICE_SPECIFIC
            ? status.serviceSpecificErrorCode() > 0
                    ? -status.serviceSpecificErrorCode()
                    : status.serviceSpecificErrorCode() == 0 ? -EFAULT
                                                             : status.serviceSpecificErrorCode()
            : -EIO;
}

binder::Status CheckPermissionForDataDelivery(const char* permission, const char* operation,
                                              const char* package) {
    using android::base::StringPrintf;

    int32_t pid;
    int32_t uid;

    if (!PermissionCache::checkCallingPermission(String16(permission), &pid, &uid)) {
        return Exception(binder::Status::EX_SECURITY,
                         StringPrintf("UID %d / PID %d lacks permission %s", uid, pid, permission));
    }

    String16 packageName{package};

    PermissionController pc;
    if (auto packageUid = pc.getPackageUid(packageName, 0); packageUid != uid) {
        return Exception(binder::Status::EX_SECURITY,
                         StringPrintf("UID %d / PID %d does not own package %s", uid, pid,
                                      package));
    }

    if (!operation) {
        return binder::Status::ok();
    }

    // Caller must also have op granted.
    switch (auto result = pc.noteOp(String16(operation), uid, packageName); result) {
        case PermissionController::MODE_ALLOWED:
        case PermissionController::MODE_DEFAULT:
            return binder::Status::ok();
        default:
            return Exception(binder::Status::EX_SECURITY,
                             StringPrintf("UID %d / PID %d / package %s lacks app-op %s, error %d",
                                          uid, pid, package, operation, result));
    }
}

} // namespace android::incremental
