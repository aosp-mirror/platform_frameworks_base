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

import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Container for a multi-package install which refers to all install sessions and args being
 * committed together.
 */
final class MultiPackageInstallingSession {
    private final List<InstallingSession> mChildInstallingSessions;
    private final Map<InstallArgs, Integer> mCurrentState;
    @NonNull
    final PackageManagerService mPm;
    final UserHandle mUser;

    MultiPackageInstallingSession(UserHandle user, List<InstallingSession> childInstallingSessions,
            PackageManagerService pm)
            throws PackageManagerException {
        if (childInstallingSessions.size() == 0) {
            throw new PackageManagerException("No child sessions found!");
        }
        mPm = pm;
        mUser = user;
        mChildInstallingSessions = childInstallingSessions;
        for (int i = 0; i < childInstallingSessions.size(); i++) {
            final InstallingSession childInstallingSession = childInstallingSessions.get(i);
            childInstallingSession.mParentInstallingSession = this;
        }
        this.mCurrentState = new ArrayMap<>(mChildInstallingSessions.size());
    }

    public void start() {
        if (DEBUG_INSTALL) Slog.i(TAG, "start " + mUser + ": " + this);
        Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                System.identityHashCode(this));
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "start");
        for (InstallingSession childInstallingSession : mChildInstallingSessions) {
            childInstallingSession.handleStartCopy();
        }
        for (InstallingSession childInstallingSession : mChildInstallingSessions) {
            childInstallingSession.handleReturnCode();
        }
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    public void tryProcessInstallRequest(InstallArgs args, int currentStatus) {
        mCurrentState.put(args, currentStatus);
        if (mCurrentState.size() != mChildInstallingSessions.size()) {
            return;
        }
        int completeStatus = PackageManager.INSTALL_SUCCEEDED;
        for (Integer status : mCurrentState.values()) {
            if (status == PackageManager.INSTALL_UNKNOWN) {
                return;
            } else if (status != PackageManager.INSTALL_SUCCEEDED) {
                completeStatus = status;
                break;
            }
        }
        final List<InstallRequest> installRequests = new ArrayList<>(mCurrentState.size());
        for (Map.Entry<InstallArgs, Integer> entry : mCurrentState.entrySet()) {
            installRequests.add(new InstallRequest(entry.getKey(),
                    new PackageInstalledInfo(completeStatus)));
        }
        int finalCompleteStatus = completeStatus;
        final InstallPackageHelper installPackageHelper = new InstallPackageHelper(mPm);
        mPm.mHandler.post(() -> installPackageHelper.processInstallRequests(
                finalCompleteStatus == PackageManager.INSTALL_SUCCEEDED /* success */,
                installRequests));
    }

    @Override
    public String toString() {
        return "MultiPackageInstallingSession{" + Integer.toHexString(System.identityHashCode(this))
                + "}";
    }
}
