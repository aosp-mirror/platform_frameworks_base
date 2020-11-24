/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.apex.ApexInfo;
import android.apex.ApexInfoList;
import android.apex.ApexSessionInfo;
import android.apex.ApexSessionParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ApexStagedEvent;
import android.content.pm.IStagedApexObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionInfo.StagedSessionErrorCode;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SigningDetails;
import android.content.pm.SigningDetails.SignatureSchemeVersion;
import android.content.pm.StagedApexInfo;
import android.content.pm.parsing.PackageInfoWithoutStateUtils;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimingsTraceLog;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageHelper;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.rollback.RollbackManagerInternal;
import com.android.server.rollback.WatchdogRollbackLogger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * This class handles staged install sessions, i.e. install sessions that require packages to
 * be installed only after a reboot.
 */
public class StagingManager {

    private static final String TAG = "StagingManager";

    private final ApexManager mApexManager;
    private final PowerManager mPowerManager;
    private final Context mContext;
    @VisibleForTesting
    final PreRebootVerificationHandler mPreRebootVerificationHandler;
    private final Supplier<PackageParser2> mPackageParserSupplier;

    private final File mFailureReasonFile = new File("/metadata/staged-install/failure_reason.txt");
    private String mFailureReason;

    @GuardedBy("mStagedSessions")
    private final SparseArray<StagedSession> mStagedSessions = new SparseArray<>();

    @GuardedBy("mFailedPackageNames")
    private final List<String> mFailedPackageNames = new ArrayList<>();
    private String mNativeFailureReason;

    @GuardedBy("mSuccessfulStagedSessionIds")
    private final List<Integer> mSuccessfulStagedSessionIds = new ArrayList<>();

    @GuardedBy("mStagedApexObservers")
    private final List<IStagedApexObserver> mStagedApexObservers = new ArrayList<>();

    private final CompletableFuture<Void> mBootCompleted = new CompletableFuture<>();

    interface StagedSession {
        boolean isMultiPackage();
        boolean isApexSession();
        boolean isCommitted();
        boolean isInTerminalState();
        boolean isDestroyed();
        boolean isSessionReady();
        boolean isSessionApplied();
        boolean isSessionFailed();
        List<StagedSession> getChildSessions();
        String getPackageName();
        int getParentSessionId();
        int sessionId();
        PackageInstaller.SessionParams sessionParams();
        boolean sessionContains(Predicate<StagedSession> filter);
        boolean containsApkSession();
        boolean containsApexSession();
        void setSessionReady();
        void setSessionFailed(@StagedSessionErrorCode int errorCode, String errorMessage);
        void setSessionApplied();
        void installSession(IntentSender statusReceiver);
        boolean hasParentSessionId();
        long getCommittedMillis();
        void abandon();
        void notifyEndPreRebootVerification();
        void verifySession();
    }

    StagingManager(Context context, Supplier<PackageParser2> packageParserSupplier, Looper looper) {
        this(context, packageParserSupplier, ApexManager.getInstance(), looper);
    }

    @VisibleForTesting
    StagingManager(Context context, Supplier<PackageParser2> packageParserSupplier,
            ApexManager apexManager) {
        this(context, packageParserSupplier, apexManager, BackgroundThread.get().getLooper());
    }

