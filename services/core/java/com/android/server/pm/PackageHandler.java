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

import static android.content.pm.PackageManager.INSTALL_FAILED_PACKAGE_CHANGED;
import static android.os.PowerExemptionManager.REASON_PACKAGE_REPLACED;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.server.pm.PackageManagerService.CHECK_PENDING_INTEGRITY_VERIFICATION;
import static com.android.server.pm.PackageManagerService.CHECK_PENDING_VERIFICATION;
import static com.android.server.pm.PackageManagerService.DEBUG_BACKUP;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEFAULT_VERIFICATION_RESPONSE;
import static com.android.server.pm.PackageManagerService.DEFERRED_NO_KILL_INSTALL_OBSERVER;
import static com.android.server.pm.PackageManagerService.DEFERRED_NO_KILL_POST_DELETE;
import static com.android.server.pm.PackageManagerService.DEFERRED_NO_KILL_POST_DELETE_DELAY_MS;
import static com.android.server.pm.PackageManagerService.DOMAIN_VERIFICATION;
import static com.android.server.pm.PackageManagerService.EMPTY_INT_ARRAY;
import static com.android.server.pm.PackageManagerService.ENABLE_ROLLBACK_STATUS;
import static com.android.server.pm.PackageManagerService.ENABLE_ROLLBACK_TIMEOUT;
import static com.android.server.pm.PackageManagerService.INIT_COPY;
import static com.android.server.pm.PackageManagerService.INSTANT_APP_RESOLUTION_PHASE_TWO;
import static com.android.server.pm.PackageManagerService.INTEGRITY_VERIFICATION_COMPLETE;
import static com.android.server.pm.PackageManagerService.PACKAGE_VERIFIED;
import static com.android.server.pm.PackageManagerService.POST_INSTALL;
import static com.android.server.pm.PackageManagerService.SEND_PENDING_BROADCAST;
import static com.android.server.pm.PackageManagerService.SNAPSHOT_UNCORK;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerService.TRACE_SNAPSHOTS;
import static com.android.server.pm.PackageManagerService.WRITE_PACKAGE_LIST;
import static com.android.server.pm.PackageManagerService.WRITE_PACKAGE_RESTRICTIONS;
import static com.android.server.pm.PackageManagerService.WRITE_SETTINGS;

import android.content.Intent;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.InstantAppRequest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.stats.storage.StorageEnums;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.EventLogTags;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import dalvik.system.VMRuntime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Part of PackageManagerService that handles events.
 */
public final class PackageHandler extends Handler {
    private final PackageManagerService mPm;
    private final VerificationHelper mVerificationHelper;

    PackageHandler(Looper looper, PackageManagerService pm) {
        super(looper);
        mPm = pm;
        mVerificationHelper = new VerificationHelper(mPm.mContext);
    }

    @Override
    public void handleMessage(Message msg) {
        try {
            doHandleMessage(msg);
        } finally {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        }
    }

