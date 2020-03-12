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

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
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
    public Cursor query(String callingPkg, @Nullable String attributionTag, Uri url,
            @Nullable String[] projection,
            @Nullable Bundle queryArgs, @Nullable ICancellationSignal cancellationSignal)
            throws RemoteException;
    public String getType(Uri url) throws RemoteException;

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
    public default Uri insert(String callingPkg, Uri url, ContentValues initialValues)
            throws RemoteException {
        return insert(callingPkg, null, url, initialValues, null);
    }
    public Uri insert(String callingPkg, String attributionTag, Uri url,
            ContentValues initialValues, Bundle extras) throws RemoteException;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "ContentProviderClient#bulkInsert(android.net.Uri, android.content.ContentValues[])"
            + "} instead")
    public default int bulkInsert(String callingPkg, Uri url, ContentValues[] initialValues)
            throws RemoteException {
        return bulkInsert(callingPkg, null, url, initialValues);
    }
    public int bulkInsert(String callingPkg, String attributionTag, Uri url,
            ContentValues[] initialValues) throws RemoteException;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "ContentProviderClient#delete(android.net.Uri, java.lang.String, java.lang"
            + ".String[])} instead")
    public default int delete(String callingPkg, Uri url, String selection, String[] selectionArgs)
            throws RemoteException {
        return delete(callingPkg, null, url,
                ContentResolver.createSqlQueryBundle(selection, selectionArgs));
    }
    public int delete(String callingPkg, String attributionTag, Uri url, Bundle extras)
            throws RemoteException;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "ContentProviderClient#update(android.net.Uri, android.content.ContentValues, java"
            + ".lang.String, java.lang.String[])} instead")
    public default int update(String callingPkg, Uri url, ContentValues values, String selection,
            String[] selectionArgs) throws RemoteException {
        return update(callingPkg, null, url, values,
                ContentResolver.createSqlQueryBundle(selection, selectionArgs));
    }
    public int update(String callingPkg, String attributionTag, Uri url, ContentValues values,
            Bundle extras) throws RemoteException;

    public ParcelFileDescriptor openFile(String callingPkg, @Nullable String attributionTag,
            Uri url, String mode, ICancellationSignal signal, IBinder callerToken)
            throws RemoteException, FileNotFoundException;

    public AssetFileDescriptor openAssetFile(String callingPkg, @Nullable String attributionTag,
            Uri url, String mode, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException;

    public ContentProviderResult[] applyBatch(String callingPkg, @Nullable String attributionTag,
            String authority, ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException;

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "ContentProviderClient#call(java.lang.String, java.lang.String, android.os.Bundle)} "
            + "instead")
    public default Bundle call(String callingPkg, String method,
            @Nullable String arg, @Nullable Bundle extras) throws RemoteException {
        return call(callingPkg, null, "unknown", method, arg, extras);
    }

    public Bundle call(String callingPkg, @Nullable String attributionTag, String authority,
            String method, @Nullable String arg, @Nullable Bundle extras) throws RemoteException;

    public int checkUriPermission(String callingPkg, @Nullable String attributionTag, Uri uri,
            int uid, int modeFlags) throws RemoteException;

    public ICancellationSignal createCancellationSignal() throws RemoteException;

    public Uri canonicalize(String callingPkg, @Nullable String attributionTag, Uri uri)
            throws RemoteException;

    /**
     * A oneway version of canonicalize. The functionality is exactly the same, except that the
     * call returns immediately, and the resulting type is returned when available via
     * a binder callback.
     */
    void canonicalizeAsync(String callingPkg, @Nullable String attributionTag, Uri uri,
            RemoteCallback callback) throws RemoteException;

    public Uri uncanonicalize(String callingPkg, @Nullable String attributionTag, Uri uri)
            throws RemoteException;

    public boolean refresh(String callingPkg, @Nullable String attributionTag, Uri url,
            @Nullable Bundle extras, ICancellationSignal cancellationSignal) throws RemoteException;

    // Data interchange.
    public String[] getStreamTypes(Uri url, String mimeTypeFilter) throws RemoteException;

    public AssetFileDescriptor openTypedAssetFile(String callingPkg,
            @Nullable String attributionTag, Uri url, String mimeType, Bundle opts,
            ICancellationSignal signal)
            throws RemoteException, FileNotFoundException;

    /* IPC constants */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    static final String descriptor = "android.content.IContentProvider";

    @UnsupportedAppUsage
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
}
