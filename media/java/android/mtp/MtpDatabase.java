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

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.media.ApplicationMediaCapabilities;
import android.media.ExifInterface;
import android.media.MediaFormat;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.provider.MediaStore.Files;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForNative;
import com.android.internal.annotations.VisibleForTesting;

import dalvik.system.CloseGuard;

import com.google.android.collect.Sets;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
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
    private static final int MAX_THUMB_SIZE = (200 * 1024);

    private final Context mContext;
    private final ContentProviderClient mMediaProvider;

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final HashMap<String, MtpStorage> mStorageMap = new HashMap<>();

    // cached property groups for single properties
    private final SparseArray<MtpPropertyGroup> mPropertyGroupsByProperty = new SparseArray<>();

    // cached property groups for all properties for a given format
    private final SparseArray<MtpPropertyGroup> mPropertyGroupsByFormat = new SparseArray<>();

    // SharedPreferences for writable MTP device properties
    private SharedPreferences mDeviceProperties;

    // Cached device properties
    private int mBatteryLevel;
    private int mBatteryScale;
    private int mDeviceType;
    private String mHostType;
    private boolean mSkipThumbForHost = false;
    private volatile boolean mHostIsWindows = false;

    private MtpServer mServer;
    private MtpStorageManager mManager;

    private static final String PATH_WHERE = Files.FileColumns.DATA + "=?";
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
            MtpConstants.DEVICE_PROPERTY_SESSION_INITIATOR_VERSION_INFO,
    };

    @VisibleForNative
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

    public static Uri getObjectPropertiesUri(int format, String volumeName) {
        switch (format) {
            case MtpConstants.FORMAT_MP3:
            case MtpConstants.FORMAT_WAV:
            case MtpConstants.FORMAT_WMA:
            case MtpConstants.FORMAT_OGG:
            case MtpConstants.FORMAT_AAC:
                return MediaStore.Audio.Media.getContentUri(volumeName);
            case MtpConstants.FORMAT_MPEG:
            case MtpConstants.FORMAT_3GP_CONTAINER:
            case MtpConstants.FORMAT_WMV:
                return MediaStore.Video.Media.getContentUri(volumeName);
            case MtpConstants.FORMAT_EXIF_JPEG:
            case MtpConstants.FORMAT_GIF:
            case MtpConstants.FORMAT_PNG:
            case MtpConstants.FORMAT_BMP:
            case MtpConstants.FORMAT_DNG:
            case MtpConstants.FORMAT_HEIF:
                return MediaStore.Images.Media.getContentUri(volumeName);
            default:
                return MediaStore.Files.getContentUri(volumeName);
        }
    }

    @VisibleForNative
    private int[] getSupportedDeviceProperties() {
        return DEVICE_PROPERTIES;
    }

    @VisibleForNative
    private int[] getSupportedPlaybackFormats() {
        return PLAYBACK_FORMATS;
    }

    @VisibleForNative
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
                    synchronized(MtpDatabase.this){
                        if (mServer != null) {
                            // send device property changed event
                            mServer.sendDevicePropertyChanged(
                                    MtpConstants.DEVICE_PROPERTY_BATTERY_LEVEL);
                        }
                    }
                }
            }
        }
    };

    public MtpDatabase(Context context, String[] subDirectories) {
        native_setup();
        mContext = Objects.requireNonNull(context);
        mMediaProvider = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY);
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

    public synchronized void setServer(MtpServer server) {
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
        MtpStorage mtpStorage = mManager.addMtpStorage(storage, () -> mHostIsWindows);
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
        mHostType = "";
        mSkipThumbForHost = false;
        mHostIsWindows = false;
    }

    @VisibleForNative
    @VisibleForTesting
    public int beginSendObject(String path, int format, int parent, int storageId) {
        MtpStorageManager.MtpObject parentObj =
                parent == 0 ? mManager.getStorageRoot(storageId) : mManager.getObject(parent);
        if (parentObj == null) {
            return -1;
        }

        Path objPath = Paths.get(path);
        return mManager.beginSendObject(parentObj, objPath.getFileName().toString(), format);
    }

    @VisibleForNative
    private void endSendObject(int handle, boolean succeeded) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null || !mManager.endSendObject(obj, succeeded)) {
            Log.e(TAG, "Failed to successfully end send object");
            return;
        }
        // Add the new file to MediaProvider
        if (succeeded) {
            updateMediaStore(mContext, obj.getPath().toFile());
        }
    }

    @VisibleForNative
    private void rescanFile(String path, int handle, int format) {
        MediaStore.scanFile(mContext.getContentResolver(), new File(path));
    }

    @VisibleForNative
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

    @VisibleForNative
    @VisibleForTesting
    public int getNumObjects(int storageID, int format, int parent) {
        List<MtpStorageManager.MtpObject> objs = mManager.getObjects(parent,
                format, storageID);
        if (objs == null) {
            return -1;
        }
        return objs.size();
    }

    @VisibleForNative
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
                    final int[] propertyList = getSupportedObjectProperties(format);
                    propertyGroup = new MtpPropertyGroup(propertyList);
                    mPropertyGroupsByFormat.put(format, propertyGroup);
                }
            } else {
                // Get this property value
                propertyGroup = mPropertyGroupsByProperty.get(property);
                if (propertyGroup == null) {
                    final int[] propertyList = new int[]{property};
                    propertyGroup = new MtpPropertyGroup(propertyList);
                    mPropertyGroupsByProperty.put(property, propertyGroup);
                }
            }
            int err = propertyGroup.getPropertyList(mMediaProvider, obj.getVolumeName(), obj, ret);
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

        updateMediaStore(mContext, oldPath.toFile());
        updateMediaStore(mContext, newPath.toFile());
        return MtpConstants.RESPONSE_OK;
    }

    @VisibleForNative
    private int beginMoveObject(int handle, int newParent, int newStorage) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        MtpStorageManager.MtpObject parent = newParent == 0 ?
                mManager.getStorageRoot(newStorage) : mManager.getObject(newParent);
        if (obj == null || parent == null)
            return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;

        boolean allowed = mManager.beginMoveObject(obj, parent);
        return allowed ? MtpConstants.RESPONSE_OK : MtpConstants.RESPONSE_GENERAL_ERROR;
    }

    @VisibleForNative
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

        Path path = newParentObj.getPath().resolve(name);
        Path oldPath = oldParentObj.getPath().resolve(name);

        updateMediaStore(mContext, oldPath.toFile());
        updateMediaStore(mContext, path.toFile());
    }

    @VisibleForNative
    private int beginCopyObject(int handle, int newParent, int newStorage) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        MtpStorageManager.MtpObject parent = newParent == 0 ?
                mManager.getStorageRoot(newStorage) : mManager.getObject(newParent);
        if (obj == null || parent == null)
            return MtpConstants.RESPONSE_INVALID_OBJECT_HANDLE;
        return mManager.beginCopyObject(obj, parent);
    }

    @VisibleForNative
    private void endCopyObject(int handle, boolean success) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null || !mManager.endCopyObject(obj, success)) {
            Log.e(TAG, "Failed to end copy object");
            return;
        }
        if (!success) {
            return;
        }

        updateMediaStore(mContext, obj.getPath().toFile());
    }

    private static void updateMediaStore(@NonNull Context context, @NonNull File file) {
        final ContentResolver resolver = context.getContentResolver();
        // For file, check whether the file name is .nomedia or not.
        // If yes, scan the parent directory to update all files in the directory.
        if (!file.isDirectory() && file.getName().toLowerCase(Locale.ROOT).endsWith(NO_MEDIA)) {
            MediaStore.scanFile(resolver, file.getParentFile());
        } else {
            MediaStore.scanFile(resolver, file);
        }
    }

    @VisibleForNative
    private int setObjectProperty(int handle, int property,
            long intValue, String stringValue) {
        switch (property) {
            case MtpConstants.PROPERTY_OBJECT_FILE_NAME:
                return renameFile(handle, stringValue);

            default:
                return MtpConstants.RESPONSE_OBJECT_PROP_NOT_SUPPORTED;
        }
    }

    @VisibleForNative
    private int getDeviceProperty(int property, long[] outIntValue, char[] outStringValue) {
        int length;
        String value;

        switch (property) {
            case MtpConstants.DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER:
            case MtpConstants.DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME:
                // writable string properties kept in shared preferences
                value = mDeviceProperties.getString(Integer.toString(property), "");
                length = value.length();
                if (length > 255) {
                    length = 255;
                }
                value.getChars(0, length, outStringValue, 0);
                outStringValue[length] = 0;
                return MtpConstants.RESPONSE_OK;
            case MtpConstants.DEVICE_PROPERTY_SESSION_INITIATOR_VERSION_INFO:
                value = mHostType;
                length = value.length();
                if (length > 255) {
                    length = 255;
                }
                value.getChars(0, length, outStringValue, 0);
                outStringValue[length] = 0;
                return MtpConstants.RESPONSE_OK;
            case MtpConstants.DEVICE_PROPERTY_IMAGE_SIZE:
                // use screen size as max image size
                // TODO(b/147721765): Add support for foldables/multi-display devices.
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

    @VisibleForNative
    private int setDeviceProperty(int property, long intValue, String stringValue) {
        switch (property) {
            case MtpConstants.DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER:
            case MtpConstants.DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME:
                // writable string properties kept in shared prefs
                SharedPreferences.Editor e = mDeviceProperties.edit();
                e.putString(Integer.toString(property), stringValue);
                return (e.commit() ? MtpConstants.RESPONSE_OK
                        : MtpConstants.RESPONSE_GENERAL_ERROR);
            case MtpConstants.DEVICE_PROPERTY_SESSION_INITIATOR_VERSION_INFO:
                mHostType = stringValue;
                Log.d(TAG, "setDeviceProperty." + Integer.toHexString(property)
                        + "=" + stringValue);
                if (stringValue.startsWith("Android/")) {
                    mSkipThumbForHost = true;
                } else if (stringValue.startsWith("Windows/")) {
                    mHostIsWindows = true;
                }
                return MtpConstants.RESPONSE_OK;
        }

        return MtpConstants.RESPONSE_DEVICE_PROP_NOT_SUPPORTED;
    }

    @VisibleForNative
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

    @VisibleForNative
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

    @VisibleForNative
    private int openFilePath(String path, boolean transcode) {
        Uri uri = MediaStore.scanFile(mContext.getContentResolver(), new File(path));
        if (uri == null) {
            Log.i(TAG, "Failed to obtain URI for openFile with transcode support: " + path);
            return -1;
        }

        try {
            Log.i(TAG, "openFile with transcode support: " + path);
            Bundle bundle = new Bundle();
            if (transcode) {
                bundle.putParcelable(MediaStore.EXTRA_MEDIA_CAPABILITIES,
                        new ApplicationMediaCapabilities.Builder().addUnsupportedVideoMimeType(
                                MediaFormat.MIMETYPE_VIDEO_HEVC).build());
            } else {
                bundle.putParcelable(MediaStore.EXTRA_MEDIA_CAPABILITIES,
                        new ApplicationMediaCapabilities.Builder().addSupportedVideoMimeType(
                                MediaFormat.MIMETYPE_VIDEO_HEVC).build());
            }
            return mMediaProvider.openTypedAssetFileDescriptor(uri, "*/*", bundle)
                    .getParcelFileDescriptor().detachFd();
        } catch (RemoteException | FileNotFoundException e) {
            Log.w(TAG, "Failed to openFile with transcode support: " + path, e);
            return -1;
        }
    }

    private int getObjectFormat(int handle) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null) {
            return -1;
        }
        return obj.getFormat();
    }

    private byte[] getThumbnailProcess(String path, Bitmap bitmap) {
        try {
            if (bitmap == null) {
                Log.d(TAG, "getThumbnailProcess: Fail to generate thumbnail. Probably unsupported or corrupted image");
                return null;
            }

            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteStream);

            if (byteStream.size() > MAX_THUMB_SIZE) {
                Log.w(TAG, "getThumbnailProcess: size=" + byteStream.size());
                return null;
            }

            byte[] byteArray = byteStream.toByteArray();

            return byteArray;
        } catch (OutOfMemoryError oomEx) {
            Log.w(TAG, "OutOfMemoryError:" + oomEx);
        }
        return null;
    }

    @VisibleForNative
    @VisibleForTesting
    public boolean getThumbnailInfo(int handle, long[] outLongs) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null) {
            return false;
        }

        String path = obj.getPath().toString();
        switch (obj.getFormat()) {
            case MtpConstants.FORMAT_HEIF:
            case MtpConstants.FORMAT_EXIF_JPEG:
            case MtpConstants.FORMAT_JFIF:
                try {
                    ExifInterface exif = new ExifInterface(path);
                    long[] thumbOffsetAndSize = exif.getThumbnailRange();
                    outLongs[0] = thumbOffsetAndSize != null ? thumbOffsetAndSize[1] : 0;
                    outLongs[1] = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0);
                    outLongs[2] = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0);
                    if (mSkipThumbForHost) {
                        Log.d(TAG, "getThumbnailInfo: Skip runtime thumbnail.");
                        return true;
                    }
                    if (exif.getThumbnailRange() != null) {
                        if ((outLongs[0] == 0) || (outLongs[1] == 0) || (outLongs[2] == 0)) {
                            Log.d(TAG, "getThumbnailInfo: check thumb info:"
                                    + thumbOffsetAndSize[0] + "," + thumbOffsetAndSize[1]
                                    + "," + outLongs[1] + "," + outLongs[2]);
                        }

                        return true;
                    }
                } catch (IOException e) {
                    // ignore and fall through
                }

