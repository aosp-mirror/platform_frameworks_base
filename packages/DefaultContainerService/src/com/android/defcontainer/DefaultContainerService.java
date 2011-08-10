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

package com.android.defcontainer;

import com.android.internal.app.IMediaContainerService;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;

import android.app.IntentService;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.res.ObbInfo;
import android.content.res.ObbScanner;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/*
 * This service copies a downloaded apk to a file passed in as
 * a ParcelFileDescriptor or to a newly created container specified
 * by parameters. The DownloadManager gives access to this process
 * based on its uid. This process also needs the ACCESS_DOWNLOAD_MANAGER
 * permission to access apks downloaded via the download manager.
 */
public class DefaultContainerService extends IntentService {
    private static final String TAG = "DefContainer";
    private static final boolean localLOGV = true;

    private static final String LIB_DIR_NAME = "lib";

    private IMediaContainerService.Stub mBinder = new IMediaContainerService.Stub() {
        /*
         * Creates a new container and copies resource there.
         * @param paackageURI the uri of resource to be copied. Can be either
         * a content uri or a file uri
         * @param cid the id of the secure container that should
         * be used for creating a secure container into which the resource
         * will be copied.
         * @param key Refers to key used for encrypting the secure container
         * @param resFileName Name of the target resource file(relative to newly
         * created secure container)
         * @return Returns the new cache path where the resource has been copied into
         *
         */
        public String copyResourceToContainer(final Uri packageURI,
                final String cid,
                final String key, final String resFileName) {
            if (packageURI == null || cid == null) {
                return null;
            }
            return copyResourceInner(packageURI, cid, key, resFileName);
        }

        /*
         * Copy specified resource to output stream
         * @param packageURI the uri of resource to be copied. Should be a file
         * uri
         * @param outStream Remote file descriptor to be used for copying
         * @return returns status code according to those in {@link
         * PackageManager}
         */
        public int copyResource(final Uri packageURI, ParcelFileDescriptor outStream) {
            if (packageURI == null || outStream == null) {
                return PackageManager.INSTALL_FAILED_INVALID_URI;
            }

            ParcelFileDescriptor.AutoCloseOutputStream autoOut
                    = new ParcelFileDescriptor.AutoCloseOutputStream(outStream);

            try {
                copyFile(packageURI, autoOut);
                return PackageManager.INSTALL_SUCCEEDED;
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "Could not copy URI " + packageURI.toString() + " FNF: "
                        + e.getMessage());
                return PackageManager.INSTALL_FAILED_INVALID_URI;
            } catch (IOException e) {
                Slog.e(TAG, "Could not copy URI " + packageURI.toString() + " IO: "
                        + e.getMessage());
                return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            }
        }

        /*
         * Determine the recommended install location for package
         * specified by file uri location.
         * @param fileUri the uri of resource to be copied. Should be a
         * file uri
         * @return Returns PackageInfoLite object containing
         * the package info and recommended app location.
         */
        public PackageInfoLite getMinimalPackageInfo(final Uri fileUri, int flags, long threshold) {
            PackageInfoLite ret = new PackageInfoLite();
            if (fileUri == null) {
                Slog.i(TAG, "Invalid package uri " + fileUri);
                ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
                return ret;
            }
            String scheme = fileUri.getScheme();
            if (scheme != null && !scheme.equals("file")) {
                Slog.w(TAG, "Falling back to installing on internal storage only");
                ret.recommendedInstallLocation = PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                return ret;
            }
            String archiveFilePath = fileUri.getPath();
            DisplayMetrics metrics = new DisplayMetrics();
            metrics.setToDefaults();

            PackageParser.PackageLite pkg = PackageParser.parsePackageLite(archiveFilePath, 0);
            if (pkg == null) {
                Slog.w(TAG, "Failed to parse package");

                final File apkFile = new File(archiveFilePath);
                if (!apkFile.exists()) {
                    ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_URI;
                } else {
                    ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
                }

                return ret;
            }
            ret.packageName = pkg.packageName;
            ret.installLocation = pkg.installLocation;

            ret.recommendedInstallLocation = recommendAppInstallLocation(pkg.installLocation,
                    archiveFilePath, flags, threshold);

            return ret;
        }

        @Override
        public boolean checkInternalFreeStorage(Uri packageUri, long threshold)
                throws RemoteException {
            final File apkFile = new File(packageUri.getPath());
            try {
                return isUnderInternalThreshold(apkFile, threshold);
            } catch (FileNotFoundException e) {
                return true;
            }
        }

