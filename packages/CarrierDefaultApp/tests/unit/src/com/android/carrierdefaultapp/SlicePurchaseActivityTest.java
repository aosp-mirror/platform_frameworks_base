/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.carrierdefaultapp;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.ActivityUnitTestCase;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.phone.slice.SlicePurchaseController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class SlicePurchaseActivityTest extends ActivityUnitTestCase<SlicePurchaseActivity> {
    private static final String CARRIER = "Some Carrier";
    private static final String URL = "file:///android_asset/slice_purchase_test.html";
    private static final int PHONE_ID = 0;

    @Mock PendingIntent mPendingIntent;
    @Mock PendingIntent mCanceledIntent;
    @Mock CarrierConfigManager mCarrierConfigManager;
    @Mock NotificationManager mNotificationManager;
    @Mock PersistableBundle mPersistableBundle;

    private SlicePurchaseActivity mSlicePurchaseActivity;
    private Context mContext;

    public SlicePurchaseActivityTest() {
        super(SlicePurchaseActivity.class);
    }

    @Before
    public void setUp() throws Exception {
        injectInstrumentation(InstrumentationRegistry.getInstrumentation());
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        super.setUp();
        MockitoAnnotations.initMocks(this);

        // setup context
        mContext = spy(getInstrumentation().getTargetContext());
        doReturn(mCarrierConfigManager).when(mContext)
                .getSystemService(eq(CarrierConfigManager.class));
        doReturn(URL).when(mPersistableBundle).getString(
                CarrierConfigManager.KEY_PREMIUM_CAPABILITY_PURCHASE_URL_STRING);
        doReturn(mPersistableBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt());
        doReturn(mNotificationManager).when(mContext)
                .getSystemService(eq(NotificationManager.class));
        doReturn(mContext).when(mContext).getApplicationContext();
        setActivityContext(mContext);

        // set up intent
        Intent intent = new Intent();
        intent.putExtra(SlicePurchaseController.EXTRA_PHONE_ID, PHONE_ID);
        intent.putExtra(SlicePurchaseController.EXTRA_SUB_ID,
                SubscriptionManager.getDefaultDataSubscriptionId());
        intent.putExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY);
        intent.putExtra(SlicePurchaseController.EXTRA_PURCHASE_URL,
                SlicePurchaseController.SLICE_PURCHASE_TEST_FILE);
        intent.putExtra(SlicePurchaseController.EXTRA_CARRIER, CARRIER);
        Intent spiedIntent = spy(intent);

        // set up pending intents
        doReturn(TelephonyManager.PHONE_PROCESS_NAME).when(mPendingIntent).getCreatorPackage();
        doReturn(true).when(mPendingIntent).isBroadcast();
        doReturn(mPendingIntent).when(spiedIntent).getParcelableExtra(
                anyString(), eq(PendingIntent.class));
        doReturn(TelephonyManager.PHONE_PROCESS_NAME).when(mCanceledIntent).getCreatorPackage();
        doReturn(true).when(mCanceledIntent).isBroadcast();
        doReturn(mCanceledIntent).when(spiedIntent).getParcelableExtra(
                eq(SlicePurchaseController.EXTRA_INTENT_CANCELED), eq(PendingIntent.class));

        mSlicePurchaseActivity = startActivity(spiedIntent, null, null);
    }

    @Test
    public void testOnPurchaseSuccessful() throws Exception {
        int duration = 5 * 60 * 1000; // 5 minutes
        int invalidDuration = -1;
        mSlicePurchaseActivity.onPurchaseSuccessful(duration);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mPendingIntent).send(eq(mContext), eq(0), intentCaptor.capture());
        Intent intent = intentCaptor.getValue();
        assertEquals(duration, intent.getLongExtra(
                SlicePurchaseController.EXTRA_PURCHASE_DURATION, invalidDuration));
    }

    @Test
    public void testOnPurchaseFailed() throws Exception {
        int failureCode = SlicePurchaseController.FAILURE_CODE_SERVER_UNREACHABLE;
        String failureReason = "Server unreachable";
        mSlicePurchaseActivity.onPurchaseFailed(failureCode, failureReason);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mPendingIntent).send(eq(mContext), eq(0), intentCaptor.capture());
        Intent intent = intentCaptor.getValue();
        assertEquals(failureCode, intent.getIntExtra(
                SlicePurchaseController.EXTRA_FAILURE_CODE, failureCode));
        assertEquals(failureReason, intent.getStringExtra(
                SlicePurchaseController.EXTRA_FAILURE_REASON));
    }

    @Test
    public void testOnUserCanceled() throws Exception {
        mSlicePurchaseActivity.onDestroy();
        verify(mCanceledIntent).send();
    }
}