// Note: above formats will fall through and go on below thumbnail generation if Exif processing fails
            case MtpConstants.FORMAT_PNG:
            case MtpConstants.FORMAT_GIF:
            case MtpConstants.FORMAT_BMP:
                outLongs[0] = MAX_THUMB_SIZE;
            // only non-zero Width & Height needed. Actual size will be retrieved upon getThumbnailData by Host
                outLongs[1] = 320;
                outLongs[2] = 240;
                return true;
        }
        return false;
    }

    @VisibleForNative
    @VisibleForTesting
    public byte[] getThumbnailData(int handle) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null) {
            return null;
        }

        String path = obj.getPath().toString();
        switch (obj.getFormat()) {
            case MtpConstants.FORMAT_HEIF:
            case MtpConstants.FORMAT_EXIF_JPEG:
            case MtpConstants.FORMAT_JFIF:
                try {
                    ExifInterface exif = new ExifInterface(path);

                    if (mSkipThumbForHost) {
                        Log.d(TAG, "getThumbnailData: Skip runtime thumbnail.");
                        return exif.getThumbnail();
                    }
                    if (exif.getThumbnailRange() != null)
                        return exif.getThumbnail();
                } catch (IOException e) {
                    // ignore and fall through
                }

// Note: above formats will fall through and go on below thumbnail generation if Exif processing fails
            case MtpConstants.FORMAT_PNG:
            case MtpConstants.FORMAT_GIF:
            case MtpConstants.FORMAT_BMP:
                {
                    Bitmap bitmap = ThumbnailUtils.createImageThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND);
                    byte[] byteArray = getThumbnailProcess(path, bitmap);

                    return byteArray;
                }
        }
        return null;
    }

    @VisibleForNative
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

    @VisibleForNative
    private void endDeleteObject(int handle, boolean success) {
        MtpStorageManager.MtpObject obj = mManager.getObject(handle);
        if (obj == null) {
            return;
        }
        if (!mManager.endRemoveObject(obj, success))
            Log.e(TAG, "Failed to end remove object");
        if (success)
            deleteFromMedia(obj, obj.getPath(), obj.isDir());
    }

    private void deleteFromMedia(MtpStorageManager.MtpObject obj, Path path, boolean isDir) {
        final Uri objectsUri = MediaStore.Files.getContentUri(obj.getVolumeName());
        try {
            // Delete the object(s) from MediaProvider, but ignore errors.
            if (isDir) {
                // recursive case - delete all children first
                mMediaProvider.delete(objectsUri,
                        // the 'like' makes it use the index, the 'lower()' makes it correct
                        // when the path contains sqlite wildcard characters
                        "_data LIKE ?1 AND lower(substr(_data,1,?2))=lower(?3)",
                        new String[]{path + "/%", Integer.toString(path.toString().length() + 1),
                                path.toString() + "/"});
            }

            String[] whereArgs = new String[]{path.toString()};
            if (mMediaProvider.delete(objectsUri, PATH_WHERE, whereArgs) == 0) {
                Log.i(TAG, "MediaProvider didn't delete " + path);
            }
            updateMediaStore(mContext, path.toFile());
        } catch (Exception e) {
            Log.d(TAG, "Failed to delete " + path + " from MediaProvider");
        }
    }

    @VisibleForNative
    private int[] getObjectReferences(int handle) {
        return null;
    }

    @VisibleForNative
    private int setObjectReferences(int handle, int[] references) {
        return MtpConstants.RESPONSE_OPERATION_NOT_SUPPORTED;
    }

    @VisibleForNative
    private long mNativeContext;

    private native final void native_setup();
    private native final void native_finalize();
}
