/*
 * Copyright 2018 The Android Open Source Project
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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.IMediaExtractorUpdateService;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.PatternMatcher;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import com.android.server.SystemService;

/** This class provides a system service that manages media framework updates. */
public class MediaUpdateService extends SystemService {
    private static final String TAG = "MediaUpdateService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String MEDIA_UPDATE_PACKAGE_NAME =
            SystemProperties.get("ro.mediacomponents.package");
    private static final String EXTRACTOR_UPDATE_SERVICE_NAME = "media.extractor.update";

    private IMediaExtractorUpdateService mMediaExtractorUpdateService;
    final Handler mHandler;

    public MediaUpdateService(Context context) {
        super(context);
        mHandler = new Handler();
    }

    @Override
    public void onStart() {
        if (("userdebug".equals(android.os.Build.TYPE) || "eng".equals(android.os.Build.TYPE))
                && !TextUtils.isEmpty(MEDIA_UPDATE_PACKAGE_NAME)) {
            connect();
            registerBroadcastReceiver();
        }
    }

    private void connect() {
        IBinder binder = ServiceManager.getService(EXTRACTOR_UPDATE_SERVICE_NAME);
        if (binder != null) {
            try {
                binder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Slog.w(TAG, "mediaextractor died; reconnecting");
                        mMediaExtractorUpdateService = null;
                        connect();
                    }
                }, 0);
            } catch (Exception e) {
                binder = null;
            }
        }
        if (binder != null) {
            mMediaExtractorUpdateService = IMediaExtractorUpdateService.Stub.asInterface(binder);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    packageStateChanged();
                }
            });
        } else {
            Slog.w(TAG, EXTRACTOR_UPDATE_SERVICE_NAME + " not found.");
        }
    }

    private void registerBroadcastReceiver() {
        BroadcastReceiver updateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_SYSTEM)
                            != UserHandle.USER_SYSTEM) {
                        // Ignore broadcast for non system users. We don't want to update system
                        // service multiple times.
                        return;
                    }
                    switch (intent.getAction()) {
                        case Intent.ACTION_PACKAGE_REMOVED:
                            if (intent.getExtras().getBoolean(Intent.EXTRA_REPLACING)) {
                                // The existing package is updated. Will be handled with the
                                // following ACTION_PACKAGE_ADDED case.
                                return;
                            }
                            packageStateChanged();
                            break;
                        case Intent.ACTION_PACKAGE_CHANGED:
                            packageStateChanged();
                            break;
                        case Intent.ACTION_PACKAGE_ADDED:
                            packageStateChanged();
                            break;
                    }
                }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(MEDIA_UPDATE_PACKAGE_NAME, PatternMatcher.PATTERN_LITERAL);

        getContext().registerReceiverAsUser(updateReceiver, UserHandle.ALL, filter,
                null /* broadcast permission */, null /* handler */);
    }

    private void packageStateChanged() {
        ApplicationInfo packageInfo = null;
        boolean pluginsAvailable = false;
        try {
            packageInfo = getContext().getPackageManager().getApplicationInfo(
                    MEDIA_UPDATE_PACKAGE_NAME, PackageManager.MATCH_SYSTEM_ONLY);
            pluginsAvailable = packageInfo.enabled;
        } catch (Exception e) {
            Slog.v(TAG, "package '" + MEDIA_UPDATE_PACKAGE_NAME + "' not installed");
        }
        if (packageInfo != null && Build.VERSION.SDK_INT != packageInfo.targetSdkVersion) {
            Slog.w(TAG, "This update package is not for this platform version. Ignoring. "
                    + "platform:" + Build.VERSION.SDK_INT
                    + " targetSdk:" + packageInfo.targetSdkVersion);
            pluginsAvailable = false;
        }
        loadExtractorPlugins(
                (packageInfo != null && pluginsAvailable) ? packageInfo.sourceDir : "");
    }

    private void loadExtractorPlugins(String apkPath) {
        try {
            if (mMediaExtractorUpdateService != null) {
                mMediaExtractorUpdateService.loadPlugins(apkPath);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error in loadPlugins", e);
        }
    }
}
