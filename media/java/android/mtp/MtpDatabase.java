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
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Files;
import android.provider.MediaStore.MediaColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import dalvik.system.CloseGuard;

import com.google.android.collect.Sets;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * MtpDatabase provides an interface for MTP operations that MtpServer can use. To do this, it uses
 * MtpStorageManager for filesystem operations and MediaProvider to get media metadata. File
 * operations are also reflected in MediaProvider if possible.
 * operations
 * {@hide}
 */
public class MtpDatabase implements AutoCloseable {
    private static final String TAG = MtpDatabase.class.getSimpleName();

    private final Context mContext;
    private final ContentProviderClient mMediaProvider;
    private final String mVolumeName;
    private final Uri mObjectsUri;
    private final MediaScanner mMediaScanner;

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final HashMap<String, MtpStorage> mStorageMap = new HashMap<>();

    // cached property groups for single properties
    private final HashMap<Integer, MtpPropertyGroup> mPropertyGroupsByProperty = new HashMap<>();

    // cached property groups for all properties for a given format
    private final HashMap<Integer, MtpPropertyGroup> mPropertyGroupsByFormat = new HashMap<>();

    // SharedPreferences for writable MTP device properties
    private SharedPreferences mDeviceProperties;

    // Cached device properties
    private int mBatteryLevel;
    private int mBatteryScale;
    private int mDeviceType;

    private MtpServer mServer;
    private MtpStorageManager mManager;

    private static final String PATH_WHERE = Files.FileColumns.DATA + "=?";
    private static final String[] ID_PROJECTION = new String[] {Files.FileColumns._ID};
    private static final String[] PATH_PROJECTION = new String[] {Files.FileColumns.DATA};
    private static final String NO_MEDIA = ".nomedia";

    static {
        System.loadLibrary("media_jni");
    }

