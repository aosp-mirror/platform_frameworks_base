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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * @hide
 */
@VisibleForTesting
public class WebViewLibraryLoader {
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
                if (args.length != 1 || args[0] == null) {
                    Log.e(LOGTAG, "Invalid RelroFileCreator args: " + Arrays.toString(args));
                    return;
                }
                Log.v(LOGTAG, "RelroFileCreator (64bit = " + is64Bit + "), lib: " + args[0]);
                if (!sAddressSpaceReserved) {
                    Log.e(LOGTAG, "can't create relro file; address space not reserved");
                    return;
                }
                result = nativeCreateRelroFile(args[0] /* path */,
                                               is64Bit ? CHROMIUM_WEBVIEW_NATIVE_RELRO_64 :
                                                         CHROMIUM_WEBVIEW_NATIVE_RELRO_32);
                if (result && DEBUG) Log.v(LOGTAG, "created relro file");
            } finally {
                // We must do our best to always notify the update service, even if something fails.
                try {
                    WebViewFactory.getUpdateServiceUnchecked().notifyRelroCreationCompleted();
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
    static void createRelroFile(final boolean is64Bit, @NonNull WebViewNativeLibrary nativeLib) {
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
            if (nativeLib == null || nativeLib.path == null) {
                throw new IllegalArgumentException(
                        "Native library paths to the WebView RelRo process must not be null!");
            }
            boolean success = LocalServices.getService(ActivityManagerInternal.class)
                    .startIsolatedProcess(
                            RelroFileCreator.class.getName(), new String[] { nativeLib.path },
                            "WebViewLoader-" + abi, abi, Process.SHARED_RELRO_UID, crashHandler);
            if (!success) throw new Exception("Failed to start the relro file creator process");
        } catch (Throwable t) {
            // Log and discard errors as we must not crash the system server.
            Log.e(LOGTAG, "error starting relro file creator for abi " + abi, t);
            crashHandler.run();
        }
    }

    /**
     * Perform preparations needed to allow loading WebView from an application. This method should
     * be called whenever we change WebView provider.
     * @return the number of relro processes started.
     */
    static int prepareNativeLibraries(PackageInfo webviewPackageInfo)
            throws WebViewFactory.MissingWebViewPackageException {
        WebViewNativeLibrary nativeLib32bit =
                getWebViewNativeLibrary(webviewPackageInfo, false /* is64bit */);
        WebViewNativeLibrary nativeLib64bit =
                getWebViewNativeLibrary(webviewPackageInfo, true /* is64bit */);
        updateWebViewZygoteVmSize(nativeLib32bit, nativeLib64bit);

        return createRelros(nativeLib32bit, nativeLib64bit);
    }

    /**
     * @return the number of relro processes started.
     */
    private static int createRelros(@Nullable WebViewNativeLibrary nativeLib32bit,
            @Nullable WebViewNativeLibrary nativeLib64bit) {
        if (DEBUG) Log.v(LOGTAG, "creating relro files");
        int numRelros = 0;

        if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
            if (nativeLib32bit == null) {
                Log.e(LOGTAG, "No 32-bit WebView library path, skipping relro creation.");
            } else {
                if (DEBUG) Log.v(LOGTAG, "Create 32 bit relro");
                createRelroFile(false /* is64Bit */, nativeLib32bit);
                numRelros++;
            }
        }

        if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
            if (nativeLib64bit == null) {
                Log.e(LOGTAG, "No 64-bit WebView library path, skipping relro creation.");
            } else {
                if (DEBUG) Log.v(LOGTAG, "Create 64 bit relro");
                createRelroFile(true /* is64Bit */, nativeLib64bit);
                numRelros++;
            }
        }
        return numRelros;
    }

    /**
     *
     * @return the native WebView libraries in the new WebView APK.
     */
    private static void updateWebViewZygoteVmSize(
            @Nullable WebViewNativeLibrary nativeLib32bit,
            @Nullable WebViewNativeLibrary nativeLib64bit)
            throws WebViewFactory.MissingWebViewPackageException {
        // Find the native libraries of the new WebView package, to change the size of the
        // memory region in the Zygote reserved for the library.
        long newVmSize = 0L;

        if (nativeLib32bit != null) newVmSize = Math.max(newVmSize, nativeLib32bit.size);
        if (nativeLib64bit != null) newVmSize = Math.max(newVmSize, nativeLib64bit.size);

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
     *
     * <p class="note"><b>Note:</b> Assumes that we have waited for relro creation.
     *
     * @param clazzLoader class loader used to find the linker namespace to load the library into.
     * @param libraryFileName the filename of the library to load.
     */
    public static int loadNativeLibrary(ClassLoader clazzLoader, String libraryFileName) {
        if (!sAddressSpaceReserved) {
            Log.e(LOGTAG, "can't load with relro file; address space not reserved");
            return WebViewFactory.LIBLOAD_ADDRESS_SPACE_NOT_RESERVED;
        }

        String relroPath = VMRuntime.getRuntime().is64Bit() ? CHROMIUM_WEBVIEW_NATIVE_RELRO_64 :
                                                              CHROMIUM_WEBVIEW_NATIVE_RELRO_32;
        int result = nativeLoadWithRelroFile(libraryFileName, relroPath, clazzLoader);
        if (result != WebViewFactory.LIBLOAD_SUCCESS) {
            Log.w(LOGTAG, "failed to load with relro file, proceeding without");
        } else if (DEBUG) {
            Log.v(LOGTAG, "loaded with relro file");
        }
        return result;
    }

    /**
     * Fetch WebView's native library paths from {@param packageInfo}.
     * @hide
     */
    @Nullable
    @VisibleForTesting
    public static WebViewNativeLibrary getWebViewNativeLibrary(PackageInfo packageInfo,
            boolean is64bit) throws WebViewFactory.MissingWebViewPackageException {
        ApplicationInfo ai = packageInfo.applicationInfo;
        final String nativeLibFileName = WebViewFactory.getWebViewLibrary(ai);

        String dir = getWebViewNativeLibraryDirectory(ai, is64bit /* 64bit */);

        WebViewNativeLibrary lib = findNativeLibrary(ai, nativeLibFileName,
                is64bit ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS, dir);

        if (DEBUG) {
            Log.v(LOGTAG, String.format("Native %d-bit lib: %s", is64bit ? 64 : 32, lib.path));
        }
        return lib;
    }

    /**
     * @return the directory of the native WebView library with bitness {@param is64bit}.
     * @hide
     */
    @VisibleForTesting
    public static String getWebViewNativeLibraryDirectory(ApplicationInfo ai, boolean is64bit) {
        // Primary arch has the same bitness as the library we are looking for.
        if (is64bit == VMRuntime.is64BitAbi(ai.primaryCpuAbi)) return ai.nativeLibraryDir;

        // Secondary arch has the same bitness as the library we are looking for.
        if (!TextUtils.isEmpty(ai.secondaryCpuAbi)) {
            return ai.secondaryNativeLibraryDir;
        }

        return "";
    }

    /**
     * @return an object describing a native WebView library given the directory path of that
     * library, or null if the library couldn't be found.
     */
    @Nullable
    private static WebViewNativeLibrary findNativeLibrary(ApplicationInfo ai,
            String nativeLibFileName, String[] abiList, String libDirectory)
            throws WebViewFactory.MissingWebViewPackageException {
        if (TextUtils.isEmpty(libDirectory)) return null;
        String libPath = libDirectory + "/" + nativeLibFileName;
        File f = new File(libPath);
        if (f.exists()) {
            return new WebViewNativeLibrary(libPath, f.length());
        } else {
            return getLoadFromApkPath(ai.sourceDir, abiList, nativeLibFileName);
        }
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public static class WebViewNativeLibrary {
        public final String path;
        public final long size;

        WebViewNativeLibrary(String path, long size) {
            this.path = path;
            this.size = size;
        }
    }

    private static WebViewNativeLibrary getLoadFromApkPath(String apkPath,
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
                    return new WebViewNativeLibrary(apkPath + "!/" + entry, e.getSize());
                }
            }
        } catch (IOException e) {
            throw new WebViewFactory.MissingWebViewPackageException(e);
        }
        return null;
    }

    /**
     * Sets the size of the memory area in which to store the relro section.
     */
    private static void setWebViewZygoteVmSize(long vmSize) {
        SystemProperties.set(WebViewFactory.CHROMIUM_WEBVIEW_VMSIZE_SIZE_PROPERTY,
                Long.toString(vmSize));
    }

    static native boolean nativeReserveAddressSpace(long addressSpaceToReserve);
    static native boolean nativeCreateRelroFile(String lib, String relro);
    static native int nativeLoadWithRelroFile(String lib, String relro, ClassLoader clazzLoader);
}
