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

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.os.AsyncTask;
import android.os.Trace;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.Slog;
import android.webkit.UserPackage;
import android.webkit.WebViewFactory;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of the WebViewUpdateService.
 * This class doesn't depend on the android system like the actual Service does and can be used
 * directly by tests (as long as they implement a SystemInterface).
 *
 * This class keeps track of and prepares the current WebView implementation, and needs to keep
 * track of a couple of different things such as what package is used as WebView implementation.
 *
 * The package-visible methods in this class are accessed from WebViewUpdateService either on the UI
 * thread or on one of multiple Binder threads. The WebView preparation code shares state between
 * threads meaning that code that chooses a new WebView implementation or checks which
 * implementation is being used needs to hold a lock.
 *
 * The WebViewUpdateService can be accessed in a couple of different ways.
 * 1. It is started from the SystemServer at boot - at that point we just initiate some state such
 * as the WebView preparation class.
 * 2. The SystemServer calls WebViewUpdateService.prepareWebViewInSystemServer. This happens at boot
 * and the WebViewUpdateService should not have been accessed before this call. In this call we
 * choose WebView implementation for the first time.
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
class WebViewUpdateServiceImpl2 implements WebViewUpdateServiceInterface {
    private static final String TAG = WebViewUpdateServiceImpl2.class.getSimpleName();

    private static class WebViewPackageMissingException extends Exception {
        WebViewPackageMissingException(String message) {
            super(message);
        }
    }

    private static final int WAIT_TIMEOUT_MS = 1000; // KEY_DISPATCHING_TIMEOUT is 5000.
    private static final long NS_PER_MS = 1000000;

    private static final int VALIDITY_OK = 0;
    private static final int VALIDITY_INCORRECT_SDK_VERSION = 1;
    private static final int VALIDITY_INCORRECT_VERSION_CODE = 2;
    private static final int VALIDITY_INCORRECT_SIGNATURE = 3;
    private static final int VALIDITY_NO_LIBRARY_FLAG = 4;

    private final SystemInterface mSystemInterface;
    private final Context mContext;
    private final WebViewProviderInfo mDefaultProvider;

    private long mMinimumVersionCode = -1;

    // Keeps track of the number of running relro creations
    private int mNumRelroCreationsStarted = 0;
    private int mNumRelroCreationsFinished = 0;
    // Implies that we need to rerun relro creation because we are using an out-of-date package
    private boolean mWebViewPackageDirty = false;
    private boolean mAnyWebViewInstalled = false;

    // Keeps track of whether we attempted to repair WebView before.
    private boolean mAttemptedToRepairBefore = false;

    private static final int NUMBER_OF_RELROS_UNKNOWN = Integer.MAX_VALUE;

    // The WebView package currently in use (or the one we are preparing).
    private PackageInfo mCurrentWebViewPackage = null;

    private final Object mLock = new Object();

    WebViewUpdateServiceImpl2(Context context, SystemInterface systemInterface) {
        mContext = context;
        mSystemInterface = systemInterface;
        WebViewProviderInfo[] webviewProviders = getWebViewPackages();

        WebViewProviderInfo defaultProvider = null;
        for (WebViewProviderInfo provider : webviewProviders) {
            if (provider.availableByDefault) {
                defaultProvider = provider;
                break;
            }
        }
        if (defaultProvider == null) {
            // This should be unreachable because the config parser enforces that there is at least
            // one availableByDefault provider.
            throw new AndroidRuntimeException("No available by default WebView Provider.");
        }
        mDefaultProvider = defaultProvider;
    }

