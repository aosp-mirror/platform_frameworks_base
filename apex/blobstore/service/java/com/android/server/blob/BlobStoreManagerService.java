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

import static com.android.server.blob.BlobStoreConfig.LOGV;
import static com.android.server.blob.BlobStoreConfig.SESSION_EXPIRY_TIMEOUT_MILLIS;
import static com.android.server.blob.BlobStoreConfig.TAG;
import static com.android.server.blob.BlobStoreConfig.XML_VERSION_CURRENT;
import static com.android.server.blob.BlobStoreSession.STATE_ABANDONED;
import static com.android.server.blob.BlobStoreSession.STATE_COMMITTED;
import static com.android.server.blob.BlobStoreSession.STATE_VERIFIED_INVALID;
import static com.android.server.blob.BlobStoreSession.STATE_VERIFIED_VALID;
import static com.android.server.blob.BlobStoreSession.stateToString;
import static com.android.server.blob.BlobStoreUtils.getDescriptionResourceId;
import static com.android.server.blob.BlobStoreUtils.getPackageResources;

import android.annotation.CurrentTimeSecondsLong;
import android.annotation.IdRes;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.blob.BlobHandle;
import android.app.blob.BlobInfo;
import android.app.blob.IBlobStoreManager;
import android.app.blob.IBlobStoreSession;
import android.app.blob.LeaseInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageStats;
import android.content.res.ResourceId;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.LimitExceededException;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.ExceptionUtils;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.CollectionUtils;
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
import com.android.server.usage.StorageStatsManagerInternal;
import com.android.server.usage.StorageStatsManagerInternal.StorageStatsAugmenter;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

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

    // Contains all ids that are currently in use.
    @GuardedBy("mBlobsLock")
    private final ArraySet<Long> mActiveBlobIds = new ArraySet<>();
    // Contains all ids that are currently in use and those that were in use but got deleted in the
    // current boot session.
    @GuardedBy("mBlobsLock")
    private final ArraySet<Long> mKnownBlobIds = new ArraySet<>();

    // Random number generator for new session ids.
    private final Random mRandom = new SecureRandom();

    private final Context mContext;
    private final Handler mHandler;
    private final Handler mBackgroundHandler;
    private final Injector mInjector;
    private final SessionStateChangeListener mSessionStateChangeListener =
            new SessionStateChangeListener();

    private PackageManagerInternal mPackageManagerInternal;

    private final Runnable mSaveBlobsInfoRunnable = this::writeBlobsInfo;
    private final Runnable mSaveSessionsRunnable = this::writeBlobSessions;

    public BlobStoreManagerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    BlobStoreManagerService(Context context, Injector injector) {
        super(context);

        mContext = context;
        mInjector = injector;
        mHandler = mInjector.initializeMessageHandler();
        mBackgroundHandler = mInjector.getBackgroundHandler();
    }

    private static Handler initializeMessageHandler() {
        final HandlerThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_DEFAULT, true /* allowIo */);
        handlerThread.start();
        final Handler handler = new Handler(handlerThread.getLooper());
        Watchdog.getInstance().addThread(handler);
        return handler;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.BLOB_STORE_SERVICE, new Stub());
        LocalServices.addService(BlobStoreManagerInternal.class, new LocalService());

        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        registerReceivers();
        LocalServices.getService(StorageStatsManagerInternal.class)
                .registerStorageStatsAugmenter(new BlobStorageStatsAugmenter(), TAG);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            BlobStoreConfig.initialize(mContext);
        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            synchronized (mBlobsLock) {
                final SparseArray<SparseArray<String>> allPackages = getAllPackages();
                readBlobSessionsLocked(allPackages);
                readBlobsInfoLocked(allPackages);
            }
        } else if (phase == PHASE_BOOT_COMPLETED) {
            BlobStoreIdleJobService.schedule(mContext);
        }
    }

    @GuardedBy("mBlobsLock")
    private long generateNextSessionIdLocked() {
        // Logic borrowed from PackageInstallerService.
        int n = 0;
        long sessionId;
        do {
            sessionId = Math.abs(mRandom.nextLong());
            if (mKnownBlobIds.indexOf(sessionId) < 0 && sessionId != 0) {
                return sessionId;
            }
        } while (n++ < 32);
        throw new IllegalStateException("Failed to allocate session ID");
    }

    private void registerReceivers() {
        final IntentFilter packageChangedFilter = new IntentFilter();
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        packageChangedFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(new PackageChangedReceiver(), UserHandle.ALL,
                packageChangedFilter, null, mHandler);

        final IntentFilter userActionFilter = new IntentFilter();
        userActionFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(new UserActionReceiver(), UserHandle.ALL,
                userActionFilter, null, mHandler);
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

    @VisibleForTesting
    void addActiveIdsForTest(long... activeIds) {
        synchronized (mBlobsLock) {
            for (long id : activeIds) {
                addActiveBlobIdLocked(id);
            }
        }
    }

    @VisibleForTesting
    Set<Long> getActiveIdsForTest() {
        synchronized (mBlobsLock) {
            return mActiveBlobIds;
        }
    }

    @VisibleForTesting
    Set<Long> getKnownIdsForTest() {
        synchronized (mBlobsLock) {
            return mKnownBlobIds;
        }
    }

    @GuardedBy("mBlobsLock")
    private void addSessionForUserLocked(BlobStoreSession session, int userId) {
        getUserSessionsLocked(userId).put(session.getSessionId(), session);
        addActiveBlobIdLocked(session.getSessionId());
    }

    @GuardedBy("mBlobsLock")
    private void addBlobForUserLocked(BlobMetadata blobMetadata, int userId) {
        addBlobForUserLocked(blobMetadata, getUserBlobsLocked(userId));
    }

    @GuardedBy("mBlobsLock")
    private void addBlobForUserLocked(BlobMetadata blobMetadata,
            ArrayMap<BlobHandle, BlobMetadata> userBlobs) {
        userBlobs.put(blobMetadata.getBlobHandle(), blobMetadata);
        addActiveBlobIdLocked(blobMetadata.getBlobId());
    }

    @GuardedBy("mBlobsLock")
    private void addActiveBlobIdLocked(long id) {
        mActiveBlobIds.add(id);
        mKnownBlobIds.add(id);
    }

    private long createSessionInternal(BlobHandle blobHandle,
            int callingUid, String callingPackage) {
        synchronized (mBlobsLock) {
            // TODO: throw if there is already an active session associated with blobHandle.
            final long sessionId = generateNextSessionIdLocked();
            final BlobStoreSession session = new BlobStoreSession(mContext,
                    sessionId, blobHandle, callingUid, callingPackage,
                    mSessionStateChangeListener);
            addSessionForUserLocked(session, UserHandle.getUserId(callingUid));
            if (LOGV) {
                Slog.v(TAG, "Created session for " + blobHandle
                        + "; callingUid=" + callingUid + ", callingPackage=" + callingPackage);
            }
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

    private void abandonSessionInternal(long sessionId,
            int callingUid, String callingPackage) {
        synchronized (mBlobsLock) {
            final BlobStoreSession session = openSessionInternal(sessionId,
                    callingUid, callingPackage);
            session.open();
            session.abandon();
            if (LOGV) {
                Slog.v(TAG, "Abandoned session with id " + sessionId
                        + "; callingUid=" + callingUid + ", callingPackage=" + callingPackage);
            }
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
            CharSequence description, long leaseExpiryTimeMillis,
            int callingUid, String callingPackage) {
        synchronized (mBlobsLock) {
            final BlobMetadata blobMetadata = getUserBlobsLocked(UserHandle.getUserId(callingUid))
                    .get(blobHandle);
            if (blobMetadata == null || !blobMetadata.isAccessAllowedForCaller(
                    callingPackage, callingUid)) {
                throw new SecurityException("Caller not allowed to access " + blobHandle
                        + "; callingUid=" + callingUid + ", callingPackage=" + callingPackage);
            }
            if (leaseExpiryTimeMillis != 0 && blobHandle.expiryTimeMillis != 0
                    && leaseExpiryTimeMillis > blobHandle.expiryTimeMillis) {
                throw new IllegalArgumentException(
                        "Lease expiry cannot be later than blobs expiry time");
            }
            if (blobMetadata.getSize()
                    > getRemainingLeaseQuotaBytesInternal(callingUid, callingPackage)) {
                throw new LimitExceededException("Total amount of data with an active lease"
                        + " is exceeding the max limit");
            }
            blobMetadata.addOrReplaceLeasee(callingPackage, callingUid,
                    descriptionResId, description, leaseExpiryTimeMillis);
            if (LOGV) {
                Slog.v(TAG, "Acquired lease on " + blobHandle
                        + "; callingUid=" + callingUid + ", callingPackage=" + callingPackage);
            }
            writeBlobsInfoAsync();
        }
    }

    @VisibleForTesting
    @GuardedBy("mBlobsLock")
    long getTotalUsageBytesLocked(int callingUid, String callingPackage) {
        final AtomicLong totalBytes = new AtomicLong(0);
        forEachBlobInUser((blobMetadata) -> {
            if (blobMetadata.isALeasee(callingPackage, callingUid)) {
                totalBytes.getAndAdd(blobMetadata.getSize());
            }
        }, UserHandle.getUserId(callingUid));
        return totalBytes.get();
    }

    private void releaseLeaseInternal(BlobHandle blobHandle, int callingUid,
            String callingPackage) {
        synchronized (mBlobsLock) {
            final ArrayMap<BlobHandle, BlobMetadata> userBlobs =
                    getUserBlobsLocked(UserHandle.getUserId(callingUid));
            final BlobMetadata blobMetadata = userBlobs.get(blobHandle);
            if (blobMetadata == null || !blobMetadata.isAccessAllowedForCaller(
                    callingPackage, callingUid)) {
                throw new SecurityException("Caller not allowed to access " + blobHandle
                        + "; callingUid=" + callingUid + ", callingPackage=" + callingPackage);
            }
            blobMetadata.removeLeasee(callingPackage, callingUid);
            if (LOGV) {
                Slog.v(TAG, "Released lease on " + blobHandle
                        + "; callingUid=" + callingUid + ", callingPackage=" + callingPackage);
            }
            if (blobMetadata.shouldBeDeleted(true /* respectLeaseWaitTime */)) {
                deleteBlobLocked(blobMetadata);
                userBlobs.remove(blobHandle);
            }
            writeBlobsInfoAsync();
        }
    }

    private long getRemainingLeaseQuotaBytesInternal(int callingUid, String callingPackage) {
        synchronized (mBlobsLock) {
            final long remainingQuota = BlobStoreConfig.getAppDataBytesLimit()
                    - getTotalUsageBytesLocked(callingUid, callingPackage);
            return remainingQuota > 0 ? remainingQuota : 0;
        }
    }

    private List<BlobInfo> queryBlobsForUserInternal(int userId) {
        final ArrayList<BlobInfo> blobInfos = new ArrayList<>();
        synchronized (mBlobsLock) {
            final ArrayMap<String, WeakReference<Resources>> resources = new ArrayMap<>();
            final Function<String, Resources> resourcesGetter = (packageName) -> {
                final WeakReference<Resources> resourcesRef = resources.get(packageName);
                Resources packageResources = resourcesRef == null ? null : resourcesRef.get();
                if (packageResources == null) {
                    packageResources = getPackageResources(mContext, packageName, userId);
                    resources.put(packageName, new WeakReference<>(packageResources));
                }
                return packageResources;
            };
            getUserBlobsLocked(userId).forEach((blobHandle, blobMetadata) -> {
                final ArrayList<LeaseInfo> leaseInfos = new ArrayList<>();
                blobMetadata.forEachLeasee(leasee -> {
                    final int descriptionResId = leasee.descriptionResEntryName == null
                            ? Resources.ID_NULL
                            : getDescriptionResourceId(resourcesGetter.apply(leasee.packageName),
                                    leasee.descriptionResEntryName, leasee.packageName);
                    leaseInfos.add(new LeaseInfo(leasee.packageName, leasee.expiryTimeMillis,
                            descriptionResId, leasee.description));
                });
                blobInfos.add(new BlobInfo(blobMetadata.getBlobId(),
                        blobHandle.getExpiryTimeMillis(), blobHandle.getLabel(), leaseInfos));
            });
        }
        return blobInfos;
    }

    private void deleteBlobInternal(long blobId, int callingUid) {
        synchronized (mBlobsLock) {
            final ArrayMap<BlobHandle, BlobMetadata> userBlobs = getUserBlobsLocked(
                    UserHandle.getUserId(callingUid));
            userBlobs.entrySet().removeIf(entry -> {
                final BlobMetadata blobMetadata = entry.getValue();
                return blobMetadata.getBlobId() == blobId;
            });
            writeBlobsInfoAsync();
        }
    }

    private List<BlobHandle> getLeasedBlobsInternal(int callingUid,
            @NonNull String callingPackage) {
        final ArrayList<BlobHandle> leasedBlobs = new ArrayList<>();
        forEachBlobInUser(blobMetadata -> {
            if (blobMetadata.isALeasee(callingPackage, callingUid)) {
                leasedBlobs.add(blobMetadata.getBlobHandle());
            }
        }, UserHandle.getUserId(callingUid));
        return leasedBlobs;
    }

    private LeaseInfo getLeaseInfoInternal(BlobHandle blobHandle,
            int callingUid, @NonNull String callingPackage) {
        synchronized (mBlobsLock) {
            final BlobMetadata blobMetadata = getUserBlobsLocked(UserHandle.getUserId(callingUid))
                    .get(blobHandle);
            if (blobMetadata == null || !blobMetadata.isAccessAllowedForCaller(
                    callingPackage, callingUid)) {
                throw new SecurityException("Caller not allowed to access " + blobHandle
                        + "; callingUid=" + callingUid + ", callingPackage=" + callingPackage);
            }
            return blobMetadata.getLeaseInfo(callingPackage, callingUid);
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
                    BlobStoreManagerService.this, session).recycleOnUse());
        }
    }

    private void onStateChangedInternal(@NonNull BlobStoreSession session) {
        switch (session.getState()) {
            case STATE_ABANDONED:
            case STATE_VERIFIED_INVALID:
                session.getSessionFile().delete();
                synchronized (mBlobsLock) {
                    getUserSessionsLocked(UserHandle.getUserId(session.getOwnerUid()))
                            .remove(session.getSessionId());
                    mActiveBlobIds.remove(session.getSessionId());
                    if (LOGV) {
                        Slog.v(TAG, "Session is invalid; deleted " + session);
                    }
                }
                break;
            case STATE_COMMITTED:
                mBackgroundHandler.post(() -> {
                    session.computeDigest();
                    mHandler.post(PooledLambda.obtainRunnable(
                            BlobStoreSession::verifyBlobData, session).recycleOnUse());
                });
                break;
            case STATE_VERIFIED_VALID:
                synchronized (mBlobsLock) {
                    final int userId = UserHandle.getUserId(session.getOwnerUid());
                    final ArrayMap<BlobHandle, BlobMetadata> userBlobs = getUserBlobsLocked(
                            userId);
                    BlobMetadata blob = userBlobs.get(session.getBlobHandle());
                    if (blob == null) {
                        blob = new BlobMetadata(mContext,
                                session.getSessionId(), session.getBlobHandle(), userId);
                        addBlobForUserLocked(blob, userBlobs);
                    }
                    final Committer newCommitter = new Committer(session.getOwnerPackageName(),
                            session.getOwnerUid(), session.getBlobAccessMode());
                    final Committer existingCommitter = blob.getExistingCommitter(newCommitter);
                    blob.addOrReplaceCommitter(newCommitter);
                    try {
                        writeBlobsInfoLocked();
                        session.sendCommitCallbackResult(COMMIT_RESULT_SUCCESS);
                    } catch (Exception e) {
                        if (existingCommitter == null) {
                            blob.removeCommitter(newCommitter);
                        } else {
                            blob.addOrReplaceCommitter(existingCommitter);
                        }
                        session.sendCommitCallbackResult(COMMIT_RESULT_ERROR);
                    }
                    getUserSessionsLocked(UserHandle.getUserId(session.getOwnerUid()))
                            .remove(session.getSessionId());
                    if (LOGV) {
                        Slog.v(TAG, "Successfully committed session " + session);
                    }
                }
                break;
            default:
                Slog.wtf(TAG, "Invalid session state: "
                        + stateToString(session.getState()));
        }
        synchronized (mBlobsLock) {
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
            XmlUtils.writeIntAttribute(out, ATTR_VERSION, XML_VERSION_CURRENT);

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
            if (LOGV) {
                Slog.v(TAG, "Finished persisting sessions data");
            }
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
        } else if (!sessionsIndexFile.exists()) {
            Slog.w(TAG, "Sessions index file not available: " + sessionsIndexFile.getBaseFile());
            return;
        }

        mSessions.clear();
        try (FileInputStream fis = sessionsIndexFile.openRead()) {
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, StandardCharsets.UTF_8.name());
            XmlUtils.beginDocument(in, TAG_SESSIONS);
            final int version = XmlUtils.readIntAttribute(in, ATTR_VERSION);
            while (true) {
                XmlUtils.nextElement(in);
                if (in.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                if (TAG_SESSION.equals(in.getName())) {
                    final BlobStoreSession session = BlobStoreSession.createFromXml(
                            in, version, mContext, mSessionStateChangeListener);
                    if (session == null) {
                        continue;
                    }
                    final SparseArray<String> userPackages = allPackages.get(
                            UserHandle.getUserId(session.getOwnerUid()));
                    if (userPackages != null
                            && session.getOwnerPackageName().equals(
                                    userPackages.get(session.getOwnerUid()))) {
                        addSessionForUserLocked(session,
                                UserHandle.getUserId(session.getOwnerUid()));
                    } else {
                        // Unknown package or the session data does not belong to this package.
                        session.getSessionFile().delete();
                    }
                    mCurrentMaxSessionId = Math.max(mCurrentMaxSessionId, session.getSessionId());
                }
            }
            if (LOGV) {
                Slog.v(TAG, "Finished reading sessions data");
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
            XmlUtils.writeIntAttribute(out, ATTR_VERSION, XML_VERSION_CURRENT);

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
            if (LOGV) {
                Slog.v(TAG, "Finished persisting blobs data");
            }
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
        } else if (!blobsIndexFile.exists()) {
            Slog.w(TAG, "Blobs index file not available: " + blobsIndexFile.getBaseFile());
            return;
        }

        mBlobsMap.clear();
        try (FileInputStream fis = blobsIndexFile.openRead()) {
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, StandardCharsets.UTF_8.name());
            XmlUtils.beginDocument(in, TAG_BLOBS);
            final int version = XmlUtils.readIntAttribute(in, ATTR_VERSION);
            while (true) {
                XmlUtils.nextElement(in);
                if (in.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                if (TAG_BLOB.equals(in.getName())) {
                    final BlobMetadata blobMetadata = BlobMetadata.createFromXml(
                            in, version, mContext);
                    final SparseArray<String> userPackages = allPackages.get(
                            blobMetadata.getUserId());
                    if (userPackages == null) {
                        blobMetadata.getBlobFile().delete();
                    } else {
                        addBlobForUserLocked(blobMetadata, blobMetadata.getUserId());
                        blobMetadata.removeInvalidCommitters(userPackages);
                        blobMetadata.removeInvalidLeasees(userPackages);
                    }
                    mCurrentMaxSessionId = Math.max(mCurrentMaxSessionId, blobMetadata.getBlobId());
                }
            }
            if (LOGV) {
                Slog.v(TAG, "Finished reading blobs data");
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
        if (!mHandler.hasCallbacks(mSaveBlobsInfoRunnable)) {
            mHandler.post(mSaveBlobsInfoRunnable);
        }
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
        if (!mHandler.hasCallbacks(mSaveSessionsRunnable)) {
            mHandler.post(mSaveSessionsRunnable);
        }
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

    @VisibleForTesting
    void handlePackageRemoved(String packageName, int uid) {
        synchronized (mBlobsLock) {
            // Clean up any pending sessions
            final LongSparseArray<BlobStoreSession> userSessions =
                    getUserSessionsLocked(UserHandle.getUserId(uid));
            userSessions.removeIf((sessionId, blobStoreSession) -> {
                if (blobStoreSession.getOwnerUid() == uid
                        && blobStoreSession.getOwnerPackageName().equals(packageName)) {
                    blobStoreSession.getSessionFile().delete();
                    mActiveBlobIds.remove(blobStoreSession.getSessionId());
                    return true;
                }
                return false;
            });
            writeBlobSessionsAsync();

            // Remove the package from the committer and leasee list
            final ArrayMap<BlobHandle, BlobMetadata> userBlobs =
                    getUserBlobsLocked(UserHandle.getUserId(uid));
            userBlobs.entrySet().removeIf(entry -> {
                final BlobMetadata blobMetadata = entry.getValue();
                final boolean isACommitter = blobMetadata.isACommitter(packageName, uid);
                if (isACommitter) {
                    blobMetadata.removeCommitter(packageName, uid);
                }
                blobMetadata.removeLeasee(packageName, uid);
                // Regardless of when the blob is committed, we need to delete
                // it if it was from the deleted package to ensure we delete all traces of it.
                if (blobMetadata.shouldBeDeleted(isACommitter /* respectLeaseWaitTime */)) {
                    deleteBlobLocked(blobMetadata);
                    return true;
                }
                return false;
            });
            writeBlobsInfoAsync();

            if (LOGV) {
                Slog.v(TAG, "Removed blobs data associated with pkg="
                        + packageName + ", uid=" + uid);
            }
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
                    mActiveBlobIds.remove(session.getSessionId());
                }
            }

            final ArrayMap<BlobHandle, BlobMetadata> userBlobs =
                    mBlobsMap.removeReturnOld(userId);
            if (userBlobs != null) {
                for (int i = 0, count = userBlobs.size(); i < count; ++i) {
                    final BlobMetadata blobMetadata = userBlobs.valueAt(i);
                    deleteBlobLocked(blobMetadata);
                }
            }
            if (LOGV) {
                Slog.v(TAG, "Removed blobs data in user " + userId);
            }
        }
    }

    @GuardedBy("mBlobsLock")
    @VisibleForTesting
    void handleIdleMaintenanceLocked() {
        // Cleanup any left over data on disk that is not part of index.
        final ArrayList<Long> deletedBlobIds = new ArrayList<>();
        final ArrayList<File> filesToDelete = new ArrayList<>();
        final File blobsDir = BlobStoreConfig.getBlobsDir();
        if (blobsDir.exists()) {
            for (File file : blobsDir.listFiles()) {
                try {
                    final long id = Long.parseLong(file.getName());
                    if (mActiveBlobIds.indexOf(id) < 0) {
                        filesToDelete.add(file);
                        deletedBlobIds.add(id);
                    }
                } catch (NumberFormatException e) {
                    Slog.wtf(TAG, "Error parsing the file name: " + file, e);
                    filesToDelete.add(file);
                }
            }
            for (int i = 0, count = filesToDelete.size(); i < count; ++i) {
                filesToDelete.get(i).delete();
            }
        }

        // Cleanup any stale blobs.
        for (int i = 0, userCount = mBlobsMap.size(); i < userCount; ++i) {
            final ArrayMap<BlobHandle, BlobMetadata> userBlobs = mBlobsMap.valueAt(i);
            userBlobs.entrySet().removeIf(entry -> {
                final BlobMetadata blobMetadata = entry.getValue();

                if (blobMetadata.shouldBeDeleted(true /* respectLeaseWaitTime */)) {
                    deleteBlobLocked(blobMetadata);
                    deletedBlobIds.add(blobMetadata.getBlobId());
                    return true;
                }
                return false;
            });
        }
        writeBlobsInfoAsync();

        // Cleanup any stale sessions.
        for (int i = 0, userCount = mSessions.size(); i < userCount; ++i) {
            final LongSparseArray<BlobStoreSession> userSessions = mSessions.valueAt(i);
            userSessions.removeIf((sessionId, blobStoreSession) -> {
                boolean shouldRemove = false;

                // Cleanup sessions which haven't been modified in a while.
                if (blobStoreSession.getSessionFile().lastModified()
                        < System.currentTimeMillis() - SESSION_EXPIRY_TIMEOUT_MILLIS) {
                    shouldRemove = true;
                }

                // Cleanup sessions with already expired data.
                if (blobStoreSession.getBlobHandle().isExpired()) {
                    shouldRemove = true;
                }

                if (shouldRemove) {
                    blobStoreSession.getSessionFile().delete();
                    mActiveBlobIds.remove(blobStoreSession.getSessionId());
                    deletedBlobIds.add(blobStoreSession.getSessionId());
                }
                return shouldRemove;
            });
        }
        if (LOGV) {
            Slog.v(TAG, "Completed idle maintenance; deleted "
                    + Arrays.toString(deletedBlobIds.toArray()));
        }
        writeBlobSessionsAsync();
    }

    @GuardedBy("mBlobsLock")
    private void deleteBlobLocked(BlobMetadata blobMetadata) {
        blobMetadata.getBlobFile().delete();
        mActiveBlobIds.remove(blobMetadata.getBlobId());
    }

    void runClearAllSessions(@UserIdInt int userId) {
        synchronized (mBlobsLock) {
            if (userId == UserHandle.USER_ALL) {
                mSessions.clear();
            } else {
                mSessions.remove(userId);
            }
            writeBlobSessionsAsync();
        }
    }

    void runClearAllBlobs(@UserIdInt int userId) {
        synchronized (mBlobsLock) {
            if (userId == UserHandle.USER_ALL) {
                mBlobsMap.clear();
            } else {
                mBlobsMap.remove(userId);
            }
            writeBlobsInfoAsync();
        }
    }

    void deleteBlob(@NonNull BlobHandle blobHandle, @UserIdInt int userId) {
        synchronized (mBlobsLock) {
            final ArrayMap<BlobHandle, BlobMetadata> userBlobs = getUserBlobsLocked(userId);
            final BlobMetadata blobMetadata = userBlobs.get(blobHandle);
            if (blobMetadata == null) {
                return;
            }
            deleteBlobLocked(blobMetadata);
            userBlobs.remove(blobHandle);
            writeBlobsInfoAsync();
        }
    }

    void runIdleMaintenance() {
        synchronized (mBlobsLock) {
            handleIdleMaintenanceLocked();
        }
    }

    @GuardedBy("mBlobsLock")
    private void dumpSessionsLocked(IndentingPrintWriter fout, DumpArgs dumpArgs) {
        for (int i = 0, userCount = mSessions.size(); i < userCount; ++i) {
            final int userId = mSessions.keyAt(i);
            if (!dumpArgs.shouldDumpUser(userId)) {
                continue;
            }
            final LongSparseArray<BlobStoreSession> userSessions = mSessions.valueAt(i);
            fout.println("List of sessions in user #"
                    + userId + " (" + userSessions.size() + "):");
            fout.increaseIndent();
            for (int j = 0, sessionsCount = userSessions.size(); j < sessionsCount; ++j) {
                final long sessionId = userSessions.keyAt(j);
                final BlobStoreSession session = userSessions.valueAt(j);
                if (!dumpArgs.shouldDumpSession(session.getOwnerPackageName(),
                        session.getOwnerUid(), session.getSessionId())) {
                    continue;
                }
                fout.println("Session #" + sessionId);
                fout.increaseIndent();
                session.dump(fout, dumpArgs);
                fout.decreaseIndent();
            }
            fout.decreaseIndent();
        }
    }

    @GuardedBy("mBlobsLock")
    private void dumpBlobsLocked(IndentingPrintWriter fout, DumpArgs dumpArgs) {
        for (int i = 0, userCount = mBlobsMap.size(); i < userCount; ++i) {
            final int userId = mBlobsMap.keyAt(i);
            if (!dumpArgs.shouldDumpUser(userId)) {
                continue;
            }
            final ArrayMap<BlobHandle, BlobMetadata> userBlobs = mBlobsMap.valueAt(i);
            fout.println("List of blobs in user #"
                    + userId + " (" + userBlobs.size() + "):");
            fout.increaseIndent();
            for (int j = 0, blobsCount = userBlobs.size(); j < blobsCount; ++j) {
                final BlobMetadata blobMetadata = userBlobs.valueAt(j);
                if (!dumpArgs.shouldDumpBlob(blobMetadata.getBlobId())) {
                    continue;
                }
                fout.println("Blob #" + blobMetadata.getBlobId());
                fout.increaseIndent();
                blobMetadata.dump(fout, dumpArgs);
                fout.decreaseIndent();
            }
            fout.decreaseIndent();
        }
    }

    private class BlobStorageStatsAugmenter implements StorageStatsAugmenter {
        @Override
        public void augmentStatsForPackage(@NonNull PackageStats stats, @NonNull String packageName,
                @UserIdInt int userId, boolean callerHasStatsPermission) {
            final AtomicLong blobsDataSize = new AtomicLong(0);
            forEachSessionInUser(session -> {
                if (session.getOwnerPackageName().equals(packageName)) {
                    blobsDataSize.getAndAdd(session.getSize());
                }
            }, userId);

            forEachBlobInUser(blobMetadata -> {
                if (blobMetadata.isALeasee(packageName)) {
                    if (!blobMetadata.hasOtherLeasees(packageName) || !callerHasStatsPermission) {
                        blobsDataSize.getAndAdd(blobMetadata.getSize());
                    }
                }
            }, userId);

            stats.dataSize += blobsDataSize.get();
        }

        @Override
        public void augmentStatsForUid(@NonNull PackageStats stats, int uid,
                boolean callerHasStatsPermission) {
            final int userId = UserHandle.getUserId(uid);
            final AtomicLong blobsDataSize = new AtomicLong(0);
            forEachSessionInUser(session -> {
                if (session.getOwnerUid() == uid) {
                    blobsDataSize.getAndAdd(session.getSize());
                }
            }, userId);

            forEachBlobInUser(blobMetadata -> {
                if (blobMetadata.isALeasee(uid)) {
                    if (!blobMetadata.hasOtherLeasees(uid) || !callerHasStatsPermission) {
                        blobsDataSize.getAndAdd(blobMetadata.getSize());
                    }
                }
            }, userId);

            stats.dataSize += blobsDataSize.get();
        }
    }

    private void forEachSessionInUser(Consumer<BlobStoreSession> consumer, int userId) {
        synchronized (mBlobsLock) {
            final LongSparseArray<BlobStoreSession> userSessions = getUserSessionsLocked(userId);
            for (int i = 0, count = userSessions.size(); i < count; ++i) {
                final BlobStoreSession session = userSessions.valueAt(i);
                consumer.accept(session);
            }
        }
    }

    private void forEachBlobInUser(Consumer<BlobMetadata> consumer, int userId) {
        synchronized (mBlobsLock) {
            final ArrayMap<BlobHandle, BlobMetadata> userBlobs = getUserBlobsLocked(userId);
            for (int i = 0, count = userBlobs.size(); i < count; ++i) {
                final BlobMetadata blobMetadata = userBlobs.valueAt(i);
                consumer.accept(blobMetadata);
            }
        }
    }

    private class PackageChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LOGV) {
                Slog.v(TAG, "Received " + intent);
            }
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
                default:
                    Slog.wtf(TAG, "Received unknown intent: " + intent);
            }
        }
    }

    private class UserActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LOGV) {
                Slog.v(TAG, "Received: " + intent);
            }
            switch (intent.getAction()) {
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
            Objects.requireNonNull(blobHandle, "blobHandle must not be null");
            blobHandle.assertIsValid();
            Objects.requireNonNull(packageName, "packageName must not be null");

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
            Objects.requireNonNull(packageName, "packageName must not be null");

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            return openSessionInternal(sessionId, callingUid, packageName);
        }

        @Override
        public void abandonSession(@IntRange(from = 1) long sessionId,
                @NonNull String packageName) {
            Preconditions.checkArgumentPositive(sessionId,
                    "sessionId must be positive: " + sessionId);
            Objects.requireNonNull(packageName, "packageName must not be null");

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            abandonSessionInternal(sessionId, callingUid, packageName);
        }

        @Override
        public ParcelFileDescriptor openBlob(@NonNull BlobHandle blobHandle,
                @NonNull String packageName) {
            Objects.requireNonNull(blobHandle, "blobHandle must not be null");
            blobHandle.assertIsValid();
            Objects.requireNonNull(packageName, "packageName must not be null");

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
                @Nullable CharSequence description,
                @CurrentTimeSecondsLong long leaseExpiryTimeMillis, @NonNull String packageName) {
            Objects.requireNonNull(blobHandle, "blobHandle must not be null");
            blobHandle.assertIsValid();
            Preconditions.checkArgument(
                    ResourceId.isValid(descriptionResId) || description != null,
                    "Description must be valid; descriptionId=" + descriptionResId
                            + ", description=" + description);
            Preconditions.checkArgumentNonnegative(leaseExpiryTimeMillis,
                    "leaseExpiryTimeMillis must not be negative");
            Objects.requireNonNull(packageName, "packageName must not be null");

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            if (Process.isIsolated(callingUid) || mPackageManagerInternal.isInstantApp(
                    packageName, UserHandle.getUserId(callingUid))) {
                throw new SecurityException("Caller not allowed to open blob; "
                        + "callingUid=" + callingUid + ", callingPackage=" + packageName);
            }

            try {
                acquireLeaseInternal(blobHandle, descriptionResId, description,
                        leaseExpiryTimeMillis, callingUid, packageName);
            } catch (Resources.NotFoundException e) {
                throw new IllegalArgumentException(e);
            } catch (LimitExceededException e) {
                throw new ParcelableException(e);
            }
        }

        @Override
        public void releaseLease(@NonNull BlobHandle blobHandle, @NonNull String packageName) {
            Objects.requireNonNull(blobHandle, "blobHandle must not be null");
            blobHandle.assertIsValid();
            Objects.requireNonNull(packageName, "packageName must not be null");

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            if (Process.isIsolated(callingUid) || mPackageManagerInternal.isInstantApp(
                    packageName, UserHandle.getUserId(callingUid))) {
                throw new SecurityException("Caller not allowed to open blob; "
                        + "callingUid=" + callingUid + ", callingPackage=" + packageName);
            }

            releaseLeaseInternal(blobHandle, callingUid, packageName);
        }

        @Override
        public long getRemainingLeaseQuotaBytes(@NonNull String packageName) {
            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            return getRemainingLeaseQuotaBytesInternal(callingUid, packageName);
        }

        @Override
        public void waitForIdle(@NonNull RemoteCallback remoteCallback) {
            Objects.requireNonNull(remoteCallback, "remoteCallback must not be null");

            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP,
                    "Caller is not allowed to call this; caller=" + Binder.getCallingUid());
            // We post messages back and forth between mHandler thread and mBackgroundHandler
            // thread while committing a blob. We need to replicate the same pattern here to
            // ensure pending messages have been handled.
            mHandler.post(() -> {
                mBackgroundHandler.post(() -> {
                    mHandler.post(PooledLambda.obtainRunnable(remoteCallback::sendResult, null)
                            .recycleOnUse());
                });
            });
        }

        @Override
        @NonNull
        public List<BlobInfo> queryBlobsForUser(@UserIdInt int userId) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Only system uid is allowed to call "
                        + "queryBlobsForUser()");
            }

            return queryBlobsForUserInternal(userId);
        }

        @Override
        public void deleteBlob(long blobId) {
            final int callingUid = Binder.getCallingUid();
            if (callingUid != Process.SYSTEM_UID) {
                throw new SecurityException("Only system uid is allowed to call "
                        + "deleteBlob()");
            }

            deleteBlobInternal(blobId, callingUid);
        }

        @Override
        @NonNull
        public List<BlobHandle> getLeasedBlobs(@NonNull String packageName) {
            Objects.requireNonNull(packageName, "packageName must not be null");

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            return getLeasedBlobsInternal(callingUid, packageName);
        }

        @Override
        @Nullable
        public LeaseInfo getLeaseInfo(@NonNull BlobHandle blobHandle, @NonNull String packageName) {
            Objects.requireNonNull(blobHandle, "blobHandle must not be null");
            blobHandle.assertIsValid();
            Objects.requireNonNull(packageName, "packageName must not be null");

            final int callingUid = Binder.getCallingUid();
            verifyCallingPackage(callingUid, packageName);

            if (Process.isIsolated(callingUid) || mPackageManagerInternal.isInstantApp(
                    packageName, UserHandle.getUserId(callingUid))) {
                throw new SecurityException("Caller not allowed to open blob; "
                        + "callingUid=" + callingUid + ", callingPackage=" + packageName);
            }

            return getLeaseInfoInternal(blobHandle, callingUid, packageName);
        }

        @Override
        public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
                @Nullable String[] args) {
            // TODO: add proto-based version of this.
            if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, writer)) return;

            final DumpArgs dumpArgs = DumpArgs.parse(args);

            final IndentingPrintWriter fout = new IndentingPrintWriter(writer, "    ");
            if (dumpArgs.shouldDumpHelp()) {
                writer.println("dumpsys blob_store [options]:");
                fout.increaseIndent();
                dumpArgs.dumpArgsUsage(fout);
                fout.decreaseIndent();
                return;
            }

            synchronized (mBlobsLock) {
                if (dumpArgs.shouldDumpAllSections()) {
                    fout.println("mCurrentMaxSessionId: " + mCurrentMaxSessionId);
                    fout.println();
                }

                if (dumpArgs.shouldDumpSessions()) {
                    dumpSessionsLocked(fout, dumpArgs);
                    fout.println();
                }
                if (dumpArgs.shouldDumpBlobs()) {
                    dumpBlobsLocked(fout, dumpArgs);
                    fout.println();
                }
            }

            if (dumpArgs.shouldDumpConfig()) {
                fout.println("BlobStore config:");
                fout.increaseIndent();
                BlobStoreConfig.dump(fout, mContext);
                fout.decreaseIndent();
                fout.println();
            }
        }

        @Override
        public int handleShellCommand(@NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {
            return (new BlobStoreManagerShellCommand(BlobStoreManagerService.this)).exec(this,
                    in.getFileDescriptor(), out.getFileDescriptor(), err.getFileDescriptor(), args);
        }
    }

    static final class DumpArgs {
        private static final int FLAG_DUMP_SESSIONS = 1 << 0;
        private static final int FLAG_DUMP_BLOBS = 1 << 1;
        private static final int FLAG_DUMP_CONFIG = 1 << 2;

        private int mSelectedSectionFlags;
        private boolean mDumpFull;
        private final ArrayList<String> mDumpPackages = new ArrayList<>();
        private final ArrayList<Integer> mDumpUids = new ArrayList<>();
        private final ArrayList<Integer> mDumpUserIds = new ArrayList<>();
        private final ArrayList<Long> mDumpBlobIds = new ArrayList<>();
        private boolean mDumpHelp;

        public boolean shouldDumpSession(String packageName, int uid, long blobId) {
            if (!CollectionUtils.isEmpty(mDumpPackages)
                    && mDumpPackages.indexOf(packageName) < 0) {
                return false;
            }
            if (!CollectionUtils.isEmpty(mDumpUids)
                    && mDumpUids.indexOf(uid) < 0) {
                return false;
            }
            if (!CollectionUtils.isEmpty(mDumpBlobIds)
                    && mDumpBlobIds.indexOf(blobId) < 0) {
                return false;
            }
            return true;
        }

        public boolean shouldDumpAllSections() {
            return mSelectedSectionFlags == 0;
        }

        public void allowDumpSessions() {
            mSelectedSectionFlags |= FLAG_DUMP_SESSIONS;
        }

        public boolean shouldDumpSessions() {
            if (shouldDumpAllSections()) {
                return true;
            }
            return (mSelectedSectionFlags & FLAG_DUMP_SESSIONS) != 0;
        }

        public void allowDumpBlobs() {
            mSelectedSectionFlags |= FLAG_DUMP_BLOBS;
        }

        public boolean shouldDumpBlobs() {
            if (shouldDumpAllSections()) {
                return true;
            }
            return (mSelectedSectionFlags & FLAG_DUMP_BLOBS) != 0;
        }

        public void allowDumpConfig() {
            mSelectedSectionFlags |= FLAG_DUMP_CONFIG;
        }

        public boolean shouldDumpConfig() {
            if (shouldDumpAllSections()) {
                return true;
            }
            return (mSelectedSectionFlags & FLAG_DUMP_CONFIG) != 0;
        }

        public boolean shouldDumpBlob(long blobId) {
            return CollectionUtils.isEmpty(mDumpBlobIds)
                    || mDumpBlobIds.indexOf(blobId) >= 0;
        }

        public boolean shouldDumpFull() {
            return mDumpFull;
        }

        public boolean shouldDumpUser(int userId) {
            return CollectionUtils.isEmpty(mDumpUserIds)
                    || mDumpUserIds.indexOf(userId) >= 0;
        }

        public boolean shouldDumpHelp() {
            return mDumpHelp;
        }

        private DumpArgs() {}

        public static DumpArgs parse(String[] args) {
            final DumpArgs dumpArgs = new DumpArgs();
            if (args == null) {
                return dumpArgs;
            }

            for (int i = 0; i < args.length; ++i) {
                final String opt = args[i];
                if ("--full".equals(opt) || "-f".equals(opt)) {
                    final int callingUid = Binder.getCallingUid();
                    if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
                        dumpArgs.mDumpFull = true;
                    }
                } else if ("--sessions".equals(opt)) {
                    dumpArgs.allowDumpSessions();
                } else if ("--blobs".equals(opt)) {
                    dumpArgs.allowDumpBlobs();
                } else if ("--config".equals(opt)) {
                    dumpArgs.allowDumpConfig();
                } else if ("--package".equals(opt) || "-p".equals(opt)) {
                    dumpArgs.mDumpPackages.add(getStringArgRequired(args, ++i, "packageName"));
                } else if ("--uid".equals(opt) || "-u".equals(opt)) {
                    dumpArgs.mDumpUids.add(getIntArgRequired(args, ++i, "uid"));
                } else if ("--user".equals(opt)) {
                    dumpArgs.mDumpUserIds.add(getIntArgRequired(args, ++i, "userId"));
                } else if ("--blob".equals(opt) || "-b".equals(opt)) {
                    dumpArgs.mDumpBlobIds.add(getLongArgRequired(args, ++i, "blobId"));
                } else if ("--help".equals(opt) || "-h".equals(opt)) {
                    dumpArgs.mDumpHelp = true;
                } else {
                    // Everything else is assumed to be blob ids.
                    dumpArgs.mDumpBlobIds.add(getLongArgRequired(args, i, "blobId"));
                }
            }
            return dumpArgs;
        }

        private static String getStringArgRequired(String[] args, int index, String argName) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing " + argName);
            }
            return args[index];
        }

        private static int getIntArgRequired(String[] args, int index, String argName) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing " + argName);
            }
            final int value;
            try {
                value = Integer.parseInt(args[index]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid " + argName + ": " + args[index]);
            }
            return value;
        }

        private static long getLongArgRequired(String[] args, int index, String argName) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing " + argName);
            }
            final long value;
            try {
                value = Long.parseLong(args[index]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid " + argName + ": " + args[index]);
            }
            return value;
        }

        private void dumpArgsUsage(IndentingPrintWriter pw) {
            pw.println("--help | -h");
            printWithIndent(pw, "Dump this help text");
            pw.println("--sessions");
            printWithIndent(pw, "Dump only the sessions info");
            pw.println("--blobs");
            printWithIndent(pw, "Dump only the committed blobs info");
            pw.println("--config");
            printWithIndent(pw, "Dump only the config values");
            pw.println("--package | -p [package-name]");
            printWithIndent(pw, "Dump blobs info associated with the given package");
            pw.println("--uid | -u [uid]");
            printWithIndent(pw, "Dump blobs info associated with the given uid");
            pw.println("--user [user-id]");
            printWithIndent(pw, "Dump blobs info in the given user");
            pw.println("--blob | -b [session-id | blob-id]");
            printWithIndent(pw, "Dump blob info corresponding to the given ID");
            pw.println("--full | -f");
            printWithIndent(pw, "Dump full unredacted blobs data");
        }

        private void printWithIndent(IndentingPrintWriter pw, String str) {
            pw.increaseIndent();
            pw.println(str);
            pw.decreaseIndent();
        }
    }

    private class LocalService extends BlobStoreManagerInternal {
        @Override
        public void onIdleMaintenance() {
            runIdleMaintenance();
        }
    }

    @VisibleForTesting
    static class Injector {
        public Handler initializeMessageHandler() {
            return BlobStoreManagerService.initializeMessageHandler();
        }

        public Handler getBackgroundHandler() {
            return BackgroundThread.getHandler();
        }
    }
}