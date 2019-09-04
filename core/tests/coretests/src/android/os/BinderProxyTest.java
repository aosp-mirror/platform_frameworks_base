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

import android.annotation.Nullable;
import android.content.Context;
import android.test.AndroidTestCase;

import androidx.test.filters.MediumTest;

public class BinderProxyTest extends AndroidTestCase {
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

    private PowerManager mPowerManager;

    /**
     * Setup any common data for the upcoming tests.
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
    }

    @MediumTest
    public void testNoListener() throws Exception {
        CountingListener listener = new CountingListener();
        Binder.setProxyTransactListener(listener);
        Binder.setProxyTransactListener(null);

        mPowerManager.isInteractive();

        assertEquals(0, listener.mStartedCount);
        assertEquals(0, listener.mEndedCount);
    }

    @MediumTest
    public void testListener() throws Exception {
        CountingListener listener = new CountingListener();
        Binder.setProxyTransactListener(listener);

        mPowerManager.isInteractive();

        assertEquals(1, listener.mStartedCount);
        assertEquals(1, listener.mEndedCount);
    }

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
}
