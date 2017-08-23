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

package android.mtp;

import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.MediaScanner;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Files;
import android.provider.MediaStore.MediaColumns;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import dalvik.system.CloseGuard;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@hide}
 */
public class MtpDatabase implements AutoCloseable {
    private static final String TAG = "MtpDatabase";

    private final Context mUserContext;
    private final Context mContext;
    private final String mPackageName;
    private final ContentProviderClient mMediaProvider;
    private final String mVolumeName;
    private final Uri mObjectsUri;
    private final MediaScanner mMediaScanner;

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    // path to primary storage
    private final String mMediaStoragePath;
    // if not null, restrict all queries to these subdirectories
    private final String[] mSubDirectories;
    // where clause for restricting queries to files in mSubDirectories
    private String mSubDirectoriesWhere;
    // where arguments for restricting queries to files in mSubDirectories
    private String[] mSubDirectoriesWhereArgs;

    private final HashMap<String, MtpStorage> mStorageMap = new HashMap<String, MtpStorage>();

    // cached property groups for single properties
    private final HashMap<Integer, MtpPropertyGroup> mPropertyGroupsByProperty
            = new HashMap<Integer, MtpPropertyGroup>();

    // cached property groups for all properties for a given format
    private final HashMap<Integer, MtpPropertyGroup> mPropertyGroupsByFormat
            = new HashMap<Integer, MtpPropertyGroup>();

    // true if the database has been modified in the current MTP session
    private boolean mDatabaseModified;

    // SharedPreferences for writable MTP device properties
    private SharedPreferences mDeviceProperties;
    private static final int DEVICE_PROPERTIES_DATABASE_VERSION = 1;

    private static final String[] ID_PROJECTION = new String[] {
            Files.FileColumns._ID, // 0
    };
    private static final String[] PATH_PROJECTION = new String[] {
            Files.FileColumns._ID, // 0
            Files.FileColumns.DATA, // 1
    };
    private static final String[] FORMAT_PROJECTION = new String[] {
            Files.FileColumns._ID, // 0
            Files.FileColumns.FORMAT, // 1
    };
    private static final String[] PATH_FORMAT_PROJECTION = new String[] {
            Files.FileColumns._ID, // 0
            Files.FileColumns.DATA, // 1
            Files.FileColumns.FORMAT, // 2
    };
    private static final String[] OBJECT_INFO_PROJECTION = new String[] {
            Files.FileColumns._ID, // 0
            Files.FileColumns.STORAGE_ID, // 1
            Files.FileColumns.FORMAT, // 2
            Files.FileColumns.PARENT, // 3
            Files.FileColumns.DATA, // 4
            Files.FileColumns.DATE_ADDED, // 5
            Files.FileColumns.DATE_MODIFIED, // 6
    };
    private static final String ID_WHERE = Files.FileColumns._ID + "=?";
    private static final String PATH_WHERE = Files.FileColumns.DATA + "=?";

    private static final String STORAGE_WHERE = Files.FileColumns.STORAGE_ID + "=?";
    private static final String FORMAT_WHERE = Files.FileColumns.FORMAT + "=?";
    private static final String PARENT_WHERE = Files.FileColumns.PARENT + "=?";
    private static final String STORAGE_FORMAT_WHERE = STORAGE_WHERE + " AND "
                                            + Files.FileColumns.FORMAT + "=?";
    private static final String STORAGE_PARENT_WHERE = STORAGE_WHERE + " AND "
                                            + Files.FileColumns.PARENT + "=?";
    private static final String FORMAT_PARENT_WHERE = FORMAT_WHERE + " AND "
                                            + Files.FileColumns.PARENT + "=?";
    private static final String STORAGE_FORMAT_PARENT_WHERE = STORAGE_FORMAT_WHERE + " AND "
                                            + Files.FileColumns.PARENT + "=?";

    private MtpServer mServer;

    // read from native code
    private int mBatteryLevel;
    private int mBatteryScale;

