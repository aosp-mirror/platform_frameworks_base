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

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.IBulkCursor;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * The ipc interface to talk to a content provider.
 * @hide
 */
public interface IContentProvider extends IInterface {
    /**
     * @hide - hide this because return type IBulkCursor and parameter
     * IContentObserver are system private classes.
     */
    public IBulkCursor bulkQuery(Uri url, String[] projection,
            String selection, String[] selectionArgs, String sortOrder, IContentObserver observer,
            CursorWindow window) throws RemoteException;
    public Cursor query(Uri url, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) throws RemoteException;
    public String getType(Uri url) throws RemoteException;
    public Uri insert(Uri url, ContentValues initialValues)
            throws RemoteException;
    public int bulkInsert(Uri url, ContentValues[] initialValues) throws RemoteException;
    public int delete(Uri url, String selection, String[] selectionArgs)
            throws RemoteException;
    public int update(Uri url, ContentValues values, String selection,
            String[] selectionArgs) throws RemoteException;
    public ParcelFileDescriptor openFile(Uri url, String mode)
            throws RemoteException, FileNotFoundException;
    public AssetFileDescriptor openAssetFile(Uri url, String mode)
            throws RemoteException, FileNotFoundException;
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException;
    /**
     * @hide -- until interface has proven itself
     *
     * Call an provider-defined method.  This can be used to implement
     * interfaces that are cheaper than using a Cursor.
     *
     * @param method Method name to call.  Opaque to framework.
     * @param request Nullable String argument passed to method.
     * @param args Nullable Bundle argument passed to method.
     */
    public Bundle call(String method, String request, Bundle args) throws RemoteException;

    /* IPC constants */
    static final String descriptor = "android.content.IContentProvider";

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
}
