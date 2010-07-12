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

package android.media;

import android.content.Context;
import android.content.ContentValues;
import android.content.IContentProvider;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.MediaStore.MtpObjects;
import android.util.Log;

/**
 * {@hide}
 */
public class MtpDatabase {

    private static final String TAG = "MtpDatabase";

    private final IContentProvider mMediaProvider;
    private final String mVolumeName;
    private final Uri mObjectsUri;

    private static final String[] ID_PROJECTION = new String[] {
            MtpObjects.ObjectColumns._ID, // 0
    };
    private static final String[] PATH_SIZE_PROJECTION = new String[] {
            MtpObjects.ObjectColumns._ID, // 0
            MtpObjects.ObjectColumns.DATA, // 1
            MtpObjects.ObjectColumns.SIZE, // 2
    };
    private static final String[] OBJECT_INFO_PROJECTION = new String[] {
            MtpObjects.ObjectColumns._ID, // 0
            MtpObjects.ObjectColumns.DATA, // 1
            MtpObjects.ObjectColumns.FORMAT, // 2
            MtpObjects.ObjectColumns.PARENT, // 3
            MtpObjects.ObjectColumns.SIZE, // 4
            MtpObjects.ObjectColumns.DATE_MODIFIED, // 5
    };
    private static final String ID_WHERE = MtpObjects.ObjectColumns._ID + "=?";
    private static final String PATH_WHERE = MtpObjects.ObjectColumns.DATA + "=?";
    private static final String PARENT_WHERE = MtpObjects.ObjectColumns.PARENT + "=?";
    private static final String PARENT_FORMAT_WHERE = PARENT_WHERE + " AND "
                                            + MtpObjects.ObjectColumns.FORMAT + "=?";

    private final MediaScanner mMediaScanner;

    static {
        System.loadLibrary("media_jni");
    }

    public MtpDatabase(Context context, String volumeName) {
        native_setup();

        mMediaProvider = context.getContentResolver().acquireProvider("media");
        mVolumeName = volumeName;
        mObjectsUri = MtpObjects.getContentUri(volumeName);
        mMediaScanner = new MediaScanner(context);
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    private int beginSendObject(String path, int format, int parent,
                         int storage, long size, long modified) {
        ContentValues values = new ContentValues();
        values.put(MtpObjects.ObjectColumns.DATA, path);
        values.put(MtpObjects.ObjectColumns.FORMAT, format);
        values.put(MtpObjects.ObjectColumns.PARENT, parent);
        // storage is ignored for now
        values.put(MtpObjects.ObjectColumns.SIZE, size);
        values.put(MtpObjects.ObjectColumns.DATE_MODIFIED, modified);

        try {
            Uri uri = mMediaProvider.insert(mObjectsUri, values);
            if (uri != null) {
                return Integer.parseInt(uri.getPathSegments().get(2));
            } else {
                return -1;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in beginSendObject", e);
            return -1;
        }
    }

    private void endSendObject(String path, int handle, int format, boolean succeeded) {
        if (succeeded) {
            Uri uri = mMediaScanner.scanMtpFile(path, mVolumeName, handle, format);
        } else {
            deleteFile(handle);
        }
    }

    private int[] getObjectList(int storageID, int format, int parent) {
        // we can ignore storageID until we support multiple storages
        Log.d(TAG, "getObjectList parent: " + parent);
        Cursor c = null;
        try {
            if (format != 0) {
                c = mMediaProvider.query(mObjectsUri, ID_PROJECTION,
                            PARENT_FORMAT_WHERE,
                            new String[] { Integer.toString(parent), Integer.toString(format) },
                             null);
            } else {
                c = mMediaProvider.query(mObjectsUri, ID_PROJECTION,
                            PARENT_WHERE, new String[] { Integer.toString(parent) }, null);
            }
            if (c == null) {
                Log.d(TAG, "null cursor");
                return null;
            }
            int count = c.getCount();
            if (count > 0) {
                int[] result = new int[count];
                for (int i = 0; i < count; i++) {
                    c.moveToNext();
                    result[i] = c.getInt(0);
                }
                Log.d(TAG, "returning " + result);
                return result;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getObjectList", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    private int getObjectProperty(int handle, int property,
                            long[] outIntValue, char[] outStringValue) {
        Log.d(TAG, "getObjectProperty: " + property);
        return 0;
    }

    private boolean getObjectInfo(int handle, int[] outStorageFormatParent,
                        char[] outName, long[] outSizeModified) {
        Log.d(TAG, "getObjectInfo: " + handle);
        Cursor c = null;
        try {
            c = mMediaProvider.query(mObjectsUri, OBJECT_INFO_PROJECTION,
                            ID_WHERE, new String[] {  Integer.toString(handle) }, null);
            if (c != null && c.moveToNext()) {
                outStorageFormatParent[0] = 0x00010001;
                outStorageFormatParent[1] = c.getInt(2);
                outStorageFormatParent[2] = c.getInt(3);

                // extract name from path
                String path = c.getString(1);
                int lastSlash = path.lastIndexOf('/');
                int start = (lastSlash >= 0 ? lastSlash + 1 : 0);
                int end = path.length();
                if (end - start > 255) {
                    end = start + 255;
                }
                path.getChars(start, end, outName, 0);
                outName[end - start] = 0;

                outSizeModified[0] = c.getLong(4);
                outSizeModified[1] = c.getLong(5);
                return true;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getObjectProperty", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return false;
    }

    private boolean getObjectFilePath(int handle, char[] outFilePath, long[] outFileLength) {
        Log.d(TAG, "getObjectFilePath: " + handle);
        Cursor c = null;
        try {
            c = mMediaProvider.query(mObjectsUri, PATH_SIZE_PROJECTION,
                            ID_WHERE, new String[] {  Integer.toString(handle) }, null);
            if (c != null && c.moveToNext()) {
                String path = c.getString(1);
                path.getChars(0, path.length(), outFilePath, 0);
                outFilePath[path.length()] = 0;
                outFileLength[0] = c.getLong(2);
                return true;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getObjectFilePath", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return false;
    }

    private boolean deleteFile(int handle) {
        Log.d(TAG, "deleteFile: " + handle);
        Uri uri = MtpObjects.getContentUri(mVolumeName, handle);
        try {
            return (mMediaProvider.delete(uri, null, null) == 1);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in deleteFile", e);
            return false;
        }
    }

    // used by the JNI code
    private int mNativeContext;

    private native final void native_setup();
    private native final void native_finalize();
}
