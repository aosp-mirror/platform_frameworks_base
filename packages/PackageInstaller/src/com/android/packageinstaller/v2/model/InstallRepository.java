/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.model;

import static com.android.packageinstaller.v2.model.PackageUtil.canPackageQuery;
import static com.android.packageinstaller.v2.model.PackageUtil.generateStubPackageInfo;
import static com.android.packageinstaller.v2.model.PackageUtil.getAppSnippet;
import static com.android.packageinstaller.v2.model.PackageUtil.getPackageInfo;
import static com.android.packageinstaller.v2.model.PackageUtil.getPackageNameForUid;
import static com.android.packageinstaller.v2.model.PackageUtil.isCallerSessionOwner;
import static com.android.packageinstaller.v2.model.PackageUtil.isInstallPermissionGrantedOrRequested;
import static com.android.packageinstaller.v2.model.PackageUtil.isPermissionGranted;
import static com.android.packageinstaller.v2.model.installstagedata.InstallAborted.ABORT_REASON_INTERNAL_ERROR;
import static com.android.packageinstaller.v2.model.installstagedata.InstallAborted.ABORT_REASON_POLICY;
import static com.android.packageinstaller.v2.model.installstagedata.InstallAborted.DLG_PACKAGE_ERROR;
import static com.android.packageinstaller.v2.model.installstagedata.InstallUserActionRequired.USER_ACTION_REASON_ANONYMOUS_SOURCE;
import static com.android.packageinstaller.v2.model.installstagedata.InstallUserActionRequired.USER_ACTION_REASON_INSTALL_CONFIRMATION;
import static com.android.packageinstaller.v2.model.installstagedata.InstallUserActionRequired.USER_ACTION_REASON_UNKNOWN_SOURCE;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.PackageUtil.AppSnippet;
import com.android.packageinstaller.v2.model.installstagedata.InstallAborted;
import com.android.packageinstaller.v2.model.installstagedata.InstallReady;
import com.android.packageinstaller.v2.model.installstagedata.InstallStage;
import com.android.packageinstaller.v2.model.installstagedata.InstallStaging;
import com.android.packageinstaller.v2.model.installstagedata.InstallUserActionRequired;
import java.io.File;
import java.io.IOException;

public class InstallRepository {

    private static final String SCHEME_PACKAGE = "package";
    private static final String TAG = InstallRepository.class.getSimpleName();
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final PackageInstaller mPackageInstaller;
    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final AppOpsManager mAppOpsManager;
    private final MutableLiveData<InstallStage> mStagingResult = new MutableLiveData<>();
    private final boolean mLocalLOGV = false;
    private Intent mIntent;
    private boolean mIsSessionInstall;
    private boolean mIsTrustedSource;
    /**
     * Session ID for a session created when caller uses PackageInstaller APIs
     */
    private int mSessionId;
    /**
     * Session ID for a session created by this app
     */
    private int mStagedSessionId = SessionInfo.INVALID_ID;
    private int mCallingUid;
    private String mCallingPackage;
    private SessionStager mSessionStager;
    private AppOpRequestInfo mAppOpRequestInfo;
    private AppSnippet mAppSnippet;
    /**
     * PackageInfo of the app being installed on device.
     */
    private PackageInfo mNewPackageInfo;

    public InstallRepository(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mPackageInstaller = mPackageManager.getPackageInstaller();
        mDevicePolicyManager = context.getSystemService(DevicePolicyManager.class);
        mUserManager = context.getSystemService(UserManager.class);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
    }

    /**
     * Extracts information from the incoming install intent, checks caller's permission to install
     * packages, verifies that the caller is the install session owner (in case of a session based
     * install) and checks if the current user has restrictions set that prevent app installation,
     *
     * @param intent the incoming {@link Intent} object for installing a package
     * @param callerInfo {@link CallerInfo} that holds the callingUid and callingPackageName
     * @return <p>{@link InstallAborted} if there are errors while performing the checks</p>
     *     <p>{@link InstallStaging} after successfully performing the checks</p>
     */
    public InstallStage performPreInstallChecks(Intent intent, CallerInfo callerInfo) {
        mIntent = intent;

        String callingAttributionTag = null;

        mIsSessionInstall =
            PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL.equals(intent.getAction())
                || PackageInstaller.ACTION_CONFIRM_INSTALL.equals(intent.getAction());

        mSessionId = mIsSessionInstall
            ? intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, SessionInfo.INVALID_ID)
            : SessionInfo.INVALID_ID;

