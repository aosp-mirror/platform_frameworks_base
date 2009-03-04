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
import android.os.RemoteException;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;

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
    public ISyncAdapter getSyncAdapter() throws RemoteException;

    /* IPC constants */
    static final String descriptor = "android.content.IContentProvider";

    static final int QUERY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    static final int GET_TYPE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int INSERT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int DELETE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int UPDATE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 9;
    static final int GET_SYNC_ADAPTER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 10;
    static final int BULK_INSERT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 12;
    static final int OPEN_FILE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 13;
    static final int OPEN_ASSET_FILE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 14;
}
