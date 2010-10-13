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
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Files;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.MediaColumns;
import android.provider.Mtp;
import android.util.Log;

import java.io.File;

/**
 * {@hide}
 */
public class MtpDatabase {

    private static final String TAG = "MtpDatabase";

    private final Context mContext;
    private final IContentProvider mMediaProvider;
    private final String mVolumeName;
    private final Uri mObjectsUri;
    private final String mMediaStoragePath;

    // true if the database has been modified in the current MTP session
    private boolean mDatabaseModified;

    // database for writable MTP device properties
    private SQLiteDatabase mDevicePropDb;
    private static final int DEVICE_PROPERTIES_DATABASE_VERSION = 1;

    // FIXME - this should be passed in via the constructor
    private final int mStorageID = 0x00010001;

    private static final String[] ID_PROJECTION = new String[] {
            Files.FileColumns._ID, // 0
    };
    private static final String[] PATH_PROJECTION = new String[] {
            Files.FileColumns._ID, // 0
            Files.FileColumns.DATA, // 1
    };
    private static final String[] PATH_SIZE_PROJECTION = new String[] {
            Files.FileColumns._ID, // 0
            Files.FileColumns.DATA, // 1
            Files.FileColumns.SIZE, // 2
    };
    private static final String[] OBJECT_INFO_PROJECTION = new String[] {
            Files.FileColumns._ID, // 0
            Files.FileColumns.DATA, // 1
            Files.FileColumns.FORMAT, // 2
            Files.FileColumns.PARENT, // 3
            Files.FileColumns.SIZE, // 4
            Files.FileColumns.DATE_MODIFIED, // 5
    };
    private static final String ID_WHERE = Files.FileColumns._ID + "=?";
    private static final String PATH_WHERE = Files.FileColumns.DATA + "=?";
    private static final String PARENT_WHERE = Files.FileColumns.PARENT + "=?";
    private static final String PARENT_FORMAT_WHERE = PARENT_WHERE + " AND "
                                            + Files.FileColumns.FORMAT + "=?";

    private static final String[] DEVICE_PROPERTY_PROJECTION = new String[] { "_id", "value" };
    private  static final String DEVICE_PROPERTY_WHERE = "code=?";

    private final MediaScanner mMediaScanner;

    static {
        System.loadLibrary("media_jni");
    }

