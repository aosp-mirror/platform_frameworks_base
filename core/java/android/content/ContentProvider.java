/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Trace.TRACE_TAG_DATABASE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.AppOpsManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.PackageManager;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * Content providers are one of the primary building blocks of Android applications, providing
 * content to applications. They encapsulate data and provide it to applications through the single
 * {@link ContentResolver} interface. A content provider is only required if you need to share
 * data between multiple applications. For example, the contacts data is used by multiple
 * applications and must be stored in a content provider. If you don't need to share data amongst
 * multiple applications you can use a database directly via
 * {@link android.database.sqlite.SQLiteDatabase}.
 *
 * <p>When a request is made via
 * a {@link ContentResolver} the system inspects the authority of the given URI and passes the
 * request to the content provider registered with the authority. The content provider can interpret
 * the rest of the URI however it wants. The {@link UriMatcher} class is helpful for parsing
 * URIs.</p>
 *
 * <p>The primary methods that need to be implemented are:
 * <ul>
 *   <li>{@link #onCreate} which is called to initialize the provider</li>
 *   <li>{@link #query} which returns data to the caller</li>
 *   <li>{@link #insert} which inserts new data into the content provider</li>
 *   <li>{@link #update} which updates existing data in the content provider</li>
 *   <li>{@link #delete} which deletes data from the content provider</li>
 *   <li>{@link #getType} which returns the MIME type of data in the content provider</li>
 * </ul></p>
 *
 * <p class="caution">Data access methods (such as {@link #insert} and
 * {@link #update}) may be called from many threads at once, and must be thread-safe.
 * Other methods (such as {@link #onCreate}) are only called from the application
 * main thread, and must avoid performing lengthy operations.  See the method
 * descriptions for their expected thread behavior.</p>
 *
 * <p>Requests to {@link ContentResolver} are automatically forwarded to the appropriate
 * ContentProvider instance, so subclasses don't have to worry about the details of
 * cross-process calls.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using content providers, read the
 * <a href="{@docRoot}guide/topics/providers/content-providers.html">Content Providers</a>
 * developer guide.</p>
 * </div>
 */
public abstract class ContentProvider implements ContentInterface, ComponentCallbacks2 {

    private static final String TAG = "ContentProvider";

    /*
     * Note: if you add methods to ContentProvider, you must add similar methods to
     *       MockContentProvider.
     */

    @UnsupportedAppUsage
    private Context mContext = null;
    private int mMyUid;

    // Since most Providers have only one authority, we keep both a String and a String[] to improve
    // performance.
    @UnsupportedAppUsage
    private String mAuthority;
    @UnsupportedAppUsage
    private String[] mAuthorities;
    @UnsupportedAppUsage
    private String mReadPermission;
    @UnsupportedAppUsage
    private String mWritePermission;
    @UnsupportedAppUsage
    private PathPermission[] mPathPermissions;
    private boolean mExported;
    private boolean mNoPerms;
    private boolean mSingleUser;

    private ThreadLocal<Pair<String, String>> mCallingPackage;

    private Transport mTransport = new Transport();

    /**
     * Construct a ContentProvider instance.  Content providers must be
     * <a href="{@docRoot}guide/topics/manifest/provider-element.html">declared
     * in the manifest</a>, accessed with {@link ContentResolver}, and created
     * automatically by the system, so applications usually do not create
     * ContentProvider instances directly.
     *
     * <p>At construction time, the object is uninitialized, and most fields and
     * methods are unavailable.  Subclasses should initialize themselves in
     * {@link #onCreate}, not the constructor.
     *
     * <p>Content providers are created on the application main thread at
     * application launch time.  The constructor must not perform lengthy
     * operations, or application startup will be delayed.
     */
    public ContentProvider() {
    }

    /**
     * Constructor just for mocking.
     *
     * @param context A Context object which should be some mock instance (like the
     * instance of {@link android.test.mock.MockContext}).
     * @param readPermission The read permision you want this instance should have in the
     * test, which is available via {@link #getReadPermission()}.
     * @param writePermission The write permission you want this instance should have
     * in the test, which is available via {@link #getWritePermission()}.
     * @param pathPermissions The PathPermissions you want this instance should have
     * in the test, which is available via {@link #getPathPermissions()}.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public ContentProvider(
            Context context,
            String readPermission,
            String writePermission,
            PathPermission[] pathPermissions) {
        mContext = context;
        mReadPermission = readPermission;
        mWritePermission = writePermission;
        mPathPermissions = pathPermissions;
    }

    /**
     * Given an IContentProvider, try to coerce it back to the real
     * ContentProvider object if it is running in the local process.  This can
     * be used if you know you are running in the same process as a provider,
     * and want to get direct access to its implementation details.  Most
     * clients should not nor have a reason to use it.
     *
     * @param abstractInterface The ContentProvider interface that is to be
     *              coerced.
     * @return If the IContentProvider is non-{@code null} and local, returns its actual
     * ContentProvider instance.  Otherwise returns {@code null}.
     * @hide
     */
    @UnsupportedAppUsage
    public static ContentProvider coerceToLocalContentProvider(
            IContentProvider abstractInterface) {
        if (abstractInterface instanceof Transport) {
            return ((Transport)abstractInterface).getContentProvider();
        }
        return null;
    }

    /**
     * Binder object that deals with remoting.
     *
     * @hide
     */
    class Transport extends ContentProviderNative {
        volatile AppOpsManager mAppOpsManager = null;
        volatile int mReadOp = AppOpsManager.OP_NONE;
        volatile int mWriteOp = AppOpsManager.OP_NONE;
        volatile ContentInterface mInterface = ContentProvider.this;

        ContentProvider getContentProvider() {
            return ContentProvider.this;
        }

        @Override
        public String getProviderName() {
            return getContentProvider().getClass().getName();
        }

        @Override
        public Cursor query(String callingPkg, @Nullable String attributionTag, Uri uri,
                @Nullable String[] projection, @Nullable Bundle queryArgs,
                @Nullable ICancellationSignal cancellationSignal) {
            uri = validateIncomingUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            if (enforceReadPermission(callingPkg, attributionTag, uri, null)
                    != AppOpsManager.MODE_ALLOWED) {
                // The caller has no access to the data, so return an empty cursor with
                // the columns in the requested order. The caller may ask for an invalid
                // column and we would not catch that but this is not a problem in practice.
                // We do not call ContentProvider#query with a modified where clause since
                // the implementation is not guaranteed to be backed by a SQL database, hence
                // it may not handle properly the tautology where clause we would have created.
                if (projection != null) {
                    return new MatrixCursor(projection, 0);
                }

                // Null projection means all columns but we have no idea which they are.
                // However, the caller may be expecting to access them my index. Hence,
                // we have to execute the query as if allowed to get a cursor with the
                // columns. We then use the column names to return an empty cursor.
                Cursor cursor;
                final Pair<String, String> original = setCallingPackage(
                        new Pair<>(callingPkg, attributionTag));
                try {
                    cursor = mInterface.query(
                            uri, projection, queryArgs,
                            CancellationSignal.fromTransport(cancellationSignal));
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                } finally {
                    setCallingPackage(original);
                }
                if (cursor == null) {
                    return null;
                }

                // Return an empty cursor for all columns.
                return new MatrixCursor(cursor.getColumnNames(), 0);
            }
            Trace.traceBegin(TRACE_TAG_DATABASE, "query");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return mInterface.query(
                        uri, projection, queryArgs,
                        CancellationSignal.fromTransport(cancellationSignal));
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public String getType(Uri uri) {
            // getCallingPackage() isn't available in getType(), as the javadoc states.
            uri = validateIncomingUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            Trace.traceBegin(TRACE_TAG_DATABASE, "getType");
            try {
                return mInterface.getType(uri);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public void getTypeAsync(Uri uri, RemoteCallback callback) {
            final Bundle result = new Bundle();
            try {
                result.putString(ContentResolver.REMOTE_CALLBACK_RESULT, getType(uri));
            } catch (Exception e) {
                putExceptionInBundle(result, ContentResolver.REMOTE_CALLBACK_ERROR, e);
            }
            callback.sendResult(result);
        }

        @Override
        public Uri insert(String callingPkg, @Nullable String attributionTag, Uri uri,
                ContentValues initialValues, Bundle extras) {
            uri = validateIncomingUri(uri);
            int userId = getUserIdFromUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            if (enforceWritePermission(callingPkg, attributionTag, uri, null)
                    != AppOpsManager.MODE_ALLOWED) {
                final Pair<String, String> original = setCallingPackage(
                        new Pair<>(callingPkg, attributionTag));
                try {
                    return rejectInsert(uri, initialValues);
                } finally {
                    setCallingPackage(original);
                }
            }
            Trace.traceBegin(TRACE_TAG_DATABASE, "insert");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return maybeAddUserId(mInterface.insert(uri, initialValues, extras), userId);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public int bulkInsert(String callingPkg, @Nullable String attributionTag, Uri uri,
                ContentValues[] initialValues) {
            uri = validateIncomingUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            if (enforceWritePermission(callingPkg, attributionTag, uri, null)
                    != AppOpsManager.MODE_ALLOWED) {
                return 0;
            }
            Trace.traceBegin(TRACE_TAG_DATABASE, "bulkInsert");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return mInterface.bulkInsert(uri, initialValues);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public ContentProviderResult[] applyBatch(String callingPkg,
                @Nullable String attributionTag, String authority,
                ArrayList<ContentProviderOperation> operations)
                throws OperationApplicationException {
            validateIncomingAuthority(authority);
            int numOperations = operations.size();
            final int[] userIds = new int[numOperations];
            for (int i = 0; i < numOperations; i++) {
                ContentProviderOperation operation = operations.get(i);
                Uri uri = operation.getUri();
                userIds[i] = getUserIdFromUri(uri);
                uri = validateIncomingUri(uri);
                uri = maybeGetUriWithoutUserId(uri);
                // Rebuild operation if we changed the Uri above
                if (!Objects.equals(operation.getUri(), uri)) {
                    operation = new ContentProviderOperation(operation, uri);
                    operations.set(i, operation);
                }
                if (operation.isReadOperation()) {
                    if (enforceReadPermission(callingPkg, attributionTag, uri, null)
                            != AppOpsManager.MODE_ALLOWED) {
                        throw new OperationApplicationException("App op not allowed", 0);
                    }
                }
                if (operation.isWriteOperation()) {
                    if (enforceWritePermission(callingPkg, attributionTag, uri, null)
                            != AppOpsManager.MODE_ALLOWED) {
                        throw new OperationApplicationException("App op not allowed", 0);
                    }
                }
            }
            Trace.traceBegin(TRACE_TAG_DATABASE, "applyBatch");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                ContentProviderResult[] results = mInterface.applyBatch(authority,
                        operations);
                if (results != null) {
                    for (int i = 0; i < results.length ; i++) {
                        if (userIds[i] != UserHandle.USER_CURRENT) {
                            // Adding the userId to the uri.
                            results[i] = new ContentProviderResult(results[i], userIds[i]);
                        }
                    }
                }
                return results;
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public int delete(String callingPkg, @Nullable String attributionTag, Uri uri,
                Bundle extras) {
            uri = validateIncomingUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            if (enforceWritePermission(callingPkg, attributionTag, uri, null)
                    != AppOpsManager.MODE_ALLOWED) {
                return 0;
            }
            Trace.traceBegin(TRACE_TAG_DATABASE, "delete");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return mInterface.delete(uri, extras);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public int update(String callingPkg, @Nullable String attributionTag, Uri uri,
                ContentValues values, Bundle extras) {
            uri = validateIncomingUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            if (enforceWritePermission(callingPkg, attributionTag, uri, null)
                    != AppOpsManager.MODE_ALLOWED) {
                return 0;
            }
            Trace.traceBegin(TRACE_TAG_DATABASE, "update");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return mInterface.update(uri, values, extras);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public ParcelFileDescriptor openFile(String callingPkg, @Nullable String attributionTag,
                Uri uri, String mode, ICancellationSignal cancellationSignal, IBinder callerToken)
                throws FileNotFoundException {
            uri = validateIncomingUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            enforceFilePermission(callingPkg, attributionTag, uri, mode, callerToken);
            Trace.traceBegin(TRACE_TAG_DATABASE, "openFile");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return mInterface.openFile(
                        uri, mode, CancellationSignal.fromTransport(cancellationSignal));
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public AssetFileDescriptor openAssetFile(String callingPkg, @Nullable String attributionTag,
                Uri uri, String mode, ICancellationSignal cancellationSignal)
                throws FileNotFoundException {
            uri = validateIncomingUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            enforceFilePermission(callingPkg, attributionTag, uri, mode, null);
            Trace.traceBegin(TRACE_TAG_DATABASE, "openAssetFile");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return mInterface.openAssetFile(
                        uri, mode, CancellationSignal.fromTransport(cancellationSignal));
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public Bundle call(String callingPkg, @Nullable String attributionTag, String authority,
                String method, @Nullable String arg, @Nullable Bundle extras) {
            validateIncomingAuthority(authority);
            Bundle.setDefusable(extras, true);
            Trace.traceBegin(TRACE_TAG_DATABASE, "call");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return mInterface.call(authority, method, arg, extras);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
            // getCallingPackage() isn't available in getType(), as the javadoc states.
            uri = validateIncomingUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            Trace.traceBegin(TRACE_TAG_DATABASE, "getStreamTypes");
            try {
                return mInterface.getStreamTypes(uri, mimeTypeFilter);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public AssetFileDescriptor openTypedAssetFile(String callingPkg,
                @Nullable String attributionTag, Uri uri, String mimeType, Bundle opts,
                ICancellationSignal cancellationSignal) throws FileNotFoundException {
            Bundle.setDefusable(opts, true);
            uri = validateIncomingUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            enforceFilePermission(callingPkg, attributionTag, uri, "r", null);
            Trace.traceBegin(TRACE_TAG_DATABASE, "openTypedAssetFile");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return mInterface.openTypedAssetFile(
                        uri, mimeType, opts, CancellationSignal.fromTransport(cancellationSignal));
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public ICancellationSignal createCancellationSignal() {
            return CancellationSignal.createTransport();
        }

        @Override
        public Uri canonicalize(String callingPkg, @Nullable String attributionTag, Uri uri) {
            uri = validateIncomingUri(uri);
            int userId = getUserIdFromUri(uri);
            uri = getUriWithoutUserId(uri);
            if (enforceReadPermission(callingPkg, attributionTag, uri, null)
                    != AppOpsManager.MODE_ALLOWED) {
                return null;
            }
            Trace.traceBegin(TRACE_TAG_DATABASE, "canonicalize");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return maybeAddUserId(mInterface.canonicalize(uri), userId);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public void canonicalizeAsync(String callingPkg, @Nullable String attributionTag, Uri uri,
                RemoteCallback callback) {
            final Bundle result = new Bundle();
            try {
                result.putParcelable(ContentResolver.REMOTE_CALLBACK_RESULT,
                        canonicalize(callingPkg, attributionTag, uri));
            } catch (Exception e) {
                putExceptionInBundle(result, ContentResolver.REMOTE_CALLBACK_ERROR, e);
            }
            callback.sendResult(result);
        }

        @Override
        public Uri uncanonicalize(String callingPkg, String attributionTag,  Uri uri) {
            uri = validateIncomingUri(uri);
            int userId = getUserIdFromUri(uri);
            uri = getUriWithoutUserId(uri);
            if (enforceReadPermission(callingPkg, attributionTag, uri, null)
                    != AppOpsManager.MODE_ALLOWED) {
                return null;
            }
            Trace.traceBegin(TRACE_TAG_DATABASE, "uncanonicalize");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return maybeAddUserId(mInterface.uncanonicalize(uri), userId);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public boolean refresh(String callingPkg, String attributionTag, Uri uri, Bundle extras,
                ICancellationSignal cancellationSignal) throws RemoteException {
            uri = validateIncomingUri(uri);
            uri = getUriWithoutUserId(uri);
            if (enforceReadPermission(callingPkg, attributionTag, uri, null)
                    != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
            Trace.traceBegin(TRACE_TAG_DATABASE, "refresh");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return mInterface.refresh(uri, extras,
                        CancellationSignal.fromTransport(cancellationSignal));
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        @Override
        public int checkUriPermission(String callingPkg, @Nullable String attributionTag, Uri uri,
                int uid, int modeFlags) {
            uri = validateIncomingUri(uri);
            uri = maybeGetUriWithoutUserId(uri);
            Trace.traceBegin(TRACE_TAG_DATABASE, "checkUriPermission");
            final Pair<String, String> original = setCallingPackage(
                    new Pair<>(callingPkg, attributionTag));
            try {
                return mInterface.checkUriPermission(uri, uid, modeFlags);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } finally {
                setCallingPackage(original);
                Trace.traceEnd(TRACE_TAG_DATABASE);
            }
        }

        private void enforceFilePermission(String callingPkg, @Nullable String attributionTag,
                Uri uri, String mode, IBinder callerToken)
                throws FileNotFoundException, SecurityException {
            if (mode != null && mode.indexOf('w') != -1) {
                if (enforceWritePermission(callingPkg, attributionTag, uri, callerToken)
                        != AppOpsManager.MODE_ALLOWED) {
                    throw new FileNotFoundException("App op not allowed");
                }
            } else {
                if (enforceReadPermission(callingPkg, attributionTag, uri, callerToken)
                        != AppOpsManager.MODE_ALLOWED) {
                    throw new FileNotFoundException("App op not allowed");
                }
            }
        }

        private int enforceReadPermission(String callingPkg, @Nullable String attributionTag,
                Uri uri, IBinder callerToken)
                throws SecurityException {
            final int mode = enforceReadPermissionInner(uri, callingPkg, attributionTag,
                    callerToken);
            if (mode != MODE_ALLOWED) {
                return mode;
            }

            return noteProxyOp(callingPkg, attributionTag, mReadOp);
        }

        private int enforceWritePermission(String callingPkg, String attributionTag, Uri uri,
                IBinder callerToken)
                throws SecurityException {
            final int mode = enforceWritePermissionInner(uri, callingPkg, attributionTag,
                    callerToken);
            if (mode != MODE_ALLOWED) {
                return mode;
            }

            return noteProxyOp(callingPkg, attributionTag, mWriteOp);
        }

        private int noteProxyOp(String callingPkg, String attributionTag, int op) {
            if (op != AppOpsManager.OP_NONE) {
                int mode = mAppOpsManager.noteProxyOp(op, callingPkg, Binder.getCallingUid(),
                        attributionTag, null);
                return mode == MODE_DEFAULT ? MODE_IGNORED : mode;
            }

            return AppOpsManager.MODE_ALLOWED;
        }

        private void putExceptionInBundle(Bundle bundle, String key, Exception e) {
            Parcel parcel = Parcel.obtain();
            try {
                try {
                    parcel.writeException(e);
                } catch (Exception ex) {
                    // getType threw an unparcelable exception. Wrap the message into
                    // a parcelable exception type
                    parcel.writeException(new IllegalStateException(e.getMessage()));
                }
                bundle.putByteArray(key, parcel.marshall());
            } finally {
                parcel.recycle();
            }
        }
    }

    boolean checkUser(int pid, int uid, Context context) {
        if (UserHandle.getUserId(uid) == context.getUserId() || mSingleUser) {
            return true;
        }
        return context.checkPermission(INTERACT_ACROSS_USERS, pid, uid) == PERMISSION_GRANTED
                || context.checkPermission(INTERACT_ACROSS_USERS_FULL, pid, uid)
                == PERMISSION_GRANTED;
    }

    /**
     * Verify that calling app holds both the given permission and any app-op
     * associated with that permission.
     */
    private int checkPermissionAndAppOp(String permission, String callingPkg,
            @Nullable String attributionTag, IBinder callerToken) {
        if (getContext().checkPermission(permission, Binder.getCallingPid(), Binder.getCallingUid(),
                callerToken) != PERMISSION_GRANTED) {
            return MODE_ERRORED;
        }

        return mTransport.noteProxyOp(callingPkg, attributionTag,
                AppOpsManager.permissionToOpCode(permission));
    }

    /** {@hide} */
    protected int enforceReadPermissionInner(Uri uri, String callingPkg,
            @Nullable String attributionTag, IBinder callerToken) throws SecurityException {
        final Context context = getContext();
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        String missingPerm = null;
        int strongestMode = MODE_ALLOWED;

        if (UserHandle.isSameApp(uid, mMyUid)) {
            return MODE_ALLOWED;
        }

        if (mExported && checkUser(pid, uid, context)) {
            final String componentPerm = getReadPermission();
            if (componentPerm != null) {
                final int mode = checkPermissionAndAppOp(componentPerm, callingPkg, attributionTag,
                        callerToken);
                if (mode == MODE_ALLOWED) {
                    return MODE_ALLOWED;
                } else {
                    missingPerm = componentPerm;
                    strongestMode = Math.max(strongestMode, mode);
                }
            }

            // track if unprotected read is allowed; any denied
            // <path-permission> below removes this ability
            boolean allowDefaultRead = (componentPerm == null);

            final PathPermission[] pps = getPathPermissions();
            if (pps != null) {
                final String path = uri.getPath();
                for (PathPermission pp : pps) {
                    final String pathPerm = pp.getReadPermission();
                    if (pathPerm != null && pp.match(path)) {
                        final int mode = checkPermissionAndAppOp(pathPerm, callingPkg,
                                attributionTag, callerToken);
                        if (mode == MODE_ALLOWED) {
                            return MODE_ALLOWED;
                        } else {
                            // any denied <path-permission> means we lose
                            // default <provider> access.
                            allowDefaultRead = false;
                            missingPerm = pathPerm;
                            strongestMode = Math.max(strongestMode, mode);
                        }
                    }
                }
            }

            // if we passed <path-permission> checks above, and no default
            // <provider> permission, then allow access.
            if (allowDefaultRead) return MODE_ALLOWED;
        }

        // last chance, check against any uri grants
        final int callingUserId = UserHandle.getUserId(uid);
        final Uri userUri = (mSingleUser && !UserHandle.isSameUser(mMyUid, uid))
                ? maybeAddUserId(uri, callingUserId) : uri;
        if (context.checkUriPermission(userUri, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                callerToken) == PERMISSION_GRANTED) {
            return MODE_ALLOWED;
        }

        // If the worst denial we found above was ignored, then pass that
        // ignored through; otherwise we assume it should be a real error below.
        if (strongestMode == MODE_IGNORED) {
            return MODE_IGNORED;
        }

        final String suffix;
        if (android.Manifest.permission.MANAGE_DOCUMENTS.equals(mReadPermission)) {
            suffix = " requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs";
        } else if (mExported) {
            suffix = " requires " + missingPerm + ", or grantUriPermission()";
        } else {
            suffix = " requires the provider be exported, or grantUriPermission()";
        }
        throw new SecurityException("Permission Denial: reading "
                + ContentProvider.this.getClass().getName() + " uri " + uri + " from pid=" + pid
                + ", uid=" + uid + suffix);
    }

    /** {@hide} */
    protected int enforceWritePermissionInner(Uri uri, String callingPkg,
            @Nullable String attributionTag, IBinder callerToken) throws SecurityException {
        final Context context = getContext();
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        String missingPerm = null;
        int strongestMode = MODE_ALLOWED;

        if (UserHandle.isSameApp(uid, mMyUid)) {
            return MODE_ALLOWED;
        }

        if (mExported && checkUser(pid, uid, context)) {
            final String componentPerm = getWritePermission();
            if (componentPerm != null) {
                final int mode = checkPermissionAndAppOp(componentPerm, callingPkg,
                        attributionTag, callerToken);
                if (mode == MODE_ALLOWED) {
                    return MODE_ALLOWED;
                } else {
                    missingPerm = componentPerm;
                    strongestMode = Math.max(strongestMode, mode);
                }
            }

            // track if unprotected write is allowed; any denied
            // <path-permission> below removes this ability
            boolean allowDefaultWrite = (componentPerm == null);

            final PathPermission[] pps = getPathPermissions();
            if (pps != null) {
                final String path = uri.getPath();
                for (PathPermission pp : pps) {
                    final String pathPerm = pp.getWritePermission();
                    if (pathPerm != null && pp.match(path)) {
                        final int mode = checkPermissionAndAppOp(pathPerm, callingPkg,
                                attributionTag, callerToken);
                        if (mode == MODE_ALLOWED) {
                            return MODE_ALLOWED;
                        } else {
                            // any denied <path-permission> means we lose
                            // default <provider> access.
                            allowDefaultWrite = false;
                            missingPerm = pathPerm;
                            strongestMode = Math.max(strongestMode, mode);
                        }
                    }
                }
            }

            // if we passed <path-permission> checks above, and no default
            // <provider> permission, then allow access.
            if (allowDefaultWrite) return MODE_ALLOWED;
        }

        // last chance, check against any uri grants
        if (context.checkUriPermission(uri, pid, uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                callerToken) == PERMISSION_GRANTED) {
            return MODE_ALLOWED;
        }

        // If the worst denial we found above was ignored, then pass that
        // ignored through; otherwise we assume it should be a real error below.
        if (strongestMode == MODE_IGNORED) {
            return MODE_IGNORED;
        }

        final String failReason = mExported
                ? " requires " + missingPerm + ", or grantUriPermission()"
                : " requires the provider be exported, or grantUriPermission()";
        throw new SecurityException("Permission Denial: writing "
                + ContentProvider.this.getClass().getName() + " uri " + uri + " from pid=" + pid
                + ", uid=" + uid + failReason);
    }

    /**
     * Retrieves the Context this provider is running in.  Only available once
     * {@link #onCreate} has been called -- this will return {@code null} in the
     * constructor.
     */
    public final @Nullable Context getContext() {
        return mContext;
    }

    /**
     * Retrieves a Non-Nullable Context this provider is running in, this is intended to be called
     * after {@link #onCreate}. When called before context was created, an IllegalStateException
     * will be thrown.
     * <p>
     * Note A provider must be declared in the manifest and created automatically by the system,
     * and context is only available after {@link #onCreate} is called.
     */
    @NonNull
    public final Context requireContext() {
        final Context ctx = getContext();
        if (ctx == null) {
            throw new IllegalStateException("Cannot find context from the provider.");
        }
        return ctx;
    }

    /**
     * Set the calling package/feature, returning the current value (or {@code null})
     * which can be used later to restore the previous state.
     */
    private Pair<String, String> setCallingPackage(Pair<String, String> callingPackage) {
        final Pair<String, String> original = mCallingPackage.get();
        mCallingPackage.set(callingPackage);
        onCallingPackageChanged();
        return original;
    }

    /**
     * Return the package name of the caller that initiated the request being
     * processed on the current thread. The returned package will have been
     * verified to belong to the calling UID. Returns {@code null} if not
     * currently processing a request.
     * <p>
     * This will always return {@code null} when processing
     * {@link #getType(Uri)} or {@link #getStreamTypes(Uri, String)} requests.
     *
     * @see Binder#getCallingUid()
     * @see Context#grantUriPermission(String, Uri, int)
     * @throws SecurityException if the calling package doesn't belong to the
     *             calling UID.
     */
    public final @Nullable String getCallingPackage() {
        final Pair<String, String> pkg = mCallingPackage.get();
        if (pkg != null) {
            mTransport.mAppOpsManager.checkPackage(Binder.getCallingUid(), pkg.first);
            return pkg.first;
        }

        return null;
    }

    /**
     * Return the attribution tag of the caller that initiated the request being
     * processed on the current thread. Returns {@code null} if not currently processing
     * a request of the request is for the default attribution.
     * <p>
     * This will always return {@code null} when processing
     * {@link #getType(Uri)} or {@link #getStreamTypes(Uri, String)} requests.
     *
     * @see #getCallingPackage
     */
    public final @Nullable String getCallingAttributionTag() {
        final Pair<String, String> pkg = mCallingPackage.get();
        if (pkg != null) {
            return pkg.second;
        }

        return null;
    }

    /**
     * @removed
     */
    @Deprecated
    public final @Nullable String getCallingFeatureId() {
        return getCallingAttributionTag();
    }

    /**
     * Return the package name of the caller that initiated the request being
     * processed on the current thread. The returned package will have
     * <em>not</em> been verified to belong to the calling UID. Returns
     * {@code null} if not currently processing a request.
     * <p>
     * This will always return {@code null} when processing
     * {@link #getType(Uri)} or {@link #getStreamTypes(Uri, String)} requests.
     *
     * @see Binder#getCallingUid()
     * @see Context#grantUriPermission(String, Uri, int)
     */
    public final @Nullable String getCallingPackageUnchecked() {
        final Pair<String, String> pkg = mCallingPackage.get();
        if (pkg != null) {
            return pkg.first;
        }

        return null;
    }

    /**
     * Called whenever the value of {@link #getCallingPackage()} changes, giving
     * the provider an opportunity to invalidate any security related caching it
     * may be performing.
     * <p>
     * This typically happens when a {@link ContentProvider} makes a nested call
     * back into itself when already processing a call from a remote process.
     */
    public void onCallingPackageChanged() {
    }

    /**
     * Opaque token representing the identity of an incoming IPC.
     */
    public final class CallingIdentity {
        /** {@hide} */
        public final long binderToken;
        /** {@hide} */
        public final Pair<String, String> callingPackage;

        /** {@hide} */
        public CallingIdentity(long binderToken, Pair<String, String> callingPackage) {
            this.binderToken = binderToken;
            this.callingPackage = callingPackage;
        }
    }

    /**
     * Reset the identity of the incoming IPC on the current thread.
     * <p>
     * Internally this calls {@link Binder#clearCallingIdentity()} and also
     * clears any value stored in {@link #getCallingPackage()}.
     *
     * @return Returns an opaque token that can be used to restore the original
     *         calling identity by passing it to
     *         {@link #restoreCallingIdentity}.
     */
    public final @NonNull CallingIdentity clearCallingIdentity() {
        return new CallingIdentity(Binder.clearCallingIdentity(), setCallingPackage(null));
    }

    /**
     * Restore the identity of the incoming IPC on the current thread back to a
     * previously identity that was returned by {@link #clearCallingIdentity}.
     * <p>
     * Internally this calls {@link Binder#restoreCallingIdentity(long)} and
     * also restores any value stored in {@link #getCallingPackage()}.
     */
    public final void restoreCallingIdentity(@NonNull CallingIdentity identity) {
        Binder.restoreCallingIdentity(identity.binderToken);
        mCallingPackage.set(identity.callingPackage);
    }

    /**
     * Change the authorities of the ContentProvider.
     * This is normally set for you from its manifest information when the provider is first
     * created.
     * @hide
     * @param authorities the semi-colon separated authorities of the ContentProvider.
     */
    protected final void setAuthorities(String authorities) {
        if (authorities != null) {
            if (authorities.indexOf(';') == -1) {
                mAuthority = authorities;
                mAuthorities = null;
            } else {
                mAuthority = null;
                mAuthorities = authorities.split(";");
            }
        }
    }

    /** @hide */
    protected final boolean matchesOurAuthorities(String authority) {
        if (mAuthority != null) {
            return mAuthority.equals(authority);
        }
        if (mAuthorities != null) {
            int length = mAuthorities.length;
            for (int i = 0; i < length; i++) {
                if (mAuthorities[i].equals(authority)) return true;
            }
        }
        return false;
    }


    /**
     * Change the permission required to read data from the content
     * provider.  This is normally set for you from its manifest information
     * when the provider is first created.
     *
     * @param permission Name of the permission required for read-only access.
     */
    protected final void setReadPermission(@Nullable String permission) {
        mReadPermission = permission;
    }

    /**
     * Return the name of the permission required for read-only access to
     * this content provider.  This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     */
    public final @Nullable String getReadPermission() {
        return mReadPermission;
    }

    /**
     * Change the permission required to read and write data in the content
     * provider.  This is normally set for you from its manifest information
     * when the provider is first created.
     *
     * @param permission Name of the permission required for read/write access.
     */
    protected final void setWritePermission(@Nullable String permission) {
        mWritePermission = permission;
    }

    /**
     * Return the name of the permission required for read/write access to
     * this content provider.  This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     */
    public final @Nullable String getWritePermission() {
        return mWritePermission;
    }

    /**
     * Change the path-based permission required to read and/or write data in
     * the content provider.  This is normally set for you from its manifest
     * information when the provider is first created.
     *
     * @param permissions Array of path permission descriptions.
     */
    protected final void setPathPermissions(@Nullable PathPermission[] permissions) {
        mPathPermissions = permissions;
    }

    /**
     * Return the path-based permissions required for read and/or write access to
     * this content provider.  This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     */
    public final @Nullable PathPermission[] getPathPermissions() {
        return mPathPermissions;
    }

    /** @hide */
    @UnsupportedAppUsage
    public final void setAppOps(int readOp, int writeOp) {
        if (!mNoPerms) {
            mTransport.mReadOp = readOp;
            mTransport.mWriteOp = writeOp;
        }
    }

    /** @hide */
    public AppOpsManager getAppOpsManager() {
        return mTransport.mAppOpsManager;
    }

    /** @hide */
    public final void setTransportLoggingEnabled(boolean enabled) {
        if (mTransport == null) {
            return;
        }
        if (enabled) {
            mTransport.mInterface = new LoggingContentInterface(getClass().getSimpleName(), this);
        } else {
            mTransport.mInterface = this;
        }
    }

    /**
     * Implement this to initialize your content provider on startup.
     * This method is called for all registered content providers on the
     * application main thread at application launch time.  It must not perform
     * lengthy operations, or application startup will be delayed.
     *
     * <p>You should defer nontrivial initialization (such as opening,
     * upgrading, and scanning databases) until the content provider is used
     * (via {@link #query}, {@link #insert}, etc).  Deferred initialization
     * keeps application startup fast, avoids unnecessary work if the provider
     * turns out not to be needed, and stops database errors (such as a full
     * disk) from halting application launch.
     *
     * <p>If you use SQLite, {@link android.database.sqlite.SQLiteOpenHelper}
     * is a helpful utility class that makes it easy to manage databases,
     * and will automatically defer opening until first use.  If you do use
     * SQLiteOpenHelper, make sure to avoid calling
     * {@link android.database.sqlite.SQLiteOpenHelper#getReadableDatabase} or
     * {@link android.database.sqlite.SQLiteOpenHelper#getWritableDatabase}
     * from this method.  (Instead, override
     * {@link android.database.sqlite.SQLiteOpenHelper#onOpen} to initialize the
     * database when it is first opened.)
     *
     * @return true if the provider was successfully loaded, false otherwise
     */
    public abstract boolean onCreate();

    /**
     * {@inheritDoc}
     * This method is always called on the application main thread, and must
     * not perform lengthy operations.
     *
     * <p>The default content provider implementation does nothing.
     * Override this method to take appropriate action.
     * (Content providers do not usually care about things like screen
     * orientation, but may want to know about locale changes.)
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
    }

    /**
     * {@inheritDoc}
     * This method is always called on the application main thread, and must
     * not perform lengthy operations.
     *
     * <p>The default content provider implementation does nothing.
     * Subclasses may override this method to take appropriate action.
     */
    @Override
    public void onLowMemory() {
    }

    @Override
    public void onTrimMemory(int level) {
    }

    /**
     * Implement this to handle query requests from clients.
     *
     * <p>Apps targeting {@link android.os.Build.VERSION_CODES#O} or higher should override
     * {@link #query(Uri, String[], Bundle, CancellationSignal)} and provide a stub
     * implementation of this method.
     *
     * <p>This method can be called from multiple threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     * <p>
     * Example client call:<p>
     * <pre>// Request a specific record.
     * Cursor managedCursor = managedQuery(
                ContentUris.withAppendedId(Contacts.People.CONTENT_URI, 2),
                projection,    // Which columns to return.
                null,          // WHERE clause.
                null,          // WHERE clause value substitution
                People.NAME + " ASC");   // Sort order.</pre>
     * Example implementation:<p>
     * <pre>// SQLiteQueryBuilder is a helper class that creates the
        // proper SQL syntax for us.
        SQLiteQueryBuilder qBuilder = new SQLiteQueryBuilder();

        // Set the table we're querying.
        qBuilder.setTables(DATABASE_TABLE_NAME);

        // If the query ends in a specific record number, we're
        // being asked for a specific record, so set the
        // WHERE clause in our query.
        if((URI_MATCHER.match(uri)) == SPECIFIC_MESSAGE){
            qBuilder.appendWhere("_id=" + uri.getPathLeafId());
        }

        // Make the query.
        Cursor c = qBuilder.query(mDb,
                projection,
                selection,
                selectionArgs,
                groupBy,
                having,
                sortOrder);
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;</pre>
     *
     * @param uri The URI to query. This will be the full URI sent by the client;
     *      if the client is requesting a specific record, the URI will end in a record number
     *      that the implementation should parse and add to a WHERE or HAVING clause, specifying
     *      that _id value.
     * @param projection The list of columns to put into the cursor. If
     *      {@code null} all columns are included.
     * @param selection A selection criteria to apply when filtering rows.
     *      If {@code null} then all rows are included.
     * @param selectionArgs You may include ?s in selection, which will be replaced by
     *      the values from selectionArgs, in order that they appear in the selection.
     *      The values will be bound as Strings.
     * @param sortOrder How the rows in the cursor should be sorted.
     *      If {@code null} then the provider is free to define the sort order.
     * @return a Cursor or {@code null}.
     */
    public abstract @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder);

    /**
     * Implement this to handle query requests from clients with support for cancellation.
     *
     * <p>Apps targeting {@link android.os.Build.VERSION_CODES#O} or higher should override
     * {@link #query(Uri, String[], Bundle, CancellationSignal)} instead of this method.
     *
     * <p>This method can be called from multiple threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     * <p>
     * Example client call:<p>
     * <pre>// Request a specific record.
     * Cursor managedCursor = managedQuery(
                ContentUris.withAppendedId(Contacts.People.CONTENT_URI, 2),
                projection,    // Which columns to return.
                null,          // WHERE clause.
                null,          // WHERE clause value substitution
                People.NAME + " ASC");   // Sort order.</pre>
     * Example implementation:<p>
     * <pre>// SQLiteQueryBuilder is a helper class that creates the
        // proper SQL syntax for us.
        SQLiteQueryBuilder qBuilder = new SQLiteQueryBuilder();

        // Set the table we're querying.
        qBuilder.setTables(DATABASE_TABLE_NAME);

        // If the query ends in a specific record number, we're
        // being asked for a specific record, so set the
        // WHERE clause in our query.
        if((URI_MATCHER.match(uri)) == SPECIFIC_MESSAGE){
            qBuilder.appendWhere("_id=" + uri.getPathLeafId());
        }

        // Make the query.
        Cursor c = qBuilder.query(mDb,
                projection,
                selection,
                selectionArgs,
                groupBy,
                having,
                sortOrder);
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;</pre>
     * <p>
     * If you implement this method then you must also implement the version of
     * {@link #query(Uri, String[], String, String[], String)} that does not take a cancellation
     * signal to ensure correct operation on older versions of the Android Framework in
     * which the cancellation signal overload was not available.
     *
     * @param uri The URI to query. This will be the full URI sent by the client;
     *      if the client is requesting a specific record, the URI will end in a record number
     *      that the implementation should parse and add to a WHERE or HAVING clause, specifying
     *      that _id value.
     * @param projection The list of columns to put into the cursor. If
     *      {@code null} all columns are included.
     * @param selection A selection criteria to apply when filtering rows.
     *      If {@code null} then all rows are included.
     * @param selectionArgs You may include ?s in selection, which will be replaced by
     *      the values from selectionArgs, in order that they appear in the selection.
     *      The values will be bound as Strings.
     * @param sortOrder How the rows in the cursor should be sorted.
     *      If {@code null} then the provider is free to define the sort order.
     * @param cancellationSignal A signal to cancel the operation in progress, or {@code null} if none.
     * If the operation is canceled, then {@link android.os.OperationCanceledException} will be thrown
     * when the query is executed.
     * @return a Cursor or {@code null}.
     */
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder, @Nullable CancellationSignal cancellationSignal) {
        return query(uri, projection, selection, selectionArgs, sortOrder);
    }

    /**
     * Implement this to handle query requests where the arguments are packed into a {@link Bundle}.
     * Arguments may include traditional SQL style query arguments. When present these
     * should be handled  according to the contract established in
     * {@link #query(Uri, String[], String, String[], String, CancellationSignal)}.
     *
     * <p>Traditional SQL arguments can be found in the bundle using the following keys:
     * <li>{@link android.content.ContentResolver#QUERY_ARG_SQL_SELECTION}
     * <li>{@link android.content.ContentResolver#QUERY_ARG_SQL_SELECTION_ARGS}
     * <li>{@link android.content.ContentResolver#QUERY_ARG_SQL_SORT_ORDER}
     *
     * <p>This method can be called from multiple threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * <p>
     * Example client call:<p>
     * <pre>// Request 20 records starting at row index 30.
       Bundle queryArgs = new Bundle();
       queryArgs.putInt(ContentResolver.QUERY_ARG_OFFSET, 30);
       queryArgs.putInt(ContentResolver.QUERY_ARG_LIMIT, 20);

       Cursor cursor = getContentResolver().query(
                contentUri,    // Content Uri is specific to individual content providers.
                projection,    // String[] describing which columns to return.
                queryArgs,     // Query arguments.
                null);         // Cancellation signal.</pre>
     *
     * Example implementation:<p>
     * <pre>

        int recordsetSize = 0x1000;  // Actual value is implementation specific.
        queryArgs = queryArgs != null ? queryArgs : Bundle.EMPTY;  // ensure queryArgs is non-null

        int offset = queryArgs.getInt(ContentResolver.QUERY_ARG_OFFSET, 0);
        int limit = queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT, Integer.MIN_VALUE);

        MatrixCursor c = new MatrixCursor(PROJECTION, limit);

        // Calculate the number of items to include in the cursor.
        int numItems = MathUtils.constrain(recordsetSize - offset, 0, limit);

        // Build the paged result set....
        for (int i = offset; i < offset + numItems; i++) {
            // populate row from your data.
        }

        Bundle extras = new Bundle();
        c.setExtras(extras);

        // Any QUERY_ARG_* key may be included if honored.
        // In an actual implementation, include only keys that are both present in queryArgs
        // and reflected in the Cursor output. For example, if QUERY_ARG_OFFSET were included
        // in queryArgs, but was ignored because it contained an invalid value (like –273),
        // then QUERY_ARG_OFFSET should be omitted.
        extras.putStringArray(ContentResolver.EXTRA_HONORED_ARGS, new String[] {
            ContentResolver.QUERY_ARG_OFFSET,
            ContentResolver.QUERY_ARG_LIMIT
        });

        extras.putInt(ContentResolver.EXTRA_TOTAL_COUNT, recordsetSize);

        cursor.setNotificationUri(getContext().getContentResolver(), uri);

        return cursor;</pre>
     * <p>
     * See {@link #query(Uri, String[], String, String[], String, CancellationSignal)}
     * for implementation details.
     *
     * @param uri The URI to query. This will be the full URI sent by the client.
     * @param projection The list of columns to put into the cursor.
     *            If {@code null} provide a default set of columns.
     * @param queryArgs A Bundle containing additional information necessary for
     *            the operation. Arguments may include SQL style arguments, such
     *            as {@link ContentResolver#QUERY_ARG_SQL_LIMIT}, but note that
     *            the documentation for each individual provider will indicate
     *            which arguments they support.
     * @param cancellationSignal A signal to cancel the operation in progress,
     *            or {@code null}.
     * @return a Cursor or {@code null}.
     */
    @Override
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable Bundle queryArgs, @Nullable CancellationSignal cancellationSignal) {
        queryArgs = queryArgs != null ? queryArgs : Bundle.EMPTY;

        // if client doesn't supply an SQL sort order argument, attempt to build one from
        // QUERY_ARG_SORT* arguments.
        String sortClause = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER);
        if (sortClause == null && queryArgs.containsKey(ContentResolver.QUERY_ARG_SORT_COLUMNS)) {
            sortClause = ContentResolver.createSqlSortClause(queryArgs);
        }

        return query(
                uri,
                projection,
                queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
                sortClause,
                cancellationSignal);
    }

    /**
     * Implement this to handle requests for the MIME type of the data at the
     * given URI.  The returned MIME type should start with
     * <code>vnd.android.cursor.item</code> for a single record,
     * or <code>vnd.android.cursor.dir/</code> for multiple items.
     * This method can be called from multiple threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * <p>Note that there are no permissions needed for an application to
     * access this information; if your content provider requires read and/or
     * write permissions, or is not exported, all applications can still call
     * this method regardless of their access permissions.  This allows them
     * to retrieve the MIME type for a URI when dispatching intents.
     *
     * @param uri the URI to query.
     * @return a MIME type string, or {@code null} if there is no type.
     */
    @Override
    public abstract @Nullable String getType(@NonNull Uri uri);

    /**
     * Implement this to support canonicalization of URIs that refer to your
     * content provider.  A canonical URI is one that can be transported across
     * devices, backup/restore, and other contexts, and still be able to refer
     * to the same data item.  Typically this is implemented by adding query
     * params to the URI allowing the content provider to verify that an incoming
     * canonical URI references the same data as it was originally intended for and,
     * if it doesn't, to find that data (if it exists) in the current environment.
     *
     * <p>For example, if the content provider holds people and a normal URI in it
     * is created with a row index into that people database, the cananical representation
     * may have an additional query param at the end which specifies the name of the
     * person it is intended for.  Later calls into the provider with that URI will look
     * up the row of that URI's base index and, if it doesn't match or its entry's
     * name doesn't match the name in the query param, perform a query on its database
     * to find the correct row to operate on.</p>
     *
     * <p>If you implement support for canonical URIs, <b>all</b> incoming calls with
     * URIs (including this one) must perform this verification and recovery of any
     * canonical URIs they receive.  In addition, you must also implement
     * {@link #uncanonicalize} to strip the canonicalization of any of these URIs.</p>
     *
     * <p>The default implementation of this method returns null, indicating that
     * canonical URIs are not supported.</p>
     *
     * @param url The Uri to canonicalize.
     *
     * @return Return the canonical representation of <var>url</var>, or null if
     * canonicalization of that Uri is not supported.
     */
    @Override
    public @Nullable Uri canonicalize(@NonNull Uri url) {
        return null;
    }

    /**
     * Remove canonicalization from canonical URIs previously returned by
     * {@link #canonicalize}.  For example, if your implementation is to add
     * a query param to canonicalize a URI, this method can simply trip any
     * query params on the URI.  The default implementation always returns the
     * same <var>url</var> that was passed in.
     *
     * @param url The Uri to remove any canonicalization from.
     *
     * @return Return the non-canonical representation of <var>url</var>, return
     * the <var>url</var> as-is if there is nothing to do, or return null if
     * the data identified by the canonical representation can not be found in
     * the current environment.
     */
    @Override
    public @Nullable Uri uncanonicalize(@NonNull Uri url) {
        return url;
    }

    /**
     * Implement this to support refresh of content identified by {@code uri}.
     * By default, this method returns false; providers who wish to implement
     * this should return true to signal the client that the provider has tried
     * refreshing with its own implementation.
     * <p>
     * This allows clients to request an explicit refresh of content identified
     * by {@code uri}.
     * <p>
     * Client code should only invoke this method when there is a strong
     * indication (such as a user initiated pull to refresh gesture) that the
     * content is stale.
     * <p>
     * Remember to send
     * {@link ContentResolver#notifyChange(Uri, android.database.ContentObserver)}
     * notifications when content changes.
     *
     * @param uri The Uri identifying the data to refresh.
     * @param extras Additional options from the client. The definitions of
     *            these are specific to the content provider being called.
     * @param cancellationSignal A signal to cancel the operation in progress,
     *            or {@code null} if none. For example, if you called refresh on
     *            a particular uri, you should call
     *            {@link CancellationSignal#throwIfCanceled()} to check whether
     *            the client has canceled the refresh request.
     * @return true if the provider actually tried refreshing.
     */
    @Override
    public boolean refresh(Uri uri, @Nullable Bundle extras,
            @Nullable CancellationSignal cancellationSignal) {
        return false;
    }

    /**
     * Perform a detailed internal check on a {@link Uri} to determine if a UID
     * is able to access it with specific mode flags.
     * <p>
     * This method is typically used when the provider implements more dynamic
     * access controls that cannot be expressed with {@code <path-permission>}
     * style static rules.
     * <p>
     * Because validation of these dynamic access controls has significant
     * system health impact, this feature is only available to providers that
     * are built into the system.
     *
     * @param uri the {@link Uri} to perform an access check on.
     * @param uid the UID to check the permission for.
     * @param modeFlags the access flags to use for the access check, such as
     *            {@link Intent#FLAG_GRANT_READ_URI_PERMISSION}.
     * @return {@link PackageManager#PERMISSION_GRANTED} if access is allowed,
     *         otherwise {@link PackageManager#PERMISSION_DENIED}.
     * @hide
     */
    @Override
    @SystemApi
    public int checkUriPermission(@NonNull Uri uri, int uid, @Intent.AccessUriMode int modeFlags) {
        return PackageManager.PERMISSION_DENIED;
    }

    /**
     * @hide
     * Implementation when a caller has performed an insert on the content
     * provider, but that call has been rejected for the operation given
     * to {@link #setAppOps(int, int)}.  The default implementation simply
     * returns a dummy URI that is the base URI with a 0 path element
     * appended.
     */
    public Uri rejectInsert(Uri uri, ContentValues values) {
        // If not allowed, we need to return some reasonable URI.  Maybe the
        // content provider should be responsible for this, but for now we
        // will just return the base URI with a dummy '0' tagged on to it.
        // You shouldn't be able to read if you can't write, anyway, so it
        // shouldn't matter much what is returned.
        return uri.buildUpon().appendPath("0").build();
    }

    /**
     * Implement this to handle requests to insert a new row. As a courtesy,
     * call
     * {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver)
     * notifyChange()} after inserting. This method can be called from multiple
     * threads, as described in <a href="
     * {@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * @param uri The content:// URI of the insertion request.
     * @param values A set of column_name/value pairs to add to the database.
     * @return The URI for the newly inserted item.
     */
    public abstract @Nullable Uri insert(@NonNull Uri uri, @Nullable ContentValues values);

    /**
     * Implement this to handle requests to insert a new row. As a courtesy,
     * call
     * {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver)
     * notifyChange()} after inserting. This method can be called from multiple
     * threads, as described in <a href="
     * {@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * @param uri The content:// URI of the insertion request.
     * @param values A set of column_name/value pairs to add to the database.
     * @param extras A Bundle containing additional information necessary for
     *            the operation. Arguments may include SQL style arguments, such
     *            as {@link ContentResolver#QUERY_ARG_SQL_LIMIT}, but note that
     *            the documentation for each individual provider will indicate
     *            which arguments they support.
     * @return The URI for the newly inserted item.
     * @throws IllegalArgumentException if the provider doesn't support one of
     *             the requested Bundle arguments.
     */
    @Override
    public @Nullable Uri insert(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable Bundle extras) {
        return insert(uri, values);
    }

    /**
     * Override this to handle requests to insert a set of new rows, or the
     * default implementation will iterate over the values and call
     * {@link #insert} on each of them.
     * As a courtesy, call {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver) notifyChange()}
     * after inserting.
     * This method can be called from multiple threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * @param uri The content:// URI of the insertion request.
     * @param values An array of sets of column_name/value pairs to add to the database.
     *    This must not be {@code null}.
     * @return The number of values that were inserted.
     */
    @Override
    public int bulkInsert(@NonNull Uri uri, @NonNull ContentValues[] values) {
        int numValues = values.length;
        for (int i = 0; i < numValues; i++) {
            insert(uri, values[i]);
        }
        return numValues;
    }

    /**
     * Implement this to handle requests to delete one or more rows. The
     * implementation should apply the selection clause when performing
     * deletion, allowing the operation to affect multiple rows in a directory.
     * As a courtesy, call
     * {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver)
     * notifyChange()} after deleting. This method can be called from multiple
     * threads, as described in <a href="
     * {@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     * <p>
     * The implementation is responsible for parsing out a row ID at the end of
     * the URI, if a specific row is being deleted. That is, the client would
     * pass in <code>content://contacts/people/22</code> and the implementation
     * is responsible for parsing the record number (22) when creating a SQL
     * statement.
     *
     * @param uri The full URI to query, including a row ID (if a specific
     *            record is requested).
     * @param selection An optional restriction to apply to rows when deleting.
     * @return The number of rows affected.
     * @throws SQLException
     */
    public abstract int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs);

    /**
     * Implement this to handle requests to delete one or more rows. The
     * implementation should apply the selection clause when performing
     * deletion, allowing the operation to affect multiple rows in a directory.
     * As a courtesy, call
     * {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver)
     * notifyChange()} after deleting. This method can be called from multiple
     * threads, as described in <a href="
     * {@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     * <p>
     * The implementation is responsible for parsing out a row ID at the end of
     * the URI, if a specific row is being deleted. That is, the client would
     * pass in <code>content://contacts/people/22</code> and the implementation
     * is responsible for parsing the record number (22) when creating a SQL
     * statement.
     *
     * @param uri The full URI to query, including a row ID (if a specific
     *            record is requested).
     * @param extras A Bundle containing additional information necessary for
     *            the operation. Arguments may include SQL style arguments, such
     *            as {@link ContentResolver#QUERY_ARG_SQL_LIMIT}, but note that
     *            the documentation for each individual provider will indicate
     *            which arguments they support.
     * @throws IllegalArgumentException if the provider doesn't support one of
     *             the requested Bundle arguments.
     * @throws SQLException
     */
    @Override
    public int delete(@NonNull Uri uri, @Nullable Bundle extras) {
        extras = (extras != null) ? extras : Bundle.EMPTY;
        return delete(uri,
                extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                extras.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS));
    }

    /**
     * Implement this to handle requests to update one or more rows. The
     * implementation should update all rows matching the selection to set the
     * columns according to the provided values map. As a courtesy, call
     * {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver)
     * notifyChange()} after updating. This method can be called from multiple
     * threads, as described in <a href="
     * {@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * @param uri The URI to query. This can potentially have a record ID if
     *            this is an update request for a specific record.
     * @param values A set of column_name/value pairs to update in the database.
     * @param selection An optional filter to match rows to update.
     * @return the number of rows affected.
     */
    public abstract int update(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable String selection, @Nullable String[] selectionArgs);

    /**
     * Implement this to handle requests to update one or more rows. The
     * implementation should update all rows matching the selection to set the
     * columns according to the provided values map. As a courtesy, call
     * {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver)
     * notifyChange()} after updating. This method can be called from multiple
     * threads, as described in <a href="
     * {@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * @param uri The URI to query. This can potentially have a record ID if
     *            this is an update request for a specific record.
     * @param values A set of column_name/value pairs to update in the database.
     * @param extras A Bundle containing additional information necessary for
     *            the operation. Arguments may include SQL style arguments, such
     *            as {@link ContentResolver#QUERY_ARG_SQL_LIMIT}, but note that
     *            the documentation for each individual provider will indicate
     *            which arguments they support.
     * @return the number of rows affected.
     * @throws IllegalArgumentException if the provider doesn't support one of
     *             the requested Bundle arguments.
     */
    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable Bundle extras) {
        extras = (extras != null) ? extras : Bundle.EMPTY;
        return update(uri, values,
                extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                extras.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS));
    }

    /**
     * Override this to handle requests to open a file blob.
     * The default implementation always throws {@link FileNotFoundException}.
     * This method can be called from multiple threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * <p>This method returns a ParcelFileDescriptor, which is returned directly
     * to the caller.  This way large data (such as images and documents) can be
     * returned without copying the content.
     *
     * <p>The returned ParcelFileDescriptor is owned by the caller, so it is
     * their responsibility to close it when done.  That is, the implementation
     * of this method should create a new ParcelFileDescriptor for each call.
     * <p>
     * If opened with the exclusive "r" or "w" modes, the returned
     * ParcelFileDescriptor can be a pipe or socket pair to enable streaming
     * of data. Opening with the "rw" or "rwt" modes implies a file on disk that
     * supports seeking.
     * <p>
     * If you need to detect when the returned ParcelFileDescriptor has been
     * closed, or if the remote process has crashed or encountered some other
     * error, you can use {@link ParcelFileDescriptor#open(File, int,
     * android.os.Handler, android.os.ParcelFileDescriptor.OnCloseListener)},
     * {@link ParcelFileDescriptor#createReliablePipe()}, or
     * {@link ParcelFileDescriptor#createReliableSocketPair()}.
     * <p>
     * If you need to return a large file that isn't backed by a real file on
     * disk, such as a file on a network share or cloud storage service,
     * consider using
     * {@link StorageManager#openProxyFileDescriptor(int, android.os.ProxyFileDescriptorCallback, android.os.Handler)}
     * which will let you to stream the content on-demand.
     *
     * <p class="note">For use in Intents, you will want to implement {@link #getType}
     * to return the appropriate MIME type for the data returned here with
     * the same URI.  This will allow intent resolution to automatically determine the data MIME
     * type and select the appropriate matching targets as part of its operation.</p>
     *
     * <p class="note">For better interoperability with other applications, it is recommended
     * that for any URIs that can be opened, you also support queries on them
     * containing at least the columns specified by {@link android.provider.OpenableColumns}.
     * You may also want to support other common columns if you have additional meta-data
     * to supply, such as {@link android.provider.MediaStore.MediaColumns#DATE_ADDED}
     * in {@link android.provider.MediaStore.MediaColumns}.</p>
     *
     * @param uri The URI whose file is to be opened.
     * @param mode Access mode for the file.  May be "r" for read-only access,
     * "rw" for read and write access, or "rwt" for read and write access
     * that truncates any existing file.
     *
     * @return Returns a new ParcelFileDescriptor which you can use to access
     * the file.
     *
     * @throws FileNotFoundException Throws FileNotFoundException if there is
     * no file associated with the given URI or the mode is invalid.
     * @throws SecurityException Throws SecurityException if the caller does
     * not have permission to access the file.
     *
     * @see #openAssetFile(Uri, String)
     * @see #openFileHelper(Uri, String)
     * @see #getType(android.net.Uri)
     * @see ParcelFileDescriptor#parseMode(String)
     */
    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        throw new FileNotFoundException("No files supported by provider at "
                + uri);
    }

    /**
     * Override this to handle requests to open a file blob.
     * The default implementation always throws {@link FileNotFoundException}.
     * This method can be called from multiple threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * <p>This method returns a ParcelFileDescriptor, which is returned directly
     * to the caller.  This way large data (such as images and documents) can be
     * returned without copying the content.
     *
     * <p>The returned ParcelFileDescriptor is owned by the caller, so it is
     * their responsibility to close it when done.  That is, the implementation
     * of this method should create a new ParcelFileDescriptor for each call.
     * <p>
     * If opened with the exclusive "r" or "w" modes, the returned
     * ParcelFileDescriptor can be a pipe or socket pair to enable streaming
     * of data. Opening with the "rw" or "rwt" modes implies a file on disk that
     * supports seeking.
     * <p>
     * If you need to detect when the returned ParcelFileDescriptor has been
     * closed, or if the remote process has crashed or encountered some other
     * error, you can use {@link ParcelFileDescriptor#open(File, int,
     * android.os.Handler, android.os.ParcelFileDescriptor.OnCloseListener)},
     * {@link ParcelFileDescriptor#createReliablePipe()}, or
     * {@link ParcelFileDescriptor#createReliableSocketPair()}.
     *
     * <p class="note">For use in Intents, you will want to implement {@link #getType}
     * to return the appropriate MIME type for the data returned here with
     * the same URI.  This will allow intent resolution to automatically determine the data MIME
     * type and select the appropriate matching targets as part of its operation.</p>
     *
     * <p class="note">For better interoperability with other applications, it is recommended
     * that for any URIs that can be opened, you also support queries on them
     * containing at least the columns specified by {@link android.provider.OpenableColumns}.
     * You may also want to support other common columns if you have additional meta-data
     * to supply, such as {@link android.provider.MediaStore.MediaColumns#DATE_ADDED}
     * in {@link android.provider.MediaStore.MediaColumns}.</p>
     *
     * @param uri The URI whose file is to be opened.
     * @param mode Access mode for the file. May be "r" for read-only access,
     *            "w" for write-only access, "rw" for read and write access, or
     *            "rwt" for read and write access that truncates any existing
     *            file.
     * @param signal A signal to cancel the operation in progress, or
     *            {@code null} if none. For example, if you are downloading a
     *            file from the network to service a "rw" mode request, you
     *            should periodically call
     *            {@link CancellationSignal#throwIfCanceled()} to check whether
     *            the client has canceled the request and abort the download.
     *
     * @return Returns a new ParcelFileDescriptor which you can use to access
     * the file.
     *
     * @throws FileNotFoundException Throws FileNotFoundException if there is
     * no file associated with the given URI or the mode is invalid.
     * @throws SecurityException Throws SecurityException if the caller does
     * not have permission to access the file.
     *
     * @see #openAssetFile(Uri, String)
     * @see #openFileHelper(Uri, String)
     * @see #getType(android.net.Uri)
     * @see ParcelFileDescriptor#parseMode(String)
     */
    @Override
    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode,
            @Nullable CancellationSignal signal) throws FileNotFoundException {
        return openFile(uri, mode);
    }

    /**
     * This is like {@link #openFile}, but can be implemented by providers
     * that need to be able to return sub-sections of files, often assets
     * inside of their .apk.
     * This method can be called from multiple threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * <p>If you implement this, your clients must be able to deal with such
     * file slices, either directly with
     * {@link ContentResolver#openAssetFileDescriptor}, or by using the higher-level
     * {@link ContentResolver#openInputStream ContentResolver.openInputStream}
     * or {@link ContentResolver#openOutputStream ContentResolver.openOutputStream}
     * methods.
     * <p>
     * The returned AssetFileDescriptor can be a pipe or socket pair to enable
     * streaming of data.
     *
     * <p class="note">If you are implementing this to return a full file, you
     * should create the AssetFileDescriptor with
     * {@link AssetFileDescriptor#UNKNOWN_LENGTH} to be compatible with
     * applications that cannot handle sub-sections of files.</p>
     *
     * <p class="note">For use in Intents, you will want to implement {@link #getType}
     * to return the appropriate MIME type for the data returned here with
     * the same URI.  This will allow intent resolution to automatically determine the data MIME
     * type and select the appropriate matching targets as part of its operation.</p>
     *
     * <p class="note">For better interoperability with other applications, it is recommended
     * that for any URIs that can be opened, you also support queries on them
     * containing at least the columns specified by {@link android.provider.OpenableColumns}.</p>
     *
     * @param uri The URI whose file is to be opened.
     * @param mode Access mode for the file.  May be "r" for read-only access,
     * "w" for write-only access (erasing whatever data is currently in
     * the file), "wa" for write-only access to append to any existing data,
     * "rw" for read and write access on any existing data, and "rwt" for read
     * and write access that truncates any existing file.
     *
     * @return Returns a new AssetFileDescriptor which you can use to access
     * the file.
     *
     * @throws FileNotFoundException Throws FileNotFoundException if there is
     * no file associated with the given URI or the mode is invalid.
     * @throws SecurityException Throws SecurityException if the caller does
     * not have permission to access the file.
     *
     * @see #openFile(Uri, String)
     * @see #openFileHelper(Uri, String)
     * @see #getType(android.net.Uri)
     */
    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        ParcelFileDescriptor fd = openFile(uri, mode);
        return fd != null ? new AssetFileDescriptor(fd, 0, -1) : null;
    }

    /**
     * This is like {@link #openFile}, but can be implemented by providers
     * that need to be able to return sub-sections of files, often assets
     * inside of their .apk.
     * This method can be called from multiple threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * <p>If you implement this, your clients must be able to deal with such
     * file slices, either directly with
     * {@link ContentResolver#openAssetFileDescriptor}, or by using the higher-level
     * {@link ContentResolver#openInputStream ContentResolver.openInputStream}
     * or {@link ContentResolver#openOutputStream ContentResolver.openOutputStream}
     * methods.
     * <p>
     * The returned AssetFileDescriptor can be a pipe or socket pair to enable
     * streaming of data.
     *
     * <p class="note">If you are implementing this to return a full file, you
     * should create the AssetFileDescriptor with
     * {@link AssetFileDescriptor#UNKNOWN_LENGTH} to be compatible with
     * applications that cannot handle sub-sections of files.</p>
     *
     * <p class="note">For use in Intents, you will want to implement {@link #getType}
     * to return the appropriate MIME type for the data returned here with
     * the same URI.  This will allow intent resolution to automatically determine the data MIME
     * type and select the appropriate matching targets as part of its operation.</p>
     *
     * <p class="note">For better interoperability with other applications, it is recommended
     * that for any URIs that can be opened, you also support queries on them
     * containing at least the columns specified by {@link android.provider.OpenableColumns}.</p>
     *
     * @param uri The URI whose file is to be opened.
     * @param mode Access mode for the file.  May be "r" for read-only access,
     * "w" for write-only access (erasing whatever data is currently in
     * the file), "wa" for write-only access to append to any existing data,
     * "rw" for read and write access on any existing data, and "rwt" for read
     * and write access that truncates any existing file.
     * @param signal A signal to cancel the operation in progress, or
     *            {@code null} if none. For example, if you are downloading a
     *            file from the network to service a "rw" mode request, you
     *            should periodically call
     *            {@link CancellationSignal#throwIfCanceled()} to check whether
     *            the client has canceled the request and abort the download.
     *
     * @return Returns a new AssetFileDescriptor which you can use to access
     * the file.
     *
     * @throws FileNotFoundException Throws FileNotFoundException if there is
     * no file associated with the given URI or the mode is invalid.
     * @throws SecurityException Throws SecurityException if the caller does
     * not have permission to access the file.
     *
     * @see #openFile(Uri, String)
     * @see #openFileHelper(Uri, String)
     * @see #getType(android.net.Uri)
     */
    @Override
    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri uri, @NonNull String mode,
            @Nullable CancellationSignal signal) throws FileNotFoundException {
        return openAssetFile(uri, mode);
    }

    /**
     * Convenience for subclasses that wish to implement {@link #openFile}
     * by looking up a column named "_data" at the given URI.
     *
     * @param uri The URI to be opened.
     * @param mode The file mode.  May be "r" for read-only access,
     * "w" for write-only access (erasing whatever data is currently in
     * the file), "wa" for write-only access to append to any existing data,
     * "rw" for read and write access on any existing data, and "rwt" for read
     * and write access that truncates any existing file.
     *
     * @return Returns a new ParcelFileDescriptor that can be used by the
     * client to access the file.
     */
    protected final @NonNull ParcelFileDescriptor openFileHelper(@NonNull Uri uri,
            @NonNull String mode) throws FileNotFoundException {
        Cursor c = query(uri, new String[]{"_data"}, null, null, null);
        int count = (c != null) ? c.getCount() : 0;
        if (count != 1) {
            // If there is not exactly one result, throw an appropriate
            // exception.
            if (c != null) {
                c.close();
            }
            if (count == 0) {
                throw new FileNotFoundException("No entry for " + uri);
            }
            throw new FileNotFoundException("Multiple items at " + uri);
        }

        c.moveToFirst();
        int i = c.getColumnIndex("_data");
        String path = (i >= 0 ? c.getString(i) : null);
        c.close();
        if (path == null) {
            throw new FileNotFoundException("Column _data not found.");
        }

        int modeBits = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(new File(path), modeBits);
    }

    /**
     * Called by a client to determine the types of data streams that this
     * content provider supports for the given URI.  The default implementation
     * returns {@code null}, meaning no types.  If your content provider stores data
     * of a particular type, return that MIME type if it matches the given
     * mimeTypeFilter.  If it can perform type conversions, return an array
     * of all supported MIME types that match mimeTypeFilter.
     *
     * @param uri The data in the content provider being queried.
     * @param mimeTypeFilter The type of data the client desires.  May be
     * a pattern, such as *&#47;* to retrieve all possible data types.
     * @return Returns {@code null} if there are no possible data streams for the
     * given mimeTypeFilter.  Otherwise returns an array of all available
     * concrete MIME types.
     *
     * @see #getType(Uri)
     * @see #openTypedAssetFile(Uri, String, Bundle)
     * @see ClipDescription#compareMimeTypes(String, String)
     */
    @Override
    public @Nullable String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter) {
        return null;
    }

    /**
     * Called by a client to open a read-only stream containing data of a
     * particular MIME type.  This is like {@link #openAssetFile(Uri, String)},
     * except the file can only be read-only and the content provider may
     * perform data conversions to generate data of the desired type.
     *
     * <p>The default implementation compares the given mimeType against the
     * result of {@link #getType(Uri)} and, if they match, simply calls
     * {@link #openAssetFile(Uri, String)}.
     *
     * <p>See {@link ClipData} for examples of the use and implementation
     * of this method.
     * <p>
     * The returned AssetFileDescriptor can be a pipe or socket pair to enable
     * streaming of data.
     *
     * <p class="note">For better interoperability with other applications, it is recommended
     * that for any URIs that can be opened, you also support queries on them
     * containing at least the columns specified by {@link android.provider.OpenableColumns}.
     * You may also want to support other common columns if you have additional meta-data
     * to supply, such as {@link android.provider.MediaStore.MediaColumns#DATE_ADDED}
     * in {@link android.provider.MediaStore.MediaColumns}.</p>
     *
     * @param uri The data in the content provider being queried.
     * @param mimeTypeFilter The type of data the client desires.  May be
     * a pattern, such as *&#47;*, if the caller does not have specific type
     * requirements; in this case the content provider will pick its best
     * type matching the pattern.
     * @param opts Additional options from the client.  The definitions of
     * these are specific to the content provider being called.
     *
     * @return Returns a new AssetFileDescriptor from which the client can
     * read data of the desired type.
     *
     * @throws FileNotFoundException Throws FileNotFoundException if there is
     * no file associated with the given URI or the mode is invalid.
     * @throws SecurityException Throws SecurityException if the caller does
     * not have permission to access the data.
     * @throws IllegalArgumentException Throws IllegalArgumentException if the
     * content provider does not support the requested MIME type.
     *
     * @see #getStreamTypes(Uri, String)
     * @see #openAssetFile(Uri, String)
     * @see ClipDescription#compareMimeTypes(String, String)
     */
    public @Nullable AssetFileDescriptor openTypedAssetFile(@NonNull Uri uri,
            @NonNull String mimeTypeFilter, @Nullable Bundle opts) throws FileNotFoundException {
        if ("*/*".equals(mimeTypeFilter)) {
            // If they can take anything, the untyped open call is good enough.
            return openAssetFile(uri, "r");
        }
        String baseType = getType(uri);
        if (baseType != null && ClipDescription.compareMimeTypes(baseType, mimeTypeFilter)) {
            // Use old untyped open call if this provider has a type for this
            // URI and it matches the request.
            return openAssetFile(uri, "r");
        }
        throw new FileNotFoundException("Can't open " + uri + " as type " + mimeTypeFilter);
    }


    /**
     * Called by a client to open a read-only stream containing data of a
     * particular MIME type.  This is like {@link #openAssetFile(Uri, String)},
     * except the file can only be read-only and the content provider may
     * perform data conversions to generate data of the desired type.
     *
     * <p>The default implementation compares the given mimeType against the
     * result of {@link #getType(Uri)} and, if they match, simply calls
     * {@link #openAssetFile(Uri, String)}.
     *
     * <p>See {@link ClipData} for examples of the use and implementation
     * of this method.
     * <p>
     * The returned AssetFileDescriptor can be a pipe or socket pair to enable
     * streaming of data.
     *
     * <p class="note">For better interoperability with other applications, it is recommended
     * that for any URIs that can be opened, you also support queries on them
     * containing at least the columns specified by {@link android.provider.OpenableColumns}.
     * You may also want to support other common columns if you have additional meta-data
     * to supply, such as {@link android.provider.MediaStore.MediaColumns#DATE_ADDED}
     * in {@link android.provider.MediaStore.MediaColumns}.</p>
     *
     * @param uri The data in the content provider being queried.
     * @param mimeTypeFilter The type of data the client desires.  May be
     * a pattern, such as *&#47;*, if the caller does not have specific type
     * requirements; in this case the content provider will pick its best
     * type matching the pattern.
     * @param opts Additional options from the client.  The definitions of
     * these are specific to the content provider being called.
     * @param signal A signal to cancel the operation in progress, or
     *            {@code null} if none. For example, if you are downloading a
     *            file from the network to service a "rw" mode request, you
     *            should periodically call
     *            {@link CancellationSignal#throwIfCanceled()} to check whether
     *            the client has canceled the request and abort the download.
     *
     * @return Returns a new AssetFileDescriptor from which the client can
     * read data of the desired type.
     *
     * @throws FileNotFoundException Throws FileNotFoundException if there is
     * no file associated with the given URI or the mode is invalid.
     * @throws SecurityException Throws SecurityException if the caller does
     * not have permission to access the data.
     * @throws IllegalArgumentException Throws IllegalArgumentException if the
     * content provider does not support the requested MIME type.
     *
     * @see #getStreamTypes(Uri, String)
     * @see #openAssetFile(Uri, String)
     * @see ClipDescription#compareMimeTypes(String, String)
     */
    @Override
    public @Nullable AssetFileDescriptor openTypedAssetFile(@NonNull Uri uri,
            @NonNull String mimeTypeFilter, @Nullable Bundle opts,
            @Nullable CancellationSignal signal) throws FileNotFoundException {
        return openTypedAssetFile(uri, mimeTypeFilter, opts);
    }

    /**
     * Interface to write a stream of data to a pipe.  Use with
     * {@link ContentProvider#openPipeHelper}.
     */
    public interface PipeDataWriter<T> {
        /**
         * Called from a background thread to stream data out to a pipe.
         * Note that the pipe is blocking, so this thread can block on
         * writes for an arbitrary amount of time if the client is slow
         * at reading.
         *
         * @param output The pipe where data should be written.  This will be
         * closed for you upon returning from this function.
         * @param uri The URI whose data is to be written.
         * @param mimeType The desired type of data to be written.
         * @param opts Options supplied by caller.
         * @param args Your own custom arguments.
         */
        public void writeDataToPipe(@NonNull ParcelFileDescriptor output, @NonNull Uri uri,
                @NonNull String mimeType, @Nullable Bundle opts, @Nullable T args);
    }

    /**
     * A helper function for implementing {@link #openTypedAssetFile}, for
     * creating a data pipe and background thread allowing you to stream
     * generated data back to the client.  This function returns a new
     * ParcelFileDescriptor that should be returned to the caller (the caller
     * is responsible for closing it).
     *
     * @param uri The URI whose data is to be written.
     * @param mimeType The desired type of data to be written.
     * @param opts Options supplied by caller.
     * @param args Your own custom arguments.
     * @param func Interface implementing the function that will actually
     * stream the data.
     * @return Returns a new ParcelFileDescriptor holding the read side of
     * the pipe.  This should be returned to the caller for reading; the caller
     * is responsible for closing it when done.
     */
    public @NonNull <T> ParcelFileDescriptor openPipeHelper(final @NonNull Uri uri,
            final @NonNull String mimeType, final @Nullable Bundle opts, final @Nullable T args,
            final @NonNull PipeDataWriter<T> func) throws FileNotFoundException {
        try {
            final ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();

            AsyncTask<Object, Object, Object> task = new AsyncTask<Object, Object, Object>() {
                @Override
                protected Object doInBackground(Object... params) {
                    func.writeDataToPipe(fds[1], uri, mimeType, opts, args);
                    try {
                        fds[1].close();
                    } catch (IOException e) {
                        Log.w(TAG, "Failure closing pipe", e);
                    }
                    return null;
                }
            };
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Object[])null);

            return fds[0];
        } catch (IOException e) {
            throw new FileNotFoundException("failure making pipe");
        }
    }

    /**
     * Returns true if this instance is a temporary content provider.
     * @return true if this instance is a temporary content provider
     */
    protected boolean isTemporary() {
        return false;
    }

    /**
     * Returns the Binder object for this provider.
     *
     * @return the Binder object for this provider
     * @hide
     */
    @UnsupportedAppUsage
    public IContentProvider getIContentProvider() {
        return mTransport;
    }

    /**
     * Like {@link #attachInfo(Context, android.content.pm.ProviderInfo)}, but for use
     * when directly instantiating the provider for testing.
     * @hide
     */
    @UnsupportedAppUsage
    public void attachInfoForTesting(Context context, ProviderInfo info) {
        attachInfo(context, info, true);
    }

    /**
     * After being instantiated, this is called to tell the content provider
     * about itself.
     *
     * @param context The context this provider is running in
     * @param info Registered information about this content provider
     */
    public void attachInfo(Context context, ProviderInfo info) {
        attachInfo(context, info, false);
    }

    private void attachInfo(Context context, ProviderInfo info, boolean testing) {
        mNoPerms = testing;
        mCallingPackage = new ThreadLocal<>();

        /*
         * Only allow it to be set once, so after the content service gives
         * this to us clients can't change it.
         */
        if (mContext == null) {
            mContext = context;
            if (context != null && mTransport != null) {
                mTransport.mAppOpsManager = (AppOpsManager) context.getSystemService(
                        Context.APP_OPS_SERVICE);
            }
            mMyUid = Process.myUid();
            if (info != null) {
                setReadPermission(info.readPermission);
                setWritePermission(info.writePermission);
                setPathPermissions(info.pathPermissions);
                mExported = info.exported;
                mSingleUser = (info.flags & ProviderInfo.FLAG_SINGLE_USER) != 0;
                setAuthorities(info.authority);
            }
            if (Build.IS_DEBUGGABLE) {
                setTransportLoggingEnabled(Log.isLoggable(getClass().getSimpleName(),
                        Log.VERBOSE));
            }
            ContentProvider.this.onCreate();
        }
    }

    /**
     * Override this to handle requests to perform a batch of operations, or the
     * default implementation will iterate over the operations and call
     * {@link ContentProviderOperation#apply} on each of them.
     * If all calls to {@link ContentProviderOperation#apply} succeed
     * then a {@link ContentProviderResult} array with as many
     * elements as there were operations will be returned.  If any of the calls
     * fail, it is up to the implementation how many of the others take effect.
     * This method can be called from multiple threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html#Threads">Processes
     * and Threads</a>.
     *
     * @param operations the operations to apply
     * @return the results of the applications
     * @throws OperationApplicationException thrown if any operation fails.
     * @see ContentProviderOperation#apply
     */
    @Override
    public @NonNull ContentProviderResult[] applyBatch(@NonNull String authority,
            @NonNull ArrayList<ContentProviderOperation> operations)
                    throws OperationApplicationException {
        return applyBatch(operations);
    }

    public @NonNull ContentProviderResult[] applyBatch(
            @NonNull ArrayList<ContentProviderOperation> operations)
                    throws OperationApplicationException {
        final int numOperations = operations.size();
        final ContentProviderResult[] results = new ContentProviderResult[numOperations];
        for (int i = 0; i < numOperations; i++) {
            results[i] = operations.get(i).apply(this, results, i);
        }
        return results;
    }

    /**
     * Call a provider-defined method.  This can be used to implement
     * interfaces that are cheaper and/or unnatural for a table-like
     * model.
     *
     * <p class="note"><strong>WARNING:</strong> The framework does no permission checking
     * on this entry into the content provider besides the basic ability for the application
     * to get access to the provider at all.  For example, it has no idea whether the call
     * being executed may read or write data in the provider, so can't enforce those
     * individual permissions.  Any implementation of this method <strong>must</strong>
     * do its own permission checks on incoming calls to make sure they are allowed.</p>
     *
     * @param method method name to call.  Opaque to framework, but should not be {@code null}.
     * @param arg provider-defined String argument.  May be {@code null}.
     * @param extras provider-defined Bundle argument.  May be {@code null}.
     * @return provider-defined return value.  May be {@code null}, which is also
     *   the default for providers which don't implement any call methods.
     */
    @Override
    public @Nullable Bundle call(@NonNull String authority, @NonNull String method,
            @Nullable String arg, @Nullable Bundle extras) {
        return call(method, arg, extras);
    }

    public @Nullable Bundle call(@NonNull String method, @Nullable String arg,
            @Nullable Bundle extras) {
        return null;
    }

    /**
     * Implement this to shut down the ContentProvider instance. You can then
     * invoke this method in unit tests.
     *
     * <p>
     * Android normally handles ContentProvider startup and shutdown
     * automatically. You do not need to start up or shut down a
     * ContentProvider. When you invoke a test method on a ContentProvider,
     * however, a ContentProvider instance is started and keeps running after
     * the test finishes, even if a succeeding test instantiates another
     * ContentProvider. A conflict develops because the two instances are
     * usually running against the same underlying data source (for example, an
     * sqlite database).
     * </p>
     * <p>
     * Implementing shutDown() avoids this conflict by providing a way to
     * terminate the ContentProvider. This method can also prevent memory leaks
     * from multiple instantiations of the ContentProvider, and it can ensure
     * unit test isolation by allowing you to completely clean up the test
     * fixture before moving on to the next test.
     * </p>
     */
    public void shutdown() {
        Log.w(TAG, "implement ContentProvider shutdown() to make sure all database " +
                "connections are gracefully shutdown");
    }

    /**
     * Print the Provider's state into the given stream.  This gets invoked if
     * you run "adb shell dumpsys activity provider &lt;provider_component_name&gt;".
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param writer The PrintWriter to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("nothing to dump");
    }

    private void validateIncomingAuthority(String authority) throws SecurityException {
        if (!matchesOurAuthorities(getAuthorityWithoutUserId(authority))) {
            String message = "The authority " + authority + " does not match the one of the "
                    + "contentProvider: ";
            if (mAuthority != null) {
                message += mAuthority;
            } else {
                message += Arrays.toString(mAuthorities);
            }
            throw new SecurityException(message);
        }
    }

    /** @hide */
    @VisibleForTesting
    public Uri validateIncomingUri(Uri uri) throws SecurityException {
        String auth = uri.getAuthority();
        if (!mSingleUser) {
            int userId = getUserIdFromAuthority(auth, UserHandle.USER_CURRENT);
            if (userId != UserHandle.USER_CURRENT && userId != mContext.getUserId()) {
                throw new SecurityException("trying to query a ContentProvider in user "
                        + mContext.getUserId() + " with a uri belonging to user " + userId);
            }
        }
        validateIncomingAuthority(auth);

        // Normalize the path by removing any empty path segments, which can be
        // a source of security issues.
        final String encodedPath = uri.getEncodedPath();
        if (encodedPath != null && encodedPath.indexOf("//") != -1) {
            final Uri normalized = uri.buildUpon()
                    .encodedPath(encodedPath.replaceAll("//+", "/")).build();
            Log.w(TAG, "Normalized " + uri + " to " + normalized
                    + " to avoid possible security issues");
            return normalized;
        } else {
            return uri;
        }
    }

    /** @hide */
    private Uri maybeGetUriWithoutUserId(Uri uri) {
        if (mSingleUser) {
            return uri;
        }
        return getUriWithoutUserId(uri);
    }

    /** @hide */
    public static int getUserIdFromAuthority(String auth, int defaultUserId) {
        if (auth == null) return defaultUserId;
        int end = auth.lastIndexOf('@');
        if (end == -1) return defaultUserId;
        String userIdString = auth.substring(0, end);
        try {
            return Integer.parseInt(userIdString);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Error parsing userId.", e);
            return UserHandle.USER_NULL;
        }
    }

    /** @hide */
    public static int getUserIdFromAuthority(String auth) {
        return getUserIdFromAuthority(auth, UserHandle.USER_CURRENT);
    }

    /** @hide */
    public static int getUserIdFromUri(Uri uri, int defaultUserId) {
        if (uri == null) return defaultUserId;
        return getUserIdFromAuthority(uri.getAuthority(), defaultUserId);
    }

    /** @hide */
    public static int getUserIdFromUri(Uri uri) {
        return getUserIdFromUri(uri, UserHandle.USER_CURRENT);
    }

    /**
     * Returns the user associated with the given URI.
     *
     * @hide
     */
    @TestApi
    public @NonNull static UserHandle getUserHandleFromUri(@NonNull Uri uri) {
        return UserHandle.of(getUserIdFromUri(uri, Process.myUserHandle().getIdentifier()));
    }

    /**
     * Removes userId part from authority string. Expects format:
     * userId@some.authority
     * If there is no userId in the authority, it symply returns the argument
     * @hide
     */
    public static String getAuthorityWithoutUserId(String auth) {
        if (auth == null) return null;
        int end = auth.lastIndexOf('@');
        return auth.substring(end+1);
    }

    /** @hide */
    public static Uri getUriWithoutUserId(Uri uri) {
        if (uri == null) return null;
        Uri.Builder builder = uri.buildUpon();
        builder.authority(getAuthorityWithoutUserId(uri.getAuthority()));
        return builder.build();
    }

    /** @hide */
    public static boolean uriHasUserId(Uri uri) {
        if (uri == null) return false;
        return !TextUtils.isEmpty(uri.getUserInfo());
    }

    /** @hide */
    @UnsupportedAppUsage
    public static Uri maybeAddUserId(Uri uri, int userId) {
        if (uri == null) return null;
        if (userId != UserHandle.USER_CURRENT
                && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            if (!uriHasUserId(uri)) {
                //We don't add the user Id if there's already one
                Uri.Builder builder = uri.buildUpon();
                builder.encodedAuthority("" + userId + "@" + uri.getEncodedAuthority());
                return builder.build();
            }
        }
        return uri;
    }
}
