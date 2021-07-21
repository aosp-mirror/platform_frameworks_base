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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * The ipc interface to talk to a content provider.
 * @hide
 */
public interface IContentProvider extends IInterface {
    Cursor query(@NonNull AttributionSource attributionSource, Uri url,
            @Nullable String[] projection,
            @Nullable Bundle queryArgs, @Nullable ICancellationSignal cancellationSignal)
            throws RemoteException;
    String getType(Uri url) throws RemoteException;

    /**
     * A oneway version of getType. The functionality is exactly the same, except that the
     * call returns immediately, and the resulting type is returned when available via
     * a binder callback.
     */
    void getTypeAsync(Uri uri, RemoteCallback callback) throws RemoteException;

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "ContentProviderClient#insert(android.net.Uri, android.content.ContentValues)} "
            + "instead")
    default Uri insert(String callingPkg, Uri url, ContentValues initialValues)
            throws RemoteException {
        return insert(new AttributionSource(Binder.getCallingUid(), callingPkg, null),
                url, initialValues, null);
    }
    Uri insert(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues initialValues, Bundle extras) throws RemoteException;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "ContentProviderClient#bulkInsert(android.net.Uri, android.content.ContentValues[])"
            + "} instead")
    default int bulkInsert(String callingPkg, Uri url, ContentValues[] initialValues)
            throws RemoteException {
        return bulkInsert(new AttributionSource(Binder.getCallingUid(), callingPkg, null),
                url, initialValues);
    }
    int bulkInsert(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues[] initialValues) throws RemoteException;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "ContentProviderClient#delete(android.net.Uri, java.lang.String, java.lang"
            + ".String[])} instead")
    default int delete(String callingPkg, Uri url, String selection, String[] selectionArgs)
            throws RemoteException {
        return delete(new AttributionSource(Binder.getCallingUid(), callingPkg, null),
                url, ContentResolver.createSqlQueryBundle(selection, selectionArgs));
    }
    int delete(@NonNull AttributionSource attributionSource, Uri url, Bundle extras)
            throws RemoteException;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "ContentProviderClient#update(android.net.Uri, android.content.ContentValues, java"
            + ".lang.String, java.lang.String[])} instead")
    default int update(String callingPkg, Uri url, ContentValues values, String selection,
            String[] selectionArgs) throws RemoteException {
        return update(new AttributionSource(Binder.getCallingUid(), callingPkg, null),
                url, values, ContentResolver.createSqlQueryBundle(selection, selectionArgs));
    }
    int update(@NonNull AttributionSource attributionSource, Uri url, ContentValues values,
            Bundle extras) throws RemoteException;

    ParcelFileDescriptor openFile(@NonNull AttributionSource attributionSource,
            Uri url, String mode, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException;

    AssetFileDescriptor openAssetFile(@NonNull AttributionSource attributionSource,
            Uri url, String mode, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException;

    ContentProviderResult[] applyBatch(@NonNull AttributionSource attributionSource,
            String authority, ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException;

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "ContentProviderClient#call(java.lang.String, java.lang.String, android.os.Bundle)} "
            + "instead")
    public default Bundle call(String callingPkg, String method,
            @Nullable String arg, @Nullable Bundle extras) throws RemoteException {
        return call(new AttributionSource(Binder.getCallingUid(), callingPkg, null),
                "unknown", method, arg, extras);
    }

    Bundle call(@NonNull AttributionSource attributionSource, String authority,
            String method, @Nullable String arg, @Nullable Bundle extras) throws RemoteException;

    int checkUriPermission(@NonNull AttributionSource attributionSource, Uri uri,
            int uid, int modeFlags) throws RemoteException;

    ICancellationSignal createCancellationSignal() throws RemoteException;

    Uri canonicalize(@NonNull AttributionSource attributionSource, Uri uri)
            throws RemoteException;

    /**
     * A oneway version of canonicalize. The functionality is exactly the same, except that the
     * call returns immediately, and the resulting type is returned when available via
     * a binder callback.
     */
    void canonicalizeAsync(@NonNull AttributionSource attributionSource, Uri uri,
            RemoteCallback callback) throws RemoteException;

    Uri uncanonicalize(@NonNull AttributionSource attributionSource, Uri uri)
            throws RemoteException;

    /**
     * A oneway version of uncanonicalize. The functionality is exactly the same, except that the
     * call returns immediately, and the resulting type is returned when available via
     * a binder callback.
     */
    void uncanonicalizeAsync(@NonNull AttributionSource attributionSource, Uri uri,
            RemoteCallback callback) throws RemoteException;

    public boolean refresh(@NonNull AttributionSource attributionSource, Uri url,
            @Nullable Bundle extras, ICancellationSignal cancellationSignal) throws RemoteException;

    // Data interchange.
    public String[] getStreamTypes(Uri url, String mimeTypeFilter) throws RemoteException;

    public AssetFileDescriptor openTypedAssetFile(@NonNull AttributionSource attributionSource,
            Uri url, String mimeType, Bundle opts, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException;

    /* IPC constants */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    static final String descriptor = "android.content.IContentProvider";

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    static final int QUERY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    static final int GET_TYPE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int INSERT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int DELETE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int UPDATE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 9;
    static final int BULK_INSERT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 12;
    static final int OPEN_FILE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 13;
    static final int OPEN_ASSET_FILE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 14;
    static final int APPLY_BATCH_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 19;
    static final int CALL_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 20;
    static final int GET_STREAM_TYPES_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 21;
    static final int OPEN_TYPED_ASSET_FILE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 22;
    static final int CREATE_CANCELATION_SIGNAL_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 23;
    static final int CANONICALIZE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 24;
    static final int UNCANONICALIZE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 25;
    static final int REFRESH_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 26;
    static final int CHECK_URI_PERMISSION_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 27;
    int GET_TYPE_ASYNC_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 28;
    int CANONICALIZE_ASYNC_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 29;
    int UNCANONICALIZE_ASYNC_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 30;
}
