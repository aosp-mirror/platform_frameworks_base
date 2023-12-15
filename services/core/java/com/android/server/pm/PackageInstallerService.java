/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.app.admin.DevicePolicyResources.Strings.Core.PACKAGE_DELETED_BY_DO;
import static android.content.pm.PackageInstaller.LOCATION_DATA_APP;
import static android.content.pm.PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_DISABLED;
import static android.content.pm.PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED;
import static android.content.pm.PackageInstaller.UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE;
import static android.content.pm.PackageInstaller.UNARCHIVAL_ERROR_NO_CONNECTIVITY;
import static android.content.pm.PackageInstaller.UNARCHIVAL_ERROR_USER_ACTION_NEEDED;
import static android.content.pm.PackageInstaller.UNARCHIVAL_GENERIC_ERROR;
import static android.content.pm.PackageInstaller.UNARCHIVAL_OK;
import static android.content.pm.PackageManager.INSTALL_UNARCHIVE_DRAFT;
import static android.os.Process.INVALID_UID;
import static android.os.Process.SYSTEM_UID;

import static com.android.server.pm.PackageArchiver.isArchivingEnabled;
import static com.android.server.pm.PackageManagerService.SHELL_PACKAGE_NAME;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PackageDeleteObserver;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.pm.ApplicationInfo;
import android.content.pm.ArchivedPackageParcel;
import android.content.pm.Flags;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageInstallerCallback;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.InstallConstraints;
import android.content.pm.PackageInstaller.InstallConstraintsResult;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageInstaller.UnarchivalStatus;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.DeleteFlags;
import android.content.pm.ParceledListSlice;
import android.content.pm.VersionedPackage;
import android.content.pm.parsing.FrameworkParsingPackageUtils;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelableException;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.pm.parsing.PackageParser2;
import com.android.internal.util.ImageUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.utils.RequestThrottle;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/** The service responsible for installing packages. */
public class PackageInstallerService extends IPackageInstaller.Stub implements
        PackageSessionProvider {
    private static final String TAG = "PackageInstaller";
    private static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);

    private static final boolean DEBUG = Build.IS_DEBUGGABLE;

    // TODO: remove outstanding sessions when installer package goes away
    // TODO: notify listeners in other users when package has been installed there
    // TODO: purge expired sessions periodically in addition to at reboot

    /** XML constants used in {@link #mSessionsFile} */
    private static final String TAG_SESSIONS = "sessions";

    /** Automatically destroy sessions older than this */
    private static final long MAX_AGE_MILLIS = 3 * DateUtils.DAY_IN_MILLIS;
    /** Automatically destroy staged sessions that have not changed state in this time */
    private static final long MAX_TIME_SINCE_UPDATE_MILLIS = 21 * DateUtils.DAY_IN_MILLIS;
    /** Upper bound on number of active sessions for a UID that has INSTALL_PACKAGES */
    private static final long MAX_ACTIVE_SESSIONS_WITH_PERMISSION = 1024;
    /** Upper bound on number of active sessions for a UID without INSTALL_PACKAGES */
    private static final long MAX_ACTIVE_SESSIONS_NO_PERMISSION = 50;
    /** Upper bound on number of historical sessions for a UID */
    private static final long MAX_HISTORICAL_SESSIONS = 1048576;
    /** Destroy sessions older than this on storage free request */
    private static final long MAX_SESSION_AGE_ON_LOW_STORAGE_MILLIS = 8 * DateUtils.HOUR_IN_MILLIS;
    /** Maximum time to wait for install constraints to be satisfied */
    private static final long MAX_INSTALL_CONSTRAINTS_TIMEOUT_MILLIS = DateUtils.WEEK_IN_MILLIS;

    /** Threshold of historical sessions size */
    private static final int HISTORICAL_SESSIONS_THRESHOLD = 500;
    /** Size of historical sessions to be cleared when reaching threshold */
    private static final int HISTORICAL_CLEAR_SIZE = 400;

    /**
     * Allow verification-skipping if it's a development app installed through ADB with
     * disable verification flag specified.
     */
    private static final int ADB_DEV_MODE = PackageManager.INSTALL_FROM_ADB
            | PackageManager.INSTALL_ALLOW_TEST;

    /**
     * Set of app op permissions that the installer of a session is allowed to change through
     * {@link PackageInstaller.SessionParams#setPermissionState(String, int)}.
     */
    public static final Set<String> INSTALLER_CHANGEABLE_APP_OP_PERMISSIONS = Set.of(
            Manifest.permission.USE_FULL_SCREEN_INTENT
    );

    final PackageArchiver mPackageArchiver;

    private final Context mContext;
    private final PackageManagerService mPm;
    private final ApexManager mApexManager;
    private final StagingManager mStagingManager;

    private AppOpsManager mAppOps;

    private final HandlerThread mInstallThread;
    private final Handler mInstallHandler;

    private final Callbacks mCallbacks;

    private volatile boolean mOkToSendBroadcasts = false;
    private volatile boolean mBypassNextStagedInstallerCheck = false;
    private volatile boolean mBypassNextAllowedApexUpdateCheck = false;
    private volatile int mDisableVerificationForUid = INVALID_UID;

    /**
     * File storing persisted {@link #mSessions} metadata.
     */
    private final AtomicFile mSessionsFile;

    /**
     * Directory storing persisted {@link #mSessions} metadata which is too
     * heavy to store directly in {@link #mSessionsFile}.
     */
    private final File mSessionsDir;

    private final InternalCallback mInternalCallback = new InternalCallback();
    private final PackageSessionVerifier mSessionVerifier;
    private final GentleUpdateHelper mGentleUpdateHelper;

    /**
     * Used for generating session IDs. Since this is created at boot time,
     * normal random might be predictable.
     */
    private final Random mRandom = new SecureRandom();

    /** All sessions allocated */
    @GuardedBy("mSessions")
    private final SparseBooleanArray mAllocatedSessions = new SparseBooleanArray();

    @GuardedBy("mSessions")
    private final SparseArray<PackageInstallerSession> mSessions = new SparseArray<>();

    /** Historical sessions kept around for debugging purposes */
    @GuardedBy("mSessions")
    private final List<PackageInstallerHistoricalSession> mHistoricalSessions = new ArrayList<>();

    @GuardedBy("mSessions")
    private final SparseIntArray mHistoricalSessionsByInstaller = new SparseIntArray();

    /** Sessions allocated to legacy users */
    @GuardedBy("mSessions")
    private final SparseBooleanArray mLegacySessions = new SparseBooleanArray();

    /** Policy for allowing a silent update. */
    private final SilentUpdatePolicy mSilentUpdatePolicy = new SilentUpdatePolicy();

    private static final FilenameFilter sStageFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return isStageName(name);
        }
    };

    private static final class Lifecycle extends SystemService {
        private final PackageInstallerService mPackageInstallerService;

        Lifecycle(Context context, PackageInstallerService service) {
            super(context);
            mPackageInstallerService = service;
        }

        @Override
        public void onStart() {
            // no-op
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mPackageInstallerService.onBroadcastReady();
            }
        }
    }

    @NonNull
    private final RequestThrottle mSettingsWriteRequest = new RequestThrottle(IoThread.getHandler(),
            () -> {
                synchronized (mSessions) {
                    return writeSessionsLocked();
                }
            });

    public PackageInstallerService(Context context, PackageManagerService pm,
            Supplier<PackageParser2> apexParserSupplier) {
        mContext = context;
        mPm = pm;

        mInstallThread = new HandlerThread(TAG);
        mInstallThread.start();

        mInstallHandler = new Handler(mInstallThread.getLooper());

        mCallbacks = new Callbacks(mInstallThread.getLooper());

        mSessionsFile = new AtomicFile(
                new File(Environment.getDataSystemDirectory(), "install_sessions.xml"),
                "package-session");
        mSessionsDir = new File(Environment.getDataSystemDirectory(), "install_sessions");
        mSessionsDir.mkdirs();

        mApexManager = ApexManager.getInstance();
        mStagingManager = new StagingManager(context);
        mSessionVerifier = new PackageSessionVerifier(context, mPm, mApexManager,
                apexParserSupplier, mInstallThread.getLooper());
        mGentleUpdateHelper = new GentleUpdateHelper(
                context, mInstallThread.getLooper(), new AppStateHelper(context));
        mPackageArchiver = new PackageArchiver(mContext, mPm);

        LocalServices.getService(SystemServiceManager.class).startService(
                new Lifecycle(context, this));
    }

    StagingManager getStagingManager() {
        return mStagingManager;
    }

    boolean okToSendBroadcasts()  {
        return mOkToSendBroadcasts;
    }

    public void systemReady() {
        mAppOps = mContext.getSystemService(AppOpsManager.class);
        mStagingManager.systemReady();
        mGentleUpdateHelper.systemReady();

        synchronized (mSessions) {
            readSessionsLocked();
            expireSessionsLocked();

            reconcileStagesLocked(StorageManager.UUID_PRIVATE_INTERNAL);

            final ArraySet<File> unclaimedIcons = newArraySet(
                    mSessionsDir.listFiles());

            // Ignore stages and icons claimed by active sessions
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                unclaimedIcons.remove(buildAppIconFile(session.sessionId));
            }

            // Clean up orphaned icons
            for (File icon : unclaimedIcons) {
                Slog.w(TAG, "Deleting orphan icon " + icon);
                icon.delete();
            }

            // Invalid sessions might have been marked while parsing. Re-write the database with
            // the updated information.
            mSettingsWriteRequest.runNow();

        }
    }

    private void onBroadcastReady() {
        // Broadcasts are not sent while we restore sessions on boot, since no processes would be
        // ready to listen to them. From now on, it is safe to send broadcasts which otherwise will
        // be rejected by ActivityManagerService if its systemReady() is not completed.
        mOkToSendBroadcasts = true;
    }

    void restoreAndApplyStagedSessionIfNeeded() {
        List<StagingManager.StagedSession> stagedSessionsToRestore = new ArrayList<>();
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (!session.isStaged()) {
                    continue;
                }
                StagingManager.StagedSession stagedSession = session.mStagedSession;
                if (!stagedSession.isInTerminalState() && stagedSession.hasParentSessionId()
                        && getSession(stagedSession.getParentSessionId()) == null) {
                    stagedSession.setSessionFailed(PackageManager.INSTALL_ACTIVATION_FAILED,
                            "An orphan staged session " + stagedSession.sessionId() + " is found, "
                                + "parent " + stagedSession.getParentSessionId() + " is missing");
                    continue;
                }
                if (!stagedSession.hasParentSessionId() && stagedSession.isCommitted()
                        && !stagedSession.isInTerminalState()) {
                    // StagingManager.restoreSessions expects a list of committed, non-finalized
                    // parent staged sessions.
                    stagedSessionsToRestore.add(stagedSession);
                }
            }
        }
        // Don't hold mSessions lock when calling restoreSessions, since it might trigger an APK
        // atomic install which needs to query sessions, which requires lock on mSessions.
        // Note: restoreSessions mutates content of stagedSessionsToRestore.
        mStagingManager.restoreSessions(stagedSessionsToRestore, mPm.isDeviceUpgrading());
    }

    @GuardedBy("mSessions")
    private void reconcileStagesLocked(String volumeUuid) {
        final ArraySet<File> unclaimedStages = getStagingDirsOnVolume(volumeUuid);
        // Ignore stages claimed by active sessions
        for (int i = 0; i < mSessions.size(); i++) {
            final PackageInstallerSession session = mSessions.valueAt(i);
            unclaimedStages.remove(session.stageDir);
        }
        removeStagingDirs(unclaimedStages);
    }

    private ArraySet<File> getStagingDirsOnVolume(String volumeUuid) {
        final File stagingDir = getTmpSessionDir(volumeUuid);
        final ArraySet<File> stagingDirs = newArraySet(stagingDir.listFiles(sStageFilter));

        // We also need to clean up orphaned staging directory for staged sessions
        final File stagedSessionStagingDir = Environment.getDataStagingDirectory(volumeUuid);
        stagingDirs.addAll(newArraySet(stagedSessionStagingDir.listFiles()));
        return stagingDirs;
    }

    private void removeStagingDirs(ArraySet<File> stagingDirsToRemove) {
        // Clean up orphaned staging directories
        for (File stage : stagingDirsToRemove) {
            Slog.w(TAG, "Deleting orphan stage " + stage);
            mPm.removeCodePath(stage);
        }
    }

    public void onPrivateVolumeMounted(String volumeUuid) {
        synchronized (mSessions) {
            reconcileStagesLocked(volumeUuid);
        }
    }

    /**
     * Called to free up some storage space from obsolete installation files
     */
    public void freeStageDirs(String volumeUuid) {
        final ArraySet<File> unclaimedStagingDirsOnVolume = getStagingDirsOnVolume(volumeUuid);
        final long currentTimeMillis = System.currentTimeMillis();
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (!unclaimedStagingDirsOnVolume.contains(session.stageDir)) {
                    // Only handles sessions stored on the target volume
                    continue;
                }
                final long age = currentTimeMillis - session.createdMillis;
                if (age >= MAX_SESSION_AGE_ON_LOW_STORAGE_MILLIS) {
                    // Aggressively close old sessions because we are running low on storage
                    // Their staging dirs will be removed too
                    PackageInstallerSession root = !session.hasParentSessionId()
                            ? session : mSessions.get(session.getParentSessionId());
                    if (root == null) {
                        Slog.e(TAG, "freeStageDirs: found an orphaned session: "
                                + session.sessionId + " parent=" + session.getParentSessionId());
                    } else if (!root.isDestroyed()) {
                        root.abandon();
                    }
                } else {
                    // Session is new enough, so it deserves to be kept even on low storage
                    unclaimedStagingDirsOnVolume.remove(session.stageDir);
                }
            }
        }
        removeStagingDirs(unclaimedStagingDirsOnVolume);
    }

    @Deprecated
    public File allocateStageDirLegacy(String volumeUuid, boolean isEphemeral) throws IOException {
        synchronized (mSessions) {
            try {
                final int sessionId = allocateSessionIdLocked();
                mLegacySessions.put(sessionId, true);
                final File sessionStageDir = buildTmpSessionDir(sessionId, volumeUuid);
                prepareStageDir(sessionStageDir);
                return sessionStageDir;
            } catch (IllegalStateException e) {
                throw new IOException(e);
            }
        }
    }

    @Deprecated
    public String allocateExternalStageCidLegacy() {
        synchronized (mSessions) {
            final int sessionId = allocateSessionIdLocked();
            mLegacySessions.put(sessionId, true);
            return "smdl" + sessionId + ".tmp";
        }
    }

    @GuardedBy("mSessions")
    private void readSessionsLocked() {
        if (LOGD) Slog.v(TAG, "readSessionsLocked()");

        mSessions.clear();

        FileInputStream fis = null;
        try {
            fis = mSessionsFile.openRead();
            final TypedXmlPullParser in = Xml.resolvePullParser(fis);

            int type;
            while ((type = in.next()) != END_DOCUMENT) {
                if (type == START_TAG) {
                    final String tag = in.getName();
                    if (PackageInstallerSession.TAG_SESSION.equals(tag)) {
                        final PackageInstallerSession session;
                        try {
                            session = PackageInstallerSession.readFromXml(in, mInternalCallback,
                                    mContext, mPm, mInstallThread.getLooper(), mStagingManager,
                                    mSessionsDir, this, mSilentUpdatePolicy);
                        } catch (Exception e) {
                            Slog.e(TAG, "Could not read session", e);
                            continue;
                        }
                        mSessions.put(session.sessionId, session);
                        mAllocatedSessions.put(session.sessionId, true);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // Missing sessions are okay, probably first boot
        } catch (IOException | XmlPullParserException | ArrayIndexOutOfBoundsException e) {
            Slog.wtf(TAG, "Failed reading install sessions", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
        // After reboot housekeeping.
        for (int i = 0; i < mSessions.size(); ++i) {
            PackageInstallerSession session = mSessions.valueAt(i);
            session.onAfterSessionRead(mSessions);
        }
    }

    @GuardedBy("mSessions")
    private void expireSessionsLocked() {
        SparseArray<PackageInstallerSession> tmp = mSessions.clone();
        final int n = tmp.size();
        for (int i = 0; i < n; ++i) {
            PackageInstallerSession session = tmp.valueAt(i);
            if (session.hasParentSessionId()) {
                // Child sessions will be expired when handling parent sessions
                continue;
            }
            final long age = System.currentTimeMillis() - session.createdMillis;
            final long timeSinceUpdate = System.currentTimeMillis() - session.getUpdatedMillis();
            final boolean valid;
            if (session.isStaged()) {
                valid = !session.isStagedAndInTerminalState()
                        || timeSinceUpdate < MAX_TIME_SINCE_UPDATE_MILLIS;
            } else if (age >= MAX_AGE_MILLIS) {
                Slog.w(TAG, "Abandoning old session created at "
                        + session.createdMillis);
                valid = false;
            } else {
                valid = true;
            }
            if (!valid) {
                Slog.w(TAG, "Remove old session: " + session.sessionId);
                // Remove expired sessions as well as child sessions if any
                removeActiveSession(session);
            }
        }
    }

    /**
     * Moves a session (including the child sessions) from mSessions to mHistoricalSessions.
     * This should only be called on a root session.
     */
    @GuardedBy("mSessions")
    private void removeActiveSession(PackageInstallerSession session) {
        mSessions.remove(session.sessionId);
        addHistoricalSessionLocked(session);
        for (PackageInstallerSession child : session.getChildSessions()) {
            mSessions.remove(child.sessionId);
            addHistoricalSessionLocked(child);
        }
    }

    @GuardedBy("mSessions")
    private void addHistoricalSessionLocked(PackageInstallerSession session) {
        if (mHistoricalSessions.size() > HISTORICAL_SESSIONS_THRESHOLD) {
            Slog.d(TAG, "Historical sessions size reaches threshold, clear the oldest");
            mHistoricalSessions.subList(0, HISTORICAL_CLEAR_SIZE).clear();
        }
        mHistoricalSessions.add(session.createHistoricalSession());

        int installerUid = session.getInstallerUid();
        // Increment the number of sessions by this installerUid.
        mHistoricalSessionsByInstaller.put(installerUid,
                mHistoricalSessionsByInstaller.get(installerUid) + 1);
    }

    @GuardedBy("mSessions")
    private boolean writeSessionsLocked() {
        if (LOGD) Slog.v(TAG, "writeSessionsLocked()");

        FileOutputStream fos = null;
        try {
            fos = mSessionsFile.startWrite();

            final TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.startTag(null, TAG_SESSIONS);
            final int size = mSessions.size();
            for (int i = 0; i < size; i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                session.write(out, mSessionsDir);
            }
            out.endTag(null, TAG_SESSIONS);
            out.endDocument();

            mSessionsFile.finishWrite(fos);
            return true;
        } catch (IOException e) {
            if (fos != null) {
                mSessionsFile.failWrite(fos);
            }
        }

        return false;
    }

    private File buildAppIconFile(int sessionId) {
        return new File(mSessionsDir, "app_icon." + sessionId + ".png");
    }

    @Override
    public int createSession(SessionParams params, String installerPackageName,
            String callingAttributionTag, int userId) {
        try {
            if (params.dataLoaderParams != null
                    && mContext.checkCallingOrSelfPermission(Manifest.permission.USE_INSTALLER_V2)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("You need the "
                        + "com.android.permission.USE_INSTALLER_V2 permission "
                        + "to use a data loader");
            }

            // Draft sessions cannot be created through the public API.
            params.installFlags &= ~PackageManager.INSTALL_UNARCHIVE_DRAFT;
            return createSessionInternal(params, installerPackageName, callingAttributionTag,
                    Binder.getCallingUid(), userId);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    int createSessionInternal(SessionParams params, String installerPackageName,
            String installerAttributionTag, int callingUid, int userId)
            throws IOException {
        final Computer snapshot = mPm.snapshotComputer();
        snapshot.enforceCrossUserPermission(callingUid, userId, true, true, "createSession");

        if (mPm.isUserRestricted(userId, UserManager.DISALLOW_INSTALL_APPS)) {
            throw new SecurityException("User restriction prevents installing");
        }

        // INSTALL_REASON_ROLLBACK allows an app to be rolled back without requiring the ROLLBACK
        // capability; ensure if this is set as the install reason the app has one of the necessary
        // signature permissions to perform the rollback.
        if (params.installReason == PackageManager.INSTALL_REASON_ROLLBACK) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.MANAGE_ROLLBACKS)
                    != PackageManager.PERMISSION_GRANTED &&
                    mContext.checkCallingOrSelfPermission(Manifest.permission.TEST_MANAGE_ROLLBACKS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "INSTALL_REASON_ROLLBACK requires the MANAGE_ROLLBACKS permission or the "
                                + "TEST_MANAGE_ROLLBACKS permission");
            }
        }

        // App package name and label length is restricted so that really long strings aren't
        // written to disk.
        if (params.appPackageName != null && !isValidPackageName(params.appPackageName)) {
            params.appPackageName = null;
        }

        params.appLabel = TextUtils.trimToSize(params.appLabel,
                PackageItemInfo.MAX_SAFE_LABEL_LENGTH);

        // Validate installer package name.
        if (params.installerPackageName != null && !isValidPackageName(
                params.installerPackageName)) {
            params.installerPackageName = null;
        }

        var requestedInstallerPackageName =
                params.installerPackageName != null ? params.installerPackageName
                        : installerPackageName;

        if (PackageManagerServiceUtils.isRootOrShell(callingUid)
                || PackageInstallerSession.isSystemDataLoaderInstallation(params)
                || PackageManagerServiceUtils.isAdoptedShell(callingUid, mContext)) {
            params.installFlags |= PackageManager.INSTALL_FROM_ADB;
            // adb installs can override the installingPackageName, but not the
            // initiatingPackageName
            installerPackageName = SHELL_PACKAGE_NAME;
        } else {
            if (callingUid != SYSTEM_UID) {
                // The supplied installerPackageName must always belong to the calling app.
                mAppOps.checkPackage(callingUid, installerPackageName);
            }
            // Only apps with INSTALL_PACKAGES are allowed to set an installer that is not the
            // caller.
            if (!TextUtils.equals(requestedInstallerPackageName, installerPackageName)) {
                if (mContext.checkCallingOrSelfPermission(Manifest.permission.INSTALL_PACKAGES)
                        != PackageManager.PERMISSION_GRANTED) {
                    mAppOps.checkPackage(callingUid, requestedInstallerPackageName);
                }
            }

            params.installFlags &= ~PackageManager.INSTALL_FROM_ADB;
            params.installFlags &= ~PackageManager.INSTALL_ALL_USERS;
            params.installFlags &= ~PackageManager.INSTALL_ARCHIVED;
            params.installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
            if ((params.installFlags & PackageManager.INSTALL_VIRTUAL_PRELOAD) != 0
                    && !mPm.isCallerVerifier(snapshot, callingUid)) {
                params.installFlags &= ~PackageManager.INSTALL_VIRTUAL_PRELOAD;
            }
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.INSTALL_TEST_ONLY_PACKAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                params.installFlags &= ~PackageManager.INSTALL_ALLOW_TEST;
            }

            // developmentInstallFlags can ony be set by shell or root.
            params.developmentInstallFlags = 0;
        }

        String originatingPackageName = null;
        if (params.originatingUid != SessionParams.UID_UNKNOWN
                && params.originatingUid != callingUid) {
            String[] packages = snapshot.getPackagesForUid(params.originatingUid);
            if (packages != null && packages.length > 0) {
                // Choose an arbitrary representative package in the case of a shared UID.
                originatingPackageName = packages[0];
            }
        }

        if (Build.IS_DEBUGGABLE || PackageManagerServiceUtils.isSystemOrRoot(callingUid)) {
            params.installFlags |= PackageManager.INSTALL_ALLOW_DOWNGRADE;
        } else {
            params.installFlags &= ~PackageManager.INSTALL_ALLOW_DOWNGRADE;
        }

        if (mDisableVerificationForUid != INVALID_UID) {
            if (callingUid == mDisableVerificationForUid) {
                params.installFlags |= PackageManager.INSTALL_DISABLE_VERIFICATION;
            } else {
                // Clear the flag if current calling uid doesn't match the requested uid.
                params.installFlags &= ~PackageManager.INSTALL_DISABLE_VERIFICATION;
            }
            // Reset the field as this is a one-off request.
            mDisableVerificationForUid = INVALID_UID;
        } else if ((params.installFlags & ADB_DEV_MODE) != ADB_DEV_MODE) {
            // Only tools under specific conditions (test app installed through ADB, and
            // verification disabled flag specified) can disable verification.
            params.installFlags &= ~PackageManager.INSTALL_DISABLE_VERIFICATION;
        }

        if (Flags.rollbackLifetime()) {
            if (params.rollbackLifetimeMillis > 0) {
                if ((params.installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) == 0) {
                    throw new IllegalArgumentException(
                            "Can't set rollbackLifetimeMillis when rollback is not enabled");
                }
                if (mContext.checkCallingOrSelfPermission(Manifest.permission.MANAGE_ROLLBACKS)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException(
                            "Setting rollback lifetime requires the MANAGE_ROLLBACKS permission");
                }
            } else if (params.rollbackLifetimeMillis < 0) {
                throw new IllegalArgumentException("rollbackLifetimeMillis can't be negative.");
            }
        }

        boolean isApex = (params.installFlags & PackageManager.INSTALL_APEX) != 0;
        if (isApex) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.INSTALL_PACKAGE_UPDATES)
                    == PackageManager.PERMISSION_DENIED
                    && mContext.checkCallingOrSelfPermission(Manifest.permission.INSTALL_PACKAGES)
                    == PackageManager.PERMISSION_DENIED) {
                throw new SecurityException("Not allowed to perform APEX updates");
            }
        } else if (params.isStaged) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.INSTALL_PACKAGES, TAG);
        }

        if (isApex) {
            if (!mApexManager.isApexSupported()) {
                throw new IllegalArgumentException(
                    "This device doesn't support the installation of APEX files");
            }
            if (params.isMultiPackage) {
                throw new IllegalArgumentException("A multi-session can't be set as APEX.");
            }
            if (PackageManagerServiceUtils.isSystemOrRootOrShell(callingUid)
                    || mBypassNextAllowedApexUpdateCheck) {
                params.installFlags |= PackageManager.INSTALL_DISABLE_ALLOWED_APEX_UPDATE_CHECK;
            } else {
                // Only specific APEX updates (installed through ADB, or for CTS tests) can disable
                // allowed APEX update check.
                params.installFlags &= ~PackageManager.INSTALL_DISABLE_ALLOWED_APEX_UPDATE_CHECK;
            }
        }

        if ((params.installFlags & PackageManager.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK) != 0
                && !PackageManagerServiceUtils.isSystemOrRootOrShell(callingUid)
                && !Build.IS_DEBUGGABLE) {
            // If the bypass flag is set, but not running as system root or shell then remove
            // the flag
            params.installFlags &= ~PackageManager.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK;
        }

        params.installFlags &= ~PackageManager.INSTALL_UNARCHIVE;
        if (isArchivingEnabled() && params.appPackageName != null) {
            PackageStateInternal ps = mPm.snapshotComputer().getPackageStateInternal(
                    params.appPackageName, SYSTEM_UID);
            if (ps != null
                    && PackageArchiver.isArchived(ps.getUserStateOrDefault(userId))
                    && PackageArchiver.getResponsibleInstallerPackage(ps)
                            .equals(requestedInstallerPackageName)) {
                params.installFlags |= PackageManager.INSTALL_UNARCHIVE;
            }
        }

        if ((params.installFlags & PackageManager.INSTALL_INSTANT_APP) != 0
                && !PackageManagerServiceUtils.isSystemOrRootOrShell(callingUid)
                && (snapshot.getFlagsForUid(callingUid) & ApplicationInfo.FLAG_SYSTEM)
                == 0) {
            throw new SecurityException(
                    "Only system apps could use the PackageManager.INSTALL_INSTANT_APP flag.");
        }

        if (params.isStaged && !PackageManagerServiceUtils.isSystemOrRootOrShell(callingUid)) {
            if (!mBypassNextStagedInstallerCheck
                    && !isStagedInstallerAllowed(requestedInstallerPackageName)) {
                throw new SecurityException("Installer not allowed to commit staged install");
            }
        }
        if (isApex && !PackageManagerServiceUtils.isSystemOrRootOrShell(callingUid)) {
            if (!mBypassNextStagedInstallerCheck
                    && !isStagedInstallerAllowed(requestedInstallerPackageName)) {
                throw new SecurityException(
                        "Installer not allowed to commit non-staged APEX install");
            }
        }

        mBypassNextStagedInstallerCheck = false;
        mBypassNextAllowedApexUpdateCheck = false;

        if (!params.isMultiPackage) {
            var hasInstallGrantRuntimePermissions = mContext.checkCallingOrSelfPermission(
                    Manifest.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS)
                    == PackageManager.PERMISSION_GRANTED;

            // Only system components can circumvent runtime permissions when installing.
            if ((params.installFlags & PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS) != 0
                    && !hasInstallGrantRuntimePermissions) {
                throw new SecurityException("You need the "
                        + Manifest.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS
                        + " permission to use the"
                        + " PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS flag");
            }

            var permissionStates = params.getPermissionStates();
            if (!permissionStates.isEmpty()) {
                if (!hasInstallGrantRuntimePermissions) {
                    for (int index = 0; index < permissionStates.size(); index++) {
                        var permissionName = permissionStates.keyAt(index);
                        if (!INSTALLER_CHANGEABLE_APP_OP_PERMISSIONS.contains(permissionName)) {
                            throw new SecurityException("You need the "
                                    + Manifest.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS
                                    + " permission to grant runtime permissions for a session");
                        }
                    }
                }
            }

            // Defensively resize giant app icons
            if (params.appIcon != null) {
                final ActivityManager am = (ActivityManager) mContext.getSystemService(
                        Context.ACTIVITY_SERVICE);
                final int iconSize = am.getLauncherLargeIconSize();
                if ((params.appIcon.getWidth() > iconSize * 2)
                        || (params.appIcon.getHeight() > iconSize * 2)) {
                    params.appIcon = Bitmap.createScaledBitmap(params.appIcon, iconSize, iconSize,
                            true);
                }
            }

            switch (params.mode) {
                case SessionParams.MODE_FULL_INSTALL:
                case SessionParams.MODE_INHERIT_EXISTING:
                    break;
                default:
                    throw new IllegalArgumentException("Invalid install mode: " + params.mode);
            }

            // If caller requested explicit location, validity check it, otherwise
            // resolve the best internal or adopted location.
            if ((params.installFlags & PackageManager.INSTALL_INTERNAL) != 0) {
                if (!InstallLocationUtils.fitsOnInternal(mContext, params)) {
                    throw new IOException("No suitable internal storage available");
                }
            } else if ((params.installFlags & PackageManager.INSTALL_FORCE_VOLUME_UUID) != 0) {
                // For now, installs to adopted media are treated as internal from
                // an install flag point-of-view.
                params.installFlags |= PackageManager.INSTALL_INTERNAL;
            } else {
                params.installFlags |= PackageManager.INSTALL_INTERNAL;

                // Resolve best location for install, based on combination of
                // requested install flags, delta size, and manifest settings.
                final long ident = Binder.clearCallingIdentity();
                try {
                    params.volumeUuid = InstallLocationUtils.resolveInstallVolume(mContext, params);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        int requestedInstallerPackageUid = INVALID_UID;
        if (requestedInstallerPackageName != null) {
            requestedInstallerPackageUid = snapshot.getPackageUid(requestedInstallerPackageName,
                    0 /* flags */, userId);
        }
        if (requestedInstallerPackageUid == INVALID_UID) {
            // Requested installer package is invalid, reset it
            requestedInstallerPackageName = null;
        }

        final int sessionId;
        final PackageInstallerSession session;
        synchronized (mSessions) {
            // Check that the installer does not have too many active sessions.
            final int activeCount = getSessionCount(mSessions, callingUid);
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.INSTALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED) {
                if (activeCount >= MAX_ACTIVE_SESSIONS_WITH_PERMISSION) {
                    throw new IllegalStateException(
                            "Too many active sessions for UID " + callingUid);
                }
            } else if (activeCount >= MAX_ACTIVE_SESSIONS_NO_PERMISSION) {
                throw new IllegalStateException(
                        "Too many active sessions for UID " + callingUid);
            }
            final int historicalCount = mHistoricalSessionsByInstaller.get(callingUid);
            if (historicalCount >= MAX_HISTORICAL_SESSIONS) {
                throw new IllegalStateException(
                        "Too many historical sessions for UID " + callingUid);
            }
            final int existingDraftSessionId =
                    getExistingDraftSessionId(requestedInstallerPackageUid, params, userId);

            sessionId = existingDraftSessionId != SessionInfo.INVALID_ID ? existingDraftSessionId
                    : allocateSessionIdLocked();
        }

        final long createdMillis = System.currentTimeMillis();
        // We're staging to exactly one location
        File stageDir = null;
        String stageCid = null;
        if (!params.isMultiPackage) {
            if ((params.installFlags & PackageManager.INSTALL_INTERNAL) != 0) {
                stageDir = buildSessionDir(sessionId, params);
            } else {
                stageCid = buildExternalStageCid(sessionId);
            }
        }

        // reset the force queryable param if it's not called by an approved caller.
        if (params.forceQueryableOverride) {
            if (!PackageManagerServiceUtils.isRootOrShell(callingUid)) {
                params.forceQueryableOverride = false;
            }
        }

        final var dpmi = LocalServices.getService(DevicePolicyManagerInternal.class);
        if (dpmi != null && dpmi.isUserOrganizationManaged(userId)) {
            params.installFlags |= PackageManager.INSTALL_FROM_MANAGED_USER_OR_PROFILE;
        }

        if (isApex || mContext.checkCallingOrSelfPermission(
                Manifest.permission.ENFORCE_UPDATE_OWNERSHIP) == PackageManager.PERMISSION_DENIED) {
            params.installFlags &= ~PackageManager.INSTALL_REQUEST_UPDATE_OWNERSHIP;
        }

        InstallSource installSource = InstallSource.create(installerPackageName,
                originatingPackageName, requestedInstallerPackageName, requestedInstallerPackageUid,
                requestedInstallerPackageName, installerAttributionTag, params.packageSource);
        session = new PackageInstallerSession(mInternalCallback, mContext, mPm, this,
                mSilentUpdatePolicy, mInstallThread.getLooper(), mStagingManager, sessionId,
                userId, callingUid, installSource, params, createdMillis, 0L, stageDir, stageCid,
                null, null, false, false, false, false, null, SessionInfo.INVALID_ID,
                false, false, false, PackageManager.INSTALL_UNKNOWN, "");

        synchronized (mSessions) {
            mSessions.put(sessionId, session);
        }
        mPm.addInstallerPackageName(session.getInstallSource());

        mCallbacks.notifySessionCreated(session.sessionId, session.userId);

        mSettingsWriteRequest.schedule();
        if (LOGD) {
            Slog.d(TAG, "Created session id=" + sessionId + " staged=" + params.isStaged);
        }
        return sessionId;
    }

    int getExistingDraftSessionId(int installerUid,
            @NonNull SessionParams sessionParams, int userId) {
        synchronized (mSessions) {
            return getExistingDraftSessionIdInternal(installerUid, sessionParams, userId);
        }
    }

    @GuardedBy("mSessions")
    private int getExistingDraftSessionIdInternal(int installerUid,
            SessionParams sessionParams, int userId) {
        String appPackageName = sessionParams.appPackageName;
        if (!isArchivingEnabled() || installerUid == INVALID_UID || appPackageName == null) {
            return SessionInfo.INVALID_ID;
        }

        PackageStateInternal ps = mPm.snapshotComputer().getPackageStateInternal(appPackageName,
                SYSTEM_UID);
        if (ps == null || !PackageArchiver.isArchived(ps.getUserStateOrDefault(userId))) {
            return SessionInfo.INVALID_ID;
        }

        // If unarchiveId is present we match based on it. If unarchiveId is missing we
        // choose a draft session too to ensure we don't end up with duplicate sessions
        // if the installer doesn't set this field.
        if (sessionParams.unarchiveId > 0) {
            PackageInstallerSession session = mSessions.get(sessionParams.unarchiveId);
            if (session != null
                    && isValidDraftSession(session, appPackageName, installerUid, userId)) {
                return session.sessionId;
            }

            return SessionInfo.INVALID_ID;
        }

        for (int i = 0; i < mSessions.size(); i++) {
            PackageInstallerSession session = mSessions.valueAt(i);
            if (session != null
                    && isValidDraftSession(session, appPackageName, installerUid, userId)) {
                return session.sessionId;
            }
        }

        return SessionInfo.INVALID_ID;
    }

    private boolean isValidDraftSession(@NonNull PackageInstallerSession session,
            @NonNull String appPackageName, int installerUid, int userId) {
        return (session.getInstallFlags() & PackageManager.INSTALL_UNARCHIVE_DRAFT) != 0
                && appPackageName.equals(session.params.appPackageName)
                && session.userId == userId
                && installerUid == session.getInstallerUid();
    }

    void cleanupDraftIfUnclaimed(int sessionId) {
        synchronized (mSessions) {
            PackageInstallerSession session = mPm.mInstallerService.getSession(sessionId);
            if (session != null && (session.getInstallFlags() & INSTALL_UNARCHIVE_DRAFT) != 0) {
                session.abandon();
            }
        }
    }

    private boolean isStagedInstallerAllowed(String installerName) {
        return SystemConfig.getInstance().getWhitelistedStagedInstallers().contains(installerName);
    }

    @Override
    public void updateSessionAppIcon(int sessionId, Bitmap appIcon) {
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            if (session == null || !isCallingUidOwner(session)) {
                throw new SecurityException("Caller has no access to session " + sessionId);
            }

            // Defensively resize giant app icons
            if (appIcon != null) {
                final ActivityManager am = (ActivityManager) mContext.getSystemService(
                        Context.ACTIVITY_SERVICE);
                final int iconSize = am.getLauncherLargeIconSize();
                if ((appIcon.getWidth() > iconSize * 2)
                        || (appIcon.getHeight() > iconSize * 2)) {
                    appIcon = Bitmap.createScaledBitmap(appIcon, iconSize, iconSize, true);
                }
            }

            session.params.appIcon = appIcon;
            session.params.appIconLastModified = -1;

            mInternalCallback.onSessionBadgingChanged(session);
        }
    }

    @Override
    public void updateSessionAppLabel(int sessionId, String appLabel) {
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            if (session == null || !isCallingUidOwner(session)) {
                throw new SecurityException("Caller has no access to session " + sessionId);
            }
            if (!appLabel.equals(session.params.appLabel)) {
                session.params.appLabel = appLabel;
                mInternalCallback.onSessionBadgingChanged(session);
            }
        }
    }

    @Override
    public void abandonSession(int sessionId) {
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            if (session == null || !isCallingUidOwner(session)) {
                throw new SecurityException("Caller has no access to session " + sessionId);
            }
            session.abandon();
        }
    }

    @Override
    public IPackageInstallerSession openSession(int sessionId) {
        try {
            return openSessionInternal(sessionId);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    private boolean checkOpenSessionAccess(final PackageInstallerSession session) {
        if (session == null
                || (session.getInstallFlags() & PackageManager.INSTALL_UNARCHIVE_DRAFT) != 0) {
            return false;
        }
        if (isCallingUidOwner(session)) {
            return true;
        }
        // Package verifiers have access to openSession for sealed sessions.
        if (session.isSealed() && mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    private PackageInstallerSession openSessionInternal(int sessionId) throws IOException {
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            if (!checkOpenSessionAccess(session)) {
                throw new SecurityException("Caller has no access to session " + sessionId);
            }
            session.open();
            return session;
        }
    }

    @GuardedBy("mSessions")
    private int allocateSessionIdLocked() {
        int n = 0;
        int sessionId;
        do {
            sessionId = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
            if (!mAllocatedSessions.get(sessionId, false)) {
                mAllocatedSessions.put(sessionId, true);
                return sessionId;
            }
        } while (n++ < 32);

        throw new IllegalStateException("Failed to allocate session ID");
    }

    static boolean isStageName(String name) {
        final boolean isFile = name.startsWith("vmdl") && name.endsWith(".tmp");
        final boolean isContainer = name.startsWith("smdl") && name.endsWith(".tmp");
        final boolean isLegacyContainer = name.startsWith("smdl2tmp");
        return isFile || isContainer || isLegacyContainer;
    }

    static int tryParseSessionId(@NonNull String tmpSessionDir)
            throws IllegalArgumentException {
        if (!tmpSessionDir.startsWith("vmdl") || !tmpSessionDir.endsWith(".tmp")) {
            throw new IllegalArgumentException("Not a temporary session directory");
        }
        String sessionId = tmpSessionDir.substring("vmdl".length(),
                tmpSessionDir.length() - ".tmp".length());
        return Integer.parseInt(sessionId);
    }

    private static boolean isValidPackageName(@NonNull String packageName) {
        if (packageName.length() > SessionParams.MAX_PACKAGE_NAME_LENGTH) {
            return false;
        }
        // "android" is a valid package name
        var errorMessage = FrameworkParsingPackageUtils.validateName(
                packageName, /* requireSeparator= */ false, /* requireFilename */ true);
        if (errorMessage != null) {
            return false;
        }
        return true;
    }

    private File getTmpSessionDir(String volumeUuid) {
        return Environment.getDataAppDirectory(volumeUuid);
    }

    private File buildTmpSessionDir(int sessionId, String volumeUuid) {
        final File sessionStagingDir = getTmpSessionDir(volumeUuid);
        return new File(sessionStagingDir, "vmdl" + sessionId + ".tmp");
    }

    private File buildSessionDir(int sessionId, SessionParams params) {
        if (params.isStaged || (params.installFlags & PackageManager.INSTALL_APEX) != 0) {
            final File sessionStagingDir = Environment.getDataStagingDirectory(params.volumeUuid);
            return new File(sessionStagingDir, "session_" + sessionId);
        }
        final File result = buildTmpSessionDir(sessionId, params.volumeUuid);
        if (DEBUG && !Objects.equals(tryParseSessionId(result.getName()), sessionId)) {
            throw new RuntimeException(
                    "session folder format is off: " + result.getName() + " (" + sessionId + ")");
        }
        return result;
    }

    static void prepareStageDir(File stageDir) throws IOException {
        if (stageDir.exists()) {
            throw new IOException("Session dir already exists: " + stageDir);
        }

        try {
            Os.mkdir(stageDir.getAbsolutePath(), 0775);
            Os.chmod(stageDir.getAbsolutePath(), 0775);
        } catch (ErrnoException e) {
            // This purposefully throws if directory already exists
            throw new IOException("Failed to prepare session dir: " + stageDir, e);
        }

        if (!SELinux.restorecon(stageDir)) {
            String path = stageDir.getCanonicalPath();
            String ctx = SELinux.fileSelabelLookup(path);
            boolean success = SELinux.setFileContext(path, ctx);
            Slog.e(TAG,
                    "Failed to SELinux.restorecon session dir, path: [" + path + "], ctx: [" + ctx
                            + "]. Retrying via SELinux.fileSelabelLookup/SELinux.setFileContext: "
                            + (success ? "SUCCESS" : "FAILURE"));
            if (!success) {
                throw new IOException("Failed to restorecon session dir: " + stageDir);
            }
        }
    }

    private String buildExternalStageCid(int sessionId) {
        return "smdl" + sessionId + ".tmp";
    }

    private boolean shouldFilterSession(@NonNull Computer snapshot, int uid, SessionInfo info) {
        if (info == null) {
            return false;
        }
        return uid != info.getInstallerUid()
                && !snapshot.canQueryPackage(uid, info.getAppPackageName());
    }

    @Override
    public SessionInfo getSessionInfo(int sessionId) {
        final int callingUid = Binder.getCallingUid();
        final SessionInfo result;
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            result = (session != null && !(session.isStaged() && session.isDestroyed()))
                    ? session.generateInfoForCaller(true /* includeIcon */, callingUid)
                    : null;
        }
        return shouldFilterSession(mPm.snapshotComputer(), callingUid, result) ? null : result;
    }

    @Override
    public ParceledListSlice<SessionInfo> getStagedSessions() {
        final int callingUid = Binder.getCallingUid();
        final List<SessionInfo> result = new ArrayList<>();
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (session.isStaged() && !session.isDestroyed()) {
                    result.add(session.generateInfoForCaller(false /* includeIcon */, callingUid));
                }
            }
        }
        final Computer snapshot = mPm.snapshotComputer();
        result.removeIf(info -> shouldFilterSession(snapshot, callingUid, info));
        return new ParceledListSlice<>(result);
    }

    @Override
    public ParceledListSlice<SessionInfo> getAllSessions(int userId) {
        final int callingUid = Binder.getCallingUid();
        final Computer snapshot = mPm.snapshotComputer();
        snapshot.enforceCrossUserPermission(callingUid, userId, true, false, "getAllSessions");

        final List<SessionInfo> result = new ArrayList<>();
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (session.userId == userId && !session.hasParentSessionId()
                        && !(session.isStaged() && session.isDestroyed())) {
                    result.add(session.generateInfoForCaller(false /* includeIcon */, callingUid));
                }
            }
        }
        result.removeIf(info -> shouldFilterSession(snapshot, callingUid, info));
        return new ParceledListSlice<>(result);
    }

    @Override
    public ParceledListSlice<SessionInfo> getMySessions(String installerPackageName, int userId) {
        final Computer snapshot = mPm.snapshotComputer();
        final int callingUid = Binder.getCallingUid();
        snapshot.enforceCrossUserPermission(callingUid, userId, true, false, "getMySessions");
        mAppOps.checkPackage(callingUid, installerPackageName);

        final List<SessionInfo> result = new ArrayList<>();
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);

                SessionInfo info =
                        session.generateInfoForCaller(false /*withIcon*/, SYSTEM_UID);
                if (Objects.equals(info.getInstallerPackageName(), installerPackageName)
                        && session.userId == userId && !session.hasParentSessionId()
                        && isCallingUidOwner(session)
                        && (session.getInstallFlags() & PackageManager.INSTALL_UNARCHIVE_DRAFT)
                            == 0) {
                    result.add(info);
                }
            }
        }
        return new ParceledListSlice<>(result);
    }

    ParceledListSlice<SessionInfo> getHistoricalSessions(int userId) {
        final int callingUid = Binder.getCallingUid();
        final Computer snapshot = mPm.snapshotComputer();
        snapshot.enforceCrossUserPermission(callingUid, userId, true, false, "getAllSessions");

        final List<SessionInfo> result = new ArrayList<>();
        synchronized (mSessions) {
            for (int i = 0; i < mHistoricalSessions.size(); i++) {
                final PackageInstallerHistoricalSession session = mHistoricalSessions.get(i);
                if (userId == UserHandle.USER_ALL || session.userId == userId) {
                    result.add(session.generateInfo());
                }
            }
        }
        result.removeIf(info -> shouldFilterSession(snapshot, callingUid, info));
        return new ParceledListSlice<>(result);
    }

    @Override
    public void uninstall(VersionedPackage versionedPackage, String callerPackageName, int flags,
                IntentSender statusReceiver, int userId) {
        uninstall(
                versionedPackage,
                callerPackageName,
                flags,
                statusReceiver,
                userId,
                Binder.getCallingUid());
    }

    void uninstall(VersionedPackage versionedPackage, String callerPackageName, int flags,
            IntentSender statusReceiver, int userId, int callingUid) {
        final Computer snapshot = mPm.snapshotComputer();
        snapshot.enforceCrossUserPermission(callingUid, userId, true, true, "uninstall");
        if (!PackageManagerServiceUtils.isRootOrShell(callingUid)) {
            mAppOps.checkPackage(callingUid, callerPackageName);
        }

        // Check whether the caller is device owner or affiliated profile owner, in which case we do
        // it silently.
        DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        final boolean canSilentlyInstallPackage =
                dpmi != null && dpmi.canSilentlyInstallPackage(callerPackageName, callingUid);

        final PackageDeleteObserverAdapter adapter = new PackageDeleteObserverAdapter(mContext,
                statusReceiver, versionedPackage.getPackageName(),
                canSilentlyInstallPackage, userId);
        final boolean shouldShowConfirmationDialog =
                (flags & PackageManager.DELETE_SHOW_DIALOG) != 0;
        if (!shouldShowConfirmationDialog
                && mContext.checkCallingOrSelfPermission(Manifest.permission.DELETE_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED) {
            // Sweet, call straight through!
            mPm.deletePackageVersioned(versionedPackage, adapter.getBinder(), userId, flags);
        } else if (!shouldShowConfirmationDialog && canSilentlyInstallPackage) {
            // Allow the device owner and affiliated profile owner to silently delete packages
            // Need to clear the calling identity to get DELETE_PACKAGES permission
            final long ident = Binder.clearCallingIdentity();
            try {
                mPm.deletePackageVersioned(versionedPackage, adapter.getBinder(), userId, flags);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            DevicePolicyEventLogger
                    .createEvent(DevicePolicyEnums.UNINSTALL_PACKAGE)
                    .setAdmin(callerPackageName)
                    .write();
        } else {
            ApplicationInfo appInfo = snapshot.getApplicationInfo(callerPackageName, 0, userId);
            if (appInfo.targetSdkVersion >= Build.VERSION_CODES.P) {
                mContext.enforceCallingOrSelfPermission(Manifest.permission.REQUEST_DELETE_PACKAGES,
                        null);
            }

            // Take a short detour to confirm with user
            final Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
            intent.setData(Uri.fromParts("package", versionedPackage.getPackageName(), null));
            intent.putExtra(PackageInstaller.EXTRA_CALLBACK,
                    new PackageManager.UninstallCompleteCallback(adapter.getBinder().asBinder()));
            if ((flags & PackageManager.DELETE_ARCHIVE) != 0) {
                // Delete flags are passed to the uninstaller activity so it can be preserved
                // in the follow-up uninstall operation after the user confirmation
                intent.putExtra(PackageInstaller.EXTRA_DELETE_FLAGS, flags);
            }
            adapter.onUserActionRequired(intent);
        }
    }

    @Override
    public void uninstallExistingPackage(VersionedPackage versionedPackage,
            String callerPackageName, IntentSender statusReceiver, int userId) {
        final int callingUid = Binder.getCallingUid();
        mContext.enforceCallingOrSelfPermission(Manifest.permission.DELETE_PACKAGES, null);
        final Computer snapshot = mPm.snapshotComputer();
        snapshot.enforceCrossUserPermission(callingUid, userId, true, true, "uninstall");
        if (!PackageManagerServiceUtils.isRootOrShell(callingUid)) {
            mAppOps.checkPackage(callingUid, callerPackageName);
        }

        final PackageDeleteObserverAdapter adapter = new PackageDeleteObserverAdapter(mContext,
                statusReceiver, versionedPackage.getPackageName(), false, userId);
        mPm.deleteExistingPackageAsUser(versionedPackage, adapter.getBinder(), userId);
    }

    @Override
    public void installExistingPackage(String packageName, int installFlags, int installReason,
            IntentSender statusReceiver, int userId, List<String> allowListedPermissions) {

        var result = mPm.installExistingPackageAsUser(packageName, userId,
                installFlags, installReason, allowListedPermissions, statusReceiver);

        int returnCode = result.first;
        IntentSender onCompleteSender = result.second;
        if (onCompleteSender != null) {
            InstallPackageHelper.onInstallComplete(returnCode, mContext, onCompleteSender);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.INSTALL_PACKAGES)
    @Override
    public void setPermissionsResult(int sessionId, boolean accepted) {
        setPermissionsResult_enforcePermission();

        synchronized (mSessions) {
            PackageInstallerSession session = mSessions.get(sessionId);
            if (session != null) {
                session.setPermissionsResult(accepted);
            }
        }
    }

    private boolean isValidForInstallConstraints(PackageStateInternal ps,
            String installerPackageName, int installerUid, String packageName) {
        final var snapshot = mPm.snapshotComputer();
        final var isSelfUpdatePermissionGranted =
                (snapshot.checkUidPermission(android.Manifest.permission.INSTALL_SELF_UPDATES,
                        installerUid) == PackageManager.PERMISSION_GRANTED);
        final var isSelfUpdateAllowed = isSelfUpdatePermissionGranted && TextUtils.equals(
                packageName, installerPackageName);
        return TextUtils.equals(ps.getInstallSource().mInstallerPackageName, installerPackageName)
                || TextUtils.equals(ps.getInstallSource().mUpdateOwnerPackageName,
                installerPackageName) || isSelfUpdateAllowed;
    }

    private CompletableFuture<InstallConstraintsResult> checkInstallConstraintsInternal(
            String installerPackageName, List<String> packageNames,
            InstallConstraints constraints, long timeoutMillis) {
        Objects.requireNonNull(packageNames);
        Objects.requireNonNull(constraints);

        final var snapshot = mPm.snapshotComputer();
        final int callingUid = Binder.getCallingUid();
        final var callingPackageName = snapshot.getNameForUid(callingUid);
        if (!TextUtils.equals(callingPackageName, installerPackageName)) {
            throw new SecurityException("The installerPackageName set by the caller doesn't match "
                    + "the caller's own package name.");
        }
        if (!PackageManagerServiceUtils.isSystemOrRootOrShell(callingUid)) {
            for (var packageName : packageNames) {
                var ps = snapshot.getPackageStateInternal(packageName);
                if (ps == null || !isValidForInstallConstraints(ps, installerPackageName,
                        callingUid, packageName)) {
                    throw new SecurityException("Caller has no access to package " + packageName);
                }
            }
        }

        return mGentleUpdateHelper.checkInstallConstraints(
                packageNames, constraints, timeoutMillis);
    }

    @Override
    public void checkInstallConstraints(String installerPackageName, List<String> packageNames,
            InstallConstraints constraints, RemoteCallback callback) {
        Objects.requireNonNull(callback);
        var future = checkInstallConstraintsInternal(
                installerPackageName, packageNames, constraints, /*timeoutMillis=*/0);
        future.thenAccept(result -> {
            var b = new Bundle();
            b.putParcelable("result", result);
            callback.sendResult(b);
        });
    }

    @Override
    public void waitForInstallConstraints(String installerPackageName, List<String> packageNames,
            InstallConstraints constraints, IntentSender callback, long timeoutMillis) {
        Objects.requireNonNull(callback);
        if (timeoutMillis < 0 || timeoutMillis > MAX_INSTALL_CONSTRAINTS_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("Invalid timeoutMillis=" + timeoutMillis);
        }
        var future = checkInstallConstraintsInternal(
                installerPackageName, packageNames, constraints, timeoutMillis);
        future.thenAccept(result -> {
            final var intent = new Intent();
            intent.putExtra(Intent.EXTRA_PACKAGES, packageNames.toArray(new String[0]));
            intent.putExtra(PackageInstaller.EXTRA_INSTALL_CONSTRAINTS, constraints);
            intent.putExtra(PackageInstaller.EXTRA_INSTALL_CONSTRAINTS_RESULT, result);
            try {
                final BroadcastOptions options = BroadcastOptions.makeBasic();
                options.setPendingIntentBackgroundActivityLaunchAllowed(false);
                callback.sendIntent(mContext, 0, intent, null /* onFinished*/,
                        null /* handler */, null /* requiredPermission */, options.toBundle());
            } catch (SendIntentException ignore) {
            }
        });
    }

    @Override
    public void registerCallback(IPackageInstallerCallback callback, int userId) {
        final Computer snapshot = mPm.snapshotComputer();
        snapshot.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false,
                "registerCallback");
        registerCallback(callback, eventUserId -> userId == eventUserId);
    }

    /**
     * Assume permissions already checked and caller's identity cleared
     */
    public void registerCallback(IPackageInstallerCallback callback, IntPredicate userCheck) {
        mCallbacks.register(callback, new BroadcastCookie(Binder.getCallingUid(), userCheck));
    }

    @Override
    public void unregisterCallback(IPackageInstallerCallback callback) {
        mCallbacks.unregister(callback);
    }

    @Override
    public PackageInstallerSession getSession(int sessionId) {
        synchronized (mSessions) {
            return mSessions.get(sessionId);
        }
    }

    @Override
    public PackageSessionVerifier getSessionVerifier() {
        return mSessionVerifier;
    }

    @Override
    public GentleUpdateHelper getGentleUpdateHelper() {
        return mGentleUpdateHelper;
    }

    @Override
    public void bypassNextStagedInstallerCheck(boolean value) {
        if (!PackageManagerServiceUtils.isSystemOrRootOrShell(Binder.getCallingUid())) {
            throw new SecurityException("Caller not allowed to bypass staged installer check");
        }
        mBypassNextStagedInstallerCheck = value;
    }

    @Override
    public void bypassNextAllowedApexUpdateCheck(boolean value) {
        if (!PackageManagerServiceUtils.isSystemOrRootOrShell(Binder.getCallingUid())) {
            throw new SecurityException("Caller not allowed to bypass allowed apex update check");
        }
        mBypassNextAllowedApexUpdateCheck = value;
    }

    @Override
    public void disableVerificationForUid(int uid) {
        if (!PackageManagerServiceUtils.isSystemOrRootOrShell(Binder.getCallingUid())) {
            throw new SecurityException("Operation not allowed for caller");
        }
        mDisableVerificationForUid = uid;
    }

    /**
     * Set an installer to allow for the unlimited silent updates.
     */
    @Override
    public void setAllowUnlimitedSilentUpdates(@Nullable String installerPackageName) {
        if (!PackageManagerServiceUtils.isSystemOrRootOrShell(Binder.getCallingUid())) {
            throw new SecurityException("Caller not allowed to unlimite silent updates");
        }
        mSilentUpdatePolicy.setAllowUnlimitedSilentUpdates(installerPackageName);
    }

    /**
     * Set the silent updates throttle time in seconds.
     */
    @Override
    public void setSilentUpdatesThrottleTime(long throttleTimeInSeconds) {
        if (!PackageManagerServiceUtils.isSystemOrRootOrShell(Binder.getCallingUid())) {
            throw new SecurityException("Caller not allowed to set silent updates throttle time");
        }
        mSilentUpdatePolicy.setSilentUpdatesThrottleTime(throttleTimeInSeconds);
    }

    @Override
    public void requestArchive(
            @NonNull String packageName,
            @NonNull String callerPackageName,
            @NonNull IntentSender intentSender,
            @NonNull UserHandle userHandle,
            @DeleteFlags int flags) {
        mPackageArchiver.requestArchive(packageName, callerPackageName, intentSender,
                userHandle, flags);
    }

    @Override
    public void requestUnarchive(
            @NonNull String packageName,
            @NonNull String callerPackageName,
            @NonNull IntentSender statusReceiver,
            @NonNull UserHandle userHandle) {
        mPackageArchiver.requestUnarchive(packageName, callerPackageName, statusReceiver,
                userHandle);
    }

    @Override
    public void installPackageArchived(
            @NonNull ArchivedPackageParcel archivedPackageParcel,
            @NonNull SessionParams params,
            @NonNull IntentSender statusReceiver,
            @NonNull String installerPackageName,
            @NonNull UserHandle userHandle) {
        Objects.requireNonNull(params);
        Objects.requireNonNull(archivedPackageParcel);
        Objects.requireNonNull(statusReceiver);
        Objects.requireNonNull(installerPackageName);
        Objects.requireNonNull(userHandle);

        final int callingUid = Binder.getCallingUid();
        final int userId = userHandle.getIdentifier();
        final Computer snapshot = mPm.snapshotComputer();
        snapshot.enforceCrossUserPermission(callingUid, userId, true, true,
                "installPackageArchived");

        if (mContext.checkCallingOrSelfPermission(Manifest.permission.INSTALL_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("You need the "
                    + "com.android.permission.INSTALL_PACKAGES permission "
                    + "to request archived package install");
        }

        params.installFlags |= PackageManager.INSTALL_ARCHIVED;
        if (params.dataLoaderParams != null) {
            throw new IllegalArgumentException(
                    "Incompatible session param: dataLoaderParams has to be null");
        }

        params.setDataLoaderParams(
                PackageManagerShellCommandDataLoader.getStreamingDataLoaderParams(null));
        var metadata = PackageManagerShellCommandDataLoader.Metadata.forArchived(
                archivedPackageParcel);

        // Create and commit install archived session.
        // Session belongs to the system_server and would not appear anywhere in the Public APIs.
        Binder.withCleanCallingIdentity(() -> {
            PackageInstallerSession session = null;
            try {
                var sessionId = createSessionInternal(params, installerPackageName, null
                        /*installerAttributionTag*/, Binder.getCallingUid(), userId);
                session = openSessionInternal(sessionId);
                session.addFile(LOCATION_DATA_APP, "base", 0 /*lengthBytes*/,
                        metadata.toByteArray(), null /*signature*/);
                session.commit(statusReceiver, false /*forTransfer*/);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            } finally {
                if (session != null) {
                    session.close();
                }
            }
        });
    }

    @Override
    public void reportUnarchivalStatus(
            int unarchiveId,
            @UnarchivalStatus int status,
            long requiredStorageBytes,
            @Nullable PendingIntent userActionIntent,
            @NonNull UserHandle userHandle) {
        verifyReportUnarchiveStatusInput(
                status, requiredStorageBytes, userActionIntent, userHandle);

        int userId = userHandle.getIdentifier();
        int binderUid = Binder.getCallingUid();

        synchronized (mSessions) {
            PackageInstallerSession session = mSessions.get(unarchiveId);
            if (session == null || session.userId != userId
                    || session.params.appPackageName == null) {
                throw new ParcelableException(new PackageManager.NameNotFoundException(
                        TextUtils.formatSimple(
                                "No valid session with unarchival ID %s found for user %s.",
                                unarchiveId, userId)));
            }

            if (!isCallingUidOwner(session)) {
                throw new SecurityException(TextUtils.formatSimple(
                        "The caller UID %s does not have access to the session with unarchiveId "
                                + "%d.",
                        binderUid, unarchiveId));
            }

            IntentSender unarchiveIntentSender = session.params.unarchiveIntentSender;
            if (unarchiveIntentSender == null) {
                throw new IllegalStateException(
                        TextUtils.formatSimple(
                                "Unarchival status for ID %s has already been set or a "
                                        + "session has been created for it already by the "
                                        + "caller.",
                                unarchiveId));
            }

            // Execute expensive calls outside the sync block.
            mPm.mHandler.post(
                    () -> mPackageArchiver.notifyUnarchivalListener(status,
                            session.getInstallerPackageName(),
                            session.params.appPackageName, requiredStorageBytes, userActionIntent,
                            unarchiveIntentSender, userId));
            session.params.unarchiveIntentSender = null;
            if (status != UNARCHIVAL_OK) {
                Binder.withCleanCallingIdentity(session::abandon);
            }
        }
    }

    private static void verifyReportUnarchiveStatusInput(int status, long requiredStorageBytes,
            @Nullable PendingIntent userActionIntent,
            @NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        if (status == UNARCHIVAL_ERROR_USER_ACTION_NEEDED) {
            Objects.requireNonNull(userActionIntent);
        }
        if (status == UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE && requiredStorageBytes <= 0) {
            throw new IllegalStateException(
                    "Insufficient storage error set, but requiredStorageBytes unspecified.");
        }
        if (status != UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE && requiredStorageBytes > 0) {
            throw new IllegalStateException(
                    TextUtils.formatSimple("requiredStorageBytes set, but error is %s.", status)
            );
        }
        if (!List.of(
                UNARCHIVAL_OK,
                UNARCHIVAL_ERROR_USER_ACTION_NEEDED,
                UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE,
                UNARCHIVAL_ERROR_NO_CONNECTIVITY,
                UNARCHIVAL_ERROR_INSTALLER_DISABLED,
                UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED,
                UNARCHIVAL_GENERIC_ERROR).contains(status)) {
            throw new IllegalStateException("Invalid status code passed " + status);
        }
    }

    private static int getSessionCount(SparseArray<PackageInstallerSession> sessions,
            int installerUid) {
        int count = 0;
        final int size = sessions.size();
        for (int i = 0; i < size; i++) {
            final PackageInstallerSession session = sessions.valueAt(i);
            if (session.getInstallerUid() == installerUid) {
                count++;
            }
        }
        return count;
    }

    private boolean isCallingUidOwner(PackageInstallerSession session) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid == Process.ROOT_UID) {
            return true;
        } else {
            return (session != null) && (callingUid == session.getInstallerUid());
        }
    }

    private boolean shouldFilterSession(@NonNull Computer snapshot, int uid, int sessionId) {
        final PackageInstallerSession session = getSession(sessionId);
        if (session == null) {
            return false;
        }
        return uid != session.getInstallerUid()
                && !snapshot.canQueryPackage(uid, session.getPackageName());
    }

    static class PackageDeleteObserverAdapter extends PackageDeleteObserver {
        private final Context mContext;
        private final IntentSender mTarget;
        private final String mPackageName;
        private final Notification mNotification;

        public PackageDeleteObserverAdapter(Context context, IntentSender target,
                String packageName, boolean showNotification, int userId) {
            mContext = context;
            mTarget = target;
            mPackageName = packageName;
            if (showNotification) {
                mNotification = buildSuccessNotification(mContext,
                        getDeviceOwnerDeletedPackageMsg(),
                        packageName,
                        userId);
            } else {
                mNotification = null;
            }
        }

        private String getDeviceOwnerDeletedPackageMsg() {
            final long ident = Binder.clearCallingIdentity();
            try {
                DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
                return dpm.getResources().getString(PACKAGE_DELETED_BY_DO,
                        () -> mContext.getString(R.string.package_deleted_device_owner));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onUserActionRequired(Intent intent) {
            if (mTarget == null) {
                return;
            }
            final Intent fillIn = new Intent();
            fillIn.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, mPackageName);
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_PENDING_USER_ACTION);
            fillIn.putExtra(Intent.EXTRA_INTENT, intent);
            try {
                final BroadcastOptions options = BroadcastOptions.makeBasic();
                options.setPendingIntentBackgroundActivityLaunchAllowed(false);
                mTarget.sendIntent(mContext, 0, fillIn, null /* onFinished*/,
                        null /* handler */, null /* requiredPermission */, options.toBundle());
            } catch (SendIntentException ignored) {
            }
        }

        @Override
        public void onPackageDeleted(String basePackageName, int returnCode, String msg) {
            if (PackageManager.DELETE_SUCCEEDED == returnCode && mNotification != null) {
                NotificationManager notificationManager = (NotificationManager)
                        mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.notify(basePackageName,
                        SystemMessage.NOTE_PACKAGE_STATE,
                        mNotification);
            }
            if (mTarget == null) {
                return;
            }
            final Intent fillIn = new Intent();
            fillIn.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, mPackageName);
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS,
                    PackageManager.deleteStatusToPublicStatus(returnCode));
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,
                    PackageManager.deleteStatusToString(returnCode, msg));
            fillIn.putExtra(PackageInstaller.EXTRA_LEGACY_STATUS, returnCode);
            try {
                final BroadcastOptions options = BroadcastOptions.makeBasic();
                options.setPendingIntentBackgroundActivityLaunchAllowed(false);
                mTarget.sendIntent(mContext, 0, fillIn, null /* onFinished*/,
                        null /* handler */, null /* requiredPermission */, options.toBundle());
            } catch (SendIntentException ignored) {
            }
        }
    }

    /**
     * Build a notification for package installation / deletion by device owners that is shown if
     * the operation succeeds.
     */
    static Notification buildSuccessNotification(Context context, String contentText,
            String basePackageName, int userId) {
        PackageInfo packageInfo = null;
        try {
            packageInfo = AppGlobals.getPackageManager().getPackageInfo(
                    basePackageName, PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, userId);
        } catch (RemoteException ignored) {
        }
        if (packageInfo == null || packageInfo.applicationInfo == null) {
            Slog.w(TAG, "Notification not built for package: " + basePackageName);
            return null;
        }
        PackageManager pm = context.getPackageManager();
        Bitmap packageIcon = ImageUtils.buildScaledBitmap(
                packageInfo.applicationInfo.loadIcon(pm),
                context.getResources().getDimensionPixelSize(
                        android.R.dimen.notification_large_icon_width),
                context.getResources().getDimensionPixelSize(
                        android.R.dimen.notification_large_icon_height));
        CharSequence packageLabel = packageInfo.applicationInfo.loadLabel(pm);
        return new Notification.Builder(context, SystemNotificationChannels.DEVICE_ADMIN)
                .setSmallIcon(R.drawable.ic_check_circle_24px)
                .setColor(context.getResources().getColor(
                        R.color.system_notification_accent_color))
                .setContentTitle(packageLabel)
                .setContentText(contentText)
                .setStyle(new Notification.BigTextStyle().bigText(contentText))
                .setLargeIcon(packageIcon)
                .build();
    }

    public static <E> ArraySet<E> newArraySet(E... elements) {
        final ArraySet<E> set = new ArraySet<E>();
        if (elements != null) {
            set.ensureCapacity(elements.length);
            Collections.addAll(set, elements);
        }
        return set;
    }

    private static final class BroadcastCookie {
        public final int callingUid;
        public final IntPredicate userCheck;

        BroadcastCookie(int callingUid, IntPredicate userCheck) {
            this.callingUid = callingUid;
            this.userCheck = userCheck;
        }
    }

    private class Callbacks extends Handler {
        private static final int MSG_SESSION_CREATED = 1;
        private static final int MSG_SESSION_BADGING_CHANGED = 2;
        private static final int MSG_SESSION_ACTIVE_CHANGED = 3;
        private static final int MSG_SESSION_PROGRESS_CHANGED = 4;
        private static final int MSG_SESSION_FINISHED = 5;

        private final RemoteCallbackList<IPackageInstallerCallback>
                mCallbacks = new RemoteCallbackList<>();

        public Callbacks(Looper looper) {
            super(looper);
        }

        public void register(IPackageInstallerCallback callback, BroadcastCookie cookie) {
            mCallbacks.register(callback, cookie);
        }

        public void unregister(IPackageInstallerCallback callback) {
            mCallbacks.unregister(callback);
        }

        @Override
        public void handleMessage(Message msg) {
            final int sessionId = msg.arg1;
            final int userId = msg.arg2;
            final int n = mCallbacks.beginBroadcast();
            final Computer snapshot = mPm.snapshotComputer();
            for (int i = 0; i < n; i++) {
                final IPackageInstallerCallback callback = mCallbacks.getBroadcastItem(i);
                final BroadcastCookie cookie = (BroadcastCookie) mCallbacks.getBroadcastCookie(i);
                if (cookie.userCheck.test(userId)
                        && !shouldFilterSession(snapshot, cookie.callingUid, sessionId)) {
                    try {
                        invokeCallback(callback, msg);
                    } catch (RemoteException ignored) {
                    }
                }
            }
            mCallbacks.finishBroadcast();
        }

        private void invokeCallback(IPackageInstallerCallback callback, Message msg)
                throws RemoteException {
            final int sessionId = msg.arg1;
            switch (msg.what) {
                case MSG_SESSION_CREATED:
                    callback.onSessionCreated(sessionId);
                    break;
                case MSG_SESSION_BADGING_CHANGED:
                    callback.onSessionBadgingChanged(sessionId);
                    break;
                case MSG_SESSION_ACTIVE_CHANGED:
                    callback.onSessionActiveChanged(sessionId, (boolean) msg.obj);
                    break;
                case MSG_SESSION_PROGRESS_CHANGED:
                    callback.onSessionProgressChanged(sessionId, (float) msg.obj);
                    break;
                case MSG_SESSION_FINISHED:
                    callback.onSessionFinished(sessionId, (boolean) msg.obj);
                    break;
            }
        }

        private void notifySessionCreated(int sessionId, int userId) {
            obtainMessage(MSG_SESSION_CREATED, sessionId, userId).sendToTarget();
        }

        private void notifySessionBadgingChanged(int sessionId, int userId) {
            obtainMessage(MSG_SESSION_BADGING_CHANGED, sessionId, userId).sendToTarget();
        }

        private void notifySessionActiveChanged(int sessionId, int userId, boolean active) {
            obtainMessage(MSG_SESSION_ACTIVE_CHANGED, sessionId, userId, active).sendToTarget();
        }

        private void notifySessionProgressChanged(int sessionId, int userId, float progress) {
            obtainMessage(MSG_SESSION_PROGRESS_CHANGED, sessionId, userId, progress).sendToTarget();
        }

        public void notifySessionFinished(int sessionId, int userId, boolean success) {
            obtainMessage(MSG_SESSION_FINISHED, sessionId, userId, success).sendToTarget();
        }
    }

    static class ParentChildSessionMap {
        private final TreeMap<PackageInstallerSession, TreeSet<PackageInstallerSession>>
                mSessionMap;

        private final Comparator<PackageInstallerSession> mSessionCreationComparator =
                Comparator.comparingLong(
                        (PackageInstallerSession sess) -> sess != null ? sess.createdMillis : -1)
                        .thenComparingInt(sess -> sess != null ? sess.sessionId : -1);

        ParentChildSessionMap() {
            mSessionMap = new TreeMap<>(mSessionCreationComparator);
        }

        boolean containsSession() {
            return !(mSessionMap.isEmpty());
        }

        private void addParentSession(PackageInstallerSession session) {
            if (!mSessionMap.containsKey(session)) {
                mSessionMap.put(session, new TreeSet<>(mSessionCreationComparator));
            }
        }

        private void addChildSession(PackageInstallerSession session,
                PackageInstallerSession parentSession) {
            addParentSession(parentSession);
            mSessionMap.get(parentSession).add(session);
        }

        void addSession(PackageInstallerSession session,
                PackageInstallerSession parentSession) {
            if (session.hasParentSessionId()) {
                addChildSession(session, parentSession);
            } else {
                addParentSession(session);
            }
        }

        void dump(String tag, IndentingPrintWriter pw) {
            pw.println(tag + " install sessions:");
            pw.increaseIndent();

            for (Map.Entry<PackageInstallerSession, TreeSet<PackageInstallerSession>> entry
                    : mSessionMap.entrySet()) {
                PackageInstallerSession parentSession = entry.getKey();
                if (parentSession != null) {
                    pw.print(tag + " ");
                    parentSession.dump(pw);
                    pw.println();
                    pw.increaseIndent();
                }

                for (PackageInstallerSession childSession : entry.getValue()) {
                    pw.print(tag + " Child ");
                    childSession.dump(pw);
                    pw.println();
                }

                pw.decreaseIndent();
            }

            pw.println();
            pw.decreaseIndent();
        }
    }

    void dump(IndentingPrintWriter pw) {
        synchronized (mSessions) {
            ParentChildSessionMap activeSessionMap = new ParentChildSessionMap();
            ParentChildSessionMap orphanedChildSessionMap = new ParentChildSessionMap();
            ParentChildSessionMap finalizedSessionMap = new ParentChildSessionMap();

            int N = mSessions.size();
            for (int i = 0; i < N; i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);

                final PackageInstallerSession rootSession = session.hasParentSessionId()
                        ? getSession(session.getParentSessionId())
                        : session;
                // Do not print orphaned child sessions as active install sessions
                if (rootSession == null) {
                    orphanedChildSessionMap.addSession(session, rootSession);
                    continue;
                }

                // Do not print finalized staged session as active install sessions
                if (rootSession.isStagedAndInTerminalState()) {
                    finalizedSessionMap.addSession(session, rootSession);
                    continue;
                }

                activeSessionMap.addSession(session, rootSession);
            }

            activeSessionMap.dump("Active", pw);

            if (orphanedChildSessionMap.containsSession()) {
                // Presence of orphaned sessions indicate leak in cleanup for multi-package and
                // should be cleaned up.
                orphanedChildSessionMap.dump("Orphaned", pw);
            }

            finalizedSessionMap.dump("Finalized", pw);

            pw.println("Historical install sessions:");
            pw.increaseIndent();
            N = mHistoricalSessions.size();
            for (int i = 0; i < N; i++) {
                mHistoricalSessions.get(i).dump(pw);
                pw.println();
            }
            pw.println();
            pw.decreaseIndent();

            pw.println("Legacy install sessions:");
            pw.increaseIndent();
            pw.println(mLegacySessions.toString());
            pw.println();
            pw.decreaseIndent();
        }
        mSilentUpdatePolicy.dump(pw);
        mGentleUpdateHelper.dump(pw);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public class InternalCallback {
        public void onSessionBadgingChanged(PackageInstallerSession session) {
            mCallbacks.notifySessionBadgingChanged(session.sessionId, session.userId);
            mSettingsWriteRequest.schedule();
        }

        public void onSessionActiveChanged(PackageInstallerSession session, boolean active) {
            mCallbacks.notifySessionActiveChanged(session.sessionId, session.userId,
                    active);
        }

        public void onSessionProgressChanged(PackageInstallerSession session, float progress) {
            mCallbacks.notifySessionProgressChanged(session.sessionId, session.userId,
                    progress);
        }

        public void onSessionChanged(PackageInstallerSession session) {
            session.markUpdated();
            mSettingsWriteRequest.schedule();
            // TODO(b/210359798): Remove the session.isStaged() check. Some apps assume this
            // broadcast is sent by only staged sessions and call isStagedSessionApplied() without
            // checking if it is a staged session or not and cause exception.
            if (mOkToSendBroadcasts && !session.isDestroyed() && session.isStaged()) {
                // we don't scrub the data here as this is sent only to the installer several
                // privileged system packages
                sendSessionUpdatedBroadcast(
                        session.generateInfoForCaller(false/*icon*/, SYSTEM_UID),
                        session.userId);
            }
        }

        public void onSessionFinished(final PackageInstallerSession session, boolean success) {
            if (success) {
                // There is a timing issue here, if the callback opens the session again in
                // notifySessionFinished() immediately, the session may not be removed from
                // the mSession. But to avoid adding unknown latency, only notifying failures
                // are moved to the last of posted runnable, notifying success cases are
                // still kept here.
                mCallbacks.notifySessionFinished(session.sessionId, session.userId, success);
            }

            mInstallHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (session.isStaged() && !success) {
                        mStagingManager.abortSession(session.mStagedSession);
                    }
                    synchronized (mSessions) {
                        // Child sessions will be removed along with its parent as a whole
                        if (!session.hasParentSessionId()) {
                            // Retain policy:
                            // 1. Don't keep non-staged sessions
                            // 2. Don't keep explicitly abandoned sessions
                            // 3. Don't keep sessions that fail validation (isCommitted() is false)
                            boolean shouldRemove = !session.isStaged() || session.isDestroyed()
                                    || !session.isCommitted();
                            if (shouldRemove) {
                                removeActiveSession(session);
                            }
                        }

                        final File appIconFile = buildAppIconFile(session.sessionId);
                        if (appIconFile.exists()) {
                            appIconFile.delete();
                        }

                        mSettingsWriteRequest.runNow();
                    }
                    if (!success) {
                        mCallbacks.notifySessionFinished(
                                session.sessionId, session.userId, success);
                    }
                }
            });
        }

        public void onSessionPrepared(PackageInstallerSession session) {
            // We prepared the destination to write into; we want to persist
            // this, but it's not critical enough to block for.
            mSettingsWriteRequest.schedule();
        }

        public void onSessionSealedBlocking(PackageInstallerSession session) {
            // It's very important that we block until we've recorded the
            // session as being sealed, since we never want to allow mutation
            // after sealing.
            mSettingsWriteRequest.runNow();
        }
    }

    /**
     * Send a {@code PackageInstaller.ACTION_SESSION_UPDATED} broadcast intent, containing
     * the {@code sessionInfo} in the extra field {@code PackageInstaller.EXTRA_SESSION}.
     */
    private void sendSessionUpdatedBroadcast(PackageInstaller.SessionInfo sessionInfo,
            int userId) {
        if (TextUtils.isEmpty(sessionInfo.installerPackageName)) {
            return;
        }
        Intent sessionUpdatedIntent = new Intent(PackageInstaller.ACTION_SESSION_UPDATED)
                .putExtra(PackageInstaller.EXTRA_SESSION, sessionInfo)
                .setPackage(sessionInfo.installerPackageName);
        mContext.sendBroadcastAsUser(sessionUpdatedIntent, UserHandle.of(userId));
    }

    /**
     * Abandon unfinished sessions if the installer package has been uninstalled.
     * @param installerAppId the app ID of the installer package that has been uninstalled.
     * @param userId the user that has the installer package uninstalled.
     */
    void onInstallerPackageDeleted(int installerAppId, int userId) {
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (!matchesInstaller(session, installerAppId, userId)) {
                    continue;
                }
                // Find parent session and only abandon parent session if installer matches
                PackageInstallerSession root = !session.hasParentSessionId()
                        ? session : mSessions.get(session.getParentSessionId());
                if (root != null && matchesInstaller(root, installerAppId, userId)
                        && !root.isDestroyed()) {
                    root.abandon();
                }
            }
        }
    }

    private boolean matchesInstaller(PackageInstallerSession session, int installerAppId,
            int userId) {
        final int installerUid = session.getInstallerUid();
        if (installerAppId == UserHandle.USER_ALL) {
            return UserHandle.getAppId(installerUid) == installerAppId;
        } else {
            return UserHandle.getUid(userId, installerAppId) == installerUid;
        }
    }
}
