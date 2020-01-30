/*
 * Copyright 2019 The Android Open Source Project
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
package com.android.server.blob;

import static android.app.blob.BlobStoreManager.COMMIT_RESULT_ERROR;
import static android.app.blob.BlobStoreManager.COMMIT_RESULT_SUCCESS;
import static android.app.blob.XmlTags.ATTR_VERSION;
import static android.app.blob.XmlTags.TAG_BLOB;
import static android.app.blob.XmlTags.TAG_BLOBS;
import static android.app.blob.XmlTags.TAG_SESSION;
import static android.app.blob.XmlTags.TAG_SESSIONS;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
import static android.os.UserHandle.USER_NULL;

import static com.android.server.blob.BlobStoreConfig.CURRENT_XML_VERSION;
import static com.android.server.blob.BlobStoreConfig.TAG;
import static com.android.server.blob.BlobStoreSession.STATE_ABANDONED;
import static com.android.server.blob.BlobStoreSession.STATE_COMMITTED;
import static com.android.server.blob.BlobStoreSession.STATE_VERIFIED_INVALID;
import static com.android.server.blob.BlobStoreSession.STATE_VERIFIED_VALID;
import static com.android.server.blob.BlobStoreSession.stateToString;

import android.annotation.CurrentTimeSecondsLong;
import android.annotation.IdRes;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.blob.BlobHandle;
import android.app.blob.IBlobStoreManager;
import android.app.blob.IBlobStoreSession;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.ExceptionUtils;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.blob.BlobMetadata.Committer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for maintaining and facilitating access to data blobs published by apps.
 */
public class BlobStoreManagerService extends SystemService {

    private final Object mBlobsLock = new Object();

    // Contains data of userId -> {sessionId -> {BlobStoreSession}}.
    @GuardedBy("mBlobsLock")
    private final SparseArray<LongSparseArray<BlobStoreSession>> mSessions = new SparseArray<>();

    @GuardedBy("mBlobsLock")
    private long mCurrentMaxSessionId;

    // Contains data of userId -> {BlobHandle -> {BlobMetadata}}
    @GuardedBy("mBlobsLock")
    private final SparseArray<ArrayMap<BlobHandle, BlobMetadata>> mBlobsMap = new SparseArray<>();

    private final Context mContext;
    private final Handler mHandler;
    private final Injector mInjector;
    private final SessionStateChangeListener mSessionStateChangeListener =
            new SessionStateChangeListener();

    private PackageManagerInternal mPackageManagerInternal;

    public BlobStoreManagerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    BlobStoreManagerService(Context context, Injector injector) {
        super(context);

        mContext = context;
        mInjector = injector;
        mHandler = mInjector.initializeMessageHandler();
    }

