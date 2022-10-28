/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.connectivity;

import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
import static android.telephony.NetworkRegistrationInfo.DOMAIN_PS;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.settingslib.mobile.TelephonyIcons;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.util.CarrierConfigTracker;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NetworkControllerDataTest extends NetworkControllerBaseTest {

    @Test
    public void test3gDataIcon() {
        setupDefaultSignal();

        verifyDataIndicators(TelephonyIcons.ICON_3G);
    }

    @Test
    public void test2gDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_GSM);

        verifyDataIndicators(TelephonyIcons.ICON_G);
    }

    @Test
    public void testCdmaDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_CDMA);

        verifyDataIndicators(TelephonyIcons.ICON_1X);
    }

    @Test
    public void testEdgeDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_EDGE);

        verifyDataIndicators(TelephonyIcons.ICON_E);
    }

    @Test
    public void testLteDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        verifyDataIndicators(TelephonyIcons.ICON_LTE);
    }

    @Test
    public void testHspaDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_HSPA);

        verifyDataIndicators(TelephonyIcons.ICON_H);
    }


    @Test
    public void testHspaPlusDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_HSPAP);

        verifyDataIndicators(TelephonyIcons.ICON_H_PLUS);
    }


    @Test
    public void testWfcNoDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_IWLAN);

        verifyDataIndicators(0);
    }

    @Test
    public void test4gDataIcon() {
        // Switch to showing 4g icon and re-initialize the NetworkController.
        mConfig.show4gForLte = true;
        mNetworkController = new NetworkControllerImpl(
                mContext,
                mMockCm,
                mMockTm,
                mTelephonyListenerManager,
                mMockWm,
                mMockSm,
                mConfig,
                Looper.getMainLooper(),
                mFakeExecutor,
                mCallbackHandler,
                mock(AccessPointControllerImpl.class),
                mock(DataUsageController.class),
                mMockSubDefaults,
                mock(DeviceProvisionedController.class),
                mMockBd,
                mDemoModeController,
                mock(CarrierConfigTracker.class),
                mWifiStatusTrackerFactory,
                mMobileFactory,
                new Handler(TestableLooper.get(this).getLooper()),
                mock(DumpManager.class),
                mock(LogBuffer.class));
        setupNetworkController();

        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        verifyDataIndicators(TelephonyIcons.ICON_4G);
    }

    @Test
    public void testNoInternetIcon_withDefaultSub() {
        setupNetworkController();
        when(mMockTm.isDataConnectionAllowed()).thenReturn(false);
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED, 0);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_CELLULAR, false, false, null);

        // Verify that a SignalDrawable with a cut out is used to display data disabled.
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, 0,
                true, DEFAULT_QS_SIGNAL_STRENGTH, 0, false,
                false, true, NO_DATA_STRING, NO_DATA_STRING, false);
    }

    @Test
    public void testDataDisabledIcon_withDefaultSub() {
        setupNetworkController();
        when(mMockTm.isDataConnectionAllowed()).thenReturn(false);
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_DISCONNECTED, 0);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_CELLULAR, false, false, null);

        // Verify that a SignalDrawable with a cut out is used to display data disabled.
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, 0,
                true, DEFAULT_QS_SIGNAL_STRENGTH, 0, false,
                false, true, NO_DATA_STRING, NO_DATA_STRING, false);
    }

    @Test
    public void testNonDefaultSIM_showsFullSignal_connected() {
        setupNetworkController();
        when(mMockTm.isDataConnectionAllowed()).thenReturn(false);
        setupDefaultSignal();
        setDefaultSubId(mSubId + 1);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED, 0);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_CELLULAR, false, false, null);

        // Verify that a SignalDrawable with a cut out is used to display data disabled.
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, 0,
                true, DEFAULT_QS_SIGNAL_STRENGTH, 0, false,
                false, false, NOT_DEFAULT_DATA_STRING, NOT_DEFAULT_DATA_STRING, false);
    }

    @Test
    public void testNonDefaultSIM_showsFullSignal_disconnected() {
        setupNetworkController();
        when(mMockTm.isDataConnectionAllowed()).thenReturn(false);
        setupDefaultSignal();
        setDefaultSubId(mSubId + 1);
        updateDataConnectionState(TelephonyManager.DATA_DISCONNECTED, 0);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_CELLULAR, false, false, null);

        // Verify that a SignalDrawable with a cut out is used to display data disabled.
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, 0,
                true, DEFAULT_QS_SIGNAL_STRENGTH, 0, false,
                false, false, NOT_DEFAULT_DATA_STRING, NOT_DEFAULT_DATA_STRING, false);
    }

    @Test
    public void testDataDisabledIcon_UserNotSetup() {
        setupNetworkController();
        when(mMockTm.isDataConnectionAllowed()).thenReturn(false);
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_DISCONNECTED, 0);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_CELLULAR, false, false, null);
        when(mMockProvisionController.isCurrentUserSetup()).thenReturn(false);
        mUserCallback.onUserSetupChanged();
        TestableLooper.get(this).processAllMessages();

        // Don't show the X until the device is setup.
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, 0,
                true, DEFAULT_QS_SIGNAL_STRENGTH, 0, false, false, false, null, null, false);
    }

    @Test
    public void testAlwaysShowDataRatIcon() {
        setupDefaultSignal();
        when(mMockTm.isDataConnectionAllowed()).thenReturn(false);
        updateDataConnectionState(TelephonyManager.DATA_DISCONNECTED,
                TelephonyManager.NETWORK_TYPE_GSM);

        // Switch to showing data RAT icon when data is disconnected
        // and re-initialize the NetworkController.
        mConfig.alwaysShowDataRatIcon = true;
        mNetworkController.handleConfigurationChanged();

        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_CELLULAR, false, false, null);
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, TelephonyIcons.ICON_G,
                true, DEFAULT_QS_SIGNAL_STRENGTH, 0, false, false, false, null, null, false);
    }

    @Test
    public void test4gDataIconConfigChange() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        // Switch to showing 4g icon and re-initialize the NetworkController.
        mConfig.show4gForLte = true;
        // Can't send the broadcast as that would actually read the config from
        // the context.  Instead we'll just poke at a function that does all of
        // the after work.
        mNetworkController.handleConfigurationChanged();

        verifyDataIndicators(TelephonyIcons.ICON_4G);
    }

    @Test
    public void testDataChangeWithoutConnectionState() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        verifyDataIndicators(TelephonyIcons.ICON_LTE);

        NetworkRegistrationInfo fakeRegInfo = new NetworkRegistrationInfo.Builder()
                .setTransportType(TRANSPORT_TYPE_WWAN)
                .setDomain(DOMAIN_PS)
                .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_HSPA)
                .build();
        when(mServiceState.getNetworkRegistrationInfo(DOMAIN_PS, TRANSPORT_TYPE_WWAN))
                .thenReturn(fakeRegInfo);
        when(mTelephonyDisplayInfo.getNetworkType()).thenReturn(TelephonyManager.NETWORK_TYPE_HSPA);
        updateServiceState();
        verifyDataIndicators(TelephonyIcons.ICON_H);
    }

    @Test
    public void testDataActivity() {
        setupDefaultSignal();

        testDataActivity(TelephonyManager.DATA_ACTIVITY_NONE, false, false);
        testDataActivity(TelephonyManager.DATA_ACTIVITY_IN, true, false);
        testDataActivity(TelephonyManager.DATA_ACTIVITY_OUT, false, true);
        testDataActivity(TelephonyManager.DATA_ACTIVITY_INOUT, true, true);
    }

    @Test
    public void testUpdateDataNetworkName() {
        setupDefaultSignal();
        String newDataName = "TestDataName";
        when(mServiceState.getOperatorAlphaShort()).thenReturn(newDataName);
        updateServiceState();
        assertDataNetworkNameEquals(newDataName);
    }

    @Test
    public void testIsDataInService_true() {
        setupDefaultSignal();
        assertTrue(mNetworkController.isMobileDataNetworkInService());
    }

    @Test
    public void testIsDataInService_noSignal_false() {
        assertFalse(mNetworkController.isMobileDataNetworkInService());
    }

    @Test
    public void testIsDataInService_notInService_false() {
        setupDefaultSignal();
        setVoiceRegState(ServiceState.STATE_OUT_OF_SERVICE);
        setDataRegState(ServiceState.STATE_OUT_OF_SERVICE);
        assertFalse(mNetworkController.isMobileDataNetworkInService());
    }

    private void testDataActivity(int direction, boolean in, boolean out) {
        updateDataActivity(direction);

        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, DEFAULT_ICON, true,
                DEFAULT_QS_SIGNAL_STRENGTH, DEFAULT_QS_ICON, in, out);
    }

    private void verifyDataIndicators(int dataIcon) {
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, dataIcon,
                true, DEFAULT_QS_SIGNAL_STRENGTH, dataIcon, false,
                false);
    }
}
