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

package com.android.server.rollback;

import static com.android.server.rollback.RollbackManagerServiceImpl.sendFailure;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseLongArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;


/**
 * Information about a rollback available for a set of atomically installed packages.
 */
class Rollback {

    private static final String TAG = "RollbackManager";

    @IntDef(flag = true, prefix = { "ROLLBACK_STATE_" }, value = {
            ROLLBACK_STATE_ENABLING,
            ROLLBACK_STATE_AVAILABLE,
            ROLLBACK_STATE_COMMITTED,
            ROLLBACK_STATE_DELETED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface RollbackState {}

    /**
     * The rollback is in the process of being enabled. It is not yet
     * available for use.
     */
    static final int ROLLBACK_STATE_ENABLING = 0;

    /**
     * The rollback is currently available.
     */
    static final int ROLLBACK_STATE_AVAILABLE = 1;

    /**
     * The rollback has been committed.
     */
    static final int ROLLBACK_STATE_COMMITTED = 3;

    /**
     * The rollback has been deleted.
     */
    static final int ROLLBACK_STATE_DELETED = 4;

    /**
     * The session ID for the staged session if this rollback data represents a staged session,
     * {@code -1} otherwise.
     */
    private final int mStagedSessionId;

    /**
     * The rollback info for this rollback.
     */
    public final RollbackInfo info;

    /**
     * The directory where the rollback data is stored.
     */
    private final File mBackupDir;

    /**
     * The time when the upgrade occurred, for purposes of expiring
     * rollback data.
     *
     * The timestamp is not applicable for all rollback states, but we make
     * sure to keep it non-null to avoid potential errors there.
     */
    @GuardedBy("mLock")
    private @NonNull Instant mTimestamp;

    /**
     * The current state of the rollback.
     * ENABLING, AVAILABLE, or COMMITTED.
     */
    @GuardedBy("mLock")
    private @RollbackState int mState;

    /**
     * The id of the post-reboot apk session for a staged install, if any.
     */
    @GuardedBy("mLock")
    private int mApkSessionId = -1;

    /**
     * True if we are expecting the package manager to call restoreUserData
     * for this rollback because it has just been committed but the rollback
     * has not yet been fully applied.
     */
    @GuardedBy("mLock")
    private boolean mRestoreUserDataInProgress = false;

    /**
     * Lock object to guard all access to Rollback state.
     */
    private final Object mLock = new Object();

    /**
     * The user that performed the install with rollback enabled.
     */
    public final int mUserId;

    /**
     * The installer package name from the install session that enabled the rollback. May be null if
     * that session did not set this value.
     *
     * If this is an empty string then the installer package name will be resolved by
     * PackageManager.
     */
    @Nullable public final String mInstallerPackageName;

    /**
     * Constructs a new, empty Rollback instance.
     *
     * @param rollbackId the id of the rollback.
     * @param backupDir the directory where the rollback data is stored.
     * @param stagedSessionId the session id if this is a staged rollback, -1 otherwise.
     * @param userId the user that performed the install with rollback enabled.
     * @param installerPackageName the installer package name from the original install session.
     */
    Rollback(int rollbackId, File backupDir, int stagedSessionId, int userId,
            String installerPackageName) {
        this.info = new RollbackInfo(rollbackId,
                /* packages */ new ArrayList<>(),
                /* isStaged */ stagedSessionId != -1,
                /* causePackages */ new ArrayList<>(),
                /* committedSessionId */ -1);
        mUserId = userId;
        mInstallerPackageName = installerPackageName;
        mBackupDir = backupDir;
        mStagedSessionId = stagedSessionId;
        mState = ROLLBACK_STATE_ENABLING;
        mTimestamp = Instant.now();
    }

    /**
     * Constructs a pre-populated Rollback instance.
     */
    Rollback(RollbackInfo info, File backupDir, Instant timestamp, int stagedSessionId,
            @RollbackState int state, int apkSessionId, boolean restoreUserDataInProgress,
            int userId, String installerPackageName) {
        this.info = info;
        mUserId = userId;
        mInstallerPackageName = installerPackageName;
        mBackupDir = backupDir;
        mTimestamp = timestamp;
        mStagedSessionId = stagedSessionId;
        mState = state;
        mApkSessionId = apkSessionId;
        mRestoreUserDataInProgress = restoreUserDataInProgress;
    }

    /**
     * Whether the rollback is for rollback of a staged install.
     */
    boolean isStaged() {
        return info.isStaged();
    }

    /**
     * Returns the directory in which rollback data should be stored.
     */
    File getBackupDir() {
        return mBackupDir;
    }

    /**
     * Returns the time when the upgrade occurred, for purposes of expiring rollback data.
     */
    Instant getTimestamp() {
        synchronized (mLock) {
            return mTimestamp;
        }
    }

    /**
     * Sets the time at which upgrade occurred.
     */
    void setTimestamp(Instant timestamp) {
        synchronized (mLock) {
            mTimestamp = timestamp;
            RollbackStore.saveRollback(this);
        }
    }

    /**
     * Returns the session ID for the staged session if this rollback data represents a staged
     * session, or {@code -1} otherwise.
     */
    int getStagedSessionId() {
        return mStagedSessionId;
    }

    /**
     * Returns the ID of the user that performed the install with rollback enabled.
     */
    int getUserId() {
        return mUserId;
    }

    /**
     * Returns the installer package name from the install session that enabled the rollback. In the
     * case that this is called on a rollback from an older version, returns the empty string.
     */
    @Nullable String getInstallerPackageName() {
        return mInstallerPackageName;
    }

    /**
     * Returns true if the rollback is in the ENABLING state.
     */
    boolean isEnabling() {
        synchronized (mLock) {
            return mState == ROLLBACK_STATE_ENABLING;
        }
    }

    /**
     * Returns true if the rollback is in the AVAILABLE state.
     */
    boolean isAvailable() {
        synchronized (mLock) {
            return mState == ROLLBACK_STATE_AVAILABLE;
        }
    }

    /**
     * Returns true if the rollback is in the COMMITTED state.
     */
    boolean isCommitted() {
        synchronized (mLock) {
            return mState == ROLLBACK_STATE_COMMITTED;
        }
    }

    /**
     * Returns true if the rollback is in the DELETED state.
     */
    boolean isDeleted() {
        synchronized (mLock) {
            return mState == ROLLBACK_STATE_DELETED;
        }
    }

    /**
     * Saves this rollback to persistent storage.
     */
    void saveRollback() {
        synchronized (mLock) {
            RollbackStore.saveRollback(this);
        }
    }

    /**
     * Enables this rollback for the provided package.
     *
     * @return boolean True if the rollback was enabled successfully for the specified package.
     */
    boolean enableForPackage(String packageName, long newVersion, long installedVersion,
            boolean isApex, String sourceDir, String[] splitSourceDirs) {
        try {
            RollbackStore.backupPackageCodePath(this, packageName, sourceDir);
            if (!ArrayUtils.isEmpty(splitSourceDirs)) {
                for (String dir : splitSourceDirs) {
                    RollbackStore.backupPackageCodePath(this, packageName, dir);
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to copy package for rollback for " + packageName, e);
            return false;
        }

        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(
                new VersionedPackage(packageName, newVersion),
                new VersionedPackage(packageName, installedVersion),
                new IntArray() /* pendingBackups */, new ArrayList<>() /* pendingRestores */,
                isApex, new IntArray(), new SparseLongArray() /* ceSnapshotInodes */);

        synchronized (mLock) {
            info.getPackages().add(packageRollbackInfo);
        }

        return true;
    }

    /**
     * Snapshots user data for the provided package and user ids. Does nothing if this rollback is
     * not in the ENABLING state.
     */
    void snapshotUserData(String packageName, int[] userIds, AppDataRollbackHelper dataHelper) {
        synchronized (mLock) {
            if (!isEnabling()) {
                return;
            }

            for (PackageRollbackInfo pkgRollbackInfo : info.getPackages()) {
                if (pkgRollbackInfo.getPackageName().equals(packageName)) {
                    dataHelper.snapshotAppData(info.getRollbackId(), pkgRollbackInfo, userIds);

                    RollbackStore.saveRollback(this);
                    pkgRollbackInfo.getSnapshottedUsers().addAll(IntArray.wrap(userIds));
                    break;
                }
            }
        }
    }

    /**
     * Commits the pending backups and restores for a given {@code userId}. If this rollback has a
     * pending backup, it is updated with a mapping from {@code userId} to inode of the CE user data
     * snapshot.
     */
    void commitPendingBackupAndRestoreForUser(int userId, AppDataRollbackHelper dataHelper) {
        synchronized (mLock) {
            if (dataHelper.commitPendingBackupAndRestoreForUser(userId, this)) {
                RollbackStore.saveRollback(this);
            }
        }
    }

    /**
     * Changes the state of the rollback to AVAILABLE. This also changes the timestamp to the
     * current time and saves the rollback. Does nothing if this rollback is already in the
     * DELETED state.
     */
    void makeAvailable() {
        synchronized (mLock) {
            if (isDeleted()) {
                Slog.w(TAG, "Cannot make deleted rollback available.");
                return;
            }
            mState = ROLLBACK_STATE_AVAILABLE;
            mTimestamp = Instant.now();
            RollbackStore.saveRollback(this);
        }
    }

    /**
     * Commits the rollback.
     */
    void commit(final Context context, List<VersionedPackage> causePackages,
            String callerPackageName, IntentSender statusReceiver) {
        synchronized (mLock) {
            if (!isAvailable()) {
                sendFailure(context, statusReceiver,
                        RollbackManager.STATUS_FAILURE_ROLLBACK_UNAVAILABLE,
                        "Rollback unavailable");
                return;
            }

            // Get a context to use to install the downgraded version of the package.
            Context pkgContext;
            try {
                pkgContext = context.createPackageContextAsUser(callerPackageName, 0,
                        UserHandle.of(mUserId));
            } catch (PackageManager.NameNotFoundException e) {
                sendFailure(context, statusReceiver, RollbackManager.STATUS_FAILURE,
                        "Invalid callerPackageName");
                return;
            }

            PackageManager pm = pkgContext.getPackageManager();
            try {
                PackageInstaller packageInstaller = pm.getPackageInstaller();
                PackageInstaller.SessionParams parentParams = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                parentParams.setRequestDowngrade(true);
                parentParams.setMultiPackage();
                if (isStaged()) {
                    parentParams.setStaged();
                }

                int parentSessionId = packageInstaller.createSession(parentParams);
                PackageInstaller.Session parentSession = packageInstaller.openSession(
                        parentSessionId);

                for (PackageRollbackInfo pkgRollbackInfo : info.getPackages()) {
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    String installerPackageName = mInstallerPackageName;
                    if (TextUtils.isEmpty(mInstallerPackageName)) {
                        installerPackageName = pm.getInstallerPackageName(
                                pkgRollbackInfo.getPackageName());
                    }
                    if (installerPackageName != null) {
                        params.setInstallerPackageName(installerPackageName);
                    }
                    params.setRequestDowngrade(true);
                    params.setRequiredInstalledVersionCode(
                            pkgRollbackInfo.getVersionRolledBackFrom().getLongVersionCode());
                    if (isStaged()) {
                        params.setStaged();
                    }
                    if (pkgRollbackInfo.isApex()) {
                        params.setInstallAsApex();
                    }
                    int sessionId = packageInstaller.createSession(params);
                    PackageInstaller.Session session = packageInstaller.openSession(sessionId);
                    File[] packageCodePaths = RollbackStore.getPackageCodePaths(
                            this, pkgRollbackInfo.getPackageName());
                    if (packageCodePaths == null) {
                        sendFailure(context, statusReceiver, RollbackManager.STATUS_FAILURE,
                                "Backup copy of package inaccessible");
                        return;
                    }

                    for (File packageCodePath : packageCodePaths) {
                        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(packageCodePath,
                                ParcelFileDescriptor.MODE_READ_ONLY)) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                session.write(packageCodePath.getName(), 0,
                                        packageCodePath.length(),
                                        fd);
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    }
                    parentSession.addChildSessionId(sessionId);
                }

                final LocalIntentReceiver receiver = new LocalIntentReceiver(
                        (Intent result) -> {
                            int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                                    PackageInstaller.STATUS_FAILURE);
                            if (status != PackageInstaller.STATUS_SUCCESS) {
                                // Committing the rollback failed, but we still have all the info we
                                // need to try rolling back again, so restore the rollback state to
                                // how it was before we tried committing.
                                // TODO: Should we just kill this rollback if commit failed?
                                // Why would we expect commit not to fail again?
                                // TODO: Could this cause a rollback to be resurrected
                                // if it should otherwise have expired by now?
                                synchronized (mLock) {
                                    mState = ROLLBACK_STATE_AVAILABLE;
                                    mRestoreUserDataInProgress = false;
                                }
                                sendFailure(context, statusReceiver,
                                        RollbackManager.STATUS_FAILURE_INSTALL,
                                        "Rollback downgrade install failed: "
                                                + result.getStringExtra(
                                                PackageInstaller.EXTRA_STATUS_MESSAGE));
                                return;
                            }

                            synchronized (mLock) {
                                if (!isStaged()) {
                                    // All calls to restoreUserData should have
                                    // completed by now for a non-staged install.
                                    mRestoreUserDataInProgress = false;
                                }

                                info.setCommittedSessionId(parentSessionId);
                                info.getCausePackages().addAll(causePackages);
                                RollbackStore.deletePackageCodePaths(this);
                                RollbackStore.saveRollback(this);
                            }

                            // Send success.
                            try {
                                final Intent fillIn = new Intent();
                                fillIn.putExtra(
                                        RollbackManager.EXTRA_STATUS,
                                        RollbackManager.STATUS_SUCCESS);
                                statusReceiver.sendIntent(context, 0, fillIn, null, null);
                            } catch (IntentSender.SendIntentException e) {
                                // Nowhere to send the result back to, so don't bother.
                            }

                            Intent broadcast = new Intent(Intent.ACTION_ROLLBACK_COMMITTED);

                            for (UserInfo userInfo : UserManager.get(context).getUsers(true)) {
                                context.sendBroadcastAsUser(broadcast,
                                        userInfo.getUserHandle(),
                                        Manifest.permission.MANAGE_ROLLBACKS);
                            }
                        }
                );

                mState = ROLLBACK_STATE_COMMITTED;
                mRestoreUserDataInProgress = true;
                parentSession.commit(receiver.getIntentSender());
            } catch (IOException e) {
                Slog.e(TAG, "Rollback failed", e);
                sendFailure(context, statusReceiver, RollbackManager.STATUS_FAILURE,
                        "IOException: " + e.toString());
            }
        }
    }

    /**
     * Restores user data for the specified package if this rollback is currently marked as
     * having a restore in progress.
     *
     * @return boolean True if this rollback has a restore in progress and contains the specified
     * package.
     */
    boolean restoreUserDataForPackageIfInProgress(String packageName, int[] userIds, int appId,
            String seInfo, AppDataRollbackHelper dataHelper) {
        synchronized (mLock) {
            if (!isRestoreUserDataInProgress()) {
                return false;
            }

            boolean foundPackage = false;
            for (PackageRollbackInfo pkgRollbackInfo : info.getPackages()) {
                if (pkgRollbackInfo.getPackageName().equals(packageName)) {
                    foundPackage = true;
                    boolean changedRollback = false;
                    for (int userId : userIds) {
                        changedRollback |= dataHelper.restoreAppData(
                                info.getRollbackId(), pkgRollbackInfo, userId, appId, seInfo);
                    }
                    // We've updated metadata about this rollback, so save it to flash.
                    if (changedRollback) {
                        RollbackStore.saveRollback(this);
                    }
                    break;
                }
            }
            return foundPackage;
        }
    }

    /**
     * Deletes app data snapshots associated with this rollback, and moves to the DELETED state.
     */
    void delete(AppDataRollbackHelper dataHelper) {
        synchronized (mLock) {
            for (PackageRollbackInfo pkgInfo : info.getPackages()) {
                IntArray snapshottedUsers = pkgInfo.getSnapshottedUsers();
                for (int i = 0; i < snapshottedUsers.size(); i++) {
                    // Destroy app data snapshot.
                    int userId = snapshottedUsers.get(i);

                    dataHelper.destroyAppDataSnapshot(info.getRollbackId(), pkgInfo, userId);
                }
            }

            RollbackStore.deleteRollback(this);
            mState = ROLLBACK_STATE_DELETED;
        }
    }

    /**
     * Returns the id of the post-reboot apk session for a staged install, if any.
     */
    int getApkSessionId() {
        synchronized (mLock) {
            return mApkSessionId;
        }
    }

    /**
     * Sets the id of the post-reboot apk session for a staged install.
     */
    void setApkSessionId(int apkSessionId) {
        synchronized (mLock) {
            mApkSessionId = apkSessionId;
            RollbackStore.saveRollback(this);
        }
    }

    /**
     * Returns true if we are expecting the package manager to call restoreUserData for this
     * rollback because it has just been committed but the rollback has not yet been fully applied.
     */
    boolean isRestoreUserDataInProgress() {
        synchronized (mLock) {
            return mRestoreUserDataInProgress;
        }
    }

    /**
     * Sets whether we are expecting the package manager to call restoreUserData for this
     * rollback because it has just been committed but the rollback has not yet been fully applied.
     */
    void setRestoreUserDataInProgress(boolean restoreUserDataInProgress) {
        synchronized (mLock) {
            mRestoreUserDataInProgress = restoreUserDataInProgress;
            RollbackStore.saveRollback(this);
        }
    }

    /**
     * Returns true if this rollback includes the package with the provided {@code packageName}.
     */
    boolean includesPackage(String packageName) {
        synchronized (mLock) {
            for (PackageRollbackInfo packageRollbackInfo : info.getPackages()) {
                if (packageRollbackInfo.getPackageName().equals(packageName)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Returns true if this rollback includes the package with the provided {@code packageName}
     * with a <i>version rolled back from</i> that is not {@code versionCode}.
     */
    boolean includesPackageWithDifferentVersion(String packageName, long versionCode) {
        synchronized (mLock) {
            for (PackageRollbackInfo pkgRollbackInfo : info.getPackages()) {
                if (pkgRollbackInfo.getPackageName().equals(packageName)
                        && pkgRollbackInfo.getVersionRolledBackFrom().getLongVersionCode()
                        != versionCode) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Returns a list containing the names of all the packages included in this rollback.
     */
    List<String> getPackageNames() {
        synchronized (mLock) {
            List<String> result = new ArrayList<>();
            for (PackageRollbackInfo pkgRollbackInfo : info.getPackages()) {
                result.add(pkgRollbackInfo.getPackageName());
            }
            return result;
        }
    }

    /**
     * Returns a list containing the names of all the apex packages included in this rollback.
     */
    List<String> getApexPackageNames() {
        synchronized (mLock) {
            List<String> result = new ArrayList<>();
            for (PackageRollbackInfo pkgRollbackInfo : info.getPackages()) {
                if (pkgRollbackInfo.isApex()) {
                    result.add(pkgRollbackInfo.getPackageName());
                }
            }
            return result;
        }
    }

    int getPackageCount() {
        synchronized (mLock) {
            return info.getPackages().size();
        }
    }

    static String rollbackStateToString(@RollbackState int state) {
        switch (state) {
            case Rollback.ROLLBACK_STATE_ENABLING: return "enabling";
            case Rollback.ROLLBACK_STATE_AVAILABLE: return "available";
            case Rollback.ROLLBACK_STATE_COMMITTED: return "committed";
        }
        throw new AssertionError("Invalid rollback state: " + state);
    }

    static @RollbackState int rollbackStateFromString(String state)
            throws ParseException {
        switch (state) {
            case "enabling": return Rollback.ROLLBACK_STATE_ENABLING;
            case "available": return Rollback.ROLLBACK_STATE_AVAILABLE;
            case "committed": return Rollback.ROLLBACK_STATE_COMMITTED;
        }
        throw new ParseException("Invalid rollback state: " + state, 0);
    }

    String getStateAsString() {
        synchronized (mLock) {
            return rollbackStateToString(mState);
        }
    }

    void dump(IndentingPrintWriter ipw) {
        synchronized (mLock) {
            ipw.println(info.getRollbackId() + ":");
            ipw.increaseIndent();
            ipw.println("-state: " + getStateAsString());
            ipw.println("-timestamp: " + getTimestamp());
            if (getStagedSessionId() != -1) {
                ipw.println("-stagedSessionId: " + getStagedSessionId());
            }
            ipw.println("-packages:");
            ipw.increaseIndent();
            for (PackageRollbackInfo pkg : info.getPackages()) {
                ipw.println(pkg.getPackageName()
                        + " " + pkg.getVersionRolledBackFrom().getLongVersionCode()
                        + " -> " + pkg.getVersionRolledBackTo().getLongVersionCode());
            }
            ipw.decreaseIndent();
            if (isCommitted()) {
                ipw.println("-causePackages:");
                ipw.increaseIndent();
                for (VersionedPackage cPkg : info.getCausePackages()) {
                    ipw.println(cPkg.getPackageName() + " " + cPkg.getLongVersionCode());
                }
                ipw.decreaseIndent();
                ipw.println("-committedSessionId: " + info.getCommittedSessionId());
            }
            ipw.decreaseIndent();
        }
    }
}
