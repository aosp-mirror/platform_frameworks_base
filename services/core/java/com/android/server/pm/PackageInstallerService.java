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

import static android.content.pm.PackageManager.INSTALL_ALL_USERS;
import static android.content.pm.PackageManager.INSTALL_FROM_ADB;
import static android.content.pm.PackageManager.INSTALL_REPLACE_EXISTING;
import static com.android.internal.util.XmlUtils.readBitmapAttribute;
import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.readUriAttribute;
import static com.android.internal.util.XmlUtils.writeBitmapAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;
import static com.android.internal.util.XmlUtils.writeUriAttribute;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageInstallerCallback;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.InstallSessionInfo;
import android.content.pm.InstallSessionParams;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
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
import android.system.ErrnoException;
import android.system.Os;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.IoThread;
import com.android.server.pm.PackageInstallerSession.Snapshot;
import com.google.android.collect.Sets;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public class PackageInstallerService extends IPackageInstaller.Stub {
    private static final String TAG = "PackageInstaller";
    private static final boolean LOGD = true;

    // TODO: remove outstanding sessions when installer package goes away
    // TODO: notify listeners in other users when package has been installed there

    /** XML constants used in {@link #mSessionsFile} */
    private static final String TAG_SESSIONS = "sessions";
    private static final String TAG_SESSION = "session";
    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_INSTALLER_PACKAGE_NAME = "installerPackageName";
    private static final String ATTR_CREATED_MILLIS = "createdMillis";
    private static final String ATTR_SESSION_STAGE_DIR = "sessionStageDir";
    private static final String ATTR_SEALED = "sealed";
    private static final String ATTR_MODE = "mode";
    private static final String ATTR_INSTALL_FLAGS = "installFlags";
    private static final String ATTR_INSTALL_LOCATION = "installLocation";
    private static final String ATTR_SIZE_BYTES = "sizeBytes";
    private static final String ATTR_APP_PACKAGE_NAME = "appPackageName";
    private static final String ATTR_APP_ICON = "appIcon";
    private static final String ATTR_APP_LABEL = "appLabel";
    private static final String ATTR_ORIGINATING_URI = "originatingUri";
    private static final String ATTR_REFERRER_URI = "referrerUri";
    private static final String ATTR_ABI_OVERRIDE = "abiOverride";

    /** Automatically destroy sessions older than this */
    private static final long MAX_AGE_MILLIS = 3 * DateUtils.DAY_IN_MILLIS;
    /** Upper bound on number of active sessions for a UID */
    private static final long MAX_ACTIVE_SESSIONS = 1024;
    /** Upper bound on number of historical sessions for a UID */
    private static final long MAX_HISTORICAL_SESSIONS = 1048576;

    private final Context mContext;
    private final PackageManagerService mPm;
    private final AppOpsManager mAppOps;

    private final File mStagingDir;
    private final HandlerThread mInstallThread;

    private final Callbacks mCallbacks;

    /**
     * File storing persisted {@link #mSessions}.
     */
    private final AtomicFile mSessionsFile;

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

    private static final FilenameFilter sStageFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.startsWith("vmdl") && name.endsWith(".tmp");
        }
    };

    public PackageInstallerService(Context context, PackageManagerService pm, File stagingDir) {
        mContext = context;
        mPm = pm;
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);

        mStagingDir = stagingDir;

        mInstallThread = new HandlerThread(TAG);
        mInstallThread.start();

        mCallbacks = new Callbacks(mInstallThread.getLooper());

        mSessionsFile = new AtomicFile(
                new File(Environment.getSystemSecureDirectory(), "install_sessions.xml"));

        synchronized (mSessions) {
            readSessionsLocked();

            // Clean up orphaned staging directories
            final ArraySet<File> stages = Sets.newArraySet(mStagingDir.listFiles(sStageFilter));
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                stages.remove(session.sessionStageDir);
            }
            for (File stage : stages) {
                Slog.w(TAG, "Deleting orphan stage " + stage);
                if (stage.isDirectory()) {
                    FileUtils.deleteContents(stage);
                }
                stage.delete();
            }
        }
    }

    public static boolean isStageFile(File file) {
        return sStageFilter.accept(null, file.getName());
    }

    @Deprecated
    public File allocateSessionDir() throws IOException {
        synchronized (mSessions) {
            try {
                final int sessionId = allocateSessionIdLocked();
                return prepareSessionStageDir(sessionId);
            } catch (IllegalStateException e) {
                throw new IOException(e);
            }
        }
    }

    private void readSessionsLocked() {
        if (LOGD) Slog.v(TAG, "readSessionsLocked()");

        mSessions.clear();

        FileInputStream fis = null;
        try {
            fis = mSessionsFile.openRead();
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, null);

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
                        } else if (!session.sessionStageDir.exists()) {
                            Slog.w(TAG, "Abandoning session with missing stage "
                                    + session.sessionStageDir);
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
        } catch (IOException e) {
            Log.wtf(TAG, "Failed reading install sessions", e);
        } catch (XmlPullParserException e) {
            Log.wtf(TAG, "Failed reading install sessions", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    private PackageInstallerSession readSessionLocked(XmlPullParser in) throws IOException {
        final int sessionId = readIntAttribute(in, ATTR_SESSION_ID);
        final int userId = readIntAttribute(in, ATTR_USER_ID);
        final String installerPackageName = readStringAttribute(in, ATTR_INSTALLER_PACKAGE_NAME);
        final long createdMillis = readLongAttribute(in, ATTR_CREATED_MILLIS);
        final File sessionStageDir = new File(readStringAttribute(in, ATTR_SESSION_STAGE_DIR));
        final boolean sealed = readBooleanAttribute(in, ATTR_SEALED);

        final InstallSessionParams params = new InstallSessionParams(
                InstallSessionParams.MODE_INVALID);
        params.mode = readIntAttribute(in, ATTR_MODE);
        params.installFlags = readIntAttribute(in, ATTR_INSTALL_FLAGS);
        params.installLocation = readIntAttribute(in, ATTR_INSTALL_LOCATION);
        params.sizeBytes = readLongAttribute(in, ATTR_SIZE_BYTES);
        params.appPackageName = readStringAttribute(in, ATTR_APP_PACKAGE_NAME);
        params.appIcon = readBitmapAttribute(in, ATTR_APP_ICON);
        params.appLabel = readStringAttribute(in, ATTR_APP_LABEL);
        params.originatingUri = readUriAttribute(in, ATTR_ORIGINATING_URI);
        params.referrerUri = readUriAttribute(in, ATTR_REFERRER_URI);
        params.abiOverride = readStringAttribute(in, ATTR_ABI_OVERRIDE);

        return new PackageInstallerSession(mInternalCallback, mPm, mInstallThread.getLooper(),
                sessionId, userId, installerPackageName, params, createdMillis, sessionStageDir,
                sealed);
    }

    private void writeSessionsLocked() {
        if (LOGD) Slog.v(TAG, "writeSessionsLocked()");

        FileOutputStream fos = null;
        try {
            fos = mSessionsFile.startWrite();

            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
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
        final InstallSessionParams params = session.params;
        final Snapshot snapshot = session.snapshot();

        out.startTag(null, TAG_SESSION);

        writeIntAttribute(out, ATTR_SESSION_ID, session.sessionId);
        writeIntAttribute(out, ATTR_USER_ID, session.userId);
        writeStringAttribute(out, ATTR_INSTALLER_PACKAGE_NAME,
                session.installerPackageName);
        writeLongAttribute(out, ATTR_CREATED_MILLIS, session.createdMillis);
        writeStringAttribute(out, ATTR_SESSION_STAGE_DIR,
                session.sessionStageDir.getAbsolutePath());
        writeBooleanAttribute(out, ATTR_SEALED, snapshot.sealed);

        writeIntAttribute(out, ATTR_MODE, params.mode);
        writeIntAttribute(out, ATTR_INSTALL_FLAGS, params.installFlags);
        writeIntAttribute(out, ATTR_INSTALL_LOCATION, params.installLocation);
        writeLongAttribute(out, ATTR_SIZE_BYTES, params.sizeBytes);
        writeStringAttribute(out, ATTR_APP_PACKAGE_NAME, params.appPackageName);
        writeBitmapAttribute(out, ATTR_APP_ICON, params.appIcon);
        writeStringAttribute(out, ATTR_APP_LABEL, params.appLabel);
        writeUriAttribute(out, ATTR_ORIGINATING_URI, params.originatingUri);
        writeUriAttribute(out, ATTR_REFERRER_URI, params.referrerUri);
        writeStringAttribute(out, ATTR_ABI_OVERRIDE, params.abiOverride);

        out.endTag(null, TAG_SESSION);
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
    public int createSession(InstallSessionParams params, String installerPackageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        mPm.enforceCrossUserPermission(callingUid, userId, true, "createSession");

        if (mPm.isUserRestricted(UserHandle.getUserId(callingUid),
                UserManager.DISALLOW_INSTALL_APPS)) {
            throw new SecurityException("User restriction prevents installing");
        }

        if ((callingUid == Process.SHELL_UID) || (callingUid == Process.ROOT_UID)) {
            installerPackageName = "com.android.shell";

            params.installFlags |= INSTALL_FROM_ADB;

        } else {
            mAppOps.checkPackage(callingUid, installerPackageName);

            params.installFlags &= ~INSTALL_FROM_ADB;
            params.installFlags &= ~INSTALL_ALL_USERS;
            params.installFlags |= INSTALL_REPLACE_EXISTING;
        }

        switch (params.mode) {
            case InstallSessionParams.MODE_FULL_INSTALL:
            case InstallSessionParams.MODE_INHERIT_EXISTING:
                break;
            default:
                throw new IllegalArgumentException("Params must have valid mode set");
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

        // Sanity check that install could fit
        if (params.sizeBytes > 0) {
            try {
                mPm.freeStorage(params.sizeBytes);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
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

            sessionId = allocateSessionIdLocked();

            final long createdMillis = System.currentTimeMillis();
            final File sessionStageDir = prepareSessionStageDir(sessionId);

            session = new PackageInstallerSession(mInternalCallback, mPm,
                    mInstallThread.getLooper(), sessionId, userId, installerPackageName, params,
                    createdMillis, sessionStageDir, false);
            mSessions.put(sessionId, session);
        }

        mCallbacks.notifySessionCreated(session.sessionId, session.userId);
        writeSessionsAsync();
        return sessionId;
    }

    @Override
    public IPackageInstallerSession openSession(int sessionId) {
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            if (session == null) {
                throw new IllegalStateException("Missing session " + sessionId);
            }
            if (!isCallingUidOwner(session)) {
                throw new SecurityException("Caller has no access to session " + sessionId);
            }
            if (session.openCount.getAndIncrement() == 0) {
                mCallbacks.notifySessionOpened(sessionId, session.userId);
            }
            return session;
        }
    }

    private int allocateSessionIdLocked() {
        int n = 0;
        int sessionId;
        do {
            sessionId = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
            if (mSessions.get(sessionId) == null && mHistoricalSessions.get(sessionId) == null) {
                return sessionId;
            }
        } while (n++ < 32);

        throw new IllegalStateException("Failed to allocate session ID");
    }

    private File prepareSessionStageDir(int sessionId) {
        final File file = new File(mStagingDir, "vmdl" + sessionId + ".tmp");

        if (file.exists()) {
            throw new IllegalStateException("Session dir already exists: " + file);
        }

        try {
            Os.mkdir(file.getAbsolutePath(), 0755);
            Os.chmod(file.getAbsolutePath(), 0755);
        } catch (ErrnoException e) {
            // This purposefully throws if directory already exists
            throw new IllegalStateException("Failed to prepare session dir", e);
        }

        if (!SELinux.restorecon(file)) {
            throw new IllegalStateException("Failed to restorecon session dir");
        }

        return file;
    }

    @Override
    public InstallSessionInfo getSessionInfo(int sessionId) {
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            if (!isCallingUidOwner(session)) {
                enforceCallerCanReadSessions();
            }
            return session != null ? session.generateInfo() : null;
        }
    }

    @Override
    public List<InstallSessionInfo> getAllSessions(int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, "getAllSessions");
        enforceCallerCanReadSessions();

        final List<InstallSessionInfo> result = new ArrayList<>();
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (session.userId == userId) {
                    result.add(session.generateInfo());
                }
            }
        }
        return result;
    }

    @Override
    public List<InstallSessionInfo> getMySessions(String installerPackageName, int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, "getMySessions");
        mAppOps.checkPackage(Binder.getCallingUid(), installerPackageName);

        final List<InstallSessionInfo> result = new ArrayList<>();
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (Objects.equals(session.installerPackageName, installerPackageName)
                        && session.userId == userId) {
                    result.add(session.generateInfo());
                }
            }
        }
        return result;
    }

    @Override
    public void uninstall(String packageName, int flags, IPackageDeleteObserver2 observer,
            int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, "uninstall");

        // TODO: enforce installer of record or permission
        mPm.deletePackage(packageName, observer, userId, flags);
    }

    @Override
    public void uninstallSplit(String basePackageName, String overlayName, int flags,
            IPackageDeleteObserver2 observer, int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, "uninstallSplit");

        // TODO: flesh out once PM has split support
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPermissionsResult(int sessionId, boolean accepted) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES, TAG);

        synchronized (mSessions) {
            mSessions.get(sessionId).setPermissionsResult(accepted);
        }
    }

    @Override
    public void registerCallback(IPackageInstallerCallback callback, int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, "registerCallback");
        enforceCallerCanReadSessions();

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

    /**
     * We allow those with permission, or the current home app.
     */
    private void enforceCallerCanReadSessions() {
        final boolean hasPermission = (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.READ_INSTALL_SESSIONS)
                == PackageManager.PERMISSION_GRANTED);
        final boolean isHomeApp = mPm.checkCallerIsHomeApp();
        if (hasPermission || isHomeApp) {
            return;
        } else {
            throw new SecurityException("Caller must be current home app to read install sessions");
        }
    }

    private static class Callbacks extends Handler {
        private static final int MSG_SESSION_CREATED = 1;
        private static final int MSG_SESSION_OPENED = 2;
        private static final int MSG_SESSION_PROGRESS_CHANGED = 3;
        private static final int MSG_SESSION_CLOSED = 4;
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
                case MSG_SESSION_OPENED:
                    callback.onSessionOpened(sessionId);
                    break;
                case MSG_SESSION_PROGRESS_CHANGED:
                    callback.onSessionProgressChanged(sessionId, (float) msg.obj);
                    break;
                case MSG_SESSION_CLOSED:
                    callback.onSessionClosed(sessionId);
                    break;
                case MSG_SESSION_FINISHED:
                    callback.onSessionFinished(sessionId, (boolean) msg.obj);
                    break;
            }
        }

        private void notifySessionCreated(int sessionId, int userId) {
            obtainMessage(MSG_SESSION_CREATED, sessionId, userId).sendToTarget();
        }

        private void notifySessionOpened(int sessionId, int userId) {
            obtainMessage(MSG_SESSION_OPENED, sessionId, userId).sendToTarget();
        }

        private void notifySessionProgressChanged(int sessionId, int userId, float progress) {
            obtainMessage(MSG_SESSION_PROGRESS_CHANGED, sessionId, userId, progress).sendToTarget();
        }

        private void notifySessionClosed(int sessionId, int userId) {
            obtainMessage(MSG_SESSION_CLOSED, sessionId, userId).sendToTarget();
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
        }
    }

    class InternalCallback {
        public void onSessionProgressChanged(PackageInstallerSession session, float progress) {
            mCallbacks.notifySessionProgressChanged(session.sessionId, session.userId, progress);
        }

        public void onSessionClosed(PackageInstallerSession session) {
            mCallbacks.notifySessionClosed(session.sessionId, session.userId);
        }

        public void onSessionFinished(PackageInstallerSession session, boolean success) {
            mCallbacks.notifySessionFinished(session.sessionId, session.userId, success);
            synchronized (mSessions) {
                mSessions.remove(session.sessionId);
                mHistoricalSessions.put(session.sessionId, session);
            }
            writeSessionsAsync();
        }
    }
}
