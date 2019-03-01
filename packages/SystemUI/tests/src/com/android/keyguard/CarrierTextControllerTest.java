/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.keyguard;


import static android.telephony.SubscriptionManager.DATA_ROAMING_DISABLE;
import static android.telephony.SubscriptionManager.DATA_ROAMING_ENABLE;
import static android.telephony.SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.IccCardConstants;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.WakefulnessLifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CarrierTextControllerTest extends SysuiTestCase {

    private static final CharSequence SEPARATOR = " \u2014 ";
    private static final String TEST_CARRIER = "TEST_CARRIER";
    private static final SubscriptionInfo TEST_SUBSCRIPTION = new SubscriptionInfo(0, "", 0,
            TEST_CARRIER, TEST_CARRIER, NAME_SOURCE_DEFAULT_SOURCE, 0xFFFFFF, "",
            DATA_ROAMING_DISABLE, null, null, null, null, false, null, "");
    private static final SubscriptionInfo TEST_SUBSCRIPTION_ROAMING = new SubscriptionInfo(0, "", 0,
            TEST_CARRIER, TEST_CARRIER, NAME_SOURCE_DEFAULT_SOURCE, 0xFFFFFF, "",
            DATA_ROAMING_ENABLE, null, null, null, null, false, null, "");
    @Mock
    private WifiManager mWifiManager;
    @Mock
    private CarrierTextController.CarrierTextCallback mCarrierTextCallback;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private ConnectivityManager mConnectivityManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    private CarrierTextController.CarrierTextCallbackInfo mCarrierTextCallbackInfo;

    private CarrierTextController mCarrierTextController;
    private TestableLooper mTestableLooper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        mContext.addMockSystemService(WifiManager.class, mWifiManager);
        mContext.addMockSystemService(ConnectivityManager.class, mConnectivityManager);
        mContext.addMockSystemService(TelephonyManager.class, mTelephonyManager);
        mDependency.injectMockDependency(WakefulnessLifecycle.class);
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
                new Handler(mTestableLooper.getLooper()));

        mCarrierTextCallbackInfo = new CarrierTextController.CarrierTextCallbackInfo("",
                new CharSequence[]{}, false, new int[]{});
        when(mTelephonyManager.getPhoneCount()).thenReturn(2);
        mCarrierTextController = new TestCarrierTextController(mContext, SEPARATOR, true, true,
                mKeyguardUpdateMonitor);
        // This should not start listening on any of the real dependencies
        mCarrierTextController.setListening(mCarrierTextCallback);
    }

    @Test
    public void testWrongSlots() {
        reset(mCarrierTextCallback);
        when(mKeyguardUpdateMonitor.getSubscriptionInfo(anyBoolean())).thenReturn(
                new ArrayList<>());
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                IccCardConstants.State.CARD_IO_ERROR);
        // This should not produce an out of bounds error, even though there are no subscriptions
        mCarrierTextController.mCallback.onSimStateChanged(0, -3,
                IccCardConstants.State.CARD_IO_ERROR);
        mCarrierTextController.mCallback.onSimStateChanged(0, 3, IccCardConstants.State.READY);
        verify(mCarrierTextCallback, never()).updateCarrierInfo(any());
    }

    @Test
    public void testMoreSlotsThanSubs() {
        reset(mCarrierTextCallback);
        when(mKeyguardUpdateMonitor.getSubscriptionInfo(anyBoolean())).thenReturn(
                new ArrayList<>());
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                IccCardConstants.State.CARD_IO_ERROR);
        // This should not produce an out of bounds error, even though there are no subscriptions
        mCarrierTextController.mCallback.onSimStateChanged(0, 1,
                IccCardConstants.State.CARD_IO_ERROR);

        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(
                any(CarrierTextController.CarrierTextCallbackInfo.class));
    }

    @Test
    public void testCallback() {
        reset(mCarrierTextCallback);
        mCarrierTextController.postToCallback(mCarrierTextCallbackInfo);
        mTestableLooper.processAllMessages();

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertEquals(mCarrierTextCallbackInfo, captor.getValue());
    }

    @Test
    public void testNullingCallback() {
        reset(mCarrierTextCallback);

        mCarrierTextController.postToCallback(mCarrierTextCallbackInfo);
        mCarrierTextController.setListening(null);

        // This shouldn't produce NPE
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(any());
    }

    @Test
    public void testCreateInfo_OneValidSubscription() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(IccCardConstants.State.READY);
        when(mKeyguardUpdateMonitor.getSubscriptionInfo(anyBoolean())).thenReturn(list);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mCarrierTextController.updateCarrierText();
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        CarrierTextController.CarrierTextCallbackInfo info = captor.getValue();
        assertEquals(1, info.listOfCarriers.length);
        assertEquals(TEST_CARRIER, info.listOfCarriers[0]);
        assertEquals(1, info.subscriptionIds.length);
    }

    @Test
    public void testCreateInfo_OneValidSubscriptionWithRoaming() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION_ROAMING);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(IccCardConstants.State.READY);
        when(mKeyguardUpdateMonitor.getSubscriptionInfo(anyBoolean())).thenReturn(list);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mCarrierTextController.updateCarrierText();
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        CarrierTextController.CarrierTextCallbackInfo info = captor.getValue();
        assertEquals(1, info.listOfCarriers.length);
        assertTrue(info.listOfCarriers[0].toString().contains(TEST_CARRIER));
        assertEquals(1, info.subscriptionIds.length);
    }

    @Test
    public void testCreateInfo_noSubscriptions() {
        reset(mCarrierTextCallback);
        when(mKeyguardUpdateMonitor.getSubscriptionInfo(anyBoolean())).thenReturn(
                new ArrayList<>());
        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mCarrierTextController.updateCarrierText();
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        CarrierTextController.CarrierTextCallbackInfo info = captor.getValue();
        assertEquals(0, info.listOfCarriers.length);
        assertEquals(0, info.subscriptionIds.length);

    }

    public static class TestCarrierTextController extends CarrierTextController {
        private KeyguardUpdateMonitor mKUM;

        public TestCarrierTextController(Context context, CharSequence separator,
                boolean showAirplaneMode, boolean showMissingSim, KeyguardUpdateMonitor kum) {
            super(context, separator, showAirplaneMode, showMissingSim);
            mKUM = kum;
        }

        @Override
        public void setListening(CarrierTextCallback callback) {
            super.setListening(callback);
            mKeyguardUpdateMonitor = mKUM;
        }
    }
}