        mCallingPackage = callerInfo.getPackageName();

        if (mCallingPackage == null && mSessionId != SessionInfo.INVALID_ID) {
            PackageInstaller.SessionInfo sessionInfo = mPackageInstaller.getSessionInfo(mSessionId);
            mCallingPackage = (sessionInfo != null) ? sessionInfo.getInstallerPackageName() : null;
            callingAttributionTag =
                (sessionInfo != null) ? sessionInfo.getInstallerAttributionTag() : null;
        }

        // Uid of the source package, coming from ActivityManager
        mCallingUid = callerInfo.getUid();
        if (mCallingUid == Process.INVALID_UID) {
            Log.e(TAG, "Could not determine the launching uid.");
        }
        final ApplicationInfo sourceInfo = getSourceInfo(mCallingPackage);
        // Uid of the source package, with a preference to uid from ApplicationInfo
        final int originatingUid = sourceInfo != null ? sourceInfo.uid : mCallingUid;
        mAppOpRequestInfo = new AppOpRequestInfo(
            getPackageNameForUid(mContext, originatingUid, mCallingPackage),
            originatingUid, callingAttributionTag);

        if (mCallingUid == Process.INVALID_UID && sourceInfo == null) {
            // Caller's identity could not be determined. Abort the install
            return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
        }

        if (!isCallerSessionOwner(mPackageInstaller, originatingUid, mSessionId)) {
            return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
        }

        mIsTrustedSource = isInstallRequestFromTrustedSource(sourceInfo, mIntent, originatingUid);

        if (!isInstallPermissionGrantedOrRequested(mContext, mCallingUid, originatingUid,
            mIsTrustedSource)) {
            return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
        }

        String restriction = getDevicePolicyRestrictions();
        if (restriction != null) {
            InstallAborted.Builder abortedBuilder =
                new InstallAborted.Builder(ABORT_REASON_POLICY).setMessage(restriction);
            final Intent adminSupportDetailsIntent =
                mDevicePolicyManager.createAdminSupportIntent(restriction);
            if (adminSupportDetailsIntent != null) {
                abortedBuilder.setResultIntent(adminSupportDetailsIntent);
            }
            return abortedBuilder.build();
        }

        maybeRemoveInvalidInstallerPackageName(callerInfo);

