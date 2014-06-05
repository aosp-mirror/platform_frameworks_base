/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.os;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.storage.IMountService;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.collect.Lists;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Provides access to environment variables.
 */
public class Environment {
    private static final String TAG = "Environment";

    private static final String ENV_EXTERNAL_STORAGE = "EXTERNAL_STORAGE";
    private static final String ENV_EMULATED_STORAGE_SOURCE = "EMULATED_STORAGE_SOURCE";
    private static final String ENV_EMULATED_STORAGE_TARGET = "EMULATED_STORAGE_TARGET";
    private static final String ENV_MEDIA_STORAGE = "MEDIA_STORAGE";
    private static final String ENV_SECONDARY_STORAGE = "SECONDARY_STORAGE";
    private static final String ENV_ANDROID_ROOT = "ANDROID_ROOT";
    private static final String ENV_OEM_ROOT = "OEM_ROOT";
    private static final String ENV_VENDOR_ROOT = "VENDOR_ROOT";

    /** {@hide} */
    public static final String DIR_ANDROID = "Android";
    private static final String DIR_DATA = "data";
    private static final String DIR_MEDIA = "media";
    private static final String DIR_OBB = "obb";
    private static final String DIR_FILES = "files";
    private static final String DIR_CACHE = "cache";

    /** {@hide} */
    @Deprecated
    public static final String DIRECTORY_ANDROID = DIR_ANDROID;

    private static final File DIR_ANDROID_ROOT = getDirectory(ENV_ANDROID_ROOT, "/system");
    private static final File DIR_OEM_ROOT = getDirectory(ENV_OEM_ROOT, "/oem");
    private static final File DIR_VENDOR_ROOT = getDirectory(ENV_VENDOR_ROOT, "/vendor");
    private static final File DIR_MEDIA_STORAGE = getDirectory(ENV_MEDIA_STORAGE, "/data/media");

    private static final String CANONCIAL_EMULATED_STORAGE_TARGET = getCanonicalPathOrNull(
            ENV_EMULATED_STORAGE_TARGET);

    private static final String SYSTEM_PROPERTY_EFS_ENABLED = "persist.security.efs.enabled";

    private static UserEnvironment sCurrentUser;
    private static boolean sUserRequired;

    static {
        initForCurrentUser();
    }

    /** {@hide} */
    public static void initForCurrentUser() {
        final int userId = UserHandle.myUserId();
        sCurrentUser = new UserEnvironment(userId);
    }

    /** {@hide} */
    public static class UserEnvironment {
        // TODO: generalize further to create package-specific environment

        /** External storage dirs, as visible to vold */
        private final File[] mExternalDirsForVold;
        /** External storage dirs, as visible to apps */
        private final File[] mExternalDirsForApp;
        /** Primary emulated storage dir for direct access */
        private final File mEmulatedDirForDirect;

        public UserEnvironment(int userId) {
            // See storage config details at http://source.android.com/tech/storage/
            String rawExternalStorage = System.getenv(ENV_EXTERNAL_STORAGE);
            String rawEmulatedSource = System.getenv(ENV_EMULATED_STORAGE_SOURCE);
            String rawEmulatedTarget = System.getenv(ENV_EMULATED_STORAGE_TARGET);

            String rawMediaStorage = System.getenv(ENV_MEDIA_STORAGE);
            if (TextUtils.isEmpty(rawMediaStorage)) {
                rawMediaStorage = "/data/media";
            }

            ArrayList<File> externalForVold = Lists.newArrayList();
            ArrayList<File> externalForApp = Lists.newArrayList();

            if (!TextUtils.isEmpty(rawEmulatedTarget)) {
                // Device has emulated storage; external storage paths should have
                // userId burned into them.
                final String rawUserId = Integer.toString(userId);
                final File emulatedSourceBase = new File(rawEmulatedSource);
                final File emulatedTargetBase = new File(rawEmulatedTarget);
                final File mediaBase = new File(rawMediaStorage);

                // /storage/emulated/0
                externalForVold.add(buildPath(emulatedSourceBase, rawUserId));
                externalForApp.add(buildPath(emulatedTargetBase, rawUserId));
                // /data/media/0
                mEmulatedDirForDirect = buildPath(mediaBase, rawUserId);

            } else {
                // Device has physical external storage; use plain paths.
                if (TextUtils.isEmpty(rawExternalStorage)) {
                    Log.w(TAG, "EXTERNAL_STORAGE undefined; falling back to default");
                    rawExternalStorage = "/storage/sdcard0";
                }

                // /storage/sdcard0
                externalForVold.add(new File(rawExternalStorage));
                externalForApp.add(new File(rawExternalStorage));
                // /data/media
                mEmulatedDirForDirect = new File(rawMediaStorage);
            }

            // Splice in any secondary storage paths, but only for owner
            final String rawSecondaryStorage = System.getenv(ENV_SECONDARY_STORAGE);
            if (!TextUtils.isEmpty(rawSecondaryStorage) && userId == UserHandle.USER_OWNER) {
                for (String secondaryPath : rawSecondaryStorage.split(":")) {
                    externalForVold.add(new File(secondaryPath));
                    externalForApp.add(new File(secondaryPath));
                }
            }

            mExternalDirsForVold = externalForVold.toArray(new File[externalForVold.size()]);
            mExternalDirsForApp = externalForApp.toArray(new File[externalForApp.size()]);
        }