    @Override
    public void packageStateChanged(String packageName, int changedState, int userId) {
        // We don't early out here in different cases where we could potentially early-out (e.g. if
        // we receive PACKAGE_CHANGED for another user than the system user) since that would
        // complicate this logic further and open up for more edge cases.
        for (WebViewProviderInfo provider : mSystemInterface.getWebViewPackages()) {
            String webviewPackage = provider.packageName;

            if (webviewPackage.equals(packageName)) {
                boolean updateWebView = false;
                boolean removedOrChangedOldPackage = false;
                String oldProviderName = null;
                PackageInfo newPackage = null;
                boolean repairNeeded = false;
                synchronized (mLock) {
                    try {
                        newPackage = findPreferredWebViewPackage();
                        if (mCurrentWebViewPackage != null) {
                            oldProviderName = mCurrentWebViewPackage.packageName;
                        }
                        // Only trigger update actions if the updated package is the one
                        // that will be used, or the one that was in use before the
                        // update, or if we haven't seen a valid WebView package before.
                        updateWebView =
                            provider.packageName.equals(newPackage.packageName)
                            || provider.packageName.equals(oldProviderName)
                            || mCurrentWebViewPackage == null;
                        // We removed the old package if we received an intent to remove
                        // or replace the old package.
                        removedOrChangedOldPackage =
                            provider.packageName.equals(oldProviderName);
                        if (updateWebView) {
                            onWebViewProviderChanged(newPackage);
                        }
                    } catch (WebViewPackageMissingException e) {
                        mCurrentWebViewPackage = null;
                        Slog.e(TAG, "Could not find valid WebView package to create relro with "
                                + e);
                    }
                    repairNeeded = shouldTriggerRepairLocked();
                }
                if (updateWebView && !removedOrChangedOldPackage
                        && oldProviderName != null) {
                    // If the provider change is the result of adding or replacing a
                    // package that was not the previous provider then we must kill
                    // packages dependent on the old package ourselves. The framework
                    // only kills dependents of packages that are being removed.
                    mSystemInterface.killPackageDependents(oldProviderName);
                }
                if (repairNeeded) {
                    attemptRepair();
                }
                return;
            }
        }
    }

    private boolean shouldTriggerRepairLocked() {
        if (mAttemptedToRepairBefore) {
            return false;
        }
        if (mCurrentWebViewPackage == null) {
            return true;
        }
        if (mCurrentWebViewPackage.packageName.equals(mDefaultProvider.packageName)) {
            List<UserPackage> userPackages =
                    mSystemInterface.getPackageInfoForProviderAllUsers(
                            mContext, mDefaultProvider);
            return !isInstalledAndEnabledForAllUsers(userPackages);
        } else {
            return false;
        }
    }

    private void attemptRepair() {
        // We didn't find a valid WebView implementation. Try explicitly re-installing and
        // re-enabling the default package for all users in case it was disabled. If this actually
        // changes the state, we will see the PackageManager broadcast shortly and try again.
        synchronized (mLock) {
            if (mAttemptedToRepairBefore) {
                return;
            }
            mAttemptedToRepairBefore = true;
        }
        Slog.w(
                TAG,
                "No provider available for all users, trying to install and enable "
                        + mDefaultProvider.packageName);
        mSystemInterface.installExistingPackageForAllUsers(
                mContext, mDefaultProvider.packageName);
        mSystemInterface.enablePackageForAllUsers(
                mContext, mDefaultProvider.packageName, true);
    }

    @Override
    public void prepareWebViewInSystemServer() {
        try {
            boolean repairNeeded = true;
            synchronized (mLock) {
                mCurrentWebViewPackage = findPreferredWebViewPackage();
                repairNeeded = shouldTriggerRepairLocked();
                String userSetting = mSystemInterface.getUserChosenWebViewProvider(mContext);
                if (userSetting != null
                        && !userSetting.equals(mCurrentWebViewPackage.packageName)) {
                    // Don't persist the user-chosen setting across boots if the package being
                    // chosen is not used (could be disabled or uninstalled) so that the user won't
                    // be surprised by the device switching to using a certain webview package,
                    // that was uninstalled/disabled a long time ago, if it is installed/enabled
                    // again.
                    mSystemInterface.updateUserSetting(mContext,
                            mCurrentWebViewPackage.packageName);
                }
                onWebViewProviderChanged(mCurrentWebViewPackage);
            }

            if (repairNeeded) {
                attemptRepair();
            }

        } catch (Throwable t) {
            // Log and discard errors at this stage as we must not crash the system server.
            Slog.e(TAG, "error preparing webview provider from system server", t);
        }
    }

    private void startZygoteWhenReady() {
        // Wait on a background thread for RELRO creation to be done. We ignore the return value
        // because even if RELRO creation failed we still want to start the zygote.
        waitForAndGetProvider();
        mSystemInterface.ensureZygoteStarted();
    }

