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
package android.app.blob;

import android.annotation.BytesLong;
import android.annotation.CallbackExecutor;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.IdRes;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.os.LimitExceededException;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.internal.util.function.pooled.PooledLambda;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * This class provides access to the blob store managed by the system.
 *
 * <p> Apps can publish and access a data blob using a {@link BlobHandle} object which can
 * be created with {@link BlobHandle#createWithSha256(byte[], CharSequence, long, String)}.
 * This {@link BlobHandle} object encapsulates the following pieces of information used for
 * identifying the blobs:
 * <ul>
 *     <li> {@link BlobHandle#getSha256Digest()}
 *     <li> {@link BlobHandle#getLabel()}
 *     <li> {@link BlobHandle#getExpiryTimeMillis()}
 *     <li> {@link BlobHandle#getTag()}
 * </ul>
 * For two {@link BlobHandle} objects to be considered identical, all these pieces of information
 * must be equal.
 *
 * <p> For contributing a new data blob, an app needs to create a session using
 * {@link BlobStoreManager#createSession(BlobHandle)} and then open this session for writing using
 * {@link BlobStoreManager#openSession(long)}.
 *
 * <p> The following code snippet shows how to create and open a session for writing:
 * <pre class="prettyprint">
 *     final long sessionId = blobStoreManager.createSession(blobHandle);
 *     try (BlobStoreManager.Session session = blobStoreManager.openSession(sessionId)) {
 *         try (ParcelFileDescriptor pfd = new ParcelFileDescriptor.AutoCloseOutputStream(
 *                 session.openWrite(offsetBytes, lengthBytes))) {
 *             writeData(pfd);
 *         }
 *     }
 * </pre>
 *
 * <p> If all the data could not be written in a single attempt, apps can close this session
 * and re-open it again using the session id obtained via
 * {@link BlobStoreManager#createSession(BlobHandle)}. Note that the session data is persisted
 * and can be re-opened for completing the data contribution, even across device reboots.
 *
 * <p> After the data is written to the session, it can be committed using
 * {@link Session#commit(Executor, Consumer)}. Until the session is committed, data written
 * to the session will not be shared with any app.
 *
 * <p class="note"> Once a session is committed using {@link Session#commit(Executor, Consumer)},
 * any data written as part of this session is sealed and cannot be modified anymore.
 *
 * <p> Before committing the session, apps can indicate which apps are allowed to access the
 * contributed data using one or more of the following access modes:
 * <ul>
 *     <li> {@link Session#allowPackageAccess(String, byte[])} which will allow whitelisting
 *          specific packages to access the blobs.
 *     <li> {@link Session#allowSameSignatureAccess()} which will allow only apps which are signed
 *          with the same certificate as the app which contributed the blob to access it.
 *     <li> {@link Session#allowPublicAccess()} which will allow any app on the device to access
 *          the blob.
 * </ul>
 *
 * <p> The following code snippet shows how to specify the access mode and commit the session:
 * <pre class="prettyprint">
 *     try (BlobStoreManager.Session session = blobStoreManager.openSession(sessionId)) {
 *         try (ParcelFileDescriptor pfd = new ParcelFileDescriptor.AutoCloseOutputStream(
 *                 session.openWrite(offsetBytes, lengthBytes))) {
 *             writeData(pfd);
 *         }
 *         session.allowSameSignatureAccess();
 *         session.allowPackageAccess(packageName, certificate);
 *         session.commit(executor, callback);
 *     }
 * </pre>
 *
 * <p> Apps that satisfy at least one of the access mode constraints specified by the publisher
 * of the data blob will be able to access it.
 *
 * <p> A data blob published without specifying any of
 * these access modes will be considered private and only the app that contributed the data
 * blob will be allowed to access it. This is still useful for overall device system health as
 * the System can try to keep one copy of data blob on disk when multiple apps contribute the
 * same data.
 *
 * <p class="note"> It is strongly recommended that apps use one of
 * {@link Session#allowPackageAccess(String, byte[])} or {@link Session#allowSameSignatureAccess()}
 * when they know, ahead of time, the set of apps they would like to share the blobs with.
 * {@link Session#allowPublicAccess()} is meant for publicly available data committed from
 * libraries and SDKs.
 *
 * <p> Once a data blob is committed with {@link Session#commit(Executor, Consumer)}, it
 * can be accessed using {@link BlobStoreManager#openBlob(BlobHandle)}, assuming the caller
 * satisfies constraints of any of the access modes associated with that data blob. An app may
 * acquire a lease on a blob with {@link BlobStoreManager#acquireLease(BlobHandle, int)} and
 * release the lease with {@link BlobStoreManager#releaseLease(BlobHandle)}. A blob will not be
 * deleted from the system while there is at least one app leasing it.
 *
 * <p> The following code snippet shows how to access the data blob:
 * <pre class="prettyprint">
 *     try (ParcelFileDescriptor pfd = new ParcelFileDescriptor.AutoCloseInputStream(
 *             blobStoreManager.openBlob(blobHandle)) {
 *         useData(pfd);
 *     }
 * </pre>
 */
@SystemService(Context.BLOB_STORE_SERVICE)
public class BlobStoreManager {
    /** @hide */
    public static final int COMMIT_RESULT_SUCCESS = 0;
    /** @hide */
    public static final int COMMIT_RESULT_ERROR = 1;

    /** @hide */
    public static final int INVALID_RES_ID = -1;

    private final Context mContext;
    private final IBlobStoreManager mService;

    /** @hide */
    public BlobStoreManager(@NonNull Context context, @NonNull IBlobStoreManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Create a new session using the given {@link BlobHandle}, returning a unique id
     * that represents the session. Once created, the session can be opened
     * multiple times across multiple device boots.
     *
     * <p> The system may automatically destroy sessions that have not been
     * finalized (either committed or abandoned) within a reasonable period of
     * time, typically about a week.
     *
     * <p> If an app is planning to acquire a lease on this data (using
     * {@link #acquireLease(BlobHandle, int)} or one of it's other variants) after committing
     * this data (using {@link Session#commit(Executor, Consumer)}), it is recommended that
     * the app checks the remaining quota for acquiring a lease first using
     * {@link #getRemainingLeaseQuotaBytes()} and can skip contributing this data if needed.
     *
     * @param blobHandle the {@link BlobHandle} identifier for which a new session
     *                   needs to be created.
     * @return positive, non-zero unique id that represents the created session.
     *         This id remains consistent across device reboots until the
     *         session is finalized. IDs are not reused during a given boot.
     *
     * @throws IOException when there is an I/O error while creating the session.
     * @throws SecurityException when the caller is not allowed to create a session, such
     *                           as when called from an Instant app.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid.
     * @throws IllegalStateException when a new session could not be created, such as when the
     *                               caller is trying to create too many sessions or when the
     *                               device is running low on space.
     */
    public @IntRange(from = 1) long createSession(@NonNull BlobHandle blobHandle)
            throws IOException {
        try {
            return mService.createSession(blobHandle, mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Open an existing session to actively perform work.
     *
     * @param sessionId a unique id obtained via {@link #createSession(BlobHandle)} that
     *                  represents a particular session.
     * @return the {@link Session} object corresponding to the {@code sessionId}.
     *
     * @throws IOException when there is an I/O error while opening the session.
     * @throws SecurityException when the caller does not own the session, or
     *                           the session does not exist or is invalid.
     */
    public @NonNull Session openSession(@IntRange(from = 1) long sessionId) throws IOException {
        try {
            return new Session(mService.openSession(sessionId, mContext.getOpPackageName()));
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Abandons an existing session and deletes any data that was written to that session so far.
     *
     * @param sessionId a unique id obtained via {@link #createSession(BlobHandle)} that
     *                  represents a particular session.
     *
     * @throws IOException when there is an I/O error while deleting the session.
     * @throws SecurityException when the caller does not own the session, or
     *                           the session does not exist or is invalid.
     */
    public void abandonSession(@IntRange(from = 1) long sessionId) throws IOException {
        try {
            mService.abandonSession(sessionId, mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Opens an existing blob for reading from the blob store managed by the system.
     *
     * @param blobHandle the {@link BlobHandle} representing the blob that the caller
     *                   wants to access.
     * @return a {@link ParcelFileDescriptor} that can be used to read the blob content.
     *
     * @throws IOException when there is an I/O while opening the blob for read.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid.
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     */
    public @NonNull ParcelFileDescriptor openBlob(@NonNull BlobHandle blobHandle)
            throws IOException {
        try {
            return mService.openBlob(blobHandle, mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Acquire a lease to the blob represented by {@code blobHandle}. This lease indicates to the
     * system that the caller wants the blob to be kept around.
     *
     * <p> Any active leases will be automatically released when the blob's expiry time
     * ({@link BlobHandle#getExpiryTimeMillis()}) is elapsed.
     *
     * <p> This lease information is persisted and calling this more than once will result in
     * latest lease overriding any previous lease.
     *
     * <p> When an app acquires a lease on a blob, the System will try to keep this
     * blob around but note that it can still be deleted if it was requested by the user.
     *
     * <p> In case the resource name for the {@code descriptionResId} is modified as part of
     * an app update, apps should re-acquire the lease with the new resource id.
     *
     * @param blobHandle the {@link BlobHandle} representing the blob that the caller wants to
     *                   acquire a lease for.
     * @param descriptionResId the resource id for a short description string that can be surfaced
     *                         to the user explaining what the blob is used for.
     * @param leaseExpiryTimeMillis the time in milliseconds after which the lease can be
     *                              automatically released, in {@link System#currentTimeMillis()}
     *                              timebase. If its value is {@code 0}, then the behavior of this
     *                              API is identical to {@link #acquireLease(BlobHandle, int)}
     *                              where clients have to explicitly call
     *                              {@link #releaseLease(BlobHandle)} when they don't
     *                              need the blob anymore.
     *
     * @throws IOException when there is an I/O error while acquiring a lease to the blob.
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid or
     *                                  if the {@code leaseExpiryTimeMillis} is greater than the
     *                                  {@link BlobHandle#getExpiryTimeMillis()}.
     * @throws LimitExceededException when a lease could not be acquired, such as when the
     *                                caller is trying to acquire leases on too much data. Apps
     *                                can avoid this by checking the remaining quota using
     *                                {@link #getRemainingLeaseQuotaBytes()} before trying to
     *                                acquire a lease.
     *
     * @see #acquireLease(BlobHandle, int)
     * @see #acquireLease(BlobHandle, CharSequence)
     */
    public void acquireLease(@NonNull BlobHandle blobHandle, @IdRes int descriptionResId,
            @CurrentTimeMillisLong long leaseExpiryTimeMillis) throws IOException {
        try {
            mService.acquireLease(blobHandle, descriptionResId, null, leaseExpiryTimeMillis,
                    mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            e.maybeRethrow(LimitExceededException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Acquire a lease to the blob represented by {@code blobHandle}. This lease indicates to the
     * system that the caller wants the blob to be kept around.
     *
     * <p> This is a variant of {@link #acquireLease(BlobHandle, int, long)} taking a
     * {@link CharSequence} for {@code description}. It is highly recommended that callers only
     * use this when a valid resource ID for {@code description} could not be provided. Otherwise,
     * apps should prefer using {@link #acquireLease(BlobHandle, int)} which will allow
     * {@code description} to be localized.
     *
     * <p> Any active leases will be automatically released when the blob's expiry time
     * ({@link BlobHandle#getExpiryTimeMillis()}) is elapsed.
     *
     * <p> This lease information is persisted and calling this more than once will result in
     * latest lease overriding any previous lease.
     *
     * <p> When an app acquires a lease on a blob, the System will try to keep this
     * blob around but note that it can still be deleted if it was requested by the user.
     *
     * @param blobHandle the {@link BlobHandle} representing the blob that the caller wants to
     *                   acquire a lease for.
     * @param description a short description string that can be surfaced
     *                    to the user explaining what the blob is used for.
     * @param leaseExpiryTimeMillis the time in milliseconds after which the lease can be
     *                              automatically released, in {@link System#currentTimeMillis()}
     *                              timebase. If its value is {@code 0}, then the behavior of this
     *                              API is identical to {@link #acquireLease(BlobHandle, int)}
     *                              where clients have to explicitly call
     *                              {@link #releaseLease(BlobHandle)} when they don't
     *                              need the blob anymore.
     *
     * @throws IOException when there is an I/O error while acquiring a lease to the blob.
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid or
     *                                  if the {@code leaseExpiryTimeMillis} is greater than the
     *                                  {@link BlobHandle#getExpiryTimeMillis()}.
     * @throws LimitExceededException when a lease could not be acquired, such as when the
     *                                caller is trying to acquire leases on too much data. Apps
     *                                can avoid this by checking the remaining quota using
     *                                {@link #getRemainingLeaseQuotaBytes()} before trying to
     *                                acquire a lease.
     *
     * @see #acquireLease(BlobHandle, int, long)
     * @see #acquireLease(BlobHandle, CharSequence)
     */
    public void acquireLease(@NonNull BlobHandle blobHandle, @NonNull CharSequence description,
            @CurrentTimeMillisLong long leaseExpiryTimeMillis) throws IOException {
        try {
            mService.acquireLease(blobHandle, INVALID_RES_ID, description, leaseExpiryTimeMillis,
                    mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            e.maybeRethrow(LimitExceededException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Acquire a lease to the blob represented by {@code blobHandle}. This lease indicates to the
     * system that the caller wants the blob to be kept around.
     *
     * <p> This is similar to {@link #acquireLease(BlobHandle, int, long)} except clients don't
     * have to specify the lease expiry time upfront using this API and need to explicitly
     * release the lease using {@link #releaseLease(BlobHandle)} when they no longer like to keep
     * a blob around.
     *
     * <p> Any active leases will be automatically released when the blob's expiry time
     * ({@link BlobHandle#getExpiryTimeMillis()}) is elapsed.
     *
     * <p> This lease information is persisted and calling this more than once will result in
     * latest lease overriding any previous lease.
     *
     * <p> When an app acquires a lease on a blob, the System will try to keep this
     * blob around but note that it can still be deleted if it was requested by the user.
     *
     * <p> In case the resource name for the {@code descriptionResId} is modified as part of
     * an app update, apps should re-acquire the lease with the new resource id.
     *
     * @param blobHandle the {@link BlobHandle} representing the blob that the caller wants to
     *                   acquire a lease for.
     * @param descriptionResId the resource id for a short description string that can be surfaced
     *                         to the user explaining what the blob is used for.
     *
     * @throws IOException when there is an I/O error while acquiring a lease to the blob.
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid.
     * @throws LimitExceededException when a lease could not be acquired, such as when the
     *                                caller is trying to acquire leases on too much data. Apps
     *                                can avoid this by checking the remaining quota using
     *                                {@link #getRemainingLeaseQuotaBytes()} before trying to
     *                                acquire a lease.
     *
     * @see #acquireLease(BlobHandle, int, long)
     * @see #acquireLease(BlobHandle, CharSequence, long)
     */
    public void acquireLease(@NonNull BlobHandle blobHandle, @IdRes int descriptionResId)
            throws IOException {
        acquireLease(blobHandle, descriptionResId, 0);
    }

    /**
     * Acquire a lease to the blob represented by {@code blobHandle}. This lease indicates to the
     * system that the caller wants the blob to be kept around.
     *
     * <p> This is a variant of {@link #acquireLease(BlobHandle, int)} taking a {@link CharSequence}
     * for {@code description}. It is highly recommended that callers only use this when a valid
     * resource ID for {@code description} could not be provided. Otherwise, apps should prefer
     * using {@link #acquireLease(BlobHandle, int)} which will allow {@code description} to be
     * localized.
     *
     * <p> This is similar to {@link #acquireLease(BlobHandle, CharSequence, long)} except clients
     * don't have to specify the lease expiry time upfront using this API and need to explicitly
     * release the lease using {@link #releaseLease(BlobHandle)} when they no longer like to keep
     * a blob around.
     *
     * <p> Any active leases will be automatically released when the blob's expiry time
     * ({@link BlobHandle#getExpiryTimeMillis()}) is elapsed.
     *
     * <p> This lease information is persisted and calling this more than once will result in
     * latest lease overriding any previous lease.
     *
     * <p> When an app acquires a lease on a blob, the System will try to keep this
     * blob around but note that it can still be deleted if it was requested by the user.
     *
     * @param blobHandle the {@link BlobHandle} representing the blob that the caller wants to
     *                   acquire a lease for.
     * @param description a short description string that can be surfaced
     *                    to the user explaining what the blob is used for.
     *
     * @throws IOException when there is an I/O error while acquiring a lease to the blob.
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid.
     * @throws LimitExceededException when a lease could not be acquired, such as when the
     *                                caller is trying to acquire leases on too much data. Apps
     *                                can avoid this by checking the remaining quota using
     *                                {@link #getRemainingLeaseQuotaBytes()} before trying to
     *                                acquire a lease.
     *
     * @see #acquireLease(BlobHandle, int)
     * @see #acquireLease(BlobHandle, CharSequence, long)
     */
    public void acquireLease(@NonNull BlobHandle blobHandle, @NonNull CharSequence description)
            throws IOException {
        acquireLease(blobHandle, description, 0);
    }

    /**
     * Release any active lease to the blob represented by {@code blobHandle} which is
     * currently held by the caller.
     *
     * @param blobHandle the {@link BlobHandle} representing the blob that the caller wants to
     *                   release the lease for.
     *
     * @throws IOException when there is an I/O error while releasing the release to the blob.
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid.
     */
    public void releaseLease(@NonNull BlobHandle blobHandle) throws IOException {
        try {
            mService.releaseLease(blobHandle, mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the remaining quota size for acquiring a lease (in bytes) which indicates the
     * remaining amount of data that an app can acquire a lease on before the System starts
     * rejecting lease requests.
     *
     * If an app wants to acquire a lease on a blob but the remaining quota size is not sufficient,
     * then it can try releasing leases on any older blobs which are not needed anymore.
     *
     * @return the remaining quota size for acquiring a lease.
     */
    public @IntRange(from = 0) long getRemainingLeaseQuotaBytes() {
        try {
            return mService.getRemainingLeaseQuotaBytes(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Wait until any pending tasks (like persisting data to disk) have finished.
     *
     * @hide
     */
    @TestApi
    public void waitForIdle(long timeoutMillis) throws InterruptedException, TimeoutException {
        try {
            final CountDownLatch countDownLatch = new CountDownLatch(1);
            mService.waitForIdle(new RemoteCallback((result) -> countDownLatch.countDown()));
            if (!countDownLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Timed out waiting for service to become idle");
            }
        } catch (ParcelableException e) {
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @NonNull
    public List<BlobInfo> queryBlobsForUser(@NonNull UserHandle user) throws IOException {
        try {
            return mService.queryBlobsForUser(user.getIdentifier());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void deleteBlob(@NonNull BlobInfo blobInfo) throws IOException {
        try {
            mService.deleteBlob(blobInfo.getId());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the {@link BlobHandle BlobHandles} corresponding to the data blobs that
     * the calling app currently has a lease on.
     *
     * @return a list of {@link BlobHandle BlobHandles} that the caller has a lease on.
     */
    @NonNull
    public List<BlobHandle> getLeasedBlobs() throws IOException {
        try {
            return mService.getLeasedBlobs(mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return {@link LeaseInfo} representing a lease acquired using
     * {@link #acquireLease(BlobHandle, int)} or one of it's other variants,
     * or {@code null} if there is no lease acquired.
     *
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid.
     *
     * @hide
     */
    @TestApi
    @Nullable
    public LeaseInfo getLeaseInfo(@NonNull BlobHandle blobHandle) throws IOException {
        try {
            return mService.getLeaseInfo(blobHandle, mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Represents an ongoing session of a blob's contribution to the blob store managed by the
     * system.
     *
     * <p> Clients that want to contribute a blob need to first create a {@link Session} using
     * {@link #createSession(BlobHandle)} and once the session is created, clients can open and
     * close this session multiple times using {@link #openSession(long)} and
     * {@link Session#close()} before committing it using
     * {@link Session#commit(Executor, Consumer)}, at which point system will take
     * ownership of the blob and the client can no longer make any modifications to the blob's
     * content.
     */
    public static class Session implements Closeable {
        private final IBlobStoreSession mSession;

        private Session(@NonNull IBlobStoreSession session) {
            mSession = session;
        }

        /**
         * Opens a file descriptor to write a blob into the session.
         *
         * <p> The returned file descriptor will start writing data at the requested offset
         * in the underlying file, which can be used to resume a partially
         * written file. If a valid file length is specified, the system will
         * preallocate the underlying disk space to optimize placement on disk.
         * It is strongly recommended to provide a valid file length when known.
         *
         * @param offsetBytes offset into the file to begin writing at, or 0 to
         *                    start at the beginning of the file.
         * @param lengthBytes total size of the file being written, used to
         *                    preallocate the underlying disk space, or -1 if unknown.
         *                    The system may clear various caches as needed to allocate
         *                    this space.
         *
         * @return a {@link ParcelFileDescriptor} for writing to the blob file.
         *
         * @throws IOException when there is an I/O error while opening the file to write.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to write to the file after it is
         *                               abandoned (using {@link #abandon()})
         *                               or committed (using {@link #commit})
         *                               or closed (using {@link #close()}).
         */
        public @NonNull ParcelFileDescriptor openWrite(@BytesLong long offsetBytes,
                @BytesLong long lengthBytes) throws IOException {
            try {
                final ParcelFileDescriptor pfd = mSession.openWrite(offsetBytes, lengthBytes);
                pfd.seekTo(offsetBytes);
                return pfd;
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Opens a file descriptor to read the blob content already written into this session.
         *
         * @return a {@link ParcelFileDescriptor} for reading from the blob file.
         *
         * @throws IOException when there is an I/O error while opening the file to read.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to read the file after it is
         *                               abandoned (using {@link #abandon()})
         *                               or closed (using {@link #close()}).
         */
        public @NonNull ParcelFileDescriptor openRead() throws IOException {
            try {
                return mSession.openRead();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Gets the size of the blob file that was written to the session so far.
         *
         * @return the size of the blob file so far.
         *
         * @throws IOException when there is an I/O error while opening the file to read.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to get the file size after it is
         *                               abandoned (using {@link #abandon()})
         *                               or closed (using {@link #close()}).
         */
        public @BytesLong long getSize() throws IOException {
            try {
                return mSession.getSize();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Close this session. It can be re-opened for writing/reading if it has not been
         * abandoned (using {@link #abandon}) or committed (using {@link #commit}).
         *
         * @throws IOException when there is an I/O error while closing the session.
         * @throws SecurityException when the caller is not the owner of the session.
         */
        public void close() throws IOException {
            try {
                mSession.close();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Abandon this session and delete any data that was written to this session so far.
         *
         * @throws IOException when there is an I/O error while abandoning the session.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to abandon a session which was
         *                               already finalized.
         */
        public void abandon() throws IOException {
            try {
                mSession.abandon();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Allow {@code packageName} with a particular signing certificate to access this blob
         * data once it is committed using a {@link BlobHandle} representing the blob.
         *
         * <p> This needs to be called before committing the blob using
         * {@link #commit(Executor, Consumer)}.
         *
         * @param packageName the name of the package which should be allowed to access the blob.
         * @param certificate the input bytes representing a certificate of type
         *                    {@link android.content.pm.PackageManager#CERT_INPUT_SHA256}.
         *
         * @throws IOException when there is an I/O error while changing the access.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to change access for a blob which is
         *                               already committed.
         */
        public void allowPackageAccess(@NonNull String packageName, @NonNull byte[] certificate)
                throws IOException {
            try {
                mSession.allowPackageAccess(packageName, certificate);
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Returns {@code true} if access has been allowed for a {@code packageName} using either
         * {@link #allowPackageAccess(String, byte[])}.
         * Otherwise, {@code false}.
         *
         * @param packageName the name of the package to check the access for.
         * @param certificate the input bytes representing a certificate of type
         *                    {@link android.content.pm.PackageManager#CERT_INPUT_SHA256}.
         *
         * @throws IOException when there is an I/O error while getting the access type.
         * @throws IllegalStateException when the caller tries to get access type from a session
         *                               which is closed or abandoned.
         */
        public boolean isPackageAccessAllowed(@NonNull String packageName,
                @NonNull byte[] certificate) throws IOException {
            try {
                return mSession.isPackageAccessAllowed(packageName, certificate);
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Allow packages which are signed with the same certificate as the caller to access this
         * blob data once it is committed using a {@link BlobHandle} representing the blob.
         *
         * <p> This needs to be called before committing the blob using
         * {@link #commit(Executor, Consumer)}.
         *
         * @throws IOException when there is an I/O error while changing the access.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to change access for a blob which is
         *                               already committed.
         */
        public void allowSameSignatureAccess() throws IOException {
            try {
                mSession.allowSameSignatureAccess();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Returns {@code true} if access has been allowed for packages signed with the same
         * certificate as the caller by using {@link #allowSameSignatureAccess()}.
         * Otherwise, {@code false}.
         *
         * @throws IOException when there is an I/O error while getting the access type.
         * @throws IllegalStateException when the caller tries to get access type from a session
         *                               which is closed or abandoned.
         */
        public boolean isSameSignatureAccessAllowed() throws IOException {
            try {
                return mSession.isSameSignatureAccessAllowed();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Allow any app on the device to access this blob data once it is committed using
         * a {@link BlobHandle} representing the blob.
         *
         * <p><strong>Note:</strong> This is only meant to be used from libraries and SDKs where
         * the apps which we want to allow access is not known ahead of time.
         * If a blob is being committed to be shared with a particular set of apps, it is highly
         * recommended to use {@link #allowPackageAccess(String, byte[])} instead.
         *
         * <p> This needs to be called before committing the blob using
         * {@link #commit(Executor, Consumer)}.
         *
         * @throws IOException when there is an I/O error while changing the access.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to change access for a blob which is
         *                               already committed.
         */
        public void allowPublicAccess() throws IOException {
            try {
                mSession.allowPublicAccess();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Returns {@code true} if public access has been allowed by using
         * {@link #allowPublicAccess()}. Otherwise, {@code false}.
         *
         * @throws IOException when there is an I/O error while getting the access type.
         * @throws IllegalStateException when the caller tries to get access type from a session
         *                               which is closed or abandoned.
         */
        public boolean isPublicAccessAllowed() throws IOException {
            try {
                return mSession.isPublicAccessAllowed();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Commit the file that was written so far to this session to the blob store maintained by
         * the system.
         *
         * <p> Once this method is called, the session is finalized and no additional
         * mutations can be performed on the session. If the device reboots
         * before the session has been finalized, you may commit the session again.
         *
         * <p> Note that this commit operation will fail if the hash of the data written so far
         * to this session does not match with the one used for
         * {@link BlobHandle#createWithSha256(byte[], CharSequence, long, String)}  BlobHandle}
         * associated with this session.
         *
         * <p> Committing the same data more than once will result in replacing the corresponding
         * access mode (via calling one of {@link #allowPackageAccess(String, byte[])},
         * {@link #allowSameSignatureAccess()}, etc) with the latest one.
         *
         * @param executor the executor on which result callback will be invoked.
         * @param resultCallback a callback to receive the commit result. when the result is
         *                       {@code 0}, it indicates success. Otherwise, failure.
         *
         * @throws IOException when there is an I/O error while committing the session.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalArgumentException when the passed parameters are not valid.
         * @throws IllegalStateException when the caller tries to commit a session which was
         *                               already finalized.
         */
        public void commit(@NonNull @CallbackExecutor Executor executor,
                @NonNull Consumer<Integer> resultCallback) throws IOException {
            try {
                mSession.commit(new IBlobCommitCallback.Stub() {
                    public void onResult(int result) {
                        executor.execute(PooledLambda.obtainRunnable(
                                Consumer::accept, resultCallback, result));
                    }
                });
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