        @Deprecated
        public File getExternalStorageDirectory() {
            return mExternalDirsForApp[0];
        }

        @Deprecated
        public File getExternalStoragePublicDirectory(String type) {
            return buildExternalStoragePublicDirs(type)[0];
        }

        public File[] getExternalDirsForVold() {
            return mExternalDirsForVold;
        }

        public File[] getExternalDirsForApp() {
            return mExternalDirsForApp;
        }

        public File getMediaDir() {
            return mEmulatedDirForDirect;
        }

        public File[] buildExternalStoragePublicDirs(String type) {
            return buildPaths(mExternalDirsForApp, type);
        }

        public File[] buildExternalStorageAndroidDataDirs() {
            return buildPaths(mExternalDirsForApp, DIR_ANDROID, DIR_DATA);
        }

        public File[] buildExternalStorageAndroidObbDirs() {
            return buildPaths(mExternalDirsForApp, DIR_ANDROID, DIR_OBB);
        }

        public File[] buildExternalStorageAppDataDirs(String packageName) {
            return buildPaths(mExternalDirsForApp, DIR_ANDROID, DIR_DATA, packageName);
        }

        public File[] buildExternalStorageAppDataDirsForVold(String packageName) {
            return buildPaths(mExternalDirsForVold, DIR_ANDROID, DIR_DATA, packageName);
        }

        public File[] buildExternalStorageAppMediaDirs(String packageName) {
            return buildPaths(mExternalDirsForApp, DIR_ANDROID, DIR_MEDIA, packageName);
        }

        public File[] buildExternalStorageAppMediaDirsForVold(String packageName) {
            return buildPaths(mExternalDirsForVold, DIR_ANDROID, DIR_MEDIA, packageName);
        }

        public File[] buildExternalStorageAppObbDirs(String packageName) {
            return buildPaths(mExternalDirsForApp, DIR_ANDROID, DIR_OBB, packageName);
        }

        public File[] buildExternalStorageAppObbDirsForVold(String packageName) {
            return buildPaths(mExternalDirsForVold, DIR_ANDROID, DIR_OBB, packageName);
        }

        public File[] buildExternalStorageAppFilesDirs(String packageName) {
            return buildPaths(mExternalDirsForApp, DIR_ANDROID, DIR_DATA, packageName, DIR_FILES);
        }

        public File[] buildExternalStorageAppCacheDirs(String packageName) {
            return buildPaths(mExternalDirsForApp, DIR_ANDROID, DIR_DATA, packageName, DIR_CACHE);
        }
    }

    /**
     * Return root of the "system" partition holding the core Android OS.
     * Always present and mounted read-only.
     */
    public static File getRootDirectory() {
        return DIR_ANDROID_ROOT;
    }

    /**
     * Return root directory of the "oem" partition holding OEM customizations,
     * if any. If present, the partition is mounted read-only.
     *
     * @hide
     */
    public static File getOemDirectory() {
        return DIR_OEM_ROOT;
    }

    /**
     * Return root directory of the "vendor" partition that holds vendor-provided
     * software that should persist across simple reflashing of the "system" partition.
     * @hide
     */
    public static File getVendorDirectory() {
        return DIR_VENDOR_ROOT;
    }