    void doHandleMessage(Message msg) {
        switch (msg.what) {
            case INIT_COPY: {
                HandlerParams params = (HandlerParams) msg.obj;
                if (params != null) {
                    if (DEBUG_INSTALL) Slog.i(TAG, "init_copy: " + params);
                    Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                            System.identityHashCode(params));
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "startCopy");
                    params.startCopy();
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
                break;
            }
            case SEND_PENDING_BROADCAST: {
                String[] packages;
                ArrayList<String>[] components;
                int size = 0;
                int[] uids;
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                synchronized (mPm.mLock) {
                    size = mPm.mPendingBroadcasts.size();
                    if (size <= 0) {
                        // Nothing to be done. Just return
                        return;
                    }
                    packages = new String[size];
                    components = new ArrayList[size];
                    uids = new int[size];
                    int i = 0;  // filling out the above arrays

                    for (int n = 0; n < mPm.mPendingBroadcasts.userIdCount(); n++) {
                        final int packageUserId = mPm.mPendingBroadcasts.userIdAt(n);
                        final ArrayMap<String, ArrayList<String>> componentsToBroadcast =
                                mPm.mPendingBroadcasts.packagesForUserId(packageUserId);
                        final int numComponents = componentsToBroadcast.size();
                        for (int index = 0; i < size && index < numComponents; index++) {
                            packages[i] = componentsToBroadcast.keyAt(index);
                            components[i] = componentsToBroadcast.valueAt(index);
                            final PackageSetting ps = mPm.mSettings.getPackageLPr(packages[i]);
                            uids[i] = (ps != null)
                                    ? UserHandle.getUid(packageUserId, ps.getAppId())
                                    : -1;
                            i++;
                        }
                    }
                    size = i;
                    mPm.mPendingBroadcasts.clear();
                }
                // Send broadcasts
                for (int i = 0; i < size; i++) {
                    mPm.sendPackageChangedBroadcast(packages[i], true /* dontKillApp */,
                            components[i], uids[i], null /* reason */);
                }
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                break;
            }
            case POST_INSTALL: {
                if (DEBUG_INSTALL) Log.v(TAG, "Handling post-install for " + msg.arg1);

                PackageManagerService.PostInstallData data = mPm.mRunningInstalls.get(msg.arg1);
                final boolean didRestore = (msg.arg2 != 0);
                mPm.mRunningInstalls.delete(msg.arg1);

                if (data != null && data.res.mFreezer != null) {
                    data.res.mFreezer.close();
                }

                if (data != null && data.mPostInstallRunnable != null) {
                    data.mPostInstallRunnable.run();
                } else if (data != null && data.args != null) {
                    InstallArgs args = data.args;
                    PackageInstalledInfo parentRes = data.res;

                    final boolean killApp = (args.mInstallFlags
                            & PackageManager.INSTALL_DONT_KILL_APP) == 0;
                    final boolean virtualPreload = ((args.mInstallFlags
                            & PackageManager.INSTALL_VIRTUAL_PRELOAD) != 0);

                    handlePackagePostInstall(parentRes, killApp, virtualPreload,
                            didRestore, args.mInstallSource.installerPackageName,
                            args.mObserver, args.mDataLoaderType);

                    // Log tracing if needed
                    if (args.mTraceMethod != null) {
                        Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, args.mTraceMethod,
                                args.mTraceCookie);
                    }
                } else if (DEBUG_INSTALL) {
                    // No post-install when we run restore from installExistingPackageForUser
                    Slog.i(TAG, "Nothing to do for post-install token " + msg.arg1);
                }

                Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, "postInstall", msg.arg1);
            } break;
            case DEFERRED_NO_KILL_POST_DELETE: {
                synchronized (mPm.mInstallLock) {
                    InstallArgs args = (InstallArgs) msg.obj;
                    if (args != null) {
                        args.doPostDeleteLI(true);
                    }
                }
            } break;
            case DEFERRED_NO_KILL_INSTALL_OBSERVER: {
                String packageName = (String) msg.obj;
                if (packageName != null) {
                    mPm.notifyInstallObserver(packageName);
                }
            } break;
            case WRITE_SETTINGS: {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                synchronized (mPm.mLock) {
                    removeMessages(WRITE_SETTINGS);
                    removeMessages(WRITE_PACKAGE_RESTRICTIONS);
                    mPm.writeSettingsLPrTEMP();
                    mPm.mDirtyUsers.clear();
                }
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } break;
            case WRITE_PACKAGE_RESTRICTIONS: {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                synchronized (mPm.mLock) {
                    removeMessages(WRITE_PACKAGE_RESTRICTIONS);
                    for (int userId : mPm.mDirtyUsers) {
                        mPm.mSettings.writePackageRestrictionsLPr(userId);
                    }
                    mPm.mDirtyUsers.clear();
                }
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } break;
            case WRITE_PACKAGE_LIST: {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                synchronized (mPm.mLock) {
                    removeMessages(WRITE_PACKAGE_LIST);
                    mPm.mSettings.writePackageListLPr(msg.arg1);
                }
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } break;
            case CHECK_PENDING_VERIFICATION: {
                final int verificationId = msg.arg1;
                final PackageVerificationState state = mPm.mPendingVerification.get(verificationId);

                if ((state != null) && !state.isVerificationComplete()
                        && !state.timeoutExtended()) {
                    final VerificationParams params = state.getVerificationParams();
                    final Uri originUri = Uri.fromFile(params.mOriginInfo.mResolvedFile);

                    String errorMsg = "Verification timed out for " + originUri;
                    Slog.i(TAG, errorMsg);

                    final UserHandle user = params.getUser();
                    if (getDefaultVerificationResponse(user)
                            == PackageManager.VERIFICATION_ALLOW) {
                        Slog.i(TAG, "Continuing with installation of " + originUri);
                        state.setVerifierResponse(Binder.getCallingUid(),
                                PackageManager.VERIFICATION_ALLOW_WITHOUT_SUFFICIENT);
                        mVerificationHelper.broadcastPackageVerified(verificationId, originUri,
                                PackageManager.VERIFICATION_ALLOW, null, params.mDataLoaderType,
                                user);
                    } else {
                        mVerificationHelper.broadcastPackageVerified(verificationId, originUri,
                                PackageManager.VERIFICATION_REJECT, null,
                                params.mDataLoaderType, user);
                        params.setReturnCode(
                                PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE, errorMsg);
                        state.setVerifierResponse(Binder.getCallingUid(),
                                PackageManager.VERIFICATION_REJECT);
                    }

                    if (state.areAllVerificationsComplete()) {
                        mPm.mPendingVerification.remove(verificationId);
                    }

                    Trace.asyncTraceEnd(
                            TRACE_TAG_PACKAGE_MANAGER, "verification", verificationId);

                    params.handleVerificationFinished();

                }
                break;
            }
            case CHECK_PENDING_INTEGRITY_VERIFICATION: {
                final int verificationId = msg.arg1;
                final PackageVerificationState state = mPm.mPendingVerification.get(verificationId);

                if (state != null && !state.isIntegrityVerificationComplete()) {
                    final VerificationParams params = state.getVerificationParams();
                    final Uri originUri = Uri.fromFile(params.mOriginInfo.mResolvedFile);

                    String errorMsg = "Integrity verification timed out for " + originUri;
                    Slog.i(TAG, errorMsg);

                    state.setIntegrityVerificationResult(
                            getDefaultIntegrityVerificationResponse());

                    if (getDefaultIntegrityVerificationResponse()
                            == PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW) {
                        Slog.i(TAG, "Integrity check times out, continuing with " + originUri);
                    } else {
                        params.setReturnCode(
                                PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                                errorMsg);
                    }

                    if (state.areAllVerificationsComplete()) {
                        mPm.mPendingVerification.remove(verificationId);
                    }

                    Trace.asyncTraceEnd(
                            TRACE_TAG_PACKAGE_MANAGER,
                            "integrity_verification",
                            verificationId);

                    params.handleIntegrityVerificationFinished();
                }
                break;
            }
            case PACKAGE_VERIFIED: {
                final int verificationId = msg.arg1;

                final PackageVerificationState state = mPm.mPendingVerification.get(verificationId);
                if (state == null) {
                    Slog.w(TAG, "Verification with id " + verificationId
                            + " not found."
                            + " It may be invalid or overridden by integrity verification");
                    break;
                }

                final PackageVerificationResponse response = (PackageVerificationResponse) msg.obj;

                state.setVerifierResponse(response.callerUid, response.code);

                if (state.isVerificationComplete()) {
                    final VerificationParams params = state.getVerificationParams();
                    final Uri originUri = Uri.fromFile(params.mOriginInfo.mResolvedFile);

                    if (state.isInstallAllowed()) {
                        mVerificationHelper.broadcastPackageVerified(verificationId, originUri,
                                response.code, null, params.mDataLoaderType, params.getUser());
                    } else {
                        params.setReturnCode(
                                PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                                "Install not allowed");
                    }

                    if (state.areAllVerificationsComplete()) {
                        mPm.mPendingVerification.remove(verificationId);
                    }

                    Trace.asyncTraceEnd(
                            TRACE_TAG_PACKAGE_MANAGER, "verification", verificationId);

                    params.handleVerificationFinished();
                }

                break;
            }
            case INTEGRITY_VERIFICATION_COMPLETE: {
                final int verificationId = msg.arg1;

                final PackageVerificationState state = mPm.mPendingVerification.get(verificationId);
                if (state == null) {
                    Slog.w(TAG, "Integrity verification with id " + verificationId
                            + " not found. It may be invalid or overridden by verifier");
                    break;
                }

                final int response = (Integer) msg.obj;
                final VerificationParams params = state.getVerificationParams();
                final Uri originUri = Uri.fromFile(params.mOriginInfo.mResolvedFile);

                state.setIntegrityVerificationResult(response);

                if (response == PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW) {
                    Slog.i(TAG, "Integrity check passed for " + originUri);
                } else {
                    params.setReturnCode(
                            PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                            "Integrity check failed for " + originUri);
                }

                if (state.areAllVerificationsComplete()) {
                    mPm.mPendingVerification.remove(verificationId);
                }

                Trace.asyncTraceEnd(
                        TRACE_TAG_PACKAGE_MANAGER,
                        "integrity_verification",
                        verificationId);

                params.handleIntegrityVerificationFinished();
                break;
            }
            case INSTANT_APP_RESOLUTION_PHASE_TWO: {
                InstantAppResolver.doInstantAppResolutionPhaseTwo(mPm.mContext,
                        mPm.mInstantAppResolverConnection,
                        (InstantAppRequest) msg.obj,
                        mPm.mInstantAppInstallerActivity,
                        mPm.mHandler);
                break;
            }
            case ENABLE_ROLLBACK_STATUS: {
                final int enableRollbackToken = msg.arg1;
                final int enableRollbackCode = msg.arg2;
                final VerificationParams params =
                        mPm.mPendingEnableRollback.get(enableRollbackToken);
                if (params == null) {
                    Slog.w(TAG, "Invalid rollback enabled token "
                            + enableRollbackToken + " received");
                    break;
                }

                mPm.mPendingEnableRollback.remove(enableRollbackToken);

                if (enableRollbackCode != PackageManagerInternal.ENABLE_ROLLBACK_SUCCEEDED) {
                    final Uri originUri = Uri.fromFile(params.mOriginInfo.mResolvedFile);
                    Slog.w(TAG, "Failed to enable rollback for " + originUri);
                    Slog.w(TAG, "Continuing with installation of " + originUri);
                }

                Trace.asyncTraceEnd(
                        TRACE_TAG_PACKAGE_MANAGER, "enable_rollback", enableRollbackToken);

                params.handleRollbackEnabled();
                break;
            }
            case ENABLE_ROLLBACK_TIMEOUT: {
                final int enableRollbackToken = msg.arg1;
                final int sessionId = msg.arg2;
                final VerificationParams params =
                        mPm.mPendingEnableRollback.get(enableRollbackToken);
                if (params != null) {
                    final Uri originUri = Uri.fromFile(params.mOriginInfo.mResolvedFile);

                    Slog.w(TAG, "Enable rollback timed out for " + originUri);
                    mPm.mPendingEnableRollback.remove(enableRollbackToken);

                    Slog.w(TAG, "Continuing with installation of " + originUri);
                    Trace.asyncTraceEnd(
                            TRACE_TAG_PACKAGE_MANAGER, "enable_rollback", enableRollbackToken);
                    params.handleRollbackEnabled();
                    Intent rollbackTimeoutIntent = new Intent(
                            Intent.ACTION_CANCEL_ENABLE_ROLLBACK);
                    rollbackTimeoutIntent.putExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_SESSION_ID,
                            sessionId);
                    rollbackTimeoutIntent.addFlags(
                            Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                    mPm.mContext.sendBroadcastAsUser(rollbackTimeoutIntent, UserHandle.SYSTEM,
                            android.Manifest.permission.PACKAGE_ROLLBACK_AGENT);
                }
                break;
            }
            case DOMAIN_VERIFICATION: {
                int messageCode = msg.arg1;
                Object object = msg.obj;
                mPm.mDomainVerificationManager.runMessage(messageCode, object);
                break;
            }
            case SNAPSHOT_UNCORK: {
                int corking = mPm.sSnapshotCorked.decrementAndGet();
                if (TRACE_SNAPSHOTS && corking == 0) {
                    Log.e(TAG, "snapshot: corking goes to zero in message handler");
                }
                break;
            }
        }
    }

    private void handlePackagePostInstall(PackageInstalledInfo res, boolean killApp,
            boolean virtualPreload, boolean launchedForRestore, String installerPackage,
            IPackageInstallObserver2 installObserver, int dataLoaderType) {
        boolean succeeded = res.mReturnCode == PackageManager.INSTALL_SUCCEEDED;
        final boolean update = res.mRemovedInfo != null && res.mRemovedInfo.mRemovedPackage != null;
        final String packageName = res.mName;
        final PackageSetting pkgSetting = succeeded ? mPm.getPackageSetting(packageName) : null;
        final boolean removedBeforeUpdate = (pkgSetting == null)
                || (pkgSetting.isSystem() && !pkgSetting.getPathString().equals(
                res.mPkg.getPath()));
        if (succeeded && removedBeforeUpdate) {
            Slog.e(TAG, packageName + " was removed before handlePackagePostInstall "
                    + "could be executed");
            res.mReturnCode = INSTALL_FAILED_PACKAGE_CHANGED;
            res.mReturnMsg = "Package was removed before install could complete.";

            // Remove the update failed package's older resources safely now
            InstallArgs args = res.mRemovedInfo != null ? res.mRemovedInfo.mArgs : null;
            if (args != null) {
                synchronized (mPm.mInstallLock) {
                    args.doPostDeleteLI(true);
                }
            }
            mPm.notifyInstallObserver(res, installObserver);
            return;
        }

        if (succeeded) {
            // Clear the uid cache after we installed a new package.
            mPm.mPerUidReadTimeoutsCache = null;

            // Send the removed broadcasts
            if (res.mRemovedInfo != null) {
                res.mRemovedInfo.sendPackageRemovedBroadcasts(killApp, false /*removedBySystem*/);
            }

            final String installerPackageName =
                    res.mInstallerPackageName != null
                            ? res.mInstallerPackageName
                            : res.mRemovedInfo != null
                                    ? res.mRemovedInfo.mInstallerPackageName
                                    : null;

            synchronized (mPm.mLock) {
                mPm.mInstantAppRegistry.onPackageInstalledLPw(res.mPkg, res.mNewUsers);
            }

            // Determine the set of users who are adding this package for
            // the first time vs. those who are seeing an update.
            int[] firstUserIds = EMPTY_INT_ARRAY;
            int[] firstInstantUserIds = EMPTY_INT_ARRAY;
            int[] updateUserIds = EMPTY_INT_ARRAY;
            int[] instantUserIds = EMPTY_INT_ARRAY;
            final boolean allNewUsers = res.mOrigUsers == null || res.mOrigUsers.length == 0;
            for (int newUser : res.mNewUsers) {
                final boolean isInstantApp = pkgSetting.getInstantApp(newUser);
                if (allNewUsers) {
                    if (isInstantApp) {
                        firstInstantUserIds = ArrayUtils.appendInt(firstInstantUserIds, newUser);
                    } else {
                        firstUserIds = ArrayUtils.appendInt(firstUserIds, newUser);
                    }
                    continue;
                }
                boolean isNew = true;
                for (int origUser : res.mOrigUsers) {
                    if (origUser == newUser) {
                        isNew = false;
                        break;
                    }
                }
                if (isNew) {
                    if (isInstantApp) {
                        firstInstantUserIds = ArrayUtils.appendInt(firstInstantUserIds, newUser);
                    } else {
                        firstUserIds = ArrayUtils.appendInt(firstUserIds, newUser);
                    }
                } else {
                    if (isInstantApp) {
                        instantUserIds = ArrayUtils.appendInt(instantUserIds, newUser);
                    } else {
                        updateUserIds = ArrayUtils.appendInt(updateUserIds, newUser);
                    }
                }
            }

            // Send installed broadcasts if the package is not a static shared lib.
            if (res.mPkg.getStaticSharedLibName() == null) {
                mPm.mProcessLoggingHandler.invalidateBaseApkHash(res.mPkg.getBaseApkPath());

                // Send added for users that see the package for the first time
                // sendPackageAddedForNewUsers also deals with system apps
                int appId = UserHandle.getAppId(res.mUid);
                boolean isSystem = res.mPkg.isSystem();
                mPm.sendPackageAddedForNewUsers(packageName, isSystem || virtualPreload,
                        virtualPreload /*startReceiver*/, appId, firstUserIds, firstInstantUserIds,
                        dataLoaderType);

                // Send added for users that don't see the package for the first time
                Bundle extras = new Bundle(1);
                extras.putInt(Intent.EXTRA_UID, res.mUid);
                if (update) {
                    extras.putBoolean(Intent.EXTRA_REPLACING, true);
                }
                extras.putInt(PackageInstaller.EXTRA_DATA_LOADER_TYPE, dataLoaderType);
                // Send to all running apps.
                final SparseArray<int[]> newBroadcastAllowList;

                synchronized (mPm.mLock) {
                    newBroadcastAllowList = mPm.mAppsFilter.getVisibilityAllowList(
                            mPm.getPackageSettingInternal(res.mName, Process.SYSTEM_UID),
                            updateUserIds, mPm.mSettings.getPackagesLocked());
                }
                mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName,
                        extras, 0 /*flags*/,
                        null /*targetPackage*/, null /*finishedReceiver*/,
                        updateUserIds, instantUserIds, newBroadcastAllowList, null);
                if (installerPackageName != null) {
                    // Send to the installer, even if it's not running.
                    mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName,
                            extras, 0 /*flags*/,
                            installerPackageName, null /*finishedReceiver*/,
                            updateUserIds, instantUserIds, null /* broadcastAllowList */, null);
                }
                // if the required verifier is defined, but, is not the installer of record
                // for the package, it gets notified
                final boolean notifyVerifier = mPm.mRequiredVerifierPackage != null
                        && !mPm.mRequiredVerifierPackage.equals(installerPackageName);
                if (notifyVerifier) {
                    mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName,
                            extras, 0 /*flags*/,
                            mPm.mRequiredVerifierPackage, null /*finishedReceiver*/,
                            updateUserIds, instantUserIds, null /* broadcastAllowList */, null);
                }
                // If package installer is defined, notify package installer about new
                // app installed
                if (mPm.mRequiredInstallerPackage != null) {
                    mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName,
                            extras, Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND /*flags*/,
                            mPm.mRequiredInstallerPackage, null /*finishedReceiver*/,
                            firstUserIds, instantUserIds, null /* broadcastAllowList */, null);
                }

                // Send replaced for users that don't see the package for the first time
                if (update) {
                    mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED,
                            packageName, extras, 0 /*flags*/,
                            null /*targetPackage*/, null /*finishedReceiver*/,
                            updateUserIds, instantUserIds, res.mRemovedInfo.mBroadcastAllowList,
                            null);
                    if (installerPackageName != null) {
                        mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED, packageName,
                                extras, 0 /*flags*/,
                                installerPackageName, null /*finishedReceiver*/,
                                updateUserIds, instantUserIds, null /*broadcastAllowList*/, null);
                    }
                    if (notifyVerifier) {
                        mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED, packageName,
                                extras, 0 /*flags*/,
                                mPm.mRequiredVerifierPackage, null /*finishedReceiver*/,
                                updateUserIds, instantUserIds, null /*broadcastAllowList*/, null);
                    }
                    mPm.sendPackageBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED,
                            null /*package*/, null /*extras*/, 0 /*flags*/,
                            packageName /*targetPackage*/,
                            null /*finishedReceiver*/, updateUserIds, instantUserIds,
                            null /*broadcastAllowList*/,
                            mPm.getTemporaryAppAllowlistBroadcastOptions(REASON_PACKAGE_REPLACED)
                                    .toBundle());
                } else if (launchedForRestore && !res.mPkg.isSystem()) {
                    // First-install and we did a restore, so we're responsible for the
                    // first-launch broadcast.
                    if (DEBUG_BACKUP) {
                        Slog.i(TAG, "Post-restore of " + packageName
                                + " sending FIRST_LAUNCH in " + Arrays.toString(firstUserIds));
                    }
                    mPm.sendFirstLaunchBroadcast(packageName, installerPackage,
                            firstUserIds, firstInstantUserIds);
                }

                // Send broadcast package appeared if external for all users
                if (res.mPkg.isExternalStorage()) {
                    if (!update) {
                        final StorageManager storage = mPm.mInjector.getSystemService(
                                StorageManager.class);
                        VolumeInfo volume =
                                storage.findVolumeByUuid(
                                        StorageManager.convert(
                                                res.mPkg.getVolumeUuid()).toString());
                        int packageExternalStorageType =
                                PackageManagerServiceUtils.getPackageExternalStorageType(volume,
                                        res.mPkg.isExternalStorage());
                        // If the package was installed externally, log it.
                        if (packageExternalStorageType != StorageEnums.UNKNOWN) {
                            FrameworkStatsLog.write(
                                    FrameworkStatsLog.APP_INSTALL_ON_EXTERNAL_STORAGE_REPORTED,
                                    packageExternalStorageType, packageName);
                        }
                    }
                    if (DEBUG_INSTALL) {
                        Slog.i(TAG, "upgrading pkg " + res.mPkg + " is external");
                    }
                    final int[] uidArray = new int[]{res.mPkg.getUid()};
                    ArrayList<String> pkgList = new ArrayList<>(1);
                    pkgList.add(packageName);
                    mPm.sendResourcesChangedBroadcast(true, true, pkgList, uidArray, null);
                }
            } else if (!ArrayUtils.isEmpty(res.mLibraryConsumers)) { // if static shared lib
                for (int i = 0; i < res.mLibraryConsumers.size(); i++) {
                    AndroidPackage pkg = res.mLibraryConsumers.get(i);
                    // send broadcast that all consumers of the static shared library have changed
                    mPm.sendPackageChangedBroadcast(pkg.getPackageName(), false /* dontKillApp */,
                            new ArrayList<>(Collections.singletonList(pkg.getPackageName())),
                            pkg.getUid(), null);
                }
            }

            // Work that needs to happen on first install within each user
            if (firstUserIds != null && firstUserIds.length > 0) {
                for (int userId : firstUserIds) {
                    mPm.restorePermissionsAndUpdateRolesForNewUserInstall(packageName,
                            userId);
                }
            }

            if (allNewUsers && !update) {
                mPm.notifyPackageAdded(packageName, res.mUid);
            } else {
                mPm.notifyPackageChanged(packageName, res.mUid);
            }

            // Log current value of "unknown sources" setting
            EventLog.writeEvent(EventLogTags.UNKNOWN_SOURCES_ENABLED,
                    getUnknownSourcesSettings());

            // Remove the replaced package's older resources safely now
            InstallArgs args = res.mRemovedInfo != null ? res.mRemovedInfo.mArgs : null;
            if (args != null) {
                if (!killApp) {
                    // If we didn't kill the app, defer the deletion of code/resource files, since
                    // they may still be in use by the running application. This mitigates problems
                    // in cases where resources or code is loaded by a new Activity before
                    // ApplicationInfo changes have propagated to all application threads.
                    scheduleDeferredNoKillPostDelete(args);
                } else {
                    synchronized (mPm.mInstallLock) {
                        args.doPostDeleteLI(true);
                    }
                }
            } else {
                // Force a gc to clear up things. Ask for a background one, it's fine to go on
                // and not block here.
                VMRuntime.getRuntime().requestConcurrentGC();
            }

            // Notify DexManager that the package was installed for new users.
            // The updated users should already be indexed and the package code paths
            // should not change.
            // Don't notify the manager for ephemeral apps as they are not expected to
            // survive long enough to benefit of background optimizations.
            for (int userId : firstUserIds) {
                PackageInfo info = mPm.getPackageInfo(packageName, /*flags*/ 0, userId);
                // There's a race currently where some install events may interleave with an
                // uninstall. This can lead to package info being null (b/36642664).
                if (info != null) {
                    mPm.getDexManager().notifyPackageInstalled(info, userId);
                }
            }
        }

        final boolean deferInstallObserver = succeeded && update && !killApp;
        if (deferInstallObserver) {
            mPm.scheduleDeferredNoKillInstallObserver(res, installObserver);
        } else {
            mPm.notifyInstallObserver(res, installObserver);
        }
    }

    /**
     * Get the default verification agent response code.
     *
     * @return default verification response code
     */
    private int getDefaultVerificationResponse(UserHandle user) {
        if (mPm.mUserManager.hasUserRestriction(UserManager.ENSURE_VERIFY_APPS,
                user.getIdentifier())) {
            return PackageManager.VERIFICATION_REJECT;
        }
        return android.provider.Settings.Global.getInt(mPm.mContext.getContentResolver(),
                android.provider.Settings.Global.PACKAGE_VERIFIER_DEFAULT_RESPONSE,
                DEFAULT_VERIFICATION_RESPONSE);
    }

    /**
     * Get the default integrity verification response code.
     */
    private int getDefaultIntegrityVerificationResponse() {
        // We are not exposing this as a user-configurable setting because we don't want to provide
        // an easy way to get around the integrity check.
        return PackageManager.VERIFICATION_REJECT;
    }

    /**
     * Get the "allow unknown sources" setting.
     *
     * @return the current "allow unknown sources" setting
     */
    private int getUnknownSourcesSettings() {
        return android.provider.Settings.Secure.getIntForUser(mPm.mContext.getContentResolver(),
                android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS,
                -1, UserHandle.USER_SYSTEM);
    }

    private void scheduleDeferredNoKillPostDelete(InstallArgs args) {
        Message message = obtainMessage(DEFERRED_NO_KILL_POST_DELETE, args);
        sendMessageDelayed(message, DEFERRED_NO_KILL_POST_DELETE_DELAY_MS);
    }
}