    private int mDeviceType;

    static {
        System.loadLibrary("media_jni");
    }

    private BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
          @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                mBatteryScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
                int newLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                if (newLevel != mBatteryLevel) {
                    mBatteryLevel = newLevel;
                    if (mServer != null) {
                        // send device property changed event
                        mServer.sendDevicePropertyChanged(
                                MtpConstants.DEVICE_PROPERTY_BATTERY_LEVEL);
                    }
                }
            }
        }
    };

    public MtpDatabase(Context context, Context userContext, String volumeName, String storagePath,
            String[] subDirectories) {
        native_setup();

        mContext = context;
        mUserContext = userContext;
        mPackageName = context.getPackageName();
        mMediaProvider = userContext.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY);
        mVolumeName = volumeName;
        mMediaStoragePath = storagePath;
        mObjectsUri = Files.getMtpObjectsUri(volumeName);
        mMediaScanner = new MediaScanner(context, mVolumeName);

        mSubDirectories = subDirectories;
        if (subDirectories != null) {
            // Compute "where" string for restricting queries to subdirectories
            StringBuilder builder = new StringBuilder();
            builder.append("(");
            int count = subDirectories.length;
            for (int i = 0; i < count; i++) {
                builder.append(Files.FileColumns.DATA + "=? OR "
                        + Files.FileColumns.DATA + " LIKE ?");
                if (i != count - 1) {
                    builder.append(" OR ");
                }
            }
            builder.append(")");
            mSubDirectoriesWhere = builder.toString();

            // Compute "where" arguments for restricting queries to subdirectories
            mSubDirectoriesWhereArgs = new String[count * 2];
            for (int i = 0, j = 0; i < count; i++) {
                String path = subDirectories[i];
                mSubDirectoriesWhereArgs[j++] = path;
                mSubDirectoriesWhereArgs[j++] = path + "/%";
            }
        }

        initDeviceProperties(context);
        mDeviceType = SystemProperties.getInt("sys.usb.mtp.device_type", 0);

        mCloseGuard.open("close");
    }

    public void setServer(MtpServer server) {
        mServer = server;

        // always unregister before registering
        try {
            mContext.unregisterReceiver(mBatteryReceiver);
        } catch (IllegalArgumentException e) {
            // wasn't previously registered, ignore
        }

        // register for battery notifications when we are connected
        if (server != null) {
            mContext.registerReceiver(mBatteryReceiver,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
    }

    @Override
    public void close() {
        mCloseGuard.close();
        if (mClosed.compareAndSet(false, true)) {
            mMediaScanner.close();
            mMediaProvider.close();
            native_finalize();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        } finally {
            super.finalize();
        }
    }

    public void addStorage(MtpStorage storage) {
        mStorageMap.put(storage.getPath(), storage);
    }

    public void removeStorage(MtpStorage storage) {
        mStorageMap.remove(storage.getPath());
    }

    private void initDeviceProperties(Context context) {
        final String devicePropertiesName = "device-properties";
        mDeviceProperties = context.getSharedPreferences(devicePropertiesName, Context.MODE_PRIVATE);
        File databaseFile = context.getDatabasePath(devicePropertiesName);

        if (databaseFile.exists()) {
            // for backward compatibility - read device properties from sqlite database
            // and migrate them to shared prefs
            SQLiteDatabase db = null;
            Cursor c = null;
            try {
                db = context.openOrCreateDatabase("device-properties", Context.MODE_PRIVATE, null);
                if (db != null) {
                    c = db.query("properties", new String[] { "_id", "code", "value" },
                            null, null, null, null, null);
                    if (c != null) {
                        SharedPreferences.Editor e = mDeviceProperties.edit();
                        while (c.moveToNext()) {
                            String name = c.getString(1);
                            String value = c.getString(2);
                            e.putString(name, value);
                        }
                        e.commit();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "failed to migrate device properties", e);
            } finally {
                if (c != null) c.close();
                if (db != null) db.close();
            }
            context.deleteDatabase(devicePropertiesName);
        }
    }

    // check to see if the path is contained in one of our storage subdirectories
    // returns true if we have no special subdirectories
    private boolean inStorageSubDirectory(String path) {
        if (mSubDirectories == null) return true;
        if (path == null) return false;

        boolean allowed = false;
        int pathLength = path.length();
        for (int i = 0; i < mSubDirectories.length && !allowed; i++) {
            String subdir = mSubDirectories[i];
            int subdirLength = subdir.length();
            if (subdirLength < pathLength &&
                    path.charAt(subdirLength) == '/' &&
                    path.startsWith(subdir)) {
                allowed = true;
            }
        }
        return allowed;
    }

    // check to see if the path matches one of our storage subdirectories
    // returns true if we have no special subdirectories
    private boolean isStorageSubDirectory(String path) {
    if (mSubDirectories == null) return false;
        for (int i = 0; i < mSubDirectories.length; i++) {
            if (path.equals(mSubDirectories[i])) {
                return true;
            }
        }
        return false;
    }

    // returns true if the path is in the storage root
    private boolean inStorageRoot(String path) {
        try {
            File f = new File(path);
            String canonical = f.getCanonicalPath();
            for (String root: mStorageMap.keySet()) {
                if (canonical.startsWith(root)) {
                    return true;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return false;
    }

    private int beginSendObject(String path, int format, int parent,
                         int storageId, long size, long modified) {
        // if the path is outside of the storage root, do not allow access
        if (!inStorageRoot(path)) {
            Log.e(TAG, "attempt to put file outside of storage area: " + path);
            return -1;
        }
        // if mSubDirectories is not null, do not allow copying files to any other locations
        if (!inStorageSubDirectory(path)) return -1;

        // make sure the object does not exist
        if (path != null) {
            Cursor c = null;
            try {
                c = mMediaProvider.query(mObjectsUri, ID_PROJECTION, PATH_WHERE,
                        new String[] { path }, null, null);
                if (c != null && c.getCount() > 0) {
                    Log.w(TAG, "file already exists in beginSendObject: " + path);
                    return -1;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in beginSendObject", e);
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }

        mDatabaseModified = true;
        ContentValues values = new ContentValues();
        values.put(Files.FileColumns.DATA, path);
        values.put(Files.FileColumns.FORMAT, format);
        values.put(Files.FileColumns.PARENT, parent);
        values.put(Files.FileColumns.STORAGE_ID, storageId);
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
                // extract name from path
                String name = path;
                int lastSlash = name.lastIndexOf('/');
                if (lastSlash >= 0) {
                    name = name.substring(lastSlash + 1);
                }
                // strip trailing ".pla" from the name
                if (name.endsWith(".pla")) {
                    name = name.substring(0, name.length() - 4);
                }

                ContentValues values = new ContentValues(1);
                values.put(Audio.Playlists.DATA, path);
                values.put(Audio.Playlists.NAME, name);
                values.put(Files.FileColumns.FORMAT, format);
                values.put(Files.FileColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000);
                values.put(MediaColumns.MEDIA_SCANNER_NEW_OBJECT_ID, handle);
                try {
                    Uri uri = mMediaProvider.insert(
                            Audio.Playlists.EXTERNAL_CONTENT_URI, values);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException in endSendObject", e);
                }
            } else {
                mMediaScanner.scanMtpFile(path, handle, format);
            }
        } else {
            deleteFile(handle);
        }
    }

    private Cursor createObjectQuery(int storageID, int format, int parent) throws RemoteException {
        String where;
        String[] whereArgs;

        if (storageID == 0xFFFFFFFF) {
            // query all stores
            if (format == 0) {
                // query all formats
                if (parent == 0) {
                    // query all objects
                    where = null;
                    whereArgs = null;
                } else {
                    if (parent == 0xFFFFFFFF) {
                        // all objects in root of store
                        parent = 0;
                    }
                    where = PARENT_WHERE;
                    whereArgs = new String[] { Integer.toString(parent) };
                }
            } else {
                // query specific format
                if (parent == 0) {
                    // query all objects
                    where = FORMAT_WHERE;
                    whereArgs = new String[] { Integer.toString(format) };
                } else {
                    if (parent == 0xFFFFFFFF) {
                        // all objects in root of store
                        parent = 0;
                    }
                    where = FORMAT_PARENT_WHERE;
                    whereArgs = new String[] { Integer.toString(format),
                                               Integer.toString(parent) };
                }
            }
        } else {
            // query specific store
            if (format == 0) {
                // query all formats
                if (parent == 0) {
                    // query all objects
                    where = STORAGE_WHERE;
                    whereArgs = new String[] { Integer.toString(storageID) };
                } else {
                    if (parent == 0xFFFFFFFF) {
                        // all objects in root of store
                        parent = 0;
                    }
                    where = STORAGE_PARENT_WHERE;
                    whereArgs = new String[] { Integer.toString(storageID),
                                               Integer.toString(parent) };
                }
            } else {
                // query specific format
                if (parent == 0) {
                    // query all objects
                    where = STORAGE_FORMAT_WHERE;
                    whereArgs = new String[] {  Integer.toString(storageID),
                                                Integer.toString(format) };
                } else {
                    if (parent == 0xFFFFFFFF) {
                        // all objects in root of store
                        parent = 0;
                    }
                    where = STORAGE_FORMAT_PARENT_WHERE;
                    whereArgs = new String[] { Integer.toString(storageID),
                                               Integer.toString(format),
                                               Integer.toString(parent) };
                }
            }
        }

        // if we are restricting queries to mSubDirectories, we need to add the restriction
        // onto our "where" arguments
        if (mSubDirectoriesWhere != null) {
            if (where == null) {
                where = mSubDirectoriesWhere;
                whereArgs = mSubDirectoriesWhereArgs;
            } else {
                where = where + " AND " + mSubDirectoriesWhere;

                // create new array to hold whereArgs and mSubDirectoriesWhereArgs
                String[] newWhereArgs =
                        new String[whereArgs.length + mSubDirectoriesWhereArgs.length];
                int i, j;
                for (i = 0; i < whereArgs.length; i++) {
                    newWhereArgs[i] = whereArgs[i];
                }
                for (j = 0; j < mSubDirectoriesWhereArgs.length; i++, j++) {
                    newWhereArgs[i] = mSubDirectoriesWhereArgs[j];
                }
                whereArgs = newWhereArgs;
            }
        }

        return mMediaProvider.query(mObjectsUri, ID_PROJECTION, where,
                whereArgs, null, null);
    }

    private int[] getObjectList(int storageID, int format, int parent) {
        Cursor c = null;
        try {
            c = createObjectQuery(storageID, format, parent);
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

    private int getNumObjects(int storageID, int format, int parent) {
        Cursor c = null;
        try {
            c = createObjectQuery(storageID, format, parent);
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
            MtpConstants.FORMAT_BMP,
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
            MtpConstants.FORMAT_FLAC,
            MtpConstants.FORMAT_DNG,
            MtpConstants.FORMAT_HEIF,
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
            MtpConstants.PROPERTY_DISPLAY_NAME,
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
            MtpConstants.PROPERTY_AUDIO_WAVE_CODEC,
            MtpConstants.PROPERTY_BITRATE_TYPE,
            MtpConstants.PROPERTY_AUDIO_BITRATE,
            MtpConstants.PROPERTY_NUMBER_OF_CHANNELS,
            MtpConstants.PROPERTY_SAMPLE_RATE,
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
            case MtpConstants.FORMAT_DNG:
            case MtpConstants.FORMAT_HEIF:
                return IMAGE_PROPERTIES;
            default:
                return FILE_PROPERTIES;
        }
    }

    private int[] getSupportedDeviceProperties() {
        return new int[] {
            MtpConstants.DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER,
            MtpConstants.DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME,
            MtpConstants.DEVICE_PROPERTY_IMAGE_SIZE,
            MtpConstants.DEVICE_PROPERTY_BATTERY_LEVEL,
            MtpConstants.DEVICE_PROPERTY_PERCEIVED_DEVICE_TYPE,
        };
    }

    private MtpPropertyList getObjectPropertyList(int handle, int format, int property,
                        int groupCode, int depth) {
        // FIXME - implement group support
        if (groupCode != 0) {
            return new MtpPropertyList(0, MtpConstants.RESPONSE_SPECIFICATION_BY_GROUP_UNSUPPORTED);
        }

        MtpPropertyGroup propertyGroup;
        if (property == 0xffffffff) {
            if (format == 0 && handle != 0 && handle != 0xffffffff) {
                // return properties based on the object's format
                format = getObjectFormat(handle);
            }
            propertyGroup = mPropertyGroupsByFormat.get(format);
            if (propertyGroup == null) {
                int[] propertyList = getSupportedObjectProperties(format);
                propertyGroup = new MtpPropertyGroup(this, mMediaProvider,
                        mVolumeName, propertyList);
                mPropertyGroupsByFormat.put(format, propertyGroup);
            }
        } else {
            propertyGroup = mPropertyGroupsByProperty.get(property);
            if (propertyGroup == null) {
                final int[] propertyList = new int[] { property };
                propertyGroup = new MtpPropertyGroup(
                        this, mMediaProvider, mVolumeName, propertyList);
                mPropertyGroupsByProperty.put(property, propertyGroup);
            }
        }

        return propertyGroup.getPropertyList(handle, format, depth);
    }

    private int renameFile(int handle, String newName) {
        Cursor c = null;

        // first compute current path
        String path = null;
        String[] whereArgs = new String[] {  Integer.toString(handle) };
        try {
            c = mMediaProvider.query(mObjectsUri, PATH_PROJECTION, ID_WHERE,
                    whereArgs, null, null);
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

        // do not allow renaming any of the special subdirectories
        if (isStorageSubDirectory(path)) {
            return MtpConstants.RESPONSE_OBJECT_WRITE_PROTECTED;
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
        if (!success) {
            Log.w(TAG, "renaming "+ path + " to " + newPath + " failed");
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

        // check if nomedia status changed
        if (newFile.isDirectory()) {
            // for directories, check if renamed from something hidden to something non-hidden
            if (oldFile.getName().startsWith(".") && !newPath.startsWith(".")) {
                // directory was unhidden
                try {
                    mMediaProvider.call(MediaStore.UNHIDE_CALL, newPath, null);
                } catch (RemoteException e) {
                    Log.e(TAG, "failed to unhide/rescan for " + newPath);
                }
            }
        } else {
            // for files, check if renamed from .nomedia to something else
            if (oldFile.getName().toLowerCase(Locale.US).equals(".nomedia")
                    && !newPath.toLowerCase(Locale.US).equals(".nomedia")) {
                try {
                    mMediaProvider.call(MediaStore.UNHIDE_CALL, oldFile.getParent(), null);
                } catch (RemoteException e) {
                    Log.e(TAG, "failed to unhide/rescan for " + newPath);
                }
            }
        }

        return MtpConstants.RESPONSE_OK;
    }

    private int setObjectProperty(int handle, int property,
                            long intValue, String stringValue) {
        switch (property) {
            case MtpConstants.PROPERTY_OBJECT_FILE_NAME:
                return renameFile(handle, stringValue);

            default:
                return MtpConstants.RESPONSE_OBJECT_PROP_NOT_SUPPORTED;
        }
    }

    private int getDeviceProperty(int property, long[] outIntValue, char[] outStringValue) {
        switch (property) {
            case MtpConstants.DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER:
            case MtpConstants.DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME:
                // writable string properties kept in shared preferences
                String value = mDeviceProperties.getString(Integer.toString(property), "");
                int length = value.length();
                if (length > 255) {
                    length = 255;
                }
                value.getChars(0, length, outStringValue, 0);
                outStringValue[length] = 0;
                return MtpConstants.RESPONSE_OK;

            case MtpConstants.DEVICE_PROPERTY_IMAGE_SIZE:
                // use screen size as max image size
                Display display = ((WindowManager)mContext.getSystemService(
                        Context.WINDOW_SERVICE)).getDefaultDisplay();
                int width = display.getMaximumSizeDimension();
                int height = display.getMaximumSizeDimension();
                String imageSize = Integer.toString(width) + "x" +  Integer.toString(height);
                imageSize.getChars(0, imageSize.length(), outStringValue, 0);
                outStringValue[imageSize.length()] = 0;
                return MtpConstants.RESPONSE_OK;

            case MtpConstants.DEVICE_PROPERTY_PERCEIVED_DEVICE_TYPE:
                outIntValue[0] = mDeviceType;
                return MtpConstants.RESPONSE_OK;

            // DEVICE_PROPERTY_BATTERY_LEVEL is implemented in the JNI code

            default:
                return MtpConstants.RESPONSE_DEVICE_PROP_NOT_SUPPORTED;
        }
    }

    private int setDeviceProperty(int property, long intValue, String stringValue) {
        switch (property) {
            case MtpConstants.DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER:
            case MtpConstants.DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME:
                // writable string properties kept in shared prefs
                SharedPreferences.Editor e = mDeviceProperties.edit();
                e.putString(Integer.toString(property), stringValue);
                return (e.commit() ? MtpConstants.RESPONSE_OK
                        : MtpConstants.RESPONSE_GENERAL_ERROR);
        }

        return MtpConstants.RESPONSE_DEVICE_PROP_NOT_SUPPORTED;
    }

    private boolean getObjectInfo(int handle, int[] outStorageFormatParent,
                        char[] outName, long[] outCreatedModified) {
        Cursor c = null;
        try {
            c = mMediaProvider.query(mObjectsUri, OBJECT_INFO_PROJECTION,
                            ID_WHERE, new String[] {  Integer.toString(handle) }, null, null);
            if (c != null && c.moveToNext()) {
                outStorageFormatParent[0] = c.getInt(1);
                outStorageFormatParent[1] = c.getInt(2);
                outStorageFormatParent[2] = c.getInt(3);

                // extract name from path
                String path = c.getString(4);
                int lastSlash = path.lastIndexOf('/');
                int start = (lastSlash >= 0 ? lastSlash + 1 : 0);
                int end = path.length();
                if (end - start > 255) {
                    end = start + 255;
                }
                path.getChars(start, end, outName, 0);
                outName[end - start] = 0;

                outCreatedModified[0] = c.getLong(5);
                outCreatedModified[1] = c.getLong(6);
                // use modification date as creation date if date added is not set
                if (outCreatedModified[0] == 0) {
                    outCreatedModified[0] = outCreatedModified[1];
                }
                return true;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getObjectInfo", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return false;
    }

    private int getObjectFilePath(int handle, char[] outFilePath, long[] outFileLengthFormat) {
        if (handle == 0) {
            // special case root directory
            mMediaStoragePath.getChars(0, mMediaStoragePath.length(), outFilePath, 0);
            outFilePath[mMediaStoragePath.length()] = 0;
            outFileLengthFormat[0] = 0;
            outFileLengthFormat[1] = MtpConstants.FORMAT_ASSOCIATION;
            return MtpConstants.RESPONSE_OK;
        }
        Cursor c = null;
        try {
            c = mMediaProvider.query(mObjectsUri, PATH_FORMAT_PROJECTION,
                            ID_WHERE, new String[] {  Integer.toString(handle) }, null, null);
            if (c != null && c.moveToNext()) {
                String path = c.getString(1);
                path.getChars(0, path.length(), outFilePath, 0);
                outFilePath[path.length()] = 0;
                // File transfers from device to host will likely fail if the size is incorrect.
                // So to be safe, use the actual file size here.
                outFileLengthFormat[0] = new File(path).length();
                outFileLengthFormat[1] = c.getLong(2);
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

    private int getObjectFormat(int handle) {
        Cursor c = null;
        try {
            c = mMediaProvider.query(mObjectsUri, FORMAT_PROJECTION,
                            ID_WHERE, new String[] { Integer.toString(handle) }, null, null);
            if (c != null && c.moveToNext()) {
                return c.getInt(1);
            } else {
                return -1;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getObjectFilePath", e);
            return -1;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private int deleteFile(int handle) {
        mDatabaseModified = true;
        String path = null;
        int format = 0;

        Cursor c = null;
        try {
            c = mMediaProvider.query(mObjectsUri, PATH_FORMAT_PROJECTION,
                            ID_WHERE, new String[] {  Integer.toString(handle) }, null, null);
            if (c != null && c.moveToNext()) {
                // don't convert to media path here, since we will be matching
                // against paths in the database matching /data/media
                path = c.getString(1);
                format = c.getInt(2);
            } else {
                return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
            }

            if (path == null || format == 0) {
                return MtpConstants.RESPONSE_GENERAL_ERROR;
            }

            // do not allow deleting any of the special subdirectories
            if (isStorageSubDirectory(path)) {
                return MtpConstants.RESPONSE_OBJECT_WRITE_PROTECTED;
            }

            if (format == MtpConstants.FORMAT_ASSOCIATION) {
                // recursive case - delete all children first
                Uri uri = Files.getMtpObjectsUri(mVolumeName);
                int count = mMediaProvider.delete(uri,
                    // the 'like' makes it use the index, the 'lower()' makes it correct
                    // when the path contains sqlite wildcard characters
                    "_data LIKE ?1 AND lower(substr(_data,1,?2))=lower(?3)",
                    new String[] { path + "/%",Integer.toString(path.length() + 1), path + "/"});
            }

            Uri uri = Files.getMtpObjectsUri(mVolumeName, handle);
            if (mMediaProvider.delete(uri, null, null) > 0) {
                if (format != MtpConstants.FORMAT_ASSOCIATION
                        && path.toLowerCase(Locale.US).endsWith("/.nomedia")) {
                    try {
                        String parentPath = path.substring(0, path.lastIndexOf("/"));
                        mMediaProvider.call(MediaStore.UNHIDE_CALL, parentPath, null);
                    } catch (RemoteException e) {
                        Log.e(TAG, "failed to unhide/rescan for " + path);
                    }
                }
                return MtpConstants.RESPONSE_OK;
            } else {
                return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in deleteFile", e);
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private int[] getObjectReferences(int handle) {
        Uri uri = Files.getMtpReferencesUri(mVolumeName, handle);
        Cursor c = null;
        try {
            c = mMediaProvider.query(uri, ID_PROJECTION, null, null, null, null);
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
            if (mMediaProvider.bulkInsert(uri, valuesList) > 0) {
                return MtpConstants.RESPONSE_OK;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setObjectReferences", e);
        }
        return MtpConstants.RESPONSE_GENERAL_ERROR;
    }

    private void sessionStarted() {
        mDatabaseModified = false;
    }

    private void sessionEnded() {
        if (mDatabaseModified) {
            mUserContext.sendBroadcast(new Intent(MediaStore.ACTION_MTP_SESSION_END));
            mDatabaseModified = false;
        }
    }

    // used by the JNI code
    private long mNativeContext;

    private native final void native_setup();
    private native final void native_finalize();
}
