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

package com.android.server.pm.verify.domain;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;

import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy;

public class DomainVerificationEnforcer {

    @NonNull
    private final Context mContext;

    @NonNull
    private Callback mCallback;

    public DomainVerificationEnforcer(@NonNull Context context) {
        mContext = context;
    }

    public void setCallback(@NonNull Callback callback) {
        mCallback = callback;
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
                    mContext.enforcePermission(android.Manifest.permission.DUMP,
                            Binder.getCallingPid(), callingUid,
                            "Caller " + callingUid
                                    + " is not allowed to query domain verification state");
                    break;
                }

                mContext.enforcePermission(android.Manifest.permission.QUERY_ALL_PACKAGES,
                        Binder.getCallingPid(), callingUid,
                        "Caller " + callingUid + " does not hold "
                                + android.Manifest.permission.QUERY_ALL_PACKAGES);
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
                final int callingPid = Binder.getCallingPid();
                boolean isLegacyVerificationAgent = false;
                if (mContext.checkPermission(
                        android.Manifest.permission.DOMAIN_VERIFICATION_AGENT, callingPid,
                        callingUid) != PackageManager.PERMISSION_GRANTED) {
                    isLegacyVerificationAgent = mContext.checkPermission(
                            android.Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT,
                            callingPid, callingUid) == PackageManager.PERMISSION_GRANTED;
                    if (!isLegacyVerificationAgent) {
                        throw new SecurityException("Caller " + callingUid + " does not hold "
                                + android.Manifest.permission.DOMAIN_VERIFICATION_AGENT);
                    }
                }

                // If the caller isn't a legacy verifier, it needs the QUERY_ALL permission
                if (!isLegacyVerificationAgent) {
                    mContext.enforcePermission(android.Manifest.permission.QUERY_ALL_PACKAGES,
                            callingPid, callingUid, "Caller " + callingUid + " does not hold "
                                    + android.Manifest.permission.QUERY_ALL_PACKAGES);
                }

                isAllowed = proxy.isCallerVerifier(callingUid);
                break;
        }

        if (!isAllowed) {
            throw new SecurityException("Caller " + callingUid
                    + " is not the approved domain verification agent");
        }
    }

    /**
     * Enforced when mutating user selection state inside an exposed API method.
     */
    public boolean assertApprovedUserStateQuerent(int callingUid, @UserIdInt int callingUserId,
            @NonNull String packageName, @UserIdInt int targetUserId) throws SecurityException {
        if (callingUserId != targetUserId) {
            mContext.enforcePermission(
                    Manifest.permission.INTERACT_ACROSS_USERS,
                    Binder.getCallingPid(), callingUid,
                    "Caller is not allowed to edit other users");
        }

        if (!mCallback.doesUserExist(callingUserId)) {
            throw new SecurityException("User " + callingUserId + " does not exist");
        } else if (!mCallback.doesUserExist(targetUserId)) {
            throw new SecurityException("User " + targetUserId + " does not exist");
        }

        return !mCallback.filterAppAccess(packageName, callingUid, targetUserId);
    }

    /**
     * Enforced when mutating user selection state inside an exposed API method.
     */
    public boolean assertApprovedUserSelector(int callingUid, @UserIdInt int callingUserId,
            @Nullable String packageName, @UserIdInt int targetUserId) throws SecurityException {
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

        if (!mCallback.doesUserExist(callingUserId)) {
            throw new SecurityException("User " + callingUserId + " does not exist");
        } else if (!mCallback.doesUserExist(targetUserId)) {
            throw new SecurityException("User " + targetUserId + " does not exist");
        }

        if (packageName == null) {
            return true;
        }

        return !mCallback.filterAppAccess(packageName, callingUid, targetUserId);
    }

    public boolean callerIsLegacyUserSelector(int callingUid, @UserIdInt int callingUserId,
            @NonNull String packageName, @UserIdInt int targetUserId) {
        mContext.enforcePermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS,
                Binder.getCallingPid(), callingUid,
                "Caller is not allowed to edit user state");

        if (callingUserId != targetUserId) {
            if (mContext.checkPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS,
                    Binder.getCallingPid(), callingUid) != PackageManager.PERMISSION_GRANTED) {
                // Legacy API did not enforce this, so for backwards compatibility, fail silently
                return false;
            }
        }

        if (!mCallback.doesUserExist(callingUserId)) {
            throw new SecurityException("User " + callingUserId + " does not exist");
        } else if (!mCallback.doesUserExist(targetUserId)) {
            throw new SecurityException("User " + targetUserId + " does not exist");
        }

        return !mCallback.filterAppAccess(packageName, callingUid, targetUserId);
    }

    public boolean callerIsLegacyUserQuerent(int callingUid, @UserIdInt int callingUserId,
            @NonNull String packageName, @UserIdInt int targetUserId) {
        if (callingUserId != targetUserId) {
            // The legacy API enforces the _FULL variant, so maintain that here
            mContext.enforcePermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    Binder.getCallingPid(), callingUid,
                    "Caller is not allowed to edit other users");
        }

        if (!mCallback.doesUserExist(callingUserId)) {
            throw new SecurityException("User " + callingUserId + " does not exist");
        } else if (!mCallback.doesUserExist(targetUserId)) {
            throw new SecurityException("User " + targetUserId + " does not exist");
        }

        return !mCallback.filterAppAccess(packageName, callingUid, targetUserId);
    }

    /**
     * Querying for the owners of a domain. Because this API cannot filter the returned list of
     * packages, enforces {@link android.Manifest.permission.QUERY_ALL_PACKAGES}, but also enforces
     * {@link android.Manifest.permission.INTERACT_ACROSS_USERS} because each user has a different
     * state.
     */
    public void assertOwnerQuerent(int callingUid, @UserIdInt int callingUserId,
            @UserIdInt int targetUserId) {
        final int callingPid = Binder.getCallingPid();
        if (callingUserId != targetUserId) {
            mContext.enforcePermission(android.Manifest.permission.INTERACT_ACROSS_USERS,
                    callingPid, callingUid, "Caller is not allowed to query other users");
        }

        mContext.enforcePermission(android.Manifest.permission.QUERY_ALL_PACKAGES,
                callingPid, callingUid, "Caller " + callingUid + " does not hold "
                        + android.Manifest.permission.QUERY_ALL_PACKAGES);

        mContext.enforcePermission(
                android.Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION,
                callingPid, callingUid, "Caller is not allowed to query user selections");

        if (!mCallback.doesUserExist(callingUserId)) {
            throw new SecurityException("User " + callingUserId + " does not exist");
        } else if (!mCallback.doesUserExist(targetUserId)) {
            throw new SecurityException("User " + targetUserId + " does not exist");
        }
    }

    public interface Callback {
        /**
         * @return true if access to the given package should be filtered and the method failed as
         * if the package was not installed
         */
        boolean filterAppAccess(@NonNull String packageName, int callingUid, @UserIdInt int userId);

        boolean doesUserExist(@UserIdInt int userId);
    }
}
