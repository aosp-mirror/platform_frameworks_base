/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.ChildZygoteProcess;
import android.os.Process;
import android.os.ZygoteProcess;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** @hide */
public class WebViewZygote {
    private static final String LOGTAG = "WebViewZygote";

    /**
     * Lock object that protects all other static members.
     */
    private static final Object sLock = new Object();

    /**
     * Instance that maintains the socket connection to the zygote. This is {@code null} if the
     * zygote is not running or is not connected.
     */
    @GuardedBy("sLock")
    private static ChildZygoteProcess sZygote;

    /**
     * Information about the selected WebView package. This is set from #onWebViewProviderChanged().
     */
    @GuardedBy("sLock")
    private static PackageInfo sPackage;

    /**
     * Original ApplicationInfo for the selected WebView package before stub fixup. This is set from
     * #onWebViewProviderChanged().
     */
    @GuardedBy("sLock")
    private static ApplicationInfo sPackageOriginalAppInfo;

    /**
     * Flag for whether multi-process WebView is enabled. If this is {@code false}, the zygote
     * will not be started.
     */
    @GuardedBy("sLock")
    private static boolean sMultiprocessEnabled = false;

    public static ZygoteProcess getProcess() {
        synchronized (sLock) {
            if (sZygote != null) return sZygote;

            connectToZygoteIfNeededLocked();
            return sZygote;
        }
    }

    public static String getPackageName() {
        synchronized (sLock) {
            return sPackage.packageName;
        }
    }

    public static boolean isMultiprocessEnabled() {
        synchronized (sLock) {
            return sMultiprocessEnabled && sPackage != null;
        }
    }

    public static void setMultiprocessEnabled(boolean enabled) {
        synchronized (sLock) {
            sMultiprocessEnabled = enabled;

            // When toggling between multi-process being on/off, start or stop the
            // zygote. If it is enabled and the zygote is not yet started, launch it.
            // Otherwise, kill it. The name may be null if the package information has
            // not yet been resolved.
            if (enabled) {
                // Run on a background thread as this waits for the zygote to start and we don't
                // want to block the caller on this. It's okay if this is delayed as anyone trying
                // to use the zygote will call it first anyway.
                AsyncTask.THREAD_POOL_EXECUTOR.execute(WebViewZygote::getProcess);
            } else {
                // No need to run this in the background, it's very brief.
                stopZygoteLocked();
            }
        }
    }

    public static void onWebViewProviderChanged(PackageInfo packageInfo,
                                                ApplicationInfo originalAppInfo) {
        synchronized (sLock) {
            sPackage = packageInfo;
            sPackageOriginalAppInfo = originalAppInfo;

            // If multi-process is not enabled, then do not start the zygote service.
            if (!sMultiprocessEnabled) {
                return;
            }

            stopZygoteLocked();
        }
    }

    @GuardedBy("sLock")
    private static void stopZygoteLocked() {
        if (sZygote != null) {
            // Close the connection and kill the zygote process. This will not cause
            // child processes to be killed by itself. But if this is called in response to
            // setMultiprocessEnabled() or onWebViewProviderChanged(), the WebViewUpdater
            // will kill all processes that depend on the WebView package.
            sZygote.close();
            Process.killProcess(sZygote.getPid());
            sZygote = null;
        }
    }

    @GuardedBy("sLock")
    private static void connectToZygoteIfNeededLocked() {
        if (sZygote != null) {
            return;
        }

        if (sPackage == null) {
            Log.e(LOGTAG, "Cannot connect to zygote, no package specified");
            return;
        }

        try {
            sZygote = Process.zygoteProcess.startChildZygote(
                    "com.android.internal.os.WebViewZygoteInit",
                    "webview_zygote",
                    Process.WEBVIEW_ZYGOTE_UID,
                    Process.WEBVIEW_ZYGOTE_UID,
                    null,  // gids
                    0,  // runtimeFlags
                    "webview_zygote",  // seInfo
                    sPackage.applicationInfo.primaryCpuAbi,  // abi
                    null);  // instructionSet

            // All the work below is usually done by LoadedApk, but the zygote can't talk to
            // PackageManager or construct a LoadedApk since it's single-threaded pre-fork, so
            // doesn't have an ActivityThread and can't use Binder.
            // Instead, figure out the paths here, in the system server where we have access to
            // the package manager. Reuse the logic from LoadedApk to determine the correct
            // paths and pass them to the zygote as strings.
            final List<String> zipPaths = new ArrayList<>(10);
            final List<String> libPaths = new ArrayList<>(10);
            LoadedApk.makePaths(null, false, sPackage.applicationInfo, zipPaths, libPaths);
            final String librarySearchPath = TextUtils.join(File.pathSeparator, libPaths);
            final String zip = (zipPaths.size() == 1) ? zipPaths.get(0) :
                    TextUtils.join(File.pathSeparator, zipPaths);

            String libFileName = WebViewFactory.getWebViewLibrary(sPackage.applicationInfo);

            // In the case where the ApplicationInfo has been modified by the stub WebView,
            // we need to use the original ApplicationInfo to determine what the original classpath
            // would have been to use as a cache key.
            LoadedApk.makePaths(null, false, sPackageOriginalAppInfo, zipPaths, null);
            final String cacheKey = (zipPaths.size() == 1) ? zipPaths.get(0) :
                    TextUtils.join(File.pathSeparator, zipPaths);

            ZygoteProcess.waitForConnectionToZygote(sZygote.getPrimarySocketAddress());

            Log.d(LOGTAG, "Preloading package " + zip + " " + librarySearchPath);
            sZygote.preloadPackageForAbi(zip, librarySearchPath, libFileName, cacheKey,
                                         Build.SUPPORTED_ABIS[0]);
        } catch (Exception e) {
            Log.e(LOGTAG, "Error connecting to webview zygote", e);
            stopZygoteLocked();
        }
    }
}
