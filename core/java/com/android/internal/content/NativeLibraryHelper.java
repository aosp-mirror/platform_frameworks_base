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

package com.android.internal.content;

import static android.content.pm.PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.content.pm.PackageManager.NO_NATIVE_LIBRARIES;
import static android.system.OsConstants.S_IRGRP;
import static android.system.OsConstants.S_IROTH;
import static android.system.OsConstants.S_IRWXU;
import static android.system.OsConstants.S_IXGRP;
import static android.system.OsConstants.S_IXOTH;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Build;
import android.os.IBinder;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.incremental.IIncrementalService;
import android.os.incremental.IncrementalManager;
import android.os.incremental.IncrementalStorage;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Slog;

import dalvik.system.CloseGuard;
import dalvik.system.VMRuntime;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Native libraries helper.
 *
 * @hide
 */
public class NativeLibraryHelper {
    private static final String TAG = "NativeHelper";
    private static final boolean DEBUG_NATIVE = false;

    public static final String LIB_DIR_NAME = "lib";
    public static final String LIB64_DIR_NAME = "lib64";

    // Special value for {@code PackageParser.Package#cpuAbiOverride} to indicate
    // that the cpuAbiOverride must be clear.
    public static final String CLEAR_ABI_OVERRIDE = "-";

    /**
     * A handle to an opened package, consisting of one or more APKs. Used as
     * input to the various NativeLibraryHelper methods. Allows us to scan and
     * parse the APKs exactly once instead of doing it multiple times.
     *
     * @hide
     */
    public static class Handle implements Closeable {
        private final CloseGuard mGuard = CloseGuard.get();
        private volatile boolean mClosed;

        final String[] apkPaths;
        final long[] apkHandles;
        final boolean multiArch;
        final boolean extractNativeLibs;
        final boolean debuggable;

        public static Handle create(File packageFile) throws IOException {
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final ParseResult<PackageLite> ret = ApkLiteParseUtils.parsePackageLite(input.reset(),
                    packageFile, /* flags */ 0);
            if (ret.isError()) {
                throw new IOException("Failed to parse package: " + packageFile,
                        ret.getException());
            }
            return create(ret.getResult());
        }

        public static Handle create(PackageLite lite) throws IOException {
            return create(lite.getAllApkPaths(), lite.isMultiArch(), lite.isExtractNativeLibs(),
                    lite.isDebuggable());
        }

        public static Handle create(List<String> codePaths, boolean multiArch,
                boolean extractNativeLibs, boolean debuggable) throws IOException {
            final int size = codePaths.size();
            final String[] apkPaths = new String[size];
            final long[] apkHandles = new long[size];
            for (int i = 0; i < size; i++) {
                final String path = codePaths.get(i);
                apkPaths[i] = path;
                apkHandles[i] = nativeOpenApk(path);
                if (apkHandles[i] == 0) {
                    // Unwind everything we've opened so far
                    for (int j = 0; j < i; j++) {
                        nativeClose(apkHandles[j]);
                    }
                    throw new IOException("Unable to open APK: " + path);
                }
            }

            return new Handle(apkPaths, apkHandles, multiArch, extractNativeLibs, debuggable);
        }

        public static Handle createFd(PackageLite lite, FileDescriptor fd) throws IOException {
            final long[] apkHandles = new long[1];
            final String path = lite.getBaseApkPath();
            apkHandles[0] = nativeOpenApkFd(fd, path);
            if (apkHandles[0] == 0) {
                throw new IOException("Unable to open APK " + path + " from fd " + fd);
            }

            return new Handle(new String[]{path}, apkHandles, lite.isMultiArch(),
                    lite.isExtractNativeLibs(), lite.isDebuggable());
        }

        Handle(String[] apkPaths, long[] apkHandles, boolean multiArch,
                boolean extractNativeLibs, boolean debuggable) {
            this.apkPaths = apkPaths;
            this.apkHandles = apkHandles;
            this.multiArch = multiArch;
            this.extractNativeLibs = extractNativeLibs;
            this.debuggable = debuggable;
            mGuard.open("close");
        }

        @Override
        public void close() {
            for (long apkHandle : apkHandles) {
                nativeClose(apkHandle);
            }
            mGuard.close();
            mClosed = true;
        }

        @Override
        protected void finalize() throws Throwable {
            if (mGuard != null) {
                mGuard.warnIfOpen();
            }
            try {
                if (!mClosed) {
                    close();
                }
            } finally {
                super.finalize();
            }
        }
    }

    private static native long nativeOpenApk(String path);
    private static native long nativeOpenApkFd(FileDescriptor fd, String debugPath);
    private static native void nativeClose(long handle);

