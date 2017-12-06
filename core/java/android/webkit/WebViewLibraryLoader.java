/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.webkit;

import android.app.ActivityManagerInternal;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.LocalServices;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

class WebViewLibraryLoader {
    private static final String LOGTAG = WebViewLibraryLoader.class.getSimpleName();

    private static final String CHROMIUM_WEBVIEW_NATIVE_RELRO_32 =
            "/data/misc/shared_relro/libwebviewchromium32.relro";
    private static final String CHROMIUM_WEBVIEW_NATIVE_RELRO_64 =
            "/data/misc/shared_relro/libwebviewchromium64.relro";
    private static final long CHROMIUM_WEBVIEW_DEFAULT_VMSIZE_BYTES = 100 * 1024 * 1024;

    private static final boolean DEBUG = false;

    private static boolean sAddressSpaceReserved = false;

    /**
     * Private class for running the actual relro creation in an unprivileged child process.
     * RelroFileCreator is a static class (without access to the outer class) to avoid accidentally
     * using any static members from the outer class. Those members will in reality differ between
     * the child process in which RelroFileCreator operates, and the app process in which the static
     * members of this class are used.
     */
    private static class RelroFileCreator {
        // Called in an unprivileged child process to create the relro file.
        public static void main(String[] args) {
            boolean result = false;
            boolean is64Bit = VMRuntime.getRuntime().is64Bit();
            try {
                if (args.length != 2 || args[0] == null || args[1] == null) {
                    Log.e(LOGTAG, "Invalid RelroFileCreator args: " + Arrays.toString(args));
                    return;
                }
                Log.v(LOGTAG, "RelroFileCreator (64bit = " + is64Bit + "), "
                        + " 32-bit lib: " + args[0] + ", 64-bit lib: " + args[1]);
                if (!sAddressSpaceReserved) {
                    Log.e(LOGTAG, "can't create relro file; address space not reserved");
                    return;
                }
                result = nativeCreateRelroFile(args[0] /* path32 */,
                                               args[1] /* path64 */,
                                               CHROMIUM_WEBVIEW_NATIVE_RELRO_32,
                                               CHROMIUM_WEBVIEW_NATIVE_RELRO_64);
                if (result && DEBUG) Log.v(LOGTAG, "created relro file");
            } finally {
                // We must do our best to always notify the update service, even if something fails.
                try {
                    WebViewFactory.getUpdateService().notifyRelroCreationCompleted();
                } catch (RemoteException e) {
                    Log.e(LOGTAG, "error notifying update service", e);
                }

                if (!result) Log.e(LOGTAG, "failed to create relro file");

                // Must explicitly exit or else this process will just sit around after we return.
                System.exit(0);
            }
        }
    }

    /**
     * Create a single relro file by invoking an isolated process that to do the actual work.
     */
    static void createRelroFile(final boolean is64Bit, String[] nativeLibraryPaths) {
        final String abi =
                is64Bit ? Build.SUPPORTED_64_BIT_ABIS[0] : Build.SUPPORTED_32_BIT_ABIS[0];

        // crashHandler is invoked by the ActivityManagerService when the isolated process crashes.
        Runnable crashHandler = new Runnable() {
            @Override
            public void run() {
                try {
                    Log.e(LOGTAG, "relro file creator for " + abi + " crashed. Proceeding without");
                    WebViewFactory.getUpdateService().notifyRelroCreationCompleted();
                } catch (RemoteException e) {
                    Log.e(LOGTAG, "Cannot reach WebViewUpdateService. " + e.getMessage());
                }
            }
        };

        try {
            if (nativeLibraryPaths == null
                    || nativeLibraryPaths[0] == null || nativeLibraryPaths[1] == null) {
                throw new IllegalArgumentException(
                        "Native library paths to the WebView RelRo process must not be null!");
            }
            int pid = LocalServices.getService(ActivityManagerInternal.class).startIsolatedProcess(
                    RelroFileCreator.class.getName(), nativeLibraryPaths,
                    "WebViewLoader-" + abi, abi, Process.SHARED_RELRO_UID, crashHandler);
            if (pid <= 0) throw new Exception("Failed to start the relro file creator process");
        } catch (Throwable t) {
            // Log and discard errors as we must not crash the system server.
            Log.e(LOGTAG, "error starting relro file creator for abi " + abi, t);
            crashHandler.run();
        }
    }

