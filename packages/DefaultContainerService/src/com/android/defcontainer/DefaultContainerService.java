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

import static com.android.internal.content.NativeLibraryHelper.LIB_DIR_NAME;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageCleanItem;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.content.res.ObbInfo;
import android.content.res.ObbScanner;
import android.os.Binder;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;
import android.util.Slog;

import com.android.internal.app.IMediaContainerService;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.os.IParcelFileDescriptorFactory;
import com.android.internal.util.ArrayUtils;

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.File;
import java.io.FileInputStream;
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
                Slog.w(TAG, "Failed to copy package at " + packagePath, e);
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
        public PackageInfoLite getMinimalPackageInfo(String packagePath, int flags,
                String abiOverride) {
            final Context context = DefaultContainerService.this;
            final boolean isForwardLocked = (flags & PackageManager.INSTALL_FORWARD_LOCK) != 0;

            PackageInfoLite ret = new PackageInfoLite();
            if (packagePath == null) {
                Slog.i(TAG, "Invalid package file " + packagePath);
                ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
                return ret;
            }

            final File packageFile = new File(packagePath);
            final PackageParser.PackageLite pkg;
            final long sizeBytes;
            try {
                pkg = PackageParser.parsePackageLite(packageFile, 0);
                sizeBytes = PackageHelper.calculateInstalledSize(pkg, isForwardLocked, abiOverride);
            } catch (PackageParserException | IOException e) {
                Slog.w(TAG, "Failed to parse package at " + packagePath + ": " + e);

                if (!packageFile.exists()) {
                    ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_URI;
                } else {
                    ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
                }

                return ret;
            }

            final int recommendedInstallLocation;
            final long token = Binder.clearCallingIdentity();
            try {
                recommendedInstallLocation = PackageHelper.resolveInstallLocation(context,
                        pkg.packageName, pkg.installLocation, sizeBytes, flags);
            } finally {
                Binder.restoreCallingIdentity(token);
            }

            ret.packageName = pkg.packageName;
            ret.splitNames = pkg.splitNames;
            ret.versionCode = pkg.versionCode;
            ret.baseRevisionCode = pkg.baseRevisionCode;
            ret.splitRevisionCodes = pkg.splitRevisionCodes;
            ret.installLocation = pkg.installLocation;
            ret.verifiers = pkg.verifiers;
            ret.recommendedInstallLocation = recommendedInstallLocation;
            ret.multiArch = pkg.multiArch;

            return ret;
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
                return PackageHelper.calculateInstalledSize(pkg, isForwardLocked, abiOverride);
            } catch (PackageParserException | IOException e) {
                Slog.w(TAG, "Failed to calculate installed size: " + e);
                return Long.MAX_VALUE;
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
            String abiOverride) throws IOException {

        // Calculate container size, rounding up to nearest MB and adding an
        // extra MB for filesystem overhead
        final long sizeBytes = PackageHelper.calculateInstalledSize(pkg, handle,
                isForwardLocked, abiOverride);

        // Create new container
        final String newMountPath = PackageHelper.createSdDir(sizeBytes, newCid, key,
                Process.myUid(), isExternal);
        if (newMountPath == null) {
            throw new IOException("Failed to create container " + newCid);
        }
        final File targetDir = new File(newMountPath);

        try {
            // Copy all APKs
            copyFile(pkg.baseCodePath, targetDir, "base.apk", isForwardLocked);
            if (!ArrayUtils.isEmpty(pkg.splitNames)) {
                for (int i = 0; i < pkg.splitNames.length; i++) {
                    copyFile(pkg.splitCodePaths[i], targetDir,
                            "split_" + pkg.splitNames[i] + ".apk", isForwardLocked);
                }
            }

            // Extract native code
            final File libraryRoot = new File(targetDir, LIB_DIR_NAME);
            final int res = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libraryRoot,
                    abiOverride);
            if (res != PackageManager.INSTALL_SUCCEEDED) {
                throw new IOException("Failed to extract native code, res=" + res);
            }

            if (!PackageHelper.finalizeSdDir(newCid)) {
                throw new IOException("Failed to finalize " + newCid);
            }

            if (PackageHelper.isContainerMounted(newCid)) {
                PackageHelper.unMountSdDir(newCid);
            }

        } catch (ErrnoException e) {
            PackageHelper.destroySdDir(newCid);
            throw e.rethrowAsIOException();
        } catch (IOException e) {
            PackageHelper.destroySdDir(newCid);
            throw e;
        }

        return newMountPath;
    }

    private int copyPackageInner(PackageLite pkg, IParcelFileDescriptorFactory target)
            throws IOException, RemoteException {
        copyFile(pkg.baseCodePath, target, "base.apk");
        if (!ArrayUtils.isEmpty(pkg.splitNames)) {
            for (int i = 0; i < pkg.splitNames.length; i++) {
                copyFile(pkg.splitCodePaths[i], target, "split_" + pkg.splitNames[i] + ".apk");
            }
        }

        return PackageManager.INSTALL_SUCCEEDED;
    }

    private void copyFile(String sourcePath, IParcelFileDescriptorFactory target, String targetName)
            throws IOException, RemoteException {
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

    private void copyFile(String sourcePath, File targetDir, String targetName,
            boolean isForwardLocked) throws IOException, ErrnoException {
        final File sourceFile = new File(sourcePath);
        final File targetFile = new File(targetDir, targetName);

        Slog.d(TAG, "Copying " + sourceFile + " to " + targetFile);
        if (!FileUtils.copyFile(sourceFile, targetFile)) {
            throw new IOException("Failed to copy " + sourceFile + " to " + targetFile);
        }

        if (isForwardLocked) {
            final String publicTargetName = PackageHelper.replaceEnd(targetName,
                    ".apk", ".zip");
            final File publicTargetFile = new File(targetDir, publicTargetName);

            PackageHelper.extractPublicFiles(sourceFile, publicTargetFile);

            Os.chmod(targetFile.getAbsolutePath(), 0640);
            Os.chmod(publicTargetFile.getAbsolutePath(), 0644);
        } else {
            Os.chmod(targetFile.getAbsolutePath(), 0644);
        }
    }
}
