/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * Interface representing calls that can be made to {@link ContentProvider}
 * instances.
 * <p>
 * These methods have been extracted into a general interface so that APIs can
 * be flexible in accepting either a {@link ContentProvider}, a
 * {@link ContentResolver}, or a {@link ContentProviderClient}.
 *
 * @hide
 */
public interface ContentInterface {
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable Bundle queryArgs, @Nullable CancellationSignal cancellationSignal)
            throws RemoteException;

    public @Nullable String getType(@NonNull Uri uri) throws RemoteException;

    public @Nullable String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter)
            throws RemoteException;

    public @Nullable Uri canonicalize(@NonNull Uri uri) throws RemoteException;

    public @Nullable Uri uncanonicalize(@NonNull Uri uri) throws RemoteException;

    public boolean refresh(@NonNull Uri uri, @Nullable Bundle args,
            @Nullable CancellationSignal cancellationSignal) throws RemoteException;

    public @Nullable Uri insert(@NonNull Uri uri, @Nullable ContentValues initialValues)
            throws RemoteException;

    public int bulkInsert(@NonNull Uri uri, @NonNull ContentValues[] initialValues)
            throws RemoteException;

    public int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs) throws RemoteException;

    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) throws RemoteException;

    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException;

    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri uri, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException;

    public @Nullable AssetFileDescriptor openTypedAssetFile(@NonNull Uri uri,
            @NonNull String mimeTypeFilter, @Nullable Bundle opts,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException;

    public @NonNull ContentProviderResult[] applyBatch(@NonNull String authority,
            @NonNull ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException;

    public @Nullable Bundle call(@NonNull String authority, @NonNull String method,
            @Nullable String arg, @Nullable Bundle extras) throws RemoteException;
}