    StagingManager(Context context, Supplier<PackageParser2> packageParserSupplier,
            ApexManager apexManager, Looper looper) {
        mContext = context;
        mPackageParserSupplier = packageParserSupplier;

        mApexManager = apexManager;
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mPreRebootVerificationHandler = new PreRebootVerificationHandler(looper);

        if (mFailureReasonFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(mFailureReasonFile))) {
                mFailureReason = reader.readLine();
            } catch (Exception ignore) { }
        }
    }

    /**
     This class manages lifecycle events for StagingManager.
     */
    public static final class Lifecycle extends SystemService {
        private static StagingManager sStagingManager;

        public Lifecycle(Context context) {
            super(context);
        }

        void startService(StagingManager stagingManager) {
            sStagingManager = stagingManager;
            LocalServices.getService(SystemServiceManager.class).startService(this);
        }

        @Override
        public void onStart() {
            // no-op
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_BOOT_COMPLETED && sStagingManager != null) {
                sStagingManager.markStagedSessionsAsSuccessful();
                sStagingManager.markBootCompleted();
            }
        }
    }

    private void markBootCompleted() {
        mApexManager.markBootCompleted();
    }

    void registerStagedApexObserver(IStagedApexObserver observer) {
        if (observer == null) {
            return;
        }
        if  (observer.asBinder() != null) {
            try {
                observer.asBinder().linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        synchronized (mStagedApexObservers) {
                            mStagedApexObservers.remove(observer);
                        }
                    }
                }, 0);
            } catch (RemoteException re) {
                Slog.w(TAG, re.getMessage());
            }
        }
        synchronized (mStagedApexObservers) {
            mStagedApexObservers.add(observer);
        }
    }

    void unregisterStagedApexObserver(IStagedApexObserver observer) {
        synchronized (mStagedApexObservers) {
            mStagedApexObservers.remove(observer);
        }
    }

    /**
     * Validates the signature used to sign the container of the new apex package
     *
     * @param newApexPkg The new apex package that is being installed
     */
    private void validateApexSignature(PackageInfo newApexPkg)
            throws PackageManagerException {
        // Get signing details of the new package
        final String apexPath = newApexPkg.applicationInfo.sourceDir;
        final String packageName = newApexPkg.packageName;
        int minSignatureScheme = ApkSignatureVerifier.getMinimumSignatureSchemeVersionForTargetSdk(
                newApexPkg.applicationInfo.targetSdkVersion);

        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        final ParseResult<SigningDetails> newResult = ApkSignatureVerifier.verify(
                input.reset(), apexPath, minSignatureScheme);
        if (newResult.isError()) {
            throw new PackageManagerException(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                    "Failed to parse APEX package " + apexPath + " : "
                            + newResult.getException(), newResult.getException());
        }
        final SigningDetails newSigningDetails = newResult.getResult();

        // Get signing details of the existing package
        final PackageInfo existingApexPkg = mApexManager.getPackageInfo(packageName,
                ApexManager.MATCH_ACTIVE_PACKAGE);
        if (existingApexPkg == null) {
            // This should never happen, because submitSessionToApexService ensures that no new
            // apexes were installed.
            throw new IllegalStateException("Unknown apex package " + packageName);
        }

        final ParseResult<SigningDetails> existingResult = ApkSignatureVerifier.verify(
                input.reset(), existingApexPkg.applicationInfo.sourceDir,
                SignatureSchemeVersion.JAR);
        if (existingResult.isError()) {
            throw new PackageManagerException(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                    "Failed to parse APEX package " + existingApexPkg.applicationInfo.sourceDir
                            + " : " + existingResult.getException(), existingResult.getException());
        }
        final SigningDetails existingSigningDetails = existingResult.getResult();

        // Verify signing details for upgrade
        if (newSigningDetails.checkCapability(existingSigningDetails,
                SigningDetails.CertCapabilities.INSTALLED_DATA)
                || existingSigningDetails.checkCapability(newSigningDetails,
                SigningDetails.CertCapabilities.ROLLBACK)) {
            return;
        }

        throw new PackageManagerException(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                "APK-container signature of APEX package " + packageName + " with version "
                        + newApexPkg.versionCodeMajor + " and path " + apexPath + " is not"
                        + " compatible with the one currently installed on device");
    }

    private List<PackageInfo> submitSessionToApexService(@NonNull StagedSession session,
            int rollbackId) throws PackageManagerException {
        final IntArray childSessionIds = new IntArray();
        if (session.isMultiPackage()) {
            for (StagedSession s : session.getChildSessions()) {
                if (s.isApexSession()) {
                    childSessionIds.add(s.sessionId());
                }
            }
        }
        ApexSessionParams apexSessionParams = new ApexSessionParams();
        apexSessionParams.sessionId = session.sessionId();
        apexSessionParams.childSessionIds = childSessionIds.toArray();
        if (session.sessionParams().installReason == PackageManager.INSTALL_REASON_ROLLBACK) {
            apexSessionParams.isRollback = true;
            apexSessionParams.rollbackId = rollbackId;
        } else {
            if (rollbackId != -1) {
                apexSessionParams.hasRollbackEnabled = true;
                apexSessionParams.rollbackId = rollbackId;
            }
        }
        // submitStagedSession will throw a PackageManagerException if apexd verification fails,
        // which will be propagated to populate stagedSessionErrorMessage of this session.
        final ApexInfoList apexInfoList = mApexManager.submitStagedSession(apexSessionParams);
        final List<PackageInfo> result = new ArrayList<>();
        final List<String> apexPackageNames = new ArrayList<>();
        for (ApexInfo apexInfo : apexInfoList.apexInfos) {
            final PackageInfo packageInfo;
            final int flags = PackageManager.GET_META_DATA;
            try (PackageParser2 packageParser = mPackageParserSupplier.get()) {
                File apexFile = new File(apexInfo.modulePath);
                final ParsedPackage parsedPackage = packageParser.parsePackage(
                        apexFile, flags, false);
                packageInfo = PackageInfoWithoutStateUtils.generate(parsedPackage, apexInfo, flags);
                if (packageInfo == null) {
                    throw new PackageManagerException(
                            SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                            "Unable to generate package info: " + apexInfo.modulePath);
                }
            } catch (PackageManagerException e) {
                throw new PackageManagerException(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                        "Failed to parse APEX package " + apexInfo.modulePath + " : " + e, e);
            }
            result.add(packageInfo);
            apexPackageNames.add(packageInfo.packageName);
        }
        Slog.d(TAG, "Session " + session.sessionId() + " has following APEX packages: "
                + apexPackageNames);
        return result;
    }

    private int retrieveRollbackIdForCommitSession(int sessionId) throws PackageManagerException {
        RollbackManager rm = mContext.getSystemService(RollbackManager.class);

        final List<RollbackInfo> rollbacks = rm.getRecentlyCommittedRollbacks();
        for (int i = 0, size = rollbacks.size(); i < size; i++) {
            final RollbackInfo rollback = rollbacks.get(i);
            if (rollback.getCommittedSessionId() == sessionId) {
                return rollback.getRollbackId();
            }
        }
        throw new PackageManagerException(
                "Could not find rollback id for commit session: " + sessionId);
    }

    // Reverts apex sessions and user data (if checkpoint is supported). Also reboots the device.
    private void abortCheckpoint(String failureReason, boolean supportsCheckpoint,
            boolean needsCheckpoint) {
        Slog.e(TAG, failureReason);
        try {
            if (supportsCheckpoint && needsCheckpoint) {
                // Store failure reason for next reboot
                try (BufferedWriter writer =
                             new BufferedWriter(new FileWriter(mFailureReasonFile))) {
                    writer.write(failureReason);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to save failure reason: ", e);
                }

                // Only revert apex sessions if device supports updating apex
                if (mApexManager.isApexSupported()) {
                    mApexManager.revertActiveSessions();
                }

                PackageHelper.getStorageManager().abortChanges(
                        "abort-staged-install", false /*retry*/);
            }
        } catch (Exception e) {
            Slog.wtf(TAG, "Failed to abort checkpoint", e);
            // Only revert apex sessions if device supports updating apex
            if (mApexManager.isApexSupported()) {
                mApexManager.revertActiveSessions();
            }
            mPowerManager.reboot(null);
        }
    }

    /**
     * Utility function for extracting apex sessions out of multi-package/single session.
     */
    private List<StagedSession> extractApexSessions(StagedSession session) {
        List<StagedSession> apexSessions = new ArrayList<>();
        if (session.isMultiPackage()) {
            for (StagedSession s : session.getChildSessions()) {
                if (s.containsApexSession()) {
                    apexSessions.add(s);
                }
            }
        } else {
            apexSessions.add(session);
        }
        return apexSessions;
    }

    /**
     * Checks if all apk-in-apex were installed without errors for all of the apex sessions. Throws
     * error for any apk-in-apex failed to install.
     *
     * @throws PackageManagerException if any apk-in-apex failed to install
     */
    private void checkInstallationOfApkInApexSuccessful(StagedSession session)
            throws PackageManagerException {
        final List<StagedSession> apexSessions = extractApexSessions(session);
        if (apexSessions.isEmpty()) {
            return;
        }

        for (StagedSession apexSession : apexSessions) {
            String packageName = apexSession.getPackageName();
            String errorMsg = mApexManager.getApkInApexInstallError(packageName);
            if (errorMsg != null) {
                throw new PackageManagerException(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "Failed to install apk-in-apex of " + packageName + " : " + errorMsg);
            }
        }
    }

    /**
     * Perform snapshot and restore as required both for APEXes themselves and for apks in APEX.
     * Apks inside apex are not installed using apk-install flow. They are scanned from the system
     * directory directly by PackageManager, as such, RollbackManager need to handle their data
     * separately here.
     */
    private void snapshotAndRestoreForApexSession(StagedSession session) {
        boolean doSnapshotOrRestore =
                (session.sessionParams().installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0
                || session.sessionParams().installReason == PackageManager.INSTALL_REASON_ROLLBACK;
        if (!doSnapshotOrRestore) {
            return;
        }

        // Find all the apex sessions that needs processing
        final List<StagedSession> apexSessions = extractApexSessions(session);
        if (apexSessions.isEmpty()) {
            return;
        }

        final UserManagerInternal um = LocalServices.getService(UserManagerInternal.class);
        final int[] allUsers = um.getUserIds();
        RollbackManagerInternal rm = LocalServices.getService(RollbackManagerInternal.class);

        for (int i = 0, sessionsSize = apexSessions.size(); i < sessionsSize; i++) {
            final String packageName = apexSessions.get(i).getPackageName();
            // Perform any snapshots or restores for the APEX itself
            snapshotAndRestoreApexUserData(packageName, allUsers, rm);

            // Process the apks inside the APEX
            final List<String> apksInApex = mApexManager.getApksInApex(packageName);
            for (int j = 0, apksSize = apksInApex.size(); j < apksSize; j++) {
                snapshotAndRestoreApkInApexUserData(apksInApex.get(j), allUsers, rm);
            }
        }
    }

    private void snapshotAndRestoreApexUserData(
            String packageName, int[] allUsers, RollbackManagerInternal rm) {
        // appId, ceDataInode, and seInfo are not needed for APEXes
        rm.snapshotAndRestoreUserData(packageName, UserHandle.toUserHandles(allUsers), 0, 0,
                null, 0 /*token*/);
    }

    private void snapshotAndRestoreApkInApexUserData(
            String packageName, int[] allUsers, RollbackManagerInternal rm) {
        PackageManagerInternal mPmi = LocalServices.getService(PackageManagerInternal.class);
        AndroidPackage pkg = mPmi.getPackage(packageName);
        if (pkg == null) {
            Slog.e(TAG, "Could not find package: " + packageName
                    + "for snapshotting/restoring user data.");
            return;
        }

        int appId = -1;
        long ceDataInode = -1;
        final PackageSetting ps = mPmi.getPackageSetting(packageName);
        if (ps != null) {
            appId = ps.getAppId();
            ceDataInode = ps.getCeDataInode(UserHandle.USER_SYSTEM);
            // NOTE: We ignore the user specified in the InstallParam because we know this is
            // an update, and hence need to restore data for all installed users.
            final int[] installedUsers = ps.queryInstalledUsers(allUsers, true);

            final String seInfo = AndroidPackageUtils.getSeInfo(pkg, ps);
            rm.snapshotAndRestoreUserData(packageName, UserHandle.toUserHandles(installedUsers),
                    appId, ceDataInode, seInfo, 0 /*token*/);
        }
    }

    /**
     *  Prepares for the logging of apexd reverts by storing the native failure reason if necessary,
     *  and adding the package name of the session which apexd reverted to the list of reverted
     *  session package names.
     *  Logging needs to wait until the ACTION_BOOT_COMPLETED broadcast is sent.
     */
    private void prepareForLoggingApexdRevert(@NonNull StagedSession session,
            @NonNull String nativeFailureReason) {
        synchronized (mFailedPackageNames) {
            mNativeFailureReason = nativeFailureReason;
            if (session.getPackageName() != null) {
                mFailedPackageNames.add(session.getPackageName());
            }
        }
    }

    private void resumeSession(@NonNull StagedSession session, boolean supportsCheckpoint,
            boolean needsCheckpoint) throws PackageManagerException {
        Slog.d(TAG, "Resuming session " + session.sessionId());

        final boolean hasApex = session.containsApexSession();

        // Before we resume session, we check if revert is needed or not. Typically, we enter file-
        // system checkpoint mode when we reboot first time in order to install staged sessions. We
        // want to install staged sessions in this mode as rebooting now will revert user data. If
        // something goes wrong, then we reboot again to enter fs-rollback mode. Rebooting now will
        // have no effect on user data, so mark the sessions as failed instead.
        // If checkpoint is supported, then we only resume sessions if we are in checkpointing mode.
        // If not, we fail all sessions.
        if (supportsCheckpoint && !needsCheckpoint) {
            String revertMsg = "Reverting back to safe state. Marking " + session.sessionId()
                    + " as failed.";
            final String reasonForRevert = getReasonForRevert();
            if (!TextUtils.isEmpty(reasonForRevert)) {
                revertMsg += " Reason for revert: " + reasonForRevert;
            }
            Slog.d(TAG, revertMsg);
            session.setSessionFailed(SessionInfo.STAGED_SESSION_UNKNOWN, revertMsg);
            return;
        }

        // Handle apk and apk-in-apex installation
        if (hasApex) {
            checkInstallationOfApkInApexSuccessful(session);
            checkDuplicateApkInApex(session);
            snapshotAndRestoreForApexSession(session);
            Slog.i(TAG, "APEX packages in session " + session.sessionId()
                    + " were successfully activated. Proceeding with APK packages, if any");
        }
        // The APEX part of the session is activated, proceed with the installation of APKs.
        Slog.d(TAG, "Installing APK packages in session " + session.sessionId());
        TimingsTraceLog t = new TimingsTraceLog(
                "StagingManagerTiming", Trace.TRACE_TAG_PACKAGE_MANAGER);
        t.traceBegin("installApksInSession");
        installApksInSession(session);
        t.traceEnd();

        Slog.d(TAG, "Marking session " + session.sessionId() + " as applied");
        session.setSessionApplied();
        if (hasApex) {
            if (supportsCheckpoint) {
                // Store the session ID, which will be marked as successful by ApexManager upon
                // boot completion.
                synchronized (mSuccessfulStagedSessionIds) {
                    mSuccessfulStagedSessionIds.add(session.sessionId());
                }
            } else {
                // Mark sessions as successful immediately on non-checkpointing devices.
                mApexManager.markStagedSessionSuccessful(session.sessionId());
            }
        }
    }

    void onInstallationFailure(StagedSession session, PackageManagerException e,
            boolean supportsCheckpoint, boolean needsCheckpoint) {
        session.setSessionFailed(e.error, e.getMessage());
        abortCheckpoint("Failed to install sessionId: " + session.sessionId()
                + " Error: " + e.getMessage(), supportsCheckpoint, needsCheckpoint);

        // If checkpoint is not supported, we have to handle failure for one staged session.
        if (!session.containsApexSession()) {
            return;
        }

        if (!mApexManager.revertActiveSessions()) {
            Slog.e(TAG, "Failed to abort APEXd session");
        } else {
            Slog.e(TAG,
                    "Successfully aborted apexd session. Rebooting device in order to revert "
                            + "to the previous state of APEXd.");
            mPowerManager.reboot(null);
        }
    }

    private String getReasonForRevert() {
        if (!TextUtils.isEmpty(mFailureReason)) {
            return mFailureReason;
        }
        if (!TextUtils.isEmpty(mNativeFailureReason)) {
            return "Session reverted due to crashing native process: " + mNativeFailureReason;
        }
        return "";
    }

    /**
     * Throws a PackageManagerException if there are duplicate packages in apk and apk-in-apex.
     */
    private void checkDuplicateApkInApex(@NonNull StagedSession session)
            throws PackageManagerException {
        if (!session.isMultiPackage()) {
            return;
        }
        final Set<String> apkNames = new ArraySet<>();
        for (StagedSession s : session.getChildSessions()) {
            if (!s.isApexSession()) {
                apkNames.add(s.getPackageName());
            }
        }
        final List<StagedSession> apexSessions = extractApexSessions(session);
        for (StagedSession apexSession : apexSessions) {
            String packageName = apexSession.getPackageName();
            for (String apkInApex : mApexManager.getApksInApex(packageName)) {
                if (!apkNames.add(apkInApex)) {
                    throw new PackageManagerException(
                            SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                            "Package: " + packageName + " in session: "
                                    + apexSession.sessionId() + " has duplicate apk-in-apex: "
                                    + apkInApex, null);

                }
            }
        }
    }

    private void installApksInSession(StagedSession session)
            throws PackageManagerException {
        if (!session.containsApkSession()) {
            return;
        }

        final LocalIntentReceiverSync receiver = new LocalIntentReceiverSync();
        session.installSession(receiver.getIntentSender());
        final Intent result = receiver.getResult();
        final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status != PackageInstaller.STATUS_SUCCESS) {
            final String errorMessage = result.getStringExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE);
            Slog.e(TAG, "Failure to install APK staged session "
                    + session.sessionId() + " [" + errorMessage + "]");
            throw new PackageManagerException(
                    SessionInfo.STAGED_SESSION_ACTIVATION_FAILED, errorMessage);
        }
    }

    void commitSession(@NonNull StagedSession session) {
        // Store this parent session which will be used to check overlapping later
        createSession(session);
        mPreRebootVerificationHandler.startPreRebootVerification(session);
    }

    private int getSessionIdForParentOrSelf(StagedSession session) {
        return session.hasParentSessionId() ? session.getParentSessionId() : session.sessionId();
    }

    private StagedSession getParentSessionOrSelf(StagedSession session) {
        return session.hasParentSessionId()
                ? getStagedSession(session.getParentSessionId())
                : session;
    }

    private boolean isRollback(StagedSession session) {
        final StagedSession root = getParentSessionOrSelf(session);
        return root.sessionParams().installReason == PackageManager.INSTALL_REASON_ROLLBACK;
    }

    /**
     * <p> Check if the session provided is non-overlapping with the active staged sessions.
     *
     * <p> A session is non-overlapping if it meets one of the following conditions: </p>
     * <ul>
     *     <li>It is a parent session</li>
     *     <li>It is already one of the active sessions</li>
     *     <li>Its package name is not same as any of the active sessions</li>
     * </ul>
     * @throws PackageManagerException if session fails the check
     */
    // TODO(b/192625695): Rename this method which checks rollbacks in addition to overlapping
    @VisibleForTesting
    void checkNonOverlappingWithStagedSessions(@NonNull StagedSession session)
            throws PackageManagerException {
        if (session.isMultiPackage()) {
            // We cannot say a parent session overlaps until we process its children
            return;
        }

        String packageName = session.getPackageName();
        if (packageName == null) {
            throw new PackageManagerException(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                    "Cannot stage session " + session.sessionId() + " with package name null");
        }

        boolean supportsCheckpoint;
        try {
            supportsCheckpoint = PackageHelper.getStorageManager().supportsCheckpoint();
        } catch (RemoteException e) {
            throw new PackageManagerException(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                    "Can't query fs-checkpoint status : " + e);
        }

        final boolean isRollback = isRollback(session);

        synchronized (mStagedSessions) {
            for (int i = 0; i < mStagedSessions.size(); i++) {
                final StagedSession stagedSession = mStagedSessions.valueAt(i);
                if (stagedSession.hasParentSessionId() || !stagedSession.isCommitted()
                        || stagedSession.isInTerminalState()
                        || stagedSession.isDestroyed()) {
                    continue;
                }

                // From here on, stagedSession is a parent active staged session

                // Check if session is one of the active sessions
                if (getSessionIdForParentOrSelf(session) == stagedSession.sessionId()) {
                    Slog.w(TAG, "Session " + session.sessionId() + " is already staged");
                    continue;
                }

                if (isRollback && !isRollback(stagedSession)) {
                    // If the new session is a rollback, then it gets priority. The existing
                    // session is failed to reduce risk and avoid an SDK extension dependency
                    // violation.
                    final StagedSession root = stagedSession;
                    if (!ensureActiveApexSessionIsAborted(root)) {
                        Slog.e(TAG, "Failed to abort apex session " + root.sessionId());
                        // Safe to ignore active apex session abort failure since session
                        // will be marked failed on next step and staging directory for session
                        // will be deleted.
                    }
                    root.setSessionFailed(
                            SessionInfo.STAGED_SESSION_CONFLICT,
                            "Session was failed by rollback session: " + session.sessionId());
                    Slog.i(TAG, "Session " + root.sessionId() + " is marked failed due to "
                            + "rollback session: " + session.sessionId());
                } else if (!isRollback && isRollback(stagedSession)) {
                    throw new PackageManagerException(
                            SessionInfo.STAGED_SESSION_CONFLICT,
                            "Session was failed by rollback session: " + stagedSession.sessionId());
                } else if (stagedSession.sessionContains(
                        s -> s.getPackageName().equals(packageName))) {
                    // Fail the session committed later when there are overlapping packages
                    if (stagedSession.getCommittedMillis() < session.getCommittedMillis()) {
                        throw new PackageManagerException(
                                SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                                "Package: " + session.getPackageName() + " in session: "
                                        + session.sessionId()
                                        + " has been staged already by session: "
                                        + stagedSession.sessionId(), null);
                    } else {
                        stagedSession.setSessionFailed(
                                SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                                "Package: " + packageName + " in session: "
                                        + stagedSession.sessionId()
                                        + " has been staged already by session: "
                                        + session.sessionId());
                    }
                }

                // Staging multiple root sessions is not allowed if device doesn't support
                // checkpoint. If session and stagedSession do not have common ancestor, they are
                // from two different root sessions.
                if (!supportsCheckpoint) {
                    throw new PackageManagerException(
                            SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                            "Cannot stage multiple sessions without checkpoint support", null);
                }
            }
        }
    }

    /**
     * Returns id of a committed and non-finalized stated session that contains same
     * {@code packageName}, or {@code -1} if no sessions have this {@code packageName} staged.
     */
    int getSessionIdByPackageName(@NonNull String packageName) {
        synchronized (mStagedSessions) {
            for (int i = 0; i < mStagedSessions.size(); i++) {
                StagedSession stagedSession = mStagedSessions.valueAt(i);
                if (!stagedSession.isCommitted() || stagedSession.isDestroyed()
                        || stagedSession.isInTerminalState()) {
                    continue;
                }
                if (stagedSession.getPackageName().equals(packageName)) {
                    return stagedSession.sessionId();
                }
            }
        }
        return -1;
    }

    @VisibleForTesting
    void createSession(@NonNull StagedSession sessionInfo) {
        synchronized (mStagedSessions) {
            mStagedSessions.append(sessionInfo.sessionId(), sessionInfo);
        }
    }

    void abortSession(@NonNull StagedSession session) {
        synchronized (mStagedSessions) {
            mStagedSessions.remove(session.sessionId());
        }
    }

    /**
     * <p>Abort committed staged session
     */
    void abortCommittedSession(@NonNull StagedSession session) {
        int sessionId = session.sessionId();
        if (session.isInTerminalState()) {
            Slog.w(TAG, "Cannot abort session in final state: " + sessionId);
            return;
        }
        if (!session.isDestroyed()) {
            throw new IllegalStateException("Committed session must be destroyed before aborting it"
                    + " from StagingManager");
        }
        if (getStagedSession(sessionId) == null) {
            Slog.w(TAG, "Session " + sessionId + " has been abandoned already");
            return;
        }

        // A session could be marked ready once its pre-reboot verification ends
        if (session.isSessionReady()) {
            if (!ensureActiveApexSessionIsAborted(session)) {
                // Failed to ensure apex session is aborted, so it can still be staged. We can still
                // safely cleanup the staged session since pre-reboot verification is complete.
                // Also, cleaning up the stageDir prevents the apex from being activated.
                Slog.e(TAG, "Failed to abort apex session " + session.sessionId());
            }
            if (session.containsApexSession()) {
                notifyStagedApexObservers();
            }
        }

        // Session was successfully aborted from apexd (if required) and pre-reboot verification
        // is also complete. It is now safe to clean up the session from system.
        abortSession(session);
    }

    /**
     * Ensure that there is no active apex session staged in apexd for the given session.
     *
     * @return returns true if it is ensured that there is no active apex session, otherwise false
     */
    private boolean ensureActiveApexSessionIsAborted(StagedSession session) {
        if (!session.containsApexSession()) {
            return true;
        }
        final ApexSessionInfo apexSession = mApexManager.getStagedSessionInfo(session.sessionId());
        if (apexSession == null || isApexSessionFinalized(apexSession)) {
            return true;
        }
        return mApexManager.abortStagedSession(session.sessionId());
    }

    private boolean isApexSessionFinalized(ApexSessionInfo session) {
        /* checking if the session is in a final state, i.e., not active anymore */
        return session.isUnknown || session.isActivationFailed || session.isSuccess
                || session.isReverted;
    }

    private static boolean isApexSessionFailed(ApexSessionInfo apexSessionInfo) {
        // isRevertInProgress is included to cover the scenario, when a device is rebooted
        // during the revert, and apexd fails to resume the revert after reboot.
        return apexSessionInfo.isActivationFailed || apexSessionInfo.isUnknown
                || apexSessionInfo.isReverted || apexSessionInfo.isRevertInProgress
                || apexSessionInfo.isRevertFailed;
    }

    private void handleNonReadyAndDestroyedSessions(List<StagedSession> sessions) {
        int j = sessions.size();
        for (int i = 0; i < j; ) {
            // Maintain following invariant:
            //  * elements at positions [0, i) should be kept
            //  * elements at positions [j, n) should be remove.
            //  * n = sessions.size()
            StagedSession session = sessions.get(i);
            if (session.isDestroyed()) {
                // Device rebooted before abandoned session was cleaned up.
                session.abandon();
                StagedSession session2 = sessions.set(j - 1, session);
                sessions.set(i, session2);
                j--;
            } else if (!session.isSessionReady()) {
                // The framework got restarted before the pre-reboot verification could complete,
                // restart the verification.
                Slog.i(TAG, "Restart verification for session=" + session.sessionId());
                mBootCompleted.thenRun(() -> session.verifySession());
                StagedSession session2 = sessions.set(j - 1, session);
                sessions.set(i, session2);
                j--;
            } else {
                i++;
            }
        }
        // Delete last j elements.
        sessions.subList(j, sessions.size()).clear();
    }

    void restoreSessions(@NonNull List<StagedSession> sessions, boolean isDeviceUpgrading) {
        TimingsTraceLog t = new TimingsTraceLog(
                "StagingManagerTiming", Trace.TRACE_TAG_PACKAGE_MANAGER);
        t.traceBegin("restoreSessions");

        // Do not resume sessions if boot completed already
        if (SystemProperties.getBoolean("sys.boot_completed", false)) {
            return;
        }

        for (int i = 0; i < sessions.size(); i++) {
            StagedSession session = sessions.get(i);
            // Quick check that PackageInstallerService gave us sessions we expected.
            Preconditions.checkArgument(!session.hasParentSessionId(),
                    session.sessionId() + " is a child session");
            Preconditions.checkArgument(session.isCommitted(),
                    session.sessionId() + " is not committed");
            Preconditions.checkArgument(!session.isInTerminalState(),
                    session.sessionId() + " is in terminal state");
            // Store this parent session which will be used to check overlapping later
            createSession(session);
        }

        if (isDeviceUpgrading) {
            // TODO(ioffe): check that corresponding apex sessions are failed.
            // The preconditions used during pre-reboot verification might have changed when device
            // is upgrading. Fail all the sessions and exit early.
            for (int i = 0; i < sessions.size(); i++) {
                StagedSession session = sessions.get(i);
                session.setSessionFailed(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "Build fingerprint has changed");
            }
            return;
        }

        boolean needsCheckpoint = false;
        boolean supportsCheckpoint = false;
        try {
            supportsCheckpoint = PackageHelper.getStorageManager().supportsCheckpoint();
            needsCheckpoint = PackageHelper.getStorageManager().needsCheckpoint();
        } catch (RemoteException e) {
            // This means that vold has crashed, and device is in a bad state.
            throw new IllegalStateException("Failed to get checkpoint status", e);
        }

        if (sessions.size() > 1 && !supportsCheckpoint) {
            throw new IllegalStateException("Detected multiple staged sessions on a device without "
                    + "fs-checkpoint support");
        }

        // Do a set of quick checks before resuming individual sessions:
        //   1. Schedule a pre-reboot verification for non-ready sessions.
        //   2. Abandon destroyed sessions.
        handleNonReadyAndDestroyedSessions(sessions); // mutates |sessions|

        //   3. Check state of apex sessions is consistent. All non-applied sessions will be marked
        //      as failed.
        final SparseArray<ApexSessionInfo> apexSessions = mApexManager.getSessions();
        boolean hasFailedApexSession = false;
        boolean hasAppliedApexSession = false;
        for (int i = 0; i < sessions.size(); i++) {
            StagedSession session = sessions.get(i);
            if (!session.containsApexSession()) {
                // At this point we are only interested in apex sessions.
                continue;
            }
            final ApexSessionInfo apexSession = apexSessions.get(session.sessionId());
            if (apexSession == null || apexSession.isUnknown) {
                hasFailedApexSession = true;
                session.setSessionFailed(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED, "apexd did "
                        + "not know anything about a staged session supposed to be activated");
                continue;
            } else if (isApexSessionFailed(apexSession)) {
                hasFailedApexSession = true;
                if (!TextUtils.isEmpty(apexSession.crashingNativeProcess)) {
                    prepareForLoggingApexdRevert(session, apexSession.crashingNativeProcess);
                }
                String errorMsg = "APEX activation failed.";
                final String reasonForRevert = getReasonForRevert();
                if (!TextUtils.isEmpty(reasonForRevert)) {
                    errorMsg += " Reason: " + reasonForRevert;
                } else if (!TextUtils.isEmpty(apexSession.errorMessage)) {
                    errorMsg += " Error: " + apexSession.errorMessage;
                }
                Slog.d(TAG, errorMsg);
                session.setSessionFailed(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED, errorMsg);
                continue;
            } else if (apexSession.isActivated || apexSession.isSuccess) {
                hasAppliedApexSession = true;
                continue;
            } else if (apexSession.isStaged) {
                // Apexd did not apply the session for some unknown reason. There is no guarantee
                // that apexd will install it next time. Safer to proactively mark it as failed.
                hasFailedApexSession = true;
                session.setSessionFailed(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "Staged session " + session.sessionId() + " at boot didn't activate nor "
                        + "fail. Marking it as failed anyway.");
            } else {
                Slog.w(TAG, "Apex session " + session.sessionId() + " is in impossible state");
                hasFailedApexSession = true;
                session.setSessionFailed(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "Impossible state");
            }
        }

        if (hasAppliedApexSession && hasFailedApexSession) {
            abortCheckpoint("Found both applied and failed apex sessions", supportsCheckpoint,
                    needsCheckpoint);
            return;
        }

        if (hasFailedApexSession) {
            // Either of those means that we failed at least one apex session, hence we should fail
            // all other sessions.
            for (int i = 0; i < sessions.size(); i++) {
                StagedSession session = sessions.get(i);
                if (session.isSessionFailed()) {
                    // Session has been already failed in the loop above.
                    continue;
                }
                session.setSessionFailed(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "Another apex session failed");
            }
            return;
        }

        // Time to resume sessions.
        for (int i = 0; i < sessions.size(); i++) {
            StagedSession session = sessions.get(i);
            try {
                resumeSession(session, supportsCheckpoint, needsCheckpoint);
            } catch (PackageManagerException e) {
                onInstallationFailure(session, e, supportsCheckpoint, needsCheckpoint);
            } catch (Exception e) {
                Slog.e(TAG, "Staged install failed due to unhandled exception", e);
                onInstallationFailure(session, new PackageManagerException(
                        SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "Staged install failed due to unhandled exception: " + e),
                        supportsCheckpoint, needsCheckpoint);
            }
        }
        t.traceEnd();
    }

    private void logFailedApexSessionsIfNecessary() {
        synchronized (mFailedPackageNames) {
            if (!mFailedPackageNames.isEmpty()) {
                WatchdogRollbackLogger.logApexdRevert(mContext,
                        mFailedPackageNames, mNativeFailureReason);
            }
        }
    }

    private void markStagedSessionsAsSuccessful() {
        synchronized (mSuccessfulStagedSessionIds) {
            for (int i = 0; i < mSuccessfulStagedSessionIds.size(); i++) {
                mApexManager.markStagedSessionSuccessful(mSuccessfulStagedSessionIds.get(i));
            }
        }
    }

    void systemReady() {
        new Lifecycle(mContext).startService(this);
        // Register the receiver of boot completed intent for staging manager.
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                onBootCompletedBroadcastReceived();
                ctx.unregisterReceiver(this);
            }
        }, new IntentFilter(Intent.ACTION_BOOT_COMPLETED));

        mFailureReasonFile.delete();
    }

    @VisibleForTesting
    void onBootCompletedBroadcastReceived() {
        mBootCompleted.complete(null);
        BackgroundThread.getExecutor().execute(() -> logFailedApexSessionsIfNecessary());
    }

    private static class LocalIntentReceiverSync {
        private final LinkedBlockingQueue<Intent> mResult = new LinkedBlockingQueue<>();

        private final IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission,
                    Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            try {
                return mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private StagedSession getStagedSession(int sessionId) {
        StagedSession session;
        synchronized (mStagedSessions) {
            session = mStagedSessions.get(sessionId);
        }
        return session;
    }

    /**
     * Returns ApexInfo about APEX contained inside the session as a {@code Map<String, ApexInfo>},
     * where the key of the map is the module name of the ApexInfo.
     *
     * Returns an empty map if there is any error.
     */
    @VisibleForTesting
    @NonNull
    Map<String, ApexInfo> getStagedApexInfos(@NonNull StagedSession session) {
        Preconditions.checkArgument(session != null, "Session is null");
        Preconditions.checkArgument(!session.hasParentSessionId(),
                session.sessionId() + " session has parent session");
        Preconditions.checkArgument(session.containsApexSession(),
                session.sessionId() + " session does not contain apex");

        // Even if caller calls this method on ready session, the session could be abandoned
        // right after this method is called.
        if (!session.isSessionReady() || session.isDestroyed()) {
            return Collections.emptyMap();
        }

        ApexSessionParams params = new ApexSessionParams();
        params.sessionId = session.sessionId();
        final IntArray childSessionIds = new IntArray();
        if (session.isMultiPackage()) {
            for (StagedSession s : session.getChildSessions()) {
                if (s.isApexSession()) {
                    childSessionIds.add(s.sessionId());
                }
            }
        }
        params.childSessionIds = childSessionIds.toArray();

        ApexInfo[] infos = mApexManager.getStagedApexInfos(params);
        Map<String, ApexInfo> result = new ArrayMap<>();
        for (ApexInfo info : infos) {
            result.put(info.moduleName, info);
        }
        return result;
    }

    /**
     * Returns apex module names of all packages that are staged ready
     */
    List<String> getStagedApexModuleNames() {
        List<String> result = new ArrayList<>();
        synchronized (mStagedSessions) {
            for (int i = 0; i < mStagedSessions.size(); i++) {
                final StagedSession session = mStagedSessions.valueAt(i);
                if (!session.isSessionReady() || session.isDestroyed()
                        || session.hasParentSessionId() || !session.containsApexSession()) {
                    continue;
                }
                result.addAll(getStagedApexInfos(session).keySet());
            }
        }
        return result;
    }

    /**
     * Returns ApexInfo of the {@code moduleInfo} provided if it is staged, otherwise returns null.
     */
    @Nullable
    StagedApexInfo getStagedApexInfo(String moduleName) {
        synchronized (mStagedSessions) {
            for (int i = 0; i < mStagedSessions.size(); i++) {
                final StagedSession session = mStagedSessions.valueAt(i);
                if (!session.isSessionReady() || session.isDestroyed()
                        || session.hasParentSessionId() || !session.containsApexSession()) {
                    continue;
                }
                ApexInfo ai = getStagedApexInfos(session).get(moduleName);
                if (ai != null) {
                    StagedApexInfo info = new StagedApexInfo();
                    info.moduleName = ai.moduleName;
                    info.diskImagePath = ai.modulePath;
                    info.versionCode = ai.versionCode;
                    info.versionName = ai.versionName;
                    return info;
                }
            }
        }
        return null;
    }

    private void notifyStagedApexObservers() {
        synchronized (mStagedApexObservers) {
            for (IStagedApexObserver observer : mStagedApexObservers) {
                ApexStagedEvent event = new ApexStagedEvent();
                event.stagedApexModuleNames = getStagedApexModuleNames().toArray(new String[0]);
                try {
                    observer.onApexStaged(event);
                } catch (RemoteException re) {
                    Slog.w(TAG, "Failed to contact the observer " + re.getMessage());
                }
            }
        }
    }

    @VisibleForTesting
    final class PreRebootVerificationHandler extends Handler {

        PreRebootVerificationHandler(Looper looper) {
            super(looper);
        }

        /**
         * Handler for states of pre reboot verification. The states are arranged linearly (shown
         * below) with each state either calling the next state, or calling some other method that
         * eventually calls the next state.
         *
         * <p><ul>
         *     <li>MSG_PRE_REBOOT_VERIFICATION_START</li>
         *     <li>MSG_PRE_REBOOT_VERIFICATION_APEX</li>
         *     <li>MSG_PRE_REBOOT_VERIFICATION_END</li>
         * </ul></p>
         *
         * Details about each of state can be found in corresponding handler of node.
         */
        private static final int MSG_PRE_REBOOT_VERIFICATION_START = 1;
        private static final int MSG_PRE_REBOOT_VERIFICATION_APEX = 2;
        @VisibleForTesting
        static final int MSG_PRE_REBOOT_VERIFICATION_END = 3;

        @Override
        public void handleMessage(Message msg) {
            final int sessionId = msg.arg1;
            final int rollbackId = msg.arg2;
            final StagedSession session = (StagedSession) msg.obj;
            if (session.isDestroyed() || session.isSessionFailed()) {
                // No point in running verification on a destroyed/failed session
                onPreRebootVerificationComplete(session);
                return;
            }
            try {
                switch (msg.what) {
                    case MSG_PRE_REBOOT_VERIFICATION_START:
                        handlePreRebootVerification_Start(session);
                        break;
                    case MSG_PRE_REBOOT_VERIFICATION_APEX:
                        handlePreRebootVerification_Apex(session, rollbackId);
                        break;
                    case MSG_PRE_REBOOT_VERIFICATION_END:
                        handlePreRebootVerification_End(session);
                        break;
                }
            } catch (Exception e) {
                Slog.e(TAG, "Pre-reboot verification failed due to unhandled exception", e);
                onPreRebootVerificationFailure(session,
                        SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "Pre-reboot verification failed due to unhandled exception: " + e);
            }
        }

        // Method for starting the pre-reboot verification
        private synchronized void startPreRebootVerification(
                @NonNull StagedSession session) {
            mBootCompleted.thenRun(() -> {
                int sessionId = session.sessionId();
                Slog.d(TAG, "Starting preRebootVerification for session " + sessionId);
                obtainMessage(MSG_PRE_REBOOT_VERIFICATION_START, sessionId, -1, session)
                        .sendToTarget();
            });
        }

        private void onPreRebootVerificationFailure(StagedSession session,
                @SessionInfo.StagedSessionErrorCode int errorCode, String errorMessage) {
            if (!ensureActiveApexSessionIsAborted(session)) {
                Slog.e(TAG, "Failed to abort apex session " + session.sessionId());
                // Safe to ignore active apex session abortion failure since session will be marked
                // failed on next step and staging directory for session will be deleted.
            }
            session.setSessionFailed(errorCode, errorMessage);
            onPreRebootVerificationComplete(session);
        }

        // Things to do when pre-reboot verification completes for a particular sessionId
        private void onPreRebootVerificationComplete(StagedSession session) {
            int sessionId = session.sessionId();
            Slog.d(TAG, "Stopping preRebootVerification for session " + sessionId);
            session.notifyEndPreRebootVerification();
        }

        private void notifyPreRebootVerification_Start_Complete(
                @NonNull StagedSession session, int rollbackId) {
            obtainMessage(MSG_PRE_REBOOT_VERIFICATION_APEX, session.sessionId(), rollbackId,
                    session).sendToTarget();
        }

        private void notifyPreRebootVerification_Apex_Complete(
                @NonNull StagedSession session) {
            obtainMessage(MSG_PRE_REBOOT_VERIFICATION_END, session.sessionId(), -1, session)
                    .sendToTarget();
        }

        /**
         * A placeholder state for starting the pre reboot verification.
         *
         * See {@link PreRebootVerificationHandler} to see all nodes of pre reboot verification
         */
        private void handlePreRebootVerification_Start(@NonNull StagedSession session) {
            try {
                if (session.isMultiPackage()) {
                    for (StagedSession s : session.getChildSessions()) {
                        checkNonOverlappingWithStagedSessions(s);
                    }
                } else {
                    checkNonOverlappingWithStagedSessions(session);
                }
            } catch (PackageManagerException e) {
                onPreRebootVerificationFailure(session, e.error, e.getMessage());
                return;
            }

            int rollbackId = -1;
            if ((session.sessionParams().installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK)
                    != 0) {
                // If rollback is enabled for this session, we call through to the RollbackManager
                // with the list of sessions it must enable rollback for. Note that
                // notifyStagedSession is a synchronous operation.
                final RollbackManagerInternal rm =
                        LocalServices.getService(RollbackManagerInternal.class);
                try {
                    // NOTE: To stay consistent with the non-staged install flow, we don't fail the
                    // entire install if rollbacks can't be enabled.
                    rollbackId = rm.notifyStagedSession(session.sessionId());
                } catch (RuntimeException re) {
                    Slog.e(TAG, "Failed to notifyStagedSession for session: "
                            + session.sessionId(), re);
                }
            } else if (session.sessionParams().installReason
                    == PackageManager.INSTALL_REASON_ROLLBACK) {
                try {
                    rollbackId = retrieveRollbackIdForCommitSession(session.sessionId());
                } catch (PackageManagerException e) {
                    onPreRebootVerificationFailure(session, e.error, e.getMessage());
                    return;
                }
            }

            notifyPreRebootVerification_Start_Complete(session, rollbackId);
        }

        /**
         * Pre-reboot verification state for apex files:
         *
         * <p><ul>
         *     <li>submits session to apex service</li>
         *     <li>validates signatures of apex files</li>
         * </ul></p>
         */
        private void handlePreRebootVerification_Apex(
                @NonNull StagedSession session, int rollbackId) {
            final boolean hasApex = session.containsApexSession();

            // APEX checks. For single-package sessions, check if they contain an APEX. For
            // multi-package sessions, find all the child sessions that contain an APEX.
            if (hasApex) {
                final List<PackageInfo> apexPackages;
                try {
                    apexPackages = submitSessionToApexService(session, rollbackId);
                    for (int i = 0, size = apexPackages.size(); i < size; i++) {
                        validateApexSignature(apexPackages.get(i));
                    }
                } catch (PackageManagerException e) {
                    onPreRebootVerificationFailure(session, e.error, e.getMessage());
                    return;
                }

                final PackageManagerInternal packageManagerInternal =
                        LocalServices.getService(PackageManagerInternal.class);
                packageManagerInternal.pruneCachedApksInApex(apexPackages);
            }

            notifyPreRebootVerification_Apex_Complete(session);
        }

        /**
         * Pre-reboot verification state for wrapping up:
         * <p><ul>
         *     <li>enables rollback if required</li>
         *     <li>marks session as ready</li>
         * </ul></p>
         */
        private void handlePreRebootVerification_End(@NonNull StagedSession session) {
            // Before marking the session as ready, start checkpoint service if available
            try {
                if (PackageHelper.getStorageManager().supportsCheckpoint()) {
                    PackageHelper.getStorageManager().startCheckpoint(2);
                }
            } catch (Exception e) {
                // Failed to get hold of StorageManager
                Slog.e(TAG, "Failed to get hold of StorageManager", e);
                onPreRebootVerificationFailure(session, SessionInfo.STAGED_SESSION_UNKNOWN,
                        "Failed to get hold of StorageManager");
                return;
            }

            // Stop pre-reboot verification before marking session ready. From this point on, if we
            // abandon the session then it will be cleaned up immediately. If session is abandoned
            // after this point, then even if for some reason system tries to install the session
            // or activate its apex, there won't be any files to work with as they will be cleaned
            // up by the system as part of abandonment. If session is abandoned before this point,
            // then the session is already destroyed and cannot be marked ready anymore.
            onPreRebootVerificationComplete(session);

            // Proactively mark session as ready before calling apexd. Although this call order
            // looks counter-intuitive, this is the easiest way to ensure that session won't end up
            // in the inconsistent state:
            //  - If device gets rebooted right before call to apexd, then apexd will never activate
            //      apex files of this staged session. This will result in StagingManager failing
            //      the session.
            // On the other hand, if the order of the calls was inverted (first call apexd, then
            // mark session as ready), then if a device gets rebooted right after the call to apexd,
            // only apex part of the train will be applied, leaving device in an inconsistent state.
            Slog.d(TAG, "Marking session " + session.sessionId() + " as ready");
            session.setSessionReady();
            if (session.isSessionReady()) {
                final boolean hasApex = session.containsApexSession();
                if (hasApex) {
                    try {
                        mApexManager.markStagedSessionReady(session.sessionId());
                        notifyStagedApexObservers();
                    } catch (PackageManagerException e) {
                        session.setSessionFailed(e.error, e.getMessage());
                        return;
                    }
                }
            }
        }
    }
}
