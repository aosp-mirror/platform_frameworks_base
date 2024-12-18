/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;

/** @hide */
@FlaggedApi(Flags.FLAG_UPDATE_SERVICE_IPC_WRAPPER)
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class WebViewUpdateManager {
    private final IWebViewUpdateService mService;

    /** @hide */
    public WebViewUpdateManager(@NonNull IWebViewUpdateService service) {
        mService = service;
    }

    /**
     * Get the singleton instance of the manager.
     *
     * <p>This exists for the benefit of callsites without a {@link Context}; prefer
     * {@link Context#getSystemService(Class)} otherwise.
     *
     * <p>This must only be called on devices with {@link PackageManager#FEATURE_WEBVIEW},
     * and will WTF or throw {@link UnsupportedOperationException} otherwise.
     */
    @SuppressLint("ManagerLookup") // service opts in to getSystemServiceWithNoContext()
    @RequiresFeature(PackageManager.FEATURE_WEBVIEW)
    public static @NonNull WebViewUpdateManager getInstance() {
        WebViewUpdateManager manager =
                (WebViewUpdateManager) SystemServiceRegistry.getSystemServiceWithNoContext(
                        Context.WEBVIEW_UPDATE_SERVICE);
        if (manager == null) {
            throw new UnsupportedOperationException("WebView not supported by device");
        } else {
            return manager;
        }
    }

    /**
     * Block until system-level WebView preparations are complete.
     *
     * <p>This also makes the current WebView provider package visible to the caller.
     *
     * @return the status of WebView preparation and the current provider package.
     */
    public @NonNull WebViewProviderResponse waitForAndGetProvider() {
        try {
            return mService.waitForAndGetProvider();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the package that is the system's current WebView implementation.
     *
     * @return the package, or null if no valid implementation is present.
     */
    public @Nullable PackageInfo getCurrentWebViewPackage() {
        try {
            return mService.getCurrentWebViewPackage();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the complete list of supported WebView providers for this device.
     *
     * <p>This includes all configured providers, regardless of whether they are currently available
     * or valid.
     */
    @SuppressLint({"ParcelableList", "ArrayReturn"})
    public @NonNull WebViewProviderInfo[] getAllWebViewPackages() {
        try {
            return mService.getAllWebViewPackages();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the list of currently-valid WebView providers for this device.
     *
     * <p>This only includes providers that are currently present on the device and meet the
     * validity criteria (signature, version, etc), but does not check if the provider is installed
     * and enabled for all users.
     *
     * <p>Note that this will be filtered by the caller's package visibility; callers should
     * have QUERY_ALL_PACKAGES permission to ensure that the list is complete.
     */
    @SuppressLint({"ParcelableList", "ArrayReturn"})
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    public @NonNull WebViewProviderInfo[] getValidWebViewPackages() {
        try {
            return mService.getValidWebViewPackages();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the package name of the current WebView implementation.
     *
     * @return the package name, or null if no valid implementation is present.
     */
    public @Nullable String getCurrentWebViewPackageName() {
        try {
            return mService.getCurrentWebViewPackageName();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Ask the system to switch to a specific WebView implementation if possible.
     *
     * <p>This choice will be stored persistently.
     *
     * @param newProvider the package name to use.
     * @return the package name which is now in use, which may not be the
     *         requested one if it was not usable.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public @Nullable String changeProviderAndSetting(@NonNull String newProvider) {
        try {
            return mService.changeProviderAndSetting(newProvider);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Used by the relro file creator to notify the service that it's done.
     * @hide
     */
    void notifyRelroCreationCompleted() {
        try {
            mService.notifyRelroCreationCompleted();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the WebView provider which will be used if no explicit choice has been made.
     *
     * <p>The default provider is not guaranteed to be a valid/usable WebView implementation.
     *
     * @return the default WebView provider.
     */
    @FlaggedApi(Flags.FLAG_UPDATE_SERVICE_V2)
    public @NonNull WebViewProviderInfo getDefaultWebViewPackage() {
        try {
            return mService.getDefaultWebViewPackage();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
