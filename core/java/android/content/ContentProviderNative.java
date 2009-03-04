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
import android.database.BulkCursorNative;
import android.database.BulkCursorToCursorAdaptor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.DatabaseUtils;
import android.database.IBulkCursor;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.FileNotFoundException;

/**
 * {@hide}
 */
abstract public class ContentProviderNative extends Binder implements IContentProvider {
    private static final String TAG = "ContentProvider";

    public ContentProviderNative()
    {
        attachInterface(this, descriptor);
    }

    /**
     * Cast a Binder object into a content resolver interface, generating
     * a proxy if needed.
     */
    static public IContentProvider asInterface(IBinder obj)
    {
        if (obj == null) {
            return null;
        }
        IContentProvider in =
            (IContentProvider)obj.queryLocalInterface(descriptor);
        if (in != null) {
            return in;
        }

        return new ContentProviderProxy(obj);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            switch (code) {
                case QUERY_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    int num = data.readInt();
                    String[] projection = null;
                    if (num > 0) {
                        projection = new String[num];
                        for (int i = 0; i < num; i++) {
                            projection[i] = data.readString();
                        }
                    }
                    String selection = data.readString();
                    num = data.readInt();
                    String[] selectionArgs = null;
                    if (num > 0) {
                        selectionArgs = new String[num];
                        for (int i = 0; i < num; i++) {
                            selectionArgs[i] = data.readString();
                        }
                    }
                    String sortOrder = data.readString();
                    IContentObserver observer = IContentObserver.Stub.
                        asInterface(data.readStrongBinder());
                    CursorWindow window = CursorWindow.CREATOR.createFromParcel(data);

                    IBulkCursor bulkCursor = bulkQuery(url, projection, selection,
                            selectionArgs, sortOrder, observer, window);
                    reply.writeNoException();
                    if (bulkCursor != null) {
                        reply.writeStrongBinder(bulkCursor.asBinder());
                    } else {
                        reply.writeStrongBinder(null);
                    }
                    return true;
                }

                case GET_TYPE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String type = getType(url);
                    reply.writeNoException();
                    reply.writeString(type);

                    return true;
                }

                case INSERT_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    ContentValues values = ContentValues.CREATOR.createFromParcel(data);

                    Uri out = insert(url, values);
                    reply.writeNoException();
                    Uri.writeToParcel(reply, out);
                    return true;
                }

                case BULK_INSERT_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    ContentValues[] values = data.createTypedArray(ContentValues.CREATOR);

                    int count = bulkInsert(url, values);
                    reply.writeNoException();
                    reply.writeInt(count);
                    return true;
                }

                case DELETE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String selection = data.readString();
                    String[] selectionArgs = data.readStringArray();

                    int count = delete(url, selection, selectionArgs);

                    reply.writeNoException();
                    reply.writeInt(count);
                    return true;
                }

                case UPDATE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    ContentValues values = ContentValues.CREATOR.createFromParcel(data);
                    String selection = data.readString();
                    String[] selectionArgs = data.readStringArray();

                    int count = update(url, values, selection, selectionArgs);

                    reply.writeNoException();
                    reply.writeInt(count);
                    return true;
                }

                case OPEN_FILE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mode = data.readString();

                    ParcelFileDescriptor fd;
                    fd = openFile(url, mode);
                    reply.writeNoException();
                    if (fd != null) {
                        reply.writeInt(1);
                        fd.writeToParcel(reply,
                                Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }

                case OPEN_ASSET_FILE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mode = data.readString();

                    AssetFileDescriptor fd;
                    fd = openAssetFile(url, mode);
                    reply.writeNoException();
                    if (fd != null) {
                        reply.writeInt(1);
                        fd.writeToParcel(reply,
                                Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }

                case GET_SYNC_ADAPTER_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    ISyncAdapter sa = getSyncAdapter();
                    reply.writeNoException();
                    reply.writeStrongBinder(sa != null ? sa.asBinder() : null);
                    return true;
                }
            }
        } catch (Exception e) {
            DatabaseUtils.writeExceptionToParcel(reply, e);
            return true;
        }

        return super.onTransact(code, data, reply, flags);
    }

    public IBinder asBinder()
    {
        return this;
    }
}


final class ContentProviderProxy implements IContentProvider
{
    public ContentProviderProxy(IBinder remote)
    {
        mRemote = remote;
    }

    public IBinder asBinder()
    {
        return mRemote;
    }

    public IBulkCursor bulkQuery(Uri url, String[] projection,
            String selection, String[] selectionArgs, String sortOrder, IContentObserver observer,
            CursorWindow window) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        data.writeInterfaceToken(IContentProvider.descriptor);

        url.writeToParcel(data, 0);
        int length = 0;
        if (projection != null) {
            length = projection.length;
        }
        data.writeInt(length);
        for (int i = 0; i < length; i++) {
            data.writeString(projection[i]);
        }
        data.writeString(selection);
        if (selectionArgs != null) {
            length = selectionArgs.length;
        } else {
            length = 0;
        }
        data.writeInt(length);
        for (int i = 0; i < length; i++) {
            data.writeString(selectionArgs[i]);
        }
        data.writeString(sortOrder);
        data.writeStrongBinder(observer.asBinder());
        window.writeToParcel(data, 0);

