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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.UserHandle;
import android.util.Slog;
import android.webkit.UserPackage;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;

import java.io.PrintWriter;
import java.lang.Integer;
import java.util.List;

/**
 * Implementation of the WebViewUpdateService.
 * This class doesn't depend on the android system like the actual Service does and can be used
 * directly by tests (as long as they implement a SystemInterface).
 *
 * This class implements two main features - handling WebView fallback packages and keeping track
 * of, and preparing, the current WebView implementation. The fallback mechanism is meant to be
 * uncoupled from the rest of the WebView preparation and does not store any state. The code for
 * choosing and preparing a WebView implementation needs to keep track of a couple of different
 * things such as what package is used as WebView implementation.
 *
 * The public methods in this class are accessed from WebViewUpdateService either on the UI thread
 * or on one of multiple Binder threads. This means that the code in this class needs to be
 * thread-safe. The fallback mechanism shares (almost) no information between threads which makes
 * it easier to argue about thread-safety (in theory, if timed badly, the fallback mechanism can
 * incorrectly enable/disable a fallback package but that fault will be corrected when we later
 * receive an intent for that enabling/disabling). On the other hand, the WebView preparation code
 * shares state between threads meaning that code that chooses a new WebView implementation or
 * checks which implementation is being used needs to hold a lock.
 *
 * The WebViewUpdateService can be accessed in a couple of different ways.
 * 1. It is started from the SystemServer at boot - at that point we just initiate some state such
 * as the WebView preparation class.
 * 2. The SystemServer calls WebViewUpdateService.prepareWebViewInSystemServer. This happens at boot
 * and the WebViewUpdateService should not have been accessed before this call. In this call we
 * enable/disable fallback packages and then choose WebView implementation for the first time.
 * 3. The update service listens for Intents related to package installs and removals. These intents
 * are received and processed on the UI thread. Each intent can result in enabling/disabling
 * fallback packages and changing WebView implementation.
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
        updateFallbackStateOnPackageChange(packageName, changedState);
        mWebViewUpdater.packageStateChanged(packageName, changedState);
    }

    void prepareWebViewInSystemServer() {
        updateFallbackStateOnBoot();
        mWebViewUpdater.prepareWebViewInSystemServer();
        mSystemInterface.notifyZygote(isMultiProcessEnabled());
    }

    private boolean existsValidNonFallbackProvider(WebViewProviderInfo[] providers) {
        for (WebViewProviderInfo provider : providers) {
            if (provider.availableByDefault && !provider.isFallback) {
                // userPackages can contain null objects.
                List<UserPackage> userPackages =
                        mSystemInterface.getPackageInfoForProviderAllUsers(mContext, provider);
                if (WebViewUpdater.isInstalledAndEnabledForAllUsers(userPackages) &&
                        // Checking validity of the package for the system user (rather than all
                        // users) since package validity depends not on the user but on the package
                        // itself.
                        mWebViewUpdater.isValidProvider(provider,
                                userPackages.get(UserHandle.USER_SYSTEM).getPackageInfo())) {
                    return true;
                }
            }
        }
        return false;
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
     * Called when a user was added or removed to ensure fallback logic and WebView preparation are
     * triggered. This has to be done since the WebView package we use depends on the enabled-state
     * of packages for all users (so adding or removing a user might cause us to change package).
     */
    private void handleUserChange() {
        if (mSystemInterface.isFallbackLogicEnabled()) {
            updateFallbackState(mSystemInterface.getWebViewPackages());
        }
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

    void enableFallbackLogic(boolean enable) {
        mSystemInterface.enableFallbackLogic(enable);
    }

    private void updateFallbackStateOnBoot() {
        if (!mSystemInterface.isFallbackLogicEnabled()) return;

        WebViewProviderInfo[] webviewProviders = mSystemInterface.getWebViewPackages();
        updateFallbackState(webviewProviders);
    }

    /**
     * Handle the enabled-state of our fallback package, i.e. if there exists some non-fallback
     * package that is valid (and available by default) then disable the fallback package,
     * otherwise, enable the fallback package.
     */
    private void updateFallbackStateOnPackageChange(String changedPackage, int changedState) {
        if (!mSystemInterface.isFallbackLogicEnabled()) return;

        WebViewProviderInfo[] webviewProviders = mSystemInterface.getWebViewPackages();

        // A package was changed / updated / downgraded, early out if it is not one of the
        // webview packages that are available by default.
        boolean changedPackageAvailableByDefault = false;
        for (WebViewProviderInfo provider : webviewProviders) {
            if (provider.packageName.equals(changedPackage)) {
                if (provider.availableByDefault) {
                    changedPackageAvailableByDefault = true;
                }
                break;
            }
        }
        if (!changedPackageAvailableByDefault) return;
        updateFallbackState(webviewProviders);
    }

    private void updateFallbackState(WebViewProviderInfo[] webviewProviders) {
        // If there exists a valid and enabled non-fallback package - disable the fallback
        // package, otherwise, enable it.
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewProviders);
        if (fallbackProvider == null) return;
        boolean existsValidNonFallbackProvider = existsValidNonFallbackProvider(webviewProviders);

        List<UserPackage> userPackages =
                mSystemInterface.getPackageInfoForProviderAllUsers(mContext, fallbackProvider);
        if (existsValidNonFallbackProvider && !isDisabledForAllUsers(userPackages)) {
            mSystemInterface.uninstallAndDisablePackageForAllUsers(mContext,
                    fallbackProvider.packageName);
        } else if (!existsValidNonFallbackProvider
                && !WebViewUpdater.isInstalledAndEnabledForAllUsers(userPackages)) {
            // Enable the fallback package for all users.
            mSystemInterface.enablePackageForAllUsers(mContext,
                    fallbackProvider.packageName, true);
        }
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

    boolean isFallbackPackage(String packageName) {
        if (packageName == null || !mSystemInterface.isFallbackLogicEnabled()) return false;

        WebViewProviderInfo[] webviewPackages = mSystemInterface.getWebViewPackages();
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewPackages);
        return (fallbackProvider != null
                && packageName.equals(fallbackProvider.packageName));
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

    private static boolean isDisabledForAllUsers(List<UserPackage> userPackages) {
        for (UserPackage userPackage : userPackages) {
            if (userPackage.getPackageInfo() != null && userPackage.isEnabledPackage()) {
                return false;
            }
        }
        return true;
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