        @Override
        public boolean checkExternalFreeStorage(Uri packageUri) throws RemoteException {
            final File apkFile = new File(packageUri.getPath());
            try {
                return isUnderExternalThreshold(apkFile);
            } catch (FileNotFoundException e) {
                return true;
            }
        }

        public ObbInfo getObbInfo(String filename) {
            try {
                return ObbScanner.getObbInfo(filename);
            } catch (IOException e) {
                Slog.d(TAG, "Couldn't get OBB info for " + filename);
                return null;
            }
        }

        @Override
        public long calculateDirectorySize(String path) throws RemoteException {
            final File directory = new File(path);
            if (directory.exists() && directory.isDirectory()) {
                return MeasurementUtils.measureDirectory(path);
            } else {
                return 0L;
            }
        }
    };

    public DefaultContainerService() {
        super("DefaultContainerService");
        setIntentRedelivery(true);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (PackageManager.ACTION_CLEAN_EXTERNAL_STORAGE.equals(intent.getAction())) {
            IPackageManager pm = IPackageManager.Stub.asInterface(
                    ServiceManager.getService("package"));
            String pkg = null;
            try {
                while ((pkg=pm.nextPackageToClean(pkg)) != null) {
                    eraseFiles(Environment.getExternalStorageAppDataDirectory(pkg));
                    eraseFiles(Environment.getExternalStorageAppMediaDirectory(pkg));
                    eraseFiles(Environment.getExternalStorageAppObbDirectory(pkg));
                }
            } catch (RemoteException e) {
            }
        }
    }

    void eraseFiles(File path) {
        if (path.isDirectory()) {
            String[] files = path.list();
            if (files != null) {
                for (String file : files) {
                    eraseFiles(new File(path, file));
                }
            }
        }
        path.delete();
    }
    
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private String copyResourceInner(Uri packageURI, String newCid, String key, String resFileName) {
        // Make sure the sdcard is mounted.
        String status = Environment.getExternalStorageState();
        if (!status.equals(Environment.MEDIA_MOUNTED)) {
            Slog.w(TAG, "Make sure sdcard is mounted.");
            return null;
        }

        // The .apk file
        String codePath = packageURI.getPath();
        File codeFile = new File(codePath);

        // Calculate size of container needed to hold base APK.
        int sizeMb;
        try {
            sizeMb = calculateContainerSize(codeFile);
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "File does not exist when trying to copy " + codeFile.getPath());
            return null;
        }

        // Create new container
        final String newCachePath;
        if ((newCachePath = PackageHelper.createSdDir(sizeMb, newCid, key, Process.myUid())) == null) {
            Slog.e(TAG, "Failed to create container " + newCid);
            return null;
        }

        if (localLOGV) {
            Slog.i(TAG, "Created container for " + newCid + " at path : " + newCachePath);
        }

        final File resFile = new File(newCachePath, resFileName);
        if (FileUtils.copyFile(new File(codePath), resFile)) {
            if (localLOGV) {
                Slog.i(TAG, "Copied " + codePath + " to " + resFile);
            }
        } else {
            Slog.e(TAG, "Failed to copy " + codePath + " to " + resFile);
            // Clean up container
            PackageHelper.destroySdDir(newCid);
            return null;
        }

        final File sharedLibraryDir = new File(newCachePath, LIB_DIR_NAME);
        if (sharedLibraryDir.mkdir()) {
            int ret = NativeLibraryHelper.copyNativeBinariesIfNeededLI(codeFile, sharedLibraryDir);
            if (ret != PackageManager.INSTALL_SUCCEEDED) {
                Slog.e(TAG, "Could not copy native libraries to " + sharedLibraryDir.getPath());
                PackageHelper.destroySdDir(newCid);
                return null;
            }
        } else {
            Slog.e(TAG, "Could not create native lib directory: " + sharedLibraryDir.getPath());
            PackageHelper.destroySdDir(newCid);
            return null;
        }

        if (!PackageHelper.finalizeSdDir(newCid)) {
            Slog.e(TAG, "Failed to finalize " + newCid + " at path " + newCachePath);
            // Clean up container
            PackageHelper.destroySdDir(newCid);
            return null;
        }

        if (localLOGV) {
            Slog.i(TAG, "Finalized container " + newCid);
        }

        if (PackageHelper.isContainerMounted(newCid)) {
            if (localLOGV) {
                Slog.i(TAG, "Unmounting " + newCid + " at path " + newCachePath);
            }

            // Force a gc to avoid being killed.
            Runtime.getRuntime().gc();
            PackageHelper.unMountSdDir(newCid);
        } else {
            if (localLOGV) {
                Slog.i(TAG, "Container " + newCid + " not mounted");
            }
        }

        return newCachePath;
    }

    private static void copyToFile(InputStream inputStream, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) >= 0) {
            out.write(buffer, 0, bytesRead);
        }
    }

    private static void copyToFile(File srcFile, OutputStream out)
            throws FileNotFoundException, IOException {
        InputStream inputStream = new BufferedInputStream(new FileInputStream(srcFile));
        try {
            copyToFile(inputStream, out);
        } finally {
            try { inputStream.close(); } catch (IOException e) {}
        }
    }

    private void copyFile(Uri pPackageURI, OutputStream outStream) throws FileNotFoundException,
            IOException {
        String scheme = pPackageURI.getScheme();
        if (scheme == null || scheme.equals("file")) {
            final File srcPackageFile = new File(pPackageURI.getPath());
            // We copy the source package file to a temp file and then rename it to the
            // destination file in order to eliminate a window where the package directory
            // scanner notices the new package file but it's not completely copied yet.
            copyToFile(srcPackageFile, outStream);
        } else if (scheme.equals("content")) {
            ParcelFileDescriptor fd = null;
            try {
                fd = getContentResolver().openFileDescriptor(pPackageURI, "r");
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "Couldn't open file descriptor from download service. "
                        + "Failed with exception " + e);
                throw e;
            }

            if (fd == null) {
                Slog.e(TAG, "Provider returned no file descriptor for " + pPackageURI.toString());
                throw new FileNotFoundException("provider returned no file descriptor");
            } else {
                if (localLOGV) {
                    Slog.i(TAG, "Opened file descriptor from download service.");
                }
                ParcelFileDescriptor.AutoCloseInputStream dlStream
                        = new ParcelFileDescriptor.AutoCloseInputStream(fd);

                // We copy the source package file to a temp file and then rename it to the
                // destination file in order to eliminate a window where the package directory
                // scanner notices the new package file but it's not completely
                // copied
                copyToFile(dlStream, outStream);
            }
        } else {
            Slog.e(TAG, "Package URI is not 'file:' or 'content:' - " + pPackageURI);
            throw new FileNotFoundException("Package URI is not 'file:' or 'content:'");
        }
    }

    private static final int PREFER_INTERNAL = 1;
    private static final int PREFER_EXTERNAL = 2;

    private int recommendAppInstallLocation(int installLocation, String archiveFilePath, int flags,
            long threshold) {
        int prefer;
        boolean checkBoth = false;

        check_inner : {
            /*
             * Explicit install flags should override the manifest settings.
             */
            if ((flags & PackageManager.INSTALL_FORWARD_LOCK) != 0) {
                /*
                 * Forward-locked applications cannot be installed on SD card,
                 * so only allow checking internal storage.
                 */
                prefer = PREFER_INTERNAL;
                break check_inner;
            } else if ((flags & PackageManager.INSTALL_INTERNAL) != 0) {
                prefer = PREFER_INTERNAL;
                break check_inner;
            } else if ((flags & PackageManager.INSTALL_EXTERNAL) != 0) {
                prefer = PREFER_EXTERNAL;
                break check_inner;
            }

            /* No install flags. Check for manifest option. */
            if (installLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY) {
                prefer = PREFER_INTERNAL;
                break check_inner;
            } else if (installLocation == PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL) {
                prefer = PREFER_EXTERNAL;
                checkBoth = true;
                break check_inner;
            } else if (installLocation == PackageInfo.INSTALL_LOCATION_AUTO) {
                // We default to preferring internal storage.
                prefer = PREFER_INTERNAL;
                checkBoth = true;
                break check_inner;
            }

            // Pick user preference
            int installPreference = Settings.System.getInt(getApplicationContext()
                    .getContentResolver(),
                    Settings.Secure.DEFAULT_INSTALL_LOCATION,
                    PackageHelper.APP_INSTALL_AUTO);
            if (installPreference == PackageHelper.APP_INSTALL_INTERNAL) {
                prefer = PREFER_INTERNAL;
                break check_inner;
            } else if (installPreference == PackageHelper.APP_INSTALL_EXTERNAL) {
                prefer = PREFER_EXTERNAL;
                break check_inner;
            }

            /*
             * Fall back to default policy of internal-only if nothing else is
             * specified.
             */
            prefer = PREFER_INTERNAL;
        }

        final boolean emulated = Environment.isExternalStorageEmulated();

        final File apkFile = new File(archiveFilePath);

        boolean fitsOnInternal = false;
        if (checkBoth || prefer == PREFER_INTERNAL) {
            try {
                fitsOnInternal = isUnderInternalThreshold(apkFile, threshold);
            } catch (FileNotFoundException e) {
                return PackageHelper.RECOMMEND_FAILED_INVALID_URI;
            }
        }

        boolean fitsOnSd = false;
        if (!emulated && (checkBoth || prefer == PREFER_EXTERNAL)) {
            try {
                fitsOnSd = isUnderExternalThreshold(apkFile);
            } catch (FileNotFoundException e) {
                return PackageHelper.RECOMMEND_FAILED_INVALID_URI;
            }
        }

        if (prefer == PREFER_INTERNAL) {
            if (fitsOnInternal) {
                return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
            }
        } else if (!emulated && prefer == PREFER_EXTERNAL) {
            if (fitsOnSd) {
                return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
            }
        }

        if (checkBoth) {
            if (fitsOnInternal) {
                return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
            } else if (!emulated && fitsOnSd) {
                return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
            }
        }

        /*
         * If they requested to be on the external media by default, return that
         * the media was unavailable. Otherwise, indicate there was insufficient
         * storage space available.
         */
        if (!emulated && (checkBoth || prefer == PREFER_EXTERNAL)
                && !Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            return PackageHelper.RECOMMEND_MEDIA_UNAVAILABLE;
        } else {
            return PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE;
        }
    }

    /**
     * Measure a file to see if it fits within the free space threshold.
     *
     * @param apkFile file to check
     * @param threshold byte threshold to compare against
     * @return true if file fits under threshold
     * @throws FileNotFoundException when APK does not exist
     */
    private boolean isUnderInternalThreshold(File apkFile, long threshold)
            throws FileNotFoundException {
        final long size = apkFile.length();
        if (size == 0 && !apkFile.exists()) {
            throw new FileNotFoundException();
        }

        final StatFs internalStats = new StatFs(Environment.getDataDirectory().getPath());
        final long availInternalSize = (long) internalStats.getAvailableBlocks()
                * (long) internalStats.getBlockSize();

        return (availInternalSize - size) > threshold;
    }


    /**
     * Measure a file to see if it fits in the external free space.
     *
     * @param apkFile file to check
     * @return true if file fits
     * @throws IOException when file does not exist
     */
    private boolean isUnderExternalThreshold(File apkFile) throws FileNotFoundException {
        if (Environment.isExternalStorageEmulated()) {
            return false;
        }

        final int sizeMb = calculateContainerSize(apkFile);

        final int availSdMb;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            StatFs sdStats = new StatFs(Environment.getExternalStorageDirectory().getPath());
            long availSdSize = (long) (sdStats.getAvailableBlocks() * sdStats.getBlockSize());
            availSdMb = (int) (availSdSize >> 20);
        } else {
            availSdMb = -1;
        }

        return availSdMb > sizeMb;
    }

    /**
     * Calculate the container size for an APK. Takes into account the
     * 
     * @param apkFile file from which to calculate size
     * @return size in megabytes (2^20 bytes)
     * @throws FileNotFoundException when file does not exist
     */
    private int calculateContainerSize(File apkFile) throws FileNotFoundException {
        // Calculate size of container needed to hold base APK.
        long sizeBytes = apkFile.length();
        if (sizeBytes == 0 && !apkFile.exists()) {
            throw new FileNotFoundException();
        }

        // Check all the native files that need to be copied and add that to the
        // container size.
        sizeBytes += NativeLibraryHelper.sumNativeBinariesLI(apkFile);

        int sizeMb = (int) (sizeBytes >> 20);
        if ((sizeBytes - (sizeMb * 1024 * 1024)) > 0) {
            sizeMb++;
        }

        /*
         * Add buffer size because we don't have a good way to determine the
         * real FAT size. Your FAT size varies with how many directory entries
         * you need, how big the whole filesystem is, and other such headaches.
         */
        sizeMb++;

        return sizeMb;
    }
}
