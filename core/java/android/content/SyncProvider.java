// Copyright 2007 The Android Open Source Project
package android.content;

import android.database.Cursor;
import android.net.Uri;

/**
 * ContentProvider that tracks the sync data and overall sync
 * history on the device.
 * 
 * @hide
 */
public class SyncProvider extends ContentProvider {
    public SyncProvider() {
    }

    private SyncStorageEngine mSyncStorageEngine;

    @Override
    public boolean onCreate() {
        mSyncStorageEngine = SyncStorageEngine.getSingleton();
        return true;
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn,
                        String selection, String[] selectionArgs, String sort) {
        return mSyncStorageEngine.query(url, projectionIn, selection, selectionArgs, sort);
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        return mSyncStorageEngine.insert(true /* the caller is the provider */,
                url, initialValues);
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        return mSyncStorageEngine.delete(true /* the caller is the provider */,
                url, where, whereArgs);
    }

    @Override
    public int update(Uri url, ContentValues initialValues, String where, String[] whereArgs) {
        return mSyncStorageEngine.update(true /* the caller is the provider */, 
                url, initialValues, where, whereArgs);
    }

    @Override
    public String getType(Uri url) {
        return mSyncStorageEngine.getType(url);
    }
}
