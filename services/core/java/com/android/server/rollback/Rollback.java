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
import android.os.UserManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

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
 *
 * <p>When accessing the state of a Rollback object, the caller is responsible for synchronization.
 * The lock object provided by {@link #getLock} should be acquired when accessing any of the mutable
 * state of a Rollback, including from the {@link RollbackInfo} and any of the
 * {@link PackageRollbackInfo} objects held within.
 */
class Rollback {

    private static final String TAG = "RollbackManager";

    @IntDef(flag = true, prefix = { "ROLLBACK_STATE_" }, value = {
            ROLLBACK_STATE_ENABLING,
            ROLLBACK_STATE_AVAILABLE,
            ROLLBACK_STATE_COMMITTED,
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
     * The session ID for the staged session if this rollback data represents a staged session,
     * {@code -1} otherwise.
     */
    private final int mStagedSessionId;

    /**
     * The rollback info for this rollback.
     *
     * <p>Any access to this field that touches any mutable state should be synchronized on
     * {@link #getLock}.
     */
    @GuardedBy("getLock")
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
     *
     * @see #getLock
     */
    private final Object mLock = new Object();

    /**
     * Constructs a new, empty Rollback instance.
     *
     * @param rollbackId the id of the rollback.
     * @param backupDir the directory where the rollback data is stored.
     * @param stagedSessionId the session id if this is a staged rollback, -1 otherwise.
     */
    Rollback(int rollbackId, File backupDir, int stagedSessionId) {
        this.info = new RollbackInfo(rollbackId,
                /* packages */ new ArrayList<>(),
                /* isStaged */ stagedSessionId != -1,
                /* causePackages */ new ArrayList<>(),
                /* committedSessionId */ -1);
        mBackupDir = backupDir;
        mStagedSessionId = stagedSessionId;
        mState = ROLLBACK_STATE_ENABLING;
        mTimestamp = Instant.now();
    }

    /**
     * Constructs a pre-populated Rollback instance.
     */
    Rollback(RollbackInfo info, File backupDir, Instant timestamp, int stagedSessionId,
            @RollbackState int state, int apkSessionId, boolean restoreUserDataInProgress) {
        this.info = info;
        mBackupDir = backupDir;
        mTimestamp = timestamp;
        mStagedSessionId = stagedSessionId;
        mState = state;
        mApkSessionId = apkSessionId;
        mRestoreUserDataInProgress = restoreUserDataInProgress;
    }

    /**
     * Returns a lock object that should be acquired before accessing any Rollback state from
     * {@link RollbackManagerServiceImpl}.
     *
     * <p>Note that while holding this lock, the lock for {@link RollbackManagerServiceImpl} should
     * not be acquired (but it is ok to acquire this lock while already holding the lock for that
     * class).
     */
    // TODO(b/136241838): Move rollback functionality into this class and synchronize on the lock
    // internally. Remove this method once this has been done for all cases.
    Object getLock() {
        return mLock;
    }

    /**
     * Whether the rollback is for rollback of a staged install.
     */
    @GuardedBy("getLock")
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
    @GuardedBy("getLock")
    Instant getTimestamp() {
        return mTimestamp;
    }

    /**
     * Sets the time at which upgrade occurred.
     */
    @GuardedBy("getLock")
    void setTimestamp(Instant timestamp) {
        mTimestamp = timestamp;
    }

    /**
     * Returns the session ID for the staged session if this rollback data represents a staged
     * session, or {@code -1} otherwise.
     */
    int getStagedSessionId() {
        return mStagedSessionId;
    }

    /**
     * Returns true if the rollback is in the ENABLING state.
     */
    @GuardedBy("getLock")
    boolean isEnabling() {
        return mState == ROLLBACK_STATE_ENABLING;
    }

    /**
     * Returns true if the rollback is in the AVAILABLE state.
     */
    @GuardedBy("getLock")
    boolean isAvailable() {
        return mState == ROLLBACK_STATE_AVAILABLE;
    }

    /**
     * Returns true if the rollback is in the COMMITTED state.
     */
    @GuardedBy("getLock")
    boolean isCommitted() {
        return mState == ROLLBACK_STATE_COMMITTED;
    }

    /**
     * Changes the state of the rollback to AVAILABLE. This also changes the timestamp to the
     * current time and saves the rollback.
     */
    @GuardedBy("getLock")
    void makeAvailable() {
        // TODO: What if the rollback has since been expired, for example due
        // to a new package being installed. Won't this revive an expired
        // rollback? Consider adding a ROLLBACK_STATE_EXPIRED to address this.
        mState = ROLLBACK_STATE_AVAILABLE;
        mTimestamp = Instant.now();
        RollbackStore.saveRollback(this);
    }

    /**
     * Commits the rollback.
     */
    @GuardedBy("getLock")
    void commit(final Context context, List<VersionedPackage> causePackages,
            String callerPackageName, IntentSender statusReceiver) {

        if (!isAvailable()) {
            sendFailure(context, statusReceiver,
                    RollbackManager.STATUS_FAILURE_ROLLBACK_UNAVAILABLE,
                    "Rollback unavailable");
            return;
        }

        // Get a context to use to install the downgraded version of the package.
        Context pkgContext;
        try {
            pkgContext = context.createPackageContext(callerPackageName, 0);
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

            for (PackageRollbackInfo info : info.getPackages()) {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                // TODO: We can't get the installerPackageName for apex
                // (b/123920130). Is it okay to ignore the installer package
                // for apex?
                if (!info.isApex()) {
                    String installerPackageName =
                            pm.getInstallerPackageName(info.getPackageName());
                    if (installerPackageName != null) {
                        params.setInstallerPackageName(installerPackageName);
                    }
                }
                params.setRequestDowngrade(true);
                params.setRequiredInstalledVersionCode(
                        info.getVersionRolledBackFrom().getLongVersionCode());
                if (isStaged()) {
                    params.setStaged();
                }
                if (info.isApex()) {
                    params.setInstallAsApex();
                }
                int sessionId = packageInstaller.createSession(params);
                PackageInstaller.Session session = packageInstaller.openSession(sessionId);
                File[] packageCodePaths = RollbackStore.getPackageCodePaths(
                        this, info.getPackageName());
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
                            // need to try rolling back again, so restore the rollback state to how
                            // it was before we tried committing.
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
                                    RollbackManager.EXTRA_STATUS, RollbackManager.STATUS_SUCCESS);
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

    /**
     * Returns the id of the post-reboot apk session for a staged install, if any.
     */
    @GuardedBy("getLock")
    int getApkSessionId() {
        return mApkSessionId;
    }

    /**
     * Sets the id of the post-reboot apk session for a staged install.
     */
    @GuardedBy("getLock")
    void setApkSessionId(int apkSessionId) {
        mApkSessionId = apkSessionId;
    }

    /**
     * Returns true if we are expecting the package manager to call restoreUserData for this
     * rollback because it has just been committed but the rollback has not yet been fully applied.
     */
    @GuardedBy("getLock")
    boolean isRestoreUserDataInProgress() {
        return mRestoreUserDataInProgress;
    }

    /**
     * Sets whether we are expecting the package manager to call restoreUserData for this
     * rollback because it has just been committed but the rollback has not yet been fully applied.
     */
    @GuardedBy("getLock")
    void setRestoreUserDataInProgress(boolean restoreUserDataInProgress) {
        mRestoreUserDataInProgress = restoreUserDataInProgress;
    }

    /**
     * Returns true if this rollback includes the package with the provided {@code packageName}.
     */
    @GuardedBy("getLock")
    boolean includesPackage(String packageName) {
        for (PackageRollbackInfo info : info.getPackages()) {
            if (info.getPackageName().equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if this rollback includes the package with the provided {@code packageName}
     * with a <i>version rolled back from</i> that is not {@code versionCode}.
     */
    @GuardedBy("getLock")
    boolean includesPackageWithDifferentVersion(String packageName, long versionCode) {
        for (PackageRollbackInfo info : info.getPackages()) {
            if (info.getPackageName().equals(packageName)
                    && info.getVersionRolledBackFrom().getLongVersionCode() != versionCode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a list containing the names of all the packages included in this rollback.
     */
    @GuardedBy("getLock")
    List<String> getPackageNames() {
        List<String> result = new ArrayList<>();
        for (PackageRollbackInfo info : info.getPackages()) {
            result.add(info.getPackageName());
        }
        return result;
    }

    /**
     * Returns a list containing the names of all the apex packages included in this rollback.
     */
    @GuardedBy("getLock")
    List<String> getApexPackageNames() {
        List<String> result = new ArrayList<>();
        for (PackageRollbackInfo info : info.getPackages()) {
            if (info.isApex()) {
                result.add(info.getPackageName());
            }
        }
        return result;
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

    @GuardedBy("getLock")
    String getStateAsString() {
        return rollbackStateToString(mState);
    }
}
