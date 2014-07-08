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

import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.util.Slog;

import dalvik.system.CloseGuard;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Native libraries helper.
 *
 * @hide
 */
public class NativeLibraryHelper {
    private static final String TAG = "NativeHelper";

    private static final boolean DEBUG_NATIVE = false;

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

        final long[] apkHandles;

        public static Handle create(File packageFile) throws IOException {
            try {
                final PackageLite lite = PackageParser.parsePackageLite(packageFile, 0);
                return create(lite);
            } catch (PackageParserException e) {
                throw new IOException("Failed to parse package: " + packageFile, e);
            }
        }

        public static Handle create(PackageLite lite) throws IOException {
            final List<String> codePaths = lite.getAllCodePaths();
            final int size = codePaths.size();
            final long[] apkHandles = new long[size];
            for (int i = 0; i < size; i++) {
                final String path = codePaths.get(i);
                apkHandles[i] = nativeOpenApk(path);
                if (apkHandles[i] == 0) {
                    // Unwind everything we've opened so far
                    for (int j = 0; j < i; j++) {
                        nativeClose(apkHandles[j]);
                    }
                    throw new IOException("Unable to open APK: " + path);
                }
            }

            return new Handle(apkHandles);
        }

        Handle(long[] apkHandles) {
            this.apkHandles = apkHandles;
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
    private static native void nativeClose(long handle);

    private static native long nativeSumNativeBinaries(long handle, String cpuAbi);

    /**
     * Sums the size of native binaries in an APK for a given ABI.
     *
     * @return size of all native binary files in bytes
     */
    public static long sumNativeBinariesLI(Handle handle, String abi) {
        long sum = 0;
        for (long apkHandle : handle.apkHandles) {
            sum += nativeSumNativeBinaries(apkHandle, abi);
        }
        return sum;
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
    public static int copyNativeBinariesIfNeededLI(Handle handle, File sharedLibraryDir,
            String abi) {
        for (long apkHandle : handle.apkHandles) {
            int res = nativeCopyNativeBinaries(apkHandle, sharedLibraryDir.getPath(), abi);
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
            final int res = nativeFindSupportedAbi(apkHandle, supportedAbis);
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

    private native static int nativeFindSupportedAbi(long handle, String[] supportedAbis);

    // Convenience method to call removeNativeBinariesFromDirLI(File)
    public static boolean removeNativeBinariesLI(String nativeLibraryPath) {
        if (nativeLibraryPath == null) return false;
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

    // We don't care about the other return values for now.
    private static final int BITCODE_PRESENT = 1;

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

    private static native int hasRenderscriptBitcode(long apkHandle);
}