    private static native long nativeSumNativeBinaries(long handle, String cpuAbi,
            boolean debuggable);

    private native static int nativeCopyNativeBinaries(long handle, String sharedLibraryPath,
            String abiToCopy, boolean extractNativeLibs, boolean debuggable);

    private static long sumNativeBinaries(Handle handle, String abi) {
        long sum = 0;
        for (long apkHandle : handle.apkHandles) {
            sum += nativeSumNativeBinaries(apkHandle, abi, handle.debuggable);
        }
        return sum;
    }

    /**
     * Copies native binaries to a shared library directory.
     *
     * @param handle APK file to scan for native libraries
     * @param sharedLibraryDir directory for libraries to be copied to
     * @return {@link PackageManager#INSTALL_SUCCEEDED} if successful or another
     *         error code from that class if not
     */
    public static int copyNativeBinaries(Handle handle, File sharedLibraryDir, String abi) {
        for (long apkHandle : handle.apkHandles) {
            int res = nativeCopyNativeBinaries(apkHandle, sharedLibraryDir.getPath(), abi,
                    handle.extractNativeLibs, handle.debuggable);
            if (res != INSTALL_SUCCEEDED) {
                return res;
            }
        }
        return INSTALL_SUCCEEDED;
    }

    /**
     * Checks if a given APK contains native code for any of the provided
     * {@code supportedAbis}. Returns an index into {@code supportedAbis} if a matching
     * ABI is found, {@link PackageManager#NO_NATIVE_LIBRARIES} if the
     * APK doesn't contain any native code, and
     * {@link PackageManager#INSTALL_FAILED_NO_MATCHING_ABIS} if none of the ABIs match.
     */
    public static int findSupportedAbi(Handle handle, String[] supportedAbis) {
        int finalRes = NO_NATIVE_LIBRARIES;
        for (long apkHandle : handle.apkHandles) {
            final int res = nativeFindSupportedAbi(apkHandle, supportedAbis, handle.debuggable);
            if (res == NO_NATIVE_LIBRARIES) {
                // No native code, keep looking through all APKs.
            } else if (res == INSTALL_FAILED_NO_MATCHING_ABIS) {
                // Found some native code, but no ABI match; update our final
                // result if we haven't found other valid code.
                if (finalRes < 0) {
                    finalRes = INSTALL_FAILED_NO_MATCHING_ABIS;
                }
            } else if (res >= 0) {
                // Found valid native code, track the best ABI match
                if (finalRes < 0 || res < finalRes) {
                    finalRes = res;
                }
            } else {
                // Unexpected error; bail
                return res;
            }
        }
        return finalRes;
    }

    private native static int nativeFindSupportedAbi(long handle, String[] supportedAbis,
            boolean debuggable);

    // Convenience method to call removeNativeBinariesFromDirLI(File)
    public static void removeNativeBinariesLI(String nativeLibraryPath) {
        if (nativeLibraryPath == null) return;
        removeNativeBinariesFromDirLI(new File(nativeLibraryPath), false /* delete root dir */);
    }

    /**
     * Remove the native binaries of a given package. This deletes the files
     */
    public static void removeNativeBinariesFromDirLI(File nativeLibraryRoot,
            boolean deleteRootDir) {
        if (DEBUG_NATIVE) {
            Slog.w(TAG, "Deleting native binaries from: " + nativeLibraryRoot.getPath());
        }

        /*
         * Just remove any file in the directory. Since the directory is owned
         * by the 'system' UID, the application is not supposed to have written
         * anything there.
         */
        if (nativeLibraryRoot.exists()) {
            final File[] files = nativeLibraryRoot.listFiles();
            if (files != null) {
                for (int nn = 0; nn < files.length; nn++) {
                    if (DEBUG_NATIVE) {
                        Slog.d(TAG, "    Deleting " + files[nn].getName());
                    }

                    if (files[nn].isDirectory()) {
                        removeNativeBinariesFromDirLI(files[nn], true /* delete root dir */);
                    } else if (!files[nn].delete()) {
                        Slog.w(TAG, "Could not delete native binary: " + files[nn].getPath());
                    }
                }
            }
            // Do not delete 'lib' directory itself, unless we're specifically
            // asked to or this will prevent installation of future updates.
            if (deleteRootDir) {
                if (!nativeLibraryRoot.delete()) {
                    Slog.w(TAG, "Could not delete native binary directory: " +
                            nativeLibraryRoot.getPath());
                }
            }
        }
    }

