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
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemService;
import android.os.ZygoteProcess;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** @hide */
public class WebViewZygote {
    private static final String LOGTAG = "WebViewZygote";

    private static final String WEBVIEW_ZYGOTE_SERVICE_32 = "webview_zygote32";
    private static final String WEBVIEW_ZYGOTE_SERVICE_64 = "webview_zygote64";

    private static ZygoteProcess sZygote;

    private static PackageInfo sPackage;

    private static boolean sMultiprocessEnabled = false;

    public static ZygoteProcess getProcess() {
        connectToZygoteIfNeeded();
        return sZygote;
    }

    public static String getPackageName() {
        return sPackage.packageName;
    }

    public static void setMultiprocessEnabled(boolean enabled) {
        sMultiprocessEnabled = enabled;

        // When toggling between multi-process being on/off, start or stop the
        // service. If it is enabled and the zygote is not yet started, bring up the service.
        // Otherwise, bring down the service. The name may be null if the package
        // information has not yet been resolved.
        final String serviceName = getServiceName();
        if (serviceName == null) return;

        if (enabled && sZygote == null) {
            SystemService.start(serviceName);
        } else {
            SystemService.stop(serviceName);
            sZygote = null;
        }
    }

    public static void onWebViewProviderChanged(PackageInfo packageInfo) {
        sPackage = packageInfo;

        // If multi-process is not enabled, then do not start the zygote service.
        if (!sMultiprocessEnabled) {
            return;
        }

        final String serviceName = getServiceName();

        if (SystemService.isStopped(serviceName)) {
            SystemService.start(serviceName);
        } else if (sZygote != null) {
            SystemService.restart(serviceName);
        }

        try {
            SystemService.waitForState(serviceName, SystemService.State.RUNNING, 5000);
        } catch (TimeoutException e) {
            Log.e(LOGTAG, "Timed out waiting for " + serviceName);
            return;
        }

        connectToZygoteIfNeeded();
    }

    private static String getServiceName() {
        if (sPackage == null)
            return null;

        if (Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains(
                    sPackage.applicationInfo.primaryCpuAbi)) {
            return WEBVIEW_ZYGOTE_SERVICE_64;
        }

        return WEBVIEW_ZYGOTE_SERVICE_32;
    }

    private static void connectToZygoteIfNeeded() {
        if (sZygote != null)
            return;

        if (sPackage == null) {
            Log.e(LOGTAG, "Cannot connect to zygote, no package specified");
            return;
        }

        final String serviceName = getServiceName();
        if (!SystemService.isRunning(serviceName)) {
            Log.e(LOGTAG, serviceName + " is not running");
            return;
        }

        try {
            sZygote = new ZygoteProcess("webview_zygote", null);

            // All the work below is usually done by LoadedApk, but the zygote can't talk to
            // PackageManager or construct a LoadedApk since it's single-threaded pre-fork, so
            // doesn't have an ActivityThread and can't use Binder.
            // Instead, figure out the paths here, in the system server where we have access to
            // the package manager. Reuse the logic from LoadedApk to determine the correct
            // paths and pass them to the zygote as strings.
            final List<String> zipPaths = new ArrayList<>(10);
            final List<String> libPaths = new ArrayList<>(10);
            LoadedApk.makePaths(null, sPackage.applicationInfo, zipPaths, libPaths);
            final String librarySearchPath = TextUtils.join(File.pathSeparator, libPaths);
            final String zip = (zipPaths.size() == 1) ? zipPaths.get(0) :
                    TextUtils.join(File.pathSeparator, zipPaths);

            Log.d(LOGTAG, "Preloading package " + zip + " " + librarySearchPath);
            sZygote.preloadPackageForAbi(zip, librarySearchPath, Build.SUPPORTED_ABIS[0]);
        } catch (Exception e) {
            Log.e(LOGTAG, "Error connecting to " + serviceName, e);
            sZygote = null;
        }
    }
}
