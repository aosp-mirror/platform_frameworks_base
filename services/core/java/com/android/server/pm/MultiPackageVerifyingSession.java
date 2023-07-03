/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm;

import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.TAG;

import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import java.util.List;
import java.util.Set;

/**
 * Container for a multi-package verifying session which refers to all verifying sessions and args
 * being committed together.
 */
final class MultiPackageVerifyingSession {
    private final IPackageInstallObserver2 mObserver;
    private final List<VerifyingSession> mChildVerifyingSessions;
    private final Set<VerifyingSession> mVerificationState;
    private final UserHandle mUser;

    MultiPackageVerifyingSession(VerifyingSession parent, List<VerifyingSession> children)
            throws PackageManagerException {
        mUser = parent.getUser();
        if (children.size() == 0) {
            throw PackageManagerException.ofInternalError("No child sessions found!",
                    PackageManagerException.INTERNAL_ERROR_VERIFY_MISSING_CHILD_SESSIONS);
        }
        mChildVerifyingSessions = children;
        // Provide every child with reference to this object as parent
        for (int i = 0; i < children.size(); i++) {
            final VerifyingSession childVerifyingSession = children.get(i);
            childVerifyingSession.mParentVerifyingSession = this;
        }
        mVerificationState = new ArraySet<>(mChildVerifyingSessions.size());
        mObserver = parent.mObserver;
    }

    public void start() {
        if (DEBUG_INSTALL) Slog.i(TAG, "start " + mUser + ": " + this);
        Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, "queueVerify",
                System.identityHashCode(this));
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "startVerify");
        for (VerifyingSession childVerifyingSession : mChildVerifyingSessions) {
            childVerifyingSession.handleStartVerify();
        }
        for (VerifyingSession childVerifyingSession : mChildVerifyingSessions) {
            childVerifyingSession.handleReturnCode();
        }
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    public void trySendVerificationCompleteNotification(VerifyingSession child) {
        mVerificationState.add(child);
        if (mVerificationState.size() != mChildVerifyingSessions.size()) {
            return;
        }
        int completeStatus = PackageManager.INSTALL_SUCCEEDED;
        String errorMsg = null;
        for (VerifyingSession childVerifyingSession : mVerificationState) {
            int status = childVerifyingSession.getRet();
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                completeStatus = status;
                errorMsg = childVerifyingSession.getErrorMessage();
                break;
            }
        }
        try {
            mObserver.onPackageInstalled(null, completeStatus,
                    errorMsg, new Bundle());
        } catch (RemoteException e) {
            Slog.i(TAG, "Observer no longer exists.");
        }
    }

    @Override
    public String toString() {
        return "MultiPackageVerifyingSession{" + Integer.toHexString(System.identityHashCode(this))
                + "}";
    }
}
