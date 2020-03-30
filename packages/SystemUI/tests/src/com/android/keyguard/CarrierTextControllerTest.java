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
import static android.telephony.SubscriptionManager.NAME_SOURCE_CARRIER_ID;

import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertFalse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.TextUtils;

import com.android.internal.telephony.IccCardConstants;
import com.android.systemui.Dependency;
import com.android.systemui.R;
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
    private static final CharSequence INVALID_CARD_TEXT = "Invalid card";
    private static final CharSequence AIRPLANE_MODE_TEXT = "Airplane mode";
    private static final String TEST_CARRIER = "TEST_CARRIER";
    private static final String TEST_CARRIER_2 = "TEST_CARRIER_2";
    private static final int TEST_CARRIER_ID = 1;
    private static final SubscriptionInfo TEST_SUBSCRIPTION = new SubscriptionInfo(0, "", 0,
            TEST_CARRIER, TEST_CARRIER, NAME_SOURCE_CARRIER_ID, 0xFFFFFF, "",
            DATA_ROAMING_DISABLE, null, null, null, null, false, null, "", false, null,
            TEST_CARRIER_ID, 0);
    private static final SubscriptionInfo TEST_SUBSCRIPTION_NULL = new SubscriptionInfo(0, "", 0,
            TEST_CARRIER, null, NAME_SOURCE_CARRIER_ID, 0xFFFFFF, "", DATA_ROAMING_DISABLE,
            null, null, null, null, false, null, "");
    private static final SubscriptionInfo TEST_SUBSCRIPTION_ROAMING = new SubscriptionInfo(0, "", 0,
            TEST_CARRIER, TEST_CARRIER, NAME_SOURCE_CARRIER_ID, 0xFFFFFF, "",
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
    @Mock
    private SubscriptionManager mSubscriptionManager;
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
        mContext.addMockSystemService(SubscriptionManager.class, mSubscriptionManager);
        mContext.getOrCreateTestableResources().addOverride(
                R.string.keyguard_sim_error_message_short, INVALID_CARD_TEXT);
        mContext.getOrCreateTestableResources().addOverride(
                R.string.airplane_mode, AIRPLANE_MODE_TEXT);
        mDependency.injectMockDependency(WakefulnessLifecycle.class);
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
                new Handler(mTestableLooper.getLooper()));

        mCarrierTextCallbackInfo = new CarrierTextController.CarrierTextCallbackInfo("",
                new CharSequence[]{}, false, new int[]{});
        when(mTelephonyManager.getSupportedModemCount()).thenReturn(3);
        when(mTelephonyManager.getActiveModemCount()).thenReturn(3);

        mCarrierTextController = new TestCarrierTextController(mContext, SEPARATOR, true, true,
                mKeyguardUpdateMonitor);
        // This should not start listening on any of the real dependencies
        mCarrierTextController.setListening(mCarrierTextCallback);
    }

    @Test
    public void testAirplaneMode() {
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 1);
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(list);
        when(mKeyguardUpdateMonitor.getSimState(0)).thenReturn(IccCardConstants.State.READY);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        mCarrierTextController.updateCarrierText();

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertEquals(AIRPLANE_MODE_TEXT, captor.getValue().carrierText);
    }

    @Test
    public void testCardIOError() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(list);
        when(mKeyguardUpdateMonitor.getSimState(0)).thenReturn(IccCardConstants.State.READY);
        when(mKeyguardUpdateMonitor.getSimState(1)).thenReturn(
                IccCardConstants.State.CARD_IO_ERROR);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        mCarrierTextController.mCallback.onSimStateChanged(3, 1,
                IccCardConstants.State.CARD_IO_ERROR);

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertEquals("TEST_CARRIER" + SEPARATOR + INVALID_CARD_TEXT, captor.getValue().carrierText);
        // There's only one subscription in the list
        assertEquals(1, captor.getValue().listOfCarriers.length);
        assertEquals(TEST_CARRIER, captor.getValue().listOfCarriers[0]);

        // Now it becomes single SIM active mode.
        reset(mCarrierTextCallback);
        when(mTelephonyManager.getActiveModemCount()).thenReturn(1);
        // Update carrier text. It should ignore error state of subId 3 in inactive slotId.
        mCarrierTextController.updateCarrierText();
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertEquals("TEST_CARRIER", captor.getValue().carrierText);
    }

    @Test
    public void testWrongSlots() {
        reset(mCarrierTextCallback);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(
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
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(
                new ArrayList<>());

        // STOPSHIP(b/130246708) This line makes sure that SubscriptionManager provides the
        // same answer as KeyguardUpdateMonitor. Remove when this is addressed
        when(mSubscriptionManager.getActiveSubscriptionInfoList(anyBoolean())).thenReturn(
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
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(list);

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
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(list);

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
    public void testCarrierText_noTextOnReadySimWhenNull() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION_NULL);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(IccCardConstants.State.READY);
        when(mKeyguardUpdateMonitor.getSubscriptionInfo(anyBoolean())).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mCarrierTextController.updateCarrierText();
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        assertTrue("Carrier text should be empty, instead it's " + captor.getValue().carrierText,
                TextUtils.isEmpty(captor.getValue().carrierText));
        assertFalse("No SIM should be available", captor.getValue().anySimReady);
    }

    @Test
    public void testCarrierText_noTextOnReadySimWhenNull_airplaneMode_wifiOn() {
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 1);
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION_NULL);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(IccCardConstants.State.READY);
        when(mKeyguardUpdateMonitor.getSubscriptionInfo(anyBoolean())).thenReturn(list);
        mockWifi();

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();
        ServiceState ss = mock(ServiceState.class);
        when(ss.getDataRegistrationState()).thenReturn(ServiceState.STATE_IN_SERVICE);
        mKeyguardUpdateMonitor.mServiceStates.put(TEST_SUBSCRIPTION_NULL.getSubscriptionId(), ss);

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mCarrierTextController.updateCarrierText();
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        assertFalse("No SIM should be available", captor.getValue().anySimReady);
        // There's no airplane mode if at least one SIM is State.READY and there's wifi
        assertFalse("Device should not be in airplane mode", captor.getValue().airplaneMode);
        assertNotEquals(AIRPLANE_MODE_TEXT, captor.getValue().carrierText);
    }

    private void mockWifi() {
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        WifiInfo wifiInfo = mock(WifiInfo.class);
        when(wifiInfo.getBSSID()).thenReturn("");
        when(mWifiManager.getConnectionInfo()).thenReturn(wifiInfo);
    }

    @Test
    public void testCreateInfo_noSubscriptions() {
        reset(mCarrierTextCallback);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(
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

    @Test
    public void testCarrierText_twoValidSubscriptions() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(IccCardConstants.State.READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mCarrierTextController.updateCarrierText();
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        assertEquals(TEST_CARRIER + SEPARATOR + TEST_CARRIER,
                captor.getValue().carrierText);
    }

    @Test
    public void testCarrierText_oneDisabledSub() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt()))
                .thenReturn(IccCardConstants.State.READY)
                .thenReturn(IccCardConstants.State.NOT_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mCarrierTextController.updateCarrierText();
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        assertEquals(TEST_CARRIER,
                captor.getValue().carrierText);
    }

    @Test
    public void testCarrierText_firstDisabledSub() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt()))
                .thenReturn(IccCardConstants.State.NOT_READY)
                .thenReturn(IccCardConstants.State.READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mCarrierTextController.updateCarrierText();
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        assertEquals(TEST_CARRIER,
                captor.getValue().carrierText);
    }

    @Test
    public void testCarrierText_threeSubsMiddleDisabled() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        list.add(TEST_SUBSCRIPTION);
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt()))
                .thenReturn(IccCardConstants.State.READY)
                .thenReturn(IccCardConstants.State.NOT_READY)
                .thenReturn(IccCardConstants.State.READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(anyBoolean())).thenReturn(list);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextController.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextController.CarrierTextCallbackInfo.class);

        mCarrierTextController.updateCarrierText();
        mTestableLooper.processAllMessages();
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        assertEquals(TEST_CARRIER + SEPARATOR + TEST_CARRIER,
                captor.getValue().carrierText);
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