        mRemote.transact(IContentProvider.QUERY_TRANSACTION, data, reply, 0);

        DatabaseUtils.readExceptionFromParcel(reply);

        IBulkCursor bulkCursor = null;
        IBinder bulkCursorBinder = reply.readStrongBinder();
        if (bulkCursorBinder != null) {
            bulkCursor = BulkCursorNative.asInterface(bulkCursorBinder);
        }
        
        data.recycle();
        reply.recycle();
        
        return bulkCursor;
    }

    public Cursor query(Uri url, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) throws RemoteException {
        //TODO make a pool of windows so we can reuse memory dealers
        CursorWindow window = new CursorWindow(false /* window will be used remotely */);
        BulkCursorToCursorAdaptor adaptor = new BulkCursorToCursorAdaptor();
        IBulkCursor bulkCursor = bulkQuery(url, projection, selection, selectionArgs, sortOrder,
                adaptor.getObserver(), window);
         
        if (bulkCursor == null) {
            return null;
        }
        adaptor.set(bulkCursor);
        return adaptor;
    }

    public String getType(Uri url) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        data.writeInterfaceToken(IContentProvider.descriptor);

        url.writeToParcel(data, 0);

        mRemote.transact(IContentProvider.GET_TYPE_TRANSACTION, data, reply, 0);

        DatabaseUtils.readExceptionFromParcel(reply);
        String out = reply.readString();

        data.recycle();
        reply.recycle();

        return out;
    }

    public Uri insert(Uri url, ContentValues values) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        data.writeInterfaceToken(IContentProvider.descriptor);

        url.writeToParcel(data, 0);
        values.writeToParcel(data, 0);

        mRemote.transact(IContentProvider.INSERT_TRANSACTION, data, reply, 0);

        DatabaseUtils.readExceptionFromParcel(reply);
        Uri out = Uri.CREATOR.createFromParcel(reply);

        data.recycle();
        reply.recycle();

        return out;
    }

    public int bulkInsert(Uri url, ContentValues[] values) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        data.writeInterfaceToken(IContentProvider.descriptor);

        url.writeToParcel(data, 0);
        data.writeTypedArray(values, 0);

        mRemote.transact(IContentProvider.BULK_INSERT_TRANSACTION, data, reply, 0);

        DatabaseUtils.readExceptionFromParcel(reply);
        int count = reply.readInt();

        data.recycle();
        reply.recycle();

        return count;
    }

    public int delete(Uri url, String selection, String[] selectionArgs)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        data.writeInterfaceToken(IContentProvider.descriptor);

        url.writeToParcel(data, 0);
        data.writeString(selection);
        data.writeStringArray(selectionArgs);

        mRemote.transact(IContentProvider.DELETE_TRANSACTION, data, reply, 0);

        DatabaseUtils.readExceptionFromParcel(reply);
        int count = reply.readInt();

        data.recycle();
        reply.recycle();

        return count;
    }

    public int update(Uri url, ContentValues values, String selection,
            String[] selectionArgs) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        data.writeInterfaceToken(IContentProvider.descriptor);

        url.writeToParcel(data, 0);
        values.writeToParcel(data, 0);
        data.writeString(selection);
        data.writeStringArray(selectionArgs);

        mRemote.transact(IContentProvider.UPDATE_TRANSACTION, data, reply, 0);

        DatabaseUtils.readExceptionFromParcel(reply);
        int count = reply.readInt();

        data.recycle();
        reply.recycle();

        return count;
    }

    public ParcelFileDescriptor openFile(Uri url, String mode)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        data.writeInterfaceToken(IContentProvider.descriptor);

        url.writeToParcel(data, 0);
        data.writeString(mode);

        mRemote.transact(IContentProvider.OPEN_FILE_TRANSACTION, data, reply, 0);

        DatabaseUtils.readExceptionWithFileNotFoundExceptionFromParcel(reply);
        int has = reply.readInt();
        ParcelFileDescriptor fd = has != 0 ? reply.readFileDescriptor() : null;

        data.recycle();
        reply.recycle();

        return fd;
    }

    public AssetFileDescriptor openAssetFile(Uri url, String mode)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        data.writeInterfaceToken(IContentProvider.descriptor);

        url.writeToParcel(data, 0);
        data.writeString(mode);

        mRemote.transact(IContentProvider.OPEN_ASSET_FILE_TRANSACTION, data, reply, 0);

        DatabaseUtils.readExceptionWithFileNotFoundExceptionFromParcel(reply);
        int has = reply.readInt();
        AssetFileDescriptor fd = has != 0
                ? AssetFileDescriptor.CREATOR.createFromParcel(reply) : null;

        data.recycle();
        reply.recycle();

        return fd;
    }

    public ISyncAdapter getSyncAdapter() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        data.writeInterfaceToken(IContentProvider.descriptor);

        mRemote.transact(IContentProvider.GET_SYNC_ADAPTER_TRANSACTION, data, reply, 0);

        DatabaseUtils.readExceptionFromParcel(reply);
        ISyncAdapter syncAdapter = ISyncAdapter.Stub.asInterface(reply.readStrongBinder());

        data.recycle();
        reply.recycle();

        return syncAdapter;
    }

    private IBinder mRemote;
}

