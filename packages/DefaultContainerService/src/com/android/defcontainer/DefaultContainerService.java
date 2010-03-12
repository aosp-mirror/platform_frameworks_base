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
import com.android.internal.content.PackageHelper;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.Package;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.os.FileUtils;
import android.os.storage.IMountService;
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
        public PackageInfoLite getMinimalPackageInfo(final Uri fileUri) {
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
            PackageParser packageParser = new PackageParser(archiveFilePath);
            File sourceFile = new File(archiveFilePath);
            DisplayMetrics metrics = new DisplayMetrics();
            metrics.setToDefaults();
            PackageParser.PackageLite pkg = packageParser.parsePackageLite(
                    archiveFilePath, 0);
            ret.packageName = pkg.packageName;
            ret.installLocation = pkg.installLocation;
            // Nuke the parser reference right away and force a gc
            Runtime.getRuntime().gc();
            packageParser = null;
            if (pkg == null) {
                Log.w(TAG, "Failed to parse package");
                ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
                return ret;
            }
            ret.packageName = pkg.packageName;
            int loc = recommendAppInstallLocation(pkg.installLocation, archiveFilePath);
            if (loc == PackageManager.INSTALL_EXTERNAL) {
                ret.recommendedInstallLocation =  PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
            } else if (loc == ERR_LOC) {
                Log.i(TAG, "Failed to install insufficient storage");
                ret.recommendedInstallLocation =  PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE;
            } else {
                // Implies install on internal storage.
                ret.recommendedInstallLocation =  PackageHelper.RECOMMEND_INSTALL_INTERNAL;
            }
            return ret;
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
        // Create new container at newCachePath
        String codePath = packageURI.getPath();
        File codeFile = new File(codePath);
        String newCachePath = null;
        // Create new container
        if ((newCachePath = PackageHelper.createSdDir(codeFile,
                newCid, key, Process.myUid())) == null) {
            Log.e(TAG, "Failed to create container " + newCid);
            return null;
        }
        if (localLOGV) Log.i(TAG, "Created container for " + newCid
                + " at path : " + newCachePath);
        File resFile = new File(newCachePath, resFileName);
        if (!FileUtils.copyFile(new File(codePath), resFile)) {
            Log.e(TAG, "Failed to copy " + codePath + " to " + resFile);
            // Clean up container
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

    // Constants related to app heuristics
    // No-installation limit for internal flash: 10% or less space available
    private static final double LOW_NAND_FLASH_TRESHOLD = 0.1;

    // SD-to-internal app size threshold: currently set to 1 MB
    private static final long INSTALL_ON_SD_THRESHOLD = (1024 * 1024);
    private static final int ERR_LOC = -1;

    private int recommendAppInstallLocation(int installLocation,
            String archiveFilePath) {
        // Initial implementation:
        // Package size = code size + cache size + data size
        // If code size > 1 MB, install on SD card.
        // Else install on internal NAND flash, unless space on NAND is less than 10%
        String status = Environment.getExternalStorageState();
        long availSDSize = -1;
        if (status.equals(Environment.MEDIA_MOUNTED)) {
            StatFs sdStats = new StatFs(
                    Environment.getExternalStorageDirectory().getPath());
            availSDSize = (long)sdStats.getAvailableBlocks() *
                    (long)sdStats.getBlockSize();
        }
        StatFs internalStats = new StatFs(Environment.getDataDirectory().getPath());
        long totalInternalSize = (long)internalStats.getBlockCount() *
                (long)internalStats.getBlockSize();
        long availInternalSize = (long)internalStats.getAvailableBlocks() *
                (long)internalStats.getBlockSize();

        double pctNandFree = (double)availInternalSize / (double)totalInternalSize;

        File apkFile = new File(archiveFilePath);
        long pkgLen = apkFile.length();

        boolean auto = true;
        // To make final copy
        long reqInstallSize = pkgLen;
        // For dex files. Just ignore and fail when extracting. Max limit of 2Gig for now.
        long reqInternalSize = 0;
        boolean intThresholdOk = (pctNandFree >= LOW_NAND_FLASH_TRESHOLD);
        boolean intAvailOk = ((reqInstallSize + reqInternalSize) < availInternalSize);
        boolean fitsOnSd = (reqInstallSize < availSDSize) && intThresholdOk &&
                (reqInternalSize < availInternalSize);
        boolean fitsOnInt = intThresholdOk && intAvailOk;

        // Consider application flags preferences as well...
        boolean installOnlyOnSd = (installLocation ==
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
        boolean installOnlyInternal = (installLocation ==
                PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        if (installOnlyInternal) {
            // If set explicitly in manifest,
            // let that override everything else
            auto = false;
        } else if (installOnlyOnSd){
            // Check if this can be accommodated on the sdcard
            if (fitsOnSd) {
                auto = false;
            }
        } else {
            // Check if user option is enabled
            boolean setInstallLoc = Settings.System.getInt(getApplicationContext()
                    .getContentResolver(),
                    Settings.System.SET_INSTALL_LOCATION, 0) != 0;
            if (setInstallLoc) {
                // Pick user preference
                int installPreference = Settings.System.getInt(getApplicationContext()
                        .getContentResolver(),
                        Settings.System.DEFAULT_INSTALL_LOCATION,
                        PackageHelper.APP_INSTALL_AUTO);
                if (installPreference == PackageHelper.APP_INSTALL_INTERNAL) {
                    installOnlyInternal = true;
                    auto = false;
                } else if (installPreference == PackageHelper.APP_INSTALL_EXTERNAL) {
                    installOnlyOnSd = true;
                    auto = false;
                }
            }
        }
        if (!auto) {
            if (installOnlyOnSd) {
                return fitsOnSd ? PackageManager.INSTALL_EXTERNAL : ERR_LOC;
            } else if (installOnlyInternal){
                // Check on internal flash
                return fitsOnInt ? 0 : ERR_LOC;
            }
        }
        // Try to install internally
        if (fitsOnInt) {
            return 0;
        }
        // Try the sdcard now.
        if (fitsOnSd) {
            return PackageManager.INSTALL_EXTERNAL;
        }
        // Return error code
        return ERR_LOC;
    }
}