    /**
     * @hide
     */
    public static void createNativeLibrarySubdir(File path) throws IOException {
        if (!path.isDirectory()) {
            path.delete();

            if (!path.mkdir()) {
                throw new IOException("Cannot create " + path.getPath());
            }

            try {
                Os.chmod(path.getPath(), S_IRWXU | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
            } catch (ErrnoException e) {
                throw new IOException("Cannot chmod native library directory "
                        + path.getPath(), e);
            }
        } else if (!SELinux.restorecon(path)) {
            throw new IOException("Cannot set SELinux context for " + path.getPath());
        }
    }

    private static long sumNativeBinariesForSupportedAbi(Handle handle, String[] abiList) {
        int abi = findSupportedAbi(handle, abiList);
        if (abi >= 0) {
            return sumNativeBinaries(handle, abiList[abi]);
        } else {
            return 0;
        }
    }

    public static int copyNativeBinariesForSupportedAbi(Handle handle, File libraryRoot,
            String[] abiList, boolean useIsaSubdir, boolean isIncremental) throws IOException {
        /*
         * If this is an internal application or our nativeLibraryPath points to
         * the app-lib directory, unpack the libraries if necessary.
         */
        int abi = findSupportedAbi(handle, abiList);
        if (abi < 0) {
            return abi;
        }

        /*
         * If we have a matching instruction set, construct a subdir under the native
         * library root that corresponds to this instruction set.
         */
        final String supportedAbi = abiList[abi];
        final String instructionSet = VMRuntime.getInstructionSet(supportedAbi);
        final File subDir;
        if (useIsaSubdir) {
            subDir = new File(libraryRoot, instructionSet);
        } else {
            subDir = libraryRoot;
        }

        if (isIncremental) {
            int res =
                    incrementalConfigureNativeBinariesForSupportedAbi(handle, subDir, supportedAbi);
            if (res != PackageManager.INSTALL_SUCCEEDED) {
                // TODO(b/133435829): the caller of this function expects that we return the index
                // to the supported ABI. However, any non-negative integer can be a valid index.
                // We should fix this function and make sure it doesn't accidentally return an error
                // code that can also be a valid index.
                return res;
            }
            return abi;
        }

        // For non-incremental, use regular extraction and copy
        createNativeLibrarySubdir(libraryRoot);
        if (subDir != libraryRoot) {
            createNativeLibrarySubdir(subDir);
        }

        // Even if extractNativeLibs is false, we still need to check if the native libs in the APK
        // are valid. This is done in the native code.
        int copyRet = copyNativeBinaries(handle, subDir, supportedAbi);
        if (copyRet != PackageManager.INSTALL_SUCCEEDED) {
            return copyRet;
        }

        return abi;
    }

    public static int copyNativeBinariesWithOverride(Handle handle, File libraryRoot,
            String abiOverride, boolean isIncremental) {
        try {
            if (handle.multiArch) {
                // Warn if we've set an abiOverride for multi-lib packages..
                // By definition, we need to copy both 32 and 64 bit libraries for
                // such packages.
                if (abiOverride != null && !CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
                    Slog.w(TAG, "Ignoring abiOverride for multi arch application.");
                }

                int copyRet = PackageManager.NO_NATIVE_LIBRARIES;
                if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                    copyRet = copyNativeBinariesForSupportedAbi(handle, libraryRoot,
                            Build.SUPPORTED_32_BIT_ABIS, true /* use isa specific subdirs */,
                            isIncremental);
                    if (copyRet < 0 && copyRet != PackageManager.NO_NATIVE_LIBRARIES &&
                            copyRet != PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS) {
                        Slog.w(TAG, "Failure copying 32 bit native libraries; copyRet=" +copyRet);
                        return copyRet;
                    }
                }

                if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
                    copyRet = copyNativeBinariesForSupportedAbi(handle, libraryRoot,
                            Build.SUPPORTED_64_BIT_ABIS, true /* use isa specific subdirs */,
                            isIncremental);
                    if (copyRet < 0 && copyRet != PackageManager.NO_NATIVE_LIBRARIES &&
                            copyRet != PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS) {
                        Slog.w(TAG, "Failure copying 64 bit native libraries; copyRet=" +copyRet);
                        return copyRet;
                    }
                }
            } else {
                String cpuAbiOverride = null;
                if (CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
                    cpuAbiOverride = null;
                } else if (abiOverride != null) {
                    cpuAbiOverride = abiOverride;
                }

                String[] abiList = (cpuAbiOverride != null) ?
                        new String[] { cpuAbiOverride } : Build.SUPPORTED_ABIS;
                if (Build.SUPPORTED_64_BIT_ABIS.length > 0 && cpuAbiOverride == null &&
                        hasRenderscriptBitcode(handle)) {
                    abiList = Build.SUPPORTED_32_BIT_ABIS;
                }

                int copyRet = copyNativeBinariesForSupportedAbi(handle, libraryRoot, abiList,
                        true /* use isa specific subdirs */, isIncremental);
                if (copyRet < 0 && copyRet != PackageManager.NO_NATIVE_LIBRARIES) {
                    Slog.w(TAG, "Failure copying native libraries [errorCode=" + copyRet + "]");
                    return copyRet;
                }
            }

            return PackageManager.INSTALL_SUCCEEDED;
        } catch (IOException e) {
            Slog.e(TAG, "Copying native libraries failed", e);
            return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        }
    }

    public static long sumNativeBinariesWithOverride(Handle handle, String abiOverride)
            throws IOException {
        long sum = 0;
        if (handle.multiArch) {
            // Warn if we've set an abiOverride for multi-lib packages..
            // By definition, we need to copy both 32 and 64 bit libraries for
            // such packages.
            if (abiOverride != null && !CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
                Slog.w(TAG, "Ignoring abiOverride for multi arch application.");
            }

            if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                sum += sumNativeBinariesForSupportedAbi(handle, Build.SUPPORTED_32_BIT_ABIS);
            }

            if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
                sum += sumNativeBinariesForSupportedAbi(handle, Build.SUPPORTED_64_BIT_ABIS);
            }
        } else {
            String cpuAbiOverride = null;
            if (CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
                cpuAbiOverride = null;
            } else if (abiOverride != null) {
                cpuAbiOverride = abiOverride;
            }

            String[] abiList = (cpuAbiOverride != null) ?
                    new String[] { cpuAbiOverride } : Build.SUPPORTED_ABIS;
            if (Build.SUPPORTED_64_BIT_ABIS.length > 0 && cpuAbiOverride == null &&
                    hasRenderscriptBitcode(handle)) {
                abiList = Build.SUPPORTED_32_BIT_ABIS;
            }

            sum += sumNativeBinariesForSupportedAbi(handle, abiList);
        }
        return sum;
    }

    /**
     * Configure the native library files managed by Incremental Service. Makes sure Incremental
     * Service will create native library directories and set up native library binary files in the
     * same structure as they are in non-incremental installations.
     *
     * @param handle The Handle object that contains all apk paths.
     * @param libSubDir The target directory to put the native library files, e.g., lib/ or lib/arm
     * @param abi The abi that is supported by the current device.
     * @return Integer code if installation succeeds or fails.
     */
    private static int incrementalConfigureNativeBinariesForSupportedAbi(Handle handle,
            File libSubDir, String abi) {
        final String[] apkPaths = handle.apkPaths;
        if (apkPaths == null || apkPaths.length == 0) {
            Slog.e(TAG, "No apks to extract native libraries from.");
            return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        }

        final IBinder incrementalService = ServiceManager.getService(Context.INCREMENTAL_SERVICE);
        if (incrementalService == null) {
            //TODO(b/133435829): add incremental specific error codes
            return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        }
        final IncrementalManager incrementalManager = new IncrementalManager(
                IIncrementalService.Stub.asInterface(incrementalService));
        final File apkParent = new File(apkPaths[0]).getParentFile();
        IncrementalStorage incrementalStorage =
                incrementalManager.openStorage(apkParent.getAbsolutePath());
        if (incrementalStorage == null) {
            Slog.e(TAG, "Failed to find incremental storage");
            return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        }

        String libRelativeDir = getRelativePath(apkParent, libSubDir);
        if (libRelativeDir == null) {
            return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        }

        for (int i = 0; i < apkPaths.length; i++) {
            if (!incrementalStorage.configureNativeBinaries(apkPaths[i], libRelativeDir, abi,
                    handle.extractNativeLibs)) {
                return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
            }
        }
        return PackageManager.INSTALL_SUCCEEDED;
    }

    private static String getRelativePath(File base, File target) {
        try {
            final Path basePath = base.toPath();
            final Path targetPath = target.toPath();
            final Path relativePath = basePath.relativize(targetPath);
            if (relativePath.toString().isEmpty()) {
                return "";
            }
            return relativePath.toString();
        } catch (IllegalArgumentException ex) {
            Slog.e(TAG, "Failed to find relative path between: " + base.getAbsolutePath()
                    + " and: " + target.getAbsolutePath());
            return null;
        }
    }

    // We don't care about the other return values for now.
    private static final int BITCODE_PRESENT = 1;

    private static native int hasRenderscriptBitcode(long apkHandle);

    public static boolean hasRenderscriptBitcode(Handle handle) throws IOException {
        for (long apkHandle : handle.apkHandles) {
            final int res = hasRenderscriptBitcode(apkHandle);
            if (res < 0) {
                throw new IOException("Error scanning APK, code: " + res);
            } else if (res == BITCODE_PRESENT) {
                return true;
            }
        }
        return false;
    }
}
