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
package com.android.server.webkit;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.AsyncTask;
import android.os.UserHandle;
import android.util.Slog;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;

import java.io.PrintWriter;

/**
 * Implementation of the WebViewUpdateService.
 * This class doesn't depend on the android system like the actual Service does and can be used
 * directly by tests (as long as they implement a SystemInterface).
 *
 * This class keeps track of and prepares the current WebView implementation, and needs to keep
 * track of a couple of different things such as what package is used as WebView implementation.
 *
 * The public methods in this class are accessed from WebViewUpdateService either on the UI thread
 * or on one of multiple Binder threads. The WebView preparation code shares state between threads
 * meaning that code that chooses a new WebView implementation or checks which implementation is
 * being used needs to hold a lock.
 *
 * The WebViewUpdateService can be accessed in a couple of different ways.
 * 1. It is started from the SystemServer at boot - at that point we just initiate some state such
 * as the WebView preparation class.
 * 2. The SystemServer calls WebViewUpdateService.prepareWebViewInSystemServer. This happens at boot
 * and the WebViewUpdateService should not have been accessed before this call. In this call we
 * migrate away from the old fallback logic if necessary and then choose WebView implementation for
 * the first time.
 * 3. The update service listens for Intents related to package installs and removals. These intents
 * are received and processed on the UI thread. Each intent can result in changing WebView
 * implementation.
 * 4. The update service can be reached through Binder calls which are handled on specific binder
 * threads. These calls can be made from any process. Generally they are used for changing WebView
 * implementation (from Settings), getting information about the current WebView implementation (for
 * loading WebView into an app process), or notifying the service about Relro creation being
 * completed.
 *
 * @hide
 */
public class WebViewUpdateServiceImpl {
    private static final String TAG = WebViewUpdateServiceImpl.class.getSimpleName();

    private SystemInterface mSystemInterface;
    private WebViewUpdater mWebViewUpdater;
    final private Context mContext;

    private final static int MULTIPROCESS_SETTING_ON_VALUE = Integer.MAX_VALUE;
    private final static int MULTIPROCESS_SETTING_OFF_VALUE = Integer.MIN_VALUE;

    public WebViewUpdateServiceImpl(Context context, SystemInterface systemInterface) {
        mContext = context;
        mSystemInterface = systemInterface;
        mWebViewUpdater = new WebViewUpdater(mContext, mSystemInterface);
    }

    void packageStateChanged(String packageName, int changedState, int userId) {
        // We don't early out here in different cases where we could potentially early-out (e.g. if
        // we receive PACKAGE_CHANGED for another user than the system user) since that would
        // complicate this logic further and open up for more edge cases.
        mWebViewUpdater.packageStateChanged(packageName, changedState);
    }

    void prepareWebViewInSystemServer() {
        migrateFallbackStateOnBoot();
        mWebViewUpdater.prepareWebViewInSystemServer();
        if (getCurrentWebViewPackage() == null) {
            // We didn't find a valid WebView implementation. Try explicitly re-enabling the
            // fallback package for all users in case it was disabled, even if we already did the
            // one-time migration before. If this actually changes the state, WebViewUpdater will
            // see the PackageManager broadcast shortly and try again.
            WebViewProviderInfo[] webviewProviders = mSystemInterface.getWebViewPackages();
            WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewProviders);
            if (fallbackProvider != null) {
                Slog.w(TAG, "No valid provider, trying to enable " + fallbackProvider.packageName);
                mSystemInterface.enablePackageForAllUsers(mContext, fallbackProvider.packageName,
                                                          true);
            } else {
                Slog.e(TAG, "No valid provider and no fallback available.");
            }
        }

