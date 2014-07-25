/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.app.Application;
import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.Log;
import com.android.server.LocalServices;
import dalvik.system.VMRuntime;

import java.io.File;
import java.util.Arrays;

import com.android.internal.os.Zygote;

/**
 * Top level factory, used creating all the main WebView implementation classes.
 *
 * @hide
 */
public final class WebViewFactory {

    private static final String CHROMIUM_WEBVIEW_FACTORY =
            "com.android.webview.chromium.WebViewChromiumFactoryProvider";

    private static final String NULL_WEBVIEW_FACTORY =
            "com.android.webview.nullwebview.NullWebViewFactoryProvider";

    private static final String CHROMIUM_WEBVIEW_NATIVE_RELRO_32 =
            "/data/misc/shared_relro/libwebviewchromium32.relro";
    private static final String CHROMIUM_WEBVIEW_NATIVE_RELRO_64 =
            "/data/misc/shared_relro/libwebviewchromium64.relro";

    // TODO: remove the library paths below as we now query the package manager.
    // The remaining step is to refactor address space reservation.
    private static final String CHROMIUM_WEBVIEW_NATIVE_LIB_32 =
            "/system/lib/libwebviewchromium.so";
    private static final String CHROMIUM_WEBVIEW_NATIVE_LIB_64 =
            "/system/lib64/libwebviewchromium.so";

    private static final String LOGTAG = "WebViewFactory";

    private static final boolean DEBUG = false;

    // Cache the factory both for efficiency, and ensure any one process gets all webviews from the
    // same provider.
    private static WebViewFactoryProvider sProviderInstance;
    private static final Object sProviderLock = new Object();
    private static boolean sAddressSpaceReserved = false;

    public static String getWebViewPackageName() {
        // TODO: Make this dynamic based on resource configuration.
        return "com.android.webview";
    }

    static WebViewFactoryProvider getProvider() {
        synchronized (sProviderLock) {
            // For now the main purpose of this function (and the factory abstraction) is to keep
            // us honest and minimize usage of WebView internals when binding the proxy.
            if (sProviderInstance != null) return sProviderInstance;

            loadNativeLibrary();

            Class<WebViewFactoryProvider> providerClass;
            try {
                providerClass = getFactoryClass();
            } catch (ClassNotFoundException e) {
                Log.e(LOGTAG, "error loading provider", e);
                throw new AndroidRuntimeException(e);
            }

            StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
            try {
                sProviderInstance = providerClass.newInstance();
                if (DEBUG) Log.v(LOGTAG, "Loaded provider: " + sProviderInstance);
                return sProviderInstance;
            } catch (Exception e) {
                Log.e(LOGTAG, "error instantiating provider", e);
                throw new AndroidRuntimeException(e);
            } finally {
                StrictMode.setThreadPolicy(oldPolicy);
            }
        }
    }

    private static Class<WebViewFactoryProvider> getFactoryClass() throws ClassNotFoundException {
        Application initialApplication = AppGlobals.getInitialApplication();
        try {
            Context webViewContext = initialApplication.createPackageContext(
                    getWebViewPackageName(),
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            initialApplication.getAssets().addAssetPath(
                    webViewContext.getApplicationInfo().sourceDir);
            ClassLoader clazzLoader = webViewContext.getClassLoader();
            return (Class<WebViewFactoryProvider>) Class.forName(CHROMIUM_WEBVIEW_FACTORY, true,
                                                                 clazzLoader);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOGTAG, "Chromium WebView package does not exist");
            return (Class<WebViewFactoryProvider>) Class.forName(NULL_WEBVIEW_FACTORY);
        }
    }

    /**
     * Perform any WebView loading preparations that must happen in the zygote.
     * Currently, this means allocating address space to load the real JNI library later.
     */
    public static void prepareWebViewInZygote() {
        try {
            System.loadLibrary("webviewchromium_loader");
            sAddressSpaceReserved = nativeReserveAddressSpace(CHROMIUM_WEBVIEW_NATIVE_LIB_32,
                                                              CHROMIUM_WEBVIEW_NATIVE_LIB_64);
            if (sAddressSpaceReserved) {
                if (DEBUG) Log.v(LOGTAG, "address space reserved");
            } else {
                Log.e(LOGTAG, "reserving address space failed");
            }
        } catch (Throwable t) {
            // Log and discard errors at this stage as we must not crash the zygote.
            Log.e(LOGTAG, "error preparing native loader", t);
        }
    }

    /**
     * Perform any WebView loading preparations that must happen at boot from the system server,
     * after the package manager has started.
     * This must be called in the system server.
     * Currently, this means spawning the child processes which will create the relro files.
     */
    public static void prepareWebViewInSystemServer() {
        if (DEBUG) Log.v(LOGTAG, "creating relro files");
        if (new File(CHROMIUM_WEBVIEW_NATIVE_LIB_64).exists()) {
            createRelroFile(true /* is64Bit */);
        }
        if (new File(CHROMIUM_WEBVIEW_NATIVE_LIB_32).exists()) {
            createRelroFile(false /* is64Bit */);
        }
    }