    public MtpDatabase(Context context, String volumeName, String storagePath) {
        native_setup();

        mContext = context;
        mMediaProvider = context.getContentResolver().acquireProvider("media");
        mVolumeName = volumeName;
        mMediaStoragePath = storagePath;
        mObjectsUri = Files.getMtpObjectsUri(volumeName);
        mMediaScanner = new MediaScanner(context);
        openDevicePropertiesDatabase(context);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            native_finalize();
        } finally {
            super.finalize();
        }
    }

    private void openDevicePropertiesDatabase(Context context) {
        mDevicePropDb = context.openOrCreateDatabase("device-properties", Context.MODE_PRIVATE, null);
        int version = mDevicePropDb.getVersion();

        // initialize if necessary
        if (version != DEVICE_PROPERTIES_DATABASE_VERSION) {
            mDevicePropDb.execSQL("CREATE TABLE properties (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "code INTEGER UNIQUE ON CONFLICT REPLACE," +
                    "value TEXT" +
                    ");");
            mDevicePropDb.execSQL("CREATE INDEX property_index ON properties (code);");
            mDevicePropDb.setVersion(DEVICE_PROPERTIES_DATABASE_VERSION);
        }
    }

    private int beginSendObject(String path, int format, int parent,
                         int storage, long size, long modified) {
        mDatabaseModified = true;
        ContentValues values = new ContentValues();
        values.put(Files.FileColumns.DATA, path);
        values.put(Files.FileColumns.FORMAT, format);
        values.put(Files.FileColumns.PARENT, parent);
        // storage is ignored for now
        values.put(Files.FileColumns.SIZE, size);
        values.put(Files.FileColumns.DATE_MODIFIED, modified);

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
                mMediaScanner.scanMtpFile(path, mVolumeName, handle, format);
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
            // allow transfering arbitrary files
            MtpConstants.FORMAT_UNDEFINED,

            MtpConstants.FORMAT_ASSOCIATION,
            MtpConstants.FORMAT_TEXT,
            MtpConstants.FORMAT_HTML,
            MtpConstants.FORMAT_WAV,
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
            MtpConstants.FORMAT_XML_DOCUMENT,
        };
    }

    private int[] getSupportedCaptureFormats() {
        // no capture formats yet
        return null;
    }

    static final int[] FILE_PROPERTIES = {
            // NOTE must match beginning of AUDIO_PROPERTIES, VIDEO_PROPERTIES
            // and IMAGE_PROPERTIES below
            MtpConstants.PROPERTY_STORAGE_ID,
            MtpConstants.PROPERTY_OBJECT_FORMAT,
            MtpConstants.PROPERTY_PROTECTION_STATUS,
            MtpConstants.PROPERTY_OBJECT_SIZE,
            MtpConstants.PROPERTY_OBJECT_FILE_NAME,
            MtpConstants.PROPERTY_DATE_MODIFIED,
            MtpConstants.PROPERTY_PARENT_OBJECT,
            MtpConstants.PROPERTY_PERSISTENT_UID,
            MtpConstants.PROPERTY_NAME,
            MtpConstants.PROPERTY_DATE_ADDED,
    };

    static final int[] AUDIO_PROPERTIES = {
            // NOTE must match FILE_PROPERTIES above
            MtpConstants.PROPERTY_STORAGE_ID,
            MtpConstants.PROPERTY_OBJECT_FORMAT,
            MtpConstants.PROPERTY_PROTECTION_STATUS,
            MtpConstants.PROPERTY_OBJECT_SIZE,
            MtpConstants.PROPERTY_OBJECT_FILE_NAME,
            MtpConstants.PROPERTY_DATE_MODIFIED,
            MtpConstants.PROPERTY_PARENT_OBJECT,
            MtpConstants.PROPERTY_PERSISTENT_UID,
            MtpConstants.PROPERTY_NAME,
            MtpConstants.PROPERTY_DISPLAY_NAME,
            MtpConstants.PROPERTY_DATE_ADDED,

            // audio specific properties
            MtpConstants.PROPERTY_ARTIST,
            MtpConstants.PROPERTY_ALBUM_NAME,
            MtpConstants.PROPERTY_ALBUM_ARTIST,
            MtpConstants.PROPERTY_TRACK,
            MtpConstants.PROPERTY_ORIGINAL_RELEASE_DATE,
            MtpConstants.PROPERTY_DURATION,
            MtpConstants.PROPERTY_GENRE,
            MtpConstants.PROPERTY_COMPOSER,
    };

    static final int[] VIDEO_PROPERTIES = {
            // NOTE must match FILE_PROPERTIES above
            MtpConstants.PROPERTY_STORAGE_ID,
            MtpConstants.PROPERTY_OBJECT_FORMAT,
            MtpConstants.PROPERTY_PROTECTION_STATUS,
            MtpConstants.PROPERTY_OBJECT_SIZE,
            MtpConstants.PROPERTY_OBJECT_FILE_NAME,
            MtpConstants.PROPERTY_DATE_MODIFIED,
            MtpConstants.PROPERTY_PARENT_OBJECT,
            MtpConstants.PROPERTY_PERSISTENT_UID,
            MtpConstants.PROPERTY_NAME,
            MtpConstants.PROPERTY_DISPLAY_NAME,
            MtpConstants.PROPERTY_DATE_ADDED,

            // video specific properties
            MtpConstants.PROPERTY_ARTIST,
            MtpConstants.PROPERTY_ALBUM_NAME,
            MtpConstants.PROPERTY_DURATION,
            MtpConstants.PROPERTY_DESCRIPTION,
    };

    static final int[] IMAGE_PROPERTIES = {
            // NOTE must match FILE_PROPERTIES above
            MtpConstants.PROPERTY_STORAGE_ID,
            MtpConstants.PROPERTY_OBJECT_FORMAT,
            MtpConstants.PROPERTY_PROTECTION_STATUS,
            MtpConstants.PROPERTY_OBJECT_SIZE,
            MtpConstants.PROPERTY_OBJECT_FILE_NAME,
            MtpConstants.PROPERTY_DATE_MODIFIED,
            MtpConstants.PROPERTY_PARENT_OBJECT,
            MtpConstants.PROPERTY_PERSISTENT_UID,
            MtpConstants.PROPERTY_NAME,
            MtpConstants.PROPERTY_DISPLAY_NAME,
            MtpConstants.PROPERTY_DATE_ADDED,

            // image specific properties
            MtpConstants.PROPERTY_DESCRIPTION,
    };

    private int[] getSupportedObjectProperties(int format) {
        switch (format) {
            case MtpConstants.FORMAT_MP3:
            case MtpConstants.FORMAT_WAV:
            case MtpConstants.FORMAT_WMA:
            case MtpConstants.FORMAT_OGG:
            case MtpConstants.FORMAT_AAC:
                return AUDIO_PROPERTIES;
            case MtpConstants.FORMAT_MPEG:
            case MtpConstants.FORMAT_3GP_CONTAINER:
            case MtpConstants.FORMAT_WMV:
                return VIDEO_PROPERTIES;
            case MtpConstants.FORMAT_EXIF_JPEG:
            case MtpConstants.FORMAT_GIF:
            case MtpConstants.FORMAT_PNG:
            case MtpConstants.FORMAT_BMP:
                return IMAGE_PROPERTIES;
            default:
                return FILE_PROPERTIES;
        }
    }

    private int[] getSupportedDeviceProperties() {
        return new int[] {
            MtpConstants.DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER,
            MtpConstants.DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME,
        };
    }

    private String queryString(int id, String column) {
        Cursor c = null;
        try {
            // for now we are only reading properties from the "objects" table
            c = mMediaProvider.query(mObjectsUri,
                            new String [] { Files.FileColumns._ID, column },
                            ID_WHERE, new String[] { Integer.toString(id) }, null);
            if (c != null && c.moveToNext()) {
                return c.getString(1);
            } else {
                return "";
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private String queryGenre(int id) {
        Cursor c = null;
        try {
            Uri uri = Audio.Genres.getContentUriForAudioId(mVolumeName, id);
            c = mMediaProvider.query(uri,
                            new String [] { Files.FileColumns._ID, Audio.GenresColumns.NAME },
                            null, null, null);
            if (c != null && c.moveToNext()) {
                return c.getString(1);
            } else {
                return "";
            }
        } catch (Exception e) {
            Log.e(TAG, "queryGenre exception", e);
            return null;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private boolean queryInt(int id, String column, long[] outValue) {
        Cursor c = null;
        try {
            // for now we are only reading properties from the "objects" table
            c = mMediaProvider.query(mObjectsUri,
                            new String [] { Files.FileColumns._ID, column },
                            ID_WHERE, new String[] { Integer.toString(id) }, null);
            if (c != null && c.moveToNext()) {
                outValue[0] = c.getLong(1);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private String nameFromPath(String path) {
        // extract name from full path
        int start = 0;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0) {
            start = lastSlash + 1;
        }
        int end = path.length();
        if (end - start > 255) {
            end = start + 255;
        }
        return path.substring(start, end);
    }

    private int renameFile(int handle, String newName) {
        Cursor c = null;

        // first compute current path
        String path = null;
        String[] whereArgs = new String[] {  Integer.toString(handle) };
        try {
            c = mMediaProvider.query(mObjectsUri, PATH_PROJECTION, ID_WHERE, whereArgs, null);
            if (c != null && c.moveToNext()) {
                path = c.getString(1);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getObjectFilePath", e);
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (path == null) {
            return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
        }

        // now rename the file.  make sure this succeeds before updating database
        File oldFile = new File(path);
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 1) {
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        }
        String newPath = path.substring(0, lastSlash + 1) + newName;
        File newFile = new File(newPath);
        boolean success = oldFile.renameTo(newFile);
        Log.d(TAG, "renaming "+ path + " to " + newPath + (success ? " succeeded" : " failed"));
        if (!success) {
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        }

        // finally update database
        ContentValues values = new ContentValues();
        values.put(Files.FileColumns.DATA, newPath);
        int updated = 0;
        try {
            // note - we are relying on a special case in MediaProvider.update() to update
            // the paths for all children in the case where this is a directory.
            updated = mMediaProvider.update(mObjectsUri, values, ID_WHERE, whereArgs);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in mMediaProvider.update", e);
        }
        if (updated == 0) {
            Log.e(TAG, "Unable to update path for " + path + " to " + newPath);
            // this shouldn't happen, but if it does we need to rename the file to its original name
            newFile.renameTo(oldFile);
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        }

        return MtpConstants.RESPONSE_OK;
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
                column = Files.FileColumns.FORMAT;
                break;
            case MtpConstants.PROPERTY_PROTECTION_STATUS:
                // protection status is always 0
                outIntValue[0] = 0;
                return MtpConstants.RESPONSE_OK;
            case MtpConstants.PROPERTY_OBJECT_SIZE:
                column = Files.FileColumns.SIZE;
                break;
            case MtpConstants.PROPERTY_OBJECT_FILE_NAME:
                // special case - need to extract file name from full path
                String value = queryString(handle, Files.FileColumns.DATA);
                if (value != null) {
                    value = nameFromPath(value);
                    value.getChars(0, value.length(), outStringValue, 0);
                    outStringValue[value.length()] = 0;
                    return MtpConstants.RESPONSE_OK;
                } else {
                    return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
                }
            case MtpConstants.PROPERTY_NAME:
                // first try title
                String name = queryString(handle, MediaColumns.TITLE);
                // then try name
                if (name == null) {
                    name = queryString(handle, Audio.PlaylistsColumns.NAME);
                }
                // if title and name fail, extract name from full path
                if (name == null) {
                    name = queryString(handle, Files.FileColumns.DATA);
                    if (name != null) {
                        name = nameFromPath(name);
                    }
                }
                if (name != null) {
                    name.getChars(0, name.length(), outStringValue, 0);
                    outStringValue[name.length()] = 0;
                    return MtpConstants.RESPONSE_OK;
                } else {
                    return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
                }
            case MtpConstants.PROPERTY_DATE_MODIFIED:
                column = Files.FileColumns.DATE_MODIFIED;
                break;
            case MtpConstants.PROPERTY_DATE_ADDED:
                column = Files.FileColumns.DATE_ADDED;
                break;
            case MtpConstants.PROPERTY_ORIGINAL_RELEASE_DATE:
                column = Audio.AudioColumns.YEAR;
                break;
            case MtpConstants.PROPERTY_PARENT_OBJECT:
                column = Files.FileColumns.PARENT;
                break;
            case MtpConstants.PROPERTY_PERSISTENT_UID:
                // PUID is concatenation of storageID and object handle
                long puid = mStorageID;
                puid <<= 32;
                puid += handle;
                outIntValue[0] = puid;
                return MtpConstants.RESPONSE_OK;
            case MtpConstants.PROPERTY_DURATION:
                column = Audio.AudioColumns.DURATION;
                break;
            case MtpConstants.PROPERTY_TRACK:
                if (queryInt(handle, Audio.AudioColumns.TRACK, outIntValue)) {
                    // track is stored in lower 3 decimal digits
                    outIntValue[0] %= 1000;
                    return MtpConstants.RESPONSE_OK;
                } else {
                    return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
                }
            case MtpConstants.PROPERTY_DISPLAY_NAME:
                column = MediaColumns.DISPLAY_NAME;
                isString = true;
                break;
            case MtpConstants.PROPERTY_ARTIST:
                column = Audio.AudioColumns.ARTIST;
                isString = true;
                break;
            case MtpConstants.PROPERTY_ALBUM_NAME:
                column = Audio.AudioColumns.ALBUM;
                isString = true;
                break;
            case MtpConstants.PROPERTY_ALBUM_ARTIST:
                column = Audio.AudioColumns.ALBUM_ARTIST;
                isString = true;
                break;
            case MtpConstants.PROPERTY_GENRE:
                String genre = queryGenre(handle);
                if (genre != null) {
                    genre.getChars(0, genre.length(), outStringValue, 0);
                    outStringValue[genre.length()] = 0;
                    return MtpConstants.RESPONSE_OK;
                } else {
                    return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
                }
            case MtpConstants.PROPERTY_COMPOSER:
                column = Audio.AudioColumns.COMPOSER;
                isString = true;
                break;
            case MtpConstants.PROPERTY_DESCRIPTION:
                column = Images.ImageColumns.DESCRIPTION;
                isString = true;
                break;
            default:
                return MtpConstants.RESPONSE_OBJECT_PROP_NOT_SUPPORTED;
        }

        if (isString) {
            String value = queryString(handle, column);
            if (value != null) {
                value.getChars(0, value.length(), outStringValue, 0);
                outStringValue[value.length()] = 0;
                return MtpConstants.RESPONSE_OK;
            }
        } else {
            if (queryInt(handle, column, outIntValue)) {
                return MtpConstants.RESPONSE_OK;
            }
        }
        // query failed if we get here
        return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
    }

    private int setObjectProperty(int handle, int property,
                            long intValue, String stringValue) {
        Log.d(TAG, "setObjectProperty: " + property);

        switch (property) {
            case MtpConstants.PROPERTY_OBJECT_FILE_NAME:
                return renameFile(handle, stringValue);

            default:
                return MtpConstants.RESPONSE_OBJECT_PROP_NOT_SUPPORTED;
        }
    }

    private int getDeviceProperty(int property, long[] outIntValue, char[] outStringValue) {
        Log.d(TAG, "getDeviceProperty: " + property);

        switch (property) {
            case MtpConstants.DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER:
            case MtpConstants.DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME:
                // writable string properties kept in our device property database
                Cursor c = null;
                try {
                    c = mDevicePropDb.query("properties", DEVICE_PROPERTY_PROJECTION,
                        DEVICE_PROPERTY_WHERE, new String[] {  Integer.toString(property) },
                        null, null, null);

                    if (c != null && c.moveToNext()) {
                        String value = c.getString(1);
                        int length = value.length();
                        if (length > 255) {
                            length = 255;
                        }
                        value.getChars(0, length, outStringValue, 0);
                        outStringValue[length] = 0;
                    } else {
                        outStringValue[0] = 0;
                    }
                    return MtpConstants.RESPONSE_OK;
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
        }

        return MtpConstants.RESPONSE_DEVICE_PROP_NOT_SUPPORTED;
    }

    private int setDeviceProperty(int property, long intValue, String stringValue) {
        Log.d(TAG, "setDeviceProperty: " + property + " : " + stringValue);

        switch (property) {
            case MtpConstants.DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER:
            case MtpConstants.DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME:
                // writable string properties kept in our device property database
                try {
                    ContentValues values = new ContentValues();
                    values.put("code", property);
                    values.put("value", stringValue);
                    mDevicePropDb.insert("properties", "code", values);
                    return MtpConstants.RESPONSE_OK;
                } catch (Exception e) {
                    return MtpConstants.RESPONSE_GENERAL_ERROR;
                }
        }

        return MtpConstants.RESPONSE_DEVICE_PROP_NOT_SUPPORTED;
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
        if (handle == 0) {
            // special case root directory
            mMediaStoragePath.getChars(0, mMediaStoragePath.length(), outFilePath, 0);
            outFilePath[mMediaStoragePath.length()] = 0;
            outFileLength[0] = 0;
            return MtpConstants.RESPONSE_OK;
        }
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

    private int deleteRecursive(int handle) throws RemoteException {
        int[] children = getObjectList(0 /* storageID */, 0 /* format */, handle);
        Uri uri = Files.getMtpObjectsUri(mVolumeName, handle);
        // delete parent first, to avoid potential infinite recursion
        int count = mMediaProvider.delete(uri, null, null);
        if (count == 1) {
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    count += deleteRecursive(children[i]);
                }
            }
        }
        return count;
    }

    private int deleteFile(int handle) {
        Log.d(TAG, "deleteFile: " + handle);
        mDatabaseModified = true;
        try {
            if (deleteRecursive(handle) > 0) {
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
        Uri uri = Files.getMtpReferencesUri(mVolumeName, handle);
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
        mDatabaseModified = true;
        Uri uri = Files.getMtpReferencesUri(mVolumeName, handle);
        int count = references.length;
        ContentValues[] valuesList = new ContentValues[count];
        for (int i = 0; i < count; i++) {
            ContentValues values = new ContentValues();
            values.put(Files.FileColumns._ID, references[i]);
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

    private void sessionStarted() {
        Log.d(TAG, "sessionStarted");
        mDatabaseModified = false;
    }

    private void sessionEnded() {
        Log.d(TAG, "sessionEnded");
        if (mDatabaseModified) {
            Log.d(TAG, "sending ACTION_MTP_SESSION_END");
            mContext.sendBroadcast(new Intent(Mtp.ACTION_MTP_SESSION_END));
            mDatabaseModified = false;
        }
    }

    // used by the JNI code
    private int mNativeContext;

    private native final void native_setup();
    private native final void native_finalize();
}