    private static final int[] PLAYBACK_FORMATS = {
            // allow transferring arbitrary files
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

    private static final int[] FILE_PROPERTIES = {
            MtpConstants.PROPERTY_STORAGE_ID,
            MtpConstants.PROPERTY_OBJECT_FORMAT,
            MtpConstants.PROPERTY_PROTECTION_STATUS,
            MtpConstants.PROPERTY_OBJECT_SIZE,
            MtpConstants.PROPERTY_OBJECT_FILE_NAME,
            MtpConstants.PROPERTY_DATE_MODIFIED,
            MtpConstants.PROPERTY_PERSISTENT_UID,
            MtpConstants.PROPERTY_PARENT_OBJECT,
            MtpConstants.PROPERTY_NAME,
            MtpConstants.PROPERTY_DISPLAY_NAME,
            MtpConstants.PROPERTY_DATE_ADDED,
    };

    private static final int[] AUDIO_PROPERTIES = {
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

    private static final int[] VIDEO_PROPERTIES = {
            MtpConstants.PROPERTY_ARTIST,
            MtpConstants.PROPERTY_ALBUM_NAME,
            MtpConstants.PROPERTY_DURATION,
            MtpConstants.PROPERTY_DESCRIPTION,
    };

    private static final int[] IMAGE_PROPERTIES = {
            MtpConstants.PROPERTY_DESCRIPTION,
    };

    private static final int[] DEVICE_PROPERTIES = {
            MtpConstants.DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER,
            MtpConstants.DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME,
            MtpConstants.DEVICE_PROPERTY_IMAGE_SIZE,
            MtpConstants.DEVICE_PROPERTY_BATTERY_LEVEL,
            MtpConstants.DEVICE_PROPERTY_PERCEIVED_DEVICE_TYPE,
    };

    private int[] getSupportedObjectProperties(int format) {
        switch (format) {
            case MtpConstants.FORMAT_MP3:
            case MtpConstants.FORMAT_WAV:
            case MtpConstants.FORMAT_WMA:
            case MtpConstants.FORMAT_OGG:
            case MtpConstants.FORMAT_AAC:
                return IntStream.concat(Arrays.stream(FILE_PROPERTIES),
                        Arrays.stream(AUDIO_PROPERTIES)).toArray();
            case MtpConstants.FORMAT_MPEG:
            case MtpConstants.FORMAT_3GP_CONTAINER:
            case MtpConstants.FORMAT_WMV:
                return IntStream.concat(Arrays.stream(FILE_PROPERTIES),
                        Arrays.stream(VIDEO_PROPERTIES)).toArray();
            case MtpConstants.FORMAT_EXIF_JPEG:
            case MtpConstants.FORMAT_GIF:
            case MtpConstants.FORMAT_PNG:
            case MtpConstants.FORMAT_BMP:
            case MtpConstants.FORMAT_DNG:
            case MtpConstants.FORMAT_HEIF:
                return IntStream.concat(Arrays.stream(FILE_PROPERTIES),
                        Arrays.stream(IMAGE_PROPERTIES)).toArray();
            default:
                return FILE_PROPERTIES;
        }
    }

    private int[] getSupportedDeviceProperties() {
        return DEVICE_PROPERTIES;
    }

    private int[] getSupportedPlaybackFormats() {
        return PLAYBACK_FORMATS;
    }

    private int[] getSupportedCaptureFormats() {
        // no capture formats yet
        return null;
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

    public MtpDatabase(Context context, String volumeName,
            String[] subDirectories) {
        native_setup();
        mContext = Objects.requireNonNull(context);
        mMediaProvider = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY);
        mVolumeName = volumeName;
        mObjectsUri = Files.getMtpObjectsUri(volumeName);
        mMediaScanner = new MediaScanner(context, mVolumeName);
        mManager = new MtpStorageManager(new MtpStorageManager.MtpNotifier() {
            @Override
            public void sendObjectAdded(int id) {
                if (MtpDatabase.this.mServer != null)
                    MtpDatabase.this.mServer.sendObjectAdded(id);
            }

            @Override
            public void sendObjectRemoved(int id) {
                if (MtpDatabase.this.mServer != null)
                    MtpDatabase.this.mServer.sendObjectRemoved(id);
            }

            @Override
            public void sendObjectInfoChanged(int id) {
                if (MtpDatabase.this.mServer != null)
                    MtpDatabase.this.mServer.sendObjectInfoChanged(id);
            }
        }, subDirectories == null ? null : Sets.newHashSet(subDirectories));

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

    public Context getContext() {
        return mContext;
    }

    @Override
    public void close() {
        mManager.close();
        mCloseGuard.close();
        if (mClosed.compareAndSet(false, true)) {
            mMediaScanner.close();
            if (mMediaProvider != null) {
                mMediaProvider.close();
            }
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

    public void addStorage(StorageVolume storage) {
        MtpStorage mtpStorage = mManager.addMtpStorage(storage);
        mStorageMap.put(storage.getPath(), mtpStorage);
        if (mServer != null) {
            mServer.addStorage(mtpStorage);
        }
    }

    public void removeStorage(StorageVolume storage) {
        MtpStorage mtpStorage = mStorageMap.get(storage.getPath());
        if (mtpStorage == null) {
            return;
        }
        if (mServer != null) {
            mServer.removeStorage(mtpStorage);
        }
        mManager.removeMtpStorage(mtpStorage);
        mStorageMap.remove(storage.getPath());
    }

    private void initDeviceProperties(Context context) {
        final String devicePropertiesName = "device-properties";
        mDeviceProperties = context.getSharedPreferences(devicePropertiesName,
                Context.MODE_PRIVATE);
        File databaseFile = context.getDatabasePath(devicePropertiesName);

        if (databaseFile.exists()) {
            // for backward compatibility - read device properties from sqlite database
            // and migrate them to shared prefs
            SQLiteDatabase db = null;
            Cursor c = null;
            try {
                db = context.openOrCreateDatabase("device-properties", Context.MODE_PRIVATE, null);
                if (db != null) {
                    c = db.query("properties", new String[]{"_id", "code", "value"},
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

    private int beginSendObject(String path, int format, int parent, int storageId) {
        MtpStorageManager.MtpObject parentObj =
                parent == 0 ? mManager.getStorageRoot(storageId) : mManager.getObject(parent);
        if (parentObj == null) {
            return -1;
        }

        Path objPath = Paths.get(path);
        return mManager.beginSendObject(parentObj, objPath.getFileName().toString(), format);
    }

    private void endSendObject(int handle, boolean succeeded) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null || !mManager.endSendObject(obj, succeeded)) {
            Log.e(TAG, "Failed to successfully end send object");
            return;
        }
        // Add the new file to MediaProvider
        if (succeeded) {
            String path = obj.getPath().toString();
            int format = obj.getFormat();
            // Get parent info from MediaProvider, since the id is different from MTP's
            ContentValues values = new ContentValues();
            values.put(Files.FileColumns.DATA, path);
            values.put(Files.FileColumns.FORMAT, format);
            values.put(Files.FileColumns.SIZE, obj.getSize());
            values.put(Files.FileColumns.DATE_MODIFIED, obj.getModifiedTime());
            try {
                if (obj.getParent().isRoot()) {
                    values.put(Files.FileColumns.PARENT, 0);
                } else {
                    int parentId = findInMedia(obj.getParent().getPath());
                    if (parentId != -1) {
                        values.put(Files.FileColumns.PARENT, parentId);
                    } else {
                        // The parent isn't in MediaProvider. Don't add the new file.
                        return;
                    }
                }

                Uri uri = mMediaProvider.insert(mObjectsUri, values);
                if (uri != null) {
                    rescanFile(path, Integer.parseInt(uri.getPathSegments().get(2)), format);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in beginSendObject", e);
            }
        }
    }

    private void rescanFile(String path, int handle, int format) {
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
                mMediaProvider.insert(
                        Audio.Playlists.EXTERNAL_CONTENT_URI, values);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in endSendObject", e);
            }
        } else {
            mMediaScanner.scanMtpFile(path, handle, format);
        }
    }

    private int[] getObjectList(int storageID, int format, int parent) {
        List<MtpStorageManager.MtpObject> objs = mManager.getObjects(parent,
                format, storageID);
        if (objs == null) {
            return null;
        }
        int[] ret = new int[objs.size()];
        for (int i = 0; i < objs.size(); i++) {
            ret[i] = objs.get(i).getId();
        }
        return ret;
    }

    private int getNumObjects(int storageID, int format, int parent) {
        List<MtpStorageManager.MtpObject> objs = mManager.getObjects(parent,
                format, storageID);
        if (objs == null) {
            return -1;
        }
        return objs.size();
    }

    private MtpPropertyList getObjectPropertyList(int handle, int format, int property,
            int groupCode, int depth) {
        // FIXME - implement group support
        if (property == 0) {
            if (groupCode == 0) {
                return new MtpPropertyList(MtpConstants.RESPONSE_PARAMETER_NOT_SUPPORTED);
            }
            return new MtpPropertyList(MtpConstants.RESPONSE_SPECIFICATION_BY_GROUP_UNSUPPORTED);
        }
        if (depth == 0xFFFFFFFF && (handle == 0 || handle == 0xFFFFFFFF)) {
            // request all objects starting at root
            handle = 0xFFFFFFFF;
            depth = 0;
        }
        if (!(depth == 0 || depth == 1)) {
            // we only support depth 0 and 1
            // depth 0: single object, depth 1: immediate children
            return new MtpPropertyList(MtpConstants.RESPONSE_SPECIFICATION_BY_DEPTH_UNSUPPORTED);
        }
        List<MtpStorageManager.MtpObject> objs = null;
        MtpStorageManager.MtpObject thisObj = null;
        if (handle == 0xFFFFFFFF) {
            // All objects are requested
            objs = mManager.getObjects(0, format, 0xFFFFFFFF);
            if (objs == null) {
                return new MtpPropertyList(MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE);
            }
        } else if (handle != 0) {
            // Add the requested object if format matches
            MtpStorageManager.MtpObject obj = mManager.getObject(handle);
            if (obj == null) {
                return new MtpPropertyList(MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE);
            }
            if (obj.getFormat() == format || format == 0) {
                thisObj = obj;
            }
        }
        if (handle == 0 || depth == 1) {
            if (handle == 0) {
                handle = 0xFFFFFFFF;
            }
            // Get the direct children of root or this object.
            objs = mManager.getObjects(handle, format,
                    0xFFFFFFFF);
            if (objs == null) {
                return new MtpPropertyList(MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE);
            }
        }
        if (objs == null) {
            objs = new ArrayList<>();
        }
        if (thisObj != null) {
            objs.add(thisObj);
        }

        MtpPropertyList ret = new MtpPropertyList(MtpConstants.RESPONSE_OK);
        MtpPropertyGroup propertyGroup;
        for (MtpStorageManager.MtpObject obj : objs) {
            if (property == 0xffffffff) {
                if (format == 0 && handle != 0 && handle != 0xffffffff) {
                    // return properties based on the object's format
                    format = obj.getFormat();
                }
                // Get all properties supported by this object
                // format should be the same between get & put
                propertyGroup = mPropertyGroupsByFormat.get(format);
                if (propertyGroup == null) {
                    int[] propertyList = getSupportedObjectProperties(format);
                    propertyGroup = new MtpPropertyGroup(mMediaProvider, mVolumeName,
                            propertyList);
                    mPropertyGroupsByFormat.put(format, propertyGroup);
                }
            } else {
                // Get this property value
                final int[] propertyList = new int[]{property};
                propertyGroup = mPropertyGroupsByProperty.get(property);
                if (propertyGroup == null) {
                    propertyGroup = new MtpPropertyGroup(mMediaProvider, mVolumeName,
                            propertyList);
                    mPropertyGroupsByProperty.put(property, propertyGroup);
                }
            }
            int err = propertyGroup.getPropertyList(obj, ret);
            if (err != MtpConstants.RESPONSE_OK) {
                return new MtpPropertyList(err);
            }
        }
        return ret;
    }

    private int renameFile(int handle, String newName) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null) {
            return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
        }
        Path oldPath = obj.getPath();

        // now rename the file.  make sure this succeeds before updating database
        if (!mManager.beginRenameObject(obj, newName))
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        Path newPath = obj.getPath();
        boolean success = oldPath.toFile().renameTo(newPath.toFile());
        try {
            Os.access(oldPath.toString(), OsConstants.F_OK);
            Os.access(newPath.toString(), OsConstants.F_OK);
        } catch (ErrnoException e) {
            // Ignore. Could fail if the metadata was already updated.
        }

        if (!mManager.endRenameObject(obj, oldPath.getFileName().toString(), success)) {
            Log.e(TAG, "Failed to end rename object");
        }
        if (!success) {
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        }

        // finally update MediaProvider
        ContentValues values = new ContentValues();
        values.put(Files.FileColumns.DATA, newPath.toString());
        String[] whereArgs = new String[]{oldPath.toString()};
        try {
            // note - we are relying on a special case in MediaProvider.update() to update
            // the paths for all children in the case where this is a directory.
            mMediaProvider.update(mObjectsUri, values, PATH_WHERE, whereArgs);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in mMediaProvider.update", e);
        }

        // check if nomedia status changed
        if (obj.isDir()) {
            // for directories, check if renamed from something hidden to something non-hidden
            if (oldPath.getFileName().startsWith(".") && !newPath.startsWith(".")) {
                // directory was unhidden
                try {
                    mMediaProvider.call(MediaStore.UNHIDE_CALL, newPath.toString(), null);
                } catch (RemoteException e) {
                    Log.e(TAG, "failed to unhide/rescan for " + newPath);
                }
            }
        } else {
            // for files, check if renamed from .nomedia to something else
            if (oldPath.getFileName().toString().toLowerCase(Locale.US).equals(NO_MEDIA)
                    && !newPath.getFileName().toString().toLowerCase(Locale.US).equals(NO_MEDIA)) {
                try {
                    mMediaProvider.call(MediaStore.UNHIDE_CALL,
                            oldPath.getParent().toString(), null);
                } catch (RemoteException e) {
                    Log.e(TAG, "failed to unhide/rescan for " + newPath);
                }
            }
        }
        return MtpConstants.RESPONSE_OK;
    }

    private int beginMoveObject(int handle, int newParent, int newStorage) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        MtpStorageManager.MtpObject parent = newParent == 0 ?
                mManager.getStorageRoot(newStorage) : mManager.getObject(newParent);
        if (obj == null || parent == null)
            return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;

        boolean allowed = mManager.beginMoveObject(obj, parent);
        return allowed ? MtpConstants.RESPONSE_OK : MtpConstants.RESPONSE_GENERAL_ERROR;
    }

    private void endMoveObject(int oldParent, int newParent, int oldStorage, int newStorage,
            int objId, boolean success) {
        MtpStorageManager.MtpObject oldParentObj = oldParent == 0 ?
                mManager.getStorageRoot(oldStorage) : mManager.getObject(oldParent);
        MtpStorageManager.MtpObject newParentObj = newParent == 0 ?
                mManager.getStorageRoot(newStorage) : mManager.getObject(newParent);
        MtpStorageManager.MtpObject obj = mManager.getObject(objId);
        String name = obj.getName();
        if (newParentObj == null || oldParentObj == null
                ||!mManager.endMoveObject(oldParentObj, newParentObj, name, success)) {
            Log.e(TAG, "Failed to end move object");
            return;
        }

        obj = mManager.getObject(objId);
        if (!success || obj == null)
            return;
        // Get parent info from MediaProvider, since the id is different from MTP's
        ContentValues values = new ContentValues();
        Path path = newParentObj.getPath().resolve(name);
        Path oldPath = oldParentObj.getPath().resolve(name);
        values.put(Files.FileColumns.DATA, path.toString());
        if (obj.getParent().isRoot()) {
            values.put(Files.FileColumns.PARENT, 0);
        } else {
            int parentId = findInMedia(path.getParent());
            if (parentId != -1) {
                values.put(Files.FileColumns.PARENT, parentId);
            } else {
                // The new parent isn't in MediaProvider, so delete the object instead
                deleteFromMedia(oldPath, obj.isDir());
                return;
            }
        }
        // update MediaProvider
        Cursor c = null;
        String[] whereArgs = new String[]{oldPath.toString()};
        try {
            int parentId = -1;
            if (!oldParentObj.isRoot()) {
                parentId = findInMedia(oldPath.getParent());
            }
            if (oldParentObj.isRoot() || parentId != -1) {
                // Old parent exists in MediaProvider - perform a move
                // note - we are relying on a special case in MediaProvider.update() to update
                // the paths for all children in the case where this is a directory.
                mMediaProvider.update(mObjectsUri, values, PATH_WHERE, whereArgs);
            } else {
                // Old parent doesn't exist - add the object
                values.put(Files.FileColumns.FORMAT, obj.getFormat());
                values.put(Files.FileColumns.SIZE, obj.getSize());
                values.put(Files.FileColumns.DATE_MODIFIED, obj.getModifiedTime());
                Uri uri = mMediaProvider.insert(mObjectsUri, values);
                if (uri != null) {
                    rescanFile(path.toString(),
                            Integer.parseInt(uri.getPathSegments().get(2)), obj.getFormat());
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in mMediaProvider.update", e);
        }
    }

    private int beginCopyObject(int handle, int newParent, int newStorage) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        MtpStorageManager.MtpObject parent = newParent == 0 ?
                mManager.getStorageRoot(newStorage) : mManager.getObject(newParent);
        if (obj == null || parent == null)
            return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
        return mManager.beginCopyObject(obj, parent);
    }

    private void endCopyObject(int handle, boolean success) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null || !mManager.endCopyObject(obj, success)) {
            Log.e(TAG, "Failed to end copy object");
            return;
        }
        if (!success) {
            return;
        }
        String path = obj.getPath().toString();
        int format = obj.getFormat();
        // Get parent info from MediaProvider, since the id is different from MTP's
        ContentValues values = new ContentValues();
        values.put(Files.FileColumns.DATA, path);
        values.put(Files.FileColumns.FORMAT, format);
        values.put(Files.FileColumns.SIZE, obj.getSize());
        values.put(Files.FileColumns.DATE_MODIFIED, obj.getModifiedTime());
        try {
            if (obj.getParent().isRoot()) {
                values.put(Files.FileColumns.PARENT, 0);
            } else {
                int parentId = findInMedia(obj.getParent().getPath());
                if (parentId != -1) {
                    values.put(Files.FileColumns.PARENT, parentId);
                } else {
                    // The parent isn't in MediaProvider. Don't add the new file.
                    return;
                }
            }
            if (obj.isDir()) {
                mMediaScanner.scanDirectories(new String[]{path});
            } else {
                Uri uri = mMediaProvider.insert(mObjectsUri, values);
                if (uri != null) {
                    rescanFile(path, Integer.parseInt(uri.getPathSegments().get(2)), format);
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in beginSendObject", e);
        }
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
                Display display = ((WindowManager) mContext.getSystemService(
                        Context.WINDOW_SERVICE)).getDefaultDisplay();
                int width = display.getMaximumSizeDimension();
                int height = display.getMaximumSizeDimension();
                String imageSize = Integer.toString(width) + "x" + Integer.toString(height);
                imageSize.getChars(0, imageSize.length(), outStringValue, 0);
                outStringValue[imageSize.length()] = 0;
                return MtpConstants.RESPONSE_OK;
            case MtpConstants.DEVICE_PROPERTY_PERCEIVED_DEVICE_TYPE:
                outIntValue[0] = mDeviceType;
                return MtpConstants.RESPONSE_OK;
            case MtpConstants.DEVICE_PROPERTY_BATTERY_LEVEL:
                outIntValue[0] = mBatteryLevel;
                outIntValue[1] = mBatteryScale;
                return MtpConstants.RESPONSE_OK;
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
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null) {
            return false;
        }
        outStorageFormatParent[0] = obj.getStorageId();
        outStorageFormatParent[1] = obj.getFormat();
        outStorageFormatParent[2] = obj.getParent().isRoot() ? 0 : obj.getParent().getId();

        int nameLen = Integer.min(obj.getName().length(), 255);
        obj.getName().getChars(0, nameLen, outName, 0);
        outName[nameLen] = 0;

        outCreatedModified[0] = obj.getModifiedTime();
        outCreatedModified[1] = obj.getModifiedTime();
        return true;
    }

    private int getObjectFilePath(int handle, char[] outFilePath, long[] outFileLengthFormat) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null) {
            return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
        }

        String path = obj.getPath().toString();
        int pathLen = Integer.min(path.length(), 4096);
        path.getChars(0, pathLen, outFilePath, 0);
        outFilePath[pathLen] = 0;

        outFileLengthFormat[0] = obj.getSize();
        outFileLengthFormat[1] = obj.getFormat();
        return MtpConstants.RESPONSE_OK;
    }

    private int getObjectFormat(int handle) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null) {
            return -1;
        }
        return obj.getFormat();
    }

