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

import static android.app.blob.BlobStoreManager.COMMIT_RESULT_SUCCESS;

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
import android.app.blob.BlobHandle;
import android.app.blob.IBlobStoreManager;
import android.app.blob.IBlobStoreSession;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ExceptionUtils;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.Watchdog;

import java.io.IOException;

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
    private final SessionStateChangeListener mSessionStateChangeListener =
            new SessionStateChangeListener();

    private PackageManagerInternal mPackageManagerInternal;

    public BlobStoreManagerService(Context context) {
        super(context);
        mContext = context;

        final HandlerThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        Watchdog.getInstance().addThread(mHandler);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.BLOB_STORE_SERVICE, new Stub());

        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
    }


    @GuardedBy("mBlobsLock")
    private long generateNextSessionIdLocked() {
        return ++mCurrentMaxSessionId;
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

    private long createSessionInternal(BlobHandle blobHandle,
            int callingUid, String callingPackage) {
        synchronized (mBlobsLock) {
            // TODO: throw if there is already an active session associated with blobHandle.
            final long sessionId = generateNextSessionIdLocked();
            final BlobStoreSession session = new BlobStoreSession(mContext,
                    sessionId, blobHandle, callingUid, callingPackage,
                    mSessionStateChangeListener);
            getUserSessionsLocked(UserHandle.getUserId(callingUid)).put(sessionId, session);
            // TODO: persist sessions data
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
            // TODO: persist sessions data
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
            // TODO: persist blobs data
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
                    getUserSessionsLocked(UserHandle.getUserId(session.ownerUid))
                            .remove(session.sessionId);
                    break;
                case STATE_COMMITTED:
                    session.verifyBlobData();
                    break;
                case STATE_VERIFIED_VALID:
                    final ArrayMap<BlobHandle, BlobMetadata> userBlobs =
                            getUserBlobsLocked(UserHandle.getUserId(session.ownerUid));
                    BlobMetadata blob = userBlobs.get(session.blobHandle);
                    if (blob == null) {
                        blob = new BlobMetadata(mContext,
                                session.sessionId, session.blobHandle);
                        userBlobs.put(session.blobHandle, blob);
                    }
                    blob.addCommitter(session.ownerPackageName, session.ownerUid,
                            session.getBlobAccessMode());
                    // TODO: Persist blobs data.
                    session.sendCommitCallbackResult(COMMIT_RESULT_SUCCESS);
                    getUserSessionsLocked(UserHandle.getUserId(session.ownerUid))
                            .remove(session.sessionId);
                    break;
                default:
                    Slog.wtf(TAG, "Invalid session state: "
                            + stateToString(session.getState()));
            }
            // TODO: Persist sessions data.
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
    }
}
