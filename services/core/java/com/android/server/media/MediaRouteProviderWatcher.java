/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.server.media;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.routeprovider.RouteProviderService;
import android.os.Handler;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

/**
 * Watches for media route provider services to be installed. Adds a provider to
 * the media session service for each registered service. For now just run all
 * providers. In the future define a policy for when to run providers.
 */
public class MediaRouteProviderWatcher {
    private static final String TAG = "MRPWatcher";
    private static final boolean DEBUG = true; // Log.isLoggable(TAG,
                                               // Log.DEBUG);

    private final Context mContext;
    private final Callback mCallback;
    private final Handler mHandler;
    private final int mUserId;
    private final PackageManager mPackageManager;

    private final ArrayList<MediaRouteProviderProxy> mProviders =
            new ArrayList<MediaRouteProviderProxy>();
    private boolean mRunning;

    public MediaRouteProviderWatcher(Context context, Callback callback, Handler handler,
            int userId) {
        mContext = context;
        mCallback = callback;
        mHandler = handler;
        mUserId = userId;
        mPackageManager = context.getPackageManager();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + " mUserId=" + mUserId);
        pw.println(prefix + " mRunning=" + mRunning);
        pw.println(prefix + " mProviders.size()=" + mProviders.size());
    }

    public void start() {
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

    // Stop discovering providers and routes. Providers that still have an
    // active session connected to them will not unbind.
    public void stop() {
        if (mRunning) {
            mRunning = false;

            mContext.unregisterReceiver(mScanPackagesReceiver);
            mHandler.removeCallbacks(mScanPackagesRunnable);

            // Stop all inactive providers.
            for (int i = mProviders.size() - 1; i >= 0; i--) {
                mProviders.get(i).stop();
            }
        }
    }

    // Clean up the providers forcibly unbinding if necessary
    public void destroy() {
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            mProviders.get(i).destroy();
            mProviders.remove(i);
        }
    }

    public ArrayList<MediaRouteProviderProxy> getProviders() {
        return mProviders;
    }

    public MediaRouteProviderProxy getProvider(String id) {
        int providerIndex = findProvider(id);
        if (providerIndex != -1) {
            return mProviders.get(providerIndex);
        }
        return null;
    }

    private void scanPackages() {
        if (!mRunning) {
            return;
        }

        // Add providers for all new services.
        // Reorder the list so that providers left at the end will be the ones
        // to remove.
        int targetIndex = 0;
        Intent intent = new Intent(RouteProviderService.SERVICE_INTERFACE);
        for (ResolveInfo resolveInfo : mPackageManager.queryIntentServicesAsUser(
                intent, 0, mUserId)) {
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (DEBUG) {
                Slog.d(TAG, "Checking service " + (serviceInfo == null ? null : serviceInfo.name));
            }
            if (serviceInfo != null && verifyServiceTrusted(serviceInfo)) {
                int sourceIndex = findProvider(serviceInfo.packageName, serviceInfo.name);
                if (sourceIndex < 0) {
                    // TODO get declared interfaces from manifest
                    if (DEBUG) {
                        Slog.d(TAG, "Creating new provider proxy for service");
                    }
                    MediaRouteProviderProxy provider =
                            new MediaRouteProviderProxy(mContext, UUID.randomUUID().toString(),
                                    new ComponentName(serviceInfo.packageName, serviceInfo.name),
                                    mUserId, null);
                    provider.start();
                    mProviders.add(targetIndex++, provider);
                    mCallback.addProvider(provider);
                } else if (sourceIndex >= targetIndex) {
                    MediaRouteProviderProxy provider = mProviders.get(sourceIndex);
                    provider.start(); // restart the provider if needed
                    provider.rebindIfDisconnected();
                    Collections.swap(mProviders, sourceIndex, targetIndex++);
                }
            }
        }

        // Remove providers for missing services.
        if (targetIndex < mProviders.size()) {
            for (int i = mProviders.size() - 1; i >= targetIndex; i--) {
                MediaRouteProviderProxy provider = mProviders.get(i);
                mCallback.removeProvider(provider);
                mProviders.remove(provider);
                provider.stop();
            }
        }
    }

    private boolean verifyServiceTrusted(ServiceInfo serviceInfo) {
        if (serviceInfo.permission == null || !serviceInfo.permission.equals(
                Manifest.permission.BIND_ROUTE_PROVIDER)) {
            // If the service does not require this permission then any app
            // could potentially bind to it and mess with their routes. So we
            // only want to trust providers that require the
            // correct permissions.
            Slog.w(TAG, "Ignoring route provider service because it did not "
                    + "require the BIND_ROUTE_PROVIDER permission in its manifest: "
                    + serviceInfo.packageName + "/" + serviceInfo.name);
            return false;
        }
        // Looks good.
        return true;
    }

    private int findProvider(String id) {
        int count = mProviders.size();
        for (int i = 0; i < count; i++) {
            MediaRouteProviderProxy provider = mProviders.get(i);
            if (TextUtils.equals(id, provider.getId())) {
                return i;
            }
        }
        return -1;
    }

    private int findProvider(String packageName, String className) {
        int count = mProviders.size();
        for (int i = 0; i < count; i++) {
            MediaRouteProviderProxy provider = mProviders.get(i);
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
            scanPackages();
        }
    };

    private final Runnable mScanPackagesRunnable = new Runnable() {
            @Override
        public void run() {
            scanPackages();
        }
    };

    public interface Callback {
        void addProvider(MediaRouteProviderProxy provider);

        void removeProvider(MediaRouteProviderProxy provider);
    }
}
