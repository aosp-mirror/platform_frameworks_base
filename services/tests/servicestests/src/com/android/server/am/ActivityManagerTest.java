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
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.server.wm.settings.SettingsSession;
import android.support.test.uiautomator.UiDevice;
import android.test.suitebuilder.annotation.LargeTest;
import android.text.TextUtils;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final String TEST_APP = "com.android.servicestests.apps.simpleservicetestapp1";
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
            uid = pm.getPackageUid(TEST_APP, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        Intent intent = new Intent();
        intent.setClassName(TEST_APP, TEST_CLASS);

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
        intent.setClassName(TEST_APP, TEST_CLASS);
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
        int uid = pm.getPackageUid(TEST_APP, 0);
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
            runShellCommand("cmd deviceidle whitelist +" + TEST_APP);
            toggleScreenOn(true);

            final Intent intent = new Intent(ACTION_FGS_STATS_TEST);
            final ComponentName cn = ComponentName.unflattenFromString(
                    TEST_APP + "/" + TEST_FGS_CLASS);
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
            runShellCommand("cmd deviceidle whitelist -" + TEST_APP);
            am.removeOnUidImportanceListener(uidListener1);
            am.removeOnUidImportanceListener(uidListener2);
            am.forceStopPackage(TEST_APP);
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
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    CachedAppOptimizer.KEY_FREEZER_DEBOUNCE_TIMEOUT,
                    DeviceConfig::getLong, CachedAppOptimizer.DEFAULT_FREEZER_DEBOUNCE_TIMEOUT);
            freezerDebounceTimeout.set(waitFor);

            final String activityManagerConstants = Settings.Global.ACTIVITY_MANAGER_CONSTANTS;
            amConstantsSettings = new SettingsSession<>(
                Settings.Global.getUriFor(activityManagerConstants),
                Settings.Global::getString, Settings.Global::putString);

            amConstantsSettings.set(
                    ActivityManagerConstants.KEY_MAX_SERVICE_INACTIVITY + "=" + waitFor);

            final Intent intent = new Intent();
            intent.setClassName(TEST_APP, TEST_CLASS);

            final CountDownLatch latch = new CountDownLatch(1);
            autoConnection = new MyServiceConnection(latch);
            mContext.bindService(intent, autoConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_OOM_MANAGEMENT);
            try {
                assertTrue("Timeout to bind to service " + intent.getComponent(),
                        latch.await(AWAIT_TIMEOUT, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                fail("Unable to bind to service " + intent.getComponent());
            }
            assertFalse(TEST_APP + " shouldn't be frozen now.", isAppFrozen(TEST_APP));

            // Trigger oomAdjUpdate/
            toggleScreenOn(false);
            toggleScreenOn(true);

            // Wait for the freezer kick in if there is any.
            Thread.sleep(waitFor * 4);

            // It still shouldn't be frozen, although it's been in cached state.
            assertFalse(TEST_APP + " shouldn't be frozen now.", isAppFrozen(TEST_APP));
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
        }
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
