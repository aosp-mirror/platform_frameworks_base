package com.android.internal.content;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.FileUtils;
import android.os.SystemProperties;
import android.util.Config;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Native libraries helper.
 *
 * @hide
 */
public class NativeLibraryHelper {
    private static final String TAG = "NativeHelper";

    private static final boolean DEBUG_NATIVE = false;

    /*
     * The following constants are returned by listPackageSharedLibsForAbiLI
     * to indicate if native shared libraries were found in the package.
     * Values are:
     *    PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES => native libraries found and installed
     *    PACKAGE_INSTALL_NATIVE_NO_LIBRARIES     => no native libraries in package
     *    PACKAGE_INSTALL_NATIVE_ABI_MISMATCH     => native libraries for another ABI found
     *                                        in package (and not installed)
     *
     */
    private static final int PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES = 0;
    private static final int PACKAGE_INSTALL_NATIVE_NO_LIBRARIES = 1;
    private static final int PACKAGE_INSTALL_NATIVE_ABI_MISMATCH = 2;

    // Directory in the APK that holds all the native shared libraries.
    private static final String APK_LIB = "lib/";
    private static final int APK_LIB_LENGTH = APK_LIB.length();

    // Prefix that native shared libraries must have.
    private static final String LIB_PREFIX = "lib";
    private static final int LIB_PREFIX_LENGTH = LIB_PREFIX.length();

    // Suffix that the native shared libraries must have.
    private static final String LIB_SUFFIX = ".so";
    private static final int LIB_SUFFIX_LENGTH = LIB_SUFFIX.length();

    // Name of the GDB binary.
    private static final String GDBSERVER = "gdbserver";

    // the minimum length of a valid native shared library of the form
    // lib/<something>/lib<name>.so.
    private static final int MIN_ENTRY_LENGTH = APK_LIB_LENGTH + 2 + LIB_PREFIX_LENGTH + 1
            + LIB_SUFFIX_LENGTH;

    /*
     * Find all files of the form lib/<cpuAbi>/lib<name>.so in the .apk
     * and add them to a list to be installed later.
     *
     * NOTE: this method may throw an IOException if the library cannot
     * be copied to its final destination, e.g. if there isn't enough
     * room left on the data partition, or a ZipException if the package
     * file is malformed.
     */
    private static int listPackageSharedLibsForAbiLI(ZipFile zipFile,
            String cpuAbi, List<Pair<ZipEntry, String>> libEntries) throws IOException,
            ZipException {
        final int cpuAbiLen = cpuAbi.length();
        boolean hasNativeLibraries = false;
        boolean installedNativeLibraries = false;

        if (DEBUG_NATIVE) {
            Slog.d(TAG, "Checking " + zipFile.getName() + " for shared libraries of CPU ABI type "
                    + cpuAbi);
        }

        Enumeration<? extends ZipEntry> entries = zipFile.entries();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();

            // skip directories
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();

            /*
             * Check that the entry looks like lib/<something>/lib<name>.so
             * here, but don't check the ABI just yet.
             *
             * - must be sufficiently long
             * - must end with LIB_SUFFIX, i.e. ".so"
             * - must start with APK_LIB, i.e. "lib/"
             */
            if (entryName.length() < MIN_ENTRY_LENGTH || !entryName.endsWith(LIB_SUFFIX)
                    || !entryName.startsWith(APK_LIB)) {
                continue;
            }

            // file name must start with LIB_PREFIX, i.e. "lib"
            int lastSlash = entryName.lastIndexOf('/');

            if (lastSlash < 0
                    || !entryName.regionMatches(lastSlash + 1, LIB_PREFIX, 0, LIB_PREFIX_LENGTH)) {
                continue;
            }

            hasNativeLibraries = true;

            // check the cpuAbi now, between lib/ and /lib<name>.so
            if (lastSlash != APK_LIB_LENGTH + cpuAbiLen
                    || !entryName.regionMatches(APK_LIB_LENGTH, cpuAbi, 0, cpuAbiLen))
                continue;

            /*
             * Extract the library file name, ensure it doesn't contain
             * weird characters. we're guaranteed here that it doesn't contain
             * a directory separator though.
             */
            String libFileName = entryName.substring(lastSlash+1);
            if (!FileUtils.isFilenameSafe(new File(libFileName))) {
                continue;
            }

            installedNativeLibraries = true;

            if (DEBUG_NATIVE) {
                Log.d(TAG, "Caching shared lib " + entry.getName());
            }

            libEntries.add(Pair.create(entry, libFileName));
        }
        if (!hasNativeLibraries)
            return PACKAGE_INSTALL_NATIVE_NO_LIBRARIES;

        if (!installedNativeLibraries)
            return PACKAGE_INSTALL_NATIVE_ABI_MISMATCH;

