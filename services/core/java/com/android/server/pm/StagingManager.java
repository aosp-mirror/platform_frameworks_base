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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.PackageParser.SigningDetails;
import android.content.pm.PackageParser.SigningDetails.SignatureSchemeVersion;
import android.content.pm.parsing.PackageInfoWithoutStateUtils;
import android.content.rollback.IRollbackManager;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageHelper;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.rollback.WatchdogRollbackLogger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * This class handles staged install sessions, i.e. install sessions that require packages to
 * be installed only after a reboot.
 */
public class StagingManager {

    private static final String TAG = "StagingManager";

    private final PackageInstallerService mPi;
    private final ApexManager mApexManager;
    private final PowerManager mPowerManager;
    private final Context mContext;
    private final PreRebootVerificationHandler mPreRebootVerificationHandler;
    private final Supplier<PackageParser2> mPackageParserSupplier;

    private final File mFailureReasonFile = new File("/metadata/staged-install/failure_reason.txt");
    private String mFailureReason;

    @GuardedBy("mStagedSessions")
    private final SparseArray<PackageInstallerSession> mStagedSessions = new SparseArray<>();

    @GuardedBy("mStagedSessions")
    private final SparseIntArray mSessionRollbackIds = new SparseIntArray();

    @GuardedBy("mFailedPackageNames")
    private final List<String> mFailedPackageNames = new ArrayList<>();
    private String mNativeFailureReason;

    @GuardedBy("mSuccessfulStagedSessionIds")
    private final List<Integer> mSuccessfulStagedSessionIds = new ArrayList<>();

