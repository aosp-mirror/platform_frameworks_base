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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.UserHandle;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;

import androidx.test.runner.AndroidJUnit4;

import com.android.phone.slice.SlicePurchaseController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class SlicePurchaseBroadcastReceiverTest {
    private static final int PHONE_ID = 0;
    private static final String TAG = "SlicePurchaseBroadcastReceiverTest";
    private static final String EXTRA = "EXTRA";

    @Mock Intent mIntent;
    @Mock Intent mDataIntent;
    @Mock PendingIntent mPendingIntent;
    @Mock PendingIntent mCanceledIntent;
    @Mock PendingIntent mContentIntent1;
    @Mock PendingIntent mContentIntent2;
    @Mock PendingIntent mNotificationShownIntent;
    @Mock Context mContext;
    @Mock Resources mResources;
    @Mock NotificationManager mNotificationManager;
    @Mock ApplicationInfo mApplicationInfo;
    @Mock PackageManager mPackageManager;
    @Mock DisplayMetrics mDisplayMetrics;
    @Mock SlicePurchaseActivity mSlicePurchaseActivity;

    private SlicePurchaseBroadcastReceiver mSlicePurchaseBroadcastReceiver;
    private ArgumentCaptor<Intent> mIntentCaptor;
    private ArgumentCaptor<Notification> mNotificationCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(mNotificationManager).when(mContext)
                .getSystemService(eq(NotificationManager.class));

        mIntentCaptor = ArgumentCaptor.forClass(Intent.class);
        mNotificationCaptor = ArgumentCaptor.forClass(Notification.class);
        mSlicePurchaseBroadcastReceiver = spy(new SlicePurchaseBroadcastReceiver());
    }

    @Test
    public void testSendSlicePurchaseAppResponse() throws Exception {
        SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponse(mIntent, EXTRA);
        verify(mPendingIntent, never()).send();

        doReturn(mPendingIntent).when(mIntent).getParcelableExtra(
                eq(EXTRA), eq(PendingIntent.class));
        SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponse(mIntent, EXTRA);
        verify(mPendingIntent).send();
    }

    @Test
    public void testSendSlicePurchaseAppResponseWithData() throws Exception {
        SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponseWithData(
                mContext, mIntent, EXTRA, mDataIntent);
        verify(mPendingIntent, never()).send(eq(mContext), eq(0), any(Intent.class));

        doReturn(mPendingIntent).when(mIntent).getParcelableExtra(
                eq(EXTRA), eq(PendingIntent.class));
        SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponseWithData(
                mContext, mIntent, EXTRA, mDataIntent);
        verify(mPendingIntent).send(eq(mContext), eq(0), mIntentCaptor.capture());
        assertEquals(mDataIntent, mIntentCaptor.getValue());
    }

    @Test
    public void testIsIntentValid() {
        assertFalse(SlicePurchaseBroadcastReceiver.isIntentValid(mIntent));

        // set up intent
        doReturn(PHONE_ID).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PHONE_ID), anyInt());
        doReturn(SubscriptionManager.getDefaultDataSubscriptionId()).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_SUB_ID), anyInt());
        doReturn(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY), anyInt());
        doReturn(TAG).when(mIntent).getStringExtra(
                eq(SlicePurchaseController.EXTRA_REQUESTING_APP_NAME));
        assertFalse(SlicePurchaseBroadcastReceiver.isIntentValid(mIntent));

        // set up pending intent
        doReturn(TelephonyManager.PHONE_PROCESS_NAME).when(mPendingIntent).getCreatorPackage();
        doReturn(true).when(mPendingIntent).isBroadcast();
        doReturn(mPendingIntent).when(mIntent).getParcelableExtra(
                anyString(), eq(PendingIntent.class));
        assertTrue(SlicePurchaseBroadcastReceiver.isIntentValid(mIntent));
    }

    @Test
    public void testDisplayNetworkBoostNotification() throws Exception {
        // set up intent
        doReturn(SlicePurchaseController.ACTION_START_SLICE_PURCHASE_APP).when(mIntent).getAction();
        doReturn(PHONE_ID).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PHONE_ID), anyInt());
        doReturn(SubscriptionManager.getDefaultDataSubscriptionId()).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_SUB_ID), anyInt());
        doReturn(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY), anyInt());
        doReturn(TAG).when(mIntent).getStringExtra(
                eq(SlicePurchaseController.EXTRA_REQUESTING_APP_NAME));

        // set up pending intents
        doReturn(TelephonyManager.PHONE_PROCESS_NAME).when(mPendingIntent).getCreatorPackage();
        doReturn(true).when(mPendingIntent).isBroadcast();
        doReturn(mPendingIntent).when(mIntent).getParcelableExtra(
                anyString(), eq(PendingIntent.class));
        doReturn(TelephonyManager.PHONE_PROCESS_NAME).when(mNotificationShownIntent)
                .getCreatorPackage();
        doReturn(true).when(mNotificationShownIntent).isBroadcast();
        doReturn(mNotificationShownIntent).when(mIntent).getParcelableExtra(
                eq(SlicePurchaseController.EXTRA_INTENT_NOTIFICATION_SHOWN),
                eq(PendingIntent.class));

        // set up notification
        doReturn(mResources).when(mContext).getResources();
        doReturn(mDisplayMetrics).when(mResources).getDisplayMetrics();
        doReturn("").when(mResources).getString(anyInt());
        doReturn(mApplicationInfo).when(mContext).getApplicationInfo();
        doReturn(mPackageManager).when(mContext).getPackageManager();

        // set up intents created by broadcast receiver
        doReturn(mContentIntent1).when(mSlicePurchaseBroadcastReceiver).createContentIntent(
                eq(mContext), eq(mIntent), eq(1));
        doReturn(mContentIntent2).when(mSlicePurchaseBroadcastReceiver).createContentIntent(
                eq(mContext), eq(mIntent), eq(2));
        doReturn(mCanceledIntent).when(mSlicePurchaseBroadcastReceiver).createCanceledIntent(
                eq(mContext), eq(mIntent));

        // send ACTION_START_SLICE_PURCHASE_APP
        mSlicePurchaseBroadcastReceiver.onReceive(mContext, mIntent);

        // verify network boost notification was shown
        verify(mNotificationManager).notifyAsUser(
                eq(SlicePurchaseBroadcastReceiver.NETWORK_BOOST_NOTIFICATION_TAG),
                eq(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY),
                mNotificationCaptor.capture(),
                eq(UserHandle.ALL));

        Notification notification = mNotificationCaptor.getValue();
        assertEquals(mContentIntent1, notification.contentIntent);
        assertEquals(mPendingIntent, notification.deleteIntent);
        assertEquals(2, notification.actions.length);
        assertEquals(mCanceledIntent, notification.actions[0].actionIntent);
        assertEquals(mContentIntent2, notification.actions[1].actionIntent);

        // verify SlicePurchaseController was notified
        verify(mNotificationShownIntent).send();
    }

    @Test
    public void testNotificationCanceled() {
        // set up intent
        doReturn("com.android.phone.slice.action.NOTIFICATION_CANCELED").when(mIntent).getAction();
        doReturn(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY), anyInt());

        // send ACTION_NOTIFICATION_CANCELED
        mSlicePurchaseBroadcastReceiver.onReceive(mContext, mIntent);

        // verify notification was canceled
        verify(mNotificationManager).cancelAsUser(
                eq(SlicePurchaseBroadcastReceiver.NETWORK_BOOST_NOTIFICATION_TAG),
                eq(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY),
                eq(UserHandle.ALL));
    }

    @Test
    public void testNotificationTimeout() {
        // set up intent
        doReturn(SlicePurchaseController.ACTION_SLICE_PURCHASE_APP_RESPONSE_TIMEOUT).when(mIntent)
                .getAction();
        doReturn(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY), anyInt());

        // send ACTION_SLICE_PURCHASE_APP_RESPONSE_TIMEOUT
        mSlicePurchaseBroadcastReceiver.onReceive(mContext, mIntent);

        // verify notification was canceled
        verify(mNotificationManager).cancelAsUser(
                eq(SlicePurchaseBroadcastReceiver.NETWORK_BOOST_NOTIFICATION_TAG),
                eq(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY),
                eq(UserHandle.ALL));
    }

    @Test
    // TODO: WebView/Activity should not close on timeout.
    //  This test should be removed once implementation is fixed.
    public void testActivityTimeout() {
        // create and track activity
        SlicePurchaseBroadcastReceiver.updateSlicePurchaseActivity(
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY, mSlicePurchaseActivity);

        // set up intent
        doReturn(SlicePurchaseController.ACTION_SLICE_PURCHASE_APP_RESPONSE_TIMEOUT).when(mIntent)
                .getAction();
        doReturn(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY), anyInt());

        // send ACTION_SLICE_PURCHASE_APP_RESPONSE_TIMEOUT
        mSlicePurchaseBroadcastReceiver.onReceive(mContext, mIntent);

        // verify activity was canceled
        verify(mSlicePurchaseActivity).finishAndRemoveTask();

        // untrack activity
        SlicePurchaseBroadcastReceiver.removeSlicePurchaseActivity(
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY);
    }
}