        return PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES;
    }

    /*
     * Find the gdbserver executable program in a package at
     * lib/<cpuAbi>/gdbserver and add it to the list of binaries
     * to be copied out later.
     *
     * Returns PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES on success,
     * or PACKAGE_INSTALL_NATIVE_NO_LIBRARIES otherwise.
     */
    private static int listPackageGdbServerLI(ZipFile zipFile, String cpuAbi,
            List<Pair<ZipEntry, String>> nativeFiles) throws IOException, ZipException {
        final String apkGdbServerPath = "lib/" + cpuAbi + "/" + GDBSERVER;

        Enumeration<? extends ZipEntry> entries = zipFile.entries();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            // skip directories
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();

            if (!entryName.equals(apkGdbServerPath)) {
                continue;
            }

            if (Config.LOGD) {
                Log.d(TAG, "Found gdbserver: " + entry.getName());
            }

            final String installGdbServerPath = APK_LIB + GDBSERVER;
            nativeFiles.add(Pair.create(entry, installGdbServerPath));

            return PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES;
        }
        return PACKAGE_INSTALL_NATIVE_NO_LIBRARIES;
    }

    /*
     * Examine shared libraries stored in the APK as
     * lib/<cpuAbi>/lib<name>.so and add them to a list to be copied
     * later.
     *
     * This function will first try the main CPU ABI defined by Build.CPU_ABI
     * (which corresponds to ro.product.cpu.abi), and also try an alternate
     * one if ro.product.cpu.abi2 is defined.
     */
    public static int listPackageNativeBinariesLI(ZipFile zipFile,
            List<Pair<ZipEntry, String>> nativeFiles) throws ZipException, IOException {
        String cpuAbi = Build.CPU_ABI;

        int result = listPackageSharedLibsForAbiLI(zipFile, cpuAbi, nativeFiles);

        /*
         * Some architectures are capable of supporting several CPU ABIs
         * for example, 'armeabi-v7a' also supports 'armeabi' native code
         * this is indicated by the definition of the ro.product.cpu.abi2
         * system property.
         *
         * only scan the package twice in case of ABI mismatch
         */
        if (result == PACKAGE_INSTALL_NATIVE_ABI_MISMATCH) {
            final String cpuAbi2 = SystemProperties.get("ro.product.cpu.abi2", null);
            if (cpuAbi2 != null) {
                result = listPackageSharedLibsForAbiLI(zipFile, cpuAbi2, nativeFiles);
            }

            if (result == PACKAGE_INSTALL_NATIVE_ABI_MISMATCH) {
                Slog.w(TAG, "Native ABI mismatch from package file");
                return PackageManager.INSTALL_FAILED_INVALID_APK;
            }

            if (result == PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES) {
                cpuAbi = cpuAbi2;
            }
        }

        /*
         * Debuggable packages may have gdbserver embedded, so add it to
         * the list to the list of items to be extracted (as lib/gdbserver)
         * into the application's native library directory later.
         */
        if (result == PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES) {
            listPackageGdbServerLI(zipFile, cpuAbi, nativeFiles);
        }
        return PackageManager.INSTALL_SUCCEEDED;
    }

    public static int copyNativeBinariesLI(File scanFile, File sharedLibraryDir) {
        /*
         * Check all the native files that need to be copied and add
         * that to the container size.
         */
        ZipFile zipFile;
        try {
            zipFile = new ZipFile(scanFile);

            List<Pair<ZipEntry, String>> nativeFiles = new LinkedList<Pair<ZipEntry, String>>();

            NativeLibraryHelper.listPackageNativeBinariesLI(zipFile, nativeFiles);

            final int N = nativeFiles.size();

            for (int i = 0; i < N; i++) {
                final Pair<ZipEntry, String> entry = nativeFiles.get(i);

                File destFile = new File(sharedLibraryDir, entry.second);
                copyNativeBinaryLI(zipFile, entry.first, sharedLibraryDir, destFile);
            }
        } catch (ZipException e) {
            Slog.w(TAG, "Failed to extract data from package file", e);
            return PackageManager.INSTALL_FAILED_INVALID_APK;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to cache package shared libs", e);
            return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
        }

        return PackageManager.INSTALL_SUCCEEDED;
    }

    private static void copyNativeBinaryLI(ZipFile zipFile, ZipEntry entry,
            File binaryDir, File binaryFile) throws IOException {
        InputStream inputStream = zipFile.getInputStream(entry);
        try {
            File tempFile = File.createTempFile("tmp", "tmp", binaryDir);
            String tempFilePath = tempFile.getPath();
            // XXX package manager can't change owner, so the executable files for
            // now need to be left as world readable and owned by the system.
            if (!FileUtils.copyToFile(inputStream, tempFile)
                    || !tempFile.setLastModified(entry.getTime())
                    || FileUtils.setPermissions(tempFilePath, FileUtils.S_IRUSR | FileUtils.S_IWUSR
                            | FileUtils.S_IRGRP | FileUtils.S_IXUSR | FileUtils.S_IXGRP
                            | FileUtils.S_IXOTH | FileUtils.S_IROTH, -1, -1) != 0
                    || !tempFile.renameTo(binaryFile)) {
                // Failed to properly write file.
                tempFile.delete();
                throw new IOException("Couldn't create cached binary " + binaryFile + " in "
                        + binaryDir);
            }
        } finally {
            inputStream.close();
        }
    }
}