        return new InstallStaging();
    }

    /**
     * @return the ApplicationInfo for the installation source (the calling package), if available
     */
    @Nullable
    private ApplicationInfo getSourceInfo(@Nullable String callingPackage) {
        if (callingPackage == null) {
            return null;
        }
        try {
            return mPackageManager.getApplicationInfo(callingPackage, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private boolean isInstallRequestFromTrustedSource(ApplicationInfo sourceInfo, Intent intent,
        int originatingUid) {
        boolean isNotUnknownSource = intent.getBooleanExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false);
        return sourceInfo != null && sourceInfo.isPrivilegedApp()
            && (isNotUnknownSource
            || isPermissionGranted(mContext, Manifest.permission.INSTALL_PACKAGES, originatingUid));
    }

    private String getDevicePolicyRestrictions() {
        final String[] restrictions = new String[]{
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
        };

        for (String restriction : restrictions) {
            if (!mUserManager.hasUserRestrictionForUser(restriction, Process.myUserHandle())) {
                continue;
            }
            return restriction;
        }
        return null;
    }

    private void maybeRemoveInvalidInstallerPackageName(CallerInfo callerInfo) {
        final String installerPackageNameFromIntent =
            mIntent.getStringExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME);
        if (installerPackageNameFromIntent == null) {
            return;
        }
        if (!TextUtils.equals(installerPackageNameFromIntent, callerInfo.getPackageName())
            && !isPermissionGranted(mPackageManager, Manifest.permission.INSTALL_PACKAGES,
            callerInfo.getPackageName())) {
            Log.e(TAG, "The given installer package name " + installerPackageNameFromIntent
                + " is invalid. Remove it.");
            EventLog.writeEvent(0x534e4554, "236687884", callerInfo.getUid(),
                "Invalid EXTRA_INSTALLER_PACKAGE_NAME");
            mIntent.removeExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME);
        }
    }

    public void stageForInstall() {
        Uri uri = mIntent.getData();
        if (mIsSessionInstall || (uri != null && SCHEME_PACKAGE.equals(uri.getScheme()))) {
            // For a session based install or installing with a package:// URI, there is no file
            // for us to stage. Setting the mStagingResult as null will signal InstallViewModel to
            // proceed with user confirmation stage.
            mStagingResult.setValue(new InstallReady());
            return;
        }
        if (uri != null
            && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
            && canPackageQuery(mContext, mCallingUid, uri)) {

            if (mStagedSessionId > 0) {
                final PackageInstaller.SessionInfo info =
                    mPackageInstaller.getSessionInfo(mStagedSessionId);
                if (info == null || !info.isActive() || info.getResolvedBaseApkPath() == null) {
                    Log.w(TAG, "Session " + mStagedSessionId + " in funky state; ignoring");
                    if (info != null) {
                        cleanupStagingSession();
                    }
                    mStagedSessionId = 0;
                }
            }

            // Session does not exist, or became invalid.
            if (mStagedSessionId <= 0) {
                // Create session here to be able to show error.
                try (final AssetFileDescriptor afd =
                    mContext.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    ParcelFileDescriptor pfd = afd != null ? afd.getParcelFileDescriptor() : null;
                    PackageInstaller.SessionParams params =
                        createSessionParams(mIntent, pfd, uri.toString());
                    mStagedSessionId = mPackageInstaller.createSession(params);
                } catch (IOException e) {
                    Log.w(TAG, "Failed to create a staging session", e);
                    mStagingResult.setValue(
                        new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR)
                            .setResultIntent(new Intent().putExtra(Intent.EXTRA_INSTALL_RESULT,
                                PackageManager.INSTALL_FAILED_INVALID_APK))
                            .setActivityResultCode(Activity.RESULT_FIRST_USER)
                            .build());
                    return;
                }
            }

            SessionStageListener listener = new SessionStageListener() {
                @Override
                public void onStagingSuccess(SessionInfo info) {
                    //TODO: Verify if the returned sessionInfo should be used anywhere
                    mStagingResult.setValue(new InstallReady());
                }

                @Override
                public void onStagingFailure() {
                    cleanupStagingSession();
                    mStagingResult.setValue(
                        new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR)
                            .setResultIntent(new Intent().putExtra(Intent.EXTRA_INSTALL_RESULT,
                                PackageManager.INSTALL_FAILED_INVALID_APK))
                            .setActivityResultCode(Activity.RESULT_FIRST_USER)
                            .build());
                }
            };
            if (mSessionStager != null) {
                mSessionStager.cancel(true);
            }
            mSessionStager = new SessionStager(mContext, uri, mStagedSessionId, listener);
            mSessionStager.execute();
        }
    }

    private void cleanupStagingSession() {
        if (mStagedSessionId > 0) {
            try {
                mPackageInstaller.abandonSession(mStagedSessionId);
            } catch (SecurityException ignored) {
            }
            mStagedSessionId = 0;
        }
    }

    private PackageInstaller.SessionParams createSessionParams(@NonNull Intent intent,
        @Nullable ParcelFileDescriptor pfd, @NonNull String debugPathName) {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        final Uri referrerUri = intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri.class);
        params.setPackageSource(
            referrerUri != null ? PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                : PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
        params.setInstallAsInstantApp(false);
        params.setReferrerUri(referrerUri);
        params.setOriginatingUri(
            intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI, Uri.class));
        params.setOriginatingUid(intent.getIntExtra(Intent.EXTRA_ORIGINATING_UID,
            Process.INVALID_UID));
        params.setInstallerPackageName(intent.getStringExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME));
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        // Disable full screen intent usage by for sideloads.
        params.setPermissionState(Manifest.permission.USE_FULL_SCREEN_INTENT,
            PackageInstaller.SessionParams.PERMISSION_STATE_DENIED);

        if (pfd != null) {
            try {
                final PackageInstaller.InstallInfo result = mPackageInstaller.readInstallInfo(pfd,
                    debugPathName, 0);
                params.setAppPackageName(result.getPackageName());
                params.setInstallLocation(result.getInstallLocation());
                params.setSize(result.calculateInstalledSize(params, pfd));
            } catch (PackageInstaller.PackageParsingException e) {
                Log.e(TAG, "Cannot parse package " + debugPathName + ". Assuming defaults.", e);
                params.setSize(pfd.getStatSize());
            } catch (IOException e) {
                Log.e(TAG,
                    "Cannot calculate installed size " + debugPathName
                        + ". Try only apk size.", e);
            }
        } else {
            Log.e(TAG, "Cannot parse package " + debugPathName + ". Assuming defaults.");
        }
        return params;
    }

    /**
     * Processes Install session, file:// or package:// URI to generate data pertaining to user
     * confirmation for an install. This method also checks if the source app has the AppOp granted
     * to install unknown apps. If an AppOp is to be requested, cache the user action prompt data to
     * be reused once appOp has been granted
     *
     * @return <ul>
     *     <li>InstallAborted </li>
     *         <ul>
     *             <li> If install session is invalid (not sealed or resolvedBaseApk path
     *             is invalid) </li>
     *             <li> Source app doesn't have visibility to target app </li>
     *             <li> The APK is invalid </li>
     *             <li> URI is invalid </li>
     *             <li> Can't get ApplicationInfo for source app, to request AppOp </li>
     *         </ul>
     *    <li> InstallUserActionRequired</li>
     *         <ul>
     *             <li> If AppOP is granted and user action is required to proceed
     *             with install </li>
     *             <li> If AppOp grant is to be requested from the user</li>
     *         </ul>
     *  </ul>
     */
    public InstallStage requestUserConfirmation() {
        if (mIsTrustedSource) {
            if (mLocalLOGV) {
                Log.i(TAG, "install allowed");
            }
            // Returns InstallUserActionRequired stage if install details could be successfully
            // computed, else it returns InstallAborted.
            return generateConfirmationSnippet();
        } else {
            InstallStage unknownSourceStage = handleUnknownSources(mAppOpRequestInfo);
            if (unknownSourceStage.getStageCode() == InstallStage.STAGE_READY) {
                // Source app already has appOp granted.
                return generateConfirmationSnippet();
            } else {
                return unknownSourceStage;
            }
        }
    }


    private InstallStage generateConfirmationSnippet() {
        final Object packageSource;
        int pendingUserActionReason = -1;
        if (PackageInstaller.ACTION_CONFIRM_INSTALL.equals(mIntent.getAction())) {
            final SessionInfo info = mPackageInstaller.getSessionInfo(mSessionId);
            String resolvedPath = info != null ? info.getResolvedBaseApkPath() : null;

            if (info == null || !info.isSealed() || resolvedPath == null) {
                Log.w(TAG, "Session " + mSessionId + " in funky state; ignoring");
                return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
            }
            packageSource = Uri.fromFile(new File(resolvedPath));
            // TODO: Not sure where is this used yet. PIA.java passes it to
            //  InstallInstalling if not null
            // mOriginatingURI = null;
            // mReferrerURI = null;
            pendingUserActionReason = info.getPendingUserActionReason();
        } else if (PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL.equals(mIntent.getAction())) {
            final SessionInfo info = mPackageInstaller.getSessionInfo(mSessionId);

            if (info == null || !info.isPreApprovalRequested()) {
                Log.w(TAG, "Session " + mSessionId + " in funky state; ignoring");
                return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
            }
            packageSource = info;
            // mOriginatingURI = null;
            // mReferrerURI = null;
            pendingUserActionReason = info.getPendingUserActionReason();
        } else {
            // Two possible origins:
            // 1. Installation with SCHEME_PACKAGE.
            // 2. Installation with "file://" for session created by this app
            if (mIntent.getData() != null && mIntent.getData().getScheme().equals(SCHEME_PACKAGE)) {
                packageSource = mIntent.getData();
            } else {
                SessionInfo stagedSessionInfo = mPackageInstaller.getSessionInfo(mStagedSessionId);
                packageSource = Uri.fromFile(new File(stagedSessionInfo.getResolvedBaseApkPath()));
            }
            // mOriginatingURI = mIntent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI);
            // mReferrerURI = mIntent.getParcelableExtra(Intent.EXTRA_REFERRER);
            pendingUserActionReason = PackageInstaller.REASON_CONFIRM_PACKAGE_CHANGE;
        }

        // if there's nothing to do, quietly slip into the ether
        if (packageSource == null) {
            Log.w(TAG, "Unspecified source");
            return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR)
                .setResultIntent(new Intent().putExtra(Intent.EXTRA_INSTALL_RESULT,
                    PackageManager.INSTALL_FAILED_INVALID_URI))
                .setActivityResultCode(Activity.RESULT_FIRST_USER)
                .build();
        }

        return processAppSnippet(packageSource, pendingUserActionReason);
    }

    /**
     * Parse the Uri (post-commit install session) or use the SessionInfo (pre-commit install
     * session) to set up the installer for this install.
     *
     * @param source The source of package URI or SessionInfo
     * @return {@code true} iff the installer could be set up
     */
    private InstallStage processAppSnippet(Object source, int userActionReason) {
        if (source instanceof Uri) {
            return processPackageUri((Uri) source, userActionReason);
        } else if (source instanceof SessionInfo) {
            return processSessionInfo((SessionInfo) source, userActionReason);
        }
        return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
    }

    /**
     * Parse the Uri and set up the installer for this package.
     *
     * @param packageUri The URI to parse
     * @return {@code true} iff the installer could be set up
     */
    private InstallStage processPackageUri(final Uri packageUri, int userActionReason) {
        final String scheme = packageUri.getScheme();
        final String packageName = packageUri.getSchemeSpecificPart();

        if (scheme == null) {
            return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
        }

        if (mLocalLOGV) {
            Log.i(TAG, "processPackageUri(): uri = " + packageUri + ", scheme = " + scheme);
        }

        switch (scheme) {
            case SCHEME_PACKAGE -> {
                for (UserHandle handle : mUserManager.getUserHandles(true)) {
                    PackageManager pmForUser = mContext.createContextAsUser(handle, 0)
                        .getPackageManager();
                    try {
                        if (pmForUser.canPackageQuery(mCallingPackage, packageName)) {
                            mNewPackageInfo = pmForUser.getPackageInfo(packageName,
                                PackageManager.GET_PERMISSIONS
                                    | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                        }
                    } catch (NameNotFoundException ignored) {
                    }
                }
                if (mNewPackageInfo == null) {
                    Log.w(TAG, "Requested package " + packageUri.getSchemeSpecificPart()
                        + " not available. Discontinuing installation");
                    return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR)
                        .setErrorDialogType(DLG_PACKAGE_ERROR)
                        .setResultIntent(new Intent().putExtra(Intent.EXTRA_INSTALL_RESULT,
                            PackageManager.INSTALL_FAILED_INVALID_APK))
                        .setActivityResultCode(Activity.RESULT_FIRST_USER)
                        .build();
                }
                mAppSnippet = getAppSnippet(mContext, mNewPackageInfo);
                if (mLocalLOGV) {
                    Log.i(TAG, "Created snippet for " + mAppSnippet.getLabel());
                }
            }
            case ContentResolver.SCHEME_FILE -> {
                File sourceFile = new File(packageUri.getPath());
                mNewPackageInfo = getPackageInfo(mContext, sourceFile,
                    PackageManager.GET_PERMISSIONS);

                // Check for parse errors
                if (mNewPackageInfo == null) {
                    Log.w(TAG, "Parse error when parsing manifest. Discontinuing installation");
                    return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR)
                        .setErrorDialogType(DLG_PACKAGE_ERROR)
                        .setResultIntent(new Intent().putExtra(Intent.EXTRA_INSTALL_RESULT,
                            PackageManager.INSTALL_FAILED_INVALID_APK))
                        .setActivityResultCode(Activity.RESULT_FIRST_USER)
                        .build();
                }
                if (mLocalLOGV) {
                    Log.i(TAG, "Creating snippet for local file " + sourceFile);
                }
                mAppSnippet = getAppSnippet(mContext, mNewPackageInfo.applicationInfo, sourceFile);
            }
            default -> {
                Log.e(TAG, "Unexpected URI scheme " + packageUri);
                return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
            }
        }

        return new InstallUserActionRequired.Builder(
            USER_ACTION_REASON_INSTALL_CONFIRMATION, mAppSnippet)
            .setDialogMessage(getUpdateMessage(mNewPackageInfo, userActionReason))
            .setAppUpdating(isAppUpdating(mNewPackageInfo))
            .build();
    }

    /**
     * Use the SessionInfo and set up the installer for pre-commit install session.
     *
     * @param sessionInfo The SessionInfo to compose
     * @return {@code true} iff the installer could be set up
     */
    private InstallStage processSessionInfo(@NonNull SessionInfo sessionInfo,
        int userActionReason) {
        mNewPackageInfo = generateStubPackageInfo(sessionInfo.getAppPackageName());

        mAppSnippet = getAppSnippet(mContext, sessionInfo);
        return new InstallUserActionRequired.Builder(
            USER_ACTION_REASON_INSTALL_CONFIRMATION, mAppSnippet)
            .setAppUpdating(isAppUpdating(mNewPackageInfo))
            .setDialogMessage(getUpdateMessage(mNewPackageInfo, userActionReason))
            .build();
    }

    private String getUpdateMessage(PackageInfo pkgInfo, int userActionReason) {
        if (isAppUpdating(pkgInfo)) {
            final CharSequence existingUpdateOwnerLabel = getExistingUpdateOwnerLabel(pkgInfo);
            final CharSequence requestedUpdateOwnerLabel = getApplicationLabel(mCallingPackage);

            if (!TextUtils.isEmpty(existingUpdateOwnerLabel)
                && userActionReason == PackageInstaller.REASON_REMIND_OWNERSHIP) {
                return mContext.getString(R.string.install_confirm_question_update_owner_reminder,
                    requestedUpdateOwnerLabel, existingUpdateOwnerLabel);
            }
        }
        return null;
    }

    private CharSequence getExistingUpdateOwnerLabel(PackageInfo pkgInfo) {
        try {
            final String packageName = pkgInfo.packageName;
            final InstallSourceInfo sourceInfo = mPackageManager.getInstallSourceInfo(packageName);
            final String existingUpdateOwner = sourceInfo.getUpdateOwnerPackageName();
            return getApplicationLabel(existingUpdateOwner);
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    private CharSequence getApplicationLabel(String packageName) {
        try {
            final ApplicationInfo appInfo = mPackageManager.getApplicationInfo(packageName,
                ApplicationInfoFlags.of(0));
            return mPackageManager.getApplicationLabel(appInfo);
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    private boolean isAppUpdating(PackageInfo newPkgInfo) {
        String pkgName = newPkgInfo.packageName;
        // Check if there is already a package on the device with this name
        // but it has been renamed to something else.
        String[] oldName = mPackageManager.canonicalToCurrentPackageNames(new String[]{pkgName});
        if (oldName != null && oldName.length > 0 && oldName[0] != null) {
            pkgName = oldName[0];
            newPkgInfo.packageName = pkgName;
            newPkgInfo.applicationInfo.packageName = pkgName;
        }
        // Check if package is already installed. display confirmation dialog if replacing pkg
        try {
            // This is a little convoluted because we want to get all uninstalled
            // apps, but this may include apps with just data, and if it is just
            // data we still want to count it as "installed".
            ApplicationInfo appInfo = mPackageManager.getApplicationInfo(pkgName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES);
            if ((appInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                return false;
            }
        } catch (NameNotFoundException e) {
            return false;
        }
        return true;
    }

    private InstallStage handleUnknownSources(AppOpRequestInfo requestInfo) {
        if (requestInfo.getCallingPackage() == null) {
            Log.i(TAG, "No source found for package " + mNewPackageInfo.packageName);
            return new InstallUserActionRequired.Builder(
                USER_ACTION_REASON_ANONYMOUS_SOURCE, null)
                .build();
        }
        // Shouldn't use static constant directly, see b/65534401.
        final String appOpStr =
            AppOpsManager.permissionToOp(Manifest.permission.REQUEST_INSTALL_PACKAGES);
        final int appOpMode = mAppOpsManager.noteOpNoThrow(appOpStr,
            requestInfo.getOriginatingUid(),
            requestInfo.getCallingPackage(), requestInfo.getAttributionTag(),
            "Started package installation activity");

        if (mLocalLOGV) {
            Log.i(TAG, "handleUnknownSources(): appMode=" + appOpMode);
        }
        switch (appOpMode) {
            case AppOpsManager.MODE_DEFAULT:
                mAppOpsManager.setMode(appOpStr, requestInfo.getOriginatingUid(),
                    requestInfo.getCallingPackage(), AppOpsManager.MODE_ERRORED);
                // fall through
            case AppOpsManager.MODE_ERRORED:
                try {
                    ApplicationInfo sourceInfo =
                        mPackageManager.getApplicationInfo(requestInfo.getCallingPackage(), 0);
                    AppSnippet sourceAppSnippet = getAppSnippet(mContext, sourceInfo);
                    return new InstallUserActionRequired.Builder(
                        USER_ACTION_REASON_UNKNOWN_SOURCE, sourceAppSnippet)
                        .setDialogMessage(requestInfo.getCallingPackage())
                        .build();
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Did not find appInfo for " + requestInfo.getCallingPackage());
                    return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
                }
            case AppOpsManager.MODE_ALLOWED:
                return new InstallReady();
            default:
                Log.e(TAG, "Invalid app op mode " + appOpMode
                    + " for OP_REQUEST_INSTALL_PACKAGES found for uid "
                    + requestInfo.getOriginatingUid());
                return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
        }
    }

    /**
     * Cleanup the staged session. Also signal the packageinstaller that an install session is to
     * be aborted
     */
    public void cleanupInstall() {
        if (mSessionId > 0) {
            mPackageInstaller.setPermissionsResult(mSessionId, false);
        } else if (mStagedSessionId > 0) {
            cleanupStagingSession();
        }
    }

    /**
     * When the identity of the install source could not be determined, user can skip checking the
     * source and directly proceed with the install.
     */
    public InstallStage forcedSkipSourceCheck() {
        return generateConfirmationSnippet();
    }

    public MutableLiveData<Integer> getStagingProgress() {
        if (mSessionStager != null) {
            return mSessionStager.getProgress();
        }
        return new MutableLiveData<>(0);
    }

    public MutableLiveData<InstallStage> getStagingResult() {
        return mStagingResult;
    }

    public interface SessionStageListener {

        void onStagingSuccess(SessionInfo info);

        void onStagingFailure();
    }

    public static class CallerInfo {

        private final String mPackageName;
        private final int mUid;

        public CallerInfo(String packageName, int uid) {
            mPackageName = packageName;
            mUid = uid;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public int getUid() {
            return mUid;
        }
    }

    public static class AppOpRequestInfo {

        private String mCallingPackage;
        private String mAttributionTag;
        private int mOrginatingUid;

        public AppOpRequestInfo(String callingPackage, int orginatingUid, String attributionTag) {
            mCallingPackage = callingPackage;
            mOrginatingUid = orginatingUid;
            mAttributionTag = attributionTag;
        }

        public String getCallingPackage() {
            return mCallingPackage;
        }

        public String getAttributionTag() {
            return mAttributionTag;
        }

        public int getOriginatingUid() {
            return mOrginatingUid;
        }
    }
}