    @Override
    public void handleNewUser(int userId) {
        // The system user is always started at boot, and by that point we have already run one
        // round of the package-changing logic (through prepareWebViewInSystemServer()), so early
        // out here.
        if (userId == UserHandle.USER_SYSTEM) return;
        handleUserChange();
    }

    @Override
    public void handleUserRemoved(int userId) {
        handleUserChange();
    }

    /**
     * Called when a user was added or removed to ensure WebView preparation is triggered.
     * This has to be done since the WebView package we use depends on the enabled-state
     * of packages for all users (so adding or removing a user might cause us to change package).
     */
    private void handleUserChange() {
        // Potentially trigger package-changing logic.
        updateCurrentWebViewPackage(null);
    }

    @Override
    public void notifyRelroCreationCompleted() {
        synchronized (mLock) {
            mNumRelroCreationsFinished++;
            checkIfRelrosDoneLocked();
        }
    }

    @Override
    public WebViewProviderResponse waitForAndGetProvider() {
        PackageInfo webViewPackage = null;
        final long timeoutTimeMs = System.nanoTime() / NS_PER_MS + WAIT_TIMEOUT_MS;
        boolean webViewReady = false;
        int webViewStatus = WebViewFactory.LIBLOAD_SUCCESS;
        synchronized (mLock) {
            webViewReady = webViewIsReadyLocked();
            while (!webViewReady) {
                final long timeNowMs = System.nanoTime() / NS_PER_MS;
                if (timeNowMs >= timeoutTimeMs) break;
                try {
                    mLock.wait(timeoutTimeMs - timeNowMs);
                } catch (InterruptedException e) {
                    // ignore
                }
                webViewReady = webViewIsReadyLocked();
            }
            // Make sure we return the provider that was used to create the relro file
            webViewPackage = mCurrentWebViewPackage;
            if (webViewReady) {
                // success
            } else if (!mAnyWebViewInstalled) {
                webViewStatus = WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES;
            } else {
                // Either the current relro creation  isn't done yet, or the new relro creatioin
                // hasn't kicked off yet (the last relro creation used an out-of-date WebView).
                webViewStatus = WebViewFactory.LIBLOAD_FAILED_WAITING_FOR_RELRO;
                String timeoutError = "Timed out waiting for relro creation, relros started "
                        + mNumRelroCreationsStarted
                        + " relros finished " + mNumRelroCreationsFinished
                        + " package dirty? " + mWebViewPackageDirty;
                Slog.e(TAG, timeoutError);
                Trace.instant(Trace.TRACE_TAG_ACTIVITY_MANAGER, timeoutError);
            }
        }
        if (!webViewReady) Slog.w(TAG, "creating relro file timed out");
        return new WebViewProviderResponse(webViewPackage, webViewStatus);
    }

    /**
     * Change WebView provider and provider setting and kill packages using the old provider.
     * Return the new provider (in case we are in the middle of creating relro files, or
     * replacing that provider it will not be in use directly, but will be used when the relros
     * or the replacement are done).
     */
    @Override
    public String changeProviderAndSetting(String newProviderName) {
        PackageInfo newPackage = updateCurrentWebViewPackage(newProviderName);
        if (newPackage == null) return "";
        return newPackage.packageName;
    }

    /**
     * Update the current WebView package.
     * @param newProviderName the package to switch to, null if no package has been explicitly
     * chosen.
     */
    private PackageInfo updateCurrentWebViewPackage(@Nullable String newProviderName) {
        PackageInfo oldPackage = null;
        PackageInfo newPackage = null;
        boolean providerChanged = false;
        boolean repairNeeded = false;
        synchronized (mLock) {
            oldPackage = mCurrentWebViewPackage;

            if (newProviderName != null) {
                mSystemInterface.updateUserSetting(mContext, newProviderName);
            }

            try {
                newPackage = findPreferredWebViewPackage();
                providerChanged = (oldPackage == null)
                        || !newPackage.packageName.equals(oldPackage.packageName);
            } catch (WebViewPackageMissingException e) {
                // If updated the Setting but don't have an installed WebView package, the
                // Setting will be used when a package is available.
                mCurrentWebViewPackage = null;
                Slog.e(TAG, "Couldn't find WebView package to use " + e);
                return null;
            }
            // Perform the provider change if we chose a new provider
            if (providerChanged) {
                onWebViewProviderChanged(newPackage);
            }
            // Choosing another provider shouldn't break our state. Only check if repair
            // is needed if this function is called as a result of a user change.
            if (newProviderName == null) {
                repairNeeded = shouldTriggerRepairLocked();
            }
        }
        // Kill apps using the old provider only if we changed provider
        if (providerChanged && oldPackage != null) {
            mSystemInterface.killPackageDependents(oldPackage.packageName);
        }
        if (repairNeeded) {
            attemptRepair();
        }
        // Return the new provider, this is not necessarily the one we were asked to switch to,
        // but the persistent setting will now be pointing to the provider we were asked to
        // switch to anyway.
        return newPackage;
    }

