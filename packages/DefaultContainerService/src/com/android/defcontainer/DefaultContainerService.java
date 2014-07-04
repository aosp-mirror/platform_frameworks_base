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

import android.app.IntentService;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageCleanItem;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.content.res.ObbInfo;
import android.content.res.ObbScanner;
import android.os.Build;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;
import android.util.Slog;

import com.android.internal.app.IMediaContainerService;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.os.IParcelFileDescriptorFactory;
import com.android.internal.util.ArrayUtils;

import dalvik.system.VMRuntime;
import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Service that offers to inspect and copy files that may reside on removable
 * storage. This is designed to prevent the system process from holding onto
 * open files that cause the kernel to kill it when the underlying device is
 * removed.
 */
public class DefaultContainerService extends IntentService {
    private static final String TAG = "DefContainer";
    private static final boolean localLOGV = false;

    private static final String LIB_DIR_NAME = "lib";

    // TODO: migrate native code unpacking to always be a derivative work

    private IMediaContainerService.Stub mBinder = new IMediaContainerService.Stub() {
        /**
         * Creates a new container and copies package there.
         *
         * @param packagePath absolute path to the package to be copied. Can be
         *            a single monolithic APK file or a cluster directory
         *            containing one or more APKs.
         * @param containerId the id of the secure container that should be used
         *            for creating a secure container into which the resource
         *            will be copied.
         * @param key Refers to key used for encrypting the secure container
         * @return Returns the new cache path where the resource has been copied
         *         into
         */
        @Override
        public String copyPackageToContainer(String packagePath, String containerId, String key,
                boolean isExternal, boolean isForwardLocked, String abiOverride) {
            if (packagePath == null || containerId == null) {
                return null;
            }

            if (isExternal) {
                // Make sure the sdcard is mounted.
                String status = Environment.getExternalStorageState();
                if (!status.equals(Environment.MEDIA_MOUNTED)) {
                    Slog.w(TAG, "Make sure sdcard is mounted.");
                    return null;
                }
            }

            PackageLite pkg = null;
            NativeLibraryHelper.Handle handle = null;
            try {
                final File packageFile = new File(packagePath);
                pkg = PackageParser.parsePackageLite(packageFile, 0);
                handle = NativeLibraryHelper.Handle.create(pkg);
                return copyPackageToContainerInner(pkg, handle, containerId, key, isExternal,
                        isForwardLocked, abiOverride);
            } catch (PackageParserException | IOException e) {
                Slog.w(TAG, "Failed to parse package at " + packagePath);
                return null;
            } finally {
                IoUtils.closeQuietly(handle);
            }
        }

        /**
         * Copy package to the target location.
         *
         * @param packagePath absolute path to the package to be copied. Can be
         *            a single monolithic APK file or a cluster directory
         *            containing one or more APKs.
         * @return returns status code according to those in
         *         {@link PackageManager}
         */
        @Override
        public int copyPackage(String packagePath, IParcelFileDescriptorFactory target) {
            if (packagePath == null || target == null) {
                return PackageManager.INSTALL_FAILED_INVALID_URI;
            }

            PackageLite pkg = null;
            try {
                final File packageFile = new File(packagePath);
                pkg = PackageParser.parsePackageLite(packageFile, 0);
                return copyPackageInner(pkg, target);
            } catch (PackageParserException | IOException | RemoteException e) {
                Slog.w(TAG, "Failed to copy package at " + packagePath + ": " + e);
                return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            }
        }

        /**
         * Parse given package and return minimal details.
         *
         * @param packagePath absolute path to the package to be copied. Can be
         *            a single monolithic APK file or a cluster directory
         *            containing one or more APKs.
         */
        @Override
        public PackageInfoLite getMinimalPackageInfo(final String packagePath, int flags,
                long threshold, String abiOverride) {
            PackageInfoLite ret = new PackageInfoLite();

            if (packagePath == null) {
                Slog.i(TAG, "Invalid package file " + packagePath);
                ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
                return ret;
            }

            final File packageFile = new File(packagePath);
            final PackageParser.PackageLite pkg;
            try {
                pkg = PackageParser.parsePackageLite(packageFile, 0);
            } catch (PackageParserException e) {
                Slog.w(TAG, "Failed to parse package at " + packagePath);

                if (!packageFile.exists()) {
                    ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_URI;
                } else {
                    ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
                }

                return ret;
            }

            ret.packageName = pkg.packageName;
            ret.versionCode = pkg.versionCode;
            ret.installLocation = pkg.installLocation;
            ret.verifiers = pkg.verifiers;
            ret.recommendedInstallLocation = recommendAppInstallLocation(pkg, flags, threshold,
                    abiOverride);
            ret.multiArch = pkg.multiArch;

            return ret;
        }

        /**
         * Determine if package will fit on internal storage.
         *
         * @param packagePath absolute path to the package to be copied. Can be
         *            a single monolithic APK file or a cluster directory
         *            containing one or more APKs.
         */
        @Override
        public boolean checkInternalFreeStorage(String packagePath, boolean isForwardLocked,
                long threshold) throws RemoteException {
            final File packageFile = new File(packagePath);
            final PackageParser.PackageLite pkg;
            try {
                pkg = PackageParser.parsePackageLite(packageFile, 0);
                return isUnderInternalThreshold(pkg, isForwardLocked, threshold);
            } catch (PackageParserException | IOException e) {
                Slog.w(TAG, "Failed to parse package at " + packagePath);
                return false;
            }
        }

        /**
         * Determine if package will fit on external storage.
         *
         * @param packagePath absolute path to the package to be copied. Can be
         *            a single monolithic APK file or a cluster directory
         *            containing one or more APKs.
         */
        @Override
        public boolean checkExternalFreeStorage(String packagePath, boolean isForwardLocked,
                String abiOverride) throws RemoteException {
            final File packageFile = new File(packagePath);
            final PackageParser.PackageLite pkg;
            try {
                pkg = PackageParser.parsePackageLite(packageFile, 0);
                return isUnderExternalThreshold(pkg, isForwardLocked, abiOverride);
            } catch (PackageParserException | IOException e) {
                Slog.w(TAG, "Failed to parse package at " + packagePath);
                return false;
            }
        }

        @Override
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            final File dir = Environment.maybeTranslateEmulatedPathToInternal(new File(path));
            if (dir.exists() && dir.isDirectory()) {
                final String targetPath = dir.getAbsolutePath();
                return MeasurementUtils.measureDirectory(targetPath);
            } else {
                return 0L;
            }
        }

