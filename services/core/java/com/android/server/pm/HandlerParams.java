/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.content.pm.PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE;

import android.annotation.NonNull;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.Pair;
import android.util.Slog;

import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.TAG;

import com.android.internal.util.ArrayUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;

abstract class HandlerParams {
    /** User handle for the user requesting the information or installation. */
    private final UserHandle mUser;
    String mTraceMethod;
    int mTraceCookie;
    @NonNull
    final PackageManagerService mPm;
    final VerificationHelper mVerificationHelper;

    // TODO(b/198166813): remove PMS dependency
    HandlerParams(UserHandle user, PackageManagerService pm) {
        mUser = user;
        mPm = pm;
        mVerificationHelper = new VerificationHelper(mPm.mContext);
    }

    UserHandle getUser() {
        return mUser;
    }

    HandlerParams setTraceMethod(String traceMethod) {
        mTraceMethod = traceMethod;
        return this;
    }

    HandlerParams setTraceCookie(int traceCookie) {
        mTraceCookie = traceCookie;
        return this;
    }

    final void startCopy() {
        if (DEBUG_INSTALL) Slog.i(TAG, "startCopy " + mUser + ": " + this);
        handleStartCopy();
        handleReturnCode();
    }

    abstract void handleStartCopy();
    abstract void handleReturnCode();

    Pair<Integer, String> verifyReplacingVersionCode(PackageInfoLite pkgLite,
            long requiredInstalledVersionCode, int installFlags) {
        if ((installFlags & PackageManager.INSTALL_APEX) != 0) {
            return verifyReplacingVersionCodeForApex(
                    pkgLite, requiredInstalledVersionCode, installFlags);
        }

        String packageName = pkgLite.packageName;
        synchronized (mPm.mLock) {
            // Package which currently owns the data that the new package will own if installed.
            // If an app is uninstalled while keeping data (e.g. adb uninstall -k), installedPkg
            // will be null whereas dataOwnerPkg will contain information about the package
            // which was uninstalled while keeping its data.
            AndroidPackage dataOwnerPkg = mPm.mPackages.get(packageName);
            if (dataOwnerPkg  == null) {
                PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
                if (ps != null) {
                    dataOwnerPkg = ps.pkg;
                }
            }

            if (requiredInstalledVersionCode != PackageManager.VERSION_CODE_HIGHEST) {
                if (dataOwnerPkg == null) {
                    String errorMsg = "Required installed version code was "
                            + requiredInstalledVersionCode
                            + " but package is not installed";
                    Slog.w(TAG, errorMsg);
                    return Pair.create(
                            PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION, errorMsg);
                }

                if (dataOwnerPkg.getLongVersionCode() != requiredInstalledVersionCode) {
                    String errorMsg = "Required installed version code was "
                            + requiredInstalledVersionCode
                            + " but actual installed version is "
                            + dataOwnerPkg.getLongVersionCode();
                    Slog.w(TAG, errorMsg);
                    return Pair.create(
                            PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION, errorMsg);
                }
            }

            if (dataOwnerPkg != null) {
                if (!PackageManagerServiceUtils.isDowngradePermitted(installFlags,
                        dataOwnerPkg.isDebuggable())) {
                    try {
                        checkDowngrade(dataOwnerPkg, pkgLite);
                    } catch (PackageManagerException e) {
                        String errorMsg = "Downgrade detected: " + e.getMessage();
                        Slog.w(TAG, errorMsg);
                        return Pair.create(
                                PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE, errorMsg);
                    }
                }
            }
        }
        return Pair.create(PackageManager.INSTALL_SUCCEEDED, null);
    }

    private Pair<Integer, String> verifyReplacingVersionCodeForApex(PackageInfoLite pkgLite,
            long requiredInstalledVersionCode, int installFlags) {
        String packageName = pkgLite.packageName;

        final PackageInfo activePackage = mPm.mApexManager.getPackageInfo(packageName,
                ApexManager.MATCH_ACTIVE_PACKAGE);
        if (activePackage == null) {
            String errorMsg = "Attempting to install new APEX package " + packageName;
            Slog.w(TAG, errorMsg);
            return Pair.create(PackageManager.INSTALL_FAILED_PACKAGE_CHANGED, errorMsg);
        }

        final long activeVersion = activePackage.getLongVersionCode();
        if (requiredInstalledVersionCode != PackageManager.VERSION_CODE_HIGHEST
                && activeVersion != requiredInstalledVersionCode) {
            String errorMsg = "Installed version of APEX package " + packageName
                    + " does not match required. Active version: " + activeVersion
                    + " required: " + requiredInstalledVersionCode;
            Slog.w(TAG, errorMsg);
            return Pair.create(PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION, errorMsg);
        }

        final boolean isAppDebuggable = (activePackage.applicationInfo.flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        final long newVersionCode = pkgLite.getLongVersionCode();
        if (!PackageManagerServiceUtils.isDowngradePermitted(installFlags, isAppDebuggable)
                && newVersionCode < activeVersion) {
            String errorMsg = "Downgrade of APEX package " + packageName
                    + " is not allowed. Active version: " + activeVersion
                    + " attempted: " + newVersionCode;
            Slog.w(TAG, errorMsg);
            return Pair.create(PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE, errorMsg);
        }

        return Pair.create(PackageManager.INSTALL_SUCCEEDED, null);
    }

    /**
     * Check and throw if the given before/after packages would be considered a
     * downgrade.
     */
    private static void checkDowngrade(AndroidPackage before, PackageInfoLite after)
            throws PackageManagerException {
        if (after.getLongVersionCode() < before.getLongVersionCode()) {
            throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                    "Update version code " + after.versionCode + " is older than current "
                            + before.getLongVersionCode());
        } else if (after.getLongVersionCode() == before.getLongVersionCode()) {
            if (after.baseRevisionCode < before.getBaseRevisionCode()) {
                throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                        "Update base revision code " + after.baseRevisionCode
                                + " is older than current " + before.getBaseRevisionCode());
            }

            if (!ArrayUtils.isEmpty(after.splitNames)) {
                for (int i = 0; i < after.splitNames.length; i++) {
                    final String splitName = after.splitNames[i];
                    final int j = ArrayUtils.indexOf(before.getSplitNames(), splitName);
                    if (j != -1) {
                        if (after.splitRevisionCodes[i] < before.getSplitRevisionCodes()[j]) {
                            throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                                    "Update split " + splitName + " revision code "
                                            + after.splitRevisionCodes[i]
                                            + " is older than current "
                                            + before.getSplitRevisionCodes()[j]);
                        }
                    }
                }
            }
        }
    }
}
