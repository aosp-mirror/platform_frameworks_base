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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import org.junit.Assert;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TargetPackageUtils {
    private static final String TAG = TargetPackageUtils.class.getSimpleName();
    public static final boolean VERBOSE = true;

    public static final String PACKAGE_NAME = "com.android.frameworks.perftests.amteststestapp";
    public static final String ACTIVITY_NAME = PACKAGE_NAME + ".TestActivity";
    public static final String SERVICE_NAME = PACKAGE_NAME + ".TestService";
    private static final String START_SERVICE_NAME = PACKAGE_NAME + ".StartProcessService";

    private static final long WAIT_TIME_MS = 100L;
    private static final long AWAIT_SERVICE_CONNECT_MS = 10000L;

    // Cache for test app's uid, so we only have to query it once.
    private static int sTestAppUid = -1;

    private static final ArrayMap<String, ICommandReceiver> sStubPackages =
            new ArrayMap<String, ICommandReceiver>();
    private static final ArrayMap<Integer, CountDownLatch> sCommandLatches =
            new ArrayMap<Integer, CountDownLatch>();
    private static int sSeqNum = 0;

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

    private static boolean isUidRunning(int uid) {
        return !Utils.runShellCommand(String.format("cmd activity get-uid-state %d", uid))
                .contains("(NONEXISTENT)");
    }

    public static void startStubPackage(Context context, String pkgName) {
        stopStubPackage(context, pkgName);
        try {
            Pair<Integer, CountDownLatch> pair = obtainLatch();
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(pkgName, Constants.STUB_INIT_SERVICE_NAME));
            intent.putExtra(Constants.EXTRA_SOURCE_PACKAGE, context.getPackageName());
            intent.putExtra(Constants.EXTRA_RECEIVER_CALLBACK, sMessenger);
            intent.putExtra(Constants.EXTRA_SEQ, pair.first);
            context.startService(intent);
            Assert.assertTrue("Timeout when waiting for starting package " +  pkgName,
                    pair.second.await(AWAIT_SERVICE_CONNECT_MS, TimeUnit.MILLISECONDS));
            Utils.runShellCommand("am unfreeze --sticky " + pkgName);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void stopStubPackage(Context context, String pkgName) {
        final PackageManager pm = context.getPackageManager();
        try {
            final int uid = pm.getPackageUid(pkgName, 0);
            if (isUidRunning(uid)) {
                ActivityManager am = context.getSystemService(ActivityManager.class);
                am.forceStopPackage(pkgName);
                while (isUidRunning(uid)) {
                    SystemClock.sleep(WAIT_TIME_MS);
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static void initCommandResultReceiver(Looper looper) {
        if (sMessenger == null) {
            sMessenger = new Messenger(new H(looper));
        }
    }

    private static void onPackageStartResult(int seq, Bundle bundle) {
        ICommandReceiver receiver = ICommandReceiver.Stub.asInterface(
                bundle.getBinder(Constants.EXTRA_RECEIVER_CALLBACK));
        String sourcePkg = bundle.getString(Constants.EXTRA_SOURCE_PACKAGE);
        sStubPackages.put(sourcePkg, receiver);
        releaseLatch(seq);
    }

    private static void onCommandResult(int seq, int result) {
        Assert.assertTrue("Error in command seq " + seq, result == Constants.RESULT_NO_ERROR);
        releaseLatch(seq);
    }

    private static Messenger sMessenger = null;
    private static class H extends Handler {
        H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case Constants.REPLY_PACKAGE_START_RESULT:
                    onPackageStartResult(msg.arg1 /* seq */, (Bundle) msg.obj);
                    break;
                case Constants.REPLY_COMMAND_RESULT:
                    onCommandResult(msg.arg1, msg.arg2);
                    break;
            }
        }
    }

    private static Pair<Integer, CountDownLatch> obtainLatch() {
        CountDownLatch latch = new CountDownLatch(1);
        int seq;
        synchronized (sCommandLatches) {
            seq = sSeqNum++;
            sCommandLatches.put(seq, latch);
        }
        return new Pair<>(seq, latch);
    }

    private static void releaseLatch(int seq) {
        synchronized (sCommandLatches) {
            CountDownLatch latch = sCommandLatches.get(seq);
            if (latch != null) {
                latch.countDown();
                sCommandLatches.remove(seq);
            }
        }
    }

    public static void sendCommand(int command, String sourcePackage, String targetPackage,
            int flags, Bundle bundle, boolean waitForResult) {
        ICommandReceiver receiver = sStubPackages.get(sourcePackage);
        Assert.assertTrue("Package hasn't been started: " + sourcePackage, receiver != null);
        try {
            Pair<Integer, CountDownLatch> pair = null;
            if (waitForResult) {
                pair = obtainLatch();
            }
            if (VERBOSE) {
                Log.i(TAG, "Sending command=" + command + ", seq=" + pair.first + ", from="
                        + sourcePackage + ", to=" + targetPackage + ", flags=" + flags);
            }
            receiver.sendCommand(command, pair.first, sourcePackage, targetPackage, flags, bundle);
            if (waitForResult) {
                Assert.assertTrue("Timeout when waiting for command " + command + " from "
                        + sourcePackage + " to " + targetPackage,
                        pair.second.await(AWAIT_SERVICE_CONNECT_MS, TimeUnit.MILLISECONDS));
            }
        } catch (RemoteException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void bindService(String sourcePackage, String targetPackage, int flags) {
        sendCommand(Constants.COMMAND_BIND_SERVICE, sourcePackage, targetPackage, flags, null,
                true);
    }

    public static void unbindService(String sourcePackage, String targetPackage, int flags) {
        sendCommand(Constants.COMMAND_UNBIND_SERVICE, sourcePackage, targetPackage, flags, null,
                true);
    }

    public static void acquireProvider(String sourcePackage, String targetPackage, Uri uri) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(Constants.EXTRA_URI, uri);
        sendCommand(Constants.COMMAND_ACQUIRE_CONTENT_PROVIDER, sourcePackage, targetPackage, 0,
                bundle, true);
    }

    public static void releaseProvider(String sourcePackage, String targetPackage, Uri uri) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(Constants.EXTRA_URI, uri);
        sendCommand(Constants.COMMAND_RELEASE_CONTENT_PROVIDER, sourcePackage, targetPackage, 0,
                bundle, true);
    }

    public static void sendBroadcast(String sourcePackage, String targetPackage) {
        sendCommand(Constants.COMMAND_SEND_BROADCAST, sourcePackage, targetPackage, 0, null, true);
    }

    public static void startActivity(String sourcePackage, String targetPackage) {
        sendCommand(Constants.COMMAND_START_ACTIVITY, sourcePackage, targetPackage, 0, null, true);
    }

    public static void stopActivity(String sourcePackage, String targetPackage) {
        sendCommand(Constants.COMMAND_STOP_ACTIVITY, sourcePackage, targetPackage, 0, null, true);
    }
}