    /**
     * This is called when we change WebView provider, either when the current provider is
     * updated or a new provider is chosen / takes precedence.
     */
    private void onWebViewProviderChanged(PackageInfo newPackage) {
        synchronized (mLock) {
            mAnyWebViewInstalled = true;
            if (mNumRelroCreationsStarted == mNumRelroCreationsFinished) {
                mSystemInterface.pinWebviewIfRequired(newPackage.applicationInfo);
                mCurrentWebViewPackage = newPackage;

                // The relro creations might 'finish' (not start at all) before
                // WebViewFactory.onWebViewProviderChanged which means we might not know the
                // number of started creations before they finish.
                mNumRelroCreationsStarted = NUMBER_OF_RELROS_UNKNOWN;
                mNumRelroCreationsFinished = 0;
                mNumRelroCreationsStarted =
                    mSystemInterface.onWebViewProviderChanged(newPackage);
                // If the relro creations finish before we know the number of started creations
                // we will have to do any cleanup/notifying here.
                checkIfRelrosDoneLocked();
            } else {
                mWebViewPackageDirty = true;
            }
        }

        // Once we've notified the system that the provider has changed and started RELRO creation,
        // try to restart the zygote so that it will be ready when apps use it.
        AsyncTask.THREAD_POOL_EXECUTOR.execute(this::startZygoteWhenReady);
    }

    /** Fetch only the currently valid WebView packages. */
    @Override
    public WebViewProviderInfo[] getValidWebViewPackages() {
        ProviderAndPackageInfo[] providersAndPackageInfos = getValidWebViewPackagesAndInfos();
        WebViewProviderInfo[] providers =
                new WebViewProviderInfo[providersAndPackageInfos.length];
        for (int n = 0; n < providersAndPackageInfos.length; n++) {
            providers[n] = providersAndPackageInfos[n].provider;
        }
        return providers;
    }

    /**
     * Returns the default WebView provider which should be first availableByDefault option in the
     * system config.
     */
    @Override
    public WebViewProviderInfo getDefaultWebViewPackage() {
        return mDefaultProvider;
    }

    private static class ProviderAndPackageInfo {
        public final WebViewProviderInfo provider;
        public final PackageInfo packageInfo;

        ProviderAndPackageInfo(WebViewProviderInfo provider, PackageInfo packageInfo) {
            this.provider = provider;
            this.packageInfo = packageInfo;
        }
    }

    private ProviderAndPackageInfo[] getValidWebViewPackagesAndInfos() {
        WebViewProviderInfo[] allProviders = mSystemInterface.getWebViewPackages();
        List<ProviderAndPackageInfo> providers = new ArrayList<>();
        for (int n = 0; n < allProviders.length; n++) {
            try {
                PackageInfo packageInfo =
                        mSystemInterface.getPackageInfoForProvider(allProviders[n]);
                if (validityResult(allProviders[n], packageInfo) == VALIDITY_OK) {
                    providers.add(new ProviderAndPackageInfo(allProviders[n], packageInfo));
                }
            } catch (NameNotFoundException e) {
                // Don't add non-existent packages
            }
        }
        return providers.toArray(new ProviderAndPackageInfo[providers.size()]);
    }

