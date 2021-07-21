/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetFileDescriptor;
import android.database.CrossProcessCursorWrapper;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import dalvik.system.CloseGuard;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The public interface object used to interact with a specific
 * {@link ContentProvider}.
 * <p>
 * Instances can be obtained by calling
 * {@link ContentResolver#acquireContentProviderClient} or
 * {@link ContentResolver#acquireUnstableContentProviderClient}. Instances must
 * be released using {@link #close()} in order to indicate to the system that
 * the underlying {@link ContentProvider} is no longer needed and can be killed
 * to free up resources.
 * <p>
 * Note that you should generally create a new ContentProviderClient instance
 * for each thread that will be performing operations. Unlike
 * {@link ContentResolver}, the methods here such as {@link #query} and
 * {@link #openFile} are not thread safe -- you must not call {@link #close()}
 * on the ContentProviderClient those calls are made from until you are finished
 * with the data they have returned.
 */
public class ContentProviderClient implements ContentInterface, AutoCloseable {
    private static final String TAG = "ContentProviderClient";

    @GuardedBy("ContentProviderClient.class")
    private static Handler sAnrHandler;

    private final ContentResolver mContentResolver;
    @UnsupportedAppUsage
    private final IContentProvider mContentProvider;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final String mPackageName;
    private final @NonNull AttributionSource mAttributionSource;

    private final String mAuthority;
    private final boolean mStable;

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    private long mAnrTimeout;
    private NotRespondingRunnable mAnrRunnable;

    /** {@hide} */
    @VisibleForTesting
    public ContentProviderClient(ContentResolver contentResolver, IContentProvider contentProvider,
            boolean stable) {
        // Only used for testing, so use a fake authority
        this(contentResolver, contentProvider, "unknown", stable);
    }

    /** {@hide} */
    public ContentProviderClient(ContentResolver contentResolver, IContentProvider contentProvider,
            String authority, boolean stable) {
        mContentResolver = contentResolver;
        mContentProvider = contentProvider;
        mPackageName = contentResolver.mPackageName;
        mAttributionSource = contentResolver.getAttributionSource();

        mAuthority = authority;
        mStable = stable;

        mCloseGuard.open("close");
    }

    /**
     * Configure this client to automatically detect and kill the remote
     * provider when an "application not responding" event is detected.
     *
     * @param timeoutMillis the duration for which a pending call is allowed
     *            block before the remote provider is considered to be
     *            unresponsive. Set to {@code 0} to allow pending calls to block
     *            indefinitely with no action taken.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.REMOVE_TASKS)
    public void setDetectNotResponding(@DurationMillisLong long timeoutMillis) {
        synchronized (ContentProviderClient.class) {
            mAnrTimeout = timeoutMillis;

            if (timeoutMillis > 0) {
                if (mAnrRunnable == null) {
                    mAnrRunnable = new NotRespondingRunnable();
                }
                if (sAnrHandler == null) {
                    sAnrHandler = new Handler(Looper.getMainLooper(), null, true /* async */);
                }

                // If the remote process hangs, we're going to kill it, so we're
                // technically okay doing blocking calls.
                Binder.allowBlocking(mContentProvider.asBinder());
            } else {
                mAnrRunnable = null;

                // If we're no longer watching for hangs, revert back to default
                // blocking behavior.
                Binder.defaultBlocking(mContentProvider.asBinder());
            }
        }
    }

    private void beforeRemote() {
        if (mAnrRunnable != null) {
            sAnrHandler.postDelayed(mAnrRunnable, mAnrTimeout);
        }
    }

    private void afterRemote() {
        if (mAnrRunnable != null) {
            sAnrHandler.removeCallbacks(mAnrRunnable);
        }
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    public @Nullable Cursor query(@NonNull Uri url, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder) throws RemoteException {
        return query(url, projection, selection,  selectionArgs, sortOrder, null);
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder, @Nullable CancellationSignal cancellationSignal)
                    throws RemoteException {
        Bundle queryArgs =
                ContentResolver.createSqlQueryBundle(selection, selectionArgs, sortOrder);
        return query(uri, projection, queryArgs, cancellationSignal);
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    @Override
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            Bundle queryArgs, @Nullable CancellationSignal cancellationSignal)
                    throws RemoteException {
        Objects.requireNonNull(uri, "url");

        beforeRemote();
        try {
            ICancellationSignal remoteCancellationSignal = null;
            if (cancellationSignal != null) {
                cancellationSignal.throwIfCanceled();
                remoteCancellationSignal = mContentProvider.createCancellationSignal();
                cancellationSignal.setRemote(remoteCancellationSignal);
            }
            final Cursor cursor = mContentProvider.query(
                    mAttributionSource, uri, projection, queryArgs,
                    remoteCancellationSignal);
            if (cursor == null) {
                return null;
            }
            return new CursorWrapperInner(cursor);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#getType ContentProvider.getType} */
    @Override
    public @Nullable String getType(@NonNull Uri url) throws RemoteException {
        Objects.requireNonNull(url, "url");

        beforeRemote();
        try {
            return mContentProvider.getType(url);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#getStreamTypes ContentProvider.getStreamTypes} */
    @Override
    public @Nullable String[] getStreamTypes(@NonNull Uri url, @NonNull String mimeTypeFilter)
            throws RemoteException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(mimeTypeFilter, "mimeTypeFilter");

        beforeRemote();
        try {
            return mContentProvider.getStreamTypes(url, mimeTypeFilter);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#canonicalize} */
    @Override
    public final @Nullable Uri canonicalize(@NonNull Uri url) throws RemoteException {
        Objects.requireNonNull(url, "url");

        beforeRemote();
        try {
            return mContentProvider.canonicalize(mAttributionSource, url);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#uncanonicalize} */
    @Override
    public final @Nullable Uri uncanonicalize(@NonNull Uri url) throws RemoteException {
        Objects.requireNonNull(url, "url");

        beforeRemote();
        try {
            return mContentProvider.uncanonicalize(mAttributionSource, url);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#refresh} */
    @Override
    public boolean refresh(Uri url, @Nullable Bundle extras,
            @Nullable CancellationSignal cancellationSignal) throws RemoteException {
        Objects.requireNonNull(url, "url");

        beforeRemote();
        try {
            ICancellationSignal remoteCancellationSignal = null;
            if (cancellationSignal != null) {
                cancellationSignal.throwIfCanceled();
                remoteCancellationSignal = mContentProvider.createCancellationSignal();
                cancellationSignal.setRemote(remoteCancellationSignal);
            }
            return mContentProvider.refresh(mAttributionSource, url, extras,
                    remoteCancellationSignal);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** {@hide} */
    @Override
    public int checkUriPermission(@NonNull Uri uri, int uid, @Intent.AccessUriMode int modeFlags)
            throws RemoteException {
        Objects.requireNonNull(uri, "uri");

        beforeRemote();
        try {
            return mContentProvider.checkUriPermission(mAttributionSource, uri, uid,
                    modeFlags);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#insert ContentProvider.insert} */
    public @Nullable Uri insert(@NonNull Uri url, @Nullable ContentValues initialValues)
            throws RemoteException {
        return insert(url, initialValues, null);
    }

    /** See {@link ContentProvider#insert ContentProvider.insert} */
    @Override
    public @Nullable Uri insert(@NonNull Uri url, @Nullable ContentValues initialValues,
            @Nullable Bundle extras) throws RemoteException {
        Objects.requireNonNull(url, "url");

        beforeRemote();
        try {
            return mContentProvider.insert(mAttributionSource, url, initialValues,
                    extras);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#bulkInsert ContentProvider.bulkInsert} */
    @Override
    public int bulkInsert(@NonNull Uri url, @NonNull ContentValues[] initialValues)
            throws RemoteException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(initialValues, "initialValues");

        beforeRemote();
        try {
            return mContentProvider.bulkInsert(mAttributionSource, url, initialValues);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#delete ContentProvider.delete} */
    public int delete(@NonNull Uri url, @Nullable String selection,
            @Nullable String[] selectionArgs) throws RemoteException {
        return delete(url, ContentResolver.createSqlQueryBundle(selection, selectionArgs));
    }

    /** See {@link ContentProvider#delete ContentProvider.delete} */
    @Override
    public int delete(@NonNull Uri url, @Nullable Bundle extras) throws RemoteException {
        Objects.requireNonNull(url, "url");

        beforeRemote();
        try {
            return mContentProvider.delete(mAttributionSource, url, extras);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#update ContentProvider.update} */
    public int update(@NonNull Uri url, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) throws RemoteException {
        return update(url, values, ContentResolver.createSqlQueryBundle(selection, selectionArgs));
    }

    /** See {@link ContentProvider#update ContentProvider.update} */
    @Override
    public int update(@NonNull Uri url, @Nullable ContentValues values, @Nullable Bundle extras)
            throws RemoteException {
        Objects.requireNonNull(url, "url");

        beforeRemote();
        try {
            return mContentProvider.update(mAttributionSource, url, values, extras);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /**
     * See {@link ContentProvider#openFile ContentProvider.openFile}.  Note that
     * this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openFileDescriptor
     * ContentResolver.openFileDescriptor} API instead.
     */
    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri url, @NonNull String mode)
            throws RemoteException, FileNotFoundException {
        return openFile(url, mode, null);
    }

    /**
     * See {@link ContentProvider#openFile ContentProvider.openFile}.  Note that
     * this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openFileDescriptor
     * ContentResolver.openFileDescriptor} API instead.
     */
    @Override
    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri url, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(mode, "mode");

        beforeRemote();
        try {
            ICancellationSignal remoteSignal = null;
            if (signal != null) {
                signal.throwIfCanceled();
                remoteSignal = mContentProvider.createCancellationSignal();
                signal.setRemote(remoteSignal);
            }
            return mContentProvider.openFile(mAttributionSource, url, mode, remoteSignal);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /**
     * See {@link ContentProvider#openAssetFile ContentProvider.openAssetFile}.
     * Note that this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openAssetFileDescriptor
     * ContentResolver.openAssetFileDescriptor} API instead.
     */
    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri url, @NonNull String mode)
            throws RemoteException, FileNotFoundException {
        return openAssetFile(url, mode, null);
    }

    /**
     * See {@link ContentProvider#openAssetFile ContentProvider.openAssetFile}.
     * Note that this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openAssetFileDescriptor
     * ContentResolver.openAssetFileDescriptor} API instead.
     */
    @Override
    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri url, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(mode, "mode");

        beforeRemote();
        try {
            ICancellationSignal remoteSignal = null;
            if (signal != null) {
                signal.throwIfCanceled();
                remoteSignal = mContentProvider.createCancellationSignal();
                signal.setRemote(remoteSignal);
            }
            return mContentProvider.openAssetFile(mAttributionSource, url, mode,
                    remoteSignal);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#openTypedAssetFile ContentProvider.openTypedAssetFile} */
    public final @Nullable AssetFileDescriptor openTypedAssetFileDescriptor(@NonNull Uri uri,
            @NonNull String mimeType, @Nullable Bundle opts)
                    throws RemoteException, FileNotFoundException {
        return openTypedAssetFileDescriptor(uri, mimeType, opts, null);
    }

    /** See {@link ContentProvider#openTypedAssetFile ContentProvider.openTypedAssetFile} */
    public final @Nullable AssetFileDescriptor openTypedAssetFileDescriptor(@NonNull Uri uri,
            @NonNull String mimeType, @Nullable Bundle opts, @Nullable CancellationSignal signal)
                    throws RemoteException, FileNotFoundException {
        return openTypedAssetFile(uri, mimeType, opts, signal);
    }

    @Override
    public final @Nullable AssetFileDescriptor openTypedAssetFile(@NonNull Uri uri,
            @NonNull String mimeTypeFilter, @Nullable Bundle opts,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(mimeTypeFilter, "mimeTypeFilter");

        beforeRemote();
        try {
            ICancellationSignal remoteSignal = null;
            if (signal != null) {
                signal.throwIfCanceled();
                remoteSignal = mContentProvider.createCancellationSignal();
                signal.setRemote(remoteSignal);
            }
            return mContentProvider.openTypedAssetFile(
                    mAttributionSource, uri, mimeTypeFilter, opts, remoteSignal);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#applyBatch ContentProvider.applyBatch} */
    public @NonNull ContentProviderResult[] applyBatch(
            @NonNull ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        return applyBatch(mAuthority, operations);
    }

    /** See {@link ContentProvider#applyBatch ContentProvider.applyBatch} */
    @Override
    public @NonNull ContentProviderResult[] applyBatch(@NonNull String authority,
            @NonNull ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        Objects.requireNonNull(operations, "operations");

        beforeRemote();
        try {
            return mContentProvider.applyBatch(mAttributionSource, authority,
                    operations);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /** See {@link ContentProvider#call(String, String, Bundle)} */
    public @Nullable Bundle call(@NonNull String method, @Nullable String arg,
            @Nullable Bundle extras) throws RemoteException {
        return call(mAuthority, method, arg, extras);
    }

    /** See {@link ContentProvider#call(String, String, Bundle)} */
    @Override
    public @Nullable Bundle call(@NonNull String authority, @NonNull String method,
            @Nullable String arg, @Nullable Bundle extras) throws RemoteException {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(method, "method");

        beforeRemote();
        try {
            return mContentProvider.call(mAttributionSource, authority, method, arg,
                    extras);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /**
     * Closes this client connection, indicating to the system that the
     * underlying {@link ContentProvider} is no longer needed.
     */
    @Override
    public void close() {
        closeInternal();
    }

    /**
     * @deprecated replaced by {@link #close()}.
     */
    @Deprecated
    public boolean release() {
        return closeInternal();
    }

    private boolean closeInternal() {
        mCloseGuard.close();
        if (mClosed.compareAndSet(false, true)) {
            // We can't do ANR checks after we cease to exist! Reset any
            // blocking behavior changes we might have made.
            setDetectNotResponding(0);

            if (mStable) {
                return mContentResolver.releaseProvider(mContentProvider);
            } else {
                return mContentResolver.releaseUnstableProvider(mContentProvider);
            }
        } else {
            return false;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Get a reference to the {@link ContentProvider} that is associated with this
     * client. If the {@link ContentProvider} is running in a different process then
     * null will be returned. This can be used if you know you are running in the same
     * process as a provider, and want to get direct access to its implementation details.
     *
     * @return If the associated {@link ContentProvider} is local, returns it.
     * Otherwise returns null.
     */
    public @Nullable ContentProvider getLocalContentProvider() {
        return ContentProvider.coerceToLocalContentProvider(mContentProvider);
    }

    /** {@hide} */
    @Deprecated
    public static void closeQuietly(ContentProviderClient client) {
        IoUtils.closeQuietly(client);
    }

    /** {@hide} */
    @Deprecated
    public static void releaseQuietly(ContentProviderClient client) {
        IoUtils.closeQuietly(client);
    }

    private class NotRespondingRunnable implements Runnable {
        @Override
        public void run() {
            Log.w(TAG, "Detected provider not responding: " + mContentProvider);
            mContentResolver.appNotRespondingViaProvider(mContentProvider);
        }
    }

    private final class CursorWrapperInner extends CrossProcessCursorWrapper {
        private final CloseGuard mCloseGuard = CloseGuard.get();

        CursorWrapperInner(Cursor cursor) {
            super(cursor);
            mCloseGuard.open("close");
        }

        @Override
        public void close() {
            mCloseGuard.close();
            super.close();
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mCloseGuard != null) {
                    mCloseGuard.warnIfOpen();
                }

                close();
            } finally {
                super.finalize();
            }
        }
    }
}
