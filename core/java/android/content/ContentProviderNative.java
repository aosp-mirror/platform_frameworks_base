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
import android.database.BulkCursorDescriptor;
import android.database.BulkCursorToCursorAdaptor;
import android.database.Cursor;
import android.database.CursorToBulkCursorAdaptor;
import android.database.DatabaseUtils;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.RemoteException;

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
    @UnsupportedAppUsage
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

                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
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

                    Bundle queryArgs = data.readBundle();
                    IContentObserver observer = IContentObserver.Stub.asInterface(
                            data.readStrongBinder());
                    ICancellationSignal cancellationSignal = ICancellationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    Cursor cursor = query(attributionSource, url, projection, queryArgs,
                            cancellationSignal);
                    if (cursor != null) {
                        CursorToBulkCursorAdaptor adaptor = null;

                        try {
                            adaptor = new CursorToBulkCursorAdaptor(cursor, observer,
                                    getProviderName());
                            cursor = null;

                            BulkCursorDescriptor d = adaptor.getBulkCursorDescriptor();
                            adaptor = null;

                            reply.writeNoException();
                            reply.writeInt(1);
                            d.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                        } finally {
                            // Close cursor if an exception was thrown while constructing the adaptor.
                            if (adaptor != null) {
                                adaptor.close();
                            }
                            if (cursor != null) {
                                cursor.close();
                            }
                        }
                    } else {
                        reply.writeNoException();
                        reply.writeInt(0);
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

                case GET_TYPE_ASYNC_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    RemoteCallback callback = RemoteCallback.CREATOR.createFromParcel(data);
                    getTypeAsync(url, callback);
                    return true;
                }

                case INSERT_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    ContentValues values = ContentValues.CREATOR.createFromParcel(data);
                    Bundle extras = data.readBundle();

                    Uri out = insert(attributionSource, url, values, extras);
                    reply.writeNoException();
                    Uri.writeToParcel(reply, out);
                    return true;
                }

                case BULK_INSERT_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    ContentValues[] values = data.createTypedArray(ContentValues.CREATOR);

                    int count = bulkInsert(attributionSource, url, values);
                    reply.writeNoException();
                    reply.writeInt(count);
                    return true;
                }

                case APPLY_BATCH_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    String authority = data.readString();
                    final int numOperations = data.readInt();
                    final ArrayList<ContentProviderOperation> operations =
                            new ArrayList<>(numOperations);
                    for (int i = 0; i < numOperations; i++) {
                        operations.add(i, ContentProviderOperation.CREATOR.createFromParcel(data));
                    }
                    final ContentProviderResult[] results = applyBatch(attributionSource,
                            authority, operations);
                    reply.writeNoException();
                    reply.writeTypedArray(results, 0);
                    return true;
                }

                case DELETE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    Bundle extras = data.readBundle();

                    int count = delete(attributionSource, url, extras);

                    reply.writeNoException();
                    reply.writeInt(count);
                    return true;
                }

                case UPDATE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    ContentValues values = ContentValues.CREATOR.createFromParcel(data);
                    Bundle extras = data.readBundle();

                    int count = update(attributionSource, url, values, extras);

                    reply.writeNoException();
                    reply.writeInt(count);
                    return true;
                }

                case OPEN_FILE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mode = data.readString();
                    ICancellationSignal signal = ICancellationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    ParcelFileDescriptor fd;
                    fd = openFile(attributionSource, url, mode, signal);
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
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mode = data.readString();
                    ICancellationSignal signal = ICancellationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    AssetFileDescriptor fd;
                    fd = openAssetFile(attributionSource, url, mode, signal);
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

                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    String authority = data.readString();
                    String method = data.readString();
                    String stringArg = data.readString();
                    Bundle extras = data.readBundle();

                    Bundle responseBundle = call(attributionSource, authority, method,
                            stringArg, extras);

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
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mimeType = data.readString();
                    Bundle opts = data.readBundle();
                    ICancellationSignal signal = ICancellationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    AssetFileDescriptor fd;
                    fd = openTypedAssetFile(attributionSource, url, mimeType, opts, signal);
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

                    ICancellationSignal cancellationSignal = createCancellationSignal();
                    reply.writeNoException();
                    reply.writeStrongBinder(cancellationSignal.asBinder());
                    return true;
                }

                case CANONICALIZE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);

                    Uri out = canonicalize(attributionSource, url);
                    reply.writeNoException();
                    Uri.writeToParcel(reply, out);
                    return true;
                }

                case CANONICALIZE_ASYNC_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri uri = Uri.CREATOR.createFromParcel(data);
                    RemoteCallback callback = RemoteCallback.CREATOR.createFromParcel(data);
                    canonicalizeAsync(attributionSource, uri, callback);
                    return true;
                }

                case UNCANONICALIZE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);

                    Uri out = uncanonicalize(attributionSource, url);
                    reply.writeNoException();
                    Uri.writeToParcel(reply, out);
                    return true;
                }

                case UNCANONICALIZE_ASYNC_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri uri = Uri.CREATOR.createFromParcel(data);
                    RemoteCallback callback = RemoteCallback.CREATOR.createFromParcel(data);
                    uncanonicalizeAsync(attributionSource, uri, callback);
                    return true;
                }

                case REFRESH_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    Bundle extras = data.readBundle();
                    ICancellationSignal signal = ICancellationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    boolean out = refresh(attributionSource, url, extras, signal);
                    reply.writeNoException();
                    reply.writeInt(out ? 0 : -1);
                    return true;
                }

                case CHECK_URI_PERMISSION_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri uri = Uri.CREATOR.createFromParcel(data);
                    int uid = data.readInt();
                    int modeFlags = data.readInt();

                    int out = checkUriPermission(attributionSource, uri, uid, modeFlags);
                    reply.writeNoException();
                    reply.writeInt(out);
                    return true;
                }
            }
        } catch (Exception e) {
            DatabaseUtils.writeExceptionToParcel(reply, e);
            return true;
        }

        return super.onTransact(code, data, reply, flags);
    }

    @Override
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

    @Override
    public IBinder asBinder()
    {
        return mRemote;
    }

    @Override
    public Cursor query(@NonNull AttributionSource attributionSource, Uri url,
            @Nullable String[] projection, @Nullable Bundle queryArgs,
            @Nullable ICancellationSignal cancellationSignal)
            throws RemoteException {
        BulkCursorToCursorAdaptor adaptor = new BulkCursorToCursorAdaptor();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            int length = 0;
            if (projection != null) {
                length = projection.length;
            }
            data.writeInt(length);
            for (int i = 0; i < length; i++) {
                data.writeString(projection[i]);
            }
            data.writeBundle(queryArgs);
            data.writeStrongBinder(adaptor.getObserver().asBinder());
            data.writeStrongBinder(
                    cancellationSignal != null ? cancellationSignal.asBinder() : null);

            mRemote.transact(IContentProvider.QUERY_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);

            if (reply.readInt() != 0) {
                BulkCursorDescriptor d = BulkCursorDescriptor.CREATOR.createFromParcel(reply);
                Binder.copyAllowBlocking(mRemote, (d.cursor != null) ? d.cursor.asBinder() : null);
                adaptor.initialize(d);
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

    @Override
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

    @Override
    /* oneway */ public void getTypeAsync(Uri uri, RemoteCallback callback) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            uri.writeToParcel(data, 0);
            callback.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.GET_TYPE_ASYNC_TRANSACTION, data, null,
                    IBinder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    @Override
    public Uri insert(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues values, Bundle extras) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            values.writeToParcel(data, 0);
            data.writeBundle(extras);

            mRemote.transact(IContentProvider.INSERT_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            Uri out = Uri.CREATOR.createFromParcel(reply);
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public int bulkInsert(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues[] values) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
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

    @Override
    public ContentProviderResult[] applyBatch(@NonNull AttributionSource attributionSource,
            String authority, ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);
            attributionSource.writeToParcel(data, 0);
            data.writeString(authority);
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

    @Override
    public int delete(@NonNull AttributionSource attributionSource, Uri url, Bundle extras)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeBundle(extras);

            mRemote.transact(IContentProvider.DELETE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            int count = reply.readInt();
            return count;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public int update(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues values, Bundle extras) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            values.writeToParcel(data, 0);
            data.writeBundle(extras);

            mRemote.transact(IContentProvider.UPDATE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            int count = reply.readInt();
            return count;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull AttributionSource attributionSource, Uri url,
            String mode, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeString(mode);
            data.writeStrongBinder(signal != null ? signal.asBinder() : null);

            mRemote.transact(IContentProvider.OPEN_FILE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionWithFileNotFoundExceptionFromParcel(reply);
            int has = reply.readInt();
            ParcelFileDescriptor fd = has != 0 ? ParcelFileDescriptor.CREATOR
                    .createFromParcel(reply) : null;
            return fd;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public AssetFileDescriptor openAssetFile(@NonNull AttributionSource attributionSource,
            Uri url, String mode, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeString(mode);
            data.writeStrongBinder(signal != null ? signal.asBinder() : null);

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

    @Override
    public Bundle call(@NonNull AttributionSource attributionSource, String authority,
            String method, String request, Bundle extras) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            data.writeString(authority);
            data.writeString(method);
            data.writeString(request);
            data.writeBundle(extras);

            mRemote.transact(IContentProvider.CALL_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            Bundle bundle = reply.readBundle();
            return bundle;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
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

    @Override
    public AssetFileDescriptor openTypedAssetFile(@NonNull AttributionSource attributionSource,
            Uri url, String mimeType, Bundle opts, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeString(mimeType);
            data.writeBundle(opts);
            data.writeStrongBinder(signal != null ? signal.asBinder() : null);

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

    @Override
    public ICancellationSignal createCancellationSignal() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            mRemote.transact(IContentProvider.CREATE_CANCELATION_SIGNAL_TRANSACTION,
                    data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            ICancellationSignal cancellationSignal = ICancellationSignal.Stub.asInterface(
                    reply.readStrongBinder());
            return cancellationSignal;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public Uri canonicalize(@NonNull AttributionSource attributionSource, Uri url)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.CANONICALIZE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            Uri out = Uri.CREATOR.createFromParcel(reply);
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    /* oneway */ public void canonicalizeAsync(@NonNull AttributionSource attributionSource,
            Uri uri, RemoteCallback callback) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            uri.writeToParcel(data, 0);
            callback.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.CANONICALIZE_ASYNC_TRANSACTION, data, null,
                    Binder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    @Override
    public Uri uncanonicalize(@NonNull AttributionSource attributionSource, Uri url)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.UNCANONICALIZE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            Uri out = Uri.CREATOR.createFromParcel(reply);
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    /* oneway */ public void uncanonicalizeAsync(@NonNull AttributionSource attributionSource,
            Uri uri, RemoteCallback callback) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            uri.writeToParcel(data, 0);
            callback.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.UNCANONICALIZE_ASYNC_TRANSACTION, data, null,
                    Binder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    @Override
    public boolean refresh(@NonNull AttributionSource attributionSource, Uri url, Bundle extras,
            ICancellationSignal signal) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeBundle(extras);
            data.writeStrongBinder(signal != null ? signal.asBinder() : null);

            mRemote.transact(IContentProvider.REFRESH_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            int success = reply.readInt();
            return (success == 0);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public int checkUriPermission(@NonNull AttributionSource attributionSource, Uri url, int uid,
            int modeFlags) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeInt(uid);
            data.writeInt(modeFlags);

            mRemote.transact(IContentProvider.CHECK_URI_PERMISSION_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @UnsupportedAppUsage
    private IBinder mRemote;
}