        boolean multiProcessEnabled = isMultiProcessEnabled();
        mSystemInterface.notifyZygote(multiProcessEnabled);
        if (multiProcessEnabled) {
            AsyncTask.THREAD_POOL_EXECUTOR.execute(this::startZygoteWhenReady);
        }
    }

    void startZygoteWhenReady() {
        // Wait on a background thread for RELRO creation to be done. We ignore the return value
        // because even if RELRO creation failed we still want to start the zygote.
        waitForAndGetProvider();
        mSystemInterface.ensureZygoteStarted();
    }

    void handleNewUser(int userId) {
        // The system user is always started at boot, and by that point we have already run one
        // round of the package-changing logic (through prepareWebViewInSystemServer()), so early
        // out here.
        if (userId == UserHandle.USER_SYSTEM) return;
        handleUserChange();
    }

    void handleUserRemoved(int userId) {
        handleUserChange();
    }

    /**
     * Called when a user was added or removed to ensure WebView preparation is triggered.
     * This has to be done since the WebView package we use depends on the enabled-state
     * of packages for all users (so adding or removing a user might cause us to change package).
     */
    private void handleUserChange() {
        // Potentially trigger package-changing logic.
        mWebViewUpdater.updateCurrentWebViewPackage(null);
    }

    void notifyRelroCreationCompleted() {
        mWebViewUpdater.notifyRelroCreationCompleted();
    }

    WebViewProviderResponse waitForAndGetProvider() {
        return mWebViewUpdater.waitForAndGetProvider();
    }

    String changeProviderAndSetting(String newProvider) {
        return mWebViewUpdater.changeProviderAndSetting(newProvider);
    }

    WebViewProviderInfo[] getValidWebViewPackages() {
        return mWebViewUpdater.getValidWebViewPackages();
    }

    WebViewProviderInfo[] getWebViewPackages() {
        return mSystemInterface.getWebViewPackages();
    }

    PackageInfo getCurrentWebViewPackage() {
        return mWebViewUpdater.getCurrentWebViewPackage();
    }

    /**
     * If the fallback logic is enabled, re-enable any fallback package for all users, then
     * disable the fallback logic.
     *
     * This migrates away from the old fallback mechanism to the new state where packages are never
     * automatically enableenableisabled.
     */
    private void migrateFallbackStateOnBoot() {
        if (!mSystemInterface.isFallbackLogicEnabled()) return;

        WebViewProviderInfo[] webviewProviders = mSystemInterface.getWebViewPackages();
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewProviders);
        if (fallbackProvider != null) {
            Slog.i(TAG, "One-time migration: enabling " + fallbackProvider.packageName);
            mSystemInterface.enablePackageForAllUsers(mContext, fallbackProvider.packageName, true);
        } else {
            Slog.i(TAG, "Skipping one-time migration: no fallback provider");
        }
        mSystemInterface.enableFallbackLogic(false);
    }

    /**
     * Returns the only fallback provider in the set of given packages, or null if there is none.
     */
    private static WebViewProviderInfo getFallbackProvider(WebViewProviderInfo[] webviewPackages) {
        for (WebViewProviderInfo provider : webviewPackages) {
            if (provider.isFallback) {
                return provider;
            }
        }
        return null;
    }

    boolean isMultiProcessEnabled() {
        int settingValue = mSystemInterface.getMultiProcessSetting(mContext);
        if (mSystemInterface.isMultiProcessDefaultEnabled()) {
            // Multiprocess should be enabled unless the user has turned it off manually.
            return settingValue > MULTIPROCESS_SETTING_OFF_VALUE;
        } else {
            // Multiprocess should not be enabled, unless the user has turned it on manually.
            return settingValue >= MULTIPROCESS_SETTING_ON_VALUE;
        }
    }

    void enableMultiProcess(boolean enable) {
        PackageInfo current = getCurrentWebViewPackage();
        mSystemInterface.setMultiProcessSetting(mContext,
                enable ? MULTIPROCESS_SETTING_ON_VALUE : MULTIPROCESS_SETTING_OFF_VALUE);
        mSystemInterface.notifyZygote(enable);
        if (current != null) {
            mSystemInterface.killPackageDependents(current.packageName);
        }
    }

    /**
     * Dump the state of this Service.
     */
    void dumpState(PrintWriter pw) {
        pw.println("Current WebView Update Service state");
        pw.println(String.format("  Fallback logic enabled: %b",
                mSystemInterface.isFallbackLogicEnabled()));
        pw.println(String.format("  Multiprocess enabled: %b", isMultiProcessEnabled()));
        mWebViewUpdater.dumpState(pw);
    }
}
