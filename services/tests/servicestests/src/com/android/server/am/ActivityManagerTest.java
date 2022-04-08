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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.support.test.uiautomator.UiDevice;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    private static final String TEST_APP = "com.android.servicestests.apps.simpleservicetestapp";
    private static final String TEST_CLASS = TEST_APP + ".SimpleService";
    private static final int TEST_LOOPS = 100;
    private static final long AWAIT_TIMEOUT = 2000;
    private static final long CHECK_INTERVAL = 100;

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
}
