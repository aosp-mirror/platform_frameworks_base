/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.content.Context;
import android.content.Intent;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.bdr_helper_app.TestCommsReceiver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests functionality of {@link android.os.IBinder.DeathRecipient} callbacks.
 */
@RunWith(AndroidJUnit4.class)
public class BinderDeathRecipientTest {
    private static final String TAG = BinderDeathRecipientTest.class.getSimpleName();
    private static final String TEST_PACKAGE_NAME_1 =
            "com.android.frameworks.coretests.bdr_helper_app1";
    private static final String TEST_PACKAGE_NAME_2 =
            "com.android.frameworks.coretests.bdr_helper_app2";

    private Context mContext;
    private Handler mHandler;
    private ActivityManager mActivityManager;
    private Set<Pair<IBinder, IBinder.DeathRecipient>> mLinkedDeathRecipients = new ArraySet<>();

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mHandler = new Handler(Looper.getMainLooper());
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

    @Test
    public void binderDied_noArgs() throws Exception {
        final IBinder testAppBinder = getNewRemoteBinder(TEST_PACKAGE_NAME_1);
        final CountDownLatch deathNotificationLatch = new CountDownLatch(1);
        final IBinder.DeathRecipient simpleDeathRecipient =
                () -> deathNotificationLatch.countDown();
        testAppBinder.linkToDeath(simpleDeathRecipient, 0);
        mLinkedDeathRecipients.add(Pair.create(testAppBinder, simpleDeathRecipient));

        mActivityManager.forceStopPackage(TEST_PACKAGE_NAME_1);
        assertTrue("Death notification did not arrive",
                deathNotificationLatch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void binderDied_iBinderArg() throws Exception {
        final IBinder testApp1Binder = getNewRemoteBinder(TEST_PACKAGE_NAME_1);
        final IBinder testApp2Binder = getNewRemoteBinder(TEST_PACKAGE_NAME_2);
        final CyclicBarrier barrier = new CyclicBarrier(2);

        final AtomicReference<IBinder> binderThatDied = new AtomicReference<>();
        final IBinder.DeathRecipient sameDeathRecipient = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                Log.e(TAG, "Should not have been called!");
            }

            @Override
            public void binderDied(IBinder who) {
                binderThatDied.set(who);
                try {
                    barrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    Log.e(TAG, "Unexpected exception while waiting on CyclicBarrier", e);
                }
            }
        };
        testApp1Binder.linkToDeath(sameDeathRecipient, 0);
        mLinkedDeathRecipients.add(Pair.create(testApp1Binder, sameDeathRecipient));

        testApp2Binder.linkToDeath(sameDeathRecipient, 0);
        mLinkedDeathRecipients.add(Pair.create(testApp2Binder, sameDeathRecipient));

        mActivityManager.forceStopPackage(TEST_PACKAGE_NAME_1);
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail("Timed out while waiting for 1st death notification: " + e.getMessage());
        }
        assertEquals("Different binder received", testApp1Binder, binderThatDied.get());

        barrier.reset();
        mActivityManager.forceStopPackage(TEST_PACKAGE_NAME_2);
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail("Timed out while waiting for 2nd death notification: " + e.getMessage());
        }
        assertEquals("Different binder received", testApp2Binder, binderThatDied.get());
    }

    @After
    public void tearDown() {
        for (Pair<IBinder, IBinder.DeathRecipient> linkedPair : mLinkedDeathRecipients) {
            linkedPair.first.unlinkToDeath(linkedPair.second, 0);
        }
    }
}
