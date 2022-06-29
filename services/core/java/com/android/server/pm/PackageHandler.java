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

import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.server.pm.PackageManagerService.CHECK_PENDING_INTEGRITY_VERIFICATION;
import static com.android.server.pm.PackageManagerService.CHECK_PENDING_VERIFICATION;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEFAULT_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD;
import static com.android.server.pm.PackageManagerService.DEFERRED_NO_KILL_INSTALL_OBSERVER;
import static com.android.server.pm.PackageManagerService.DEFERRED_NO_KILL_POST_DELETE;
import static com.android.server.pm.PackageManagerService.DEFERRED_PENDING_KILL_INSTALL_OBSERVER;
import static com.android.server.pm.PackageManagerService.DOMAIN_VERIFICATION;
import static com.android.server.pm.PackageManagerService.ENABLE_ROLLBACK_STATUS;
import static com.android.server.pm.PackageManagerService.ENABLE_ROLLBACK_TIMEOUT;
import static com.android.server.pm.PackageManagerService.INIT_COPY;
import static com.android.server.pm.PackageManagerService.INSTANT_APP_RESOLUTION_PHASE_TWO;
import static com.android.server.pm.PackageManagerService.INTEGRITY_VERIFICATION_COMPLETE;
import static com.android.server.pm.PackageManagerService.PACKAGE_VERIFIED;
import static com.android.server.pm.PackageManagerService.POST_INSTALL;
import static com.android.server.pm.PackageManagerService.PRUNE_UNUSED_STATIC_SHARED_LIBRARIES;
import static com.android.server.pm.PackageManagerService.SEND_PENDING_BROADCAST;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerService.WRITE_PACKAGE_LIST;
import static com.android.server.pm.PackageManagerService.WRITE_PACKAGE_RESTRICTIONS;
import static com.android.server.pm.PackageManagerService.WRITE_SETTINGS;

import android.content.Intent;
import android.content.pm.InstantAppRequest;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import java.io.IOException;

/**
 * Part of PackageManagerService that handles events.
 */
final class PackageHandler extends Handler {
    private final PackageManagerService mPm;
    private final InstallPackageHelper mInstallPackageHelper;

    PackageHandler(Looper looper, PackageManagerService pm) {
        super(looper);
        mPm = pm;
        mInstallPackageHelper = new InstallPackageHelper(mPm);
    }

    @Override
    public void handleMessage(Message msg) {
        try {
            doHandleMessage(msg);
        } finally {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
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
                mInstallPackageHelper.sendPendingBroadcasts();
                break;
            }
            case POST_INSTALL: {
                if (DEBUG_INSTALL) Log.v(TAG, "Handling post-install for " + msg.arg1);

                PostInstallData data = mPm.mRunningInstalls.get(msg.arg1);
                final boolean didRestore = (msg.arg2 != 0);
                mPm.mRunningInstalls.delete(msg.arg1);

                if (data != null && data.res.mFreezer != null) {
                    data.res.mFreezer.close();
                }

                if (data != null && data.mPostInstallRunnable != null) {
                    data.mPostInstallRunnable.run();
                } else if (data != null && data.args != null) {
                    mInstallPackageHelper.handlePackagePostInstall(data.res, data.args, didRestore);
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
            case DEFERRED_NO_KILL_INSTALL_OBSERVER:
            case DEFERRED_PENDING_KILL_INSTALL_OBSERVER: {
                final String packageName = (String) msg.obj;
                if (packageName != null) {
                    final boolean killApp = msg.what == DEFERRED_PENDING_KILL_INSTALL_OBSERVER;
                    mPm.notifyInstallObserver(packageName, killApp);
                }
            } break;
            case WRITE_SETTINGS: {
                mPm.writeSettings();
            } break;
            case WRITE_PACKAGE_RESTRICTIONS: {
                mPm.writePendingRestrictions();
            } break;
            case WRITE_PACKAGE_LIST: {
                mPm.writePackageList(msg.arg1);
            } break;
            case CHECK_PENDING_VERIFICATION: {
                final int verificationId = msg.arg1;
                final boolean streaming = msg.arg2 != 0;
                final PackageVerificationState state = mPm.mPendingVerification.get(verificationId);

                if (state == null || state.isVerificationComplete()) {
                    // Not found or complete.
                    break;
                }
                if (!streaming && state.timeoutExtended()) {
                    // Timeout extended.
                    break;
                }

                final PackageVerificationResponse response = (PackageVerificationResponse) msg.obj;

                final VerificationParams params = state.getVerificationParams();
                final Uri originUri = Uri.fromFile(params.mOriginInfo.mResolvedFile);

                String errorMsg = "Verification timed out for " + originUri;
                Slog.i(TAG, errorMsg);

                final UserHandle user = params.getUser();
                if (response.code != PackageManager.VERIFICATION_REJECT) {
                    Slog.i(TAG, "Continuing with installation of " + originUri);
                    state.setVerifierResponse(response.callerUid, response.code);
                    VerificationUtils.broadcastPackageVerified(verificationId, originUri,
                            PackageManager.VERIFICATION_ALLOW, null, params.mDataLoaderType,
                            user, mPm.mContext);
                } else {
                    VerificationUtils.broadcastPackageVerified(verificationId, originUri,
                            PackageManager.VERIFICATION_REJECT, null,
                            params.mDataLoaderType, user, mPm.mContext);
                    params.setReturnCode(
                            PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE, errorMsg);
                    state.setVerifierResponse(response.callerUid, response.code);
                }

                if (state.areAllVerificationsComplete()) {
                    mPm.mPendingVerification.remove(verificationId);
                }

                Trace.asyncTraceEnd(
                        TRACE_TAG_PACKAGE_MANAGER, "verification", verificationId);

                params.handleVerificationFinished();
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
                if (state.isVerificationComplete()) {
                    Slog.w(TAG, "Verification with id " + verificationId + " already complete.");
                    break;
                }

                final PackageVerificationResponse response = (PackageVerificationResponse) msg.obj;
                state.setVerifierResponse(response.callerUid, response.code);

                if (state.isVerificationComplete()) {
                    final VerificationParams params = state.getVerificationParams();
                    final Uri originUri = Uri.fromFile(params.mOriginInfo.mResolvedFile);

                    if (state.isInstallAllowed()) {
                        VerificationUtils.broadcastPackageVerified(verificationId, originUri,
                                response.code, null, params.mDataLoaderType, params.getUser(),
                                mPm.mContext);
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
                        mPm.snapshotComputer(),
                        mPm.mUserManager,
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
            case PRUNE_UNUSED_STATIC_SHARED_LIBRARIES: {
                try {
                    mPm.mInjector.getSharedLibrariesImpl().pruneUnusedStaticSharedLibraries(
                            mPm.snapshotComputer(),
                            Long.MAX_VALUE,
                            Settings.Global.getLong(mPm.mContext.getContentResolver(),
                                    Settings.Global.UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD,
                                    DEFAULT_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD));
                } catch (IOException e) {
                    Log.w(TAG, "Failed to prune unused static shared libraries :"
                            + e.getMessage());
                }
                break;
            }
        }
    }

    /**
     * Get the default integrity verification response code.
     */
    private int getDefaultIntegrityVerificationResponse() {
        // We are not exposing this as a user-configurable setting because we don't want to provide
        // an easy way to get around the integrity check.
        return PackageManager.VERIFICATION_REJECT;
    }
}
