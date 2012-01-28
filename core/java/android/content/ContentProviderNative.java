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
import android.database.CursorToBulkCursorAdaptor;
import android.database.DatabaseUtils;
import android.database.IBulkCursor;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * {@hide}
 */
abstract public class ContentProviderNative extends Binder implements IContentProvider {
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

    /**
     * Gets the name of the content provider.
     * Should probably be part of the {@link IContentProvider} interface.
     * @return The content provider name.
     */
    public abstract String getProviderName();

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            switch (code) {
                case QUERY_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);

                    Uri url = Uri.CREATOR.createFromParcel(data);

                    // String[] projection
                    int num = data.readInt();
                    String[] projection = null;
                    if (num > 0) {
                        projection = new String[num];
                        for (int i = 0; i < num; i++) {
                            projection[i] = data.readString();
                        }
                    }

                    // String selection, String[] selectionArgs...
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
                    IContentObserver observer = IContentObserver.Stub.asInterface(
                            data.readStrongBinder());
                    ICancelationSignal cancelationSignal = ICancelationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    Cursor cursor = query(url, projection, selection, selectionArgs, sortOrder,
                            cancelationSignal);
                    if (cursor != null) {
                        CursorToBulkCursorAdaptor adaptor = new CursorToBulkCursorAdaptor(
                                cursor, observer, getProviderName());
                        final IBinder binder = adaptor.asBinder();
                        final int count = adaptor.count();
                        final int index = BulkCursorToCursorAdaptor.findRowIdColumnIndex(
                                adaptor.getColumnNames());
                        final boolean wantsAllOnMoveCalls = adaptor.getWantsAllOnMoveCalls();

                        reply.writeNoException();
                        reply.writeStrongBinder(binder);
                        reply.writeInt(count);
                        reply.writeInt(index);
                        reply.writeInt(wantsAllOnMoveCalls ? 1 : 0);
                    } else {
                        reply.writeNoException();
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

                case APPLY_BATCH_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    final int numOperations = data.readInt();
                    final ArrayList<ContentProviderOperation> operations =
                            new ArrayList<ContentProviderOperation>(numOperations);
                    for (int i = 0; i < numOperations; i++) {
                        operations.add(i, ContentProviderOperation.CREATOR.createFromParcel(data));
                    }
                    final ContentProviderResult[] results = applyBatch(operations);
                    reply.writeNoException();
                    reply.writeTypedArray(results, 0);
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

                case CALL_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);

                    String method = data.readString();
                    String stringArg = data.readString();
                    Bundle args = data.readBundle();

                    Bundle responseBundle = call(method, stringArg, args);

