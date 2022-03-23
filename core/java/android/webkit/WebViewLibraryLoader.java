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
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import dalvik.system.VMRuntime;

import java.util.Arrays;

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
                String packageName = args[0];
                String libraryFileName = args[1];
                Log.v(LOGTAG, "RelroFileCreator (64bit = " + is64Bit + "), package: "
                        + packageName + " library: " + libraryFileName);
                if (!sAddressSpaceReserved) {
                    Log.e(LOGTAG, "can't create relro file; address space not reserved");
                    return;
                }
                LoadedApk apk = ActivityThread.currentActivityThread().getPackageInfo(
                        packageName,
                        null,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                result = nativeCreateRelroFile(libraryFileName,
                                               is64Bit ? CHROMIUM_WEBVIEW_NATIVE_RELRO_64 :
                                                         CHROMIUM_WEBVIEW_NATIVE_RELRO_32,
                                               apk.getClassLoader());
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
    static void createRelroFile(final boolean is64Bit, @NonNull String packageName,
            @NonNull String libraryFileName) {
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
            boolean success = LocalServices.getService(ActivityManagerInternal.class)
                    .startIsolatedProcess(
                            RelroFileCreator.class.getName(),
                            new String[] { packageName, libraryFileName },
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
    static int prepareNativeLibraries(@NonNull PackageInfo webViewPackageInfo) {
        // TODO(torne): new way of calculating VM size
        // updateWebViewZygoteVmSize(nativeLib32bit, nativeLib64bit);
        String libraryFileName = WebViewFactory.getWebViewLibrary(
                webViewPackageInfo.applicationInfo);
        if (libraryFileName == null) {
            // Can't do anything with no filename, don't spawn any processes.
            return 0;
        }
        return createRelros(webViewPackageInfo.packageName, libraryFileName);
    }

    /**
     * @return the number of relro processes started.
     */
    private static int createRelros(@NonNull String packageName, @NonNull String libraryFileName) {
        if (DEBUG) Log.v(LOGTAG, "creating relro files");
        int numRelros = 0;

        if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
            if (DEBUG) Log.v(LOGTAG, "Create 32 bit relro");
            createRelroFile(false /* is64Bit */, packageName, libraryFileName);
            numRelros++;
        }

        if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
            if (DEBUG) Log.v(LOGTAG, "Create 64 bit relro");
            createRelroFile(true /* is64Bit */, packageName, libraryFileName);
            numRelros++;
        }
        return numRelros;
    }

    /**
     * Reserve space for the native library to be loaded into.
     */
    static void reserveAddressSpaceInZygote() {
        System.loadLibrary("webviewchromium_loader");
        boolean is64Bit = VMRuntime.getRuntime().is64Bit();
        // On 64-bit address space is really cheap and we can reserve 1GB which is plenty.
        // On 32-bit it's fairly scarce and we should keep it to a realistic number that
        // permits some future growth but doesn't hog space: we use 130MB which is roughly
        // what was calculated on older OS versions in practice.
        long addressSpaceToReserve = is64Bit ? 1 * 1024 * 1024 * 1024 : 130 * 1024 * 1024;
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

    static native boolean nativeReserveAddressSpace(long addressSpaceToReserve);
    static native boolean nativeCreateRelroFile(String lib, String relro, ClassLoader clazzLoader);
    static native int nativeLoadWithRelroFile(String lib, String relro, ClassLoader clazzLoader);
}
