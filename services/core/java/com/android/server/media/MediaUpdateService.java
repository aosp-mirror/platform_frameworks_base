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
import android.media.IMediaUpdateService;
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
import java.util.HashMap;

/** This class provides a system service that manages media framework updates. */
public class MediaUpdateService extends SystemService {
    private static final String TAG = "MediaUpdateService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String MEDIA_UPDATE_PACKAGE_NAME =
            SystemProperties.get("ro.mediacomponents.package");
    private static final String EXTRACTOR_UPDATE_SERVICE_NAME = "media.extractor.update";
    private static final String CODEC_UPDATE_SERVICE_NAME = "media.codec.update";
    private static final String[] UPDATE_SERVICE_NAME_ARRAY = {
            EXTRACTOR_UPDATE_SERVICE_NAME, CODEC_UPDATE_SERVICE_NAME,
    };
    private final HashMap<String, IMediaUpdateService> mUpdateServiceMap = new HashMap<>();
    private final Handler mHandler = new Handler();

    public MediaUpdateService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        if (("userdebug".equals(android.os.Build.TYPE) || "eng".equals(android.os.Build.TYPE))
                && !TextUtils.isEmpty(MEDIA_UPDATE_PACKAGE_NAME)) {
            for (String serviceName : UPDATE_SERVICE_NAME_ARRAY) {
                connect(serviceName);
            }
            registerBroadcastReceiver();
        }
    }

    private void connect(final String serviceName) {
        IBinder binder = ServiceManager.getService(serviceName);
        if (binder != null) {
            try {
                binder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Slog.w(TAG, "service " + serviceName + " died; reconnecting");
                        synchronized (mUpdateServiceMap) {
                            mUpdateServiceMap.remove(serviceName);
                        }
                        connect(serviceName);
                    }
                }, 0);
            } catch (Exception e) {
                binder = null;
            }
        }
        if (binder != null) {
            synchronized (mUpdateServiceMap) {
                mUpdateServiceMap.put(serviceName,
                        IMediaUpdateService.Stub.asInterface(binder));
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    packageStateChanged(serviceName);
                }
            });
        } else {
            Slog.w(TAG, serviceName + " not found.");
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
                            // fall-thru
                        case Intent.ACTION_PACKAGE_CHANGED:
                        case Intent.ACTION_PACKAGE_ADDED:
                            for (String serviceName : UPDATE_SERVICE_NAME_ARRAY) {
                                packageStateChanged(serviceName);
                            }
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

    private void packageStateChanged(String serviceName) {
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
        loadPlugins(serviceName,
                (packageInfo != null && pluginsAvailable) ? packageInfo.sourceDir : "");
    }

    private void loadPlugins(String serviceName, String apkPath) {
        try {
            IMediaUpdateService service = null;
            synchronized (serviceName) {
                service = mUpdateServiceMap.get(serviceName);
            }
            if (service != null) {
                service.loadPlugins(apkPath);
            } else {
                Slog.w(TAG, "service " + serviceName + " passed away");
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error in loadPlugins for " + serviceName, e);
        }
    }
}