    /**
     *
     * @return the native WebView libraries in the new WebView APK.
     */
    static String[] updateWebViewZygoteVmSize(PackageInfo packageInfo)
            throws WebViewFactory.MissingWebViewPackageException {
        // Find the native libraries of the new WebView package, to change the size of the
        // memory region in the Zygote reserved for the library.
        String[] nativeLibs = getWebViewNativeLibraryPaths(packageInfo);
        if (nativeLibs != null) {
            long newVmSize = 0L;

            for (String path : nativeLibs) {
                if (path == null || TextUtils.isEmpty(path)) continue;
                if (DEBUG) Log.d(LOGTAG, "Checking file size of " + path);
                File f = new File(path);
                if (f.exists()) {
                    newVmSize = Math.max(newVmSize, f.length());
                    continue;
                }
                if (path.contains("!/")) {
                    String[] split = TextUtils.split(path, "!/");
                    if (split.length == 2) {
                        try (ZipFile z = new ZipFile(split[0])) {
                            ZipEntry e = z.getEntry(split[1]);
                            if (e != null && e.getMethod() == ZipEntry.STORED) {
                                newVmSize = Math.max(newVmSize, e.getSize());
                                continue;
                            }
                        }
                        catch (IOException e) {
                            Log.e(LOGTAG, "error reading APK file " + split[0] + ", ", e);
                        }
                    }
                }
                Log.e(LOGTAG, "error sizing load for " + path);
            }

            if (DEBUG) {
                Log.v(LOGTAG, "Based on library size, need " + newVmSize
                        + " bytes of address space.");
            }
            // The required memory can be larger than the file on disk (due to .bss), and an
            // upgraded version of the library will likely be larger, so always attempt to
            // reserve twice as much as we think to allow for the library to grow during this
            // boot cycle.
            newVmSize = Math.max(2 * newVmSize, CHROMIUM_WEBVIEW_DEFAULT_VMSIZE_BYTES);
            Log.d(LOGTAG, "Setting new address space to " + newVmSize);
            setWebViewZygoteVmSize(newVmSize);
        }
        return nativeLibs;
    }

    /**
     * Reserve space for the native library to be loaded into.
     */
    static void reserveAddressSpaceInZygote() {
        System.loadLibrary("webviewchromium_loader");
        long addressSpaceToReserve =
                SystemProperties.getLong(WebViewFactory.CHROMIUM_WEBVIEW_VMSIZE_SIZE_PROPERTY,
                CHROMIUM_WEBVIEW_DEFAULT_VMSIZE_BYTES);
        sAddressSpaceReserved = nativeReserveAddressSpace(addressSpaceToReserve);

        if (sAddressSpaceReserved) {
            if (DEBUG) {
                Log.v(LOGTAG, "address space reserved: " + addressSpaceToReserve + " bytes");
            }
        } else {
            Log.e(LOGTAG, "reserving " + addressSpaceToReserve + " bytes of address space failed");
        }
    }

    /**
     * Load WebView's native library into the current process.
     * Note: assumes that we have waited for relro creation.
     * @param clazzLoader class loader used to find the linker namespace to load the library into.
     * @param packageInfo the package from which WebView is loaded.
     */
    static int loadNativeLibrary(ClassLoader clazzLoader, PackageInfo packageInfo)
            throws WebViewFactory.MissingWebViewPackageException {
        if (!sAddressSpaceReserved) {
            Log.e(LOGTAG, "can't load with relro file; address space not reserved");
            return WebViewFactory.LIBLOAD_ADDRESS_SPACE_NOT_RESERVED;
        }

        final String libraryFileName =
                WebViewFactory.getWebViewLibrary(packageInfo.applicationInfo);
        int result = nativeLoadWithRelroFile(libraryFileName, CHROMIUM_WEBVIEW_NATIVE_RELRO_32,
                                             CHROMIUM_WEBVIEW_NATIVE_RELRO_64, clazzLoader);
        if (result != WebViewFactory.LIBLOAD_SUCCESS) {
            Log.w(LOGTAG, "failed to load with relro file, proceeding without");
        } else if (DEBUG) {
            Log.v(LOGTAG, "loaded with relro file");
        }
        return result;
    }

