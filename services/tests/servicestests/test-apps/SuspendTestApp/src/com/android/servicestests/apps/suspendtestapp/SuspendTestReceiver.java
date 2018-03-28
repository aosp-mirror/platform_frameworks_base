/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.servicestests.apps.suspendtestapp;

import static com.android.server.pm.SuspendPackagesTest.ACTION_REPORT_MY_PACKAGE_SUSPENDED;
import static com.android.server.pm.SuspendPackagesTest.ACTION_REPORT_MY_PACKAGE_UNSUSPENDED;
import static com.android.server.pm.SuspendPackagesTest.INSTRUMENTATION_PACKAGE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

public class SuspendTestReceiver extends BroadcastReceiver {
    private static final String TAG = SuspendTestReceiver.class.getSimpleName();

    public static final String PACKAGE_NAME = "com.android.servicestests.apps.suspendtestapp";
    public static final String ACTION_GET_SUSPENDED_STATE =
            PACKAGE_NAME + ".action.GET_SUSPENDED_STATE";
    public static final String EXTRA_SUSPENDED = PACKAGE_NAME + ".extra.SUSPENDED";
    public static final String EXTRA_SUSPENDED_APP_EXTRAS =
            PACKAGE_NAME + ".extra.SUSPENDED_APP_EXTRAS";

    @Override
    public void onReceive(Context context, Intent intent) {
        final PackageManager packageManager = context.getPackageManager();
        Log.d(TAG, "Received action " + intent.getAction());
        final Bundle appExtras;
        switch (intent.getAction()) {
            case ACTION_GET_SUSPENDED_STATE:
                final Bundle result = new Bundle();
                final boolean suspended = packageManager.isPackageSuspended();
                appExtras = packageManager.getSuspendedPackageAppExtras();
                result.putBoolean(EXTRA_SUSPENDED, suspended);
                result.putBundle(EXTRA_SUSPENDED_APP_EXTRAS, appExtras);
                setResult(0, null, result);
                break;
            case Intent.ACTION_MY_PACKAGE_SUSPENDED:
                appExtras = intent.getBundleExtra(Intent.EXTRA_SUSPENDED_PACKAGE_EXTRAS);
                final Intent reportSuspendIntent = new Intent(ACTION_REPORT_MY_PACKAGE_SUSPENDED)
                        .putExtra(EXTRA_SUSPENDED_APP_EXTRAS, appExtras)
                        .setPackage(INSTRUMENTATION_PACKAGE);
                context.sendBroadcast(reportSuspendIntent);
                break;
            case Intent.ACTION_MY_PACKAGE_UNSUSPENDED:
                final Intent reportUnsuspendIntent =
                        new Intent(ACTION_REPORT_MY_PACKAGE_UNSUSPENDED)
                        .setPackage(INSTRUMENTATION_PACKAGE);
                context.sendBroadcast(reportUnsuspendIntent);
                break;
            default:
                Log.e(TAG, "Unknown action: " + intent.getAction());
        }
    }
}
