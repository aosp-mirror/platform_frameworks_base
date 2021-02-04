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

package com.android.server.pm.domain.verify;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.Binder;
import android.os.Process;

import com.android.server.pm.domain.verify.proxy.DomainVerificationProxy;

public class DomainVerificationEnforcer {

    @NonNull
    private final Context mContext;

    public DomainVerificationEnforcer(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Enforced when mutating any state from shell or internally in the system process.
     */
    public void assertInternal(int callingUid) {
        switch (callingUid) {
            case Process.ROOT_UID:
            case Process.SHELL_UID:
            case Process.SYSTEM_UID:
                break;
            default:
                throw new SecurityException(
                        "Caller " + callingUid + " is not allowed to change internal state");
        }
    }

    /**
     * Enforced when retrieving state for a package. The system, the verifier, and anyone approved
     * to mutate user selections are allowed through.
     */
    public void assertApprovedQuerent(int callingUid, @NonNull DomainVerificationProxy proxy) {
        switch (callingUid) {
            case Process.ROOT_UID:
            case Process.SHELL_UID:
            case Process.SYSTEM_UID:
                break;
            default:
                if (!proxy.isCallerVerifier(callingUid)) {
                    mContext.enforcePermission(
                            android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION,
                            Binder.getCallingPid(), callingUid,
                            "Caller " + callingUid
                                    + " is not allowed to query domain verification state");
                }
                break;
        }
    }

    /**
     * Enforced when mutating domain verification state inside an exposed API method.
     */
    public void assertApprovedVerifier(int callingUid, @NonNull DomainVerificationProxy proxy)
            throws SecurityException {
        boolean isAllowed;
        switch (callingUid) {
            case Process.ROOT_UID:
            case Process.SHELL_UID:
            case Process.SYSTEM_UID:
                isAllowed = true;
                break;
            default:
                // TODO(b/159952358): Remove permission check? The component package should
                //  have been checked when the verifier component was first scanned in PMS.
                mContext.enforcePermission(
                        android.Manifest.permission.DOMAIN_VERIFICATION_AGENT,
                        Binder.getCallingPid(), callingUid,
                        "Caller " + callingUid + " does not hold DOMAIN_VERIFICATION_AGENT");
                isAllowed = proxy.isCallerVerifier(callingUid);
                break;
        }

        if (!isAllowed) {
            throw new SecurityException("Caller " + callingUid
                    + " is not the approved domain verification agent, isVerifier = "
                    + proxy.isCallerVerifier(callingUid));
        }
    }

    /**
     * Enforced when mutating user selection state inside an exposed API method.
     */
    public void assertApprovedUserSelector(int callingUid, @UserIdInt int callingUserId,
            @UserIdInt int targetUserId) throws SecurityException {
        if (callingUserId != targetUserId) {
            mContext.enforcePermission(
                    Manifest.permission.INTERACT_ACROSS_USERS,
                    Binder.getCallingPid(), callingUid,
                    "Caller is not allowed to edit other users");
        }

        mContext.enforcePermission(
                android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION,
                Binder.getCallingPid(), callingUid,
                "Caller is not allowed to edit user selections");
    }

    public void callerIsLegacyUserSelector(int callingUid) {
        mContext.enforcePermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS,
                Binder.getCallingPid(), callingUid,
                "Caller is not allowed to edit user state");
    }
}
