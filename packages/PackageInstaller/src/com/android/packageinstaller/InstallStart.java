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

import static android.content.pm.Flags.usePiaV2;
import static com.android.packageinstaller.PackageUtil.getMaxTargetSdkVersionForUid;

import android.Manifest;
import android.app.Activity;
import android.app.DialogFragment;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import androidx.annotation.Nullable;
import com.android.packageinstaller.v2.ui.InstallLaunch;
import java.util.Arrays;

/**
 * Select which activity is the first visible activity of the installation and forward the intent to
 * it.
 */
public class InstallStart extends Activity {
    private static final String TAG = InstallStart.class.getSimpleName();

    private PackageManager mPackageManager;
    private PackageInstaller mPackageInstaller;
    private UserManager mUserManager;
    private boolean mAbortInstall = false;
    private boolean mShouldFinish = true;

    private final boolean mLocalLOGV = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        if (usePiaV2()) {
            Log.i(TAG, "Using Pia V2");

            Intent piaV2 = new Intent(getIntent());
            piaV2.putExtra(InstallLaunch.EXTRA_CALLING_PKG_NAME, getLaunchedFromPackage());
            piaV2.putExtra(InstallLaunch.EXTRA_CALLING_PKG_UID, getLaunchedFromUid());
            piaV2.setClass(this, InstallLaunch.class);
            piaV2.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
            startActivity(piaV2);
            finish();
            return;
        }
        mPackageManager = getPackageManager();
        mPackageInstaller = mPackageManager.getPackageInstaller();
        mUserManager = getSystemService(UserManager.class);

        Intent intent = getIntent();
        String callingPackage = getLaunchedFromPackage();
        String callingAttributionTag = null;

        // Uid of the source package, coming from ActivityManager
        int callingUid = getLaunchedFromUid();
        if (callingUid == Process.INVALID_UID) {
            Log.w(TAG, "Could not determine the launching uid.");
        }

        // The UID of the origin of the installation. Note that it can be different than the
        // "installer" of the session. For instance, if a 3P caller launched PIA with an ACTION_VIEW
        // intent, the originatingUid is the 3P caller, but the "installer" in this case would
        // be PIA.
        int originatingUid = callingUid;

        final boolean isSessionInstall =
                PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL.equals(intent.getAction())
                        || PackageInstaller.ACTION_CONFIRM_INSTALL.equals(intent.getAction());

