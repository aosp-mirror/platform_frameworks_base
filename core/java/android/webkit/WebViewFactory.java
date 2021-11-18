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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.UptimeMillisLong;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.Application;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Trace;
import android.util.AndroidRuntimeException;
import android.util.ArraySet;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Top level factory, used creating all the main WebView implementation classes.
 *
 * @hide
 */
@SystemApi
public final class WebViewFactory {

    // visible for WebViewZygoteInit to look up the class by reflection and call preloadInZygote.
    /** @hide */
    private static final String CHROMIUM_WEBVIEW_FACTORY =
            "com.android.webview.chromium.WebViewChromiumFactoryProviderForS";

    private static final String CHROMIUM_WEBVIEW_FACTORY_METHOD = "create";

    private static final String LOGTAG = "WebViewFactory";

    private static final boolean DEBUG = false;

    // Cache the factory both for efficiency, and ensure any one process gets all webviews from the
    // same provider.
    @UnsupportedAppUsage
    private static WebViewFactoryProvider sProviderInstance;
    private static final Object sProviderLock = new Object();
    @UnsupportedAppUsage
    private static PackageInfo sPackageInfo;
    private static Boolean sWebViewSupported;
    private static boolean sWebViewDisabled;
    private static String sDataDirectorySuffix; // stored here so it can be set without loading WV

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

    /**
     * Stores the timestamps at which various WebView startup events occurred in this process.
     */
    public static class StartupTimestamps {
        long mWebViewLoadStart;
        long mCreateContextStart;
        long mCreateContextEnd;
        long mAddAssetsStart;
        long mAddAssetsEnd;
        long mGetClassLoaderStart;
        long mGetClassLoaderEnd;
        long mNativeLoadStart;
        long mNativeLoadEnd;
        long mProviderClassForNameStart;
        long mProviderClassForNameEnd;

        StartupTimestamps() {}

        /** When the overall WebView provider load began. */
        @UptimeMillisLong
        public long getWebViewLoadStart() {
            return mWebViewLoadStart;
        }

        /** Before creating the WebView APK Context. */
        @UptimeMillisLong
        public long getCreateContextStart() {
            return mCreateContextStart;
        }

        /** After creating the WebView APK Context. */
        @UptimeMillisLong
        public long getCreateContextEnd() {
            return mCreateContextEnd;
        }

        /** Before adding WebView assets to AssetManager. */
        @UptimeMillisLong
        public long getAddAssetsStart() {
            return mAddAssetsStart;
        }

        /** After adding WebView assets to AssetManager. */
        @UptimeMillisLong
        public long getAddAssetsEnd() {
            return mAddAssetsEnd;
        }

        /** Before creating the WebView ClassLoader. */
        @UptimeMillisLong
        public long getGetClassLoaderStart() {
            return mGetClassLoaderStart;
        }

        /** After creating the WebView ClassLoader. */
        @UptimeMillisLong
        public long getGetClassLoaderEnd() {
            return mGetClassLoaderEnd;
        }

        /** Before preloading the WebView native library. */
        @UptimeMillisLong
        public long getNativeLoadStart() {
            return mNativeLoadStart;
        }

        /** After preloading the WebView native library. */
        @UptimeMillisLong
        public long getNativeLoadEnd() {
            return mNativeLoadEnd;
        }

        /** Before looking up the WebView provider class. */
        @UptimeMillisLong
        public long getProviderClassForNameStart() {
            return mProviderClassForNameStart;
        }

        /** After looking up the WebView provider class. */
        @UptimeMillisLong
        public long getProviderClassForNameEnd() {
            return mProviderClassForNameEnd;
        }
    }

    static final StartupTimestamps sTimestamps = new StartupTimestamps();

    @NonNull
    static StartupTimestamps getStartupTimestamps() {
        return sTimestamps;
    }

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

    static class MissingWebViewPackageException extends Exception {
        public MissingWebViewPackageException(String message) { super(message); }
        public MissingWebViewPackageException(Exception e) { super(e); }
    }

