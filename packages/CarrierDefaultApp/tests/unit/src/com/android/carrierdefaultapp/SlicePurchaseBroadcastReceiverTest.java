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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
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
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.UserHandle;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.test.runner.AndroidJUnit4;

import com.android.phone.slice.SlicePurchaseController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URL;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class SlicePurchaseBroadcastReceiverTest {
    private static final int PHONE_ID = 0;
    private static final String CARRIER = "Some Carrier";
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
    @Mock Configuration mConfiguration;
    @Mock NotificationManager mNotificationManager;
    @Mock ApplicationInfo mApplicationInfo;
    @Mock PackageManager mPackageManager;

    private SlicePurchaseBroadcastReceiver mSlicePurchaseBroadcastReceiver;
    private Resources mSpiedResources;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSpiedResources = spy(Resources.getSystem());

        doReturn("").when(mResources).getString(anyInt());
        doReturn(mNotificationManager).when(mContext)
                .getSystemService(eq(NotificationManager.class));
        doReturn(mApplicationInfo).when(mContext).getApplicationInfo();
        doReturn(mPackageManager).when(mContext).getPackageManager();
        doReturn(mSpiedResources).when(mContext).getResources();

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
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mPendingIntent).send(eq(mContext), eq(0), captor.capture());
        assertEquals(mDataIntent, captor.getValue());
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
        doReturn(SlicePurchaseController.SLICE_PURCHASE_TEST_FILE).when(mIntent).getStringExtra(
                eq(SlicePurchaseController.EXTRA_PURCHASE_URL));
        doReturn(CARRIER).when(mIntent).getStringExtra(eq(SlicePurchaseController.EXTRA_CARRIER));
        assertFalse(SlicePurchaseBroadcastReceiver.isIntentValid(mIntent));

        // set up pending intent
        doReturn(TelephonyManager.PHONE_PROCESS_NAME).when(mPendingIntent).getCreatorPackage();
        doReturn(true).when(mPendingIntent).isBroadcast();
        doReturn(mPendingIntent).when(mIntent).getParcelableExtra(
                anyString(), eq(PendingIntent.class));
        assertTrue(SlicePurchaseBroadcastReceiver.isIntentValid(mIntent));
    }

    @Test
    public void testGetPurchaseUrl() {
        String[] invalidUrls = new String[] {
                null,
                "",
                "www.google.com",
                "htt://www.google.com",
                "http//www.google.com",
                "http:/www.google.com",
                "file:///android_asset/",
                "file:///android_asset/slice_store_test.html"
        };

        for (String url : invalidUrls) {
            URL purchaseUrl = SlicePurchaseBroadcastReceiver.getPurchaseUrl(url);
            assertNull(purchaseUrl);
        }

        assertEquals(SlicePurchaseController.SLICE_PURCHASE_TEST_FILE,
                SlicePurchaseBroadcastReceiver.getPurchaseUrl(
                        SlicePurchaseController.SLICE_PURCHASE_TEST_FILE).toString());
    }

    @Test
    public void testDisplayPerformanceBoostNotification() throws Exception {
        displayPerformanceBoostNotification();

        // verify performance boost notification was shown
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(mNotificationManager).notifyAsUser(
                eq(SlicePurchaseBroadcastReceiver.PERFORMANCE_BOOST_NOTIFICATION_TAG),
                eq(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY),
                captor.capture(),
                eq(UserHandle.ALL));

        // verify notification fields
        Notification notification = captor.getValue();
        assertEquals(mContentIntent1, notification.contentIntent);
        assertEquals(mPendingIntent, notification.deleteIntent);
        assertEquals(2, notification.actions.length);
        assertEquals(mCanceledIntent, notification.actions[0].actionIntent);
        assertEquals(mContentIntent2, notification.actions[1].actionIntent);

        // verify SlicePurchaseController was notified
        verify(mNotificationShownIntent).send();
    }

    private void displayPerformanceBoostNotification() {
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

        // spy notification intents to prevent PendingIntent issues
        doReturn(mContentIntent1).when(mSlicePurchaseBroadcastReceiver).createContentIntent(
                eq(mContext), eq(mIntent), eq(1));
        doReturn(mContentIntent2).when(mSlicePurchaseBroadcastReceiver).createContentIntent(
                eq(mContext), eq(mIntent), eq(2));
        doReturn(mCanceledIntent).when(mSlicePurchaseBroadcastReceiver).createCanceledIntent(
                eq(mContext), eq(mIntent));

        // spy resources to prevent resource not found issues
        doReturn(mResources).when(mSlicePurchaseBroadcastReceiver).getResources(eq(mContext));

        // send ACTION_START_SLICE_PURCHASE_APP
        doReturn(SlicePurchaseController.ACTION_START_SLICE_PURCHASE_APP).when(mIntent).getAction();
        doReturn(PHONE_ID).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PHONE_ID), anyInt());
        doReturn(SubscriptionManager.getDefaultDataSubscriptionId()).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_SUB_ID), anyInt());
        doReturn(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY), anyInt());
        doReturn(SlicePurchaseController.SLICE_PURCHASE_TEST_FILE).when(mIntent).getStringExtra(
                eq(SlicePurchaseController.EXTRA_PURCHASE_URL));
        doReturn(CARRIER).when(mIntent).getStringExtra(eq(SlicePurchaseController.EXTRA_CARRIER));
        mSlicePurchaseBroadcastReceiver.onReceive(mContext, mIntent);
    }

    @Test
    public void testNotificationCanceled() {
        // send ACTION_NOTIFICATION_CANCELED
        doReturn("com.android.phone.slice.action.NOTIFICATION_CANCELED").when(mIntent).getAction();
        doReturn(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY), anyInt());
        mSlicePurchaseBroadcastReceiver.onReceive(mContext, mIntent);

        // verify notification was canceled
        verify(mNotificationManager).cancelAsUser(
                eq(SlicePurchaseBroadcastReceiver.PERFORMANCE_BOOST_NOTIFICATION_TAG),
                eq(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY),
                eq(UserHandle.ALL));
    }

    @Test
    public void testNotificationTimeout() throws Exception {
        displayPerformanceBoostNotification();

        // send ACTION_SLICE_PURCHASE_APP_RESPONSE_TIMEOUT
        doReturn(SlicePurchaseController.ACTION_SLICE_PURCHASE_APP_RESPONSE_TIMEOUT).when(mIntent)
                .getAction();
        doReturn(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY).when(mIntent).getIntExtra(
                eq(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY), anyInt());
        mSlicePurchaseBroadcastReceiver.onReceive(mContext, mIntent);

        // verify notification was canceled
        verify(mNotificationManager).cancelAsUser(
                eq(SlicePurchaseBroadcastReceiver.PERFORMANCE_BOOST_NOTIFICATION_TAG),
                eq(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY),
                eq(UserHandle.ALL));
    }

    @Test
    public void testLocaleChanged() throws Exception {
        // get previous locale
        doReturn(mConfiguration).when(mSpiedResources).getConfiguration();
        Locale before = getLocale();

        // display notification
        displayPerformanceBoostNotification();
        clearInvocations(mNotificationManager);
        clearInvocations(mNotificationShownIntent);

        // change current locale from previous value
        Locale newLocale = Locale.forLanguageTag("en-US");
        if (before.equals(newLocale)) {
            newLocale = Locale.forLanguageTag("ko-KR");
        }
        doReturn(newLocale).when(mSlicePurchaseBroadcastReceiver).getCurrentLocale();

        // send ACTION_LOCALE_CHANGED
        doReturn(Intent.ACTION_LOCALE_CHANGED).when(mIntent).getAction();
        mSlicePurchaseBroadcastReceiver.onReceive(mContext, mIntent);

        // verify notification was updated and SlicePurchaseController was not notified
        verify(mNotificationManager).cancelAsUser(
                eq(SlicePurchaseBroadcastReceiver.PERFORMANCE_BOOST_NOTIFICATION_TAG),
                eq(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY),
                eq(UserHandle.ALL));
        verify(mNotificationManager).notifyAsUser(
                eq(SlicePurchaseBroadcastReceiver.PERFORMANCE_BOOST_NOTIFICATION_TAG),
                eq(TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY),
                any(Notification.class),
                eq(UserHandle.ALL));
        verify(mNotificationShownIntent, never()).send();

        // verify locale was changed successfully
        doCallRealMethod().when(mSlicePurchaseBroadcastReceiver).getResources(eq(mContext));
        assertEquals(newLocale, getLocale());
    }

    private Locale getLocale() {
        try {
            mSlicePurchaseBroadcastReceiver.getResources(mContext);
            fail("getLocale should not have completed successfully.");
        } catch (NullPointerException expected) { }
        ArgumentCaptor<Locale> captor = ArgumentCaptor.forClass(Locale.class);
        verify(mConfiguration).setLocale(captor.capture());
        clearInvocations(mConfiguration);
        return captor.getValue();
    }
}
