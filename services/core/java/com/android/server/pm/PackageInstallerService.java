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

import static com.android.internal.util.XmlUtils.readBitmapAttribute;
import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.readUriAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;
import static com.android.internal.util.XmlUtils.writeUriAttribute;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PackageDeleteObserver;
import android.app.PackageInstallObserver;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageInstallerCallback;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.ExceptionUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.Xml;

import libcore.io.IoUtils;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageHelper;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.ImageUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.IoThread;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public class PackageInstallerService extends IPackageInstaller.Stub {
    private static final String TAG = "PackageInstaller";
    private static final boolean LOGD = false;

    // TODO: remove outstanding sessions when installer package goes away
    // TODO: notify listeners in other users when package has been installed there
    // TODO: purge expired sessions periodically in addition to at reboot

    /** XML constants used in {@link #mSessionsFile} */
    private static final String TAG_SESSIONS = "sessions";
    private static final String TAG_SESSION = "session";
    private static final String TAG_GRANTED_RUNTIME_PERMISSION = "granted-runtime-permission";
    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_INSTALLER_PACKAGE_NAME = "installerPackageName";
    private static final String ATTR_INSTALLER_UID = "installerUid";
    private static final String ATTR_CREATED_MILLIS = "createdMillis";
    private static final String ATTR_SESSION_STAGE_DIR = "sessionStageDir";
    private static final String ATTR_SESSION_STAGE_CID = "sessionStageCid";
    private static final String ATTR_PREPARED = "prepared";
    private static final String ATTR_SEALED = "sealed";
    private static final String ATTR_MODE = "mode";
    private static final String ATTR_INSTALL_FLAGS = "installFlags";
    private static final String ATTR_INSTALL_LOCATION = "installLocation";
    private static final String ATTR_SIZE_BYTES = "sizeBytes";
    private static final String ATTR_APP_PACKAGE_NAME = "appPackageName";
    @Deprecated
    private static final String ATTR_APP_ICON = "appIcon";
    private static final String ATTR_APP_LABEL = "appLabel";
    private static final String ATTR_ORIGINATING_URI = "originatingUri";
    private static final String ATTR_ORIGINATING_UID = "originatingUid";
    private static final String ATTR_REFERRER_URI = "referrerUri";
    private static final String ATTR_ABI_OVERRIDE = "abiOverride";
    private static final String ATTR_VOLUME_UUID = "volumeUuid";
    private static final String ATTR_NAME = "name";

    /** Automatically destroy sessions older than this */
    private static final long MAX_AGE_MILLIS = 3 * DateUtils.DAY_IN_MILLIS;
    /** Upper bound on number of active sessions for a UID */
    private static final long MAX_ACTIVE_SESSIONS = 1024;
    /** Upper bound on number of historical sessions for a UID */
    private static final long MAX_HISTORICAL_SESSIONS = 1048576;

    private final Context mContext;
    private final PackageManagerService mPm;

    private AppOpsManager mAppOps;

    private final HandlerThread mInstallThread;
    private final Handler mInstallHandler;

    private final Callbacks mCallbacks;

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

    /**
     * Used for generating session IDs. Since this is created at boot time,
     * normal random might be predictable.
     */
    private final Random mRandom = new SecureRandom();

    @GuardedBy("mSessions")
    private final SparseArray<PackageInstallerSession> mSessions = new SparseArray<>();

    /** Historical sessions kept around for debugging purposes */
    @GuardedBy("mSessions")
    private final SparseArray<PackageInstallerSession> mHistoricalSessions = new SparseArray<>();

    /** Sessions allocated to legacy users */
    @GuardedBy("mSessions")
    private final SparseBooleanArray mLegacySessions = new SparseBooleanArray();

    private static final FilenameFilter sStageFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return isStageName(name);
        }
    };

    public PackageInstallerService(Context context, PackageManagerService pm) {
        mContext = context;
        mPm = pm;

        mInstallThread = new HandlerThread(TAG);
        mInstallThread.start();

        mInstallHandler = new Handler(mInstallThread.getLooper());

        mCallbacks = new Callbacks(mInstallThread.getLooper());

        mSessionsFile = new AtomicFile(
                new File(Environment.getDataSystemDirectory(), "install_sessions.xml"));
        mSessionsDir = new File(Environment.getDataSystemDirectory(), "install_sessions");
        mSessionsDir.mkdirs();

        synchronized (mSessions) {
            readSessionsLocked();

            reconcileStagesLocked(StorageManager.UUID_PRIVATE_INTERNAL, false /*isEphemeral*/);
            reconcileStagesLocked(StorageManager.UUID_PRIVATE_INTERNAL, true /*isEphemeral*/);

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
        }
    }

    public void systemReady() {
        mAppOps = mContext.getSystemService(AppOpsManager.class);
    }

    private void reconcileStagesLocked(String volumeUuid, boolean isEphemeral) {
        final File stagingDir = buildStagingDir(volumeUuid, isEphemeral);
        final ArraySet<File> unclaimedStages = newArraySet(
                stagingDir.listFiles(sStageFilter));

        // Ignore stages claimed by active sessions
        for (int i = 0; i < mSessions.size(); i++) {
            final PackageInstallerSession session = mSessions.valueAt(i);
            unclaimedStages.remove(session.stageDir);
        }

        // Clean up orphaned staging directories
        for (File stage : unclaimedStages) {
            Slog.w(TAG, "Deleting orphan stage " + stage);
            synchronized (mPm.mInstallLock) {
                mPm.removeCodePathLI(stage);
            }
        }
    }

    public void onPrivateVolumeMounted(String volumeUuid) {
        synchronized (mSessions) {
            reconcileStagesLocked(volumeUuid, false /*isEphemeral*/);
        }
    }

    public void onSecureContainersAvailable() {
        synchronized (mSessions) {
            final ArraySet<String> unclaimed = new ArraySet<>();
            for (String cid : PackageHelper.getSecureContainerList()) {
                if (isStageName(cid)) {
                    unclaimed.add(cid);
                }
            }

            // Ignore stages claimed by active sessions
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                final String cid = session.stageCid;

                if (unclaimed.remove(cid)) {
                    // Claimed by active session, mount it
                    PackageHelper.mountSdDir(cid, PackageManagerService.getEncryptKey(),
                            Process.SYSTEM_UID);
                }
            }

            // Clean up orphaned staging containers
            for (String cid : unclaimed) {
                Slog.w(TAG, "Deleting orphan container " + cid);
                PackageHelper.destroySdDir(cid);
            }
        }
    }

    public static boolean isStageName(String name) {
        final boolean isFile = name.startsWith("vmdl") && name.endsWith(".tmp");
        final boolean isContainer = name.startsWith("smdl") && name.endsWith(".tmp");
        final boolean isLegacyContainer = name.startsWith("smdl2tmp");
        return isFile || isContainer || isLegacyContainer;
    }

    @Deprecated
    public File allocateStageDirLegacy(String volumeUuid, boolean isEphemeral) throws IOException {
        synchronized (mSessions) {
            try {
                final int sessionId = allocateSessionIdLocked();
                mLegacySessions.put(sessionId, true);
                final File stageDir = buildStageDir(volumeUuid, sessionId, isEphemeral);
                prepareStageDir(stageDir);
                return stageDir;
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

    private void readSessionsLocked() {
        if (LOGD) Slog.v(TAG, "readSessionsLocked()");

        mSessions.clear();

        FileInputStream fis = null;
        try {
            fis = mSessionsFile.openRead();
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, StandardCharsets.UTF_8.name());

            int type;
            while ((type = in.next()) != END_DOCUMENT) {
                if (type == START_TAG) {
                    final String tag = in.getName();
                    if (TAG_SESSION.equals(tag)) {
                        final PackageInstallerSession session = readSessionLocked(in);
                        final long age = System.currentTimeMillis() - session.createdMillis;

                        final boolean valid;
                        if (age >= MAX_AGE_MILLIS) {
                            Slog.w(TAG, "Abandoning old session first created at "
                                    + session.createdMillis);
                            valid = false;
                        } else {
                            valid = true;
                        }

                        if (valid) {
                            mSessions.put(session.sessionId, session);
                        } else {
                            // Since this is early during boot we don't send
                            // any observer events about the session, but we
                            // keep details around for dumpsys.
                            mHistoricalSessions.put(session.sessionId, session);
                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // Missing sessions are okay, probably first boot
        } catch (IOException | XmlPullParserException e) {
            Slog.wtf(TAG, "Failed reading install sessions", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    private PackageInstallerSession readSessionLocked(XmlPullParser in) throws IOException,
            XmlPullParserException {
        final int sessionId = readIntAttribute(in, ATTR_SESSION_ID);
        final int userId = readIntAttribute(in, ATTR_USER_ID);
        final String installerPackageName = readStringAttribute(in, ATTR_INSTALLER_PACKAGE_NAME);
        final int installerUid = readIntAttribute(in, ATTR_INSTALLER_UID, mPm.getPackageUid(
                installerPackageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, userId));
        final long createdMillis = readLongAttribute(in, ATTR_CREATED_MILLIS);
        final String stageDirRaw = readStringAttribute(in, ATTR_SESSION_STAGE_DIR);
        final File stageDir = (stageDirRaw != null) ? new File(stageDirRaw) : null;
        final String stageCid = readStringAttribute(in, ATTR_SESSION_STAGE_CID);
        final boolean prepared = readBooleanAttribute(in, ATTR_PREPARED, true);
        final boolean sealed = readBooleanAttribute(in, ATTR_SEALED);

        final SessionParams params = new SessionParams(
                SessionParams.MODE_INVALID);
        params.mode = readIntAttribute(in, ATTR_MODE);
        params.installFlags = readIntAttribute(in, ATTR_INSTALL_FLAGS);
        params.installLocation = readIntAttribute(in, ATTR_INSTALL_LOCATION);
        params.sizeBytes = readLongAttribute(in, ATTR_SIZE_BYTES);
        params.appPackageName = readStringAttribute(in, ATTR_APP_PACKAGE_NAME);
        params.appIcon = readBitmapAttribute(in, ATTR_APP_ICON);
        params.appLabel = readStringAttribute(in, ATTR_APP_LABEL);
        params.originatingUri = readUriAttribute(in, ATTR_ORIGINATING_URI);
        params.originatingUid =
                readIntAttribute(in, ATTR_ORIGINATING_UID, SessionParams.UID_UNKNOWN);
        params.referrerUri = readUriAttribute(in, ATTR_REFERRER_URI);
        params.abiOverride = readStringAttribute(in, ATTR_ABI_OVERRIDE);
        params.volumeUuid = readStringAttribute(in, ATTR_VOLUME_UUID);
        params.grantedRuntimePermissions = readGrantedRuntimePermissions(in);

        final File appIconFile = buildAppIconFile(sessionId);
        if (appIconFile.exists()) {
            params.appIcon = BitmapFactory.decodeFile(appIconFile.getAbsolutePath());
            params.appIconLastModified = appIconFile.lastModified();
        }

        return new PackageInstallerSession(mInternalCallback, mContext, mPm,
                mInstallThread.getLooper(), sessionId, userId, installerPackageName, installerUid,
                params, createdMillis, stageDir, stageCid, prepared, sealed);
    }

    private void writeSessionsLocked() {
        if (LOGD) Slog.v(TAG, "writeSessionsLocked()");

        FileOutputStream fos = null;
        try {
            fos = mSessionsFile.startWrite();

            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_SESSIONS);
            final int size = mSessions.size();
            for (int i = 0; i < size; i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                writeSessionLocked(out, session);
            }
            out.endTag(null, TAG_SESSIONS);
            out.endDocument();

            mSessionsFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mSessionsFile.failWrite(fos);
            }
        }
    }

    private void writeSessionLocked(XmlSerializer out, PackageInstallerSession session)
            throws IOException {
        final SessionParams params = session.params;

        out.startTag(null, TAG_SESSION);

        writeIntAttribute(out, ATTR_SESSION_ID, session.sessionId);
        writeIntAttribute(out, ATTR_USER_ID, session.userId);
        writeStringAttribute(out, ATTR_INSTALLER_PACKAGE_NAME,
                session.installerPackageName);
        writeIntAttribute(out, ATTR_INSTALLER_UID, session.installerUid);
        writeLongAttribute(out, ATTR_CREATED_MILLIS, session.createdMillis);
        if (session.stageDir != null) {
            writeStringAttribute(out, ATTR_SESSION_STAGE_DIR,
                    session.stageDir.getAbsolutePath());
        }
        if (session.stageCid != null) {
            writeStringAttribute(out, ATTR_SESSION_STAGE_CID, session.stageCid);
        }
        writeBooleanAttribute(out, ATTR_PREPARED, session.isPrepared());
        writeBooleanAttribute(out, ATTR_SEALED, session.isSealed());

        writeIntAttribute(out, ATTR_MODE, params.mode);
        writeIntAttribute(out, ATTR_INSTALL_FLAGS, params.installFlags);
        writeIntAttribute(out, ATTR_INSTALL_LOCATION, params.installLocation);
        writeLongAttribute(out, ATTR_SIZE_BYTES, params.sizeBytes);
        writeStringAttribute(out, ATTR_APP_PACKAGE_NAME, params.appPackageName);
        writeStringAttribute(out, ATTR_APP_LABEL, params.appLabel);
        writeUriAttribute(out, ATTR_ORIGINATING_URI, params.originatingUri);
        writeIntAttribute(out, ATTR_ORIGINATING_UID, params.originatingUid);
        writeUriAttribute(out, ATTR_REFERRER_URI, params.referrerUri);
        writeStringAttribute(out, ATTR_ABI_OVERRIDE, params.abiOverride);
        writeStringAttribute(out, ATTR_VOLUME_UUID, params.volumeUuid);

        // Persist app icon if changed since last written
        final File appIconFile = buildAppIconFile(session.sessionId);
        if (params.appIcon == null && appIconFile.exists()) {
            appIconFile.delete();
        } else if (params.appIcon != null
                && appIconFile.lastModified() != params.appIconLastModified) {
            if (LOGD) Slog.w(TAG, "Writing changed icon " + appIconFile);
            FileOutputStream os = null;
            try {
                os = new FileOutputStream(appIconFile);
                params.appIcon.compress(CompressFormat.PNG, 90, os);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write icon " + appIconFile + ": " + e.getMessage());
            } finally {
                IoUtils.closeQuietly(os);
            }

            params.appIconLastModified = appIconFile.lastModified();
        }

        writeGrantedRuntimePermissions(out, params.grantedRuntimePermissions);

        out.endTag(null, TAG_SESSION);
    }

    private static void writeGrantedRuntimePermissions(XmlSerializer out,
            String[] grantedRuntimePermissions) throws IOException {
        if (grantedRuntimePermissions != null) {
            for (String permission : grantedRuntimePermissions) {
                out.startTag(null, TAG_GRANTED_RUNTIME_PERMISSION);
                writeStringAttribute(out, ATTR_NAME, permission);
                out.endTag(null, TAG_GRANTED_RUNTIME_PERMISSION);
            }
        }
    }

    private static String[] readGrantedRuntimePermissions(XmlPullParser in)
            throws IOException, XmlPullParserException {
        List<String> permissions = null;

        final int outerDepth = in.getDepth();
        int type;
        while ((type = in.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || in.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (TAG_GRANTED_RUNTIME_PERMISSION.equals(in.getName())) {
                String permission = readStringAttribute(in, ATTR_NAME);
                if (permissions == null) {
                    permissions = new ArrayList<>();
                }
                permissions.add(permission);
            }
        }

        if (permissions == null) {
            return null;
        }

        String[] permissionsArray = new String[permissions.size()];
        permissions.toArray(permissionsArray);
        return permissionsArray;
    }

    private File buildAppIconFile(int sessionId) {
        return new File(mSessionsDir, "app_icon." + sessionId + ".png");
    }

    private void writeSessionsAsync() {
        IoThread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                synchronized (mSessions) {
                    writeSessionsLocked();
                }
            }
        });
    }

    @Override
    public int createSession(SessionParams params, String installerPackageName, int userId) {
        try {
            return createSessionInternal(params, installerPackageName, userId);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    private int createSessionInternal(SessionParams params, String installerPackageName, int userId)
            throws IOException {
        final int callingUid = Binder.getCallingUid();
        mPm.enforceCrossUserPermission(callingUid, userId, true, true, "createSession");

        if (mPm.isUserRestricted(userId, UserManager.DISALLOW_INSTALL_APPS)) {
            throw new SecurityException("User restriction prevents installing");
        }

        if ((callingUid == Process.SHELL_UID) || (callingUid == Process.ROOT_UID)) {
            params.installFlags |= PackageManager.INSTALL_FROM_ADB;

        } else {
            mAppOps.checkPackage(callingUid, installerPackageName);

            params.installFlags &= ~PackageManager.INSTALL_FROM_ADB;
            params.installFlags &= ~PackageManager.INSTALL_ALL_USERS;
            params.installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
        }

        // Only system components can circumvent runtime permissions when installing.
        if ((params.installFlags & PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS) != 0
                && mContext.checkCallingOrSelfPermission(Manifest.permission
                .INSTALL_GRANT_RUNTIME_PERMISSIONS) == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException("You need the "
                    + "android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS permission "
                    + "to use the PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS flag");
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

        // If caller requested explicit location, sanity check it, otherwise
        // resolve the best internal or adopted location.
        if ((params.installFlags & PackageManager.INSTALL_INTERNAL) != 0) {
            if (!PackageHelper.fitsOnInternal(mContext, params.sizeBytes)) {
                throw new IOException("No suitable internal storage available");
            }

        } else if ((params.installFlags & PackageManager.INSTALL_EXTERNAL) != 0) {
            if (!PackageHelper.fitsOnExternal(mContext, params.sizeBytes)) {
                throw new IOException("No suitable external storage available");
            }

        } else if ((params.installFlags & PackageManager.INSTALL_FORCE_VOLUME_UUID) != 0) {
            // For now, installs to adopted media are treated as internal from
            // an install flag point-of-view.
            params.setInstallFlagsInternal();

        } else {
            // For now, installs to adopted media are treated as internal from
            // an install flag point-of-view.
            params.setInstallFlagsInternal();

            // Resolve best location for install, based on combination of
            // requested install flags, delta size, and manifest settings.
            final long ident = Binder.clearCallingIdentity();
            try {
                params.volumeUuid = PackageHelper.resolveInstallVolume(mContext,
                        params.appPackageName, params.installLocation, params.sizeBytes);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        final int sessionId;
        final PackageInstallerSession session;
        synchronized (mSessions) {
            // Sanity check that installer isn't going crazy
            final int activeCount = getSessionCount(mSessions, callingUid);
            if (activeCount >= MAX_ACTIVE_SESSIONS) {
                throw new IllegalStateException(
                        "Too many active sessions for UID " + callingUid);
            }
            final int historicalCount = getSessionCount(mHistoricalSessions, callingUid);
            if (historicalCount >= MAX_HISTORICAL_SESSIONS) {
                throw new IllegalStateException(
                        "Too many historical sessions for UID " + callingUid);
            }

            final long createdMillis = System.currentTimeMillis();
            sessionId = allocateSessionIdLocked();

            // We're staging to exactly one location
            File stageDir = null;
            String stageCid = null;
            if ((params.installFlags & PackageManager.INSTALL_INTERNAL) != 0) {
                final boolean isEphemeral =
                        (params.installFlags & PackageManager.INSTALL_EPHEMERAL) != 0;
                stageDir = buildStageDir(params.volumeUuid, sessionId, isEphemeral);
            } else {
                stageCid = buildExternalStageCid(sessionId);
            }

            session = new PackageInstallerSession(mInternalCallback, mContext, mPm,
                    mInstallThread.getLooper(), sessionId, userId, installerPackageName, callingUid,
                    params, createdMillis, stageDir, stageCid, false, false);
            mSessions.put(sessionId, session);
        }

        mCallbacks.notifySessionCreated(session.sessionId, session.userId);
        writeSessionsAsync();
        return sessionId;
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
            session.params.appLabel = appLabel;
            mInternalCallback.onSessionBadgingChanged(session);
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

    private IPackageInstallerSession openSessionInternal(int sessionId) throws IOException {
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            if (session == null || !isCallingUidOwner(session)) {
                throw new SecurityException("Caller has no access to session " + sessionId);
            }
            session.open();
            return session;
        }
    }

    private int allocateSessionIdLocked() {
        int n = 0;
        int sessionId;
        do {
            sessionId = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
            if (mSessions.get(sessionId) == null && mHistoricalSessions.get(sessionId) == null
                    && !mLegacySessions.get(sessionId, false)) {
                return sessionId;
            }
        } while (n++ < 32);

        throw new IllegalStateException("Failed to allocate session ID");
    }

    private File buildStagingDir(String volumeUuid, boolean isEphemeral) {
        if (isEphemeral) {
            return Environment.getDataAppEphemeralDirectory(volumeUuid);
        }
        return Environment.getDataAppDirectory(volumeUuid);
    }

    private File buildStageDir(String volumeUuid, int sessionId, boolean isEphemeral) {
        final File stagingDir = buildStagingDir(volumeUuid, isEphemeral);
        return new File(stagingDir, "vmdl" + sessionId + ".tmp");
    }

    static void prepareStageDir(File stageDir) throws IOException {
        if (stageDir.exists()) {
            throw new IOException("Session dir already exists: " + stageDir);
        }

        try {
            Os.mkdir(stageDir.getAbsolutePath(), 0755);
            Os.chmod(stageDir.getAbsolutePath(), 0755);
        } catch (ErrnoException e) {
            // This purposefully throws if directory already exists
            throw new IOException("Failed to prepare session dir: " + stageDir, e);
        }

        if (!SELinux.restorecon(stageDir)) {
            throw new IOException("Failed to restorecon session dir: " + stageDir);
        }
    }

    private String buildExternalStageCid(int sessionId) {
        return "smdl" + sessionId + ".tmp";
    }

    static void prepareExternalStageCid(String stageCid, long sizeBytes) throws IOException {
        if (PackageHelper.createSdDir(sizeBytes, stageCid, PackageManagerService.getEncryptKey(),
                Process.SYSTEM_UID, true) == null) {
            throw new IOException("Failed to create session cid: " + stageCid);
        }
    }

    @Override
    public SessionInfo getSessionInfo(int sessionId) {
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            return session != null ? session.generateInfo() : null;
        }
    }

    @Override
    public ParceledListSlice<SessionInfo> getAllSessions(int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "getAllSessions");

        final List<SessionInfo> result = new ArrayList<>();
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (session.userId == userId) {
                    result.add(session.generateInfo());
                }
            }
        }
        return new ParceledListSlice<>(result);
    }

    @Override
    public ParceledListSlice<SessionInfo> getMySessions(String installerPackageName, int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "getMySessions");
        mAppOps.checkPackage(Binder.getCallingUid(), installerPackageName);

        final List<SessionInfo> result = new ArrayList<>();
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (Objects.equals(session.installerPackageName, installerPackageName)
                        && session.userId == userId) {
                    result.add(session.generateInfo());
                }
            }
        }
        return new ParceledListSlice<>(result);
    }

    @Override
    public void uninstall(String packageName, String callerPackageName, int flags,
                IntentSender statusReceiver, int userId) {
        final int callingUid = Binder.getCallingUid();
        mPm.enforceCrossUserPermission(callingUid, userId, true, true, "uninstall");
        boolean allowSilentUninstall = true;
        if ((callingUid != Process.SHELL_UID) && (callingUid != Process.ROOT_UID)) {
            mAppOps.checkPackage(callingUid, callerPackageName);
            final String installerPackageName = mPm.getInstallerPackageName(packageName);
            allowSilentUninstall = mPm.isOrphaned(packageName) ||
                    (installerPackageName != null
                            && installerPackageName.equals(callerPackageName));
        }

        // Check whether the caller is device owner, in which case we do it silently.
        DevicePolicyManager dpm = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        boolean isDeviceOwner = (dpm != null) && dpm.isDeviceOwnerAppOnCallingUser(
                callerPackageName);

        final PackageDeleteObserverAdapter adapter = new PackageDeleteObserverAdapter(mContext,
                statusReceiver, packageName, isDeviceOwner, userId);
        if (allowSilentUninstall && mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES) == PackageManager.PERMISSION_GRANTED) {
            // Sweet, call straight through!
            mPm.deletePackage(packageName, adapter.getBinder(), userId, flags);
        } else if (isDeviceOwner) {
            // Allow the DeviceOwner to silently delete packages
            // Need to clear the calling identity to get DELETE_PACKAGES permission
            long ident = Binder.clearCallingIdentity();
            try {
                mPm.deletePackage(packageName, adapter.getBinder(), userId, flags);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        } else {
            // Take a short detour to confirm with user
            final Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
            intent.setData(Uri.fromParts("package", packageName, null));
            intent.putExtra(PackageInstaller.EXTRA_CALLBACK, adapter.getBinder().asBinder());
            adapter.onUserActionRequired(intent);
        }
    }

    @Override
    public void setPermissionsResult(int sessionId, boolean accepted) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES, TAG);

        synchronized (mSessions) {
            PackageInstallerSession session = mSessions.get(sessionId);
            if (session != null) {
                session.setPermissionsResult(accepted);
            }
        }
    }

    @Override
    public void registerCallback(IPackageInstallerCallback callback, int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "registerCallback");
        mCallbacks.register(callback, userId);
    }

    @Override
    public void unregisterCallback(IPackageInstallerCallback callback) {
        mCallbacks.unregister(callback);
    }

    private static int getSessionCount(SparseArray<PackageInstallerSession> sessions,
            int installerUid) {
        int count = 0;
        final int size = sessions.size();
        for (int i = 0; i < size; i++) {
            final PackageInstallerSession session = sessions.valueAt(i);
            if (session.installerUid == installerUid) {
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
            return (session != null) && (callingUid == session.installerUid);
        }
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
                        mContext.getResources().getString(R.string.package_deleted_device_owner),
                        packageName,
                        userId);
            } else {
                mNotification = null;
            }
        }

        @Override
        public void onUserActionRequired(Intent intent) {
            final Intent fillIn = new Intent();
            fillIn.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, mPackageName);
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_PENDING_USER_ACTION);
            fillIn.putExtra(Intent.EXTRA_INTENT, intent);
            try {
                mTarget.sendIntent(mContext, 0, fillIn, null, null);
            } catch (SendIntentException ignored) {
            }
        }

        @Override
        public void onPackageDeleted(String basePackageName, int returnCode, String msg) {
            if (PackageManager.DELETE_SUCCEEDED == returnCode && mNotification != null) {
                NotificationManager notificationManager = (NotificationManager)
                        mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.notify(basePackageName, 0, mNotification);
            }
            final Intent fillIn = new Intent();
            fillIn.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, mPackageName);
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS,
                    PackageManager.deleteStatusToPublicStatus(returnCode));
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,
                    PackageManager.deleteStatusToString(returnCode, msg));
            fillIn.putExtra(PackageInstaller.EXTRA_LEGACY_STATUS, returnCode);
            try {
                mTarget.sendIntent(mContext, 0, fillIn, null, null);
            } catch (SendIntentException ignored) {
            }
        }
    }

    static class PackageInstallObserverAdapter extends PackageInstallObserver {
        private final Context mContext;
        private final IntentSender mTarget;
        private final int mSessionId;
        private final boolean mShowNotification;
        private final int mUserId;

        public PackageInstallObserverAdapter(Context context, IntentSender target, int sessionId,
                boolean showNotification, int userId) {
            mContext = context;
            mTarget = target;
            mSessionId = sessionId;
            mShowNotification = showNotification;
            mUserId = userId;
        }

        @Override
        public void onUserActionRequired(Intent intent) {
            final Intent fillIn = new Intent();
            fillIn.putExtra(PackageInstaller.EXTRA_SESSION_ID, mSessionId);
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_PENDING_USER_ACTION);
            fillIn.putExtra(Intent.EXTRA_INTENT, intent);
            try {
                mTarget.sendIntent(mContext, 0, fillIn, null, null);
            } catch (SendIntentException ignored) {
            }
        }

        @Override
        public void onPackageInstalled(String basePackageName, int returnCode, String msg,
                Bundle extras) {
            if (PackageManager.INSTALL_SUCCEEDED == returnCode && mShowNotification) {
                boolean update = (extras != null) && extras.getBoolean(Intent.EXTRA_REPLACING);
                Notification notification = buildSuccessNotification(mContext,
                        mContext.getResources()
                                .getString(update ? R.string.package_updated_device_owner :
                                        R.string.package_installed_device_owner),
                        basePackageName,
                        mUserId);
                if (notification != null) {
                    NotificationManager notificationManager = (NotificationManager)
                            mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                    notificationManager.notify(basePackageName, 0, notification);
                }
            }
            final Intent fillIn = new Intent();
            fillIn.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, basePackageName);
            fillIn.putExtra(PackageInstaller.EXTRA_SESSION_ID, mSessionId);
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS,
                    PackageManager.installStatusToPublicStatus(returnCode));
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,
                    PackageManager.installStatusToString(returnCode, msg));
            fillIn.putExtra(PackageInstaller.EXTRA_LEGACY_STATUS, returnCode);
            if (extras != null) {
                final String existing = extras.getString(
                        PackageManager.EXTRA_FAILURE_EXISTING_PACKAGE);
                if (!TextUtils.isEmpty(existing)) {
                    fillIn.putExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME, existing);
                }
            }
            try {
                mTarget.sendIntent(mContext, 0, fillIn, null, null);
            } catch (SendIntentException ignored) {
            }
        }
    }

    /**
     * Build a notification for package installation / deletion by device owners that is shown if
     * the operation succeeds.
     */
    private static Notification buildSuccessNotification(Context context, String contentText,
            String basePackageName, int userId) {
        PackageInfo packageInfo = null;
        try {
            packageInfo = AppGlobals.getPackageManager().getPackageInfo(
                    basePackageName, 0, userId);
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
        return new Notification.Builder(context)
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

    private static class Callbacks extends Handler {
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

        public void register(IPackageInstallerCallback callback, int userId) {
            mCallbacks.register(callback, new UserHandle(userId));
        }

        public void unregister(IPackageInstallerCallback callback) {
            mCallbacks.unregister(callback);
        }

        @Override
        public void handleMessage(Message msg) {
            final int userId = msg.arg2;
            final int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                final IPackageInstallerCallback callback = mCallbacks.getBroadcastItem(i);
                final UserHandle user = (UserHandle) mCallbacks.getBroadcastCookie(i);
                // TODO: dispatch notifications for slave profiles
                if (userId == user.getIdentifier()) {
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

    void dump(IndentingPrintWriter pw) {
        synchronized (mSessions) {
            pw.println("Active install sessions:");
            pw.increaseIndent();
            int N = mSessions.size();
            for (int i = 0; i < N; i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                session.dump(pw);
                pw.println();
            }
            pw.println();
            pw.decreaseIndent();

            pw.println("Historical install sessions:");
            pw.increaseIndent();
            N = mHistoricalSessions.size();
            for (int i = 0; i < N; i++) {
                final PackageInstallerSession session = mHistoricalSessions.valueAt(i);
                session.dump(pw);
                pw.println();
            }
            pw.println();
            pw.decreaseIndent();

            pw.println("Legacy install sessions:");
            pw.increaseIndent();
            pw.println(mLegacySessions.toString());
            pw.decreaseIndent();
        }
    }

    class InternalCallback {
        public void onSessionBadgingChanged(PackageInstallerSession session) {
            mCallbacks.notifySessionBadgingChanged(session.sessionId, session.userId);
            writeSessionsAsync();
        }

        public void onSessionActiveChanged(PackageInstallerSession session, boolean active) {
            mCallbacks.notifySessionActiveChanged(session.sessionId, session.userId, active);
        }

        public void onSessionProgressChanged(PackageInstallerSession session, float progress) {
            mCallbacks.notifySessionProgressChanged(session.sessionId, session.userId, progress);
        }

        public void onSessionFinished(final PackageInstallerSession session, boolean success) {
            mCallbacks.notifySessionFinished(session.sessionId, session.userId, success);

            mInstallHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (mSessions) {
                        mSessions.remove(session.sessionId);
                        mHistoricalSessions.put(session.sessionId, session);

                        final File appIconFile = buildAppIconFile(session.sessionId);
                        if (appIconFile.exists()) {
                            appIconFile.delete();
                        }

                        writeSessionsLocked();
                    }
                }
            });
        }

        public void onSessionPrepared(PackageInstallerSession session) {
            // We prepared the destination to write into; we want to persist
            // this, but it's not critical enough to block for.
            writeSessionsAsync();
        }

        public void onSessionSealedBlocking(PackageInstallerSession session) {
            // It's very important that we block until we've recorded the
            // session as being sealed, since we never want to allow mutation
            // after sealing.
            synchronized (mSessions) {
                writeSessionsLocked();
            }
        }
    }
}