                    reply.writeNoException();
                    reply.writeBundle(responseBundle);
                    return true;
                }

                case GET_STREAM_TYPES_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mimeTypeFilter = data.readString();
                    String[] types = getStreamTypes(url, mimeTypeFilter);
                    reply.writeNoException();
                    reply.writeStringArray(types);

                    return true;
                }

                case OPEN_TYPED_ASSET_FILE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mimeType = data.readString();
                    Bundle opts = data.readBundle();

                    AssetFileDescriptor fd;
                    fd = openTypedAssetFile(url, mimeType, opts);
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

                case CREATE_CANCELATION_SIGNAL_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);

                    ICancelationSignal cancelationSignal = createCancelationSignal();
                    reply.writeNoException();
                    reply.writeStrongBinder(cancelationSignal.asBinder());
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

    public Cursor query(Uri url, String[] projection, String selection,
            String[] selectionArgs, String sortOrder, ICancelationSignal cancelationSignal)
                    throws RemoteException {
        BulkCursorToCursorAdaptor adaptor = new BulkCursorToCursorAdaptor();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
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
            data.writeStrongBinder(adaptor.getObserver().asBinder());
            data.writeStrongBinder(cancelationSignal != null ? cancelationSignal.asBinder() : null);

            mRemote.transact(IContentProvider.QUERY_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);

            IBulkCursor bulkCursor = BulkCursorNative.asInterface(reply.readStrongBinder());
            if (bulkCursor != null) {
                int rowCount = reply.readInt();
                int idColumnPosition = reply.readInt();
                boolean wantsAllOnMoveCalls = reply.readInt() != 0;
                adaptor.initialize(bulkCursor, rowCount, idColumnPosition, wantsAllOnMoveCalls);
            } else {
                adaptor.close();
                adaptor = null;
            }
            return adaptor;
        } catch (RemoteException ex) {
            adaptor.close();
            throw ex;
        } catch (RuntimeException ex) {
            adaptor.close();
            throw ex;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public String getType(Uri url) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            url.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.GET_TYPE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            String out = reply.readString();
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public Uri insert(Uri url, ContentValues values) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            url.writeToParcel(data, 0);
            values.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.INSERT_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            Uri out = Uri.CREATOR.createFromParcel(reply);
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public int bulkInsert(Uri url, ContentValues[] values) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            url.writeToParcel(data, 0);
            data.writeTypedArray(values, 0);

            mRemote.transact(IContentProvider.BULK_INSERT_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            int count = reply.readInt();
            return count;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);
            data.writeInt(operations.size());
            for (ContentProviderOperation operation : operations) {
                operation.writeToParcel(data, 0);
            }
            mRemote.transact(IContentProvider.APPLY_BATCH_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionWithOperationApplicationExceptionFromParcel(reply);
            final ContentProviderResult[] results =
                    reply.createTypedArray(ContentProviderResult.CREATOR);
            return results;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public int delete(Uri url, String selection, String[] selectionArgs)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            url.writeToParcel(data, 0);
            data.writeString(selection);
            data.writeStringArray(selectionArgs);

            mRemote.transact(IContentProvider.DELETE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            int count = reply.readInt();
            return count;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public int update(Uri url, ContentValues values, String selection,
            String[] selectionArgs) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            url.writeToParcel(data, 0);
            values.writeToParcel(data, 0);
            data.writeString(selection);
            data.writeStringArray(selectionArgs);

            mRemote.transact(IContentProvider.UPDATE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            int count = reply.readInt();
            return count;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public ParcelFileDescriptor openFile(Uri url, String mode)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            url.writeToParcel(data, 0);
            data.writeString(mode);

            mRemote.transact(IContentProvider.OPEN_FILE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionWithFileNotFoundExceptionFromParcel(reply);
            int has = reply.readInt();
            ParcelFileDescriptor fd = has != 0 ? reply.readFileDescriptor() : null;
            return fd;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public AssetFileDescriptor openAssetFile(Uri url, String mode)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            url.writeToParcel(data, 0);
            data.writeString(mode);

            mRemote.transact(IContentProvider.OPEN_ASSET_FILE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionWithFileNotFoundExceptionFromParcel(reply);
            int has = reply.readInt();
            AssetFileDescriptor fd = has != 0
                    ? AssetFileDescriptor.CREATOR.createFromParcel(reply) : null;
            return fd;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public Bundle call(String method, String request, Bundle args)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            data.writeString(method);
            data.writeString(request);
            data.writeBundle(args);

            mRemote.transact(IContentProvider.CALL_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            Bundle bundle = reply.readBundle();
            return bundle;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public String[] getStreamTypes(Uri url, String mimeTypeFilter) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            url.writeToParcel(data, 0);
            data.writeString(mimeTypeFilter);

            mRemote.transact(IContentProvider.GET_STREAM_TYPES_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            String[] out = reply.createStringArray();
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public AssetFileDescriptor openTypedAssetFile(Uri url, String mimeType, Bundle opts)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            url.writeToParcel(data, 0);
            data.writeString(mimeType);
            data.writeBundle(opts);

            mRemote.transact(IContentProvider.OPEN_TYPED_ASSET_FILE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionWithFileNotFoundExceptionFromParcel(reply);
            int has = reply.readInt();
            AssetFileDescriptor fd = has != 0
                    ? AssetFileDescriptor.CREATOR.createFromParcel(reply) : null;
            return fd;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public ICancelationSignal createCancelationSignal() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            mRemote.transact(IContentProvider.CREATE_CANCELATION_SIGNAL_TRANSACTION,
                    data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            ICancelationSignal cancelationSignal = ICancelationSignal.Stub.asInterface(
                    reply.readStrongBinder());
            return cancelationSignal;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private IBinder mRemote;
}
