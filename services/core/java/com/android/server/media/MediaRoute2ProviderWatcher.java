/*
 * Copyright 2019 The Android Open Source Project
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

import static android.content.pm.PackageManager.GET_RESOLVED_FILTER;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.MediaRoute2ProviderService;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.media.flags.Flags;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

/**
 * Watches changes of packages, or scan them for finding media route providers.
 */
final class MediaRoute2ProviderWatcher {
    private static final String TAG = "MR2ProviderWatcher";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final PackageManager.ResolveInfoFlags RESOLVE_INFO_FLAGS =
            PackageManager.ResolveInfoFlags.of(GET_RESOLVED_FILTER);

    private final Context mContext;
    private final Callback mCallback;
    private final Handler mHandler;
    private final int mUserId;
    private final PackageManager mPackageManager;

    private final ArrayList<MediaRoute2ProviderServiceProxy> mProxies = new ArrayList<>();
    private boolean mRunning;

    MediaRoute2ProviderWatcher(Context context,
            Callback callback, Handler handler, int userId) {
        mContext = context;
        mCallback = callback;
        mHandler = handler;
        mUserId = userId;
        mPackageManager = context.getPackageManager();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "MediaRoute2ProviderWatcher");
        prefix += "  ";
        if (mProxies.isEmpty()) {
            pw.println(prefix + "<no provider service proxies>");
        } else {
            for (MediaRoute2ProviderServiceProxy proxy : mProxies) {
                proxy.dump(pw, prefix);
            }
        }
    }

    public void start() {
        if (!mRunning) {
            mRunning = true;

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            if (!Flags.enablePreventionOfKeepAliveRouteProviders()) {
                filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            }
            filter.addDataScheme("package");
            mContext.registerReceiverAsUser(mScanPackagesReceiver,
                    new UserHandle(mUserId), filter, null, mHandler);

            // Scan packages.
            // Also has the side-effect of restarting providers if needed.
            postScanPackagesIfNeeded();
        }
    }

    public void stop() {
        if (mRunning) {
            mRunning = false;

            mContext.unregisterReceiver(mScanPackagesReceiver);
            mHandler.removeCallbacks(this::scanPackages);

            // Stop all providers.
            for (int i = mProxies.size() - 1; i >= 0; i--) {
                mProxies.get(i).stop();
            }
        }
    }

    private void scanPackages() {
        if (!mRunning) {
            return;
        }

        // Add providers for all new services.
        // Reorder the list so that providers left at the end will be the ones to remove.
        int targetIndex = 0;
        Intent intent = new Intent(MediaRoute2ProviderService.SERVICE_INTERFACE);
        for (ResolveInfo resolveInfo :
                mPackageManager.queryIntentServicesAsUser(intent, RESOLVE_INFO_FLAGS, mUserId)) {
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (serviceInfo != null) {
                boolean isSelfScanOnlyProvider = false;
                Iterator<String> categoriesIterator = resolveInfo.filter.categoriesIterator();
                if (categoriesIterator != null) {
                    while (categoriesIterator.hasNext()) {
                        isSelfScanOnlyProvider |=
                                MediaRoute2ProviderService.CATEGORY_SELF_SCAN_ONLY.equals(
                                        categoriesIterator.next());
                    }
                }
                int sourceIndex = findProvider(serviceInfo.packageName, serviceInfo.name);
                if (sourceIndex < 0) {
                    MediaRoute2ProviderServiceProxy proxy =
                            new MediaRoute2ProviderServiceProxy(
                                    mContext,
                                    new ComponentName(serviceInfo.packageName, serviceInfo.name),
                                    isSelfScanOnlyProvider,
                                    mUserId);
                    proxy.start();
                    mProxies.add(targetIndex++, proxy);
                    mCallback.onAddProviderService(proxy);
                } else if (sourceIndex >= targetIndex) {
                    MediaRoute2ProviderServiceProxy proxy = mProxies.get(sourceIndex);
                    proxy.start(); // restart the proxy if needed
                    proxy.rebindIfDisconnected();
                    Collections.swap(mProxies, sourceIndex, targetIndex++);
                }
            }
        }

        // Remove providers for missing services.
        if (targetIndex < mProxies.size()) {
            for (int i = mProxies.size() - 1; i >= targetIndex; i--) {
                MediaRoute2ProviderServiceProxy proxy = mProxies.get(i);
                mCallback.onRemoveProviderService(proxy);
                mProxies.remove(proxy);
                proxy.stop();
            }
        }
    }

    private int findProvider(String packageName, String className) {
        int count = mProxies.size();
        for (int i = 0; i < count; i++) {
            MediaRoute2ProviderServiceProxy proxy = mProxies.get(i);
            if (proxy.hasComponentName(packageName, className)) {
                return i;
            }
        }
        return -1;
    }

    private void postScanPackagesIfNeeded() {
        if (!mHandler.hasCallbacks(this::scanPackages)) {
            mHandler.post(this::scanPackages);
        }
    }

    private final BroadcastReceiver mScanPackagesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Slog.d(TAG, "Received package manager broadcast: " + intent);
            }
            postScanPackagesIfNeeded();
        }
    };

    public interface Callback {
        void onAddProviderService(@NonNull MediaRoute2ProviderServiceProxy proxy);
        void onRemoveProviderService(@NonNull MediaRoute2ProviderServiceProxy proxy);
    }
}
