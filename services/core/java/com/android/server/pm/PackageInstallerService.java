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
import static android.net.TrafficStats.MB_IN_BYTES;
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
import android.app.PackageDeleteObserver;
import android.app.PackageInstallObserver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageInstallerCallback;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
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
import android.os.storage.StorageManager;
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
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageHelper;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.IoThread;
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
    // TODO: purge expired sessions periodically in addition to at reboot

    /** XML constants used in {@link #mSessionsFile} */
    private static final String TAG_SESSIONS = "sessions";
    private static final String TAG_SESSION = "session";
    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_INSTALLER_PACKAGE_NAME = "installerPackageName";
    private static final String ATTR_CREATED_MILLIS = "createdMillis";
    private static final String ATTR_SESSION_STAGE_DIR = "sessionStageDir";
    private static final String ATTR_SESSION_STAGE_CID = "sessionStageCid";
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
    private final StorageManager mStorage;

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

    /** Sessions allocated to legacy users */
    @GuardedBy("mSessions")
    private final SparseBooleanArray mLegacySessions = new SparseBooleanArray();

    private static final FilenameFilter sStageFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return isStageName(name);
        }
    };

    public PackageInstallerService(Context context, PackageManagerService pm, File stagingDir) {
        mContext = context;
        mPm = pm;
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mStorage = StorageManager.from(mContext);

        mStagingDir = stagingDir;

        mInstallThread = new HandlerThread(TAG);
        mInstallThread.start();

        mCallbacks = new Callbacks(mInstallThread.getLooper());

        mSessionsFile = new AtomicFile(
                new File(Environment.getSystemSecureDirectory(), "install_sessions.xml"));

        synchronized (mSessions) {
            readSessionsLocked();

            final ArraySet<File> unclaimed = Sets.newArraySet(mStagingDir.listFiles(sStageFilter));

            // Ignore stages claimed by active sessions
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                unclaimed.remove(session.internalStageDir);
            }

            // Clean up orphaned staging directories
            for (File stage : unclaimed) {
                Slog.w(TAG, "Deleting orphan stage " + stage);
                if (stage.isDirectory()) {
                    FileUtils.deleteContents(stage);
                }
                stage.delete();
            }
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
                final String cid = session.externalStageCid;

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
    public File allocateInternalStageDirLegacy() throws IOException {
        synchronized (mSessions) {
            try {
                final int sessionId = allocateSessionIdLocked();
                mLegacySessions.put(sessionId, true);
                return prepareInternalStageDir(sessionId);
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
                        } else if (session.internalStageDir != null
                                && !session.internalStageDir.exists()) {
                            Slog.w(TAG, "Abandoning internal session with missing stage "
                                    + session.internalStageDir);
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
        final String stageDirRaw = readStringAttribute(in, ATTR_SESSION_STAGE_DIR);
        final File stageDir = (stageDirRaw != null) ? new File(stageDirRaw) : null;
        final String stageCid = readStringAttribute(in, ATTR_SESSION_STAGE_CID);
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
        params.referrerUri = readUriAttribute(in, ATTR_REFERRER_URI);
        params.abiOverride = readStringAttribute(in, ATTR_ABI_OVERRIDE);

        return new PackageInstallerSession(mInternalCallback, mContext, mPm,
                mInstallThread.getLooper(), sessionId, userId, installerPackageName, params,
                createdMillis, stageDir, stageCid, sealed);
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
        final SessionParams params = session.params;

        out.startTag(null, TAG_SESSION);

        writeIntAttribute(out, ATTR_SESSION_ID, session.sessionId);
        writeIntAttribute(out, ATTR_USER_ID, session.userId);
        writeStringAttribute(out, ATTR_INSTALLER_PACKAGE_NAME,
                session.installerPackageName);
        writeLongAttribute(out, ATTR_CREATED_MILLIS, session.createdMillis);
        if (session.internalStageDir != null) {
            writeStringAttribute(out, ATTR_SESSION_STAGE_DIR,
                    session.internalStageDir.getAbsolutePath());
        }
        if (session.externalStageCid != null) {
            writeStringAttribute(out, ATTR_SESSION_STAGE_CID, session.externalStageCid);
        }
        writeBooleanAttribute(out, ATTR_SEALED, session.isSealed());

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

        // Figure out where we're going to be staging session data
        final boolean stageInternal;

        if (params.mode == SessionParams.MODE_FULL_INSTALL) {
            // Brand new install, use best resolved location. This also verifies
            // that target has enough free space for the install.
            final int resolved = PackageHelper.resolveInstallLocation(mContext,
                    params.appPackageName, params.installLocation, params.sizeBytes,
                    params.installFlags);
            if (resolved == PackageHelper.RECOMMEND_INSTALL_INTERNAL) {
                stageInternal = true;
            } else if (resolved == PackageHelper.RECOMMEND_INSTALL_EXTERNAL) {
                stageInternal = false;
            } else {
                throw new IOException("No storage with enough free space; res=" + resolved);
            }

        } else if (params.mode == SessionParams.MODE_INHERIT_EXISTING) {
            // We always stage inheriting sessions on internal storage first,
            // since we don't want to grow containers until we're sure that
            // everything looks legit.
            stageInternal = true;
            checkInternalStorage(params.sizeBytes);

            // If we have a good hunch we'll end up on external storage, verify
            // free space there too.
            final ApplicationInfo info = mPm.getApplicationInfo(params.appPackageName, 0,
                    userId);
            if (info != null && (info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
                checkExternalStorage(params.sizeBytes);

                throw new UnsupportedOperationException("TODO: finish fleshing out ASEC support");
            }

        } else {
            throw new IllegalArgumentException("Invalid install mode: " + params.mode);
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
            if (stageInternal) {
                stageDir = prepareInternalStageDir(sessionId);
            } else {
                stageCid = prepareExternalStageCid(sessionId, params.sizeBytes);
            }

            session = new PackageInstallerSession(mInternalCallback, mContext, mPm,
                    mInstallThread.getLooper(), sessionId, userId, installerPackageName, params,
                    createdMillis, stageDir, stageCid, false);
            mSessions.put(sessionId, session);
        }

        mCallbacks.notifySessionCreated(session.sessionId, session.userId);
        writeSessionsAsync();
        return sessionId;
    }

    private void checkInternalStorage(long sizeBytes) throws IOException {
        if (sizeBytes <= 0) return;

        final File target = Environment.getDataDirectory();
        final long targetBytes = sizeBytes + mStorage.getStorageLowBytes(target);

        mPm.freeStorage(targetBytes);
        if (target.getUsableSpace() < targetBytes) {
            throw new IOException("Not enough internal space to write " + sizeBytes + " bytes");
        }
    }

    private void checkExternalStorage(long sizeBytes) throws IOException {
        if (sizeBytes <= 0) return;

        final File target = new UserEnvironment(UserHandle.USER_OWNER)
                .getExternalStorageDirectory();
        final long targetBytes = sizeBytes + mStorage.getStorageLowBytes(target);

        if (target.getUsableSpace() < targetBytes) {
            throw new IOException("Not enough external space to write " + sizeBytes + " bytes");
        }
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

    private File prepareInternalStageDir(int sessionId) throws IOException {
        final File file = new File(mStagingDir, "vmdl" + sessionId + ".tmp");

        if (file.exists()) {
            throw new IOException("Session dir already exists: " + file);
        }

        try {
            Os.mkdir(file.getAbsolutePath(), 0755);
            Os.chmod(file.getAbsolutePath(), 0755);
        } catch (ErrnoException e) {
            // This purposefully throws if directory already exists
            throw new IOException("Failed to prepare session dir", e);
        }

        if (!SELinux.restorecon(file)) {
            throw new IOException("Failed to restorecon session dir");
        }

        return file;
    }

    private String prepareExternalStageCid(int sessionId, long sizeBytes) throws IOException {
        if (sizeBytes <= 0) {
            throw new IOException("Session must provide valid size for ASEC");
        }

        final String cid = "smdl" + sessionId + ".tmp";

        // Round up to nearest MB, plus another MB for filesystem overhead
        final int sizeMb = (int) ((sizeBytes + MB_IN_BYTES) / MB_IN_BYTES) + 1;

        if (PackageHelper.createSdDir(sizeMb, cid, PackageManagerService.getEncryptKey(),
                Process.SYSTEM_UID, true) == null) {
            throw new IOException("Failed to create ASEC");
        }

        return cid;
    }

    @Override
    public SessionInfo getSessionInfo(int sessionId) {
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            if (!isCallingUidOwner(session)) {
                enforceCallerCanReadSessions();
            }
            return session != null ? session.generateInfo() : null;
        }
    }

    @Override
    public List<SessionInfo> getAllSessions(int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, "getAllSessions");
        enforceCallerCanReadSessions();

        final List<SessionInfo> result = new ArrayList<>();
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
    public List<SessionInfo> getMySessions(String installerPackageName, int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, "getMySessions");
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
        return result;
    }

    @Override
    public void uninstall(String packageName, int flags, IntentSender statusReceiver, int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, "uninstall");

        final PackageDeleteObserverAdapter adapter = new PackageDeleteObserverAdapter(mContext,
                statusReceiver, packageName);
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DELETE_PACKAGES)
                == PackageManager.PERMISSION_GRANTED) {
            // Sweet, call straight through!
            mPm.deletePackage(packageName, adapter.getBinder(), userId, flags);

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

    static class PackageDeleteObserverAdapter extends PackageDeleteObserver {
        private final Context mContext;
        private final IntentSender mTarget;
        private final String mPackageName;

        public PackageDeleteObserverAdapter(Context context, IntentSender target,
                String packageName) {
            mContext = context;
            mTarget = target;
            mPackageName = packageName;
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

        public PackageInstallObserverAdapter(Context context, IntentSender target, int sessionId) {
            mContext = context;
            mTarget = target;
            mSessionId = sessionId;
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
            final Intent fillIn = new Intent();
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
                    fillIn.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, existing);
                }
            }
            try {
                mTarget.sendIntent(mContext, 0, fillIn, null, null);
            } catch (SendIntentException ignored) {
            }
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

            pw.println("Legacy install sessions:");
            pw.increaseIndent();
            pw.println(mLegacySessions.toString());
            pw.decreaseIndent();
        }
    }

    class InternalCallback {
        public void onSessionProgressChanged(PackageInstallerSession session, float progress) {
            mCallbacks.notifySessionProgressChanged(session.sessionId, session.userId, progress);
        }

        public void onSessionOpened(PackageInstallerSession session) {
            mCallbacks.notifySessionOpened(session.sessionId, session.userId);
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

        public void onSessionSealed(PackageInstallerSession session) {
            // It's very important that we block until we've recorded the
            // session as being sealed, since we never want to allow mutation
            // after sealing.
            writeSessionsLocked();
        }
    }
}
