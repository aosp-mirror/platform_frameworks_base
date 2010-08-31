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
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.MediaColumns;
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

    // FIXME - this should be passed in via the constructor
    private final int mStorageID = 0x00010001;

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
    protected void finalize() throws Throwable {
        try {
            native_finalize();
        } finally {
            super.finalize();
        }
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
            // handle abstract playlists separately
            // they do not exist in the file system so don't use the media scanner here
            if (format == MtpConstants.FORMAT_ABSTRACT_AV_PLAYLIST) {
                // Strip Windows Media Player file extension
                if (path.endsWith(".pla")) {
                    path = path.substring(0, path.length() - 4);
                }

                // extract name from path
                String name = path;
                int lastSlash = name.lastIndexOf('/');
                if (lastSlash >= 0) {
                    name = name.substring(lastSlash + 1);
                }

                ContentValues values = new ContentValues(1);
                values.put(Audio.Playlists.DATA, path);
                values.put(Audio.Playlists.NAME, name);
                values.put(MediaColumns.MEDIA_SCANNER_NEW_OBJECT_ID, handle);
                try {
                    Uri uri = mMediaProvider.insert(Audio.Playlists.EXTERNAL_CONTENT_URI, values);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException in endSendObject", e);
                }
            } else {
                Uri uri = mMediaScanner.scanMtpFile(path, mVolumeName, handle, format);
            }
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

    private int getNumObjects(int storageID, int format, int parent) {
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
            if (c != null) {
                return c.getCount();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getNumObjects", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return -1;
    }

    private int[] getSupportedPlaybackFormats() {
        return new int[] {
            MtpConstants.FORMAT_ASSOCIATION,
            MtpConstants.FORMAT_MP3,
            MtpConstants.FORMAT_MPEG,
            MtpConstants.FORMAT_EXIF_JPEG,
            MtpConstants.FORMAT_TIFF_EP,
            MtpConstants.FORMAT_GIF,
            MtpConstants.FORMAT_JFIF,
            MtpConstants.FORMAT_PNG,
            MtpConstants.FORMAT_TIFF,
            MtpConstants.FORMAT_WMA,
            MtpConstants.FORMAT_OGG,
            MtpConstants.FORMAT_AAC,
            MtpConstants.FORMAT_MP4_CONTAINER,
            MtpConstants.FORMAT_MP2,
            MtpConstants.FORMAT_3GP_CONTAINER,
            MtpConstants.FORMAT_ABSTRACT_AV_PLAYLIST,
            MtpConstants.FORMAT_WPL_PLAYLIST,
            MtpConstants.FORMAT_M3U_PLAYLIST,
            MtpConstants.FORMAT_PLS_PLAYLIST,
        };
    }

    private int[] getSupportedCaptureFormats() {
        // no capture formats yet
        return null;
    }

    private int[] getSupportedObjectProperties(int handle) {
        return new int[] {
            MtpConstants.PROPERTY_STORAGE_ID,
            MtpConstants.PROPERTY_OBJECT_FORMAT,
            MtpConstants.PROPERTY_OBJECT_SIZE,
            MtpConstants.PROPERTY_OBJECT_FILE_NAME,
            MtpConstants.PROPERTY_PARENT_OBJECT,
        };
    }

    private int[] getSupportedDeviceProperties() {
        // no device properties yet
        return null;
    }

    private int getObjectProperty(int handle, int property,
                            long[] outIntValue, char[] outStringValue) {
        Log.d(TAG, "getObjectProperty: " + property);
        String column = null;
        boolean isString = false;

        switch (property) {
            case MtpConstants.PROPERTY_STORAGE_ID:
                outIntValue[0] = mStorageID;
                return MtpConstants.RESPONSE_OK;
            case MtpConstants.PROPERTY_OBJECT_FORMAT:
                column = MtpObjects.ObjectColumns.FORMAT;
                break;
            case MtpConstants.PROPERTY_PROTECTION_STATUS:
                // protection status is always 0
                outIntValue[0] = 0;
                return MtpConstants.RESPONSE_OK;
            case MtpConstants.PROPERTY_OBJECT_SIZE:
                column = MtpObjects.ObjectColumns.SIZE;
                break;
            case MtpConstants.PROPERTY_OBJECT_FILE_NAME:
                column = MtpObjects.ObjectColumns.DATA;
                isString = true;
                break;
            case MtpConstants.PROPERTY_DATE_MODIFIED:
                column = MtpObjects.ObjectColumns.DATE_MODIFIED;
                break;
            case MtpConstants.PROPERTY_PARENT_OBJECT:
                column = MtpObjects.ObjectColumns.PARENT;
                break;
            case MtpConstants.PROPERTY_PERSISTENT_UID:
                // PUID is concatenation of storageID and object handle
                long puid = mStorageID;
                puid <<= 32;
                puid += handle;
                outIntValue[0] = puid;
                return MtpConstants.RESPONSE_OK;
            default:
                return MtpConstants.RESPONSE_OBJECT_PROP_NOT_SUPPORTED;
        }

        Cursor c = null;
        try {
            // for now we are only reading properties from the "objects" table
            c = mMediaProvider.query(mObjectsUri,
                            new String [] { MtpObjects.ObjectColumns._ID, column },
                            ID_WHERE, new String[] { Integer.toString(handle) }, null);
            if (c != null && c.moveToNext()) {
                if (isString) {
                    String value = c.getString(1);
                    int start = 0;

                    if (property == MtpConstants.PROPERTY_OBJECT_FILE_NAME) {
                        // extract name from full path
                        int lastSlash = value.lastIndexOf('/');
                        if (lastSlash >= 0) {
                            start = lastSlash + 1;
                        }
                    }
                    int end = value.length();
                    if (end - start > 255) {
                        end = start + 255;
                    }
                    value.getChars(start, end, outStringValue, 0);
                    outStringValue[end - start] = 0;
                } else {
                    outIntValue[0] = c.getLong(1);
                }
                return MtpConstants.RESPONSE_OK;
            }
        } catch (Exception e) {
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        } finally {
            if (c != null) {
                c.close();
            }
        }
        // query failed if we get here
        return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
    }

    private boolean getObjectInfo(int handle, int[] outStorageFormatParent,
                        char[] outName, long[] outSizeModified) {
        Log.d(TAG, "getObjectInfo: " + handle);
        Cursor c = null;
        try {
            c = mMediaProvider.query(mObjectsUri, OBJECT_INFO_PROJECTION,
                            ID_WHERE, new String[] {  Integer.toString(handle) }, null);
            if (c != null && c.moveToNext()) {
                outStorageFormatParent[0] = mStorageID;
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

    private int getObjectFilePath(int handle, char[] outFilePath, long[] outFileLength) {
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
                return MtpConstants.RESPONSE_OK;
            } else {
                return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getObjectFilePath", e);
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private int deleteFile(int handle) {
        Log.d(TAG, "deleteFile: " + handle);
        Uri uri = MtpObjects.getContentUri(mVolumeName, handle);
        try {
            if (mMediaProvider.delete(uri, null, null) == 1) {
                return MtpConstants.RESPONSE_OK;
            } else {
                return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in deleteFile", e);
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        }
    }

    private int[] getObjectReferences(int handle) {
        Log.d(TAG, "getObjectReferences for: " + handle);
        Uri uri = MtpObjects.getReferencesUri(mVolumeName, handle);
        Cursor c = null;
        try {
            c = mMediaProvider.query(uri, ID_PROJECTION, null, null, null);
            if (c == null) {
                return null;
            }
            int count = c.getCount();
            if (count > 0) {
                int[] result = new int[count];
                for (int i = 0; i < count; i++) {
                    c.moveToNext();
                    result[i] = c.getInt(0);
                }
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

    private int setObjectReferences(int handle, int[] references) {
        Uri uri = MtpObjects.getReferencesUri(mVolumeName, handle);
        int count = references.length;
        ContentValues[] valuesList = new ContentValues[count];
        for (int i = 0; i < count; i++) {
            ContentValues values = new ContentValues();
            values.put(MtpObjects.ObjectColumns._ID, references[i]);
            valuesList[i] = values;
        }
        try {
            if (count == mMediaProvider.bulkInsert(uri, valuesList)) {
                return MtpConstants.RESPONSE_OK;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setObjectReferences", e);
        }
        return MtpConstants.RESPONSE_GENERAL_ERROR;
    }

    // used by the JNI code
    private int mNativeContext;

    private native final void native_setup();
    private native final void native_finalize();
}
