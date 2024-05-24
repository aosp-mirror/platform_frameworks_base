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

import android.apex.ApexInfo;
import android.apex.ApexInfoList;
import android.apex.ApexSessionInfo;
import android.apex.ApexSessionParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Slog;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.pm.parsing.PackageParser2;
import com.android.internal.pm.parsing.PackageParserException;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.rollback.RollbackManagerInternal;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class PackageSessionVerifier {
    private static final String TAG = "PackageSessionVerifier";

    interface Callback {
        void onResult(int returnCode, String msg);
    }

    private final Context mContext;
    private final PackageManagerService mPm;
    private final ApexManager mApexManager;
    private final Supplier<PackageParser2> mPackageParserSupplier;
    // The handler thread to run verification tasks. Since all tasks run in the same thread,
    // there is no need for synchronization.
    private final Handler mHandler;
    // Parent sessions for checking session conflicts.
    private final List<StagingManager.StagedSession> mStagedSessions = new ArrayList<>();

    PackageSessionVerifier(Context context, PackageManagerService pm,
            ApexManager apexManager, Supplier<PackageParser2> packageParserSupplier,
            Looper looper) {
        mContext = context;
        mPm = pm;
        mApexManager = apexManager;
        mPackageParserSupplier = packageParserSupplier;
        mHandler = new Handler(looper);
    }

    @VisibleForTesting
    PackageSessionVerifier() {
        mContext = null;
        mPm = null;
        mApexManager = null;
        mPackageParserSupplier = null;
        mHandler = null;
    }

    /**
     * Runs verifications that are common to both staged and non-staged sessions.
     */
    public void verify(PackageInstallerSession session, Callback callback) {
        mHandler.post(() -> {
            try {
                storeSession(session.mStagedSession);
                if (session.isMultiPackage()) {
                    for (PackageInstallerSession child : session.getChildSessions()) {
                        checkApexUpdateAllowed(child);
                        checkRebootlessApex(child);
                        checkApexSignature(child);
                    }
                } else {
                    checkApexUpdateAllowed(session);
                    checkRebootlessApex(session);
                    checkApexSignature(session);
                }
                verifyAPK(session, callback);
            } catch (PackageManagerException e) {
                String errorMessage = PackageManager.installStatusToString(e.error, e.getMessage());
                session.setSessionFailed(e.error, errorMessage);
                callback.onResult(e.error, e.getMessage());
            }
        });
    }

    private SigningDetails getSigningDetails(PackageInfo apexPkg) throws PackageManagerException {
        final String apexPath = apexPkg.applicationInfo.sourceDir;
        final int minSignatureScheme =
                ApkSignatureVerifier.getMinimumSignatureSchemeVersionForTargetSdk(
                        apexPkg.applicationInfo.targetSdkVersion);
        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        final ParseResult<SigningDetails> result = ApkSignatureVerifier.verify(
                input, apexPath, minSignatureScheme);
        if (result.isError()) {
            throw new PackageManagerException(PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                    "Failed to verify APEX package " + apexPath + " : "
                            + result.getException(), result.getException());
        }
        return result.getResult();
    }

    private void checkApexSignature(PackageInstallerSession session)
            throws PackageManagerException {
        if (!session.isApexSession()) {
            return;
        }
        final String packageName = session.getPackageName();
        final PackageInfo existingApexPkg = mPm.snapshotComputer().getPackageInfo(
                session.getPackageName(), PackageManager.MATCH_APEX, UserHandle.USER_SYSTEM);
        if (existingApexPkg == null) {
            throw new PackageManagerException(PackageManager.INSTALL_FAILED_PACKAGE_CHANGED,
                    "Attempting to install new APEX package " + packageName);
        }
        final SigningDetails existingSigningDetails = getSigningDetails(existingApexPkg);
        final SigningDetails newSigningDetails = session.getSigningDetails();
        if (newSigningDetails.checkCapability(existingSigningDetails,
                SigningDetails.CertCapabilities.INSTALLED_DATA)
                || existingSigningDetails.checkCapability(newSigningDetails,
                SigningDetails.CertCapabilities.ROLLBACK)) {
            return;
        }
        throw new PackageManagerException(PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                "APK container signature of APEX package " + packageName
                        + " is not compatible with the one currently installed on device");
    }

    /**
     * Runs verifications particular to APK. This includes APEX sessions since an APEX can also
     * be treated as APK.
     */
    private void verifyAPK(PackageInstallerSession session, Callback callback)
            throws PackageManagerException {
        final IPackageInstallObserver2 observer = new IPackageInstallObserver2.Stub() {
            @Override
            public void onUserActionRequired(Intent intent) {
                throw new IllegalStateException();
            }
            @Override
            public void onPackageInstalled(String basePackageName, int returnCode, String msg,
                    Bundle extras) {
                if (session.isStaged() && returnCode == PackageManager.INSTALL_SUCCEEDED) {
                    // Continue verification for staged sessions
                    verifyStaged(session.mStagedSession, callback);
                    return;
                }
                if (returnCode != PackageManager.INSTALL_SUCCEEDED) {
                    String errorMessage = PackageManager.installStatusToString(returnCode, msg);
                    session.setSessionFailed(returnCode, errorMessage);
                    callback.onResult(returnCode, msg);
                } else {
                    session.setSessionReady();
                    callback.onResult(PackageManager.INSTALL_SUCCEEDED, null);
                }
            }
        };
        final VerifyingSession verifyingSession = createVerifyingSession(session, observer);
        if (session.isMultiPackage()) {
            final List<PackageInstallerSession> childSessions = session.getChildSessions();
            List<VerifyingSession> verifyingChildSessions = new ArrayList<>(childSessions.size());
            for (PackageInstallerSession child : childSessions) {
                verifyingChildSessions.add(createVerifyingSession(child, null));
            }
            verifyingSession.verifyStage(verifyingChildSessions);
        } else {
            verifyingSession.verifyStage();
        }
    }

    private VerifyingSession createVerifyingSession(
            PackageInstallerSession session, IPackageInstallObserver2 observer) {
        final UserHandle user;
        if ((session.params.installFlags & PackageManager.INSTALL_ALL_USERS) != 0) {
            user = UserHandle.ALL;
        } else {
            user = new UserHandle(session.userId);
        }
        return new VerifyingSession(user, session.stageDir, observer, session.params,
                session.getInstallSource(), session.getInstallerUid(), session.getSigningDetails(),
                session.sessionId, session.getPackageLite(), session.getUserActionRequired(), mPm);
    }

    /**
     * Starts pre-reboot verification for the staged-session. This operation is broken into the
     * following phases:
     * <ul>
     *     <li>Checks if multiple active sessions are supported.</li>
     *     <li>Checks conflicts in rollbacks and overlapping packages.</li>
     *     <li>Submits apex sessions to apex service.</li>
     *     <li>Validates signatures of apex files.</li>
     *     <li>Notifies the result of verification.</li>
     * </ul>
     *
     * Note it is the responsibility of the caller to ensure the staging files remain unchanged
     * while the verification is in progress.
     */
    private void verifyStaged(StagingManager.StagedSession session, Callback callback) {
        Slog.d(TAG, "Starting preRebootVerification for session " + session.sessionId());
        mHandler.post(() -> {
            try {
                checkActiveSessions();
                checkRollbacks(session);
                if (session.isMultiPackage()) {
                    for (StagingManager.StagedSession child : session.getChildSessions()) {
                        checkOverlaps(session, child);
                    }
                } else {
                    checkOverlaps(session, session);
                }
                dispatchVerifyApex(session, callback);
            } catch (PackageManagerException e) {
                onVerificationFailure(session, callback, e.error, e.getMessage());
            }
        });
    }

    /**
     * Stores staged-sessions for checking package overlapping and rollback conflicts.
     */
    @VisibleForTesting
    void storeSession(StagingManager.StagedSession session) {
        if (session != null) {
            mStagedSessions.add(session);
        }
    }

    private void onVerificationSuccess(StagingManager.StagedSession session, Callback callback) {
        callback.onResult(PackageManager.INSTALL_SUCCEEDED, null);
    }

    private void onVerificationFailure(StagingManager.StagedSession session, Callback callback,
            int errorCode, String errorMessage) {
        if (!ensureActiveApexSessionIsAborted(session)) {
            Slog.e(TAG, "Failed to abort apex session " + session.sessionId());
            // Safe to ignore active apex session abortion failure since session will be marked
            // failed on next step and staging directory for session will be deleted.
        }
        session.setSessionFailed(errorCode, errorMessage);
        callback.onResult(PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE, errorMessage);
    }

    private void dispatchVerifyApex(StagingManager.StagedSession session, Callback callback) {
        mHandler.post(() -> {
            try {
                verifyApex(session);
                dispatchEndVerification(session, callback);
            } catch (PackageManagerException e) {
                onVerificationFailure(session, callback, e.error, e.getMessage());
            }
        });
    }

    private void dispatchEndVerification(StagingManager.StagedSession session, Callback callback) {
        mHandler.post(() -> {
            try {
                endVerification(session);
                onVerificationSuccess(session, callback);
            } catch (PackageManagerException e) {
                onVerificationFailure(session, callback, e.error, e.getMessage());
            }
        });
    }

    /**
     * Pre-reboot verification phase for apex files:
     *
     * <p><ul>
     *     <li>submits session to apex service</li>
     *     <li>validates signatures of apex files</li>
     * </ul></p>
     */
    private void verifyApex(StagingManager.StagedSession session) throws PackageManagerException {
        int rollbackId = -1;
        if ((session.sessionParams().installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0) {
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
        } else if (isRollback(session)) {
            rollbackId = retrieveRollbackIdForCommitSession(session.sessionId());
        }

        final boolean hasApex = session.containsApexSession();
        // APEX checks. For single-package sessions, check if they contain an APEX. For
        // multi-package sessions, find all the child sessions that contain an APEX.
        if (hasApex) {
            submitSessionToApexService(session, rollbackId);
        }
    }

    /**
     * Pre-reboot verification phase for wrapping up:
     * <p><ul>
     *     <li>enables checkpoint if supported</li>
     *     <li>marks session as ready</li>
     * </ul></p>
     */
    private void endVerification(StagingManager.StagedSession session)
            throws PackageManagerException {
        // Before marking the session as ready, start checkpoint service if available
        try {
            if (InstallLocationUtils.getStorageManager().supportsCheckpoint()) {
                InstallLocationUtils.getStorageManager().startCheckpoint(2);
            }
        } catch (Exception e) {
            // Failed to get hold of StorageManager
            Slog.e(TAG, "Failed to get hold of StorageManager", e);
            throw new PackageManagerException(
                    PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                    "Failed to get hold of StorageManager");
        }
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
                mApexManager.markStagedSessionReady(session.sessionId());
            }
        }
    }

    private void submitSessionToApexService(StagingManager.StagedSession session,
            int rollbackId) throws PackageManagerException {
        final IntArray childSessionIds = new IntArray();
        if (session.isMultiPackage()) {
            for (StagingManager.StagedSession s : session.getChildSessions()) {
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
        final List<String> apexPackageNames = new ArrayList<>();
        for (ApexInfo apexInfo : apexInfoList.apexInfos) {
            final ParsedPackage parsedPackage;
            try (PackageParser2 packageParser = mPackageParserSupplier.get()) {
                File apexFile = new File(apexInfo.modulePath);
                parsedPackage = packageParser.parsePackage(apexFile, 0, false);
            } catch (PackageParserException e) {
                throw new PackageManagerException(
                        PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                        "Failed to parse APEX package " + apexInfo.modulePath + " : " + e, e);
            }
            apexPackageNames.add(parsedPackage.getPackageName());
        }
        Slog.d(TAG, "Session " + session.sessionId() + " has following APEX packages: "
                + apexPackageNames);
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
                PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                "Could not find rollback id for commit session: " + sessionId);
    }

    private static boolean isRollback(StagingManager.StagedSession session) {
        return session.sessionParams().installReason == PackageManager.INSTALL_REASON_ROLLBACK;
    }

    private static boolean isApexSessionFinalized(ApexSessionInfo info) {
        /* checking if the session is in a final state, i.e., not active anymore */
        return info.isUnknown || info.isActivationFailed || info.isSuccess
                || info.isReverted;
    }

    private boolean ensureActiveApexSessionIsAborted(StagingManager.StagedSession session) {
        if (!session.containsApexSession()) {
            return true;
        }
        final int sessionId = session.sessionId();
        final ApexSessionInfo apexSession = mApexManager.getStagedSessionInfo(sessionId);
        if (apexSession == null || isApexSessionFinalized(apexSession)) {
            return true;
        }
        return mApexManager.abortStagedSession(sessionId);
    }

    private boolean isApexUpdateAllowed(String apexPackageName, String installerPackageName) {
        if (mPm.getModuleInfo(apexPackageName, 0) != null) {
            final String modulesInstaller =
                    SystemConfig.getInstance().getModulesInstallerPackageName();
            if (modulesInstaller == null) {
                Slog.w(TAG, "No modules installer defined");
                return false;
            }
            return modulesInstaller.equals(installerPackageName);
        }
        final String vendorApexInstaller =
                SystemConfig.getInstance().getAllowedVendorApexes().get(apexPackageName);
        if (vendorApexInstaller == null) {
            Slog.w(TAG, apexPackageName + " is not allowed to be updated");
            return false;
        }
        return vendorApexInstaller.equals(installerPackageName);
    }

    /**
     * Checks if APEX update is allowed.
     *
     * This phase is shared between staged and non-staged sessions and should be called after
     * boot is completed since this check depends on the ModuleInfoProvider, which is only populated
     * after device has booted.
     */
    private void checkApexUpdateAllowed(PackageInstallerSession session)
            throws PackageManagerException {
        if (!session.isApexSession()) {
            return;
        }
        final int installFlags = session.params.installFlags;
        if ((installFlags & PackageManager.INSTALL_DISABLE_ALLOWED_APEX_UPDATE_CHECK) != 0) {
            return;
        }
        final String packageName = session.getPackageName();
        final String installerPackageName = session.getInstallSource().mInstallerPackageName;
        if (!isApexUpdateAllowed(packageName, installerPackageName)) {
            throw new PackageManagerException(
                    PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                    "Update of APEX package " + packageName + " is not allowed for "
                            + installerPackageName);
        }
    }

    /**
     * Fails this rebootless APEX session if the same package name found in any staged sessions.
     */
    @VisibleForTesting
    void checkRebootlessApex(PackageInstallerSession session)
            throws PackageManagerException {
        if (session.isStaged() || !session.isApexSession()) {
            return;
        }
        String packageName = session.getPackageName();
        if (packageName == null) {
            throw new PackageManagerException(
                    PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                    "Invalid session " + session.sessionId + " with package name null");
        }
        for (StagingManager.StagedSession stagedSession : mStagedSessions) {
            if (stagedSession.isDestroyed() || stagedSession.isInTerminalState()) {
                continue;
            }
            if (stagedSession.sessionContains(s -> packageName.equals(s.getPackageName()))) {
                // Staged-sessions take priority over rebootless APEX
                throw new PackageManagerException(
                        PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                        "Staged session " + stagedSession.sessionId() + " already contains "
                                + packageName);
            }
        }
    }

    /**
     * Checks if multiple staged-sessions are supported. It is supported only when the system
     * supports checkpoint.
     */
    private void checkActiveSessions() throws PackageManagerException {
        try {
            checkActiveSessions(InstallLocationUtils.getStorageManager().supportsCheckpoint());
        } catch (RemoteException e) {
            throw new PackageManagerException(PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                    "Can't query fs-checkpoint status : " + e);
        }
    }

    @VisibleForTesting
    void checkActiveSessions(boolean supportsCheckpoint) throws PackageManagerException {
        int activeSessions = 0;
        for (StagingManager.StagedSession stagedSession : mStagedSessions) {
            if (stagedSession.isDestroyed() || stagedSession.isInTerminalState()) {
                continue;
            }
            ++activeSessions;
        }
        if (!supportsCheckpoint && activeSessions > 1) {
            throw new PackageManagerException(
                    PackageManager.INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS,
                    "Cannot stage multiple sessions without checkpoint support");
        }
    }

    /**
     * Fails non-rollback sessions if any rollback session exists. A rollback session might cause
     * downgrade of SDK extension which in turn will result in dependency violation of other
     * non-rollback sessions.
     */
    @VisibleForTesting
    void checkRollbacks(StagingManager.StagedSession session)
            throws PackageManagerException {
        if (session.isDestroyed() || session.isInTerminalState()) {
            return;
        }
        for (StagingManager.StagedSession stagedSession : mStagedSessions) {
            if (stagedSession.isDestroyed() || stagedSession.isInTerminalState()) {
                continue;
            }
            if (isRollback(session) && !isRollback(stagedSession)) {
                // If the new session is a rollback, then it gets priority. The existing
                // session is failed to reduce risk and avoid an SDK extension dependency
                // violation.
                if (!ensureActiveApexSessionIsAborted(stagedSession)) {
                    Slog.e(TAG, "Failed to abort apex session " + stagedSession.sessionId());
                    // Safe to ignore active apex session abort failure since session
                    // will be marked failed on next step and staging directory for session
                    // will be deleted.
                }
                stagedSession.setSessionFailed(
                        PackageManager.INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS,
                        "Session was failed by rollback session: " + session.sessionId());
                Slog.i(TAG, "Session " + stagedSession.sessionId() + " is marked failed due to "
                        + "rollback session: " + session.sessionId());
            } else if (!isRollback(session) && isRollback(stagedSession)) {
                throw new PackageManagerException(
                        PackageManager.INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS,
                        "Session was failed by rollback session: " + stagedSession.sessionId());

            }
        }
    }

    /**
     * Fails the session that is committed later when overlapping packages are detected.
     *
     * @param parent The parent session.
     * @param child The child session whose package name will be checked.
     *              This will equal to {@code parent} for a single-package session.
     */
    @VisibleForTesting
    void checkOverlaps(StagingManager.StagedSession parent,
            StagingManager.StagedSession child) throws PackageManagerException {
        if (parent.isDestroyed() || parent.isInTerminalState()) {
            return;
        }
        final String packageName = child.getPackageName();
        if (packageName == null) {
            throw new PackageManagerException(
                    PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                    "Cannot stage session " + child.sessionId() + " with package name null");
        }
        for (StagingManager.StagedSession stagedSession : mStagedSessions) {
            if (stagedSession.isDestroyed() || stagedSession.isInTerminalState()
                    || stagedSession.sessionId() == parent.sessionId()) {
                continue;
            }
            if (stagedSession.sessionContains(s -> packageName.equals(s.getPackageName()))) {
                if (stagedSession.getCommittedMillis() < parent.getCommittedMillis()) {
                    // Fail the session committed later when there are overlapping packages
                    throw new PackageManagerException(
                            PackageManager.INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS,
                            "Package: " + packageName + " in session: "
                                    + child.sessionId()
                                    + " has been staged already by session: "
                                    + stagedSession.sessionId());
                } else {
                    stagedSession.setSessionFailed(
                            PackageManager.INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS,
                            "Package: " + packageName + " in session: "
                                    + stagedSession.sessionId()
                                    + " has been staged already by session: "
                                    + child.sessionId());
                }
            }
        }
    }
}
