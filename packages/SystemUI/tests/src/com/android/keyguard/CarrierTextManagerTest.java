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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertFalse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.keyguard.logging.CarrierTextManagerLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.log.LogBufferHelperKt;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel.FakeDeviceBasedSatelliteViewModel;
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository;
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.time.FakeSystemClock;

import kotlinx.coroutines.test.TestScope;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class CarrierTextManagerTest extends SysuiTestCase {

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
    private FakeWifiRepository mWifiRepository = new FakeWifiRepository();
    private final FakeDeviceBasedSatelliteViewModel mSatelliteViewModel =
            new FakeDeviceBasedSatelliteViewModel();
    @Mock
    private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock
    private CarrierTextManager.CarrierTextCallback mCarrierTextCallback;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private TelephonyListenerManager mTelephonyListenerManager;
    private FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private FakeExecutor mMainExecutor = new FakeExecutor(mFakeSystemClock);
    private FakeExecutor mBgExecutor = new FakeExecutor(mFakeSystemClock);
    @Mock
    private SubscriptionManager mSubscriptionManager;
    private CarrierTextManager.CarrierTextCallbackInfo mCarrierTextCallbackInfo;

    private CarrierTextManager mCarrierTextManager;

    private CarrierTextManagerLogger mLogger =
            new CarrierTextManagerLogger(
                    LogBufferHelperKt.logcatLogBuffer("CarrierTextManagerLog"));

    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);
    private final TestScope mTestScope = mKosmos.getTestScope();
    private final JavaAdapter mJavaAdapter = new JavaAdapter(mTestScope.getBackgroundScope());

    private Void checkMainThread(InvocationOnMock inv) {
        assertThat(mMainExecutor.isExecuting()).isTrue();
        assertThat(mBgExecutor.isExecuting()).isFalse();
        return null;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext.addMockSystemService(PackageManager.class, mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        mContext.addMockSystemService(TelephonyManager.class, mTelephonyManager);
        mContext.addMockSystemService(SubscriptionManager.class, mSubscriptionManager);
        mContext.getOrCreateTestableResources().addOverride(
                R.string.keyguard_sim_error_message_short, INVALID_CARD_TEXT);
        mContext.getOrCreateTestableResources().addOverride(
                R.string.airplane_mode, AIRPLANE_MODE_TEXT);
        mDependency.injectMockDependency(WakefulnessLifecycle.class);
        mDependency.injectTestDependency(KeyguardUpdateMonitor.class, mKeyguardUpdateMonitor);

        doAnswer(this::checkMainThread).when(mKeyguardUpdateMonitor)
                .registerCallback(any(KeyguardUpdateMonitorCallback.class));
        doAnswer(this::checkMainThread).when(mKeyguardUpdateMonitor)
                .removeCallback(any(KeyguardUpdateMonitorCallback.class));

        mCarrierTextCallbackInfo = new CarrierTextManager.CarrierTextCallbackInfo(
                /* carrierText= */ "",
                /* listOfCarriers= */ new CharSequence[]{},
                /* anySimReady= */ false,
                /* subscriptionIds= */ new int[]{});
        when(mTelephonyManager.getSupportedModemCount()).thenReturn(3);
        when(mTelephonyManager.getActiveModemCount()).thenReturn(3);

        mCarrierTextManager = new CarrierTextManager.Builder(
                mContext,
                mContext.getResources(),
                mWifiRepository,
                mSatelliteViewModel,
                mJavaAdapter,
                mTelephonyManager,
                mTelephonyListenerManager,
                mWakefulnessLifecycle,
                mMainExecutor,
                mBgExecutor,
                mKeyguardUpdateMonitor,
                mLogger)
                .setShowAirplaneMode(true)
                .setShowMissingSim(true)
                .build();

        // This should not start listening on any of the real dependencies but will test that
        // callbacks in mKeyguardUpdateMonitor are done in the mTestableLooper thread
        mCarrierTextManager.setListening(mCarrierTextCallback);
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
    }

    @Test
    public void testKeyguardUpdateMonitorCalledInMainThread() throws Exception {
        mCarrierTextManager.setListening(null);
        mCarrierTextManager.setListening(mCarrierTextCallback);
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
    }

    @Test
    public void testAirplaneMode() {
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 1);
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);
        when(mKeyguardUpdateMonitor.getSimState(0)).thenReturn(TelephonyManager.SIM_STATE_READY);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        mCarrierTextManager.updateCarrierText();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertEquals(AIRPLANE_MODE_TEXT, captor.getValue().carrierText);
    }

    /** regression test for b/281706473, caused by sending NULL plmn / spn to the logger */
    @Test
    public void testAirplaneMode_noSim_nullPlmn_nullSpn_doesNotCrash() {
        // GIVEN - sticy broadcast that returns a null PLMN and null SPN
        Intent stickyIntent = new Intent(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED);
        stickyIntent.putExtra(TelephonyManager.EXTRA_SHOW_PLMN, true);
        stickyIntent.removeExtra(TelephonyManager.EXTRA_PLMN);
        stickyIntent.putExtra(TelephonyManager.EXTRA_SHOW_SPN, true);
        stickyIntent.removeExtra(TelephonyManager.EXTRA_SPN);

        mCarrierTextManager = new CarrierTextManager.Builder(
                getContextSpyForStickyBroadcast(stickyIntent),
                mContext.getResources(),
                mWifiRepository,
                mSatelliteViewModel,
                mJavaAdapter,
                mTelephonyManager,
                mTelephonyListenerManager,
                mWakefulnessLifecycle,
                mMainExecutor,
                mBgExecutor,
                mKeyguardUpdateMonitor,
                mLogger
        )
                .setShowAirplaneMode(true)
                .setShowMissingSim(true)
                .build();

        // GIVEN - airplane mode is off (causing CTM to fetch the sticky broadcast)
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 1);
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);
        when(mKeyguardUpdateMonitor.getSimState(0))
                .thenReturn(TelephonyManager.SIM_STATE_NOT_READY);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        // WHEN CTM fetches the broadcast and attempts to log the result, no crash results
        mCarrierTextManager.updateCarrierText();

        // No assert, this test should not crash
    }

    @Test
    public void testCardIOError() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);
        when(mKeyguardUpdateMonitor.getSimState(0)).thenReturn(TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getSimState(1)).thenReturn(
                TelephonyManager.SIM_STATE_CARD_IO_ERROR);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        mCarrierTextManager.mCallback.onSimStateChanged(3, 1,
                TelephonyManager.SIM_STATE_CARD_IO_ERROR);

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertEquals("TEST_CARRIER" + SEPARATOR + INVALID_CARD_TEXT, captor.getValue().carrierText);
        // There's only one subscription in the list
        assertEquals(1, captor.getValue().listOfCarriers.length);
        assertEquals(TEST_CARRIER, captor.getValue().listOfCarriers[0]);

        // Now it becomes single SIM active mode.
        reset(mCarrierTextCallback);
        when(mTelephonyManager.getActiveModemCount()).thenReturn(1);
        // Update carrier text. It should ignore error state of subId 3 in inactive slotId.
        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertEquals("TEST_CARRIER", captor.getValue().carrierText);
    }

    @Test
    public void testWrongSlots() {
        reset(mCarrierTextCallback);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(
                new ArrayList<>());
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_CARD_IO_ERROR);
        // This should not produce an out of bounds error, even though there are no subscriptions
        mCarrierTextManager.mCallback.onSimStateChanged(0, -3,
                TelephonyManager.SIM_STATE_CARD_IO_ERROR);
        mCarrierTextManager.mCallback.onSimStateChanged(0, 3, TelephonyManager.SIM_STATE_READY);
        verify(mCarrierTextCallback, never()).updateCarrierInfo(any());
    }

    @Test
    public void testMoreSlotsThanSubs() {
        reset(mCarrierTextCallback);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(
                new ArrayList<>());

        // STOPSHIP(b/130246708) This line makes sure that SubscriptionManager provides the
        // same answer as KeyguardUpdateMonitor. Remove when this is addressed
        when(mSubscriptionManager.getCompleteActiveSubscriptionInfoList()).thenReturn(
                new ArrayList<>());

        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_CARD_IO_ERROR);
        // This should not produce an out of bounds error, even though there are no subscriptions
        mCarrierTextManager.mCallback.onSimStateChanged(0, 1,
                TelephonyManager.SIM_STATE_CARD_IO_ERROR);

        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(
                any(CarrierTextManager.CarrierTextCallbackInfo.class));
    }

    @Test
    public void testCallback() {
        reset(mCarrierTextCallback);
        mCarrierTextManager.postToCallback(mCarrierTextCallbackInfo);
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertEquals(mCarrierTextCallbackInfo, captor.getValue());
    }

    @Test
    public void testNullingCallback() {
        reset(mCarrierTextCallback);

        mCarrierTextManager.postToCallback(mCarrierTextCallbackInfo);
        mCarrierTextManager.setListening(null);

        // This shouldn't produce NPE
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(any());
    }

    @Test
    public void testCreateInfo_OneValidSubscription() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        CarrierTextManager.CarrierTextCallbackInfo info = captor.getValue();
        assertEquals(1, info.listOfCarriers.length);
        assertEquals(TEST_CARRIER, info.listOfCarriers[0]);
        assertEquals(1, info.subscriptionIds.length);
    }

    @Test
    public void testCreateInfo_OneValidSubscriptionWithRoaming() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION_ROAMING);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        CarrierTextManager.CarrierTextCallbackInfo info = captor.getValue();
        assertEquals(1, info.listOfCarriers.length);
        assertTrue(info.listOfCarriers[0].toString().contains(TEST_CARRIER));
        assertEquals(1, info.subscriptionIds.length);
    }

    @Test
    public void testCarrierText_noTextOnReadySimWhenNull() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION_NULL);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
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
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);

        assertFalse(mWifiRepository.isWifiConnectedWithValidSsid());
        mWifiRepository.setWifiNetwork(
                new WifiNetworkModel.Active(
                        /* networkId= */ 0,
                        /* isValidated= */ false,
                        /* level= */ 0,
                        /* ssid= */ "",
                        /* hotspotDeviceType= */ WifiNetworkModel.HotspotDeviceType.NONE,
                        /* isPasspointAccessPoint= */ false,
                        /* isOnlineSignUpForPasspointAccessPoint= */ false,
                        /* passpointProviderFriendlyName= */ null));
        assertTrue(mWifiRepository.isWifiConnectedWithValidSsid());

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();
        ServiceState ss = mock(ServiceState.class);
        when(ss.getDataRegistrationState()).thenReturn(ServiceState.STATE_IN_SERVICE);
        mKeyguardUpdateMonitor.mServiceStates.put(TEST_SUBSCRIPTION_NULL.getSubscriptionId(), ss);

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        assertFalse("No SIM should be available", captor.getValue().anySimReady);
        // There's no airplane mode if at least one SIM is State.READY and there's wifi
        assertFalse("Device should not be in airplane mode", captor.getValue().airplaneMode);
        assertNotEquals(AIRPLANE_MODE_TEXT, captor.getValue().carrierText);
    }

    @Test
    public void carrierText_satelliteTextNull_isSatelliteFalse_textNotUsed() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        // WHEN the satellite text is null
        mSatelliteViewModel.getCarrierText().setValue(null);
        mTestScope.getTestScheduler().runCurrent();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);

        // THEN satellite mode is false and the default subscription carrier text is used
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertThat(captor.getValue().isInSatelliteMode).isFalse();
        assertThat(captor.getValue().carrierText).isEqualTo(TEST_CARRIER);
    }

    @Test
    public void carrierText_hasSatelliteText_isSatelliteTrue_textUsed() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        // WHEN the satellite text is non-null
        mSatelliteViewModel.getCarrierText().setValue("Satellite Test Text");
        mTestScope.getTestScheduler().runCurrent();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);

        // THEN satellite mode is true and the satellite text is used
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertThat(captor.getValue().isInSatelliteMode).isTrue();
        assertThat(captor.getValue().carrierText).isEqualTo("Satellite Test Text");
    }

    @Test
    public void carrierText_satelliteTextUpdates_autoTriggersCallback() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        // WHEN the satellite text is set
        mSatelliteViewModel.getCarrierText().setValue("Test satellite text");
        mTestScope.getTestScheduler().runCurrent();

        // THEN we should automatically re-trigger #updateCarrierText and get callback info
        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        // AND use the satellite text as the carrier text
        assertThat(captor.getValue().isInSatelliteMode).isTrue();
        assertThat(captor.getValue().carrierText).isEqualTo("Test satellite text");

        // WHEN the satellite text is reset to null
        reset(mCarrierTextCallback);
        mSatelliteViewModel.getCarrierText().setValue(null);
        mTestScope.getTestScheduler().runCurrent();

        // THEN we should automatically re-trigger #updateCarrierText and get callback info
        // that doesn't include the satellite info
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertThat(captor.getValue().isInSatelliteMode).isFalse();
        assertThat(captor.getValue().carrierText).isEqualTo(TEST_CARRIER);
    }

    @Test
    public void carrierText_updatedWhileNotListening_getsNewValueWhenListening() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        mSatelliteViewModel.getCarrierText().setValue("Old satellite text");
        mTestScope.getTestScheduler().runCurrent();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertThat(captor.getValue().carrierText).isEqualTo("Old satellite text");

        // WHEN we stop listening
        reset(mCarrierTextCallback);
        mCarrierTextManager.setListening(null);

        // AND the satellite text updates
        mSatelliteViewModel.getCarrierText().setValue("New satellite text");

        // THEN we don't get new callback info because we aren't listening
        verify(mCarrierTextCallback, never()).updateCarrierInfo(any());

        // WHEN we start listening again
        reset(mCarrierTextCallback);
        mCarrierTextManager.setListening(mCarrierTextCallback);

        // THEN we should automatically re-trigger #updateCarrierText and get callback info
        // that includes the new satellite state and text
        mTestScope.getTestScheduler().runCurrent();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());
        assertThat(captor.getValue().isInSatelliteMode).isTrue();
        assertThat(captor.getValue().carrierText).isEqualTo("New satellite text");
    }

    @Test
    public void testCreateInfo_noSubscriptions() {
        reset(mCarrierTextCallback);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(
                new ArrayList<>());

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        CarrierTextManager.CarrierTextCallbackInfo info = captor.getValue();
        assertEquals(0, info.listOfCarriers.length);
        assertEquals(0, info.subscriptionIds.length);

    }

    @Test
    public void testCarrierText_oneValidSubscription() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        assertThat(captor.getValue().carrierText).isEqualTo(TEST_CARRIER);
    }

    @Test
    public void testCarrierText_twoValidSubscriptions() {
        reset(mCarrierTextCallback);
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        list.add(TEST_SUBSCRIPTION);
        when(mKeyguardUpdateMonitor.getSimState(anyInt())).thenReturn(
                TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
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
                .thenReturn(TelephonyManager.SIM_STATE_READY)
                .thenReturn(TelephonyManager.SIM_STATE_NOT_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
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
                .thenReturn(TelephonyManager.SIM_STATE_NOT_READY)
                .thenReturn(TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);

        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
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
                .thenReturn(TelephonyManager.SIM_STATE_READY)
                .thenReturn(TelephonyManager.SIM_STATE_NOT_READY)
                .thenReturn(TelephonyManager.SIM_STATE_READY);
        when(mKeyguardUpdateMonitor.getFilteredSubscriptionInfo()).thenReturn(list);
        mKeyguardUpdateMonitor.mServiceStates = new HashMap<>();

        ArgumentCaptor<CarrierTextManager.CarrierTextCallbackInfo> captor =
                ArgumentCaptor.forClass(
                        CarrierTextManager.CarrierTextCallbackInfo.class);

        mCarrierTextManager.updateCarrierText();
        FakeExecutor.exhaustExecutors(mMainExecutor, mBgExecutor);
        verify(mCarrierTextCallback).updateCarrierInfo(captor.capture());

        assertEquals(TEST_CARRIER + SEPARATOR + TEST_CARRIER,
                captor.getValue().carrierText);
    }

    @Test
    public void testGetStatusForIccState() {
        when(mKeyguardUpdateMonitor.isDeviceProvisioned()).thenReturn(false);
        assertEquals(CarrierTextManager.StatusMode.SimMissingLocked,
                mCarrierTextManager.getStatusForIccState(TelephonyManager.SIM_STATE_ABSENT));
        assertEquals(CarrierTextManager.StatusMode.NetworkLocked,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_NETWORK_LOCKED));
        assertEquals(CarrierTextManager.StatusMode.SimNotReady,
                mCarrierTextManager.getStatusForIccState(TelephonyManager.SIM_STATE_NOT_READY));
        assertEquals(CarrierTextManager.StatusMode.SimLocked,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_PIN_REQUIRED));
        assertEquals(CarrierTextManager.StatusMode.SimPukLocked,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_PUK_REQUIRED));
        assertEquals(CarrierTextManager.StatusMode.Normal,
                mCarrierTextManager.getStatusForIccState(TelephonyManager.SIM_STATE_READY));
        assertEquals(CarrierTextManager.StatusMode.SimMissingLocked,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_PERM_DISABLED));
        assertEquals(CarrierTextManager.StatusMode.SimUnknown,
                mCarrierTextManager.getStatusForIccState(TelephonyManager.SIM_STATE_UNKNOWN));
        assertEquals(CarrierTextManager.StatusMode.SimIoError,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_CARD_IO_ERROR));
        assertEquals(CarrierTextManager.StatusMode.SimRestricted,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_CARD_RESTRICTED));

        when(mKeyguardUpdateMonitor.isDeviceProvisioned()).thenReturn(true);
        assertEquals(CarrierTextManager.StatusMode.SimMissing,
                mCarrierTextManager.getStatusForIccState(TelephonyManager.SIM_STATE_ABSENT));
        assertEquals(CarrierTextManager.StatusMode.NetworkLocked,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_NETWORK_LOCKED));
        assertEquals(CarrierTextManager.StatusMode.SimNotReady,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_NOT_READY));
        assertEquals(CarrierTextManager.StatusMode.SimLocked,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_PIN_REQUIRED));
        assertEquals(CarrierTextManager.StatusMode.SimPukLocked,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_PUK_REQUIRED));
        assertEquals(CarrierTextManager.StatusMode.Normal,
                mCarrierTextManager.getStatusForIccState(TelephonyManager.SIM_STATE_READY));
        assertEquals(CarrierTextManager.StatusMode.SimPermDisabled,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_PERM_DISABLED));
        assertEquals(CarrierTextManager.StatusMode.SimUnknown,
                mCarrierTextManager.getStatusForIccState(TelephonyManager.SIM_STATE_UNKNOWN));
        assertEquals(CarrierTextManager.StatusMode.SimIoError,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_CARD_IO_ERROR));
        assertEquals(CarrierTextManager.StatusMode.SimRestricted,
                mCarrierTextManager.getStatusForIccState(
                        TelephonyManager.SIM_STATE_CARD_RESTRICTED));
    }

    private Context getContextSpyForStickyBroadcast(Intent returnVal) {
        Context contextSpy = spy(mContext);
        doReturn(returnVal).when(contextSpy).registerReceiver(eq(null), any(IntentFilter.class));
        return contextSpy;
    }
}
