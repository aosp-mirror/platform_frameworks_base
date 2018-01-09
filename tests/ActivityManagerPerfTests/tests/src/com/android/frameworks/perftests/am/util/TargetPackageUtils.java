/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.frameworks.perftests.am.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;

public class TargetPackageUtils {
    private static final String TAG = TargetPackageUtils.class.getSimpleName();

    public static final String PACKAGE_NAME = "com.android.frameworks.perftests.amteststestapp";
    public static final String ACTIVITY_NAME = PACKAGE_NAME + ".TestActivity";
    public static final String SERVICE_NAME = PACKAGE_NAME + ".TestService";

    private static final long WAIT_TIME_MS = 100L;

    // Cache for test app's uid, so we only have to query it once.
    private static int sTestAppUid = -1;

    /**
     * Kills the test package synchronously.
     */
    public static void killTargetPackage(Context context) {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        activityManager.forceStopPackage(PACKAGE_NAME);
        while (targetPackageIsRunning(context)) {
            sleep();
        }

        Utils.drainBroadcastQueue();
    }

    /**
     * Starts the test package synchronously. It does so by starting an Activity.
     */
    public static void startTargetPackage(Context context, TimeReceiver timeReceiver) {
        // "am start-activity -W PACKAGE_NAME/ACTIVITY_CLASS_NAME" still requires a sleep even
        // though it should be synchronous, so just use Intent instead
        final Intent intent = new Intent();
        intent.putExtras(timeReceiver.createReceiveTimeExtraBinder());
        intent.setClassName(PACKAGE_NAME, ACTIVITY_NAME);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        while (!targetPackageIsRunning(context)) {
            sleep();
        }
        // make sure Application has run
        timeReceiver.getReceivedTimeNs(Constants.TYPE_TARGET_PACKAGE_START);
        Utils.drainBroadcastQueue();
    }

    private static boolean targetPackageIsRunning(Context context) {
        final int uid = getTestAppUid(context);
        final String result = Utils.runShellCommand(
                String.format("cmd activity get-uid-state %d", uid));
        return !result.contains("(NONEXISTENT)");
    }

    private static void sleep() {
        SystemClock.sleep(WAIT_TIME_MS);
    }

    private static int getTestAppUid(Context context) {
        if (sTestAppUid == -1) {
            final PackageManager pm = context.getPackageManager();
            try {
                sTestAppUid = pm.getPackageUid(PACKAGE_NAME, 0);
            } catch (PackageManager.NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        return sTestAppUid;
    }

}

