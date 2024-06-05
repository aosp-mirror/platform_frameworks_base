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

import static android.webkit.Flags.updateServiceV2;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.ChildZygoteProcess;
import android.os.Process;
import android.os.ZygoteProcess;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.Zygote;

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
     * Flag for whether multi-process WebView is enabled. If this is {@code false}, the zygote will
     * not be started. Should be removed entirely after we remove the updateServiceV2 flag.
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
            if (updateServiceV2()) {
                return sPackage != null;
            } else {
                return sMultiprocessEnabled && sPackage != null;
            }
        }
    }

    public static void setMultiprocessEnabled(boolean enabled) {
        if (updateServiceV2()) {
            throw new IllegalStateException(
                    "setMultiprocessEnabled shouldn't be called if update_service_v2 flag is set.");
        }
        synchronized (sLock) {
            sMultiprocessEnabled = enabled;

            // When multi-process is disabled, kill the zygote. When it is enabled,
            // the zygote will be started when it is first needed in getProcess().
            if (!enabled) {
                stopZygoteLocked();
            }
        }
    }

    static void onWebViewProviderChanged(PackageInfo packageInfo) {
        synchronized (sLock) {
            sPackage = packageInfo;

            // If multi-process is not enabled, then do not start the zygote service.
            // Only check sMultiprocessEnabled if updateServiceV2 is not enabled.
            if (!updateServiceV2() && !sMultiprocessEnabled) {
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
            String abi = sPackage.applicationInfo.primaryCpuAbi;
            int runtimeFlags = Zygote.getMemorySafetyRuntimeFlagsForSecondaryZygote(
                    sPackage.applicationInfo, null);
            sZygote = Process.ZYGOTE_PROCESS.startChildZygote(
                    "com.android.internal.os.WebViewZygoteInit",
                    "webview_zygote",
                    Process.WEBVIEW_ZYGOTE_UID,
                    Process.WEBVIEW_ZYGOTE_UID,
                    null,  // gids
                    runtimeFlags,
                    "webview_zygote",  // seInfo
                    abi,  // abi
                    TextUtils.join(",", Build.SUPPORTED_ABIS),
                    null, // instructionSet
                    Process.FIRST_ISOLATED_UID,
                    Integer.MAX_VALUE); // TODO(b/123615476) deal with user-id ranges properly
            ZygoteProcess.waitForConnectionToZygote(sZygote.getPrimarySocketAddress());
            sZygote.preloadApp(sPackage.applicationInfo, abi);
        } catch (Exception e) {
            Log.e(LOGTAG, "Error connecting to webview zygote", e);
            stopZygoteLocked();
        }
    }
}
