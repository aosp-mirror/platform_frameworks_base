/*
 * Copyright (C) 2016 Google Inc.
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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;
import android.test.InstrumentationTestCase;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class CarrierDefaultReceiverTest extends InstrumentationTestCase {
    @Mock
    private NotificationManager mNotificationMgr;
    @Mock
    private TelephonyManager mTelephonyMgr;
    @Mock
    private CarrierConfigManager mCarrierConfigMgr;
    @Captor
    private ArgumentCaptor<Integer> mInt;
    @Captor
    private ArgumentCaptor<Notification> mNotification;
    @Captor
    private ArgumentCaptor<String> mString;
    private TestContext mContext;
    private CarrierDefaultBroadcastReceiver mReceiver;
    private static String TAG;

    private static final String PORTAL_NOTIFICATION_TAG = "CarrierDefault.Portal.Notification";
    private static final int PORTAL_NOTIFICATION_ID = 0;
    private static int subId = 0;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        TAG = this.getClass().getSimpleName();
        MockitoAnnotations.initMocks(this);
        mContext = new TestContext(getInstrumentation().getTargetContext());
        mContext.injectSystemService(NotificationManager.class, mNotificationMgr);
        mContext.injectSystemService(TelephonyManager.class, mTelephonyMgr);
        mContext.injectSystemService(CarrierConfigManager.class, mCarrierConfigMgr);

        mReceiver = new CarrierDefaultBroadcastReceiver();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testOnReceiveRedirection() {
        // carrier action idx list includes 4(portal notification) & 1(disable metered APNs)
        // upon redirection signal
        PersistableBundle b = new PersistableBundle();
        b.putStringArray(CarrierConfigManager
                .KEY_CARRIER_DEFAULT_ACTIONS_ON_REDIRECTION_STRING_ARRAY, new String[]{"4,1"});
        doReturn(b).when(mCarrierConfigMgr).getConfig();

        Intent intent = new Intent(TelephonyIntents.ACTION_CARRIER_SIGNAL_REDIRECTED);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        Rlog.d(TAG, "OnReceive redirection intent");
        mReceiver.onReceive(mContext, intent);

        mContext.waitForMs(100);

        Rlog.d(TAG, "verify carrier action: showPortalNotification");
        verify(mNotificationMgr, times(1)).notify(mString.capture(), mInt.capture(),
                mNotification.capture());
        assertEquals(PORTAL_NOTIFICATION_ID, (int) mInt.getValue());
        assertEquals(PORTAL_NOTIFICATION_TAG, mString.getValue());
        PendingIntent pendingIntent = mNotification.getValue().contentIntent;
        assertNotNull(pendingIntent);

        Rlog.d(TAG, "verify carrier action: disable all metered apns");
        verify(mTelephonyMgr).setCarrierDataEnabled(eq(false));
    }
}