        @Override
        public long[] getFileSystemStats(String path) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            try {
                final StructStatVfs stat = Os.statvfs(path);
                final long totalSize = stat.f_blocks * stat.f_bsize;
                final long availSize = stat.f_bavail * stat.f_bsize;
                return new long[] { totalSize, availSize };
            } catch (ErrnoException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void clearDirectory(String path) throws RemoteException {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            final File directory = new File(path);
            if (directory.exists() && directory.isDirectory()) {
                eraseFiles(directory);
            }
        }

        /**
         * Calculate estimated footprint of given package post-installation.
         *
         * @param packagePath absolute path to the package to be copied. Can be
         *            a single monolithic APK file or a cluster directory
         *            containing one or more APKs.
         */
        @Override
        public long calculateInstalledSize(String packagePath, boolean isForwardLocked,
                String abiOverride) throws RemoteException {
            final File packageFile = new File(packagePath);
            final PackageParser.PackageLite pkg;
            try {
                pkg = PackageParser.parsePackageLite(packageFile, 0);
                return calculateContainerSize(pkg, isForwardLocked, abiOverride) * 1024 * 1024;
            } catch (PackageParserException | IOException e) {
                /*
                 * Okay, something failed, so let's just estimate it to be 2x
                 * the file size. Note this will be 0 if the file doesn't exist.
                 */
                return packageFile.length() * 2;
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
            final IPackageManager pm = IPackageManager.Stub.asInterface(
                    ServiceManager.getService("package"));
            PackageCleanItem item = null;
            try {
                while ((item = pm.nextPackageToClean(item)) != null) {
                    final UserEnvironment userEnv = new UserEnvironment(item.userId);
                    eraseFiles(userEnv.buildExternalStorageAppDataDirs(item.packageName));
                    eraseFiles(userEnv.buildExternalStorageAppMediaDirs(item.packageName));
                    if (item.andCode) {
                        eraseFiles(userEnv.buildExternalStorageAppObbDirs(item.packageName));
                    }
                }
            } catch (RemoteException e) {
            }
        }
    }

    void eraseFiles(File[] paths) {
        for (File path : paths) {
            eraseFiles(path);
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

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private String copyPackageToContainerInner(PackageLite pkg, NativeLibraryHelper.Handle handle,
            String newCid, String key, boolean isExternal, boolean isForwardLocked,
            String abiOverride) {
        // TODO: extend to support copying all split APKs
        if (!ArrayUtils.isEmpty(pkg.splitNames)) {
            throw new UnsupportedOperationException("Copying split APKs not yet supported");
        }

        final String resFileName = "pkg.apk";
        final String publicResFileName = "res.zip";

        if (pkg.multiArch) {
            // TODO: Support multiArch installs on ASEC.
            throw new IllegalArgumentException("multiArch not supported on ASEC installs.");
        }

        // The .apk file
        final String codePath = pkg.baseCodePath;
        final File codeFile = new File(codePath);
        final String[] abis;
        try {
            abis = calculateAbiList(handle, abiOverride, pkg.multiArch);
        } catch (IOException ioe) {
            Slog.w(TAG, "Problem determining app ABIS: " + ioe);
            return null;
        }

        // Calculate size of container needed to hold base APK.
        final int sizeMb;
        try {
            sizeMb = calculateContainerSize(pkg, handle, isForwardLocked, abis);
        } catch (IOException e) {
            Slog.w(TAG, "Problem when trying to copy " + codeFile.getPath());
            return null;
        }

        // Create new container
        final String newCachePath = PackageHelper.createSdDir(sizeMb, newCid, key, Process.myUid(),
                isExternal);
        if (newCachePath == null) {
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

        try {
            Os.chmod(resFile.getAbsolutePath(), 0640);
        } catch (ErrnoException e) {
            Slog.e(TAG, "Could not chown APK: " + e.getMessage());
            PackageHelper.destroySdDir(newCid);
            return null;
        }

        if (isForwardLocked) {
            File publicZipFile = new File(newCachePath, publicResFileName);
            try {
                PackageHelper.extractPublicFiles(resFile.getAbsolutePath(), publicZipFile);
                if (localLOGV) {
                    Slog.i(TAG, "Copied resources to " + publicZipFile);
                }
            } catch (IOException e) {
                Slog.e(TAG, "Could not chown public APK " + publicZipFile.getAbsolutePath() + ": "
                        + e.getMessage());
                PackageHelper.destroySdDir(newCid);
                return null;
            }

            try {
                Os.chmod(publicZipFile.getAbsolutePath(), 0644);
            } catch (ErrnoException e) {
                Slog.e(TAG, "Could not chown public resource file: " + e.getMessage());
                PackageHelper.destroySdDir(newCid);
                return null;
            }
        }

        final File sharedLibraryDir = new File(newCachePath, LIB_DIR_NAME);
        if (sharedLibraryDir.mkdir()) {
            int ret = PackageManager.INSTALL_SUCCEEDED;
            if (abis != null) {
                // TODO(multiArch): Support multi-arch installs on asecs. Note that we are NOT
                // using an ISA specific subdir here for now.
                final String abi = abis[0];
                ret = NativeLibraryHelper.copyNativeBinariesIfNeededLI(handle,
                        sharedLibraryDir, abi);

                if (ret != PackageManager.INSTALL_SUCCEEDED) {
                    Slog.e(TAG, "Could not copy native libraries to " + sharedLibraryDir.getPath());
                    PackageHelper.destroySdDir(newCid);
                    return null;
                }
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

    private int copyPackageInner(PackageLite pkg, IParcelFileDescriptorFactory target)
            throws IOException, RemoteException {
        copyFile(pkg.baseCodePath, "base.apk", target);
        if (!ArrayUtils.isEmpty(pkg.splitNames)) {
            for (int i = 0; i < pkg.splitNames.length; i++) {
                copyFile(pkg.splitCodePaths[i], "split_" + pkg.splitNames[i] + ".apk", target);
            }
        }

        return PackageManager.INSTALL_SUCCEEDED;
    }

    private void copyFile(String sourcePath, String targetName,
            IParcelFileDescriptorFactory target) throws IOException, RemoteException {
        Slog.d(TAG, "Copying " + sourcePath + " to " + targetName);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(sourcePath);
            out = new ParcelFileDescriptor.AutoCloseOutputStream(
                    target.open(targetName, ParcelFileDescriptor.MODE_READ_WRITE));
            Streams.copy(in, out);
        } finally {
            IoUtils.closeQuietly(out);
            IoUtils.closeQuietly(in);
        }
    }

    private static final int PREFER_INTERNAL = 1;
    private static final int PREFER_EXTERNAL = 2;

    private int recommendAppInstallLocation(PackageLite pkg, int flags, long threshold,
            String abiOverride) {
        int prefer;
        boolean checkBoth = false;

        final boolean isForwardLocked = (flags & PackageManager.INSTALL_FORWARD_LOCK) != 0;

        check_inner : {
            /*
             * Explicit install flags should override the manifest settings.
             */
            if ((flags & PackageManager.INSTALL_INTERNAL) != 0) {
                prefer = PREFER_INTERNAL;
                break check_inner;
            } else if ((flags & PackageManager.INSTALL_EXTERNAL) != 0) {
                prefer = PREFER_EXTERNAL;
                break check_inner;
            }

            /* No install flags. Check for manifest option. */
            if (pkg.installLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY) {
                prefer = PREFER_INTERNAL;
                break check_inner;
            } else if (pkg.installLocation == PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL) {
                prefer = PREFER_EXTERNAL;
                checkBoth = true;
                break check_inner;
            } else if (pkg.installLocation == PackageInfo.INSTALL_LOCATION_AUTO) {
                // We default to preferring internal storage.
                prefer = PREFER_INTERNAL;
                checkBoth = true;
                break check_inner;
            }

            // Pick user preference
            int installPreference = Settings.Global.getInt(getApplicationContext()
                    .getContentResolver(),
                    Settings.Global.DEFAULT_INSTALL_LOCATION,
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

        boolean fitsOnInternal = false;
        if (checkBoth || prefer == PREFER_INTERNAL) {
            try {
                fitsOnInternal = isUnderInternalThreshold(pkg, isForwardLocked, threshold);
            } catch (IOException e) {
                return PackageHelper.RECOMMEND_FAILED_INVALID_URI;
            }
        }

        boolean fitsOnSd = false;
        if (!emulated && (checkBoth || prefer == PREFER_EXTERNAL)) {
            try {
                fitsOnSd = isUnderExternalThreshold(pkg, isForwardLocked, abiOverride);
            } catch (IOException e) {
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
     * @param threshold byte threshold to compare against
     * @return true if file fits under threshold
     * @throws FileNotFoundException when APK does not exist
     */
    private boolean isUnderInternalThreshold(PackageLite pkg, boolean isForwardLocked,
            long threshold) throws IOException {
        long sizeBytes = 0;
        for (String codePath : pkg.getAllCodePaths()) {
            sizeBytes += new File(codePath).length();

            if (isForwardLocked) {
                sizeBytes += PackageHelper.extractPublicFiles(codePath, null);
            }
        }

        final StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        final long availBytes = stat.getAvailableBytes();
        return (availBytes - sizeBytes) > threshold;
    }

    /**
     * Measure a file to see if it fits in the external free space.
     *
     * @return true if file fits
     * @throws IOException when file does not exist
     */
    private boolean isUnderExternalThreshold(PackageLite pkg, boolean isForwardLocked,
            String abiOverride) throws IOException {
        if (Environment.isExternalStorageEmulated()) {
            return false;
        }

        final int sizeMb = calculateContainerSize(pkg, isForwardLocked, abiOverride);

        final int availSdMb;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            final StatFs sdStats = new StatFs(Environment.getExternalStorageDirectory().getPath());
            final int blocksToMb = (1 << 20) / sdStats.getBlockSize();
            availSdMb = sdStats.getAvailableBlocks() * blocksToMb;
        } else {
            availSdMb = -1;
        }

        return availSdMb > sizeMb;
    }

    private int calculateContainerSize(PackageLite pkg, boolean isForwardLocked, String abiOverride)
            throws IOException {
        NativeLibraryHelper.Handle handle = null;
        try {
            handle = NativeLibraryHelper.Handle.create(pkg);
            return calculateContainerSize(pkg, handle, isForwardLocked,
                    calculateAbiList(handle, abiOverride, pkg.multiArch));
        } finally {
            IoUtils.closeQuietly(handle);
        }
    }

    private String[] calculateAbiList(NativeLibraryHelper.Handle handle, String abiOverride,
                                      boolean isMultiArch) throws IOException {
        if (isMultiArch) {
            final int abi32 = NativeLibraryHelper.findSupportedAbi(handle, Build.SUPPORTED_32_BIT_ABIS);
            final int abi64 = NativeLibraryHelper.findSupportedAbi(handle, Build.SUPPORTED_64_BIT_ABIS);

            if (abi32 >= 0 && abi64 >= 0) {
                return new String[] { Build.SUPPORTED_64_BIT_ABIS[abi64], Build.SUPPORTED_32_BIT_ABIS[abi32] };
            } else if (abi64 >= 0) {
                return new String[] { Build.SUPPORTED_64_BIT_ABIS[abi64] };
            } else if (abi32 >= 0) {
                return new String[] { Build.SUPPORTED_32_BIT_ABIS[abi32] };
            }

            if (abi64 != PackageManager.NO_NATIVE_LIBRARIES || abi32 != PackageManager.NO_NATIVE_LIBRARIES) {
                throw new IOException("Error determining ABI list: errorCode=[" + abi32 + "," + abi64 + "]");
            }

        } else {
            String[] abiList = Build.SUPPORTED_ABIS;
            if (abiOverride != null) {
                abiList = new String[] { abiOverride };
            } else if (Build.SUPPORTED_64_BIT_ABIS.length > 0 &&
                    NativeLibraryHelper.hasRenderscriptBitcode(handle)) {
                abiList = Build.SUPPORTED_32_BIT_ABIS;
            }

            final int abi = NativeLibraryHelper.findSupportedAbi(handle,abiList);
            if (abi >= 0) {
               return new String[]{Build.SUPPORTED_ABIS[abi]};
            }

            if (abi != PackageManager.NO_NATIVE_LIBRARIES) {
                throw new IOException("Error determining ABI list: errorCode=" + abi);
            }
        }

        return null;
    }

    /**
     * Calculate the container size for a package.
     * 
     * @return size in megabytes (2^20 bytes)
     * @throws IOException when there is a problem reading the file
     */
    private int calculateContainerSize(PackageLite pkg, NativeLibraryHelper.Handle handle,
            boolean isForwardLocked, String[] abis) throws IOException {
        // Calculate size of container needed to hold APKs.
        long sizeBytes = 0;
        for (String codePath : pkg.getAllCodePaths()) {
            sizeBytes += new File(codePath).length();

            if (isForwardLocked) {
                sizeBytes += PackageHelper.extractPublicFiles(codePath, null);
            }
        }

        // Check all the native files that need to be copied and add that to the
        // container size.
        if (abis != null) {
            sizeBytes += NativeLibraryHelper.sumNativeBinariesLI(handle, abis);
        }

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
