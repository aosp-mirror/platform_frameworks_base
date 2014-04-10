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

import android.content.pm.PackageManager;
import android.util.Slog;

import java.io.File;

/**
 * Native libraries helper.
 *
 * @hide
 */
public class NativeLibraryHelper {
    private static final String TAG = "NativeHelper";

    private static final boolean DEBUG_NATIVE = false;

    /**
     * A handle to an opened APK. Used as input to the various NativeLibraryHelper
     * methods. Allows us to scan and parse the APK exactly once instead of doing
     * it multiple times.
     *
     * @hide
     */
    public static class ApkHandle {
        final String apkPath;
        final long apkHandle;

        public ApkHandle(String path) {
            apkPath = path;
            apkHandle = nativeOpenApk(apkPath);
        }

        public ApkHandle(File apkFile) {
            apkPath = apkFile.getPath();
            apkHandle = nativeOpenApk(apkPath);
        }

        public void close() {
            nativeClose(apkHandle);
        }
    }


    private static native long nativeOpenApk(String path);
    private static native void nativeClose(long handle);

    private static native long nativeSumNativeBinaries(long handle, String cpuAbi);

    /**
     * Sums the size of native binaries in an APK for a given ABI.
     *
     * @return size of all native binary files in bytes
     */
    public static long sumNativeBinariesLI(ApkHandle handle, String abi) {
        return nativeSumNativeBinaries(handle.apkHandle, abi);
    }

    private native static int nativeCopyNativeBinaries(long handle,
            String sharedLibraryPath, String abiToCopy);

    /**
     * Copies native binaries to a shared library directory.
     *
     * @param handle APK file to scan for native libraries
     * @param sharedLibraryDir directory for libraries to be copied to
     * @return {@link PackageManager#INSTALL_SUCCEEDED} if successful or another
     *         error code from that class if not
     */
    public static int copyNativeBinariesIfNeededLI(ApkHandle handle, File sharedLibraryDir,
            String abi) {
        return nativeCopyNativeBinaries(handle.apkHandle, sharedLibraryDir.getPath(), abi);
    }

    /**
     * Checks if a given APK contains native code for any of the provided
     * {@code supportedAbis}. Returns an index into {@code supportedAbis} if a matching
     * ABI is found, {@link PackageManager#NO_NATIVE_LIBRARIES} if the
     * APK doesn't contain any native code, and
     * {@link PackageManager#INSTALL_FAILED_NO_MATCHING_ABIS} if none of the ABIs match.
     */
    public static int findSupportedAbi(ApkHandle handle, String[] supportedAbis) {
        return nativeFindSupportedAbi(handle.apkHandle, supportedAbis);
    }

    private native static int nativeFindSupportedAbi(long handle, String[] supportedAbis);

    // Convenience method to call removeNativeBinariesFromDirLI(File)
    public static boolean removeNativeBinariesLI(String nativeLibraryPath) {
        return removeNativeBinariesFromDirLI(new File(nativeLibraryPath));
    }

    // Remove the native binaries of a given package. This simply
    // gets rid of the files in the 'lib' sub-directory.
    public static boolean removeNativeBinariesFromDirLI(File nativeLibraryDir) {
        if (DEBUG_NATIVE) {
            Slog.w(TAG, "Deleting native binaries from: " + nativeLibraryDir.getPath());
        }

        boolean deletedFiles = false;

        /*
         * Just remove any file in the directory. Since the directory is owned
         * by the 'system' UID, the application is not supposed to have written
         * anything there.
         */
        if (nativeLibraryDir.exists()) {
            final File[] binaries = nativeLibraryDir.listFiles();
            if (binaries != null) {
                for (int nn = 0; nn < binaries.length; nn++) {
                    if (DEBUG_NATIVE) {
                        Slog.d(TAG, "    Deleting " + binaries[nn].getName());
                    }

                    if (!binaries[nn].delete()) {
                        Slog.w(TAG, "Could not delete native binary: " + binaries[nn].getPath());
                    } else {
                        deletedFiles = true;
                    }
                }
            }
            // Do not delete 'lib' directory itself, or this will prevent
            // installation of future updates.
        }

        return deletedFiles;
    }
}
