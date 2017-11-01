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

package com.android.server.connectivity.tethering;

import static com.android.internal.telephony.IccCardConstants.INTENT_VALUE_ICC_ABSENT;
import static com.android.internal.telephony.IccCardConstants.INTENT_VALUE_ICC_LOADED;
import static com.android.internal.telephony.IccCardConstants.INTENT_KEY_ICC_STATE;
import static com.android.internal.telephony.TelephonyIntents.ACTION_SIM_STATE_CHANGED;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.reset;

import android.content.Context;
import android.content.Intent;
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
public class SimChangeListenerTest {
    @Mock private Context mContext;
    private BroadcastInterceptingContext mServiceContext;
    private Handler mHandler;
    private SimChangeListener mSCL;
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
        mSCL = new SimChangeListener(mServiceContext, mHandler, () -> doCallback());
    }

    @After public void tearDown() throws Exception {
        if (mSCL != null) {
            mSCL.stopListening();
            mSCL = null;
        }
    }

    private void sendSimStateChangeIntent(String state) {
        final Intent intent = new Intent(ACTION_SIM_STATE_CHANGED);
        intent.putExtra(INTENT_KEY_ICC_STATE, state);
        mServiceContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Test
    public void testNotSeenFollowedBySeenCallsCallback() {
        mSCL.startListening();

        sendSimStateChangeIntent(INTENT_VALUE_ICC_ABSENT);
        sendSimStateChangeIntent(INTENT_VALUE_ICC_LOADED);
        assertEquals(1, mCallbackCount);

        sendSimStateChangeIntent(INTENT_VALUE_ICC_ABSENT);
        sendSimStateChangeIntent(INTENT_VALUE_ICC_LOADED);
        assertEquals(2, mCallbackCount);

        mSCL.stopListening();
    }

    @Test
    public void testNotListeningDoesNotCallback() {
        sendSimStateChangeIntent(INTENT_VALUE_ICC_ABSENT);
        sendSimStateChangeIntent(INTENT_VALUE_ICC_LOADED);
        assertEquals(0, mCallbackCount);

        sendSimStateChangeIntent(INTENT_VALUE_ICC_ABSENT);
        sendSimStateChangeIntent(INTENT_VALUE_ICC_LOADED);
        assertEquals(0, mCallbackCount);
    }

    @Test
    public void testSeenOnlyDoesNotCallback() {
        mSCL.startListening();

        sendSimStateChangeIntent(INTENT_VALUE_ICC_LOADED);
        assertEquals(0, mCallbackCount);

        sendSimStateChangeIntent(INTENT_VALUE_ICC_LOADED);
        assertEquals(0, mCallbackCount);

        mSCL.stopListening();
    }
}
