/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.layoutlib.bridge;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.IContentProvider;
import android.content.OperationApplicationException;
import android.content.res.AssetFileDescriptor;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.IBulkCursor;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * A mock content resolver for the LayoutLib Bridge.
 * <p/>
 * It won't serve any actual data but it's good enough for all
 * the widgets which expect to have a content resolver available via
 * {@link BridgeContext#getContentResolver()}.
 */
public class BridgeContentResolver extends ContentResolver {

    private BridgeContentProvider mProvider = null;

    public static final class BridgeContentProvider implements IContentProvider {

        public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> arg0)
                throws RemoteException, OperationApplicationException {
            // TODO Auto-generated method stub
            return null;
        }

        public int bulkInsert(Uri arg0, ContentValues[] arg1) throws RemoteException {
            // TODO Auto-generated method stub
            return 0;
        }

        public IBulkCursor bulkQuery(Uri arg0, String[] arg1, String arg2, String[] arg3,
                String arg4, IContentObserver arg5, CursorWindow arg6) throws RemoteException {
            // TODO Auto-generated method stub
            return null;
        }

        public Bundle call(String arg0, String arg1, Bundle arg2) throws RemoteException {
            // TODO Auto-generated method stub
            return null;
        }

        public int delete(Uri arg0, String arg1, String[] arg2) throws RemoteException {
            // TODO Auto-generated method stub
            return 0;
        }

        public String getType(Uri arg0) throws RemoteException {
            // TODO Auto-generated method stub
            return null;
        }

        public Uri insert(Uri arg0, ContentValues arg1) throws RemoteException {
            // TODO Auto-generated method stub
            return null;
        }

        public AssetFileDescriptor openAssetFile(Uri arg0, String arg1) throws RemoteException,
                FileNotFoundException {
            // TODO Auto-generated method stub
            return null;
        }

        public ParcelFileDescriptor openFile(Uri arg0, String arg1) throws RemoteException,
                FileNotFoundException {
            // TODO Auto-generated method stub
            return null;
        }

        public Cursor query(Uri arg0, String[] arg1, String arg2, String[] arg3, String arg4)
                throws RemoteException {
            // TODO Auto-generated method stub
            return null;
        }

        public int update(Uri arg0, ContentValues arg1, String arg2, String[] arg3)
                throws RemoteException {
            // TODO Auto-generated method stub
            return 0;
        }

        public IBinder asBinder() {
            // TODO Auto-generated method stub
            return null;
        }

    }

    public BridgeContentResolver(Context context) {
        super(context);
    }

    @Override
    public IContentProvider acquireProvider(Context c, String name) {
        if (mProvider == null) {
            mProvider = new BridgeContentProvider();
        }

        return mProvider;
    }

    @Override
    public IContentProvider acquireExistingProvider(Context c, String name) {
        if (mProvider == null) {
            mProvider = new BridgeContentProvider();
        }

        return mProvider;
    }

    @Override
    public boolean releaseProvider(IContentProvider icp) {
        // ignore
        return false;
    }

    /**
     * Stub for the layoutlib bridge content resolver.
     */
    @Override
    public void registerContentObserver(Uri uri, boolean notifyForDescendents,
            ContentObserver observer) {
        // pass
    }

    /**
     * Stub for the layoutlib bridge content resolver.
     */
    @Override
    public void unregisterContentObserver(ContentObserver observer) {
        // pass
    }

    /**
     * Stub for the layoutlib bridge content resolver.
     */
    @Override
    public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
        // pass
    }

    /**
     * Stub for the layoutlib bridge content resolver.
     */
    @Override
    public void startSync(Uri uri, Bundle extras) {
        // pass
    }

    /**
     * Stub for the layoutlib bridge content resolver.
     */
    @Override
    public void cancelSync(Uri uri) {
        // pass
    }
}