    /**
     * Gets the system directory available for secure storage.
     * If Encrypted File system is enabled, it returns an encrypted directory (/data/secure/system).
     * Otherwise, it returns the unencrypted /data/system directory.
     * @return File object representing the secure storage system directory.
     * @hide
     */
    public static File getSystemSecureDirectory() {
        if (isEncryptedFilesystemEnabled()) {
            return new File(SECURE_DATA_DIRECTORY, "system");
        } else {
            return new File(DATA_DIRECTORY, "system");
        }
    }

    /**
     * Gets the data directory for secure storage.
     * If Encrypted File system is enabled, it returns an encrypted directory (/data/secure).
     * Otherwise, it returns the unencrypted /data directory.
     * @return File object representing the data directory for secure storage.
     * @hide
     */
    public static File getSecureDataDirectory() {
        if (isEncryptedFilesystemEnabled()) {
            return SECURE_DATA_DIRECTORY;
        } else {
            return DATA_DIRECTORY;
        }
    }

    /**
     * Return directory used for internal media storage, which is protected by
     * {@link android.Manifest.permission#WRITE_MEDIA_STORAGE}.
     *
     * @hide
     */
    public static File getMediaStorageDirectory() {
        throwIfUserRequired();
        return sCurrentUser.getMediaDir();
    }

    /**
     * Return the system directory for a user. This is for use by system services to store
     * files relating to the user. This directory will be automatically deleted when the user
     * is removed.
     *
     * @hide
     */
    public static File getUserSystemDirectory(int userId) {
        return new File(new File(getSystemSecureDirectory(), "users"), Integer.toString(userId));
    }

    /**
     * Returns the config directory for a user. This is for use by system services to store files
     * relating to the user which should be readable by any app running as that user.
     *
     * @hide
     */
    public static File getUserConfigDirectory(int userId) {
        return new File(new File(new File(
                getDataDirectory(), "misc"), "user"), Integer.toString(userId));
    }

    /**
     * Returns whether the Encrypted File System feature is enabled on the device or not.
     * @return <code>true</code> if Encrypted File System feature is enabled, <code>false</code>
     * if disabled.
     * @hide
     */
    public static boolean isEncryptedFilesystemEnabled() {
        return SystemProperties.getBoolean(SYSTEM_PROPERTY_EFS_ENABLED, false);
    }

    private static final File DATA_DIRECTORY
            = getDirectory("ANDROID_DATA", "/data");

    /**
     * @hide
     */
    private static final File SECURE_DATA_DIRECTORY
            = getDirectory("ANDROID_SECURE_DATA", "/data/secure");

    private static final File DOWNLOAD_CACHE_DIRECTORY = getDirectory("DOWNLOAD_CACHE", "/cache");

    /**
     * Return the user data directory.
     */
    public static File getDataDirectory() {
        return DATA_DIRECTORY;
    }

    /**
     * Return the primary external storage directory. This directory may not
     * currently be accessible if it has been mounted by the user on their
     * computer, has been removed from the device, or some other problem has
     * happened. You can determine its current state with
     * {@link #getExternalStorageState()}.
     * <p>
     * <em>Note: don't be confused by the word "external" here. This directory
     * can better be thought as media/shared storage. It is a filesystem that
     * can hold a relatively large amount of data and that is shared across all
     * applications (does not enforce permissions). Traditionally this is an SD
     * card, but it may also be implemented as built-in storage in a device that
     * is distinct from the protected internal storage and can be mounted as a
     * filesystem on a computer.</em>
     * <p>
     * On devices with multiple users (as described by {@link UserManager}),
     * each user has their own isolated external storage. Applications only have
     * access to the external storage for the user they're running as.
     * <p>
     * In devices with multiple "external" storage directories, this directory
     * represents the "primary" external storage that the user will interact
     * with. Access to secondary storage is available through
     * <p>
     * Applications should not directly use this top-level directory, in order
     * to avoid polluting the user's root namespace. Any files that are private
     * to the application should be placed in a directory returned by
     * {@link android.content.Context#getExternalFilesDir
     * Context.getExternalFilesDir}, which the system will take care of deleting
     * if the application is uninstalled. Other shared files should be placed in
     * one of the directories returned by
     * {@link #getExternalStoragePublicDirectory}.
     * <p>
     * Writing to this path requires the
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} permission,
     * and starting in read access requires the
     * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} permission,
     * which is automatically granted if you hold the write permission.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#KITKAT}, if your
     * application only needs to store internal data, consider using
     * {@link Context#getExternalFilesDir(String)} or
     * {@link Context#getExternalCacheDir()}, which require no permissions to
     * read or write.
     * <p>
     * This path may change between platform versions, so applications should
     * only persist relative paths.
     * <p>
     * Here is an example of typical code to monitor the state of external
     * storage:
     * <p>
     * {@sample
     * development/samples/ApiDemos/src/com/example/android/apis/content/ExternalStorage.java
     * monitor_storage}
     *
     * @see #getExternalStorageState()
     * @see #isExternalStorageRemovable()
     */
    public static File getExternalStorageDirectory() {
        throwIfUserRequired();
        return sCurrentUser.getExternalDirsForApp()[0];
    }

