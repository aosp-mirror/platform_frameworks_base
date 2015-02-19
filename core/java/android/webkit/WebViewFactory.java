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

import android.annotation.SystemApi;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.Trace;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.Log;

import com.android.server.LocalServices;

import dalvik.system.VMRuntime;

import java.io.File;
import java.util.Arrays;

/**
 * Top level factory, used creating all the main WebView implementation classes.
 *
 * @hide
 */
@SystemApi
public final class WebViewFactory {

    private static final String CHROMIUM_WEBVIEW_FACTORY =
            "com.android.webview.chromium.WebViewChromiumFactoryProvider";

    private static final String NULL_WEBVIEW_FACTORY =
            "com.android.webview.nullwebview.NullWebViewFactoryProvider";

    private static final String CHROMIUM_WEBVIEW_NATIVE_RELRO_32 =
            "/data/misc/shared_relro/libwebviewchromium32.relro";
    private static final String CHROMIUM_WEBVIEW_NATIVE_RELRO_64 =
            "/data/misc/shared_relro/libwebviewchromium64.relro";

    public static final String CHROMIUM_WEBVIEW_VMSIZE_SIZE_PROPERTY =
            "persist.sys.webview.vmsize";
    private static final long CHROMIUM_WEBVIEW_DEFAULT_VMSIZE_BYTES = 100 * 1024 * 1024;

    private static final String LOGTAG = "WebViewFactory";

    private static final boolean DEBUG = false;

    // Cache the factory both for efficiency, and ensure any one process gets all webviews from the
    // same provider.
    private static WebViewFactoryProvider sProviderInstance;
    private static final Object sProviderLock = new Object();
    private static boolean sAddressSpaceReserved = false;
    private static PackageInfo sPackageInfo;

    public static String getWebViewPackageName() {
        return AppGlobals.getInitialApplication().getString(
                com.android.internal.R.string.config_webViewPackageName);
    }

    public static PackageInfo getLoadedPackageInfo() {
        return sPackageInfo;
    }

    static WebViewFactoryProvider getProvider() {
        synchronized (sProviderLock) {
            // For now the main purpose of this function (and the factory abstraction) is to keep
            // us honest and minimize usage of WebView internals when binding the proxy.
            if (sProviderInstance != null) return sProviderInstance;

            final int uid = android.os.Process.myUid();
            if (uid == android.os.Process.ROOT_UID || uid == android.os.Process.SYSTEM_UID) {
                throw new UnsupportedOperationException(
                        "For security reasons, WebView is not allowed in privileged processes");
            }

            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.getProvider()");
            try {
                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.loadNativeLibrary()");
                loadNativeLibrary();
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);

                Class<WebViewFactoryProvider> providerClass;
                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.getFactoryClass()");
                try {
                    providerClass = getFactoryClass();
                } catch (ClassNotFoundException e) {
                    Log.e(LOGTAG, "error loading provider", e);
                    throw new AndroidRuntimeException(e);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
                }

                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "providerClass.newInstance()");
                try {
                    try {
                        sProviderInstance = providerClass.getConstructor(WebViewDelegate.class)
                                .newInstance(new WebViewDelegate());
                    } catch (Exception e) {
                        sProviderInstance = providerClass.newInstance();
                    }
                    if (DEBUG) Log.v(LOGTAG, "Loaded provider: " + sProviderInstance);
                    return sProviderInstance;
                } catch (Exception e) {
                    Log.e(LOGTAG, "error instantiating provider", e);
                    throw new AndroidRuntimeException(e);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
        }
    }

