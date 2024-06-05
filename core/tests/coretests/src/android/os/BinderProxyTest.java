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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = PowerManager.class)
public class BinderProxyTest {
    private static class CountingListener implements Binder.ProxyTransactListener {
        int mStartedCount;
        int mEndedCount;

        public Object onTransactStarted(IBinder binder, int transactionCode) {
            mStartedCount++;
            return null;
        }

        public void onTransactEnded(@Nullable Object session) {
            mEndedCount++;
        }
    };

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private Context mContext;
    private PowerManager mPowerManager;

    /**
     * Setup any common data for the upcoming tests.
     */
    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
    }

    @Test
    @MediumTest
    public void testNoListener() throws Exception {
        CountingListener listener = new CountingListener();
        Binder.setProxyTransactListener(listener);
        Binder.setProxyTransactListener(null);

        mPowerManager.isInteractive();

        assertEquals(0, listener.mStartedCount);
        assertEquals(0, listener.mEndedCount);
    }

    @Test
    @MediumTest
    public void testListener() throws Exception {
        CountingListener listener = new CountingListener();
        Binder.setProxyTransactListener(listener);

        mPowerManager.isInteractive();

        assertEquals(1, listener.mStartedCount);
        assertEquals(1, listener.mEndedCount);
    }

    @Test
    @MediumTest
    public void testSessionPropagated() throws Exception {
        Binder.setProxyTransactListener(new Binder.ProxyTransactListener() {
            public Object onTransactStarted(IBinder binder, int transactionCode) {
                return "foo";
            }

            public void onTransactEnded(@Nullable Object session) {
                assertEquals("foo", session);
            }
        });

        // Check it does not throw..
        mPowerManager.isInteractive();
    }

    private IBinder mRemoteBinder = null;

    @Test
    @MediumTest
    public void testGetExtension() throws Exception {
        final CountDownLatch bindLatch = new CountDownLatch(1);
        ServiceConnection connection =
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        mRemoteBinder = service;
                        bindLatch.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {}
                };
        try {
            mContext.bindService(
                    new Intent(mContext, BinderProxyService.class),
                    connection,
                    Context.BIND_AUTO_CREATE);
            if (!bindLatch.await(500, TimeUnit.MILLISECONDS)) {
                fail(
                        "Timed out while binding service: "
                                + BinderProxyService.class.getSimpleName());
            }
            assertTrue(mRemoteBinder instanceof BinderProxy);
            assertNotNull(mRemoteBinder);

            IBinder extension = mRemoteBinder.getExtension();
            assertNotNull(extension);
            assertTrue(extension.pingBinder());
        } finally {
            mContext.unbindService(connection);
        }
    }
}