    /**
     * Returns either the package info of the WebView provider determined in the following way:
     * If the user has chosen a provider then use that if it is valid, enabled and installed
     * for all users, otherwise use the default provider.
     */
    private PackageInfo findPreferredWebViewPackage() throws WebViewPackageMissingException {
        // If the user has chosen provider, use that (if it's installed and enabled for all
        // users).
        String userChosenPackageName = mSystemInterface.getUserChosenWebViewProvider(mContext);
        WebViewProviderInfo userChosenProvider =
                getWebViewProviderForPackage(userChosenPackageName);
        if (userChosenProvider != null) {
            try {
                PackageInfo packageInfo =
                        mSystemInterface.getPackageInfoForProvider(userChosenProvider);
                if (validityResult(userChosenProvider, packageInfo) == VALIDITY_OK) {
                    List<UserPackage> userPackages =
                            mSystemInterface.getPackageInfoForProviderAllUsers(
                                    mContext, userChosenProvider);
                    if (isInstalledAndEnabledForAllUsers(userPackages)) {
                        return packageInfo;
                    }
                }
            } catch (NameNotFoundException e) {
                Slog.w(TAG, "User chosen WebView package (" + userChosenPackageName
                        + ") not found");
            }
        }

        // User did not choose, or the choice failed; return the default provider even if it is not
        // installed or enabled for all users.
        try {
            PackageInfo packageInfo = mSystemInterface.getPackageInfoForProvider(mDefaultProvider);
            if (validityResult(mDefaultProvider, packageInfo) == VALIDITY_OK) {
                return packageInfo;
            }
        } catch (NameNotFoundException e) {
            Slog.w(TAG, "Default WebView package (" + mDefaultProvider.packageName + ") not found");
        }

        // This should never happen during normal operation (only with modified system images).
        mAnyWebViewInstalled = false;
        throw new WebViewPackageMissingException("Could not find a loadable WebView package");
    }

    private WebViewProviderInfo getWebViewProviderForPackage(String packageName) {
        WebViewProviderInfo[] allProviders = getWebViewPackages();
        for (int n = 0; n < allProviders.length; n++) {
            if (allProviders[n].packageName.equals(packageName)) {
                return allProviders[n];
            }
        }
        return null;
    }

