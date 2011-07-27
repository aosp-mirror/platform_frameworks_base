package com.android.internal.content;

import android.os.Build;
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

    private static native long nativeSumNativeBinaries(String file, String cpuAbi, String cpuAbi2);

    public static long sumNativeBinariesLI(File apkFile) {
        final String cpuAbi = Build.CPU_ABI;
        final String cpuAbi2 = Build.CPU_ABI2;
        return nativeSumNativeBinaries(apkFile.getPath(), cpuAbi, cpuAbi2);
    }

    private native static int nativeCopyNativeBinaries(String filePath, String sharedLibraryPath,
            String cpuAbi, String cpuAbi2);

    public static int copyNativeBinariesIfNeededLI(File apkFile, File sharedLibraryDir) {
        final String cpuAbi = Build.CPU_ABI;
        final String cpuAbi2 = Build.CPU_ABI2;
        return nativeCopyNativeBinaries(apkFile.getPath(), sharedLibraryDir.getPath(), cpuAbi,
                cpuAbi2);
    }

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