    private static boolean isWebViewSupported() {
        // No lock; this is a benign race as Boolean's state is final and the PackageManager call
        // will always return the same value.
        if (sWebViewSupported == null) {
            sWebViewSupported = AppGlobals.getInitialApplication().getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_WEBVIEW);
        }
        return sWebViewSupported;
    }

    /**
     * @hide
     */
    static void disableWebView() {
        synchronized (sProviderLock) {
            if (sProviderInstance != null) {
                throw new IllegalStateException(
                        "Can't disable WebView: WebView already initialized");
            }
            sWebViewDisabled = true;
        }
    }

    /**
     * @hide
     */
    static void setDataDirectorySuffix(String suffix) {
        synchronized (sProviderLock) {
            if (sProviderInstance != null) {
                throw new IllegalStateException(
                        "Can't set data directory suffix: WebView already initialized");
            }
            if (suffix.indexOf(File.separatorChar) >= 0) {
                throw new IllegalArgumentException("Suffix " + suffix
                                                   + " contains a path separator");
            }
            sDataDirectorySuffix = suffix;
        }
    }

    /**
     * @hide
     */
    static String getDataDirectorySuffix() {
        synchronized (sProviderLock) {
            return sDataDirectorySuffix;
        }
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
        synchronized (sProviderLock) {
            return sPackageInfo;
        }
    }

    /**
     * @hide
     */
    public static Class<WebViewFactoryProvider> getWebViewProviderClass(ClassLoader clazzLoader)
            throws ClassNotFoundException {
        return (Class<WebViewFactoryProvider>) Class.forName(CHROMIUM_WEBVIEW_FACTORY,
                true, clazzLoader);
    }

    /**
     * Load the native library for the given package name if that package
     * name is the same as the one providing the webview.
     */
    public static int loadWebViewNativeLibraryFromPackage(String packageName,
                                                          ClassLoader clazzLoader) {
        if (!isWebViewSupported()) {
            return LIBLOAD_WRONG_PACKAGE_NAME;
        }

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
        String libraryFileName;
        try {
            PackageInfo packageInfo = packageManager.getPackageInfo(packageName,
                    PackageManager.GET_META_DATA | PackageManager.MATCH_DEBUG_TRIAGED_MISSING);
            libraryFileName = getWebViewLibrary(packageInfo.applicationInfo);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOGTAG, "Couldn't find package " + packageName);
            return LIBLOAD_WRONG_PACKAGE_NAME;
        }

        int loadNativeRet = WebViewLibraryLoader.loadNativeLibrary(clazzLoader, libraryFileName);
        // If we failed waiting for relro we want to return that fact even if we successfully
        // load the relro file.
        if (loadNativeRet == LIBLOAD_SUCCESS) return response.status;
        return loadNativeRet;
    }

    @UnsupportedAppUsage
    static WebViewFactoryProvider getProvider() {
        synchronized (sProviderLock) {
            // For now the main purpose of this function (and the factory abstraction) is to keep
            // us honest and minimize usage of WebView internals when binding the proxy.
            if (sProviderInstance != null) return sProviderInstance;

            sTimestamps.mWebViewLoadStart = SystemClock.uptimeMillis();
            final int uid = android.os.Process.myUid();
            if (uid == android.os.Process.ROOT_UID || uid == android.os.Process.SYSTEM_UID
                    || uid == android.os.Process.PHONE_UID || uid == android.os.Process.NFC_UID
                    || uid == android.os.Process.BLUETOOTH_UID) {
                throw new UnsupportedOperationException(
                        "For security reasons, WebView is not allowed in privileged processes");
            }

            if (!isWebViewSupported()) {
                // Device doesn't support WebView; don't try to load it, just throw.
                throw new UnsupportedOperationException();
            }

            if (sWebViewDisabled) {
                throw new IllegalStateException(
                        "WebView.disableWebView() was called: WebView is disabled");
            }

            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.getProvider()");
            try {
                Class<WebViewFactoryProvider> providerClass = getProviderClass();
                Method staticFactory = providerClass.getMethod(
                        CHROMIUM_WEBVIEW_FACTORY_METHOD, WebViewDelegate.class);

                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactoryProvider invocation");
                try {
                    sProviderInstance = (WebViewFactoryProvider)
                            staticFactory.invoke(null, new WebViewDelegate());
                    if (DEBUG) Log.v(LOGTAG, "Loaded provider: " + sProviderInstance);
                    return sProviderInstance;
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
                }
            } catch (Exception e) {
                Log.e(LOGTAG, "error instantiating provider", e);
                throw new AndroidRuntimeException(e);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
        }
    }

    /**
     * Returns {@code true} if the signatures match, {@code false} otherwise
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
    private static void verifyPackageInfo(PackageInfo chosen, PackageInfo toUse)
            throws MissingWebViewPackageException {
        if (!chosen.packageName.equals(toUse.packageName)) {
            throw new MissingWebViewPackageException("Failed to verify WebView provider, "
                    + "packageName mismatch, expected: "
                    + chosen.packageName + " actual: " + toUse.packageName);
        }
        if (chosen.getLongVersionCode() > toUse.getLongVersionCode()) {
            throw new MissingWebViewPackageException("Failed to verify WebView provider, "
                    + "version code is lower than expected: " + chosen.getLongVersionCode()
                    + " actual: " + toUse.getLongVersionCode());
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

    @UnsupportedAppUsage
    private static Context getWebViewContextAndSetProvider() throws MissingWebViewPackageException {
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
                ActivityManager.getService().addPackageDependency(
                        response.packageInfo.packageName);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
            // Fetch package info and verify it against the chosen package
            PackageInfo newPackageInfo = null;
            PackageManager pm = initialApplication.getPackageManager();
            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "PackageManager.getPackageInfo()");
            try {
                newPackageInfo = pm.getPackageInfo(
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

            ApplicationInfo ai = newPackageInfo.applicationInfo;

            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW,
                    "initialApplication.createApplicationContext");
            sTimestamps.mCreateContextStart = SystemClock.uptimeMillis();
            try {
                // Construct an app context to load the Java code into the current app.
                Context webViewContext = initialApplication.createApplicationContext(
                        ai,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                sPackageInfo = newPackageInfo;
                return webViewContext;
            } finally {
                sTimestamps.mCreateContextEnd = SystemClock.uptimeMillis();
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
        } catch (RemoteException | PackageManager.NameNotFoundException e) {
            throw new MissingWebViewPackageException("Failed to load WebView provider: " + e);
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
                    sPackageInfo.versionName + " (code " + sPackageInfo.getLongVersionCode() + ")");

            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.getChromiumProviderClass()");
            try {
                sTimestamps.mAddAssetsStart = SystemClock.uptimeMillis();
                for (String newAssetPath : webViewContext.getApplicationInfo().getAllApkPaths()) {
                    initialApplication.getAssets().addAssetPathAsSharedLibrary(newAssetPath);
                }
                sTimestamps.mAddAssetsEnd = sTimestamps.mGetClassLoaderStart =
                        SystemClock.uptimeMillis();
                ClassLoader clazzLoader = webViewContext.getClassLoader();
                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.loadNativeLibrary()");
                sTimestamps.mGetClassLoaderEnd = sTimestamps.mNativeLoadStart =
                        SystemClock.uptimeMillis();
                WebViewLibraryLoader.loadNativeLibrary(clazzLoader,
                        getWebViewLibrary(sPackageInfo.applicationInfo));
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "Class.forName()");
                sTimestamps.mNativeLoadEnd = sTimestamps.mProviderClassForNameStart =
                        SystemClock.uptimeMillis();
                try {
                    return getWebViewProviderClass(clazzLoader);
                } finally {
                    sTimestamps.mProviderClassForNameEnd = SystemClock.uptimeMillis();
                    Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
                }
            } catch (ClassNotFoundException e) {
                Log.e(LOGTAG, "error loading provider", e);
                throw new AndroidRuntimeException(e);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
        } catch (MissingWebViewPackageException e) {
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
            WebViewLibraryLoader.reserveAddressSpaceInZygote();
        } catch (Throwable t) {
            // Log and discard errors at this stage as we must not crash the zygote.
            Log.e(LOGTAG, "error preparing native loader", t);
        }
    }

    /**
     * @hide
     */
    public static int onWebViewProviderChanged(PackageInfo packageInfo) {
        int startedRelroProcesses = 0;
        try {
            startedRelroProcesses = WebViewLibraryLoader.prepareNativeLibraries(packageInfo);
        } catch (Throwable t) {
            // Log and discard errors at this stage as we must not crash the system server.
            Log.e(LOGTAG, "error preparing webview native library", t);
        }

        WebViewZygote.onWebViewProviderChanged(packageInfo);

        return startedRelroProcesses;
    }

    private static String WEBVIEW_UPDATE_SERVICE_NAME = "webviewupdate";

    /** @hide */
    @UnsupportedAppUsage
    public static IWebViewUpdateService getUpdateService() {
        if (isWebViewSupported()) {
            return getUpdateServiceUnchecked();
        } else {
            return null;
        }
    }

    /** @hide */
    static IWebViewUpdateService getUpdateServiceUnchecked() {
        return IWebViewUpdateService.Stub.asInterface(
                ServiceManager.getService(WEBVIEW_UPDATE_SERVICE_NAME));
    }
}
