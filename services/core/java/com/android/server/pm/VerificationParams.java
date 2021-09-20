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

import static android.content.Intent.EXTRA_LONG_VERSION_CODE;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_VERSION_CODE;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_ID;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.content.pm.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V4;
import static android.os.PowerWhitelistManager.REASON_PACKAGE_VERIFIER;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.server.pm.PackageManagerService.CHECK_PENDING_INTEGRITY_VERIFICATION;
import static com.android.server.pm.PackageManagerService.CHECK_PENDING_VERIFICATION;
import static com.android.server.pm.PackageManagerService.DEBUG_VERIFY;
import static com.android.server.pm.PackageManagerService.ENABLE_ROLLBACK_TIMEOUT;
import static com.android.server.pm.PackageManagerService.PACKAGE_MIME_TYPE;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.DataLoaderType;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.VerifierInfo;
import android.content.pm.parsing.PackageLite;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;

import com.android.server.DeviceIdleInternal;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.io.File;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class VerificationParams extends HandlerParams {
    /**
     * Whether integrity verification is enabled by default.
     */
    private static final boolean DEFAULT_INTEGRITY_VERIFY_ENABLE = true;
    /**
     * The default maximum time to wait for the integrity verification to return in
     * milliseconds.
     */
    private static final long DEFAULT_INTEGRITY_VERIFICATION_TIMEOUT = 30 * 1000;
    /**
     * Whether verification is enabled by default.
     */
    private static final boolean DEFAULT_VERIFY_ENABLE = true;
    /**
     * Timeout duration in milliseconds for enabling package rollback. If we fail to enable
     * rollback within that period, the install will proceed without rollback enabled.
     *
     * <p>If flag value is negative, the default value will be assigned.
     *
     * Flag type: {@code long}
     * Namespace: NAMESPACE_ROLLBACK
     */
    private static final String PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS = "enable_rollback_timeout";
    /**
     * The default duration to wait for rollback to be enabled in
     * milliseconds.
     */
    private static final long DEFAULT_ENABLE_ROLLBACK_TIMEOUT_MILLIS = 10 * 1000;

    final OriginInfo mOriginInfo;
    final IPackageInstallObserver2 mObserver;
    final int mInstallFlags;
    @NonNull
    final InstallSource mInstallSource;
    final String mPackageAbiOverride;
    final VerificationInfo mVerificationInfo;
    final SigningDetails mSigningDetails;
    @Nullable
    MultiPackageVerificationParams mParentVerificationParams;
    final long mRequiredInstalledVersionCode;
    final int mDataLoaderType;
    final int mSessionId;

    private boolean mWaitForVerificationToComplete;
    private boolean mWaitForIntegrityVerificationToComplete;
    private boolean mWaitForEnableRollbackToComplete;
    private int mRet = PackageManager.INSTALL_SUCCEEDED;
    private String mErrorMessage = null;

    final PackageLite mPackageLite;

    VerificationParams(UserHandle user, File stagedDir, IPackageInstallObserver2 observer,
            PackageInstaller.SessionParams sessionParams, InstallSource installSource,
            int installerUid, SigningDetails signingDetails, int sessionId, PackageLite lite,
            PackageManagerService pm) {
        super(user, pm);
        mOriginInfo = OriginInfo.fromStagedFile(stagedDir);
        mObserver = observer;
        mInstallFlags = sessionParams.installFlags;
        mInstallSource = installSource;
        mPackageAbiOverride = sessionParams.abiOverride;
        mVerificationInfo = new VerificationInfo(
                sessionParams.originatingUri,
                sessionParams.referrerUri,
                sessionParams.originatingUid,
                installerUid
        );
        mSigningDetails = signingDetails;
        mRequiredInstalledVersionCode = sessionParams.requiredInstalledVersionCode;
        mDataLoaderType = (sessionParams.dataLoaderParams != null)
                ? sessionParams.dataLoaderParams.getType() : DataLoaderType.NONE;
        mSessionId = sessionId;
        mPackageLite = lite;
    }

    @Override
    public String toString() {
        return "InstallParams{" + Integer.toHexString(System.identityHashCode(this))
                + " file=" + mOriginInfo.mFile + "}";
    }

    public void handleStartCopy() {
        PackageInfoLite pkgLite = PackageManagerServiceUtils.getMinimalPackageInfo(mPm.mContext,
                mPackageLite, mOriginInfo.mResolvedPath, mInstallFlags, mPackageAbiOverride);

        Pair<Integer, String> ret = verifyReplacingVersionCode(
                pkgLite, mRequiredInstalledVersionCode, mInstallFlags);
        setReturnCode(ret.first, ret.second);
        if (mRet != INSTALL_SUCCEEDED) {
            return;
        }

        // Perform package verification and enable rollback (unless we are simply moving the
        // package).
        if (!mOriginInfo.mExisting) {
            if ((mInstallFlags & PackageManager.INSTALL_APEX) == 0) {
                // TODO(b/182426975): treat APEX as APK when APK verification is concerned
                sendApkVerificationRequest(pkgLite);
            }
            if ((mInstallFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0) {
                sendEnableRollbackRequest();
            }
        }
    }

    void sendApkVerificationRequest(PackageInfoLite pkgLite) {
        final int verificationId = mPm.mPendingVerificationToken++;

        PackageVerificationState verificationState =
                new PackageVerificationState(this);
        mPm.mPendingVerification.append(verificationId, verificationState);

        sendIntegrityVerificationRequest(verificationId, pkgLite, verificationState);
        sendPackageVerificationRequest(
                verificationId, pkgLite, verificationState);

        // If both verifications are skipped, we should remove the state.
        if (verificationState.areAllVerificationsComplete()) {
            mPm.mPendingVerification.remove(verificationId);
        }
    }

    void sendEnableRollbackRequest() {
        final int enableRollbackToken = mPm.mPendingEnableRollbackToken++;
        Trace.asyncTraceBegin(
                TRACE_TAG_PACKAGE_MANAGER, "enable_rollback", enableRollbackToken);
        mPm.mPendingEnableRollback.append(enableRollbackToken, this);

        Intent enableRollbackIntent = new Intent(Intent.ACTION_PACKAGE_ENABLE_ROLLBACK);
        enableRollbackIntent.putExtra(
                PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_TOKEN,
                enableRollbackToken);
        enableRollbackIntent.putExtra(
                PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_SESSION_ID,
                mSessionId);
        enableRollbackIntent.setType(PACKAGE_MIME_TYPE);
        enableRollbackIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Allow the broadcast to be sent before boot complete.
        // This is needed when committing the apk part of a staged
        // session in early boot. The rollback manager registers
        // its receiver early enough during the boot process that
        // it will not miss the broadcast.
        enableRollbackIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

        mPm.mContext.sendBroadcastAsUser(enableRollbackIntent, UserHandle.SYSTEM,
                android.Manifest.permission.PACKAGE_ROLLBACK_AGENT);

        mWaitForEnableRollbackToComplete = true;

        // the duration to wait for rollback to be enabled, in millis
        long rollbackTimeout = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ROLLBACK,
                PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS,
                DEFAULT_ENABLE_ROLLBACK_TIMEOUT_MILLIS);
        if (rollbackTimeout < 0) {
            rollbackTimeout = DEFAULT_ENABLE_ROLLBACK_TIMEOUT_MILLIS;
        }
        final Message msg = mPm.mHandler.obtainMessage(ENABLE_ROLLBACK_TIMEOUT);
        msg.arg1 = enableRollbackToken;
        msg.arg2 = mSessionId;
        mPm.mHandler.sendMessageDelayed(msg, rollbackTimeout);
    }

    /**
     * Send a request to check the integrity of the package.
     */
    void sendIntegrityVerificationRequest(
            int verificationId,
            PackageInfoLite pkgLite,
            PackageVerificationState verificationState) {
        if (!isIntegrityVerificationEnabled()) {
            // Consider the integrity check as passed.
            verificationState.setIntegrityVerificationResult(
                    PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
            return;
        }

        final Intent integrityVerification =
                new Intent(Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION);

        integrityVerification.setDataAndType(Uri.fromFile(new File(mOriginInfo.mResolvedPath)),
                PACKAGE_MIME_TYPE);

        final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND;
        integrityVerification.addFlags(flags);

        integrityVerification.putExtra(EXTRA_VERIFICATION_ID, verificationId);
        integrityVerification.putExtra(EXTRA_PACKAGE_NAME, pkgLite.packageName);
        integrityVerification.putExtra(EXTRA_VERSION_CODE, pkgLite.versionCode);
        integrityVerification.putExtra(EXTRA_LONG_VERSION_CODE, pkgLite.getLongVersionCode());
        populateInstallerExtras(integrityVerification);

        // send to integrity component only.
        integrityVerification.setPackage("android");

        final BroadcastOptions options = BroadcastOptions.makeBasic();

        mPm.mContext.sendOrderedBroadcastAsUser(integrityVerification, UserHandle.SYSTEM,
                /* receiverPermission= */ null,
                /* appOp= */ AppOpsManager.OP_NONE,
                /* options= */ options.toBundle(),
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        final Message msg =
                                mPm.mHandler.obtainMessage(CHECK_PENDING_INTEGRITY_VERIFICATION);
                        msg.arg1 = verificationId;
                        mPm.mHandler.sendMessageDelayed(msg, getIntegrityVerificationTimeout());
                    }
                }, /* scheduler= */ null,
                /* initialCode= */ 0,
                /* initialData= */ null,
                /* initialExtras= */ null);

        Trace.asyncTraceBegin(
                TRACE_TAG_PACKAGE_MANAGER, "integrity_verification", verificationId);

        // stop the copy until verification succeeds.
        mWaitForIntegrityVerificationToComplete = true;
    }


    /**
     * Get the integrity verification timeout.
     *
     * @return verification timeout in milliseconds
     */
    private long getIntegrityVerificationTimeout() {
        long timeout = Settings.Global.getLong(mPm.mContext.getContentResolver(),
                Settings.Global.APP_INTEGRITY_VERIFICATION_TIMEOUT,
                DEFAULT_INTEGRITY_VERIFICATION_TIMEOUT);
        // The setting can be used to increase the timeout but not decrease it, since that is
        // equivalent to disabling the integrity component.
        return Math.max(timeout, DEFAULT_INTEGRITY_VERIFICATION_TIMEOUT);
    }

    /**
     * Check whether or not integrity verification has been enabled.
     */
    private boolean isIntegrityVerificationEnabled() {
        // We are not exposing this as a user-configurable setting because we don't want to provide
        // an easy way to get around the integrity check.
        return DEFAULT_INTEGRITY_VERIFY_ENABLE;
    }

    /**
     * Send a request to verifier(s) to verify the package if necessary.
     */
    void sendPackageVerificationRequest(
            int verificationId,
            PackageInfoLite pkgLite,
            PackageVerificationState verificationState) {

        // TODO: http://b/22976637
        // Apps installed for "all" users use the device owner to verify the app
        UserHandle verifierUser = getUser();
        if (verifierUser == UserHandle.ALL) {
            verifierUser = UserHandle.SYSTEM;
        }

        /*
         * Determine if we have any installed package verifiers. If we
         * do, then we'll defer to them to verify the packages.
         */
        final int requiredUid = mPm.mRequiredVerifierPackage == null ? -1
                : mPm.getPackageUid(mPm.mRequiredVerifierPackage, MATCH_DEBUG_TRIAGED_MISSING,
                        verifierUser.getIdentifier());
        verificationState.setRequiredVerifierUid(requiredUid);
        final int installerUid =
                mVerificationInfo == null ? -1 : mVerificationInfo.mInstallerUid;
        final boolean isVerificationEnabled = isVerificationEnabled(
                pkgLite, verifierUser.getIdentifier(), mInstallFlags, installerUid);
        final boolean isV4Signed =
                (mSigningDetails.getSignatureSchemeVersion() == SIGNING_BLOCK_V4);
        final boolean isIncrementalInstall =
                (mDataLoaderType == DataLoaderType.INCREMENTAL);
        // NOTE: We purposefully skip verification for only incremental installs when there's
        // a v4 signature block. Otherwise, proceed with verification as usual.
        if (!mOriginInfo.mExisting
                && isVerificationEnabled
                && (!isIncrementalInstall || !isV4Signed)) {
            final Intent verification = new Intent(
                    Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);
            verification.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            verification.setDataAndType(Uri.fromFile(new File(mOriginInfo.mResolvedPath)),
                    PACKAGE_MIME_TYPE);
            verification.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Query all live verifiers based on current user state
            final ParceledListSlice<ResolveInfo> receivers = mPm.queryIntentReceivers(verification,
                    PACKAGE_MIME_TYPE, 0, verifierUser.getIdentifier());

            if (DEBUG_VERIFY) {
                Slog.d(TAG, "Found " + receivers.getList().size() + " verifiers for intent "
                        + verification.toString() + " with " + pkgLite.verifiers.length
                        + " optional verifiers");
            }

            verification.putExtra(PackageManager.EXTRA_VERIFICATION_ID, verificationId);

            verification.putExtra(
                    PackageManager.EXTRA_VERIFICATION_INSTALL_FLAGS, mInstallFlags);

            verification.putExtra(
                    PackageManager.EXTRA_VERIFICATION_PACKAGE_NAME, pkgLite.packageName);

            verification.putExtra(
                    PackageManager.EXTRA_VERIFICATION_VERSION_CODE, pkgLite.versionCode);

            verification.putExtra(
                    PackageManager.EXTRA_VERIFICATION_LONG_VERSION_CODE,
                    pkgLite.getLongVersionCode());

            populateInstallerExtras(verification);

            final List<ComponentName> sufficientVerifiers = matchVerifiers(pkgLite,
                    receivers.getList(), verificationState);

            DeviceIdleInternal idleController =
                    mPm.mInjector.getLocalService(DeviceIdleInternal.class);
            final long idleDuration = mVerificationHelper.getVerificationTimeout();
            final BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setTemporaryAppAllowlist(idleDuration,
                    TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                    REASON_PACKAGE_VERIFIER, "");

            /*
             * If any sufficient verifiers were listed in the package
             * manifest, attempt to ask them.
             */
            if (sufficientVerifiers != null) {
                final int n = sufficientVerifiers.size();
                if (n == 0) {
                    String errorMsg = "Additional verifiers required, but none installed.";
                    Slog.i(TAG, errorMsg);
                    setReturnCode(PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE, errorMsg);
                } else {
                    for (int i = 0; i < n; i++) {
                        final ComponentName verifierComponent = sufficientVerifiers.get(i);
                        idleController.addPowerSaveTempWhitelistApp(Process.myUid(),
                                verifierComponent.getPackageName(), idleDuration,
                                verifierUser.getIdentifier(), false,
                                REASON_PACKAGE_VERIFIER, "package verifier");

                        final Intent sufficientIntent = new Intent(verification);
                        sufficientIntent.setComponent(verifierComponent);
                        mPm.mContext.sendBroadcastAsUser(sufficientIntent, verifierUser,
                                /* receiverPermission= */ null,
                                options.toBundle());
                    }
                }
            }

            if (mPm.mRequiredVerifierPackage != null) {
                final ComponentName requiredVerifierComponent = matchComponentForVerifier(
                        mPm.mRequiredVerifierPackage, receivers.getList());
                /*
                 * Send the intent to the required verification agent,
                 * but only start the verification timeout after the
                 * target BroadcastReceivers have run.
                 */
                verification.setComponent(requiredVerifierComponent);
                idleController.addPowerSaveTempWhitelistApp(Process.myUid(),
                        mPm.mRequiredVerifierPackage, idleDuration,
                        verifierUser.getIdentifier(), false,
                        REASON_PACKAGE_VERIFIER, "package verifier");
                mPm.mContext.sendOrderedBroadcastAsUser(verification, verifierUser,
                        android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                        /* appOp= */ AppOpsManager.OP_NONE,
                        /* options= */ options.toBundle(),
                        new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                final Message msg = mPm.mHandler
                                        .obtainMessage(CHECK_PENDING_VERIFICATION);
                                msg.arg1 = verificationId;
                                mPm.mHandler.sendMessageDelayed(msg,
                                        mVerificationHelper.getVerificationTimeout());
                            }
                        }, null, 0, null, null);

                Trace.asyncTraceBegin(
                        TRACE_TAG_PACKAGE_MANAGER, "verification", verificationId);

                /*
                 * We don't want the copy to proceed until verification
                 * succeeds.
                 */
                mWaitForVerificationToComplete = true;
            }
        } else {
            verificationState.setVerifierResponse(
                    requiredUid, PackageManager.VERIFICATION_ALLOW);
        }
    }


    private List<ComponentName> matchVerifiers(PackageInfoLite pkgInfo,
            List<ResolveInfo> receivers, final PackageVerificationState verificationState) {
        if (pkgInfo.verifiers.length == 0) {
            return null;
        }

        final int n = pkgInfo.verifiers.length;
        final List<ComponentName> sufficientVerifiers = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            final VerifierInfo verifierInfo = pkgInfo.verifiers[i];

            final ComponentName comp = matchComponentForVerifier(verifierInfo.packageName,
                    receivers);
            if (comp == null) {
                continue;
            }

            final int verifierUid = getUidForVerifier(verifierInfo);
            if (verifierUid == -1) {
                continue;
            }

            if (DEBUG_VERIFY) {
                Slog.d(TAG, "Added sufficient verifier " + verifierInfo.packageName
                        + " with the correct signature");
            }
            sufficientVerifiers.add(comp);
            verificationState.addSufficientVerifier(verifierUid);
        }

        return sufficientVerifiers;
    }

    private int getUidForVerifier(VerifierInfo verifierInfo) {
        synchronized (mPm.mLock) {
            final AndroidPackage pkg = mPm.mPackages.get(verifierInfo.packageName);
            if (pkg == null) {
                return -1;
            } else if (pkg.getSigningDetails().getSignatures().length != 1) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName
                        + " has more than one signature; ignoring");
                return -1;
            }

            /*
             * If the public key of the package's signature does not match
             * our expected public key, then this is a different package and
             * we should skip.
             */

            final byte[] expectedPublicKey;
            try {
                final Signature verifierSig = pkg.getSigningDetails().getSignatures()[0];
                final PublicKey publicKey = verifierSig.getPublicKey();
                expectedPublicKey = publicKey.getEncoded();
            } catch (CertificateException e) {
                return -1;
            }

            final byte[] actualPublicKey = verifierInfo.publicKey.getEncoded();

            if (!Arrays.equals(actualPublicKey, expectedPublicKey)) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName
                        + " does not have the expected public key; ignoring");
                return -1;
            }

            return pkg.getUid();
        }
    }

    private static ComponentName matchComponentForVerifier(String packageName,
            List<ResolveInfo> receivers) {
        ActivityInfo targetReceiver = null;

        final int nr = receivers.size();
        for (int i = 0; i < nr; i++) {
            final ResolveInfo info = receivers.get(i);
            if (info.activityInfo == null) {
                continue;
            }

            if (packageName.equals(info.activityInfo.packageName)) {
                targetReceiver = info.activityInfo;
                break;
            }
        }

        if (targetReceiver == null) {
            return null;
        }

        return new ComponentName(targetReceiver.packageName, targetReceiver.name);
    }

    /**
     * Check whether or not package verification has been enabled.
     *
     * @return true if verification should be performed
     */
    private boolean isVerificationEnabled(
            PackageInfoLite pkgInfoLite, int userId, int installFlags, int installerUid) {
        if (!DEFAULT_VERIFY_ENABLE) {
            return false;
        }

        // Check if installing from ADB
        if ((installFlags & PackageManager.INSTALL_FROM_ADB) != 0) {
            if (mPm.isUserRestricted(userId, UserManager.ENSURE_VERIFY_APPS)) {
                return true;
            }
            // Check if the developer wants to skip verification for ADB installs
            if ((installFlags & PackageManager.INSTALL_DISABLE_VERIFICATION) != 0) {
                synchronized (mPm.mLock) {
                    if (mPm.mSettings.getPackageLPr(pkgInfoLite.packageName) == null) {
                        // Always verify fresh install
                        return true;
                    }
                }
                // Only skip when apk is debuggable
                return !pkgInfoLite.debuggable;
            }
            return Settings.Global.getInt(mPm.mContext.getContentResolver(),
                    Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB, 1) != 0;
        }

        // only when not installed from ADB, skip verification for instant apps when
        // the installer and verifier are the same.
        if ((installFlags & PackageManager.INSTALL_INSTANT_APP) != 0) {
            if (mPm.mInstantAppInstallerActivity != null
                    && mPm.mInstantAppInstallerActivity.packageName.equals(
                    mPm.mRequiredVerifierPackage)) {
                try {
                    mPm.mInjector.getSystemService(AppOpsManager.class)
                            .checkPackage(installerUid, mPm.mRequiredVerifierPackage);
                    if (DEBUG_VERIFY) {
                        Slog.i(TAG, "disable verification for instant app");
                    }
                    return false;
                } catch (SecurityException ignore) { }
            }
        }
        return true;
    }

    void populateInstallerExtras(Intent intent) {
        intent.putExtra(PackageManager.EXTRA_VERIFICATION_INSTALLER_PACKAGE,
                mInstallSource.initiatingPackageName);

        if (mVerificationInfo != null) {
            if (mVerificationInfo.mOriginatingUri != null) {
                intent.putExtra(Intent.EXTRA_ORIGINATING_URI,
                        mVerificationInfo.mOriginatingUri);
            }
            if (mVerificationInfo.mReferrer != null) {
                intent.putExtra(Intent.EXTRA_REFERRER,
                        mVerificationInfo.mReferrer);
            }
            if (mVerificationInfo.mOriginatingUid >= 0) {
                intent.putExtra(Intent.EXTRA_ORIGINATING_UID,
                        mVerificationInfo.mOriginatingUid);
            }
            if (mVerificationInfo.mInstallerUid >= 0) {
                intent.putExtra(PackageManager.EXTRA_VERIFICATION_INSTALLER_UID,
                        mVerificationInfo.mInstallerUid);
            }
        }
    }

    void setReturnCode(int ret, String message) {
        if (mRet == PackageManager.INSTALL_SUCCEEDED) {
            // Only update mRet if it was previously INSTALL_SUCCEEDED to
            // ensure we do not overwrite any previous failure results.
            mRet = ret;
            mErrorMessage = message;
        }
    }

    void handleVerificationFinished() {
        mWaitForVerificationToComplete = false;
        handleReturnCode();
    }

    void handleIntegrityVerificationFinished() {
        mWaitForIntegrityVerificationToComplete = false;
        handleReturnCode();
    }


    void handleRollbackEnabled() {
        // TODO(b/112431924): Consider halting the install if we
        // couldn't enable rollback.
        mWaitForEnableRollbackToComplete = false;
        handleReturnCode();
    }

    @Override
    void handleReturnCode() {
        if (mWaitForVerificationToComplete || mWaitForIntegrityVerificationToComplete
                || mWaitForEnableRollbackToComplete) {
            return;
        }
        sendVerificationCompleteNotification();
    }

    private void sendVerificationCompleteNotification() {
        if (mParentVerificationParams != null) {
            mParentVerificationParams.trySendVerificationCompleteNotification(this, mRet);
        } else {
            try {
                mObserver.onPackageInstalled(null, mRet, mErrorMessage,
                        new Bundle());
            } catch (RemoteException e) {
                Slog.i(TAG, "Observer no longer exists.");
            }
        }
    }

    public void verifyStage() {
        mPm.mHandler.post(this::startCopy);
    }

    public void verifyStage(List<VerificationParams> children)
            throws PackageManagerException {
        final MultiPackageVerificationParams params =
                new MultiPackageVerificationParams(this, children, mPm);
        mPm.mHandler.post(params::startCopy);
    }

    /**
     * Container for a multi-package install which refers to all install sessions and args being
     * committed together.
     */
    static final class MultiPackageVerificationParams extends HandlerParams {
        private final IPackageInstallObserver2 mObserver;
        private final List<VerificationParams> mChildParams;
        private final Map<VerificationParams, Integer> mVerificationState;

        MultiPackageVerificationParams(VerificationParams parent, List<VerificationParams> children,
                PackageManagerService pm) throws PackageManagerException {
            super(parent.getUser(), pm);
            if (children.size() == 0) {
                throw new PackageManagerException("No child sessions found!");
            }
            mChildParams = children;
            // Provide every child with reference to this object as parent
            for (int i = 0; i < children.size(); i++) {
                final VerificationParams childParams = children.get(i);
                childParams.mParentVerificationParams = this;
            }
            mVerificationState = new ArrayMap<>(mChildParams.size());
            mObserver = parent.mObserver;
        }

        @Override
        void handleStartCopy() {
            for (VerificationParams params : mChildParams) {
                params.handleStartCopy();
            }
        }

        @Override
        void handleReturnCode() {
            for (VerificationParams params : mChildParams) {
                params.handleReturnCode();
            }
        }

        void trySendVerificationCompleteNotification(VerificationParams child, int currentStatus) {
            mVerificationState.put(child, currentStatus);
            if (mVerificationState.size() != mChildParams.size()) {
                return;
            }
            int completeStatus = PackageManager.INSTALL_SUCCEEDED;
            String errorMsg = null;
            for (VerificationParams params : mVerificationState.keySet()) {
                int status = params.mRet;
                if (status == PackageManager.INSTALL_UNKNOWN) {
                    return;
                } else if (status != PackageManager.INSTALL_SUCCEEDED) {
                    completeStatus = status;
                    errorMsg = params.mErrorMessage;
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
    }
}