    /** {@hide} */
    public static File getLegacyExternalStorageDirectory() {
        return new File(System.getenv(ENV_EXTERNAL_STORAGE));
    }

    /** {@hide} */
    public static File getLegacyExternalStorageObbDirectory() {
        return buildPath(getLegacyExternalStorageDirectory(), DIR_ANDROID, DIR_OBB);
    }

    /** {@hide} */
    public static File getEmulatedStorageSource(int userId) {
        // /mnt/shell/emulated/0
        return new File(System.getenv(ENV_EMULATED_STORAGE_SOURCE), String.valueOf(userId));
    }

    /** {@hide} */
    public static File getEmulatedStorageObbSource() {
        // /mnt/shell/emulated/obb
        return new File(System.getenv(ENV_EMULATED_STORAGE_SOURCE), DIR_OBB);
    }

    /**
     * Standard directory in which to place any audio files that should be
     * in the regular list of music for the user.
     * This may be combined with
     * {@link #DIRECTORY_PODCASTS}, {@link #DIRECTORY_NOTIFICATIONS},
     * {@link #DIRECTORY_ALARMS}, and {@link #DIRECTORY_RINGTONES} as a series
     * of directories to categories a particular audio file as more than one
     * type.
     */
    public static String DIRECTORY_MUSIC = "Music";
    
    /**
     * Standard directory in which to place any audio files that should be
     * in the list of podcasts that the user can select (not as regular
     * music).
     * This may be combined with {@link #DIRECTORY_MUSIC},
     * {@link #DIRECTORY_NOTIFICATIONS},
     * {@link #DIRECTORY_ALARMS}, and {@link #DIRECTORY_RINGTONES} as a series
     * of directories to categories a particular audio file as more than one
     * type.
     */
    public static String DIRECTORY_PODCASTS = "Podcasts";
    
    /**
     * Standard directory in which to place any audio files that should be
     * in the list of ringtones that the user can select (not as regular
     * music).
     * This may be combined with {@link #DIRECTORY_MUSIC},
     * {@link #DIRECTORY_PODCASTS}, {@link #DIRECTORY_NOTIFICATIONS}, and
     * {@link #DIRECTORY_ALARMS} as a series
     * of directories to categories a particular audio file as more than one
     * type.
     */
    public static String DIRECTORY_RINGTONES = "Ringtones";
    
    /**
     * Standard directory in which to place any audio files that should be
     * in the list of alarms that the user can select (not as regular
     * music).
     * This may be combined with {@link #DIRECTORY_MUSIC},
     * {@link #DIRECTORY_PODCASTS}, {@link #DIRECTORY_NOTIFICATIONS},
     * and {@link #DIRECTORY_RINGTONES} as a series
     * of directories to categories a particular audio file as more than one
     * type.
     */
    public static String DIRECTORY_ALARMS = "Alarms";
    
    /**
     * Standard directory in which to place any audio files that should be
     * in the list of notifications that the user can select (not as regular
     * music).
     * This may be combined with {@link #DIRECTORY_MUSIC},
     * {@link #DIRECTORY_PODCASTS},
     * {@link #DIRECTORY_ALARMS}, and {@link #DIRECTORY_RINGTONES} as a series
     * of directories to categories a particular audio file as more than one
     * type.
     */
    public static String DIRECTORY_NOTIFICATIONS = "Notifications";
    
    /**
     * Standard directory in which to place pictures that are available to
     * the user.  Note that this is primarily a convention for the top-level
     * public directory, as the media scanner will find and collect pictures
     * in any directory.
     */
    public static String DIRECTORY_PICTURES = "Pictures";
    