        // If the activity was started via a PackageInstaller session, we retrieve the originating
        // UID from that session
        final int sessionId = (isSessionInstall
                ? intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, SessionInfo.INVALID_ID)
                : SessionInfo.INVALID_ID);
        if (sessionId != SessionInfo.INVALID_ID) {
            PackageInstaller.SessionInfo sessionInfo = mPackageInstaller.getSessionInfo(sessionId);
            if (sessionInfo != null) {
                callingAttributionTag = sessionInfo.getInstallerAttributionTag();
                if (sessionInfo.getOriginatingUid() != Process.INVALID_UID) {
                    originatingUid = sessionInfo.getOriginatingUid();
                }
            }
        }

        final ApplicationInfo sourceInfo = getSourceInfo(callingPackage);

        if (callingUid == Process.INVALID_UID && sourceInfo == null) {
            Log.e(TAG, "Cannot determine caller since UID is invalid and sourceInfo is null");
            mAbortInstall = true;
        }

        boolean isDocumentsManager = checkPermission(Manifest.permission.MANAGE_DOCUMENTS,
                -1, callingUid) == PackageManager.PERMISSION_GRANTED;
        boolean isSystemDownloadsProvider = PackageUtil.getSystemDownloadsProviderInfo(
                                                mPackageManager, callingUid) != null;
        boolean isTrustedSource = false;
        if (sourceInfo != null && sourceInfo.isPrivilegedApp()) {
            isTrustedSource = intent.getBooleanExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false) || (
                callingUid != Process.INVALID_UID && checkPermission(
                    Manifest.permission.INSTALL_PACKAGES, -1 /* pid */, callingUid)
                    == PackageManager.PERMISSION_GRANTED);
        }

        if (!isTrustedSource && !isSystemDownloadsProvider && !isDocumentsManager
                && callingUid != Process.INVALID_UID) {
            final int targetSdkVersion = getMaxTargetSdkVersionForUid(this, callingUid);
            if (targetSdkVersion < 0) {
                Log.e(TAG, "Cannot get target sdk version for uid " + callingUid);
                // Invalid originating uid supplied. Abort install.
                mAbortInstall = true;
            } else if (targetSdkVersion >= Build.VERSION_CODES.O && !isUidRequestingPermission(
                callingUid, Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
                Log.e(TAG, "Requesting uid " + callingUid + " needs to declare permission "
                        + Manifest.permission.REQUEST_INSTALL_PACKAGES);
                mAbortInstall = true;
            }
        }

        if (sessionId != -1 && !isCallerSessionOwner(callingUid, sessionId)) {
            Log.e(TAG, "CallingUid " + callingUid + " is not the owner of session " +
                sessionId);
            mAbortInstall = true;
        }

        checkDevicePolicyRestrictions(isTrustedSource);

        final String installerPackageNameFromIntent = getIntent().getStringExtra(
                Intent.EXTRA_INSTALLER_PACKAGE_NAME);
        if (installerPackageNameFromIntent != null) {
            if (!TextUtils.equals(installerPackageNameFromIntent, callingPackage)
                    && mPackageManager.checkPermission(Manifest.permission.INSTALL_PACKAGES,
                    callingPackage) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "The given installer package name " + installerPackageNameFromIntent
                        + " is invalid. Remove it.");
                EventLog.writeEvent(0x534e4554, "236687884", getLaunchedFromUid(),
                        "Invalid EXTRA_INSTALLER_PACKAGE_NAME");
                getIntent().removeExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME);
            }
        }

        if (mAbortInstall) {
            setResult(RESULT_CANCELED);
            if (mShouldFinish) {
                finish();
            }
            return;
        }

        Intent nextActivity = new Intent(intent);
        nextActivity.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // The the installation source as the nextActivity thinks this activity is the source, hence
        // set the originating UID and sourceInfo explicitly
        nextActivity.putExtra(PackageInstallerActivity.EXTRA_CALLING_PACKAGE, callingPackage);
        nextActivity.putExtra(PackageInstallerActivity.EXTRA_CALLING_ATTRIBUTION_TAG,
                callingAttributionTag);
        nextActivity.putExtra(PackageInstallerActivity.EXTRA_ORIGINAL_SOURCE_INFO, sourceInfo);
        nextActivity.putExtra(Intent.EXTRA_ORIGINATING_UID, originatingUid);
        nextActivity.putExtra(PackageInstallerActivity.EXTRA_IS_TRUSTED_SOURCE, isTrustedSource);

        if (isSessionInstall) {
            nextActivity.setClass(this, PackageInstallerActivity.class);
            nextActivity.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        } else {
            Uri packageUri = intent.getData();

            if (packageUri != null
                    && packageUri.getScheme().equals(ContentResolver.SCHEME_CONTENT)
                    && canPackageQuery(callingUid, packageUri)) {
                // [IMPORTANT] This path is deprecated, but should still work. Only necessary
                // features should be added.

                // Stage a session with this file to prevent it from being changed underneath
                // this process.
                nextActivity.setClass(this, InstallStaging.class);
            } else if (packageUri != null && PackageInstallerActivity.SCHEME_PACKAGE.equals(
                    packageUri.getScheme())) {
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
            try {
                startActivity(nextActivity);
            } catch (SecurityException e) {
                Intent result = new Intent();
                result.putExtra(Intent.EXTRA_INSTALL_RESULT,
                        PackageManager.INSTALL_FAILED_INVALID_URI);
                setResult(RESULT_FIRST_USER, result);
            }
        }
        finish();
    }

    private boolean isUidRequestingPermission(int uid, String permission) {
        final String[] packageNames = mPackageManager.getPackagesForUid(uid);
        if (packageNames == null) {
            return false;
        }
        for (final String packageName : packageNames) {
            final PackageInfo packageInfo;
            try {
                packageInfo = mPackageManager.getPackageInfo(packageName,
                        PackageManager.GET_PERMISSIONS);
            } catch (PackageManager.NameNotFoundException e) {
                // Ignore and try the next package
                continue;
            }
            if (packageInfo.requestedPermissions != null
                    && Arrays.asList(packageInfo.requestedPermissions).contains(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the ApplicationInfo for the installation source (the calling package), if available
     */
    private ApplicationInfo getSourceInfo(@Nullable String callingPackage) {
        if (callingPackage != null) {
            try {
                return mPackageManager.getApplicationInfo(callingPackage, 0);
            } catch (PackageManager.NameNotFoundException ex) {
                // ignore
            }
        }
        return null;
    }

    private boolean canPackageQuery(int callingUid, Uri packageUri) {
        ProviderInfo info = mPackageManager.resolveContentProvider(packageUri.getAuthority(),
                PackageManager.ComponentInfoFlags.of(0));
        if (info == null) {
            return false;
        }
        String targetPackage = info.packageName;

        String[] callingPackages = mPackageManager.getPackagesForUid(callingUid);
        if (callingPackages == null) {
            return false;
        }
        for (String callingPackage : callingPackages) {
            try {
                if (mPackageManager.canPackageQuery(callingPackage, targetPackage)) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // no-op
            }
        }
        return false;
    }

    private boolean isCallerSessionOwner(int callingUid, int sessionId) {
        if (callingUid == Process.ROOT_UID) {
            return true;
        }
        PackageInstaller.SessionInfo sessionInfo = mPackageInstaller.getSessionInfo(sessionId);
        if (sessionInfo == null) {
            return false;
        }
        int installerUid = sessionInfo.getInstallerUid();
        return callingUid == installerUid;
    }

    private void checkDevicePolicyRestrictions(boolean isTrustedSource) {
        String[] restrictions;
        if(isTrustedSource) {
            restrictions = new String[] { UserManager.DISALLOW_INSTALL_APPS };
        } else {
            restrictions =  new String[] {
                UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
            };
        }

        final DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        for (String restriction : restrictions) {
            if (!mUserManager.hasUserRestrictionForUser(restriction, Process.myUserHandle())) {
                continue;
            }

            mAbortInstall = true;

            // If the given restriction is set by an admin, display information about the
            // admin enforcing the restriction for the affected user. If not enforced by the admin,
            // show the system dialog.
            final Intent showAdminSupportDetailsIntent = dpm.createAdminSupportIntent(restriction);
            if (showAdminSupportDetailsIntent != null) {
                if (mLocalLOGV) Log.i(TAG, "starting " + showAdminSupportDetailsIntent);
                startActivity(showAdminSupportDetailsIntent);
            } else {
                if (mLocalLOGV) Log.i(TAG, "Restriction set by system: " + restriction);
                mShouldFinish = false;
                showDialogInner(restriction);
            }
            break;
        }
    }

    /**
     * Replace any dialog shown by the dialog with the one for the given
     * {@link #createDialog(String)}.
     *
     * @param restriction The restriction to create the dialog for
     */
    private void showDialogInner(String restriction) {
        if (mLocalLOGV) Log.i(TAG, "showDialogInner(" + restriction + ")");
        DialogFragment currentDialog =
                (DialogFragment) getFragmentManager().findFragmentByTag("dialog");
        if (currentDialog != null) {
            currentDialog.dismissAllowingStateLoss();
        }

        DialogFragment newDialog = createDialog(restriction);
        if (newDialog != null) {
            getFragmentManager().beginTransaction()
                    .add(newDialog, "dialog").commitAllowingStateLoss();
        }
    }

    /**
     * Create a new dialog.
     *
     * @param restriction The restriction to create the dialog for
     * @return The dialog
     */
    private DialogFragment createDialog(String restriction) {
        if (mLocalLOGV) Log.i(TAG, "createDialog(" + restriction + ")");
        switch (restriction) {
            case UserManager.DISALLOW_INSTALL_APPS:
                return PackageUtil.SimpleErrorDialog.newInstance(
                        R.string.install_apps_user_restriction_dlg_text);
            case UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES:
            case UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY:
                return PackageUtil.SimpleErrorDialog.newInstance(
                        R.string.unknown_apps_user_restriction_dlg_text);
        }
        return null;
    }
}