    private static Class<WebViewFactoryProvider> getFactoryClass() throws ClassNotFoundException {
        Application initialApplication = AppGlobals.getInitialApplication();
        try {
            // First fetch the package info so we can log the webview package version.
            String packageName = getWebViewPackageName();
            sPackageInfo = initialApplication.getPackageManager().getPackageInfo(packageName, 0);
            Log.i(LOGTAG, "Loading " + packageName + " version " + sPackageInfo.versionName +
                          " (code " + sPackageInfo.versionCode + ")");

            // Construct a package context to load the Java code into the current app.
            Context webViewContext = initialApplication.createPackageContext(packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            initialApplication.getAssets().addAssetPath(
                    webViewContext.getApplicationInfo().sourceDir);
            ClassLoader clazzLoader = webViewContext.getClassLoader();
            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "Class.forName()");
            try {
                return (Class<WebViewFactoryProvider>) Class.forName(CHROMIUM_WEBVIEW_FACTORY, true,
                                                                     clazzLoader);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // If the package doesn't exist, then try loading the null WebView instead.
            // If that succeeds, then this is a device without WebView support; if it fails then
            // swallow the failure, complain that the real WebView is missing and rethrow the
            // original exception.
            try {
                return (Class<WebViewFactoryProvider>) Class.forName(NULL_WEBVIEW_FACTORY);
            } catch (ClassNotFoundException e2) {
                // Ignore.
            }
            Log.e(LOGTAG, "Chromium WebView package does not exist", e);
            throw new AndroidRuntimeException(e);
        }
    }

    /**
     * Perform any WebView loading preparations that must happen in the zygote.
     * Currently, this means allocating address space to load the real JNI library later.
     */
    public static void prepareWebViewInZygote() {
        try {
            System.loadLibrary("webviewchromium_loader");
            long addressSpaceToReserve =
                    SystemProperties.getLong(CHROMIUM_WEBVIEW_VMSIZE_SIZE_PROPERTY,
                    CHROMIUM_WEBVIEW_DEFAULT_VMSIZE_BYTES);
            sAddressSpaceReserved = nativeReserveAddressSpace(addressSpaceToReserve);

            if (sAddressSpaceReserved) {
                if (DEBUG) {
                    Log.v(LOGTAG, "address space reserved: " + addressSpaceToReserve + " bytes");
                }
            } else {
                Log.e(LOGTAG, "reserving " + addressSpaceToReserve +
                        " bytes of address space failed");
            }
        } catch (Throwable t) {
            // Log and discard errors at this stage as we must not crash the zygote.
            Log.e(LOGTAG, "error preparing native loader", t);
        }
    }

    /**
     * Perform any WebView loading preparations that must happen at boot from the system server,
     * after the package manager has started or after an update to the webview is installed.
     * This must be called in the system server.
     * Currently, this means spawning the child processes which will create the relro files.
     */
    public static void prepareWebViewInSystemServer() {
        String[] nativePaths = null;
        try {
            nativePaths = getWebViewNativeLibraryPaths();
        } catch (Throwable t) {
            // Log and discard errors at this stage as we must not crash the system server.
            Log.e(LOGTAG, "error preparing webview native library", t);
        }
        prepareWebViewInSystemServer(nativePaths);
    }

    private static void prepareWebViewInSystemServer(String[] nativeLibraryPaths) {
        if (DEBUG) Log.v(LOGTAG, "creating relro files");

        // We must always trigger createRelRo regardless of the value of nativeLibraryPaths. Any
        // unexpected values will be handled there to ensure that we trigger notifying any process
        // waiting on relreo creation.
        if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
            if (DEBUG) Log.v(LOGTAG, "Create 32 bit relro");
            createRelroFile(false /* is64Bit */, nativeLibraryPaths);
        }

        if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
            if (DEBUG) Log.v(LOGTAG, "Create 64 bit relro");
            createRelroFile(true /* is64Bit */, nativeLibraryPaths);
        }
    }

    public static void onWebViewUpdateInstalled() {
        String[] nativeLibs = null;
        try {
            nativeLibs = WebViewFactory.getWebViewNativeLibraryPaths();
            if (nativeLibs != null) {
                long newVmSize = 0L;

                for (String path : nativeLibs) {
                    if (DEBUG) Log.d(LOGTAG, "Checking file size of " + path);
                    if (path == null) continue;
                    File f = new File(path);
                    if (f.exists()) {
                        long length = f.length();
                        if (length > newVmSize) {
                            newVmSize = length;
                        }
                    }
                }

                if (DEBUG) {
                    Log.v(LOGTAG, "Based on library size, need " + newVmSize +
                            " bytes of address space.");
                }
                // The required memory can be larger than the file on disk (due to .bss), and an
                // upgraded version of the library will likely be larger, so always attempt to
                // reserve twice as much as we think to allow for the library to grow during this
                // boot cycle.
                newVmSize = Math.max(2 * newVmSize, CHROMIUM_WEBVIEW_DEFAULT_VMSIZE_BYTES);
                Log.d(LOGTAG, "Setting new address space to " + newVmSize);
                SystemProperties.set(CHROMIUM_WEBVIEW_VMSIZE_SIZE_PROPERTY,
                        Long.toString(newVmSize));
            }
        } catch (Throwable t) {
            // Log and discard errors at this stage as we must not crash the system server.
            Log.e(LOGTAG, "error preparing webview native library", t);
        }
        prepareWebViewInSystemServer(nativeLibs);
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

    private static void createRelroFile(final boolean is64Bit, String[] nativeLibraryPaths) {
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
            if (nativeLibraryPaths == null
                    || nativeLibraryPaths[0] == null || nativeLibraryPaths[1] == null) {
                throw new IllegalArgumentException(
                        "Native library paths to the WebView RelRo process must not be null!");
            }
            int pid = LocalServices.getService(ActivityManagerInternal.class).startIsolatedProcess(
                    RelroFileCreator.class.getName(), nativeLibraryPaths, "WebViewLoader-" + abi, abi,
                    Process.SHARED_RELRO_UID, crashHandler);
            if (pid <= 0) throw new Exception("Failed to start the relro file creator process");
        } catch (Throwable t) {
            // Log and discard errors as we must not crash the system server.
            Log.e(LOGTAG, "error starting relro file creator for abi " + abi, t);
            crashHandler.run();
        }
    }

    private static class RelroFileCreator {
        // Called in an unprivileged child process to create the relro file.
        public static void main(String[] args) {
            boolean result = false;
            boolean is64Bit = VMRuntime.getRuntime().is64Bit();
            try{
                if (args.length != 2 || args[0] == null || args[1] == null) {
                    Log.e(LOGTAG, "Invalid RelroFileCreator args: " + Arrays.toString(args));
                    return;
                }
                Log.v(LOGTAG, "RelroFileCreator (64bit = " + is64Bit + "), " +
                        " 32-bit lib: " + args[0] + ", 64-bit lib: " + args[1]);
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
                    getUpdateService().notifyRelroCreationCompleted(is64Bit, result);
                } catch (RemoteException e) {
                    Log.e(LOGTAG, "error notifying update service", e);
                }

                if (!result) Log.e(LOGTAG, "failed to create relro file");

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

    private static native boolean nativeReserveAddressSpace(long addressSpaceToReserve);
    private static native boolean nativeCreateRelroFile(String lib32, String lib64,
                                                        String relro32, String relro64);
    private static native boolean nativeLoadWithRelroFile(String lib32, String lib64,
                                                          String relro32, String relro64);
}