    /**
     * Standard directory in which to place movies that are available to
     * the user.  Note that this is primarily a convention for the top-level
     * public directory, as the media scanner will find and collect movies
     * in any directory.
     */
    public static String DIRECTORY_MOVIES = "Movies";
    
    /**
     * Standard directory in which to place files that have been downloaded by
     * the user.  Note that this is primarily a convention for the top-level
     * public directory, you are free to download files anywhere in your own
     * private directories.  Also note that though the constant here is
     * named DIRECTORY_DOWNLOADS (plural), the actual file name is non-plural for
     * backwards compatibility reasons.
     */
    public static String DIRECTORY_DOWNLOADS = "Download";
    
    /**
     * The traditional location for pictures and videos when mounting the
     * device as a camera.  Note that this is primarily a convention for the
     * top-level public directory, as this convention makes no sense elsewhere.
     */
    public static String DIRECTORY_DCIM = "DCIM";

    /**
     * Standard directory in which to place documents that have been created by
     * the user.
     */
    public static String DIRECTORY_DOCUMENTS = "Documents";

    /**
     * Get a top-level public external storage directory for placing files of
     * a particular type.  This is where the user will typically place and
     * manage their own files, so you should be careful about what you put here
     * to ensure you don't erase their files or get in the way of their own
     * organization.
     * 
     * <p>On devices with multiple users (as described by {@link UserManager}),
     * each user has their own isolated external storage. Applications only
     * have access to the external storage for the user they're running as.</p>
     *
     * <p>Here is an example of typical code to manipulate a picture on
     * the public external storage:</p>
     * 
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/content/ExternalStorage.java
     * public_picture}
     * 
     * @param type The type of storage directory to return.  Should be one of
     * {@link #DIRECTORY_MUSIC}, {@link #DIRECTORY_PODCASTS},
     * {@link #DIRECTORY_RINGTONES}, {@link #DIRECTORY_ALARMS},
     * {@link #DIRECTORY_NOTIFICATIONS}, {@link #DIRECTORY_PICTURES},
     * {@link #DIRECTORY_MOVIES}, {@link #DIRECTORY_DOWNLOADS}, or
     * {@link #DIRECTORY_DCIM}.  May not be null.
     * 
     * @return Returns the File path for the directory.  Note that this
     * directory may not yet exist, so you must make sure it exists before
     * using it such as with {@link File#mkdirs File.mkdirs()}.
     */
    public static File getExternalStoragePublicDirectory(String type) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStoragePublicDirs(type)[0];
    }

    /**
     * Returns the path for android-specific data on the SD card.
     * @hide
     */
    public static File[] buildExternalStorageAndroidDataDirs() {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAndroidDataDirs();
    }

    /**
     * Generates the raw path to an application's data
     * @hide
     */
    public static File[] buildExternalStorageAppDataDirs(String packageName) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAppDataDirs(packageName);
    }
    
    /**
     * Generates the raw path to an application's media
     * @hide
     */
    public static File[] buildExternalStorageAppMediaDirs(String packageName) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAppMediaDirs(packageName);
    }
    
    /**
     * Generates the raw path to an application's OBB files
     * @hide
     */
    public static File[] buildExternalStorageAppObbDirs(String packageName) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAppObbDirs(packageName);
    }
    
    /**
     * Generates the path to an application's files.
     * @hide
     */
    public static File[] buildExternalStorageAppFilesDirs(String packageName) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAppFilesDirs(packageName);
    }

    /**
     * Generates the path to an application's cache.
     * @hide
     */
    public static File[] buildExternalStorageAppCacheDirs(String packageName) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAppCacheDirs(packageName);
    }
    
    /**
     * Return the download/cache content directory.
     */
    public static File getDownloadCacheDirectory() {
        return DOWNLOAD_CACHE_DIRECTORY;
    }

    /**
     * Unknown storage state, such as when a path isn't backed by known storage
     * media.
     *
     * @see #getExternalStorageState(File)
     */
    public static final String MEDIA_UNKNOWN = "unknown";

    /**
     * Storage state if the media is not present.
     *
     * @see #getExternalStorageState(File)
     */
    public static final String MEDIA_REMOVED = "removed";

    /**
     * Storage state if the media is present but not mounted.
     *
     * @see #getExternalStorageState(File)
     */
    public static final String MEDIA_UNMOUNTED = "unmounted";

    /**
     * Storage state if the media is present and being disk-checked.
     *
     * @see #getExternalStorageState(File)
     */
    public static final String MEDIA_CHECKING = "checking";

    /**
     * Storage state if the media is present but is blank or is using an
     * unsupported filesystem.
     *
     * @see #getExternalStorageState(File)
     */
    public static final String MEDIA_NOFS = "nofs";

    /**
     * Storage state if the media is present and mounted at its mount point with
     * read/write access.
     *
     * @see #getExternalStorageState(File)
     */
    public static final String MEDIA_MOUNTED = "mounted";

    /**
     * Storage state if the media is present and mounted at its mount point with
     * read-only access.
     *
     * @see #getExternalStorageState(File)
     */
    public static final String MEDIA_MOUNTED_READ_ONLY = "mounted_ro";

    /**
     * Storage state if the media is present not mounted, and shared via USB
     * mass storage.
     *
     * @see #getExternalStorageState(File)
     */
    public static final String MEDIA_SHARED = "shared";

    /**
     * Storage state if the media was removed before it was unmounted.
     *
     * @see #getExternalStorageState(File)
     */
    public static final String MEDIA_BAD_REMOVAL = "bad_removal";

    /**
     * Storage state if the media is present but cannot be mounted. Typically
     * this happens if the file system on the media is corrupted.
     *
     * @see #getExternalStorageState(File)
     */
    public static final String MEDIA_UNMOUNTABLE = "unmountable";

    /**
     * Returns the current state of the primary "external" storage device.
     * 
     * @see #getExternalStorageDirectory()
     * @return one of {@link #MEDIA_UNKNOWN}, {@link #MEDIA_REMOVED},
     *         {@link #MEDIA_UNMOUNTED}, {@link #MEDIA_CHECKING},
     *         {@link #MEDIA_NOFS}, {@link #MEDIA_MOUNTED},
     *         {@link #MEDIA_MOUNTED_READ_ONLY}, {@link #MEDIA_SHARED},
     *         {@link #MEDIA_BAD_REMOVAL}, or {@link #MEDIA_UNMOUNTABLE}.
     */
    public static String getExternalStorageState() {
        final File externalDir = sCurrentUser.getExternalDirsForApp()[0];
        return getExternalStorageState(externalDir);
    }

    /**
     * @deprecated use {@link #getExternalStorageState(File)}
     */
    @Deprecated
    public static String getStorageState(File path) {
        return getExternalStorageState(path);
    }

    /**
     * Returns the current state of the storage device that provides the given
     * path.
     *
     * @return one of {@link #MEDIA_UNKNOWN}, {@link #MEDIA_REMOVED},
     *         {@link #MEDIA_UNMOUNTED}, {@link #MEDIA_CHECKING},
     *         {@link #MEDIA_NOFS}, {@link #MEDIA_MOUNTED},
     *         {@link #MEDIA_MOUNTED_READ_ONLY}, {@link #MEDIA_SHARED},
     *         {@link #MEDIA_BAD_REMOVAL}, or {@link #MEDIA_UNMOUNTABLE}.
     */
    public static String getExternalStorageState(File path) {
        final StorageVolume volume = getStorageVolume(path);
        if (volume != null) {
            final IMountService mountService = IMountService.Stub.asInterface(
                    ServiceManager.getService("mount"));
            try {
                return mountService.getVolumeState(volume.getPath());
            } catch (RemoteException e) {
            }
        }

        return Environment.MEDIA_UNKNOWN;
    }

    /**
     * Returns whether the primary "external" storage device is removable.
     *
     * @return true if the storage device can be removed (such as an SD card),
     *         or false if the storage device is built in and cannot be
     *         physically removed.
     */
    public static boolean isExternalStorageRemovable() {
        if (isStorageDisabled()) return false;
        final File externalDir = sCurrentUser.getExternalDirsForApp()[0];
        return isExternalStorageRemovable(externalDir);
    }

    /**
     * Returns whether the storage device that provides the given path is
     * removable.
     *
     * @return true if the storage device can be removed (such as an SD card),
     *         or false if the storage device is built in and cannot be
     *         physically removed.
     * @throws IllegalArgumentException if the path is not a valid storage
     *             device.
     */
    public static boolean isExternalStorageRemovable(File path) {
        final StorageVolume volume = getStorageVolume(path);
        if (volume != null) {
            return volume.isRemovable();
        } else {
            throw new IllegalArgumentException("Failed to find storage device at " + path);
        }
    }

    /**
     * Returns whether the primary "external" storage device is emulated. If
     * true, data stored on this device will be stored on a portion of the
     * internal storage system.
     *
     * @see DevicePolicyManager#setStorageEncryption(android.content.ComponentName,
     *      boolean)
     */
    public static boolean isExternalStorageEmulated() {
        if (isStorageDisabled()) return false;
        final File externalDir = sCurrentUser.getExternalDirsForApp()[0];
        return isExternalStorageEmulated(externalDir);
    }

    /**
     * Returns whether the storage device that provides the given path is
     * emulated. If true, data stored on this device will be stored on a portion
     * of the internal storage system.
     *
     * @throws IllegalArgumentException if the path is not a valid storage
     *             device.
     */
    public static boolean isExternalStorageEmulated(File path) {
        final StorageVolume volume = getStorageVolume(path);
        if (volume != null) {
            return volume.isEmulated();
        } else {
            throw new IllegalArgumentException("Failed to find storage device at " + path);
        }
    }

    static File getDirectory(String variableName, String defaultPath) {
        String path = System.getenv(variableName);
        return path == null ? new File(defaultPath) : new File(path);
    }

    private static String getCanonicalPathOrNull(String variableName) {
        String path = System.getenv(variableName);
        if (path == null) {
            return null;
        }
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            Log.w(TAG, "Unable to resolve canonical path for " + path);
            return null;
        }
    }

    /** {@hide} */
    public static void setUserRequired(boolean userRequired) {
        sUserRequired = userRequired;
    }

    private static void throwIfUserRequired() {
        if (sUserRequired) {
            Log.wtf(TAG, "Path requests must specify a user by using UserEnvironment",
                    new Throwable());
        }
    }

    /**
     * Append path segments to each given base path, returning result.
     *
     * @hide
     */
    public static File[] buildPaths(File[] base, String... segments) {
        File[] result = new File[base.length];
        for (int i = 0; i < base.length; i++) {
            result[i] = buildPath(base[i], segments);
        }
        return result;
    }

    /**
     * Append path segments to given base path, returning result.
     *
     * @hide
     */
    public static File buildPath(File base, String... segments) {
        File cur = base;
        for (String segment : segments) {
            if (cur == null) {
                cur = new File(segment);
            } else {
                cur = new File(cur, segment);
            }
        }
        return cur;
    }

    private static boolean isStorageDisabled() {
        return SystemProperties.getBoolean("config.disable_storage", false);
    }

    private static StorageVolume getStorageVolume(File path) {
        try {
            path = path.getCanonicalFile();
        } catch (IOException e) {
            return null;
        }

        try {
            final IMountService mountService = IMountService.Stub.asInterface(
                    ServiceManager.getService("mount"));
            final StorageVolume[] volumes = mountService.getVolumeList();
            for (StorageVolume volume : volumes) {
                if (FileUtils.contains(volume.getPathFile(), path)) {
                    return volume;
                }
            }
        } catch (RemoteException e) {
        }

        return null;
    }

    /**
     * If the given path exists on emulated external storage, return the
     * translated backing path hosted on internal storage. This bypasses any
     * emulation later, improving performance. This is <em>only</em> suitable
     * for read-only access.
     * <p>
     * Returns original path if given path doesn't meet these criteria. Callers
     * must hold {@link android.Manifest.permission#WRITE_MEDIA_STORAGE}
     * permission.
     *
     * @hide
     */
    public static File maybeTranslateEmulatedPathToInternal(File path) {
        // Fast return if not emulated, or missing variables
        if (!Environment.isExternalStorageEmulated()
                || CANONCIAL_EMULATED_STORAGE_TARGET == null) {
            return path;
        }

        try {
            final String rawPath = path.getCanonicalPath();
            if (rawPath.startsWith(CANONCIAL_EMULATED_STORAGE_TARGET)) {
                final File internalPath = new File(DIR_MEDIA_STORAGE,
                        rawPath.substring(CANONCIAL_EMULATED_STORAGE_TARGET.length()));
                if (internalPath.exists()) {
                    return internalPath;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to resolve canonical path for " + path);
        }

        // Unable to translate to internal path; use original
        return path;
    }
}