    /**
     * Fetch WebView's native library paths from {@param packageInfo}.
     */
    static String[] getWebViewNativeLibraryPaths(PackageInfo packageInfo)
            throws WebViewFactory.MissingWebViewPackageException {
        ApplicationInfo ai = packageInfo.applicationInfo;
        final String nativeLibFileName = WebViewFactory.getWebViewLibrary(ai);

        String path32;
        String path64;
        boolean primaryArchIs64bit = VMRuntime.is64BitAbi(ai.primaryCpuAbi);
        if (!TextUtils.isEmpty(ai.secondaryCpuAbi)) {
            // Multi-arch case.
            if (primaryArchIs64bit) {
                // Primary arch: 64-bit, secondary: 32-bit.
                path64 = ai.nativeLibraryDir;
                path32 = ai.secondaryNativeLibraryDir;
            } else {
                // Primary arch: 32-bit, secondary: 64-bit.
                path64 = ai.secondaryNativeLibraryDir;
                path32 = ai.nativeLibraryDir;
            }
        } else if (primaryArchIs64bit) {
            // Single-arch 64-bit.
            path64 = ai.nativeLibraryDir;
            path32 = "";
        } else {
            // Single-arch 32-bit.
            path32 = ai.nativeLibraryDir;
            path64 = "";
        }

        // Form the full paths to the extracted native libraries.
        // If libraries were not extracted, try load from APK paths instead.
        if (!TextUtils.isEmpty(path32)) {
            path32 += "/" + nativeLibFileName;
            File f = new File(path32);
            if (!f.exists()) {
                path32 = getLoadFromApkPath(ai.sourceDir,
                                            Build.SUPPORTED_32_BIT_ABIS,
                                            nativeLibFileName);
            }
        }
        if (!TextUtils.isEmpty(path64)) {
            path64 += "/" + nativeLibFileName;
            File f = new File(path64);
            if (!f.exists()) {
                path64 = getLoadFromApkPath(ai.sourceDir,
                                            Build.SUPPORTED_64_BIT_ABIS,
                                            nativeLibFileName);
            }
        }

        if (DEBUG) Log.v(LOGTAG, "Native 32-bit lib: " + path32 + ", 64-bit lib: " + path64);
        return new String[] { path32, path64 };
    }

    private static String getLoadFromApkPath(String apkPath,
                                             String[] abiList,
                                             String nativeLibFileName)
            throws WebViewFactory.MissingWebViewPackageException {
        // Search the APK for a native library conforming to a listed ABI.
        try (ZipFile z = new ZipFile(apkPath)) {
            for (String abi : abiList) {
                final String entry = "lib/" + abi + "/" + nativeLibFileName;
                ZipEntry e = z.getEntry(entry);
                if (e != null && e.getMethod() == ZipEntry.STORED) {
                    // Return a path formatted for dlopen() load from APK.
                    return apkPath + "!/" + entry;
                }
            }
        } catch (IOException e) {
            throw new WebViewFactory.MissingWebViewPackageException(e);
        }
        return "";
    }

    /**
     * Sets the size of the memory area in which to store the relro section.
     */
    private static void setWebViewZygoteVmSize(long vmSize) {
        SystemProperties.set(WebViewFactory.CHROMIUM_WEBVIEW_VMSIZE_SIZE_PROPERTY,
                Long.toString(vmSize));
    }

    static native boolean nativeReserveAddressSpace(long addressSpaceToReserve);
    static native boolean nativeCreateRelroFile(String lib32, String lib64,
                                                        String relro32, String relro64);
    static native int nativeLoadWithRelroFile(String lib, String relro32, String relro64,
                                                      ClassLoader clazzLoader);
}