    private static String[] getWebViewNativeLibraryPaths()
            throws PackageManager.NameNotFoundException {
        final String NATIVE_LIB_FILE_NAME = "libwebviewchromium.so";

        PackageManager pm = AppGlobals.getInitialApplication().getPackageManager();
        ApplicationInfo ai = pm.getApplicationInfo(getWebViewPackageName(), 0);

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
        if (!TextUtils.isEmpty(path32)) path32 += "/" + NATIVE_LIB_FILE_NAME;
        if (!TextUtils.isEmpty(path64)) path64 += "/" + NATIVE_LIB_FILE_NAME;
        return new String[] { path32, path64 };
    }

    private static void createRelroFile(final boolean is64Bit) {
        final String abi =
                is64Bit ? Build.SUPPORTED_64_BIT_ABIS[0] : Build.SUPPORTED_32_BIT_ABIS[0];

        // crashHandler is invoked by the ActivityManagerService when the isolated process crashes.
        Runnable crashHandler = new Runnable() {
            @Override
            public void run() {
                try {
                    Log.e(LOGTAG, "relro file creator for " + abi + " crashed. Proceeding without");
                    getUpdateService().notifyRelroCreationCompleted(is64Bit, false);
                } catch (RemoteException e) {
                    Log.e(LOGTAG, "Cannot reach WebViewUpdateService. " + e.getMessage());
                }
            }
        };

        try {
            String[] args = getWebViewNativeLibraryPaths();
            LocalServices.getService(ActivityManagerInternal.class).startIsolatedProcess(
                    RelroFileCreator.class.getName(), args, "WebViewLoader-" + abi, abi,
                    Process.SHARED_RELRO_UID, crashHandler);
        } catch (Throwable t) {
            // Log and discard errors as we must not crash the system server.
            Log.e(LOGTAG, "error starting relro file creator for abi " + abi, t);
            crashHandler.run();
        }
    }

    private static class RelroFileCreator {
        // Called in an unprivileged child process to create the relro file.
        public static void main(String[] args) {
            try{
                if (args.length != 2 || args[0] == null || args[1] == null) {
                    Log.e(LOGTAG, "Invalid RelroFileCreator args: " + Arrays.toString(args));
                    return;
                }

                boolean is64Bit = VMRuntime.getRuntime().is64Bit();
                Log.v(LOGTAG, "RelroFileCreator (64bit = " + is64Bit + "), " +
                        " 32-bit lib: " + args[0] + ", 64-bit lib: " + args[1]);
                if (!sAddressSpaceReserved) {
                    Log.e(LOGTAG, "can't create relro file; address space not reserved");
                    return;
                }
                boolean result = nativeCreateRelroFile(args[0] /* path32 */,
                                                       args[1] /* path64 */,
                                                       CHROMIUM_WEBVIEW_NATIVE_RELRO_32,
                                                       CHROMIUM_WEBVIEW_NATIVE_RELRO_64);
                if (!result) {
                    Log.e(LOGTAG, "failed to create relro file");
                } else if (DEBUG) {
                    Log.v(LOGTAG, "created relro file");
                }
                try {
                    getUpdateService().notifyRelroCreationCompleted(is64Bit, result);
                } catch (RemoteException e) {
                    Log.e(LOGTAG, "error notifying update service", e);
                }
            } finally {
                // Must explicitly exit or else this process will just sit around after we return.
                System.exit(0);
            }
        }
    }

    private static void loadNativeLibrary() {
        if (!sAddressSpaceReserved) {
            Log.e(LOGTAG, "can't load with relro file; address space not reserved");
            return;
        }

        try {
            getUpdateService().waitForRelroCreationCompleted(VMRuntime.getRuntime().is64Bit());
        } catch (RemoteException e) {
            Log.e(LOGTAG, "error waiting for relro creation, proceeding without", e);
            return;
        }

        try {
            String[] args = getWebViewNativeLibraryPaths();
            boolean result = nativeLoadWithRelroFile(args[0] /* path32 */,
                                                     args[1] /* path64 */,
                                                     CHROMIUM_WEBVIEW_NATIVE_RELRO_32,
                                                     CHROMIUM_WEBVIEW_NATIVE_RELRO_64);
            if (!result) {
                Log.w(LOGTAG, "failed to load with relro file, proceeding without");
            } else if (DEBUG) {
                Log.v(LOGTAG, "loaded with relro file");
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOGTAG, "Failed to list WebView package libraries for loadNativeLibrary", e);
        }
    }

    private static IWebViewUpdateService getUpdateService() {
        return IWebViewUpdateService.Stub.asInterface(ServiceManager.getService("webviewupdate"));
    }

    private static native boolean nativeReserveAddressSpace(String lib32, String lib64);
    private static native boolean nativeCreateRelroFile(String lib32, String lib64,
                                                        String relro32, String relro64);
    private static native boolean nativeLoadWithRelroFile(String lib32, String lib64,
                                                          String relro32, String relro64);
}
