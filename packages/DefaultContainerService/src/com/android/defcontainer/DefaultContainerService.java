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
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.app.IntentService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import android.os.FileUtils;
import android.provider.Settings;

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
         * @param packageURI the uri of resource to be copied. Should be a
         * file uri
         * @param outStream Remote file descriptor to be used for copying
         * @return Returns true if copy succeded or false otherwise.
         */
        public boolean copyResource(final Uri packageURI,
                ParcelFileDescriptor outStream) {
            if (packageURI == null ||  outStream == null) {
                return false;
            }
            ParcelFileDescriptor.AutoCloseOutputStream
            autoOut = new ParcelFileDescriptor.AutoCloseOutputStream(outStream);
            return copyFile(packageURI, autoOut);
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
                Log.i(TAG, "Invalid package uri " + fileUri);
                ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
                return ret;
            }
            String scheme = fileUri.getScheme();
            if (scheme != null && !scheme.equals("file")) {
                Log.w(TAG, "Falling back to installing on internal storage only");
                ret.recommendedInstallLocation = PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                return ret;
            }
            String archiveFilePath = fileUri.getPath();
            DisplayMetrics metrics = new DisplayMetrics();
            metrics.setToDefaults();
            PackageParser.PackageLite pkg = PackageParser.parsePackageLite(archiveFilePath, 0);
            if (pkg == null) {
                Log.w(TAG, "Failed to parse package");
                ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
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
            return isUnderInternalThreshold(apkFile, threshold);
        }

        @Override
        public boolean checkExternalFreeStorage(Uri packageUri) throws RemoteException {
            final File apkFile = new File(packageUri.getPath());
            return isUnderExternalThreshold(apkFile);
        }

        public ObbInfo getObbInfo(String filename) {
            try {
                return ObbScanner.getObbInfo(filename);
            } catch (IOException e) {
                Log.d(TAG, "Couldn't get OBB info for " + filename);
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
            Log.w(TAG, "Make sure sdcard is mounted.");
            return null;
        }

        // The .apk file
        String codePath = packageURI.getPath();
        File codeFile = new File(codePath);

        // Native files we need to copy to the container.
        List<Pair<ZipEntry, String>> nativeFiles = new ArrayList<Pair<ZipEntry, String>>();

        // Calculate size of container needed to hold base APK.
        final int sizeMb = calculateContainerSize(codeFile, nativeFiles);

        // Create new container
        String newCachePath = null;
        if ((newCachePath = PackageHelper.createSdDir(sizeMb, newCid, key, Process.myUid())) == null) {
            Log.e(TAG, "Failed to create container " + newCid);
            return null;
        }
        if (localLOGV)
            Log.i(TAG, "Created container for " + newCid + " at path : " + newCachePath);
        File resFile = new File(newCachePath, resFileName);
        if (!FileUtils.copyFile(new File(codePath), resFile)) {
            Log.e(TAG, "Failed to copy " + codePath + " to " + resFile);
            // Clean up container
            PackageHelper.destroySdDir(newCid);
            return null;
        }

        try {
            ZipFile zipFile = new ZipFile(codeFile);

            File sharedLibraryDir = new File(newCachePath, LIB_DIR_NAME);
            sharedLibraryDir.mkdir();

            final int N = nativeFiles.size();
            for (int i = 0; i < N; i++) {
                final Pair<ZipEntry, String> entry = nativeFiles.get(i);

                InputStream is = zipFile.getInputStream(entry.first);
                try {
                    File destFile = new File(sharedLibraryDir, entry.second);
                    if (!FileUtils.copyToFile(is, destFile)) {
                        throw new IOException("Couldn't copy native binary "
                                + entry.first.getName() + " to " + entry.second);
                    }
                } finally {
                    is.close();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Couldn't copy native file to container", e);
            PackageHelper.destroySdDir(newCid);
            return null;
        }

        if (localLOGV) Log.i(TAG, "Copied " + codePath + " to " + resFile);
        if (!PackageHelper.finalizeSdDir(newCid)) {
            Log.e(TAG, "Failed to finalize " + newCid + " at path " + newCachePath);
            // Clean up container
            PackageHelper.destroySdDir(newCid);
        }
        if (localLOGV) Log.i(TAG, "Finalized container " + newCid);
        if (PackageHelper.isContainerMounted(newCid)) {
            if (localLOGV) Log.i(TAG, "Unmounting " + newCid +
                    " at path " + newCachePath);
            // Force a gc to avoid being killed.
            Runtime.getRuntime().gc();
            PackageHelper.unMountSdDir(newCid);
        } else {
            if (localLOGV) Log.i(TAG, "Container " + newCid + " not mounted");
        }
        return newCachePath;
    }

    public static boolean copyToFile(InputStream inputStream, FileOutputStream out) {
        try {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) >= 0) {
                out.write(buffer, 0, bytesRead);
            }
            return true;
        } catch (IOException e) {
            Log.i(TAG, "Exception : " + e + " when copying file");
            return false;
        }
    }

    public static boolean copyToFile(File srcFile, FileOutputStream out) {
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(srcFile);
            return copyToFile(inputStream, out);
        } catch (IOException e) {
            return false;
        } finally {
            try { if (inputStream != null) inputStream.close(); } catch (IOException e) {}
        }
    }

    private  boolean copyFile(Uri pPackageURI, FileOutputStream outStream) {
        String scheme = pPackageURI.getScheme();
        if (scheme == null || scheme.equals("file")) {
            final File srcPackageFile = new File(pPackageURI.getPath());
            // We copy the source package file to a temp file and then rename it to the
            // destination file in order to eliminate a window where the package directory
            // scanner notices the new package file but it's not completely copied yet.
            if (!copyToFile(srcPackageFile, outStream)) {
                Log.e(TAG, "Couldn't copy file: " + srcPackageFile);
                return false;
            }
        } else if (scheme.equals("content")) {
            ParcelFileDescriptor fd = null;
            try {
                fd = getContentResolver().openFileDescriptor(pPackageURI, "r");
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Couldn't open file descriptor from download service. Failed with exception " + e);
                return false;
            }
            if (fd == null) {
                Log.e(TAG, "Couldn't open file descriptor from download service (null).");
                return false;
            } else {
                if (localLOGV) {
                    Log.v(TAG, "Opened file descriptor from download service.");
                }
                ParcelFileDescriptor.AutoCloseInputStream
                dlStream = new ParcelFileDescriptor.AutoCloseInputStream(fd);
                // We copy the source package file to a temp file and then rename it to the
                // destination file in order to eliminate a window where the package directory
                // scanner notices the new package file but it's not completely copied yet.
                if (!copyToFile(dlStream, outStream)) {
                    Log.e(TAG, "Couldn't copy " + pPackageURI + " to temp file.");
                    return false;
                }
            }
        } else {
            Log.e(TAG, "Package URI is not 'file:' or 'content:' - " + pPackageURI);
            return false;
        }
        return true;
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
            fitsOnInternal = isUnderInternalThreshold(apkFile, threshold);
        }

        boolean fitsOnSd = false;
        if (!emulated && (checkBoth || prefer == PREFER_EXTERNAL)) {
            fitsOnSd = isUnderExternalThreshold(apkFile);
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

    private boolean isUnderInternalThreshold(File apkFile, long threshold) {
        final long size = apkFile.length();

        final StatFs internalStats = new StatFs(Environment.getDataDirectory().getPath());
        final long availInternalSize = (long) internalStats.getAvailableBlocks()
                * (long) internalStats.getBlockSize();

        return (availInternalSize - size) > threshold;
    }


    private boolean isUnderExternalThreshold(File apkFile) {
        if (Environment.isExternalStorageEmulated()) {
            return false;
        }

        final int sizeMb = calculateContainerSize(apkFile, null);

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
     */
    private int calculateContainerSize(File apkFile, List<Pair<ZipEntry, String>> outFiles) {
        // Calculate size of container needed to hold base APK.
        long sizeBytes = apkFile.length();

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