    private int beginDeleteObject(int handle) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null) {
            return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
        }
        if (!mManager.beginRemoveObject(obj)) {
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        }
        return MtpConstants.RESPONSE_OK;
    }

    private void endDeleteObject(int handle, boolean success) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null) {
            return;
        }
        if (!mManager.endRemoveObject(obj, success))
            Log.e(TAG, "Failed to end remove object");
        if (success)
            deleteFromMedia(obj.getPath(), obj.isDir());
    }

    private int findInMedia(Path path) {
        int ret = -1;
        Cursor c = null;
        try {
            c = mMediaProvider.query(mObjectsUri, ID_PROJECTION, PATH_WHERE,
                    new String[]{path.toString()}, null, null);
            if (c != null && c.moveToNext()) {
                ret = c.getInt(0);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error finding " + path + " in MediaProvider");
        } finally {
            if (c != null)
                c.close();
        }
        return ret;
    }

    private void deleteFromMedia(Path path, boolean isDir) {
        try {
            // Delete the object(s) from MediaProvider, but ignore errors.
            if (isDir) {
                // recursive case - delete all children first
                mMediaProvider.delete(mObjectsUri,
                        // the 'like' makes it use the index, the 'lower()' makes it correct
                        // when the path contains sqlite wildcard characters
                        "_data LIKE ?1 AND lower(substr(_data,1,?2))=lower(?3)",
                        new String[]{path + "/%", Integer.toString(path.toString().length() + 1),
                                path.toString() + "/"});
            }

            String[] whereArgs = new String[]{path.toString()};
            if (mMediaProvider.delete(mObjectsUri, PATH_WHERE, whereArgs) > 0) {
                if (!isDir && path.toString().toLowerCase(Locale.US).endsWith(NO_MEDIA)) {
                    try {
                        String parentPath = path.getParent().toString();
                        mMediaProvider.call(MediaStore.UNHIDE_CALL, parentPath, null);
                    } catch (RemoteException e) {
                        Log.e(TAG, "failed to unhide/rescan for " + path);
                    }
                }
            } else {
                Log.i(TAG, "Mediaprovider didn't delete " + path);
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to delete " + path + " from MediaProvider");
        }
    }

    private int[] getObjectReferences(int handle) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null)
            return null;
        // Translate this handle to the MediaProvider Handle
        handle = findInMedia(obj.getPath());
        if (handle == -1)
            return null;
        Uri uri = Files.getMtpReferencesUri(mVolumeName, handle);
        Cursor c = null;
        try {
            c = mMediaProvider.query(uri, PATH_PROJECTION, null, null, null, null);
            if (c == null) {
                return null;
            }
                ArrayList<Integer> result = new ArrayList<>();
                while (c.moveToNext()) {
                    // Translate result handles back into handles for this session.
                    String refPath = c.getString(0);
                    MtpStorageManager.MtpObject refObj = mManager.getByPath(refPath);
                    if (refObj != null) {
                        result.add(refObj.getId());
                    }
                }
                return result.stream().mapToInt(Integer::intValue).toArray();
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
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null)
            return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
        // Translate this handle to the MediaProvider Handle
        handle = findInMedia(obj.getPath());
        if (handle == -1)
            return MtpConstants.RESPONSE_GENERAL_ERROR;
        Uri uri = Files.getMtpReferencesUri(mVolumeName, handle);
        ArrayList<ContentValues> valuesList = new ArrayList<>();
        for (int id : references) {
            // Translate each reference id to the MediaProvider Id
            MtpStorageManager.MtpObject refObj = mManager.getObject(id);
            if (refObj == null)
                continue;
            int refHandle = findInMedia(refObj.getPath());
            if (refHandle == -1)
                continue;
            ContentValues values = new ContentValues();
            values.put(Files.FileColumns._ID, refHandle);
            valuesList.add(values);
        }
        try {
            if (mMediaProvider.bulkInsert(uri, valuesList.toArray(new ContentValues[0])) > 0) {
                return MtpConstants.RESPONSE_OK;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setObjectReferences", e);
        }
        return MtpConstants.RESPONSE_GENERAL_ERROR;
    }

    // used by the JNI code
    private long mNativeContext;

    private native final void native_setup();
    private native final void native_finalize();
}
