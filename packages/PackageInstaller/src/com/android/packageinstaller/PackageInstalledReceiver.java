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

package com.android.packageinstaller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

/**
 * Receive new app installed broadcast and notify user new app installed.
 */
public class PackageInstalledReceiver extends BroadcastReceiver {
    private static final String TAG = PackageInstalledReceiver.class.getSimpleName();

    private static final boolean DEBUG = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!context.getPackageManager().shouldShowNewAppInstalledNotification()) {
            return;
        }

        String action = intent.getAction();

        if (DEBUG) {
            Log.i(TAG, "Received action: " + action);
        }

        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            Uri packageUri = intent.getData();
            if (packageUri == null) {
                return;
            }

            String packageName = packageUri.getSchemeSpecificPart();
            if (packageName == null) {
                Log.e(TAG, "No package name");
                return;
            }

            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                if (DEBUG) {
                    Log.i(TAG, "Not new app, skip it: " + packageName);
                }
                return;
            }

            try {
                InstallSourceInfo installerInfo =
                        context.getPackageManager().getInstallSourceInfo(packageName);
                String installer = installerInfo.getInstallingPackageName();
                if (installer == null) {
                    Log.e(TAG, "No installer package name for: " + packageName);
                    return;
                }
                new PackageInstalledNotificationUtils(context, installer,
                        packageName).postAppInstalledNotification();
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Cannot get source info for: " + packageName);
            }
        }
    }
}
