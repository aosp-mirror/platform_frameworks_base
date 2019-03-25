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

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;

/**
 */
final class MediaRoute2ProviderWatcher {
    private static final String TAG = "MediaRouteProvider";  // max. 23 chars
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final Callback mCallback;
    private final Handler mHandler;
    private final int mUserId;
    private final PackageManager mPackageManager;

    private final ArrayList<MediaRoute2ProviderProxy> mProviders = new ArrayList<>();
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
        pw.println(prefix + "Watcher");
        pw.println(prefix + "  mUserId=" + mUserId);
        pw.println(prefix + "  mRunning=" + mRunning);
        pw.println(prefix + "  mProviders.size()=" + mProviders.size());
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

    public void stop() {
        if (mRunning) {
            mRunning = false;

            mContext.unregisterReceiver(mScanPackagesReceiver);
            mHandler.removeCallbacks(mScanPackagesRunnable);

            // Stop all providers.
            for (int i = mProviders.size() - 1; i >= 0; i--) {
                mProviders.get(i).stop();
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
        for (ResolveInfo resolveInfo : mPackageManager.queryIntentServicesAsUser(
                intent, 0, mUserId)) {
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (serviceInfo != null) {
                int sourceIndex = findProvider(serviceInfo.packageName, serviceInfo.name);
                if (sourceIndex < 0) {
                    MediaRoute2ProviderProxy provider =
                            new MediaRoute2ProviderProxy(mContext,
                            new ComponentName(serviceInfo.packageName, serviceInfo.name),
                            mUserId);
                    provider.start();
                    mProviders.add(targetIndex++, provider);
                    mCallback.addProvider(provider);
                } else if (sourceIndex >= targetIndex) {
                    MediaRoute2ProviderProxy provider = mProviders.get(sourceIndex);
                    provider.start(); // restart the provider if needed
                    provider.rebindIfDisconnected();
                    Collections.swap(mProviders, sourceIndex, targetIndex++);
                }
            }
        }

        // Remove providers for missing services.
        if (targetIndex < mProviders.size()) {
            for (int i = mProviders.size() - 1; i >= targetIndex; i--) {
                MediaRoute2ProviderProxy provider = mProviders.get(i);
                mCallback.removeProvider(provider);
                mProviders.remove(provider);
                provider.stop();
            }
        }
    }

    private int findProvider(String packageName, String className) {
        int count = mProviders.size();
        for (int i = 0; i < count; i++) {
            MediaRoute2ProviderProxy provider = mProviders.get(i);
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
        void addProvider(MediaRoute2ProviderProxy provider);
        void removeProvider(MediaRoute2ProviderProxy provider);
    }
}