    private static Handler initializeMessageHandler() {
        final HandlerThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
        handlerThread.start();
        final Handler handler = new Handler(handlerThread.getLooper());
        Watchdog.getInstance().addThread(handler);
        return handler;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.BLOB_STORE_SERVICE, new Stub());

        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        registerReceivers();
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            synchronized (mBlobsLock) {
                final SparseArray<SparseArray<String>> allPackages = getAllPackages();
                readBlobSessionsLocked(allPackages);
                readBlobsInfoLocked(allPackages);
            }
        }
    }

    @GuardedBy("mBlobsLock")
    private long generateNextSessionIdLocked() {
        return ++mCurrentMaxSessionId;
    }

    private void registerReceivers() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(new PackageChangedReceiver(), UserHandle.ALL,
                intentFilter, null, mHandler);
    }

    @GuardedBy("mBlobsLock")
    private LongSparseArray<BlobStoreSession> getUserSessionsLocked(int userId) {
        LongSparseArray<BlobStoreSession> userSessions = mSessions.get(userId);
        if (userSessions == null) {
            userSessions = new LongSparseArray<>();
            mSessions.put(userId, userSessions);
        }
        return userSessions;
    }

    @GuardedBy("mBlobsLock")
    private ArrayMap<BlobHandle, BlobMetadata> getUserBlobsLocked(int userId) {
        ArrayMap<BlobHandle, BlobMetadata> userBlobs = mBlobsMap.get(userId);
        if (userBlobs == null) {
            userBlobs = new ArrayMap<>();
            mBlobsMap.put(userId, userBlobs);
        }
        return userBlobs;
    }

    @VisibleForTesting
    void addUserSessionsForTest(LongSparseArray<BlobStoreSession> userSessions, int userId) {
        synchronized (mBlobsLock) {
            mSessions.put(userId, userSessions);
        }
    }

    @VisibleForTesting
    void addUserBlobsForTest(ArrayMap<BlobHandle, BlobMetadata> userBlobs, int userId) {
        synchronized (mBlobsLock) {
            mBlobsMap.put(userId, userBlobs);
        }
    }

    private long createSessionInternal(BlobHandle blobHandle,
            int callingUid, String callingPackage) {
        synchronized (mBlobsLock) {
            // TODO: throw if there is already an active session associated with blobHandle.
            final long sessionId = generateNextSessionIdLocked();
            final BlobStoreSession session = new BlobStoreSession(mContext,
                    sessionId, blobHandle, callingUid, callingPackage,
                    mSessionStateChangeListener);
            getUserSessionsLocked(UserHandle.getUserId(callingUid)).put(sessionId, session);
            writeBlobSessionsAsync();
            return sessionId;
        }
    }

    private BlobStoreSession openSessionInternal(long sessionId,
            int callingUid, String callingPackage) {
        final BlobStoreSession session;
        synchronized (mBlobsLock) {
            session = getUserSessionsLocked(
                    UserHandle.getUserId(callingUid)).get(sessionId);
            if (session == null || !session.hasAccess(callingUid, callingPackage)
                    || session.isFinalized()) {
                throw new SecurityException("Session not found: " + sessionId);
            }
        }
        session.open();
        return session;
    }

    private void deleteSessionInternal(long sessionId,
            int callingUid, String callingPackage) {
        synchronized (mBlobsLock) {
            final BlobStoreSession session = openSessionInternal(sessionId,
                    callingUid, callingPackage);
            session.open();
            session.abandon();

            writeBlobSessionsAsync();
        }
    }

    private ParcelFileDescriptor openBlobInternal(BlobHandle blobHandle, int callingUid,
            String callingPackage) throws IOException {
        synchronized (mBlobsLock) {
            final BlobMetadata blobMetadata = getUserBlobsLocked(UserHandle.getUserId(callingUid))
                    .get(blobHandle);
            if (blobMetadata == null || !blobMetadata.isAccessAllowedForCaller(
                    callingPackage, callingUid)) {
                throw new SecurityException("Caller not allowed to access " + blobHandle
                        + "; callingUid=" + callingUid + ", callingPackage=" + callingPackage);
            }
            return blobMetadata.openForRead(callingPackage);
        }
    }

    private void acquireLeaseInternal(BlobHandle blobHandle, int descriptionResId,
            long leaseExpiryTimeMillis, int callingUid, String callingPackage) {
        synchronized (mBlobsLock) {
            final BlobMetadata blobMetadata = getUserBlobsLocked(UserHandle.getUserId(callingUid))
                    .get(blobHandle);
            if (blobMetadata == null || !blobMetadata.isAccessAllowedForCaller(
                    callingPackage, callingUid)) {
                throw new SecurityException("Caller not allowed to access " + blobHandle
                        + "; callingUid=" + callingUid + ", callingPackage=" + callingPackage);
            }
            if (leaseExpiryTimeMillis != 0 && leaseExpiryTimeMillis > blobHandle.expiryTimeMillis) {
                throw new IllegalArgumentException(
                        "Lease expiry cannot be later than blobs expiry time");
            }
            blobMetadata.addLeasee(callingPackage, callingUid,
                    descriptionResId, leaseExpiryTimeMillis);
            writeBlobsInfoAsync();
        }
    }

    private void releaseLeaseInternal(BlobHandle blobHandle, int callingUid,
            String callingPackage) {
        synchronized (mBlobsLock) {
            final BlobMetadata blobMetadata = getUserBlobsLocked(UserHandle.getUserId(callingUid))
                    .get(blobHandle);
            if (blobMetadata == null || !blobMetadata.isAccessAllowedForCaller(
                    callingPackage, callingUid)) {
                throw new SecurityException("Caller not allowed to access " + blobHandle
                        + "; callingUid=" + callingUid + ", callingPackage=" + callingPackage);
            }
            blobMetadata.removeLeasee(callingPackage, callingUid);
            writeBlobsInfoAsync();
        }
    }

    private void verifyCallingPackage(int callingUid, String callingPackage) {
        if (mPackageManagerInternal.getPackageUid(
                callingPackage, 0, UserHandle.getUserId(callingUid)) != callingUid) {
            throw new SecurityException("Specified calling package [" + callingPackage
                    + "] does not match the calling uid " + callingUid);
        }
    }

    class SessionStateChangeListener {
        public void onStateChanged(@NonNull BlobStoreSession session) {
            mHandler.post(PooledLambda.obtainRunnable(
                    BlobStoreManagerService::onStateChangedInternal,
                    BlobStoreManagerService.this, session));
        }
    }

    private void onStateChangedInternal(@NonNull BlobStoreSession session) {
        synchronized (mBlobsLock) {
            switch (session.getState()) {
                case STATE_ABANDONED:
                case STATE_VERIFIED_INVALID:
                    session.getSessionFile().delete();
                    getUserSessionsLocked(UserHandle.getUserId(session.getOwnerUid()))
                            .remove(session.getSessionId());
                    break;
                case STATE_COMMITTED:
                    session.verifyBlobData();
                    break;
                case STATE_VERIFIED_VALID:
                    final int userId = UserHandle.getUserId(session.getOwnerUid());
                    final ArrayMap<BlobHandle, BlobMetadata> userBlobs = getUserBlobsLocked(userId);
                    BlobMetadata blob = userBlobs.get(session.getBlobHandle());
                    if (blob == null) {
                        blob = new BlobMetadata(mContext,
                                session.getSessionId(), session.getBlobHandle(), userId);
                        userBlobs.put(session.getBlobHandle(), blob);
                    }
                    final Committer newCommitter = new Committer(session.getOwnerPackageName(),
                            session.getOwnerUid(), session.getBlobAccessMode());
                    final Committer existingCommitter = blob.getExistingCommitter(newCommitter);
                    blob.addCommitter(newCommitter);
                    try {
                        writeBlobsInfoLocked();
                        session.sendCommitCallbackResult(COMMIT_RESULT_SUCCESS);
                    } catch (Exception e) {
                        blob.addCommitter(existingCommitter);
                        session.sendCommitCallbackResult(COMMIT_RESULT_ERROR);
                    }
                    getUserSessionsLocked(UserHandle.getUserId(session.getOwnerUid()))
                            .remove(session.getSessionId());
                    break;
                default:
                    Slog.wtf(TAG, "Invalid session state: "
                            + stateToString(session.getState()));
            }
            try {
                writeBlobSessionsLocked();
            } catch (Exception e) {
                // already logged, ignore.
            }
        }
    }

    @GuardedBy("mBlobsLock")
    private void writeBlobSessionsLocked() throws Exception {
        final AtomicFile sessionsIndexFile = prepareSessionsIndexFile();
        if (sessionsIndexFile == null) {
            Slog.wtf(TAG, "Error creating sessions index file");
            return;
        }
        FileOutputStream fos = null;
        try {
            fos = sessionsIndexFile.startWrite(SystemClock.uptimeMillis());
            final XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_SESSIONS);
            XmlUtils.writeIntAttribute(out, ATTR_VERSION, CURRENT_XML_VERSION);

            for (int i = 0, userCount = mSessions.size(); i < userCount; ++i) {
                final LongSparseArray<BlobStoreSession> userSessions =
                        mSessions.valueAt(i);
                for (int j = 0, sessionsCount = userSessions.size(); j < sessionsCount; ++j) {
                    out.startTag(null, TAG_SESSION);
                    userSessions.valueAt(j).writeToXml(out);
                    out.endTag(null, TAG_SESSION);
                }
            }

            out.endTag(null, TAG_SESSIONS);
            out.endDocument();
            sessionsIndexFile.finishWrite(fos);
        } catch (Exception e) {
            sessionsIndexFile.failWrite(fos);
            Slog.wtf(TAG, "Error writing sessions data", e);
            throw e;
        }
    }

    @GuardedBy("mBlobsLock")
    private void readBlobSessionsLocked(SparseArray<SparseArray<String>> allPackages) {
        if (!BlobStoreConfig.getBlobStoreRootDir().exists()) {
            return;
        }
        final AtomicFile sessionsIndexFile = prepareSessionsIndexFile();
        if (sessionsIndexFile == null) {
            Slog.wtf(TAG, "Error creating sessions index file");
            return;
        }

        mSessions.clear();
        try (FileInputStream fis = sessionsIndexFile.openRead()) {
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, StandardCharsets.UTF_8.name());
            XmlUtils.beginDocument(in, TAG_SESSIONS);
            while (true) {
                XmlUtils.nextElement(in);
                if (in.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                if (TAG_SESSION.equals(in.getName())) {
                    final BlobStoreSession session = BlobStoreSession.createFromXml(
                            in, mContext, mSessionStateChangeListener);
                    if (session == null) {
                        continue;
                    }
                    final SparseArray<String> userPackages = allPackages.get(
                            UserHandle.getUserId(session.getOwnerUid()));
                    if (userPackages != null
                            && session.getOwnerPackageName().equals(
                                    userPackages.get(session.getOwnerUid()))) {
                        getUserSessionsLocked(UserHandle.getUserId(session.getOwnerUid())).put(
                                session.getSessionId(), session);
                    } else {
                        // Unknown package or the session data does not belong to this package.
                        session.getSessionFile().delete();
                    }
                    mCurrentMaxSessionId = Math.max(mCurrentMaxSessionId, session.getSessionId());
                }
            }
        } catch (Exception e) {
            Slog.wtf(TAG, "Error reading sessions data", e);
        }
    }

    @GuardedBy("mBlobsLock")
    private void writeBlobsInfoLocked() throws Exception {
        final AtomicFile blobsIndexFile = prepareBlobsIndexFile();
        if (blobsIndexFile == null) {
            Slog.wtf(TAG, "Error creating blobs index file");
            return;
        }
        FileOutputStream fos = null;
        try {
            fos = blobsIndexFile.startWrite(SystemClock.uptimeMillis());
            final XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_BLOBS);
            XmlUtils.writeIntAttribute(out, ATTR_VERSION, CURRENT_XML_VERSION);

            for (int i = 0, userCount = mBlobsMap.size(); i < userCount; ++i) {
                final ArrayMap<BlobHandle, BlobMetadata> userBlobs = mBlobsMap.valueAt(i);
                for (int j = 0, blobsCount = userBlobs.size(); j < blobsCount; ++j) {
                    out.startTag(null, TAG_BLOB);
                    userBlobs.valueAt(j).writeToXml(out);
                    out.endTag(null, TAG_BLOB);
                }
            }

            out.endTag(null, TAG_BLOBS);
            out.endDocument();
            blobsIndexFile.finishWrite(fos);
        } catch (Exception e) {
            blobsIndexFile.failWrite(fos);
            Slog.wtf(TAG, "Error writing blobs data", e);
            throw e;
        }
    }

    @GuardedBy("mBlobsLock")
    private void readBlobsInfoLocked(SparseArray<SparseArray<String>> allPackages) {
        if (!BlobStoreConfig.getBlobStoreRootDir().exists()) {
            return;
        }
        final AtomicFile blobsIndexFile = prepareBlobsIndexFile();
        if (blobsIndexFile == null) {
            Slog.wtf(TAG, "Error creating blobs index file");
            return;
        }

        mBlobsMap.clear();
        try (FileInputStream fis = blobsIndexFile.openRead()) {
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, StandardCharsets.UTF_8.name());
            XmlUtils.beginDocument(in, TAG_BLOBS);
            while (true) {
                XmlUtils.nextElement(in);
                if (in.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                if (TAG_BLOB.equals(in.getName())) {
                    final BlobMetadata blobMetadata = BlobMetadata.createFromXml(mContext, in);
                    final SparseArray<String> userPackages = allPackages.get(blobMetadata.userId);
                    if (userPackages == null) {
                        blobMetadata.getBlobFile().delete();
                    } else {
                        getUserBlobsLocked(blobMetadata.userId).put(
                                blobMetadata.blobHandle, blobMetadata);
                        blobMetadata.removeInvalidCommitters(userPackages);
                        blobMetadata.removeInvalidLeasees(userPackages);
                    }
                    mCurrentMaxSessionId = Math.max(mCurrentMaxSessionId, blobMetadata.blobId);
                }
            }
        } catch (Exception e) {
            Slog.wtf(TAG, "Error reading blobs data", e);
        }
    }

    private void writeBlobsInfo() {
        synchronized (mBlobsLock) {
            try {
                writeBlobsInfoLocked();
            } catch (Exception e) {
                // Already logged, ignore
            }
        }
    }

    private void writeBlobsInfoAsync() {
        mHandler.post(PooledLambda.obtainRunnable(
                BlobStoreManagerService::writeBlobsInfo,
                BlobStoreManagerService.this).recycleOnUse());
    }

    private void writeBlobSessions() {
        synchronized (mBlobsLock) {
            try {
                writeBlobSessionsLocked();
            } catch (Exception e) {
                // Already logged, ignore
            }
        }
    }

    private void writeBlobSessionsAsync() {
        mHandler.post(PooledLambda.obtainRunnable(
                BlobStoreManagerService::writeBlobSessions,
                BlobStoreManagerService.this).recycleOnUse());
    }

    private int getPackageUid(String packageName, int userId) {
        final int uid = mPackageManagerInternal.getPackageUid(
                packageName,
                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE | MATCH_UNINSTALLED_PACKAGES,
                userId);
        return uid;
    }

    private SparseArray<SparseArray<String>> getAllPackages() {
        final SparseArray<SparseArray<String>> allPackages = new SparseArray<>();
        final int[] allUsers = LocalServices.getService(UserManagerInternal.class).getUserIds();
        for (int userId : allUsers) {
            final SparseArray<String> userPackages = new SparseArray<>();
            allPackages.put(userId, userPackages);
            final List<ApplicationInfo> applicationInfos = mPackageManagerInternal
                    .getInstalledApplications(
                            MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE
                                    | MATCH_UNINSTALLED_PACKAGES,
                            userId, Process.myUid());
            for (int i = 0, count = applicationInfos.size(); i < count; ++i) {
                final ApplicationInfo applicationInfo = applicationInfos.get(i);
                userPackages.put(applicationInfo.uid, applicationInfo.packageName);
            }
        }
        return allPackages;
    }

    AtomicFile prepareSessionsIndexFile() {
        final File file = BlobStoreConfig.prepareSessionIndexFile();
        if (file == null) {
            return null;
        }
        return new AtomicFile(file, "session_index" /* commitLogTag */);
    }

    AtomicFile prepareBlobsIndexFile() {
        final File file = BlobStoreConfig.prepareBlobsIndexFile();
        if (file == null) {
            return null;
        }
        return new AtomicFile(file, "blobs_index" /* commitLogTag */);
    }

    void handlePackageRemoved(String packageName, int uid) {
        synchronized (mBlobsLock) {
            // Clean up any pending sessions
            final LongSparseArray<BlobStoreSession> userSessions =
                    getUserSessionsLocked(UserHandle.getUserId(uid));
            final ArrayList<Integer> indicesToRemove = new ArrayList<>();
            for (int i = 0, count = userSessions.size(); i < count; ++i) {
                final BlobStoreSession session = userSessions.valueAt(i);
                if (session.getOwnerUid() == uid
                        && session.getOwnerPackageName().equals(packageName)) {
                    session.getSessionFile().delete();
                    indicesToRemove.add(i);
                }
            }
            for (int i = 0, count = indicesToRemove.size(); i < count; ++i) {
                userSessions.removeAt(indicesToRemove.get(i));
            }
            writeBlobSessionsAsync();

            // Remove the package from the committer and leasee list
            final ArrayMap<BlobHandle, BlobMetadata> userBlobs =
                    getUserBlobsLocked(UserHandle.getUserId(uid));
            indicesToRemove.clear();
            for (int i = 0, count = userBlobs.size(); i < count; ++i) {
                final BlobMetadata blobMetadata = userBlobs.valueAt(i);
                blobMetadata.removeCommitter(packageName, uid);
                blobMetadata.removeLeasee(packageName, uid);
                // Delete the blob if it doesn't have any active leases.
                if (!blobMetadata.hasLeases()) {
                    blobMetadata.getBlobFile().delete();
                    indicesToRemove.add(i);
                }
            }
            for (int i = 0, count = indicesToRemove.size(); i < count; ++i) {
                userBlobs.removeAt(indicesToRemove.get(i));
            }
            writeBlobsInfoAsync();
        }
    }

    private void handleUserRemoved(int userId) {
        synchronized (mBlobsLock) {
            final LongSparseArray<BlobStoreSession> userSessions =
                    mSessions.removeReturnOld(userId);
            if (userSessions != null) {
                for (int i = 0, count = userSessions.size(); i < count; ++i) {
                    final BlobStoreSession session = userSessions.valueAt(i);
                    session.getSessionFile().delete();
                }
            }

            final ArrayMap<BlobHandle, BlobMetadata> userBlobs =
                    mBlobsMap.removeReturnOld(userId);
            if (userBlobs != null) {
                for (int i = 0, count = userBlobs.size(); i < count; ++i) {
                    final BlobMetadata blobMetadata = userBlobs.valueAt(i);
                    blobMetadata.getBlobFile().delete();
                }
            }
        }
    }

    private class PackageChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                case Intent.ACTION_PACKAGE_DATA_CLEARED:
                    final String packageName = intent.getData().getSchemeSpecificPart();
                    if (packageName == null) {
                        Slog.wtf(TAG, "Package name is missing in the intent: " + intent);
                        return;
                    }
                    final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    if (uid == -1) {
                        Slog.wtf(TAG, "uid is missing in the intent: " + intent);
                        return;
                    }
                    handlePackageRemoved(packageName, uid);
                    break;
                case Intent.ACTION_USER_REMOVED:
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                            USER_NULL);
                    if (userId == USER_NULL) {
                        Slog.wtf(TAG, "userId is missing in the intent: " + intent);
                        return;
                    }
                    handleUserRemoved(userId);
                    break;
                default:
                    Slog.wtf(TAG, "Received unknown intent: " + intent);
            }
        }
    }

    private class Stub extends IBlobStoreManager.Stub {
        @Override
        @IntRange(from = 1)
        public long createSession(@NonNull BlobHandle blobHandle,
                @NonNull String packageName) {
            Preconditions.checkNotNull(blobHandle, "blobHandle must not be null");
            Preconditions.checkNotNull(packageName, "packageName must not be null");
            // TODO: verify blobHandle.algorithm is sha-256
            // TODO: assert blobHandle is valid.

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            if (Process.isIsolated(callingUid) || mPackageManagerInternal.isInstantApp(
                    packageName, UserHandle.getUserId(callingUid))) {
                throw new SecurityException("Caller not allowed to create session; "
                        + "callingUid=" + callingUid + ", callingPackage=" + packageName);
            }

            // TODO: Verify caller request is within limits (no. of calls/blob sessions/blobs)
            return createSessionInternal(blobHandle, callingUid, packageName);
        }

        @Override
        @NonNull
        public IBlobStoreSession openSession(@IntRange(from = 1) long sessionId,
                @NonNull String packageName) {
            Preconditions.checkArgumentPositive(sessionId,
                    "sessionId must be positive: " + sessionId);
            Preconditions.checkNotNull(packageName, "packageName must not be null");

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            return openSessionInternal(sessionId, callingUid, packageName);
        }

        @Override
        public void deleteSession(@IntRange(from = 1) long sessionId,
                @NonNull String packageName) {
            Preconditions.checkArgumentPositive(sessionId,
                    "sessionId must be positive: " + sessionId);
            Preconditions.checkNotNull(packageName, "packageName must not be null");

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            deleteSessionInternal(sessionId, callingUid, packageName);
        }

        @Override
        public ParcelFileDescriptor openBlob(@NonNull BlobHandle blobHandle,
                @NonNull String packageName) {
            Preconditions.checkNotNull(blobHandle, "blobHandle must not be null");
            Preconditions.checkNotNull(packageName, "packageName must not be null");

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            if (Process.isIsolated(callingUid) || mPackageManagerInternal.isInstantApp(
                    packageName, UserHandle.getUserId(callingUid))) {
                throw new SecurityException("Caller not allowed to open blob; "
                        + "callingUid=" + callingUid + ", callingPackage=" + packageName);
            }

            try {
                return openBlobInternal(blobHandle, callingUid, packageName);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }

        @Override
        public void acquireLease(@NonNull BlobHandle blobHandle, @IdRes int descriptionResId,
                @CurrentTimeSecondsLong long leaseTimeoutSecs, @NonNull String packageName) {
            Preconditions.checkNotNull(blobHandle, "blobHandle must not be null");
            Preconditions.checkNotNull(packageName, "packageName must not be null");
            Preconditions.checkArgumentPositive(descriptionResId,
                    "descriptionResId must be positive; value=" + descriptionResId);

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            acquireLeaseInternal(blobHandle, descriptionResId, leaseTimeoutSecs,
                    callingUid, packageName);
        }

        @Override
        public void releaseLease(@NonNull BlobHandle blobHandle, @NonNull String packageName) {
            Preconditions.checkNotNull(blobHandle, "blobHandle must not be null");
            Preconditions.checkNotNull(packageName, "packageName must not be null");


            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            releaseLeaseInternal(blobHandle, callingUid, packageName);
        }

        @Override
        public void waitForIdle(@NonNull RemoteCallback remoteCallback) {
            Preconditions.checkNotNull(remoteCallback, "remoteCallback must not be null");

            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP,
                    "Caller is not allowed to call this; caller=" + Binder.getCallingUid());
            mHandler.post(PooledLambda.obtainRunnable(remoteCallback::sendResult, null)
                    .recycleOnUse());
        }

        @Override
        public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
                @Nullable String[] args) {
            // TODO: add proto-based version of this.
            if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;

            final IndentingPrintWriter fout = new IndentingPrintWriter(writer, "    ");
            synchronized (mBlobsLock) {
                fout.println("mCurrentMaxSessionId: " + mCurrentMaxSessionId);
                fout.println();
                for (int i = 0, userCount = mSessions.size(); i < userCount; ++i) {
                    final int userId = mSessions.keyAt(i);
                    final LongSparseArray<BlobStoreSession> userSessions = mSessions.valueAt(i);
                    fout.println("List of sessions in user #"
                            + userId + " (" + userSessions.size() + "):");
                    fout.increaseIndent();
                    for (int j = 0, sessionsCount = userSessions.size(); j < sessionsCount; ++j) {
                        final long sessionId = userSessions.keyAt(j);
                        final BlobStoreSession session = userSessions.valueAt(j);
                        fout.println("Session #" + sessionId);
                        fout.increaseIndent();
                        session.dump(fout);
                        fout.decreaseIndent();
                    }
                    fout.decreaseIndent();
                }

                fout.print("\n\n");

                for (int i = 0, userCount = mBlobsMap.size(); i < userCount; ++i) {
                    final int userId = mBlobsMap.keyAt(i);
                    final ArrayMap<BlobHandle, BlobMetadata> userBlobs = mBlobsMap.valueAt(i);
                    fout.println("List of blobs in user #"
                            + userId + " (" + userBlobs.size() + "):");
                    fout.increaseIndent();
                    for (int j = 0, blobsCount = userBlobs.size(); j < blobsCount; ++j) {
                        final BlobMetadata blobMetadata = userBlobs.valueAt(j);
                        fout.println("Blob #" + blobMetadata.blobId);
                        fout.increaseIndent();
                        blobMetadata.dump(fout);
                        fout.decreaseIndent();
                    }
                    fout.decreaseIndent();
                }
            }
        }
    }

    @VisibleForTesting
    static class Injector {
        public Handler initializeMessageHandler() {
            return BlobStoreManagerService.initializeMessageHandler();
        }
    }
}