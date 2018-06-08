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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;

import org.junit.Assert;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TargetPackageUtils {
    private static final String TAG = TargetPackageUtils.class.getSimpleName();

    public static final String PACKAGE_NAME = "com.android.frameworks.perftests.amteststestapp";
    public static final String ACTIVITY_NAME = PACKAGE_NAME + ".TestActivity";
    public static final String SERVICE_NAME = PACKAGE_NAME + ".TestService";
    private static final String START_SERVICE_NAME = PACKAGE_NAME + ".StartProcessService";

    private static final long WAIT_TIME_MS = 100L;
    private static final long AWAIT_SERVICE_CONNECT_MS = 10000L;

    // Cache for test app's uid, so we only have to query it once.
    private static int sTestAppUid = -1;

    /**
     * Kills the test package synchronously.
     */
    public static void killTargetPackage(Context context, ServiceConnection serviceConnection) {
        unbindFromService(context, serviceConnection);

        final ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        activityManager.forceStopPackage(PACKAGE_NAME);
        while (targetPackageIsRunning(context)) {
            SystemClock.sleep(WAIT_TIME_MS);
        }

        Utils.drainBroadcastQueue();
    }

    /**
     * Starts the test package synchronously. It does so by starting an Activity.
     */
    public static ServiceConnection startTargetPackage(Context context) {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_RESULT_RECEIVER, new CountDownResultReceiver(countDownLatch));
        intent.setClassName(PACKAGE_NAME, START_SERVICE_NAME);
        final ServiceConnection serviceConnection = bindAndWaitForConnectedService(context, intent);

        try {
            final boolean targetPackageIdleSuccess = countDownLatch.await(AWAIT_SERVICE_CONNECT_MS,
                    TimeUnit.MILLISECONDS);
            Assert.assertTrue("Timeout when waiting for ILooperIdleCallback.Stub.looperIdle()",
                    targetPackageIdleSuccess);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Utils.drainBroadcastQueue();
        return serviceConnection;
    }

    private static boolean targetPackageIsRunning(Context context) {
        final int uid = getTestAppUid(context);
        final String result = Utils.runShellCommand(
                String.format("cmd activity get-uid-state %d", uid));
        return !result.contains("(NONEXISTENT)");
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

    public static ServiceConnection bindAndWaitForConnectedService(Context context, Intent intent) {
        return bindAndWaitForConnectedService(context, intent, 0);
    }

    public static ServiceConnection bindAndWaitForConnectedService(Context context, Intent intent,
            int bindFlags) {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final ServiceConnection serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                countDownLatch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };

        final boolean success = context.bindService(intent, serviceConnection,
                Context.BIND_AUTO_CREATE | bindFlags);
        Assert.assertTrue("Could not bind to service", success);

        try {
            final boolean connectedSuccess = countDownLatch.await(AWAIT_SERVICE_CONNECT_MS,
                    TimeUnit.MILLISECONDS);
            Assert.assertTrue("Timeout when waiting for ServiceConnection.onServiceConnected()",
                    connectedSuccess);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        return serviceConnection;
    }

    public static void unbindFromService(Context context, ServiceConnection serviceConnection) {
        if (serviceConnection != null) {
            context.unbindService(serviceConnection);
        }
    }

}

