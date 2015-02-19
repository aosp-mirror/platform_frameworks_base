/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.layoutlib.bridge.android;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.IContentProvider;
import android.content.OperationApplicationException;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * Mock implementation of {@link IContentProvider}.
 *
 * TODO: never return null when the method is not supposed to. Return fake data instead.
 */
public final class BridgeContentProvider implements IContentProvider {
    @Override
    public ContentProviderResult[] applyBatch(String callingPackage,
            ArrayList<ContentProviderOperation> arg0)
            throws RemoteException, OperationApplicationException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int bulkInsert(String callingPackage, Uri arg0, ContentValues[] arg1)
            throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public Bundle call(String callingPackage, String arg0, String arg1, Bundle arg2)
            throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int delete(String callingPackage, Uri arg0, String arg1, String[] arg2)
            throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public String getType(Uri arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Uri insert(String callingPackage, Uri arg0, ContentValues arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public AssetFileDescriptor openAssetFile(
            String callingPackage, Uri arg0, String arg1, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(
            String callingPackage, Uri arg0, String arg1, ICancellationSignal signal, IBinder token)
            throws RemoteException, FileNotFoundException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Cursor query(String callingPackage, Uri arg0, String[] arg1, String arg2, String[] arg3,
            String arg4, ICancellationSignal arg5) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int update(String callingPackage, Uri arg0, ContentValues arg1, String arg2,
            String[] arg3) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public IBinder asBinder() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String[] getStreamTypes(Uri arg0, String arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(String callingPackage, Uri arg0, String arg1,
            Bundle arg2, ICancellationSignal signal) throws RemoteException, FileNotFoundException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ICancellationSignal createCancellationSignal() throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }


    @Override
    public Uri canonicalize(String callingPkg, Uri uri) throws RemoteException {
        return null;
    }

    @Override
    public Uri uncanonicalize(String callingPkg, Uri uri) throws RemoteException {
        return null;
    }
}
