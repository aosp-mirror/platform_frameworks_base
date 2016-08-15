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
 * limitations under the License
 */

package com.android.server.retaildemo;

import android.app.AppGlobals;
import android.app.PackageInstallObserver;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Helper class for installing preloaded APKs
 */
class PreloadAppsInstaller {
    private static final String SYSTEM_SERVER_PACKAGE_NAME = "android";
    private static String TAG = PreloadAppsInstaller.class.getSimpleName();
    private static final String PRELOAD_APK_EXT = ".apk.preload";
    private static boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final IPackageManager mPackageManager;
    private final File preloadsAppsDirectory;
    private final Context mContext;

    private final Map<String, String> mApkToPackageMap;

    PreloadAppsInstaller(Context context) {
        this(context, AppGlobals.getPackageManager(), Environment.getDataPreloadsAppsDirectory());
    }

    @VisibleForTesting
    PreloadAppsInstaller(Context context, IPackageManager packageManager, File preloadsAppsDirectory) {
        mContext = context;
        mPackageManager = packageManager;
        mApkToPackageMap = Collections.synchronizedMap(new ArrayMap<>());
        this.preloadsAppsDirectory = preloadsAppsDirectory;
    }

    void installApps(int userId) {
        File[] files = preloadsAppsDirectory.listFiles();
        AppInstallCounter counter = new AppInstallCounter(mContext, userId);
        if (ArrayUtils.isEmpty(files)) {
            counter.setExpectedAppsCount(0);
            return;
        }
        int expectedCount = 0;
        for (File file : files) {
            String apkName = file.getName();
            if (apkName.endsWith(PRELOAD_APK_EXT) && file.isFile()) {
                String packageName = mApkToPackageMap.get(apkName);
                if (packageName != null) {
                    try {
                        expectedCount++;
                        installExistingPackage(packageName, userId, counter);
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to install existing package " + packageName, e);
                    }
                } else {
                    try {
                        installPackage(file, userId, counter);
                        expectedCount++;
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to install package from " + file, e);
                    }
                }
            }
        }
        counter.setExpectedAppsCount(expectedCount);
    }

    private void installExistingPackage(String packageName, int userId,
            AppInstallCounter counter) {
        if (DEBUG) {
            Log.d(TAG, "installExistingPackage " + packageName + " u" + userId);
        }
        try {
            mPackageManager.installExistingPackageAsUser(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            counter.appInstallFinished();
        }
    }

    private void installPackage(File file, final int userId, AppInstallCounter counter)
            throws IOException, RemoteException {
        final String apkName = file.getName();
        if (DEBUG) {
            Log.d(TAG, "installPackage " + apkName + " u" + userId);
        }
        mPackageManager.installPackageAsUser(file.getPath(), new PackageInstallObserver() {
            @Override
            public void onPackageInstalled(String basePackageName, int returnCode, String msg,
                    Bundle extras) {
                if (DEBUG) {
                    Log.d(TAG, "Package " + basePackageName + " installed u" + userId
                            + " returnCode: " + returnCode + " msg: " + msg);
                }
                // Don't notify the counter for now, we'll do it in installExistingPackage
                if (returnCode == PackageManager.INSTALL_SUCCEEDED) {
                    mApkToPackageMap.put(apkName, basePackageName);
                    // Install on user 0 so that the package is cached when demo user is re-created
                    installExistingPackage(basePackageName, UserHandle.USER_SYSTEM, counter);
                } else if (returnCode == PackageManager.INSTALL_FAILED_ALREADY_EXISTS) {
                    // This can only happen in first session after a reboot
                    if (!mApkToPackageMap.containsKey(apkName)) {
                        mApkToPackageMap.put(apkName, basePackageName);
                    }
                    installExistingPackage(basePackageName, userId, counter);
                } else {
                    Log.e(TAG, "Package " + basePackageName + " cannot be installed from "
                            + apkName + ": " + msg + " (returnCode " + returnCode + ")");
                    counter.appInstallFinished();
                }
            }
        }.getBinder(), 0, SYSTEM_SERVER_PACKAGE_NAME, userId);
    }

    private static class AppInstallCounter {
        private int expectedCount = -1; // -1 means expectedCount not set
        private int finishedCount;
        private final Context mContext;
        private final int userId;

        AppInstallCounter(Context context, int userId) {
            mContext = context;
            this.userId = userId;
        }

        synchronized void appInstallFinished() {
            this.finishedCount++;
            checkIfAllFinished();
        }

        synchronized void setExpectedAppsCount(int expectedCount) {
            this.expectedCount = expectedCount;
            checkIfAllFinished();
        }

        private void checkIfAllFinished() {
            if (expectedCount == finishedCount) {
                Log.i(TAG, "All preloads finished installing for user " + userId);
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.DEMO_USER_SETUP_COMPLETE, "1", userId);
            }
        }
    }
}