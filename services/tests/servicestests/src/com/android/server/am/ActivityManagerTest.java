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
 * limitations under the License.
 */

package com.android.server.am;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.app.ActivityManager.OnUidImportanceListener;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.server.wm.settings.SettingsSession;
import android.support.test.uiautomator.UiDevice;
import android.test.suitebuilder.annotation.LargeTest;
import android.text.TextUtils;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tests for {@link ActivityManager}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ActivityManagerTest
 */
@FlakyTest(detail = "Promote to presubmit if stable")
@Presubmit
public class ActivityManagerTest {
    private static final String TAG = "ActivityManagerTest";

    private static final String TEST_APP1 = "com.android.servicestests.apps.simpleservicetestapp1";
    private static final String TEST_APP2 = "com.android.servicestests.apps.simpleservicetestapp2";
    private static final String TEST_APP3 = "com.android.servicestests.apps.simpleservicetestapp3";
    private static final String TEST_CLASS =
            "com.android.servicestests.apps.simpleservicetestapp.SimpleService";
    private static final int TEST_LOOPS = 100;
    private static final long AWAIT_TIMEOUT = 2000;
    private static final long CHECK_INTERVAL = 100;

    private static final String TEST_FGS_CLASS =
            "com.android.servicestests.apps.simpleservicetestapp.SimpleFgService";
    private static final String ACTION_FGS_STATS_TEST =
            "com.android.servicestests.apps.simpleservicetestapp.ACTION_FGS_STATS_TEST";
    private static final String EXTRA_MESSENGER = "extra_messenger";
    private static final String ACTION_RECEIVER_TEST =
            "com.android.servicestests.apps.simpleservicetestapp.TEST";

    private static final String EXTRA_CALLBACK = "callback";
    private static final String EXTRA_COMMAND = "command";
    private static final String EXTRA_FLAGS = "flags";
    private static final String EXTRA_TARGET_PACKAGE = "target_package";

    private static final int COMMAND_INVALID = 0;
    private static final int COMMAND_EMPTY = 1;
    private static final int COMMAND_BIND_SERVICE = 2;
    private static final int COMMAND_UNBIND_SERVICE = 3;
    private static final int COMMAND_STOP_SELF = 4;

    private static final String TEST_ISOLATED_CLASS =
            "com.android.servicestests.apps.simpleservicetestapp.SimpleIsolatedService";

