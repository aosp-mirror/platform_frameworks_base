/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.util;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.reset;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.util.test.BroadcastInterceptingContext;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class VersionedBroadcastListenerTest {
    private static final String TAG = VersionedBroadcastListenerTest.class.getSimpleName();
    private static final String ACTION_TEST = "action.test.happy.broadcasts";

    @Mock private Context mContext;
    private BroadcastInterceptingContext mServiceContext;
    private Handler mHandler;
    private VersionedBroadcastListener mListener;
    private int mCallbackCount;

    private void doCallback() { mCallbackCount++; }

    private class MockContext extends BroadcastInterceptingContext {
        MockContext(Context base) {
            super(base);
        }
    }

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
    }

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        reset(mContext);
        mServiceContext = new MockContext(mContext);
        mHandler = new Handler(Looper.myLooper());
        mCallbackCount = 0;
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TEST);
        mListener = new VersionedBroadcastListener(
                TAG, mServiceContext, mHandler, filter, (Intent intent) -> doCallback());
    }

    @After public void tearDown() throws Exception {
        if (mListener != null) {
            mListener.stopListening();
            mListener = null;
        }
    }

    private void sendBroadcast() {
        final Intent intent = new Intent(ACTION_TEST);
        mServiceContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Test
    public void testBasicListening() {
        assertEquals(0, mCallbackCount);
        mListener.startListening();
        for (int i = 0; i < 5; i++) {
            sendBroadcast();
            assertEquals(i+1, mCallbackCount);
        }
        mListener.stopListening();
    }

    @Test
    public void testBroadcastsBeforeStartAreIgnored() {
        assertEquals(0, mCallbackCount);
        for (int i = 0; i < 5; i++) {
            sendBroadcast();
            assertEquals(0, mCallbackCount);
        }

        mListener.startListening();
        sendBroadcast();
        assertEquals(1, mCallbackCount);
    }

    @Test
    public void testBroadcastsAfterStopAreIgnored() {
        mListener.startListening();
        sendBroadcast();
        assertEquals(1, mCallbackCount);
        mListener.stopListening();

        for (int i = 0; i < 5; i++) {
            sendBroadcast();
            assertEquals(1, mCallbackCount);
        }
    }
}
