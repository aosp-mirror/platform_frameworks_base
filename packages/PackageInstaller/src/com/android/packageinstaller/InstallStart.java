/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.packageinstaller;

import static com.android.packageinstaller.PackageUtil.getMaxTargetSdkVersionForUid;

import android.Manifest;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

/**
 * Select which activity is the first visible activity of the installation and forward the intent to
 * it.
 */
public class InstallStart extends Activity {
    private static final String LOG_TAG = InstallStart.class.getSimpleName();

    private static final String DOWNLOADS_AUTHORITY = "downloads";
    private IPackageManager mIPackageManager;
    private boolean mAbortInstall = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mIPackageManager = AppGlobals.getPackageManager();
        Intent intent = getIntent();
        String callingPackage = getCallingPackage();

        final boolean isSessionInstall =
                PackageInstaller.ACTION_CONFIRM_INSTALL.equals(intent.getAction());

        // If the activity was started via a PackageInstaller session, we retrieve the calling
        // package from that session
        final int sessionId = (isSessionInstall
                ? intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                : -1);
        if (callingPackage == null && sessionId != -1) {
            PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
            callingPackage = (sessionInfo != null) ? sessionInfo.getInstallerPackageName() : null;
        }

        final ApplicationInfo sourceInfo = getSourceInfo(callingPackage);
        final int originatingUid = getOriginatingUid(sourceInfo);
        boolean isTrustedSource = false;
        if (sourceInfo != null
                && (sourceInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
            isTrustedSource = intent.getBooleanExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false);
        }

        if (!isTrustedSource && originatingUid != PackageInstaller.SessionParams.UID_UNKNOWN) {
            final int targetSdkVersion = getMaxTargetSdkVersionForUid(this, originatingUid);
            if (targetSdkVersion < 0) {
                Log.w(LOG_TAG, "Cannot get target sdk version for uid " + originatingUid);
                // Invalid originating uid supplied. Abort install.
                mAbortInstall = true;
            } else if (targetSdkVersion >= Build.VERSION_CODES.O && !declaresAppOpPermission(
                    originatingUid, Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
                Log.e(LOG_TAG, "Requesting uid " + originatingUid + " needs to declare permission "
                        + Manifest.permission.REQUEST_INSTALL_PACKAGES);
                mAbortInstall = true;
            }
        }
        if (mAbortInstall) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        Intent nextActivity = new Intent(intent);
        nextActivity.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);

        // The the installation source as the nextActivity thinks this activity is the source, hence
        // set the originating UID and sourceInfo explicitly
        nextActivity.putExtra(PackageInstallerActivity.EXTRA_CALLING_PACKAGE, callingPackage);
        nextActivity.putExtra(PackageInstallerActivity.EXTRA_ORIGINAL_SOURCE_INFO, sourceInfo);
        nextActivity.putExtra(Intent.EXTRA_ORIGINATING_UID, originatingUid);

        if (isSessionInstall) {
            nextActivity.setClass(this, PackageInstallerActivity.class);
        } else {
            Uri packageUri = intent.getData();

            if (packageUri != null && packageUri.getScheme().equals(
                    ContentResolver.SCHEME_CONTENT)) {
                // [IMPORTANT] This path is deprecated, but should still work. Only necessary
                // features should be added.

                // Copy file to prevent it from being changed underneath this process
                nextActivity.setClass(this, InstallStaging.class);
            } else if (packageUri != null && packageUri.getScheme().equals(
                    PackageInstallerActivity.SCHEME_PACKAGE)) {
                nextActivity.setClass(this, PackageInstallerActivity.class);
            } else {
                Intent result = new Intent();
                result.putExtra(Intent.EXTRA_INSTALL_RESULT,
                        PackageManager.INSTALL_FAILED_INVALID_URI);
                setResult(RESULT_FIRST_USER, result);

                nextActivity = null;
            }
        }

        if (nextActivity != null) {
            startActivity(nextActivity);
        }
        finish();
    }

    private boolean declaresAppOpPermission(int uid, String permission) {
        try {
            final String[] packages = mIPackageManager.getAppOpPermissionPackages(permission);
            if (packages == null) {
                return false;
            }
            for (String packageName : packages) {
                try {
                    if (uid == getPackageManager().getPackageUid(packageName, 0)) {
                        return true;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // Ignore and try the next package
                }
            }
        } catch (RemoteException rexc) {
            // If remote package manager cannot be reached, install will likely fail anyway.
        }
        return false;
    }

    /**
     * @return the ApplicationInfo for the installation source (the calling package), if available
     */
    private ApplicationInfo getSourceInfo(@Nullable String callingPackage) {
        if (callingPackage != null) {
            try {
                return getPackageManager().getApplicationInfo(callingPackage, 0);
            } catch (PackageManager.NameNotFoundException ex) {
                // ignore
            }
        }
        return null;
    }

    /**
     * Get the originating uid if possible, or
     * {@link android.content.pm.PackageInstaller.SessionParams#UID_UNKNOWN} if not available
     *
     * @param sourceInfo The source of this installation
     * @return The UID of the installation source or UID_UNKNOWN
     */
    private int getOriginatingUid(@Nullable ApplicationInfo sourceInfo) {
        // The originating uid from the intent. We only trust/use this if it comes from either
        // the document manager app or the downloads provider
        final int uidFromIntent = getIntent().getIntExtra(Intent.EXTRA_ORIGINATING_UID,
                PackageInstaller.SessionParams.UID_UNKNOWN);

        final int callingUid;
        if (sourceInfo != null) {
            callingUid = sourceInfo.uid;
        } else {
            try {
                callingUid = ActivityManager.getService()
                        .getLaunchedFromUid(getActivityToken());
            } catch (RemoteException ex) {
                // Cannot reach ActivityManager. Aborting install.
                Log.e(LOG_TAG, "Could not determine the launching uid.");
                mAbortInstall = true;
                return PackageInstaller.SessionParams.UID_UNKNOWN;
            }
        }
        try {
            if (mIPackageManager.checkUidPermission(Manifest.permission.MANAGE_DOCUMENTS,
                    callingUid) == PackageManager.PERMISSION_GRANTED) {
                return uidFromIntent;
            }
        } catch (RemoteException rexc) {
            // Ignore. Should not happen.
        }
        if (isSystemDownloadsProvider(callingUid)) {
            return uidFromIntent;
        }
        // We don't trust uid from the intent. Use the calling uid instead.
        return callingUid;
    }

    private boolean isSystemDownloadsProvider(int uid) {
        final ProviderInfo downloadProviderPackage = getPackageManager().resolveContentProvider(
                DOWNLOADS_AUTHORITY, 0);
        if (downloadProviderPackage == null) {
            // There seems to be no currently enabled downloads provider on the system.
            return false;
        }
        final ApplicationInfo appInfo = downloadProviderPackage.applicationInfo;
        return (appInfo.isSystemApp() && uid == appInfo.uid);
    }
}
