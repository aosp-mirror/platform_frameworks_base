/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import com.android.frameworks.coretests.aidl.IBfsccTestAppCmdService;
import com.android.frameworks.coretests.bdr_helper_app.TestCommsReceiver;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests functionality of {@link android.os.IBinder.FrozenStateChangeCallback}.
 */
@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = ActivityManager.class)
public class BinderFrozenStateChangeNotificationTest {
    private static final String TAG = BinderFrozenStateChangeNotificationTest.class.getSimpleName();

    private static final String TEST_PACKAGE_NAME_1 =
            "com.android.frameworks.coretests.bfscctestapp";
    private static final String TEST_PACKAGE_NAME_2 =
            "com.android.frameworks.coretests.bdr_helper_app1";
    private static final String TEST_APP_CMD_SERVICE =
            TEST_PACKAGE_NAME_1 + ".BfsccTestAppCmdService";

    private static final int CALLBACK_WAIT_TIMEOUT_SECS = 5;

    private IBfsccTestAppCmdService mBfsccTestAppCmdService;
    private ServiceConnection mTestAppConnection;
    private Context mContext;
    private Handler mHandler;

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mHandler = new Handler(Looper.getMainLooper());
        ((ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE)).killUid(
                mContext.getPackageManager().getPackageUid(TEST_PACKAGE_NAME_1, 0),
                "Wiping Test Package");
        mTestAppConnection = bindService();
    }

    private IBinder getNewRemoteBinder(String testPackage) throws InterruptedException {
        final CountDownLatch resultLatch = new CountDownLatch(1);
        final AtomicInteger resultCode = new AtomicInteger(Activity.RESULT_CANCELED);
        final AtomicReference<Bundle> resultExtras = new AtomicReference<>();

        final Intent intent = new Intent(TestCommsReceiver.ACTION_GET_BINDER)
                .setClassName(testPackage, TestCommsReceiver.class.getName());
        mContext.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                resultCode.set(getResultCode());
                resultExtras.set(getResultExtras(true));
                resultLatch.countDown();
            }
        }, mHandler, Activity.RESULT_CANCELED, null, null);

        assertTrue("Request for binder timed out", resultLatch.await(5, TimeUnit.SECONDS));
        assertEquals(Activity.RESULT_OK, resultCode.get());
        return resultExtras.get().getBinder(TestCommsReceiver.EXTRA_KEY_BINDER);
    }

    private ServiceConnection bindService()
            throws Exception {
        final CountDownLatch bindLatch = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "Service connected");
                mBfsccTestAppCmdService = IBfsccTestAppCmdService.Stub.asInterface(service);
                bindLatch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "Service disconnected");
            }
        };
        mContext.bindService(
                new Intent().setComponent(
                        new ComponentName(TEST_PACKAGE_NAME_1, TEST_APP_CMD_SERVICE)),
                connection,
                Context.BIND_AUTO_CREATE
                        | Context.BIND_NOT_FOREGROUND);
        if (!bindLatch.await(5, TimeUnit.SECONDS)) {
            fail("Timed out waiting for the service to bind");
        }
        return connection;
    }

    private void unbindService(ServiceConnection service) {
        if (service != null) {
            mContext.unbindService(service);
        }
    }

    @Test
    public void onStateChangeCalled() throws Exception {
        final LinkedBlockingQueue<Boolean> results = new LinkedBlockingQueue<>();
        if (createCallback(mBfsccTestAppCmdService.asBinder(), results) == null) {
            return;
        }
        ensureUnfrozenCallback(results);
        freezeApp1();
        ensureFrozenCallback(results);
        unfreezeApp1();
        ensureUnfrozenCallback(results);
    }

    @Test
    public void onStateChangeNotCalledAfterCallbackRemoved() throws Exception {
        final LinkedBlockingQueue<Boolean> results = new LinkedBlockingQueue<>();
        IBinder.FrozenStateChangeCallback callback;
        if ((callback = createCallback(mBfsccTestAppCmdService.asBinder(), results)) == null) {
            return;
        }
        ensureUnfrozenCallback(results);
        mBfsccTestAppCmdService.asBinder().removeFrozenStateChangeCallback(callback);
        freezeApp1();
        assertEquals("No more callbacks should be invoked.", 0, results.size());
    }

    @Test
    public void multipleCallbacks() throws Exception {
        final LinkedBlockingQueue<Boolean> results1 = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Boolean> results2 = new LinkedBlockingQueue<>();
        IBinder.FrozenStateChangeCallback callback1;
        if ((callback1 = createCallback(mBfsccTestAppCmdService.asBinder(), results1)) == null) {
            return;
        }
        ensureUnfrozenCallback(results1);
        freezeApp1();
        ensureFrozenCallback(results1);
        if (createCallback(mBfsccTestAppCmdService.asBinder(), results2) == null) {
            return;
        }
        ensureFrozenCallback(results2);

        unfreezeApp1();
        ensureUnfrozenCallback(results1);
        ensureUnfrozenCallback(results2);

        mBfsccTestAppCmdService.asBinder().removeFrozenStateChangeCallback(callback1);
        freezeApp1();
        assertEquals("No more callbacks should be invoked.", 0, results1.size());
        ensureFrozenCallback(results2);
    }

    @Test
    public void onStateChangeCalledWithTheRightBinder() throws Exception {
        final IBinder binder = mBfsccTestAppCmdService.asBinder();
        final LinkedBlockingQueue<IBinder> results = new LinkedBlockingQueue<>();
        IBinder.FrozenStateChangeCallback callback =
                (IBinder who, int state) -> results.offer(who);
        try {
            binder.addFrozenStateChangeCallback(callback);
        } catch (UnsupportedOperationException e) {
            return;
        }
        assertEquals("Callback received the wrong Binder object.",
                binder, results.poll(CALLBACK_WAIT_TIMEOUT_SECS, TimeUnit.SECONDS));
        freezeApp1();
        assertEquals("Callback received the wrong Binder object.",
                binder, results.poll(CALLBACK_WAIT_TIMEOUT_SECS, TimeUnit.SECONDS));
        unfreezeApp1();
        assertEquals("Callback received the wrong Binder object.",
                binder, results.poll(CALLBACK_WAIT_TIMEOUT_SECS, TimeUnit.SECONDS));
    }

    @After
    public void tearDown() {
        if (mTestAppConnection != null) {
            mContext.unbindService(mTestAppConnection);
        }
    }

    private IBinder.FrozenStateChangeCallback createCallback(IBinder binder, Queue<Boolean> queue)
            throws RemoteException {
        try {
            final IBinder.FrozenStateChangeCallback callback =
                    (IBinder who, int state) ->
                            queue.offer(state == IBinder.FrozenStateChangeCallback.STATE_FROZEN);
            binder.addFrozenStateChangeCallback(callback);
            return callback;
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    private void ensureFrozenCallback(LinkedBlockingQueue<Boolean> queue)
            throws InterruptedException {
        assertEquals(Boolean.TRUE, queue.poll(CALLBACK_WAIT_TIMEOUT_SECS, TimeUnit.SECONDS));
    }

    private void ensureUnfrozenCallback(LinkedBlockingQueue<Boolean> queue)
            throws InterruptedException {
        assertEquals(Boolean.FALSE, queue.poll(CALLBACK_WAIT_TIMEOUT_SECS, TimeUnit.SECONDS));
    }

    private String executeShellCommand(String cmd) throws Exception {
        return UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()).executeShellCommand(cmd);
    }

    private void freezeApp1() throws Exception {
        executeShellCommand("am freeze " + TEST_PACKAGE_NAME_1);
    }

    private void freezeApp2() throws Exception {
        executeShellCommand("am freeze " + TEST_PACKAGE_NAME_2);
    }

    private void unfreezeApp1() throws Exception {
        executeShellCommand("am unfreeze " + TEST_PACKAGE_NAME_1);
    }

    private void unfreezeApp2() throws Exception {
        executeShellCommand("am unfreeze " + TEST_PACKAGE_NAME_2);
    }
}
