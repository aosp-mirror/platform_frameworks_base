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

package com.android.server.tv;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Watches for emote provider services to be installed.
 * Adds a provider for each registered service.
 *
 * @see TvRemoteProviderProxy
 */
final class TvRemoteProviderWatcher {

    private static final String TAG = "TvRemoteProvWatcher";  // max. 23 chars
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.VERBOSE);

    private final Context mContext;
    private final ProviderMethods mProvider;
    private final Handler mHandler;
    private final PackageManager mPackageManager;
    private final ArrayList<TvRemoteProviderProxy> mProviderProxies = new ArrayList<>();
    private final int mUserId;
    private final String mUnbundledServicePackage;

    private boolean mRunning;

    public TvRemoteProviderWatcher(Context context, ProviderMethods provider, Handler handler) {
        mContext = context;
        mProvider = provider;
        mHandler = handler;
        mUserId = UserHandle.myUserId();
        mPackageManager = context.getPackageManager();
        mUnbundledServicePackage = context.getString(
                com.android.internal.R.string.config_tvRemoteServicePackage);
    }

    public void start() {
        if (DEBUG) Slog.d(TAG, "start()");
        if (!mRunning) {
            mRunning = true;

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addDataScheme("package");
            mContext.registerReceiverAsUser(mScanPackagesReceiver,
                    new UserHandle(mUserId), filter, null, mHandler);

            // Scan packages.
            // Also has the side-effect of restarting providers if needed.
            mHandler.post(mScanPackagesRunnable);
        }
    }

    public void stop() {
        if (mRunning) {
            mRunning = false;

            mContext.unregisterReceiver(mScanPackagesReceiver);
            mHandler.removeCallbacks(mScanPackagesRunnable);

            // Stop all providers.
            for (int i = mProviderProxies.size() - 1; i >= 0; i--) {
                mProviderProxies.get(i).stop();
            }
        }
    }

    private void scanPackages() {
        if (!mRunning) {
            return;
        }

        if (DEBUG) Log.d(TAG, "scanPackages()");
        // Add providers for all new services.
        // Reorder the list so that providers left at the end will be the ones to remove.
        int targetIndex = 0;
        Intent intent = new Intent(TvRemoteProviderProxy.SERVICE_INTERFACE);
        for (ResolveInfo resolveInfo : mPackageManager.queryIntentServicesAsUser(
                intent, 0, mUserId)) {
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (serviceInfo != null && verifyServiceTrusted(serviceInfo)) {
                int sourceIndex = findProvider(serviceInfo.packageName, serviceInfo.name);
                if (sourceIndex < 0) {
                    TvRemoteProviderProxy providerProxy =
                            new TvRemoteProviderProxy(mContext,
                                    new ComponentName(serviceInfo.packageName, serviceInfo.name),
                                    mUserId, serviceInfo.applicationInfo.uid);
                    providerProxy.start();
                    mProviderProxies.add(targetIndex++, providerProxy);
                    mProvider.addProvider(providerProxy);
                } else if (sourceIndex >= targetIndex) {
                    TvRemoteProviderProxy provider = mProviderProxies.get(sourceIndex);
                    provider.start(); // restart the provider if needed
                    provider.rebindIfDisconnected();
                    Collections.swap(mProviderProxies, sourceIndex, targetIndex++);
                }
            }
        }
        if (DEBUG) Log.d(TAG, "scanPackages() targetIndex " + targetIndex);
        // Remove providers for missing services.
        if (targetIndex < mProviderProxies.size()) {
            for (int i = mProviderProxies.size() - 1; i >= targetIndex; i--) {
                TvRemoteProviderProxy providerProxy = mProviderProxies.get(i);
                mProvider.removeProvider(providerProxy);
                mProviderProxies.remove(providerProxy);
                providerProxy.stop();
            }
        }
    }

    private boolean verifyServiceTrusted(ServiceInfo serviceInfo) {
        if (serviceInfo.permission == null || !serviceInfo.permission.equals(
                Manifest.permission.BIND_TV_REMOTE_SERVICE)) {
            // If the service does not require this permission then any app could
            // potentially bind to it and cause the atv remote provider service to
            // misbehave.  So we only want to trust providers that require the
            // correct permissions.
            Slog.w(TAG, "Ignoring atv remote provider service because it did not "
                    + "require the BIND_TV_REMOTE_SERVICE permission in its manifest: "
                    + serviceInfo.packageName + "/" + serviceInfo.name);
            return false;
        }

        // Check if package name is white-listed here.
        if (!serviceInfo.packageName.equals(mUnbundledServicePackage)) {
            Slog.w(TAG, "Ignoring atv remote provider service because the package has not "
                    + "been set and/or whitelisted: "
                    + serviceInfo.packageName + "/" + serviceInfo.name);
            return false;
        }

        if (!hasNecessaryPermissions(serviceInfo.packageName)) {
            // If the service does not have permission to be
            // a virtual tv remote controller, do not trust it.
            Slog.w(TAG, "Ignoring atv remote provider service because its package does not "
                    + "have TV_VIRTUAL_REMOTE_CONTROLLER permission: " + serviceInfo.packageName);
            return false;
        }

        // Looks good.
        return true;
    }

    // Returns true only if these permissions are present in calling package.
    // Manifest.permission.TV_VIRTUAL_REMOTE_CONTROLLER : virtual remote controller on TV
    private boolean hasNecessaryPermissions(String packageName) {
        if ((mPackageManager.checkPermission(Manifest.permission.TV_VIRTUAL_REMOTE_CONTROLLER,
                        packageName) == PackageManager.PERMISSION_GRANTED)) {
            return true;
        }
        return false;
    }

    private int findProvider(String packageName, String className) {
        int count = mProviderProxies.size();
        for (int i = 0; i < count; i++) {
            TvRemoteProviderProxy provider = mProviderProxies.get(i);
            if (provider.hasComponentName(packageName, className)) {
                return i;
            }
        }
        return -1;
    }

    private final BroadcastReceiver mScanPackagesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Slog.d(TAG, "Received package manager broadcast: " + intent);
            }
            mHandler.post(mScanPackagesRunnable);
        }
    };

    private final Runnable mScanPackagesRunnable = new Runnable() {
        @Override
        public void run() {
            scanPackages();
        }
    };

    public interface ProviderMethods {
        void addProvider(TvRemoteProviderProxy providerProxy);

        void removeProvider(TvRemoteProviderProxy providerProxy);
    }
}