    StagingManager(PackageInstallerService pi, Context context,
            Supplier<PackageParser2> packageParserSupplier) {
        mPi = pi;
        mContext = context;
        mPackageParserSupplier = packageParserSupplier;

        mApexManager = ApexManager.getInstance();
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mPreRebootVerificationHandler = new PreRebootVerificationHandler(
                BackgroundThread.get().getLooper());

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

    private void updateStoredSession(@NonNull PackageInstallerSession sessionInfo) {
        synchronized (mStagedSessions) {
            PackageInstallerSession storedSession = mStagedSessions.get(sessionInfo.sessionId);
            // storedSession might be null if a call to abortSession was made before the session
            // is updated.
            if (storedSession != null) {
                mStagedSessions.put(sessionInfo.sessionId, sessionInfo);
            }
        }
    }

    private void markBootCompleted() {
        mApexManager.markBootCompleted();
    }

    /**
     * Validates the signature used to sign the container of the new apex package
     *
     * @param newApexPkg The new apex package that is being installed
     * @throws PackageManagerException
     */
    private void validateApexSignature(PackageInfo newApexPkg)
            throws PackageManagerException {
        // Get signing details of the new package
        final String apexPath = newApexPkg.applicationInfo.sourceDir;
        final String packageName = newApexPkg.packageName;
        int minSignatureScheme = ApkSignatureVerifier.getMinimumSignatureSchemeVersionForTargetSdk(
                newApexPkg.applicationInfo.targetSdkVersion);

        final SigningDetails newSigningDetails;
        try {
            newSigningDetails = ApkSignatureVerifier.verify(apexPath, minSignatureScheme);
        } catch (PackageParserException e) {
            throw new PackageManagerException(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                    "Failed to parse APEX package " + apexPath, e);
        }

        // Get signing details of the existing package
        final PackageInfo existingApexPkg = mApexManager.getPackageInfo(packageName,
                ApexManager.MATCH_ACTIVE_PACKAGE);
        if (existingApexPkg == null) {
            // This should never happen, because submitSessionToApexService ensures that no new
            // apexes were installed.
            throw new IllegalStateException("Unknown apex package " + packageName);
        }

        final SigningDetails existingSigningDetails;
        try {
            existingSigningDetails = ApkSignatureVerifier.verify(
                existingApexPkg.applicationInfo.sourceDir, SignatureSchemeVersion.JAR);
        } catch (PackageParserException e) {
            throw new PackageManagerException(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                    "Failed to parse APEX package " + existingApexPkg.applicationInfo.sourceDir, e);
        }

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

    private List<PackageInfo> submitSessionToApexService(
            @NonNull PackageInstallerSession session) throws PackageManagerException {
        final IntArray childSessionIds = new IntArray();
        if (session.isMultiPackage()) {
            for (int id : session.getChildSessionIds()) {
                if (isApexSession(getStagedSession(id))) {
                    childSessionIds.add(id);
                }
            }
        }
        ApexSessionParams apexSessionParams = new ApexSessionParams();
        apexSessionParams.sessionId = session.sessionId;
        apexSessionParams.childSessionIds = childSessionIds.toArray();
        if (session.params.installReason == PackageManager.INSTALL_REASON_ROLLBACK) {
            apexSessionParams.isRollback = true;
            apexSessionParams.rollbackId = retrieveRollbackIdForCommitSession(session.sessionId);
        } else {
            synchronized (mStagedSessions) {
                int rollbackId = mSessionRollbackIds.get(session.sessionId, -1);
                if (rollbackId != -1) {
                    apexSessionParams.hasRollbackEnabled = true;
                    apexSessionParams.rollbackId = rollbackId;
                }
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
            } catch (PackageParserException e) {
                throw new PackageManagerException(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                        "Failed to parse APEX package " + apexInfo.modulePath, e);
            }
            final PackageInfo activePackage = mApexManager.getPackageInfo(packageInfo.packageName,
                    ApexManager.MATCH_ACTIVE_PACKAGE);
            if (activePackage == null) {
                Slog.w(TAG, "Attempting to install new APEX package " + packageInfo.packageName);
                throw new PackageManagerException(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                        "It is forbidden to install new APEX packages.");
            }
            checkRequiredVersionCode(session, activePackage);
            checkDowngrade(session, activePackage, packageInfo);
            result.add(packageInfo);
            apexPackageNames.add(packageInfo.packageName);
        }
        Slog.d(TAG, "Session " + session.sessionId + " has following APEX packages: "
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

    private void checkRequiredVersionCode(final PackageInstallerSession session,
            final PackageInfo activePackage) throws PackageManagerException {
        if (session.params.requiredInstalledVersionCode == PackageManager.VERSION_CODE_HIGHEST) {
            return;
        }
        final long activeVersion = activePackage.applicationInfo.longVersionCode;
        if (activeVersion != session.params.requiredInstalledVersionCode) {
            if (!mApexManager.abortStagedSession(session.sessionId)) {
                Slog.e(TAG, "Failed to abort apex session " + session.sessionId);
            }
            throw new PackageManagerException(
                    SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                    "Installed version of APEX package " + activePackage.packageName
                            + " does not match required. Active version: " + activeVersion
                            + " required: " + session.params.requiredInstalledVersionCode);
        }
    }

    private void checkDowngrade(final PackageInstallerSession session,
            final PackageInfo activePackage, final PackageInfo newPackage)
            throws PackageManagerException {
        final long activeVersion = activePackage.applicationInfo.longVersionCode;
        final long newVersionCode = newPackage.applicationInfo.longVersionCode;
        boolean isAppDebuggable = (activePackage.applicationInfo.flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        final boolean allowsDowngrade = PackageManagerServiceUtils.isDowngradePermitted(
                session.params.installFlags, isAppDebuggable);
        if (activeVersion > newVersionCode && !allowsDowngrade) {
            if (!mApexManager.abortStagedSession(session.sessionId)) {
                Slog.e(TAG, "Failed to abort apex session " + session.sessionId);
            }
            throw new PackageManagerException(
                    SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                    "Downgrade of APEX package " + newPackage.packageName
                            + " is not allowed. Active version: " + activeVersion
                            + " attempted: " + newVersionCode);
        }
    }

    private static boolean isApexSession(@NonNull PackageInstallerSession session) {
        return (session.params.installFlags & PackageManager.INSTALL_APEX) != 0;
    }

    private boolean sessionContains(@NonNull PackageInstallerSession session,
                                    Predicate<PackageInstallerSession> filter) {
        if (!session.isMultiPackage()) {
            return filter.test(session);
        }
        synchronized (mStagedSessions) {
            final int[] childSessionIds = session.getChildSessionIds();
            for (int id : childSessionIds) {
                // Retrieve cached sessions matching ids.
                final PackageInstallerSession s = mStagedSessions.get(id);
                // Filter only the ones containing APEX.
                if (filter.test(s)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean sessionContainsApex(@NonNull PackageInstallerSession session) {
        return sessionContains(session, (s) -> isApexSession(s));
    }

    private boolean sessionContainsApk(@NonNull PackageInstallerSession session) {
        return sessionContains(session, (s) -> !isApexSession(s));
    }

    // Reverts apex sessions and user data (if checkpoint is supported). Also reboots the device.
    private void abortCheckpoint(int sessionId, String errorMsg) {
        String failureReason = "Failed to install sessionId: " + sessionId + " Error: " + errorMsg;
        Slog.e(TAG, failureReason);
        try {
            if (supportsCheckpoint() && needsCheckpoint()) {
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
                        "StagingManager initiated", false /*retry*/);
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

    private boolean supportsCheckpoint() throws RemoteException {
        return PackageHelper.getStorageManager().supportsCheckpoint();
    }

    private boolean needsCheckpoint() throws RemoteException {
        return PackageHelper.getStorageManager().needsCheckpoint();
    }

    /**
     * Utility function for extracting apex sessions out of multi-package/single session.
     */
    private List<PackageInstallerSession> extractApexSessions(PackageInstallerSession session) {
        List<PackageInstallerSession> apexSessions = new ArrayList<>();
        if (session.isMultiPackage()) {
            List<PackageInstallerSession> childrenSessions = new ArrayList<>();
            synchronized (mStagedSessions) {
                for (int childSessionId : session.getChildSessionIds()) {
                    PackageInstallerSession childSession = mStagedSessions.get(childSessionId);
                    if (childSession != null) {
                        childrenSessions.add(childSession);
                    }
                }
            }
            for (int i = 0, size = childrenSessions.size(); i < size; i++) {
                final PackageInstallerSession childSession = childrenSessions.get(i);
                if (sessionContainsApex(childSession)) {
                    apexSessions.add(childSession);
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
    private void checkInstallationOfApkInApexSuccessful(PackageInstallerSession session)
            throws PackageManagerException {
        final List<PackageInstallerSession> apexSessions = extractApexSessions(session);
        if (apexSessions.isEmpty()) {
            return;
        }

        for (PackageInstallerSession apexSession : apexSessions) {
            String packageName = apexSession.getPackageName();
            if (!mApexManager.isApkInApexInstallSuccess(packageName)) {
                throw new PackageManagerException(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "Failed to install apk-in-apex of " + packageName);
            }
        }
    }

    /**
     * Perform snapshot and restore as required both for APEXes themselves and for apks in APEX.
     * Apks inside apex are not installed using apk-install flow. They are scanned from the system
     * directory directly by PackageManager, as such, RollbackManager need to handle their data
     * separately here.
     */
    private void snapshotAndRestoreForApexSession(PackageInstallerSession session) {
        boolean doSnapshotOrRestore =
                (session.params.installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0
                || session.params.installReason == PackageManager.INSTALL_REASON_ROLLBACK;
        if (!doSnapshotOrRestore) {
            return;
        }

        // Find all the apex sessions that needs processing
        final List<PackageInstallerSession> apexSessions = extractApexSessions(session);
        if (apexSessions.isEmpty()) {
            return;
        }

        final UserManagerInternal um = LocalServices.getService(UserManagerInternal.class);
        final int[] allUsers = um.getUserIds();
        IRollbackManager rm = IRollbackManager.Stub.asInterface(
                ServiceManager.getService(Context.ROLLBACK_SERVICE));

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
            String packageName, int[] allUsers, IRollbackManager rm) {
        try {
            // appId, ceDataInode, and seInfo are not needed for APEXes
            rm.snapshotAndRestoreUserData(packageName, allUsers, 0, 0,
                    null, 0 /*token*/);
        } catch (RemoteException re) {
            Slog.e(TAG, "Error snapshotting/restoring user data: " + re);
        }
    }

    private void snapshotAndRestoreApkInApexUserData(
            String packageName, int[] allUsers, IRollbackManager rm) {
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
            appId = ps.appId;
            ceDataInode = ps.getCeDataInode(UserHandle.USER_SYSTEM);
            // NOTE: We ignore the user specified in the InstallParam because we know this is
            // an update, and hence need to restore data for all installed users.
            final int[] installedUsers = ps.queryInstalledUsers(allUsers, true);

            final String seInfo = AndroidPackageUtils.getSeInfo(pkg, ps);
            try {
                rm.snapshotAndRestoreUserData(packageName, installedUsers, appId, ceDataInode,
                        seInfo, 0 /*token*/);
            } catch (RemoteException re) {
                Slog.e(TAG, "Error snapshotting/restoring user data: " + re);
            }
        }
    }

    /**
     *  Prepares for the logging of apexd reverts by storing the native failure reason if necessary,
     *  and adding the package name of the session which apexd reverted to the list of reverted
     *  session package names.
     *  Logging needs to wait until the ACTION_BOOT_COMPLETED broadcast is sent.
     */
    private void prepareForLoggingApexdRevert(@NonNull PackageInstallerSession session,
            @NonNull String nativeFailureReason) {
        synchronized (mFailedPackageNames) {
            mNativeFailureReason = nativeFailureReason;
            if (session.getPackageName() != null) {
                mFailedPackageNames.add(session.getPackageName());
            }
        }
    }

    private void resumeSession(@NonNull PackageInstallerSession session)
            throws PackageManagerException {
        Slog.d(TAG, "Resuming session " + session.sessionId);

        final boolean hasApex = sessionContainsApex(session);
        ApexSessionInfo apexSessionInfo = null;
        if (hasApex) {
            // Check with apexservice whether the apex packages have been activated.
            apexSessionInfo = mApexManager.getStagedSessionInfo(session.sessionId);

            // Prepare for logging a native crash during boot, if one occurred.
            if (apexSessionInfo != null && !TextUtils.isEmpty(
                    apexSessionInfo.crashingNativeProcess)) {
                prepareForLoggingApexdRevert(session, apexSessionInfo.crashingNativeProcess);
            }

            if (apexSessionInfo != null && apexSessionInfo.isVerified) {
                // Session has been previously submitted to apexd, but didn't complete all the
                // pre-reboot verification, perhaps because the device rebooted in the meantime.
                // Greedily re-trigger the pre-reboot verification. We want to avoid marking it as
                // failed when not in checkpoint mode, hence it is being processed separately.
                Slog.d(TAG, "Found pending staged session " + session.sessionId + " still to "
                        + "be verified, resuming pre-reboot verification");
                mPreRebootVerificationHandler.startPreRebootVerification(session.sessionId);
                return;
            }
        }

        // Before we resume session, we check if revert is needed or not. Typically, we enter file-
        // system checkpoint mode when we reboot first time in order to install staged sessions. We
        // want to install staged sessions in this mode as rebooting now will revert user data. If
        // something goes wrong, then we reboot again to enter fs-rollback mode. Rebooting now will
        // have no effect on user data, so mark the sessions as failed instead.
        try {
            // If checkpoint is supported, then we only resume sessions if we are in checkpointing
            // mode. If not, we fail all sessions.
            if (supportsCheckpoint() && !needsCheckpoint()) {
                String revertMsg = "Reverting back to safe state. Marking "
                        + session.sessionId + " as failed.";
                final String reasonForRevert = getReasonForRevert();
                if (!TextUtils.isEmpty(reasonForRevert)) {
                    revertMsg += " Reason for revert: " + reasonForRevert;
                }
                Slog.d(TAG, revertMsg);
                session.setStagedSessionFailed(SessionInfo.STAGED_SESSION_UNKNOWN, revertMsg);
                return;
            }
        } catch (RemoteException e) {
            // Cannot continue staged install without knowing if fs-checkpoint is supported
            Slog.e(TAG, "Checkpoint support unknown. Aborting staged install for session "
                    + session.sessionId, e);
            // TODO: Mark all staged sessions together and reboot only once
            session.setStagedSessionFailed(SessionInfo.STAGED_SESSION_UNKNOWN,
                    "Checkpoint support unknown. Aborting staged install.");
            if (hasApex) {
                mApexManager.revertActiveSessions();
            }
            mPowerManager.reboot("Checkpoint support unknown");
            return;
        }

        // Check if apex packages in the session failed to activate
        if (hasApex) {
            if (apexSessionInfo == null) {
                final String errorMsg = "apexd did not know anything about a staged session "
                        + "supposed to be activated";
                throw new PackageManagerException(
                        SessionInfo.STAGED_SESSION_ACTIVATION_FAILED, errorMsg);
            }
            if (isApexSessionFailed(apexSessionInfo)) {
                String errorMsg = "APEX activation failed. Check logcat messages from apexd "
                        + "for more information.";
                if (!TextUtils.isEmpty(mNativeFailureReason)) {
                    errorMsg = "Session reverted due to crashing native process: "
                            + mNativeFailureReason;
                }
                throw new PackageManagerException(
                        SessionInfo.STAGED_SESSION_ACTIVATION_FAILED, errorMsg);
            }
            if (!apexSessionInfo.isActivated && !apexSessionInfo.isSuccess) {
                // Apexd did not apply the session for some unknown reason. There is no
                // guarantee that apexd will install it next time. Safer to proactively mark
                // it as failed.
                final String errorMsg = "Staged session " + session.sessionId + "at boot "
                        + "didn't activate nor fail. Marking it as failed anyway.";
                throw new PackageManagerException(
                        SessionInfo.STAGED_SESSION_ACTIVATION_FAILED, errorMsg);
            }
        }
        // Handle apk and apk-in-apex installation
        if (hasApex) {
            checkInstallationOfApkInApexSuccessful(session);
            snapshotAndRestoreForApexSession(session);
            Slog.i(TAG, "APEX packages in session " + session.sessionId
                    + " were successfully activated. Proceeding with APK packages, if any");
        }
        // The APEX part of the session is activated, proceed with the installation of APKs.
        Slog.d(TAG, "Installing APK packages in session " + session.sessionId);
        installApksInSession(session);

        Slog.d(TAG, "Marking session " + session.sessionId + " as applied");
        session.setStagedSessionApplied();
        if (hasApex) {
            try {
                if (supportsCheckpoint()) {
                    // Store the session ID, which will be marked as successful by ApexManager
                    // upon boot completion.
                    synchronized (mSuccessfulStagedSessionIds) {
                        mSuccessfulStagedSessionIds.add(session.sessionId);
                    }
                } else {
                    // Mark sessions as successful immediately on non-checkpointing devices.
                    mApexManager.markStagedSessionSuccessful(session.sessionId);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Checkpoint support unknown, marking session as successful "
                        + "immediately.");
                mApexManager.markStagedSessionSuccessful(session.sessionId);
            }
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

    private List<String> findAPKsInDir(File stageDir) {
        List<String> ret = new ArrayList<>();
        if (stageDir != null && stageDir.exists()) {
            for (File file : stageDir.listFiles()) {
                if (file.getAbsolutePath().toLowerCase().endsWith(".apk")) {
                    ret.add(file.getAbsolutePath());
                }
            }
        }
        return ret;
    }

    void onInstallationFailure(PackageInstallerSession session, PackageManagerException e) {
        session.setStagedSessionFailed(e.error, e.getMessage());
        abortCheckpoint(session.sessionId, e.getMessage());

        // If checkpoint is not supported, we have to handle failure for one staged session.
        if (!sessionContainsApex(session)) {
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

    @NonNull
    private PackageInstallerSession createAndWriteApkSession(
            @NonNull PackageInstallerSession originalSession, boolean preReboot)
            throws PackageManagerException {
        final int errorCode = preReboot ? SessionInfo.STAGED_SESSION_VERIFICATION_FAILED
                : SessionInfo.STAGED_SESSION_ACTIVATION_FAILED;
        if (originalSession.stageDir == null) {
            Slog.wtf(TAG, "Attempting to install a staged APK session with no staging dir");
            throw new PackageManagerException(errorCode,
                    "Attempting to install a staged APK session with no staging dir");
        }
        List<String> apkFilePaths = findAPKsInDir(originalSession.stageDir);
        if (apkFilePaths.isEmpty()) {
            Slog.w(TAG, "Can't find staged APK in " + originalSession.stageDir.getAbsolutePath());
            throw new PackageManagerException(errorCode,
                    "Can't find staged APK in " + originalSession.stageDir.getAbsolutePath());
        }

        PackageInstaller.SessionParams params = originalSession.params.copy();
        params.isStaged = false;
        params.installFlags |= PackageManager.INSTALL_STAGED;
        if (preReboot) {
            params.installFlags &= ~PackageManager.INSTALL_ENABLE_ROLLBACK;
            params.installFlags |= PackageManager.INSTALL_DRY_RUN;
        } else {
            params.installFlags |= PackageManager.INSTALL_DISABLE_VERIFICATION;
        }
        try {
            int apkSessionId = mPi.createSession(
                    params, originalSession.getInstallerPackageName(),
                    originalSession.userId);
            PackageInstallerSession apkSession = mPi.getSession(apkSessionId);
            apkSession.open();
            for (int i = 0, size = apkFilePaths.size(); i < size; i++) {
                final String apkFilePath = apkFilePaths.get(i);
                File apkFile = new File(apkFilePath);
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(apkFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);
                long sizeBytes = (pfd == null) ? -1 : pfd.getStatSize();
                if (sizeBytes < 0) {
                    Slog.e(TAG, "Unable to get size of: " + apkFilePath);
                    throw new PackageManagerException(errorCode,
                            "Unable to get size of: " + apkFilePath);
                }
                apkSession.write(apkFile.getName(), 0, sizeBytes, pfd);
            }
            return apkSession;
        } catch (IOException | ParcelableException e) {
            Slog.e(TAG, "Failure to install APK staged session " + originalSession.sessionId, e);
            throw new PackageManagerException(errorCode, "Failed to create/write APK session", e);
        }
    }

    /**
     * Extract apks in the given session into a new session. Returns {@code null} if there is no
     * apks in the given session. Only parent session is returned for multi-package session.
     */
    @Nullable
    private PackageInstallerSession extractApksInSession(PackageInstallerSession session,
            boolean preReboot) throws PackageManagerException {
        final int errorCode = preReboot ? SessionInfo.STAGED_SESSION_VERIFICATION_FAILED
                : SessionInfo.STAGED_SESSION_ACTIVATION_FAILED;
        if (!session.isMultiPackage() && !isApexSession(session)) {
            return createAndWriteApkSession(session, preReboot);
        } else if (session.isMultiPackage()) {
            // For multi-package staged sessions containing APKs, we identify which child sessions
            // contain an APK, and with those then create a new multi-package group of sessions,
            // carrying over all the session parameters and unmarking them as staged. On commit the
            // sessions will be installed atomically.
            final List<PackageInstallerSession> childSessions = new ArrayList<>();
            synchronized (mStagedSessions) {
                final int[] childSessionIds = session.getChildSessionIds();
                for (int id : childSessionIds) {
                    final PackageInstallerSession s = mStagedSessions.get(id);
                    if (!isApexSession(s)) {
                        childSessions.add(s);
                    }
                }
            }
            if (childSessions.isEmpty()) {
                // APEX-only multi-package staged session, nothing to do.
                return null;
            }
            final PackageInstaller.SessionParams params = session.params.copy();
            params.isStaged = false;
            if (preReboot) {
                params.installFlags &= ~PackageManager.INSTALL_ENABLE_ROLLBACK;
            }
            final int apkParentSessionId = mPi.createSession(
                    params, session.getInstallerPackageName(),
                    session.userId);
            final PackageInstallerSession apkParentSession = mPi.getSession(apkParentSessionId);
            try {
                apkParentSession.open();
            } catch (IOException e) {
                Slog.e(TAG, "Unable to prepare multi-package session for staged session "
                        + session.sessionId);
                throw new PackageManagerException(errorCode,
                        "Unable to prepare multi-package session for staged session");
            }

            for (int i = 0, size = childSessions.size(); i < size; i++) {
                final PackageInstallerSession apkChildSession = createAndWriteApkSession(
                        childSessions.get(i), preReboot);
                try {
                    apkParentSession.addChildSessionId(apkChildSession.sessionId);
                } catch (IllegalStateException e) {
                    Slog.e(TAG, "Failed to add a child session for installing the APK files", e);
                    throw new PackageManagerException(errorCode,
                            "Failed to add a child session " + apkChildSession.sessionId);
                }
            }
            return apkParentSession;
        }
        return null;
    }

    private void verifyApksInSession(PackageInstallerSession session)
            throws PackageManagerException {

        final PackageInstallerSession apksToVerify = extractApksInSession(
                session,  /* preReboot */ true);
        if (apksToVerify == null) {
            return;
        }

        final LocalIntentReceiverAsync receiver = new LocalIntentReceiverAsync(
                (Intent result) -> {
                    int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE);
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        final String errorMessage = result.getStringExtra(
                                PackageInstaller.EXTRA_STATUS_MESSAGE);
                        Slog.e(TAG, "Failure to verify APK staged session "
                                + session.sessionId + " [" + errorMessage + "]");
                        session.setStagedSessionFailed(
                                SessionInfo.STAGED_SESSION_VERIFICATION_FAILED, errorMessage);
                        mPreRebootVerificationHandler.onPreRebootVerificationComplete(
                                session.sessionId);
                        return;
                    }
                    mPreRebootVerificationHandler.notifyPreRebootVerification_Apk_Complete(
                            session.sessionId);
                });

        apksToVerify.commit(receiver.getIntentSender(), false);
    }

    private void installApksInSession(@NonNull PackageInstallerSession session)
            throws PackageManagerException {

        final PackageInstallerSession apksToInstall = extractApksInSession(
                session, /* preReboot */ false);
        if (apksToInstall == null) {
            return;
        }

        if ((apksToInstall.params.installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0) {
            // If rollback is available for this session, notify the rollback
            // manager of the apk session so it can properly enable rollback.
            final IRollbackManager rm = IRollbackManager.Stub.asInterface(
                    ServiceManager.getService(Context.ROLLBACK_SERVICE));
            try {
                rm.notifyStagedApkSession(session.sessionId, apksToInstall.sessionId);
            } catch (RemoteException re) {
                Slog.e(TAG, "Failed to notifyStagedApkSession for session: "
                        + session.sessionId, re);
            }
        }

        final LocalIntentReceiverSync receiver = new LocalIntentReceiverSync();
        apksToInstall.commit(receiver.getIntentSender(), false);
        final Intent result = receiver.getResult();
        final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status != PackageInstaller.STATUS_SUCCESS) {
            final String errorMessage = result.getStringExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE);
            Slog.e(TAG, "Failure to install APK staged session "
                    + session.sessionId + " [" + errorMessage + "]");
            throw new PackageManagerException(
                    SessionInfo.STAGED_SESSION_ACTIVATION_FAILED, errorMessage);
        }
    }

    void commitSession(@NonNull PackageInstallerSession session) {
        updateStoredSession(session);
        mPreRebootVerificationHandler.startPreRebootVerification(session.sessionId);
    }

    private int parentOrOwnSessionId(PackageInstallerSession session) {
        return session.hasParentSessionId() ? session.getParentSessionId() : session.sessionId;
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
    void checkNonOverlappingWithStagedSessions(@NonNull PackageInstallerSession session)
            throws PackageManagerException {
        if (session.isMultiPackage()) {
            // We cannot say a parent session overlaps until we process its children
            return;
        }
        if (session.getPackageName() == null) {
            throw new PackageManagerException(PackageManager.INSTALL_FAILED_INVALID_APK,
                    "Cannot stage session " + session.sessionId + " with package name null");
        }

        boolean supportsCheckpoint = ((StorageManager) mContext.getSystemService(
                Context.STORAGE_SERVICE)).isCheckpointSupported();

        synchronized (mStagedSessions) {
            for (int i = 0; i < mStagedSessions.size(); i++) {
                final PackageInstallerSession stagedSession = mStagedSessions.valueAt(i);
                if (!stagedSession.isCommitted() || stagedSession.isStagedAndInTerminalState()
                        || stagedSession.isDestroyed()) {
                    continue;
                }
                if (stagedSession.isMultiPackage()) {
                    // This active parent staged session is useless as it doesn't have a package
                    // name and the session we are checking is not a parent session either.
                    continue;
                }
                // Check if stagedSession has an active parent session or not
                if (stagedSession.hasParentSessionId()) {
                    int parentId = stagedSession.getParentSessionId();
                    PackageInstallerSession parentSession = mStagedSessions.get(parentId);
                    if (parentSession == null || parentSession.isStagedAndInTerminalState()
                            || parentSession.isDestroyed()) {
                        // Parent session has been abandoned or terminated already
                        continue;
                    }
                }

                // From here on, stagedSession is a non-parent active staged session

                // Check if session is one of the active sessions
                if (session.sessionId == stagedSession.sessionId) {
                    Slog.w(TAG, "Session " + session.sessionId + " is already staged");
                    continue;
                }

                // If session is not among the active sessions, then it cannot have same package
                // name as any of the active sessions.
                if (session.getPackageName().equals(stagedSession.getPackageName())) {
                    throw new PackageManagerException(
                            PackageManager.INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS,
                            "Package: " + session.getPackageName() + " in session: "
                                    + session.sessionId + " has been staged already by session: "
                                    + stagedSession.sessionId, null);
                }

                // Staging multiple root sessions is not allowed if device doesn't support
                // checkpoint. If session and stagedSession do not have common ancestor, they are
                // from two different root sessions.
                if (!supportsCheckpoint
                        && parentOrOwnSessionId(session) != parentOrOwnSessionId(stagedSession)) {
                    throw new PackageManagerException(
                            PackageManager.INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS,
                            "Cannot stage multiple sessions without checkpoint support", null);
                }
            }
        }
    }

    void createSession(@NonNull PackageInstallerSession sessionInfo) {
        synchronized (mStagedSessions) {
            mStagedSessions.append(sessionInfo.sessionId, sessionInfo);
        }
    }

    void abortSession(@NonNull PackageInstallerSession session) {
        synchronized (mStagedSessions) {
            mStagedSessions.remove(session.sessionId);
            mSessionRollbackIds.delete(session.sessionId);
        }
    }

    /**
     * <p>Abort committed staged session
     *
     * <p>This method must be called while holding {@link PackageInstallerSession.mLock}.
     *
     * <p>The method returns {@code false} to indicate it is not safe to clean up the session from
     * system yet. When it is safe, the method returns {@code true}.
     *
     * <p> When it is safe to clean up, {@link StagingManager} will call
     * {@link PackageInstallerSession#abandon()} on the session again.
     *
     * @return {@code true} if it is safe to cleanup the session resources, otherwise {@code false}.
     */
    boolean abortCommittedSessionLocked(@NonNull PackageInstallerSession session) {
        int sessionId = session.sessionId;
        if (session.isStagedSessionApplied()) {
            Slog.w(TAG, "Cannot abort applied session : " + sessionId);
            return false;
        }
        if (!session.isDestroyed()) {
            throw new IllegalStateException("Committed session must be destroyed before aborting it"
                    + " from StagingManager");
        }
        if (getStagedSession(sessionId) == null) {
            Slog.w(TAG, "Session " + sessionId + " has been abandoned already");
            return false;
        }

        // If pre-reboot verification is running, then return false. StagingManager will call
        // abandon again when pre-reboot verification ends.
        if (mPreRebootVerificationHandler.isVerificationRunning(sessionId)) {
            Slog.w(TAG, "Session " + sessionId + " aborted before pre-reboot "
                    + "verification completed.");
            return false;
        }

        // A session could be marked ready once its pre-reboot verification ends
        if (session.isStagedSessionReady()) {
            if (sessionContainsApex(session)) {
                try {
                    ApexSessionInfo apexSession =
                            mApexManager.getStagedSessionInfo(session.sessionId);
                    if (apexSession == null || isApexSessionFinalized(apexSession)) {
                        Slog.w(TAG,
                                "Cannot abort session " + session.sessionId
                                        + " because it is not active.");
                    } else {
                        mApexManager.abortStagedSession(session.sessionId);
                    }
                } catch (Exception e) {
                    // Failed to contact apexd service. The apex might still be staged. We can still
                    // safely cleanup the staged session since pre-reboot verification is complete.
                    // Also, cleaning up the stageDir prevents the apex from being activated.
                    Slog.w(TAG, "Could not contact apexd to abort staged session " + sessionId);
                }
            }
        }

        // Session was successfully aborted from apexd (if required) and pre-reboot verification
        // is also complete. It is now safe to clean up the session from system.
        abortSession(session);
        return true;
    }

    /**
     * Ensure that there is no active apex session staged in apexd for the given session.
     *
     * @return returns true if it is ensured that there is no active apex session, otherwise false
     */
    private boolean ensureActiveApexSessionIsAborted(PackageInstallerSession session) {
        if (!sessionContainsApex(session)) {
            return true;
        }
        final ApexSessionInfo apexSession = mApexManager.getStagedSessionInfo(session.sessionId);
        if (apexSession == null || isApexSessionFinalized(apexSession)) {
            return true;
        }
        try {
            return mApexManager.abortStagedSession(session.sessionId);
        } catch (PackageManagerException ignore) {
            return false;
        }
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

    @GuardedBy("mStagedSessions")
    private boolean isMultiPackageSessionComplete(@NonNull PackageInstallerSession session) {
        // This method assumes that the argument is either a parent session of a multi-package
        // i.e. isMultiPackage() returns true, or that it is a child session, i.e.
        // hasParentSessionId() returns true.
        if (session.isMultiPackage()) {
            // Parent session of a multi-package group. Check that we restored all the children.
            for (int childSession : session.getChildSessionIds()) {
                if (mStagedSessions.get(childSession) == null) {
                    return false;
                }
            }
            return true;
        }
        if (session.hasParentSessionId()) {
            PackageInstallerSession parent = mStagedSessions.get(session.getParentSessionId());
            if (parent == null) {
                return false;
            }
            return isMultiPackageSessionComplete(parent);
        }
        Slog.wtf(TAG, "Attempting to restore an invalid multi-package session.");
        return false;
    }

    void restoreSession(@NonNull PackageInstallerSession session, boolean isDeviceUpgrading) {
        PackageInstallerSession sessionToResume = session;
        synchronized (mStagedSessions) {
            mStagedSessions.append(session.sessionId, session);
            // For multi-package sessions, we don't know in which order they will be restored. We
            // need to wait until we have restored all the session in a group before restoring them.
            if (session.isMultiPackage() || session.hasParentSessionId()) {
                if (!isMultiPackageSessionComplete(session)) {
                    // Still haven't recovered all sessions of the group, return.
                    return;
                }
                // Group recovered, find the parent if necessary and resume the installation.
                if (session.hasParentSessionId()) {
                    sessionToResume = mStagedSessions.get(session.getParentSessionId());
                }
            }
        }
        // The preconditions used during pre-reboot verification might have changed when device
        // is upgrading. Updated staged sessions to activation failed before we resume the session.
        if (isDeviceUpgrading && !sessionToResume.isStagedAndInTerminalState()) {
            sessionToResume.setStagedSessionFailed(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "Build fingerprint has changed");
            return;
        }
        checkStateAndResume(sessionToResume);
    }

    private void checkStateAndResume(@NonNull PackageInstallerSession session) {
        // Do not resume session if boot completed already
        if (SystemProperties.getBoolean("sys.boot_completed", false)) {
            return;
        }

        if (!session.isCommitted()) {
            // Session hasn't been committed yet, ignore.
            return;
        }
        // Check the state of the session and decide what to do next.
        if (session.isStagedSessionFailed() || session.isStagedSessionApplied()) {
            // Final states, nothing to do.
            return;
        }
        if (session.isDestroyed()) {
            // Device rebooted before abandoned session was cleaned up.
            session.abandon();
            return;
        }
        if (!session.isStagedSessionReady()) {
            // The framework got restarted before the pre-reboot verification could complete,
            // restart the verification.
            mPreRebootVerificationHandler.startPreRebootVerification(session.sessionId);
        } else {
            // Session had already being marked ready. Start the checks to verify if there is any
            // follow-up work.
            try {
                resumeSession(session);
            } catch (PackageManagerException e) {
                onInstallationFailure(session, e);
            } catch (Exception e) {
                Slog.e(TAG, "Staged install failed due to unhandled exception", e);
                onInstallationFailure(session, new PackageManagerException(
                        SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "Staged install failed due to unhandled exception: " + e));
            }
        }
    }

    private void logFailedApexSessionsIfNecessary() {
        synchronized (mFailedPackageNames) {
            if (!mFailedPackageNames.isEmpty()) {
                WatchdogRollbackLogger.logApexdRevert(mContext,
                        mFailedPackageNames, mNativeFailureReason);
            }
        }
    }

    void markStagedSessionsAsSuccessful() {
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
                mPreRebootVerificationHandler.readyToStart();
                BackgroundThread.getExecutor().execute(
                        () -> logFailedApexSessionsIfNecessary());
                ctx.unregisterReceiver(this);
            }
        }, new IntentFilter(Intent.ACTION_BOOT_COMPLETED));

        mFailureReasonFile.delete();
    }

    private static class LocalIntentReceiverAsync {
        final Consumer<Intent> mConsumer;

        LocalIntentReceiverAsync(Consumer<Intent> consumer) {
            mConsumer = consumer;
        }

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                mConsumer.accept(intent);
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }
    }

    private static class LocalIntentReceiverSync {
        private final LinkedBlockingQueue<Intent> mResult = new LinkedBlockingQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
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

    private PackageInstallerSession getStagedSession(int sessionId) {
        PackageInstallerSession session;
        synchronized (mStagedSessions) {
            session = mStagedSessions.get(sessionId);
        }
        return session;
    }

    private final class PreRebootVerificationHandler extends Handler {
        // Hold session ids before handler gets ready to do the verification.
        private IntArray mPendingSessionIds;
        private boolean mIsReady;
        @GuardedBy("mVerificationRunning")
        private final SparseBooleanArray mVerificationRunning = new SparseBooleanArray();

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
         *     <li>MSG_PRE_REBOOT_VERIFICATION_APK</li>
         *     <li>MSG_PRE_REBOOT_VERIFICATION_END</li>
         * </ul></p>
         *
         * Details about each of state can be found in corresponding handler of node.
         */
        private static final int MSG_PRE_REBOOT_VERIFICATION_START = 1;
        private static final int MSG_PRE_REBOOT_VERIFICATION_APEX = 2;
        private static final int MSG_PRE_REBOOT_VERIFICATION_APK = 3;
        private static final int MSG_PRE_REBOOT_VERIFICATION_END = 4;

        @Override
        public void handleMessage(Message msg) {
            final int sessionId = msg.arg1;
            final PackageInstallerSession session = getStagedSession(sessionId);
            if (session == null) {
                Slog.wtf(TAG, "Session disappeared in the middle of pre-reboot verification: "
                        + sessionId);
                return;
            }
            if (session.isDestroyed()) {
                // No point in running verification on a destroyed session
                onPreRebootVerificationComplete(sessionId);
                return;
            }
            try {
                switch (msg.what) {
                    case MSG_PRE_REBOOT_VERIFICATION_START:
                        handlePreRebootVerification_Start(session);
                        break;
                    case MSG_PRE_REBOOT_VERIFICATION_APEX:
                        handlePreRebootVerification_Apex(session);
                        break;
                    case MSG_PRE_REBOOT_VERIFICATION_APK:
                        handlePreRebootVerification_Apk(session);
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

        // Notify the handler that system is ready, and reschedule the pre-reboot verifications.
        private synchronized void readyToStart() {
            mIsReady = true;
            if (mPendingSessionIds != null) {
                for (int i = 0; i < mPendingSessionIds.size(); i++) {
                    startPreRebootVerification(mPendingSessionIds.get(i));
                }
                mPendingSessionIds = null;
            }
        }

        // Method for starting the pre-reboot verification
        private synchronized void startPreRebootVerification(int sessionId) {
            if (!mIsReady) {
                if (mPendingSessionIds == null) {
                    mPendingSessionIds = new IntArray();
                }
                mPendingSessionIds.add(sessionId);
                return;
            }

            PackageInstallerSession session = getStagedSession(sessionId);
            synchronized (mVerificationRunning) {
                // Do not start verification on a session that has been abandoned
                if (session == null || session.isDestroyed()) {
                    return;
                }
                Slog.d(TAG, "Starting preRebootVerification for session " + sessionId);
                mVerificationRunning.put(sessionId, true);
            }
            obtainMessage(MSG_PRE_REBOOT_VERIFICATION_START, sessionId, 0).sendToTarget();
        }

        private void onPreRebootVerificationFailure(PackageInstallerSession session,
                @SessionInfo.StagedSessionErrorCode int errorCode, String errorMessage) {
            if (!ensureActiveApexSessionIsAborted(session)) {
                Slog.e(TAG, "Failed to abort apex session " + session.sessionId);
                // Safe to ignore active apex session abortion failure since session will be marked
                // failed on next step and staging directory for session will be deleted.
            }
            session.setStagedSessionFailed(errorCode, errorMessage);
            onPreRebootVerificationComplete(session.sessionId);
        }

        // Things to do when pre-reboot verification completes for a particular sessionId
        private void onPreRebootVerificationComplete(int sessionId) {
            // Remove it from mVerificationRunning so that verification is considered complete
            synchronized (mVerificationRunning) {
                Slog.d(TAG, "Stopping preRebootVerification for session " + sessionId);
                mVerificationRunning.delete(sessionId);
            }
            // Check if the session was destroyed while pre-reboot verification was running. If so,
            // abandon it again.
            PackageInstallerSession session = getStagedSession(sessionId);
            if (session != null && session.isDestroyed()) {
                session.abandon();
            }
        }

        private boolean isVerificationRunning(int sessionId) {
            synchronized (mVerificationRunning) {
                return mVerificationRunning.get(sessionId);
            }
        }

        private void notifyPreRebootVerification_Start_Complete(int sessionId) {
            obtainMessage(MSG_PRE_REBOOT_VERIFICATION_APEX, sessionId, 0).sendToTarget();
        }

        private void notifyPreRebootVerification_Apex_Complete(int sessionId) {
            obtainMessage(MSG_PRE_REBOOT_VERIFICATION_APK, sessionId, 0).sendToTarget();
        }

        private void notifyPreRebootVerification_Apk_Complete(int sessionId) {
            obtainMessage(MSG_PRE_REBOOT_VERIFICATION_END, sessionId, 0).sendToTarget();
        }

        /**
         * A placeholder state for starting the pre reboot verification.
         *
         * See {@link PreRebootVerificationHandler} to see all nodes of pre reboot verification
         */
        private void handlePreRebootVerification_Start(@NonNull PackageInstallerSession session) {
            if ((session.params.installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0) {
                // If rollback is enabled for this session, we call through to the RollbackManager
                // with the list of sessions it must enable rollback for. Note that
                // notifyStagedSession is a synchronous operation.
                final IRollbackManager rm = IRollbackManager.Stub.asInterface(
                        ServiceManager.getService(Context.ROLLBACK_SERVICE));
                try {
                    // NOTE: To stay consistent with the non-staged install flow, we don't fail the
                    // entire install if rollbacks can't be enabled.
                    int rollbackId = rm.notifyStagedSession(session.sessionId);
                    if (rollbackId != -1) {
                        synchronized (mStagedSessions) {
                            mSessionRollbackIds.put(session.sessionId, rollbackId);
                        }
                    }
                } catch (RemoteException re) {
                    Slog.e(TAG, "Failed to notifyStagedSession for session: "
                            + session.sessionId, re);
                }
            }

            notifyPreRebootVerification_Start_Complete(session.sessionId);
        }

        /**
         * Pre-reboot verification state for apex files:
         *
         * <p><ul>
         *     <li>submits session to apex service</li>
         *     <li>validates signatures of apex files</li>
         * </ul></p>
         */
        private void handlePreRebootVerification_Apex(@NonNull PackageInstallerSession session) {
            final boolean hasApex = sessionContainsApex(session);

            // APEX checks. For single-package sessions, check if they contain an APEX. For
            // multi-package sessions, find all the child sessions that contain an APEX.
            if (hasApex) {
                final List<PackageInfo> apexPackages;
                try {
                    apexPackages = submitSessionToApexService(session);
                    for (int i = 0, size = apexPackages.size(); i < size; i++) {
                        validateApexSignature(apexPackages.get(i));
                    }
                } catch (PackageManagerException e) {
                    session.setStagedSessionFailed(e.error, e.getMessage());
                    onPreRebootVerificationComplete(session.sessionId);
                    return;
                }

                final PackageManagerInternal packageManagerInternal =
                        LocalServices.getService(PackageManagerInternal.class);
                packageManagerInternal.pruneCachedApksInApex(apexPackages);
            }

            notifyPreRebootVerification_Apex_Complete(session.sessionId);
        }

        /**
         * Pre-reboot verification state for apk files:
         *   <p><ul>
         *       <li>performs a dry-run install of apk</li>
         *   </ul></p>
         */
        private void handlePreRebootVerification_Apk(@NonNull PackageInstallerSession session) {
            if (!sessionContainsApk(session)) {
                notifyPreRebootVerification_Apk_Complete(session.sessionId);
                return;
            }

            try {
                Slog.d(TAG, "Running a pre-reboot verification for APKs in session "
                        + session.sessionId + " by performing a dry-run install");

                // verifyApksInSession will notify the handler when APK verification is complete
                verifyApksInSession(session);
                // TODO(b/118865310): abort the session on apexd.
            } catch (PackageManagerException e) {
                session.setStagedSessionFailed(e.error, e.getMessage());
                onPreRebootVerificationComplete(session.sessionId);
            }
        }

        /**
         * Pre-reboot verification state for wrapping up:
         * <p><ul>
         *     <li>enables rollback if required</li>
         *     <li>marks session as ready</li>
         * </ul></p>
         */
        private void handlePreRebootVerification_End(@NonNull PackageInstallerSession session) {
            // Before marking the session as ready, start checkpoint service if available
            try {
                IStorageManager storageManager = PackageHelper.getStorageManager();
                if (storageManager.supportsCheckpoint()) {
                    storageManager.startCheckpoint(2);
                }
            } catch (Exception e) {
                // Failed to get hold of StorageManager
                Slog.e(TAG, "Failed to get hold of StorageManager", e);
                session.setStagedSessionFailed(SessionInfo.STAGED_SESSION_UNKNOWN,
                        "Failed to get hold of StorageManager");
                onPreRebootVerificationComplete(session.sessionId);
                return;
            }

            // Stop pre-reboot verification before marking session ready. From this point on, if we
            // abandon the session then it will be cleaned up immediately. If session is abandoned
            // after this point, then even if for some reason system tries to install the session
            // or activate its apex, there won't be any files to work with as they will be cleaned
            // up by the system as part of abandonment. If session is abandoned before this point,
            // then the session is already destroyed and cannot be marked ready anymore.
            onPreRebootVerificationComplete(session.sessionId);

            // Proactively mark session as ready before calling apexd. Although this call order
            // looks counter-intuitive, this is the easiest way to ensure that session won't end up
            // in the inconsistent state:
            //  - If device gets rebooted right before call to apexd, then apexd will never activate
            //      apex files of this staged session. This will result in StagingManager failing
            //      the session.
            // On the other hand, if the order of the calls was inverted (first call apexd, then
            // mark session as ready), then if a device gets rebooted right after the call to apexd,
            // only apex part of the train will be applied, leaving device in an inconsistent state.
            Slog.d(TAG, "Marking session " + session.sessionId + " as ready");
            session.setStagedSessionReady();
            if (session.isStagedSessionReady()) {
                final boolean hasApex = sessionContainsApex(session);
                if (hasApex) {
                    try {
                        mApexManager.markStagedSessionReady(session.sessionId);
                    } catch (PackageManagerException e) {
                        session.setStagedSessionFailed(e.error, e.getMessage());
                        return;
                    }
                }
            }
        }
    }
}