    private IActivityManager mService;
    private IRemoteCallback mCallback;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mService = ActivityManager.getService();
        mContext = InstrumentationRegistry.getTargetContext();
    }

    @Test
    public void testTaskIdsForRunningUsers() throws RemoteException {
        int[] runningUserIds = mService.getRunningUserIds();
        assertThat(runningUserIds).isNotEmpty();
        for (int userId : runningUserIds) {
            testTaskIdsForUser(userId);
        }
    }

    private void testTaskIdsForUser(int userId) throws RemoteException {
        List<?> recentTasks = mService.getRecentTasks(100, 0, userId).getList();
        if (recentTasks != null) {
            for (Object elem : recentTasks) {
                assertThat(elem).isInstanceOf(RecentTaskInfo.class);
                RecentTaskInfo recentTask = (RecentTaskInfo) elem;
                int taskId = recentTask.taskId;
                assertEquals("The task id " + taskId + " should not belong to user " + userId,
                             taskId / UserHandle.PER_USER_RANGE, userId);
            }
        }
    }

    @Test
    public void testServiceUnbindAndKilling() {
        for (int i = TEST_LOOPS; i > 0; i--) {
            runOnce(i);
        }
    }

    private void runOnce(long yieldDuration) {
        final PackageManager pm = mContext.getPackageManager();
        int uid = 0;
        try {
            uid = pm.getPackageUid(TEST_APP1, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        Intent intent = new Intent();
        intent.setClassName(TEST_APP1, TEST_CLASS);

        // Create a service connection with auto creation.
        CountDownLatch latch = new CountDownLatch(1);
        final MyServiceConnection autoConnection = new MyServiceConnection(latch);
        mContext.bindService(intent, autoConnection, Context.BIND_AUTO_CREATE);
        try {
            assertTrue("Timeout to bind to service " + intent.getComponent(),
                    latch.await(AWAIT_TIMEOUT, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            fail("Unable to bind to service " + intent.getComponent());
        }

        // Create a service connection without any flags.
        intent = new Intent();
        intent.setClassName(TEST_APP1, TEST_CLASS);
        latch = new CountDownLatch(1);
        MyServiceConnection otherConnection = new MyServiceConnection(latch);
        mContext.bindService(intent, otherConnection, 0);
        try {
            assertTrue("Timeout to bind to service " + intent.getComponent(),
                    latch.await(AWAIT_TIMEOUT, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            fail("Unable to bind to service " + intent.getComponent());
        }

        // Inform the remote process to kill itself
        try {
            mCallback.sendResult(null);
            // It's basically a test for race condition, we expect the bringDownServiceLocked()
            // would find out the hosting process is dead - to do this, technically we should
            // do killing and unbinding simultaneously; but in reality, the killing would take
            // a little while, before the signal really kills it; so we do it in the same thread,
            // and even wait a while after sending killing signal.
            Thread.sleep(yieldDuration);
        } catch (RemoteException | InterruptedException e) {
            fail("Unable to kill the process");
        }
        // Now unbind that auto connection, this should be equivalent to stopService
        mContext.unbindService(autoConnection);

        // Now we don't expect the system_server crashes.

        // Wait for the target process dies
        long total = 0;
        for (; total < AWAIT_TIMEOUT; total += CHECK_INTERVAL) {
            try {
                if (!targetPackageIsRunning(mContext, uid)) {
                    break;
                }
                Thread.sleep(CHECK_INTERVAL);
            } catch (InterruptedException e) {
            }
        }
        assertTrue("Timeout to wait for the target package dies", total < AWAIT_TIMEOUT);
        mCallback = null;
    }

    private boolean targetPackageIsRunning(Context context, int uid) {
        final String result = runShellCommand(
                String.format("cmd activity get-uid-state %d", uid));
        return !result.contains("(NONEXISTENT)");
    }

    private static String runShellCommand(String cmd) {
        try {
            return UiDevice.getInstance(
                    InstrumentationRegistry.getInstrumentation()).executeShellCommand(cmd);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private class MyServiceConnection implements ServiceConnection {
        private CountDownLatch mLatch;

        MyServiceConnection(CountDownLatch latch) {
            this.mLatch = latch;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mCallback = IRemoteCallback.Stub.asInterface(service);
            mLatch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }

    /**
     * Note: This test actually only works in eng build. It'll always pass
     * in user and userdebug build, because the expected exception won't be
     * thrown in those builds.
     */
    @LargeTest
    @Test
    public void testFgsProcStatsTracker() throws Exception {
        final PackageManager pm = mContext.getPackageManager();
        final long timeout = 5000;
        int uid = pm.getPackageUid(TEST_APP1, 0);
        final MyUidImportanceListener uidListener1 = new MyUidImportanceListener(uid);
        final MyUidImportanceListener uidListener2 = new MyUidImportanceListener(uid);
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final CountDownLatch[] latchHolder = new CountDownLatch[1];
        final H handler = new H(Looper.getMainLooper(), latchHolder);
        final Messenger messenger = new Messenger(handler);
        final DropBoxManager dbox = mContext.getSystemService(DropBoxManager.class);
        final CountDownLatch dboxLatch = new CountDownLatch(1);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String tag_wtf = "system_server_wtf";
                if (tag_wtf.equals(intent.getStringExtra(DropBoxManager.EXTRA_TAG))) {
                    final DropBoxManager.Entry e = dbox.getNextEntry(tag_wtf, intent.getLongExtra(
                            DropBoxManager.EXTRA_TIME, 0) - 1);
                    final String text = e.getText(8192);
                    if (TextUtils.isEmpty(text)) {
                        return;
                    }
                    if (text.indexOf("can't store negative values") == -1) {
                        return;
                    }
                    dboxLatch.countDown();
                }
            }
        };
        try {
            mContext.registerReceiver(receiver,
                    new IntentFilter(DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED));
            am.addOnUidImportanceListener(uidListener1,
                    RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
            am.addOnUidImportanceListener(uidListener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            runShellCommand("cmd deviceidle whitelist +" + TEST_APP1);
            toggleScreenOn(true);

            final Intent intent = new Intent(ACTION_FGS_STATS_TEST);
            final ComponentName cn = ComponentName.unflattenFromString(
                    TEST_APP1 + "/" + TEST_FGS_CLASS);
            final Bundle bundle = new Bundle();
            intent.setComponent(cn);
            bundle.putBinder(EXTRA_MESSENGER, messenger.getBinder());
            intent.putExtras(bundle);

            latchHolder[0] = new CountDownLatch(1);
            mContext.startForegroundService(intent);
            assertTrue("Timed out to start fg service", uidListener1.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE, timeout));
            assertTrue("Timed out to get the remote messenger", latchHolder[0].await(
                    timeout, TimeUnit.MILLISECONDS));

            Thread.sleep(timeout);
            latchHolder[0] = new CountDownLatch(1);
            handler.sendRemoteMessage(H.MSG_STOP_FOREGROUND, 0, 0, null);
            assertTrue("Timed out to wait for stop fg", latchHolder[0].await(
                    timeout, TimeUnit.MILLISECONDS));

            Thread.sleep(timeout);
            latchHolder[0] = new CountDownLatch(1);
            handler.sendRemoteMessage(H.MSG_START_FOREGROUND, 0, 0, null);
            assertTrue("Timed out to wait for start fg", latchHolder[0].await(
                    timeout, TimeUnit.MILLISECONDS));

            toggleScreenOn(false);
            latchHolder[0] = new CountDownLatch(1);
            handler.sendRemoteMessage(H.MSG_STOP_FOREGROUND, 0, 0, null);
            assertTrue("Timed out to wait for stop fg", latchHolder[0].await(
                    timeout, TimeUnit.MILLISECONDS));
            assertFalse("There shouldn't be negative values", dboxLatch.await(
                    timeout * 2, TimeUnit.MILLISECONDS));
        } finally {
            toggleScreenOn(true);
            runShellCommand("cmd deviceidle whitelist -" + TEST_APP1);
            am.removeOnUidImportanceListener(uidListener1);
            am.removeOnUidImportanceListener(uidListener2);
            am.forceStopPackage(TEST_APP1);
            mContext.unregisterReceiver(receiver);
        }
    }

    @LargeTest
    @Test
    public void testAppFreezerWithAllowOomAdj() throws Exception {
        final long waitFor = 5000;
        boolean freezerWasEnabled = isFreezerEnabled();
        SettingsSession<String> freezerEnabled = null;
        SettingsSession<String> amConstantsSettings = null;
        DeviceConfigSession<Long> freezerDebounceTimeout = null;
        MyServiceConnection autoConnection = null;
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final PackageManager pm = mContext.getPackageManager();
        final int uid1 = pm.getPackageUid(TEST_APP1, 0);
        final int uid2 = pm.getPackageUid(TEST_APP2, 0);
        final MyUidImportanceListener uid1Listener = new MyUidImportanceListener(uid1);
        final MyUidImportanceListener uid2Listener = new MyUidImportanceListener(uid2);
        try {
            if (!freezerWasEnabled) {
                freezerEnabled = new SettingsSession<>(
                        Settings.Global.getUriFor(Settings.Global.CACHED_APPS_FREEZER_ENABLED),
                        Settings.Global::getString, Settings.Global::putString);
                freezerEnabled.set("enabled");
                Thread.sleep(waitFor);
                if (!isFreezerEnabled()) {
                    // Still not enabled? Probably because the device doesn't support it.
                    return;
                }
            }
            freezerDebounceTimeout = new DeviceConfigSession<>(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                    CachedAppOptimizer.KEY_FREEZER_DEBOUNCE_TIMEOUT,
                    DeviceConfig::getLong, CachedAppOptimizer.DEFAULT_FREEZER_DEBOUNCE_TIMEOUT);
            freezerDebounceTimeout.set(waitFor);

            final String activityManagerConstants = Settings.Global.ACTIVITY_MANAGER_CONSTANTS;
            amConstantsSettings = new SettingsSession<>(
                Settings.Global.getUriFor(activityManagerConstants),
                Settings.Global::getString, Settings.Global::putString);

            amConstantsSettings.set(
                    ActivityManagerConstants.KEY_MAX_SERVICE_INACTIVITY + "=" + waitFor);

            runShellCommand("cmd deviceidle whitelist +" + TEST_APP1);
            runShellCommand("cmd deviceidle whitelist +" + TEST_APP2);

            am.addOnUidImportanceListener(uid1Listener,
                    RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
            am.addOnUidImportanceListener(uid2Listener, RunningAppProcessInfo.IMPORTANCE_CACHED);

            final Intent intent = new Intent();
            intent.setClassName(TEST_APP1, TEST_CLASS);

            CountDownLatch latch = new CountDownLatch(1);
            autoConnection = new MyServiceConnection(latch);
            mContext.bindService(intent, autoConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY);
            try {
                assertTrue("Timeout to bind to service " + intent.getComponent(),
                        latch.await(AWAIT_TIMEOUT, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                fail("Unable to bind to service " + intent.getComponent());
            }
            assertFalse(TEST_APP1 + " shouldn't be frozen now.", isAppFrozen(TEST_APP1));

            // Trigger oomAdjUpdate/
            toggleScreenOn(false);
            toggleScreenOn(true);

            // Wait for the freezer kick in if there is any.
            Thread.sleep(waitFor * 4);

            // It still shouldn't be frozen, although it's been in cached state.
            assertFalse(TEST_APP1 + " shouldn't be frozen now.", isAppFrozen(TEST_APP1));

            final CountDownLatch[] latchHolder = new CountDownLatch[1];
            final IRemoteCallback callback = new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle bundle) {
                    if (bundle != null) {
                        latchHolder[0].countDown();
                    }
                }
            };

            // Bind from app1 to app2 without BIND_WAIVE_PRIORITY.
            final Bundle extras = new Bundle();
            extras.putBinder(EXTRA_CALLBACK, callback.asBinder());
            latchHolder[0] = new CountDownLatch(1);
            sendCommand(COMMAND_BIND_SERVICE, TEST_APP1, TEST_APP2, extras);
            assertTrue("Timed out to bind to " + TEST_APP2, latchHolder[0].await(
                    waitFor, TimeUnit.MILLISECONDS));

            // Stop service in app1
            extras.clear();
            sendCommand(COMMAND_STOP_SELF, TEST_APP1, TEST_APP1, extras);

            assertTrue(TEST_APP2 + " should be in cached", uid2Listener.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_CACHED, waitFor));

            // Wait for the freezer kick in if there is any.
            Thread.sleep(waitFor * 4);

            // It still shouldn't be frozen, although it's been in cached state.
            assertFalse(TEST_APP2 + " shouldn't be frozen now.", isAppFrozen(TEST_APP2));
        } finally {
            toggleScreenOn(true);
            if (amConstantsSettings != null) {
                amConstantsSettings.close();
            }
            if (freezerEnabled != null) {
                freezerEnabled.close();
            }
            if (freezerDebounceTimeout != null) {
                freezerDebounceTimeout.close();
            }
            if (autoConnection != null) {
                mContext.unbindService(autoConnection);
            }
            am.removeOnUidImportanceListener(uid1Listener);
            am.removeOnUidImportanceListener(uid2Listener);
            sendCommand(COMMAND_UNBIND_SERVICE, TEST_APP1, TEST_APP2, null);
            sendCommand(COMMAND_UNBIND_SERVICE, TEST_APP2, TEST_APP1, null);
            runShellCommand("cmd deviceidle whitelist -" + TEST_APP1);
            runShellCommand("cmd deviceidle whitelist -" + TEST_APP2);
            am.forceStopPackage(TEST_APP1);
            am.forceStopPackage(TEST_APP2);
        }
    }

    private void sendCommand(int command, String sourcePkg, String targetPkg, Bundle extras) {
        final Intent intent = new Intent();
        intent.setClassName(sourcePkg, TEST_CLASS);
        intent.putExtra(EXTRA_COMMAND, command);
        intent.putExtra(EXTRA_TARGET_PACKAGE, targetPkg);
        if (extras != null) {
            intent.putExtras(extras);
        }
        mContext.startService(intent);
    }

    private boolean isFreezerEnabled() throws Exception {
        final String output = runShellCommand("dumpsys activity settings");
        final Matcher matcher = Pattern.compile("\\b" + CachedAppOptimizer.KEY_USE_FREEZER
                + "\\b=\\b(true|false)\\b").matcher(output);
        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }
        return false;
    }

    private boolean isAppFrozen(String packageName) throws Exception {
        final String output = runShellCommand("dumpsys activity p " + packageName);
        final Matcher matcher = Pattern.compile("\\b" + ProcessCachedOptimizerRecord.IS_FROZEN
                + "\\b=\\b(true|false)\\b").matcher(output);
        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }
        return false;
    }

    @LargeTest
    @Test
    public void testKillAppIfBgRestrictedCachedIdle() throws Exception {
        final long shortTimeoutMs = 5_000;
        final long backgroundSettleMs = 10_000;
        final PackageManager pm = mContext.getPackageManager();
        final int uid = pm.getPackageUid(TEST_APP1, 0);
        final MyUidImportanceListener uidListener1 = new MyUidImportanceListener(uid);
        final MyUidImportanceListener uidListener2 = new MyUidImportanceListener(uid);
        final MyUidImportanceListener uidListener3 = new MyUidImportanceListener(uid);
        SettingsSession<String> amConstantsSettings = null;
        DeviceConfigSession<Boolean> killBgRestrictedAndCachedIdle = null;
        DeviceConfigSession<Long> killBgRestrictedAndCachedIdleSettleTime = null;
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final CountDownLatch[] latchHolder = new CountDownLatch[1];
        final H handler = new H(Looper.getMainLooper(), latchHolder);
        final Messenger messenger = new Messenger(handler);
        try {
            am.addOnUidImportanceListener(uidListener1,
                    RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
            am.addOnUidImportanceListener(uidListener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            am.addOnUidImportanceListener(uidListener3, RunningAppProcessInfo.IMPORTANCE_CACHED);
            toggleScreenOn(true);

            killBgRestrictedAndCachedIdle = new DeviceConfigSession<>(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ActivityManagerConstants.KEY_KILL_BG_RESTRICTED_CACHED_IDLE,
                    DeviceConfig::getBoolean,
                    ActivityManagerConstants.DEFAULT_KILL_BG_RESTRICTED_CACHED_IDLE);
            killBgRestrictedAndCachedIdle.set(true);
            killBgRestrictedAndCachedIdleSettleTime = new DeviceConfigSession<>(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ActivityManagerConstants.KEY_KILL_BG_RESTRICTED_CACHED_IDLE_SETTLE_TIME,
                    DeviceConfig::getLong,
                    ActivityManagerConstants.DEFAULT_KILL_BG_RESTRICTED_CACHED_IDLE_SETTLE_TIME_MS);
            killBgRestrictedAndCachedIdleSettleTime.set(backgroundSettleMs);
            amConstantsSettings = new SettingsSession<>(
                Settings.Global.getUriFor(Settings.Global.ACTIVITY_MANAGER_CONSTANTS),
                Settings.Global::getString, Settings.Global::putString);
            final KeyValueListParser parser = new KeyValueListParser(',');
            long currentBackgroundSettleMs =
                    ActivityManagerConstants.DEFAULT_BACKGROUND_SETTLE_TIME;
            try {
                parser.setString(amConstantsSettings.get());
                currentBackgroundSettleMs = parser.getLong(
                        ActivityManagerConstants.KEY_BACKGROUND_SETTLE_TIME,
                        ActivityManagerConstants.DEFAULT_BACKGROUND_SETTLE_TIME);
            } catch (IllegalArgumentException e) {
            }
            // Drain queue to make sure the existing UID_IDLE_MSG has been processed.
            Thread.sleep(currentBackgroundSettleMs);
            amConstantsSettings.set(
                    ActivityManagerConstants.KEY_BACKGROUND_SETTLE_TIME + "=" + backgroundSettleMs);

            runShellCommand("cmd appops set " + TEST_APP1 + " RUN_ANY_IN_BACKGROUND allow");

            final Intent intent = new Intent(ACTION_FGS_STATS_TEST);
            final ComponentName cn = ComponentName.unflattenFromString(
                    TEST_APP1 + "/" + TEST_FGS_CLASS);
            final Bundle bundle = new Bundle();
            intent.setComponent(cn);
            bundle.putBinder(EXTRA_MESSENGER, messenger.getBinder());
            intent.putExtras(bundle);

            // Start the FGS.
            latchHolder[0] = new CountDownLatch(1);
            mContext.startForegroundService(intent);
            assertTrue("Timed out to start fg service", uidListener1.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE, shortTimeoutMs));
            assertTrue("Timed out to get the remote messenger", latchHolder[0].await(
                    shortTimeoutMs, TimeUnit.MILLISECONDS));
            assertFalse("FGS shouldn't be killed", uidListener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, backgroundSettleMs + shortTimeoutMs));

            // Stop the FGS, it shouldn't be killed because it's not in FAS state.
            latchHolder[0] = new CountDownLatch(1);
            handler.sendRemoteMessage(H.MSG_STOP_SERVICE, 0, 0, null);
            assertTrue("Timed out to wait for stop fg", latchHolder[0].await(
                    shortTimeoutMs, TimeUnit.MILLISECONDS));
            assertFalse("FGS shouldn't be killed", uidListener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, backgroundSettleMs + shortTimeoutMs));

            // Set the FAS state.
            runShellCommand("cmd appops set " + TEST_APP1 + " RUN_ANY_IN_BACKGROUND deny");
            // Now it should've been killed.
            assertTrue("Should have been killed", uidListener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, backgroundSettleMs + shortTimeoutMs));

            // Start the FGS.
            // Temporarily allow RUN_ANY_IN_BACKGROUND to start FGS.
            runShellCommand("cmd appops set " + TEST_APP1 + " RUN_ANY_IN_BACKGROUND allow");
            latchHolder[0] = new CountDownLatch(1);
            mContext.startForegroundService(intent);
            assertTrue("Timed out to start fg service", uidListener1.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE, shortTimeoutMs));
            assertTrue("Timed out to get the remote messenger", latchHolder[0].await(
                    shortTimeoutMs, TimeUnit.MILLISECONDS));
            runShellCommand("cmd appops set " + TEST_APP1 + " RUN_ANY_IN_BACKGROUND deny");
            // It shouldn't be killed since it's not cached.
            assertFalse("FGS shouldn't be killed", uidListener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, backgroundSettleMs + shortTimeoutMs));

            // Stop the FGS, it should get killed because it's cached & uid idle & in FAS state.
            latchHolder[0] = new CountDownLatch(1);
            handler.sendRemoteMessage(H.MSG_STOP_SERVICE, 0, 0, null);
            assertTrue("Timed out to wait for stop fg", latchHolder[0].await(
                    shortTimeoutMs, TimeUnit.MILLISECONDS));
            assertTrue("Should have been killed", uidListener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, backgroundSettleMs + shortTimeoutMs));

            // Start the FGS.
            // Temporarily allow RUN_ANY_IN_BACKGROUND to start FGS.
            runShellCommand("cmd appops set " + TEST_APP1 + " RUN_ANY_IN_BACKGROUND allow");
            latchHolder[0] = new CountDownLatch(1);
            mContext.startForegroundService(intent);
            assertTrue("Timed out to start fg service", uidListener1.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE, shortTimeoutMs));
            assertTrue("Timed out to get the remote messenger", latchHolder[0].await(
                    shortTimeoutMs, TimeUnit.MILLISECONDS));
            runShellCommand("cmd appops set " + TEST_APP1 + " RUN_ANY_IN_BACKGROUND deny");
            // It shouldn't be killed since it's not cached.
            assertFalse("FGS shouldn't be killed", uidListener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, backgroundSettleMs + shortTimeoutMs));

            // Stop the FGS.
            latchHolder[0] = new CountDownLatch(1);
            handler.sendRemoteMessage(H.MSG_STOP_SERVICE, 0, 0, null);
            assertTrue("Timed out to wait for stop fg", latchHolder[0].await(
                    shortTimeoutMs, TimeUnit.MILLISECONDS));
            assertTrue("Should have been in cached state", uidListener3.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_CACHED, shortTimeoutMs));
            final long now = SystemClock.uptimeMillis();
            // Sleep a while to let the UID idle ticking.
            Thread.sleep(shortTimeoutMs);
            // Now send a broadcast, it should bring the app out of cached for a while.
            final Intent intent2 = new Intent(ACTION_RECEIVER_TEST);
            final Bundle extras = new Bundle();
            final IRemoteCallback callback = new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle data) throws RemoteException {
                    latchHolder[0].countDown();
                }
            };
            extras.putBinder(EXTRA_CALLBACK, callback.asBinder());
            intent2.putExtras(extras);
            intent2.setPackage(TEST_APP1);
            latchHolder[0] = new CountDownLatch(1);
            mContext.sendBroadcast(intent2);
            assertTrue("Timed out to wait for receiving broadcast", latchHolder[0].await(
                    shortTimeoutMs, TimeUnit.MILLISECONDS));
            // Try to wait for the killing, it should be killed after backgroundSettleMs
            // since receiving the broadcast
            assertFalse("Shouldn't be killed now", uidListener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE,
                    backgroundSettleMs + now - SystemClock.uptimeMillis() + 2_000));
            // Now wait a bit longer, it should be killed.
            assertTrue("Should have been killed", uidListener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, backgroundSettleMs + shortTimeoutMs));

            // Disable this FAS cached idle kill feature.
            killBgRestrictedAndCachedIdle.set(false);

            // Start the FGS.
            // Temporarily allow RUN_ANY_IN_BACKGROUND to start FGS.
            runShellCommand("cmd appops set " + TEST_APP1 + " RUN_ANY_IN_BACKGROUND allow");
            latchHolder[0] = new CountDownLatch(1);
            mContext.startForegroundService(intent);
            assertTrue("Timed out to start fg service", uidListener1.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE, shortTimeoutMs));
            assertTrue("Timed out to get the remote messenger", latchHolder[0].await(
                    shortTimeoutMs, TimeUnit.MILLISECONDS));
            runShellCommand("cmd appops set " + TEST_APP1 + " RUN_ANY_IN_BACKGROUND deny");
            assertFalse("FGS shouldn't be killed", uidListener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, backgroundSettleMs + shortTimeoutMs));

            // Stop the FGS, it shouldn't be killed because the feature has been turned off.
            latchHolder[0] = new CountDownLatch(1);
            handler.sendRemoteMessage(H.MSG_STOP_SERVICE, 0, 0, null);
            assertTrue("Timed out to wait for stop fg", latchHolder[0].await(
                    shortTimeoutMs, TimeUnit.MILLISECONDS));
            assertFalse("FGS shouldn't be killed", uidListener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, backgroundSettleMs + shortTimeoutMs));
        } finally {
            runShellCommand("cmd appops set " + TEST_APP1 + " RUN_ANY_IN_BACKGROUND default");
            if (amConstantsSettings != null) {
                amConstantsSettings.close();
            }
            if (killBgRestrictedAndCachedIdle != null) {
                killBgRestrictedAndCachedIdle.close();
            }
            if (killBgRestrictedAndCachedIdleSettleTime != null) {
                killBgRestrictedAndCachedIdleSettleTime.close();
            }
            am.removeOnUidImportanceListener(uidListener1);
            am.removeOnUidImportanceListener(uidListener2);
            am.removeOnUidImportanceListener(uidListener3);
            am.forceStopPackage(TEST_APP1);
        }
    }

    @Ignore("Need to disable calling uid check in ActivityManagerService.killPids before this test")
    @Test
    public void testKillPids() throws Exception {
        final long timeout = 5000;
        final long shortTimeout = 2000;
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final PackageManager pm = mContext.getPackageManager();
        final int uid1 = pm.getPackageUid(TEST_APP1, 0);
        final int uid2 = pm.getPackageUid(TEST_APP2, 0);
        final int uid3 = pm.getPackageUid(TEST_APP3, 0);
        final MyUidImportanceListener uid1Listener1 = new MyUidImportanceListener(uid1);
        final MyUidImportanceListener uid1Listener2 = new MyUidImportanceListener(uid1);
        final MyUidImportanceListener uid2Listener1 = new MyUidImportanceListener(uid2);
        final MyUidImportanceListener uid2Listener2 = new MyUidImportanceListener(uid2);
        final MyUidImportanceListener uid3Listener1 = new MyUidImportanceListener(uid3);
        final MyUidImportanceListener uid3Listener2 = new MyUidImportanceListener(uid3);
        try {
            am.addOnUidImportanceListener(uid1Listener1, RunningAppProcessInfo.IMPORTANCE_SERVICE);
            am.addOnUidImportanceListener(uid1Listener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            am.addOnUidImportanceListener(uid2Listener1, RunningAppProcessInfo.IMPORTANCE_SERVICE);
            am.addOnUidImportanceListener(uid2Listener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            am.addOnUidImportanceListener(uid3Listener1, RunningAppProcessInfo.IMPORTANCE_SERVICE);
            am.addOnUidImportanceListener(uid3Listener2, RunningAppProcessInfo.IMPORTANCE_GONE);
            runShellCommand("cmd deviceidle whitelist +" + TEST_APP1);
            runShellCommand("cmd deviceidle whitelist +" + TEST_APP2);
            runShellCommand("cmd deviceidle whitelist +" + TEST_APP3);
            final int[] pids = new int[3];
            // Test sync kills
            pids[0] = startTargetService(am, TEST_APP1, TEST_CLASS, uid1, TEST_APP1,
                    uid1Listener1, timeout);
            pids[1] = startTargetService(am, TEST_APP2, TEST_CLASS, uid2, TEST_APP2,
                    uid2Listener1, timeout);
            pids[2] = startTargetService(am, TEST_APP3, TEST_CLASS, uid3, TEST_APP3,
                    uid3Listener1, timeout);
            Thread.sleep(shortTimeout);
            mService.killPids(pids, "testKillPids", false);
            assertTrue("Timed out to kill process", uid1Listener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, timeout));
            assertTrue("Timed out to kill process", uid2Listener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, timeout));
            assertTrue("Timed out to kill process", uid3Listener2.waitFor(
                    RunningAppProcessInfo.IMPORTANCE_GONE, timeout));
        } finally {
            runShellCommand("cmd deviceidle whitelist -" + TEST_APP1);
            runShellCommand("cmd deviceidle whitelist -" + TEST_APP2);
            runShellCommand("cmd deviceidle whitelist -" + TEST_APP3);
            am.removeOnUidImportanceListener(uid1Listener1);
            am.removeOnUidImportanceListener(uid1Listener2);
            am.removeOnUidImportanceListener(uid2Listener1);
            am.removeOnUidImportanceListener(uid2Listener2);
            am.removeOnUidImportanceListener(uid3Listener1);
            am.removeOnUidImportanceListener(uid3Listener2);
            am.forceStopPackage(TEST_APP1);
            am.forceStopPackage(TEST_APP2);
            am.forceStopPackage(TEST_APP3);
        }
    }

    private int startTargetService(ActivityManager am, String targetPakage, String targetService,
            int targetUid, String targetProcessName, MyUidImportanceListener uidListener,
            long timeout) throws Exception {
        final Intent intent = new Intent();
        intent.setComponent(ComponentName.unflattenFromString(targetPakage + "/" + targetService));
        mContext.startService(intent);
        assertTrue("Timed out to start service", uidListener.waitFor(
                RunningAppProcessInfo.IMPORTANCE_SERVICE, timeout));
        final List<RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        for (int i = processes.size() - 1; i >= 0; i--) {
            final RunningAppProcessInfo info = processes.get(i);
            if (info.uid == targetUid && targetProcessName.equals(info.processName)) {
                return info.pid;
            }
        }
        return -1;
    }

    @Test
    public void testGetIsolatedProcesses() throws Exception {
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final PackageManager pm = mContext.getPackageManager();
        final int uid1 = pm.getPackageUid(TEST_APP1, 0);
        final int uid2 = pm.getPackageUid(TEST_APP2, 0);
        final int uid3 = pm.getPackageUid(TEST_APP3, 0);
        final List<Pair<Integer, ServiceConnection>> uid1Processes = new ArrayList<>();
        final List<Pair<Integer, ServiceConnection>> uid2Processes = new ArrayList<>();
        try {
            assertTrue("There shouldn't be any isolated process for " + TEST_APP1,
                    getIsolatedProcesses(uid1).isEmpty());
            assertTrue("There shouldn't be any isolated process for " + TEST_APP2,
                    getIsolatedProcesses(uid2).isEmpty());
            assertTrue("There shouldn't be any isolated process for " + TEST_APP3,
                    getIsolatedProcesses(uid3).isEmpty());

            // Verify uid1
            uid1Processes.add(createIsolatedProcessAndVerify(TEST_APP1, uid1));
            uid1Processes.add(createIsolatedProcessAndVerify(TEST_APP1, uid1));
            uid1Processes.add(createIsolatedProcessAndVerify(TEST_APP1, uid1));
            verifyIsolatedProcesses(uid1Processes, getIsolatedProcesses(uid1));

            // Let one of the processes go
            final Pair<Integer, ServiceConnection> uid1P2 = uid1Processes.remove(2);
            mContext.unbindService(uid1P2.second);
            Thread.sleep(5_000); // Wait for the process gone.
            verifyIsolatedProcesses(uid1Processes, getIsolatedProcesses(uid1));

            // Verify uid2
            uid2Processes.add(createIsolatedProcessAndVerify(TEST_APP2, uid2));
            verifyIsolatedProcesses(uid2Processes, getIsolatedProcesses(uid2));

            // Verify uid1 again
            verifyIsolatedProcesses(uid1Processes, getIsolatedProcesses(uid1));

            // Verify uid3
            assertTrue("There shouldn't be any isolated process for " + TEST_APP3,
                    getIsolatedProcesses(uid3).isEmpty());
        } finally {
            for (Pair<Integer, ServiceConnection> p: uid1Processes) {
                mContext.unbindService(p.second);
            }
            for (Pair<Integer, ServiceConnection> p: uid2Processes) {
                mContext.unbindService(p.second);
            }
            am.forceStopPackage(TEST_APP1);
            am.forceStopPackage(TEST_APP2);
            am.forceStopPackage(TEST_APP3);
        }
    }

    private static List<Integer> getIsolatedProcesses(int uid) throws Exception {
        final String output = runShellCommand("am get-isolated-pids " + uid);
        final Matcher matcher = Pattern.compile("(\\d+)").matcher(output);
        final List<Integer> pids = new ArrayList<>();
        while (matcher.find()) {
            pids.add(Integer.parseInt(output.substring(matcher.start(), matcher.end())));
        }
        return pids;
    }

    private void verifyIsolatedProcesses(List<Pair<Integer, ServiceConnection>> processes,
            List<Integer> pids) {
        final List<Integer> l = processes.stream().map(p -> p.first).collect(Collectors.toList());
        assertTrue("Isolated processes don't match", l.containsAll(pids));
        assertTrue("Isolated processes don't match", pids.containsAll(l));
    }

    private Pair<Integer, ServiceConnection> createIsolatedProcessAndVerify(String pkgName, int uid)
            throws Exception {
        final Pair<Integer, ServiceConnection> p = createIsolatedProcess(pkgName);
        final List<Integer> pids = getIsolatedProcesses(uid);
        assertTrue("Can't find the isolated pid " + p.first + " for " + pkgName,
                pids.contains(p.first));
        return p;
    }

    private Pair<Integer, ServiceConnection> createIsolatedProcess(String pkgName)
            throws Exception {
        final int[] pid = new int[1];
        final CountDownLatch[] latch = new CountDownLatch[1];
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final IRemoteCallback s = IRemoteCallback.Stub.asInterface(service);
                final IBinder callback = new Binder() {
                    @Override
                    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                            throws RemoteException {
                        if (code == Binder.FIRST_CALL_TRANSACTION) {
                            pid[0] = data.readInt();
                            latch[0].countDown();
                            return true;
                        }
                        return super.onTransact(code, data, reply, flags);
                    }
                };
                try {
                    final Bundle extra = new Bundle();
                    extra.putBinder(EXTRA_CALLBACK, callback);
                    s.sendResult(extra);
                } catch (RemoteException e) {
                    fail("Unable to call into isolated process");
                }
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        final Intent intent = new Intent();
        intent.setClassName(pkgName, TEST_ISOLATED_CLASS);
        latch[0] = new CountDownLatch(1);
        assertTrue("Unable to create isolated process in " + pkgName,
                mContext.bindIsolatedService(intent, Context.BIND_AUTO_CREATE,
                Long.toString(SystemClock.uptimeMillis()), mContext.getMainExecutor(), conn));
        assertTrue("Timeout to bind to service " + intent.getComponent(),
                latch[0].await(AWAIT_TIMEOUT, TimeUnit.MILLISECONDS));
        return Pair.create(pid[0], conn);
    }

    /**
     * Make sure the screen state.
     */
    private void toggleScreenOn(final boolean screenon) throws Exception {
        if (screenon) {
            runShellCommand("input keyevent KEYCODE_WAKEUP");
            runShellCommand("wm dismiss-keyguard");
        } else {
            runShellCommand("input keyevent KEYCODE_SLEEP");
        }
        // Since the screen on/off intent is ordered, they will not be sent right now.
        Thread.sleep(2_000);
    }

    private class H extends Handler {
        static final int MSG_INIT = 0;
        static final int MSG_DONE = 1;
        static final int MSG_START_FOREGROUND = 2;
        static final int MSG_STOP_FOREGROUND = 3;
        static final int MSG_STOP_SERVICE = 4;

        private Messenger mRemoteMessenger;
        private CountDownLatch[] mLatchHolder;

        H(Looper looper, CountDownLatch[] latchHolder) {
            super(looper);
            mLatchHolder = latchHolder;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INIT:
                    mRemoteMessenger = (Messenger) msg.obj;
                    mLatchHolder[0].countDown();
                    break;
                case MSG_DONE:
                    mLatchHolder[0].countDown();
                    break;
            }
        }

        void sendRemoteMessage(int what, int arg1, int arg2, Object obj) {
            Message msg = Message.obtain();
            msg.what = what;
            msg.arg1 = arg1;
            msg.arg2 = arg2;
            msg.obj = obj;
            try {
                mRemoteMessenger.send(msg);
            } catch (RemoteException e) {
            }
            msg.recycle();
        }
    }

    private static class MyUidImportanceListener implements OnUidImportanceListener {
        final CountDownLatch[] mLatchHolder = new CountDownLatch[1];
        private final int mExpectedUid;
        private int mExpectedImportance;
        private int mCurrentImportance = RunningAppProcessInfo.IMPORTANCE_GONE;

        MyUidImportanceListener(int uid) {
            mExpectedUid = uid;
        }

        @Override
        public void onUidImportance(int uid, int importance) {
            if (uid == mExpectedUid) {
                synchronized (this) {
                    if (importance == mExpectedImportance && mLatchHolder[0] != null) {
                        mLatchHolder[0].countDown();
                    }
                    mCurrentImportance = importance;
                }
                Log.i(TAG, "uid " + uid + " importance: " + importance);
            }
        }

        boolean waitFor(int expectedImportance, long timeout) throws Exception {
            synchronized (this) {
                mExpectedImportance = expectedImportance;
                if (mCurrentImportance == expectedImportance) {
                    return true;
                }
                mLatchHolder[0] = new CountDownLatch(1);
            }
            return mLatchHolder[0].await(timeout, TimeUnit.MILLISECONDS);
        }
    }
}