    /**
     * Return true iff {@param packageInfos} point to only installed and enabled packages.
     * The given packages {@param packageInfos} should all be pointing to the same package, but each
     * PackageInfo representing a different user's package.
     */
    private static boolean isInstalledAndEnabledForAllUsers(
            List<UserPackage> userPackages) {
        for (UserPackage userPackage : userPackages) {
            if (!userPackage.isInstalledPackage() || !userPackage.isEnabledPackage()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public WebViewProviderInfo[] getWebViewPackages() {
        return mSystemInterface.getWebViewPackages();
    }

    @Override
    public PackageInfo getCurrentWebViewPackage() {
        synchronized (mLock) {
            return mCurrentWebViewPackage;
        }
    }

    /**
     * Returns whether WebView is ready and is not going to go through its preparation phase
     * again directly.
     */
    private boolean webViewIsReadyLocked() {
        return !mWebViewPackageDirty
            && (mNumRelroCreationsStarted == mNumRelroCreationsFinished)
            // The current package might be replaced though we haven't received an intent
            // declaring this yet, the following flag makes anyone loading WebView to wait in
            // this case.
            && mAnyWebViewInstalled;
    }

    private void checkIfRelrosDoneLocked() {
        if (mNumRelroCreationsStarted == mNumRelroCreationsFinished) {
            if (mWebViewPackageDirty) {
                mWebViewPackageDirty = false;
                // If we have changed provider since we started the relro creation we need to
                // redo the whole process using the new package instead.
                try {
                    PackageInfo newPackage = findPreferredWebViewPackage();
                    onWebViewProviderChanged(newPackage);
                } catch (WebViewPackageMissingException e) {
                    mCurrentWebViewPackage = null;
                    // If we can't find any valid WebView package we are now in a state where
                    // mAnyWebViewInstalled is false, so loading WebView will be blocked and we
                    // should simply wait until we receive an intent declaring a new package was
                    // installed.
                }
            } else {
                mLock.notifyAll();
            }
        }
    }

    private int validityResult(WebViewProviderInfo configInfo, PackageInfo packageInfo) {
        // Ensure the provider targets this framework release (or a later one).
        if (!UserPackage.hasCorrectTargetSdkVersion(packageInfo)) {
            return VALIDITY_INCORRECT_SDK_VERSION;
        }
        if (!versionCodeGE(packageInfo.getLongVersionCode(), getMinimumVersionCode())
                && !mSystemInterface.systemIsDebuggable()) {
            // Webview providers may be downgraded arbitrarily low, prevent that by enforcing
            // minimum version code. This check is only enforced for user builds.
            return VALIDITY_INCORRECT_VERSION_CODE;
        }
        if (!providerHasValidSignature(configInfo, packageInfo, mSystemInterface)) {
            return VALIDITY_INCORRECT_SIGNATURE;
        }
        if (WebViewFactory.getWebViewLibrary(packageInfo.applicationInfo) == null) {
            return VALIDITY_NO_LIBRARY_FLAG;
        }
        return VALIDITY_OK;
    }

    /**
     * Both versionCodes should be from a WebView provider package implemented by Chromium.
     * VersionCodes from other kinds of packages won't make any sense in this method.
     *
     * An introduction to Chromium versionCode scheme:
     * "BBBBPPPXX"
     * BBBB: 4 digit branch number. It monotonically increases over time.
     * PPP: patch number in the branch. It is padded with zeroes to the left. These three digits
     * may change their meaning in the future.
     * XX: Digits to differentiate different APK builds of the same source version.
     *
     * This method takes the "BBBB" of versionCodes and compare them.
     *
     * https://www.chromium.org/developers/version-numbers describes general Chromium versioning;
     * https://source.chromium.org/chromium/chromium/src/+/master:build/util/android_chrome_version.py
     * is the canonical source for how Chromium versionCodes are calculated.
     *
     * @return true if versionCode1 is higher than or equal to versionCode2.
     */
    private static boolean versionCodeGE(long versionCode1, long versionCode2) {
        long v1 = versionCode1 / 100000;
        long v2 = versionCode2 / 100000;

        return v1 >= v2;
    }

    /**
     * Gets the minimum version code allowed for a valid provider. It is the minimum versionCode
     * of all available-by-default WebView provider packages. If there is no such WebView provider
     * package on the system, then return -1, which means all positive versionCode WebView packages
     * are accepted.
     *
     * Note that this is a private method that handles a variable (mMinimumVersionCode) which is
     * shared between threads. Furthermore, this method does not hold mLock meaning that we must
     * take extra care to ensure this method is thread-safe.
     */
    private long getMinimumVersionCode() {
        if (mMinimumVersionCode > 0) {
            return mMinimumVersionCode;
        }

        long minimumVersionCode = -1;
        for (WebViewProviderInfo provider : mSystemInterface.getWebViewPackages()) {
            if (provider.availableByDefault) {
                try {
                    long versionCode =
                            mSystemInterface.getFactoryPackageVersion(provider.packageName);
                    if (minimumVersionCode < 0 || versionCode < minimumVersionCode) {
                        minimumVersionCode = versionCode;
                    }
                } catch (NameNotFoundException e) {
                    // Safe to ignore.
                }
            }
        }

        mMinimumVersionCode = minimumVersionCode;
        return mMinimumVersionCode;
    }

    private static boolean providerHasValidSignature(WebViewProviderInfo provider,
            PackageInfo packageInfo, SystemInterface systemInterface) {
        // Skip checking signatures on debuggable builds, for development purposes.
        if (systemInterface.systemIsDebuggable()) return true;

        // Allow system apps to be valid providers regardless of signature.
        if (packageInfo.applicationInfo.isSystemApp()) return true;

        // We don't support packages with multiple signatures.
        if (packageInfo.signatures.length != 1) return false;

        // If any of the declared signatures match the package signature, it's valid.
        for (Signature signature : provider.signatures) {
            if (signature.equals(packageInfo.signatures[0])) return true;
        }

        return false;
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

    @Override
    public boolean isMultiProcessEnabled() {
        throw new IllegalStateException(
                "isMultiProcessEnabled shouldn't be called if update_service_v2 flag is set.");
    }

    @Override
    public void enableMultiProcess(boolean enable) {
        throw new IllegalStateException(
                "enableMultiProcess shouldn't be called if update_service_v2 flag is set.");
    }

    /** Dump the state of this Service. */
    @Override
    public void dumpState(PrintWriter pw) {
        pw.println("Current WebView Update Service state");
        synchronized (mLock) {
            if (mCurrentWebViewPackage == null) {
                pw.println("  Current WebView package is null");
            } else {
                pw.println(
                        TextUtils.formatSimple(
                                "  Current WebView package (name, version): (%s, %s)",
                                mCurrentWebViewPackage.packageName,
                                mCurrentWebViewPackage.versionName));
            }
            pw.println(
                    TextUtils.formatSimple(
                            "  Minimum targetSdkVersion: %d", UserPackage.MINIMUM_SUPPORTED_SDK));
            pw.println(
                    TextUtils.formatSimple(
                            "  Minimum WebView version code: %d", mMinimumVersionCode));
            pw.println(
                    TextUtils.formatSimple(
                            "  Number of relros started: %d", mNumRelroCreationsStarted));
            pw.println(
                    TextUtils.formatSimple(
                            "  Number of relros finished: %d", mNumRelroCreationsFinished));
            pw.println(TextUtils.formatSimple("  WebView package dirty: %b", mWebViewPackageDirty));
            pw.println(
                    TextUtils.formatSimple(
                            "  Any WebView package installed: %b", mAnyWebViewInstalled));

            try {
                PackageInfo preferredWebViewPackage = findPreferredWebViewPackage();
                pw.println(
                        TextUtils.formatSimple(
                                "  Preferred WebView package (name, version): (%s, %s)",
                                preferredWebViewPackage.packageName,
                                preferredWebViewPackage.versionName));
            } catch (WebViewPackageMissingException e) {
                pw.println("  Preferred WebView package: none");
            }

            dumpAllPackageInformationLocked(pw);
        }
    }

    private void dumpAllPackageInformationLocked(PrintWriter pw) {
        WebViewProviderInfo[] allProviders = mSystemInterface.getWebViewPackages();
        pw.println("  WebView packages:");
        for (WebViewProviderInfo provider : allProviders) {
            List<UserPackage> userPackages =
                    mSystemInterface.getPackageInfoForProviderAllUsers(mContext, provider);
            PackageInfo systemUserPackageInfo =
                    userPackages.get(UserHandle.USER_SYSTEM).getPackageInfo();
            if (systemUserPackageInfo == null) {
                pw.println(
                        TextUtils.formatSimple("    %s is NOT installed.", provider.packageName));
                continue;
            }

            int validity = validityResult(provider, systemUserPackageInfo);
            String packageDetails =
                    TextUtils.formatSimple(
                            "versionName: %s, versionCode: %d, targetSdkVersion: %d",
                            systemUserPackageInfo.versionName,
                            systemUserPackageInfo.getLongVersionCode(),
                            systemUserPackageInfo.applicationInfo.targetSdkVersion);
            if (validity == VALIDITY_OK) {
                boolean installedForAllUsers =
                        isInstalledAndEnabledForAllUsers(
                                mSystemInterface.getPackageInfoForProviderAllUsers(
                                        mContext, provider));
                pw.println(
                        TextUtils.formatSimple(
                                "    Valid package %s (%s) is %s installed/enabled for all users",
                                systemUserPackageInfo.packageName,
                                packageDetails,
                                installedForAllUsers ? "" : "NOT"));
            } else {
                pw.println(
                        TextUtils.formatSimple(
                                "    Invalid package %s (%s), reason: %s",
                                systemUserPackageInfo.packageName,
                                packageDetails,
                                getInvalidityReason(validity)));
            }
        }
    }

    private static String getInvalidityReason(int invalidityReason) {
        switch (invalidityReason) {
            case VALIDITY_INCORRECT_SDK_VERSION:
                return "SDK version too low";
            case VALIDITY_INCORRECT_VERSION_CODE:
                return "Version code too low";
            case VALIDITY_INCORRECT_SIGNATURE:
                return "Incorrect signature";
            case VALIDITY_NO_LIBRARY_FLAG:
                return "No WebView-library manifest flag";
            default:
                return "Unexcepted validity-reason";
        }
    }
}
