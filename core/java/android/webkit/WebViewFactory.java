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
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.Trace;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.ArraySet;
import android.util.Log;

import com.android.server.LocalServices;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

    // Error codes for loadWebViewNativeLibraryFromPackage
    public static final int LIBLOAD_SUCCESS = 0;
    public static final int LIBLOAD_WRONG_PACKAGE_NAME = 1;
    public static final int LIBLOAD_ADDRESS_SPACE_NOT_RESERVED = 2;

    // error codes for waiting for WebView preparation
    public static final int LIBLOAD_FAILED_WAITING_FOR_RELRO = 3;
    public static final int LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES = 4;

    // native relro loading error codes
    public static final int LIBLOAD_FAILED_TO_OPEN_RELRO_FILE = 5;
    public static final int LIBLOAD_FAILED_TO_LOAD_LIBRARY = 6;
    public static final int LIBLOAD_FAILED_JNI_CALL = 7;

    // more error codes for waiting for WebView preparation
    public static final int LIBLOAD_FAILED_WAITING_FOR_WEBVIEW_REASON_UNKNOWN = 8;

    // error for namespace lookup
    public static final int LIBLOAD_FAILED_TO_FIND_NAMESPACE = 10;

    private static String getWebViewPreparationErrorReason(int error) {
        switch (error) {
            case LIBLOAD_FAILED_WAITING_FOR_RELRO:
                return "Time out waiting for Relro files being created";
            case LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES:
                return "No WebView installed";
            case LIBLOAD_FAILED_WAITING_FOR_WEBVIEW_REASON_UNKNOWN:
                return "Crashed for unknown reason";
        }
        return "Unknown";
    }

    /**
     * @hide
     */
    public static class MissingWebViewPackageException extends AndroidRuntimeException {
        public MissingWebViewPackageException(String message) { super(message); }
        public MissingWebViewPackageException(Exception e) { super(e); }
    }

    /**
     * @hide
     */
    public static String getWebViewLibrary(ApplicationInfo ai) {
        if (ai.metaData != null)
            return ai.metaData.getString("com.android.webview.WebViewLibrary");
        return null;
    }

    public static PackageInfo getLoadedPackageInfo() {
        return sPackageInfo;
    }

    /**
     * Load the native library for the given package name iff that package
     * name is the same as the one providing the webview.
     */
    public static int loadWebViewNativeLibraryFromPackage(String packageName,
                                                          ClassLoader clazzLoader) {
        WebViewProviderResponse response = null;
        try {
            response = getUpdateService().waitForAndGetProvider();
        } catch (RemoteException e) {
            Log.e(LOGTAG, "error waiting for relro creation", e);
            return LIBLOAD_FAILED_WAITING_FOR_WEBVIEW_REASON_UNKNOWN;
        }


        if (response.status != LIBLOAD_SUCCESS
                && response.status != LIBLOAD_FAILED_WAITING_FOR_RELRO) {
            return response.status;
        }
        if (!response.packageInfo.packageName.equals(packageName)) {
            return LIBLOAD_WRONG_PACKAGE_NAME;
        }

        PackageManager packageManager = AppGlobals.getInitialApplication().getPackageManager();
        PackageInfo packageInfo;
        try {
            packageInfo = packageManager.getPackageInfo(packageName,
                    PackageManager.GET_META_DATA | PackageManager.MATCH_DEBUG_TRIAGED_MISSING);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOGTAG, "Couldn't find package " + packageName);
            return LIBLOAD_WRONG_PACKAGE_NAME;
        }
        sPackageInfo = packageInfo;

        int loadNativeRet = loadNativeLibrary(clazzLoader);
        // If we failed waiting for relro we want to return that fact even if we successfully load
        // the relro file.
        if (loadNativeRet == LIBLOAD_SUCCESS) return response.status;
        return loadNativeRet;
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

            StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.getProvider()");
            try {
                Class<WebViewFactoryProvider> providerClass = getProviderClass();

                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "providerClass.newInstance()");
                try {
                    sProviderInstance = providerClass.getConstructor(WebViewDelegate.class)
                            .newInstance(new WebViewDelegate());
                    if (DEBUG) Log.v(LOGTAG, "Loaded provider: " + sProviderInstance);
                    return sProviderInstance;
                } catch (Exception e) {
                    Log.e(LOGTAG, "error instantiating provider", e);
                    throw new AndroidRuntimeException(e);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
                StrictMode.setThreadPolicy(oldPolicy);
            }
        }
    }

    /**
     * Returns true if the signatures match, false otherwise
     */
    private static boolean signaturesEquals(Signature[] s1, Signature[] s2) {
        if (s1 == null) {
            return s2 == null;
        }
        if (s2 == null) return false;

        ArraySet<Signature> set1 = new ArraySet<>();
        for(Signature signature : s1) {
            set1.add(signature);
        }
        ArraySet<Signature> set2 = new ArraySet<>();
        for(Signature signature : s2) {
            set2.add(signature);
        }
        return set1.equals(set2);
    }

    // Throws MissingWebViewPackageException on failure
    private static void verifyPackageInfo(PackageInfo chosen, PackageInfo toUse) {
        if (!chosen.packageName.equals(toUse.packageName)) {
            throw new MissingWebViewPackageException("Failed to verify WebView provider, "
                    + "packageName mismatch, expected: "
                    + chosen.packageName + " actual: " + toUse.packageName);
        }
        if (chosen.versionCode > toUse.versionCode) {
            throw new MissingWebViewPackageException("Failed to verify WebView provider, "
                    + "version code is lower than expected: " + chosen.versionCode
                    + " actual: " + toUse.versionCode);
        }
        if (getWebViewLibrary(toUse.applicationInfo) == null) {
            throw new MissingWebViewPackageException("Tried to load an invalid WebView provider: "
                    + toUse.packageName);
        }
        if (!signaturesEquals(chosen.signatures, toUse.signatures)) {
            throw new MissingWebViewPackageException("Failed to verify WebView provider, "
                    + "signature mismatch");
        }
    }

    private static Context getWebViewContextAndSetProvider() {
        Application initialApplication = AppGlobals.getInitialApplication();
        try {
            WebViewProviderResponse response = null;
            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW,
                    "WebViewUpdateService.waitForAndGetProvider()");
            try {
                response = getUpdateService().waitForAndGetProvider();
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
            if (response.status != LIBLOAD_SUCCESS
                    && response.status != LIBLOAD_FAILED_WAITING_FOR_RELRO) {
                throw new MissingWebViewPackageException("Failed to load WebView provider: "
                        + getWebViewPreparationErrorReason(response.status));
            }
            // Register to be killed before fetching package info - so that we will be
            // killed if the package info goes out-of-date.
            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "ActivityManager.addPackageDependency()");
            try {
                ActivityManagerNative.getDefault().addPackageDependency(
                        response.packageInfo.packageName);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
            // Fetch package info and verify it against the chosen package
            PackageInfo newPackageInfo = null;
            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "PackageManager.getPackageInfo()");
            try {
                newPackageInfo = initialApplication.getPackageManager().getPackageInfo(
                    response.packageInfo.packageName,
                    PackageManager.GET_SHARED_LIBRARY_FILES
                    | PackageManager.MATCH_DEBUG_TRIAGED_MISSING
                    // Make sure that we fetch the current provider even if its not
                    // installed for the current user
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES
                    // Fetch signatures for verification
                    | PackageManager.GET_SIGNATURES
                    // Get meta-data for meta data flag verification
                    | PackageManager.GET_META_DATA);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }

            // Validate the newly fetched package info, throws MissingWebViewPackageException on
            // failure
            verifyPackageInfo(response.packageInfo, newPackageInfo);

            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW,
                    "initialApplication.createApplicationContext");
            try {
                // Construct an app context to load the Java code into the current app.
                Context webViewContext = initialApplication.createApplicationContext(
                        newPackageInfo.applicationInfo,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                sPackageInfo = newPackageInfo;
                return webViewContext;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
        } catch (RemoteException | PackageManager.NameNotFoundException e) {
            throw new MissingWebViewPackageException("Failed to load WebView provider: " + e);
        }
    }

    private static Class<WebViewFactoryProvider> getProviderClass() {
        Context webViewContext = null;
        Application initialApplication = AppGlobals.getInitialApplication();

        try {
            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW,
                    "WebViewFactory.getWebViewContextAndSetProvider()");
            try {
                webViewContext = getWebViewContextAndSetProvider();
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
            Log.i(LOGTAG, "Loading " + sPackageInfo.packageName + " version " +
                    sPackageInfo.versionName + " (code " + sPackageInfo.versionCode + ")");

            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.getChromiumProviderClass()");
            try {
                initialApplication.getAssets().addAssetPathAsSharedLibrary(
                        webViewContext.getApplicationInfo().sourceDir);
                ClassLoader clazzLoader = webViewContext.getClassLoader();

                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.loadNativeLibrary()");
                loadNativeLibrary(clazzLoader);
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);

                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "Class.forName()");
                try {
                    return (Class<WebViewFactoryProvider>) Class.forName(CHROMIUM_WEBVIEW_FACTORY,
                            true, clazzLoader);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
                }
            } catch (ClassNotFoundException e) {
                Log.e(LOGTAG, "error loading provider", e);
                throw new AndroidRuntimeException(e);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
        } catch (MissingWebViewPackageException e) {
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

    private static int prepareWebViewInSystemServer(String[] nativeLibraryPaths) {
        if (DEBUG) Log.v(LOGTAG, "creating relro files");
        int numRelros = 0;

        // We must always trigger createRelRo regardless of the value of nativeLibraryPaths. Any
        // unexpected values will be handled there to ensure that we trigger notifying any process
        // waiting on relro creation.
        if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
            if (DEBUG) Log.v(LOGTAG, "Create 32 bit relro");
            createRelroFile(false /* is64Bit */, nativeLibraryPaths);
            numRelros++;
        }

        if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
            if (DEBUG) Log.v(LOGTAG, "Create 64 bit relro");
            createRelroFile(true /* is64Bit */, nativeLibraryPaths);
            numRelros++;
        }
        return numRelros;
    }

    /**
     * @hide
     */
    public static int onWebViewProviderChanged(PackageInfo packageInfo) {
        String[] nativeLibs = null;
        try {
            nativeLibs = WebViewFactory.getWebViewNativeLibraryPaths(packageInfo);
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
        return prepareWebViewInSystemServer(nativeLibs);
    }

    // throws MissingWebViewPackageException
    private static String getLoadFromApkPath(String apkPath,
                                             String[] abiList,
                                             String nativeLibFileName) {
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
            throw new MissingWebViewPackageException(e);
        }
        return "";
    }

    // throws MissingWebViewPackageException
    private static String[] getWebViewNativeLibraryPaths(PackageInfo packageInfo) {
        ApplicationInfo ai = packageInfo.applicationInfo;
        final String NATIVE_LIB_FILE_NAME = getWebViewLibrary(ai);

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
            path32 += "/" + NATIVE_LIB_FILE_NAME;
            File f = new File(path32);
            if (!f.exists()) {
                path32 = getLoadFromApkPath(ai.sourceDir,
                                            Build.SUPPORTED_32_BIT_ABIS,
                                            NATIVE_LIB_FILE_NAME);
            }
        }
        if (!TextUtils.isEmpty(path64)) {
            path64 += "/" + NATIVE_LIB_FILE_NAME;
            File f = new File(path64);
            if (!f.exists()) {
                path64 = getLoadFromApkPath(ai.sourceDir,
                                            Build.SUPPORTED_64_BIT_ABIS,
                                            NATIVE_LIB_FILE_NAME);
            }
        }

        if (DEBUG) Log.v(LOGTAG, "Native 32-bit lib: " + path32 + ", 64-bit lib: " + path64);
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
                    getUpdateService().notifyRelroCreationCompleted();
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
                    getUpdateService().notifyRelroCreationCompleted();
                } catch (RemoteException e) {
                    Log.e(LOGTAG, "error notifying update service", e);
                }

                if (!result) Log.e(LOGTAG, "failed to create relro file");

                // Must explicitly exit or else this process will just sit around after we return.
                System.exit(0);
            }
        }
    }

    // Assumes that we have waited for relro creation and set sPackageInfo
    private static int loadNativeLibrary(ClassLoader clazzLoader) {
        if (!sAddressSpaceReserved) {
            Log.e(LOGTAG, "can't load with relro file; address space not reserved");
            return LIBLOAD_ADDRESS_SPACE_NOT_RESERVED;
        }

        String[] args = getWebViewNativeLibraryPaths(sPackageInfo);
        int result = nativeLoadWithRelroFile(args[0] /* path32 */,
                                             args[1] /* path64 */,
                                             CHROMIUM_WEBVIEW_NATIVE_RELRO_32,
                                             CHROMIUM_WEBVIEW_NATIVE_RELRO_64,
                                             clazzLoader);
        if (result != LIBLOAD_SUCCESS) {
            Log.w(LOGTAG, "failed to load with relro file, proceeding without");
        } else if (DEBUG) {
            Log.v(LOGTAG, "loaded with relro file");
        }
        return result;
    }

    private static String WEBVIEW_UPDATE_SERVICE_NAME = "webviewupdate";

    /** @hide */
    public static IWebViewUpdateService getUpdateService() {
        return IWebViewUpdateService.Stub.asInterface(
                ServiceManager.getService(WEBVIEW_UPDATE_SERVICE_NAME));
    }

    private static native boolean nativeReserveAddressSpace(long addressSpaceToReserve);
    private static native boolean nativeCreateRelroFile(String lib32, String lib64,
                                                        String relro32, String relro64);
    private static native int nativeLoadWithRelroFile(String lib32, String lib64,
                                                      String relro32, String relro64,
                                                      ClassLoader clazzLoader);
}
