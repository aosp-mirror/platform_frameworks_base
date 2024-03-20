/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vcn;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.vcn.VcnManager.VCN_RESTRICTED_TRANSPORTS_INT_ARRAY_KEY;
import static android.telephony.SubscriptionManager.INVALID_SIM_SLOT_INDEX;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;
import static android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener;
import static android.telephony.TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED;

import static com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import static com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionTrackerCallback;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonMap;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.vcn.VcnManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.os.test.TestLooper;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.CarrierPrivilegesCallback;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.telephony.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tests for TelephonySubscriptionTracker */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class TelephonySubscriptionTrackerTest {
    private static final String PACKAGE_NAME =
            TelephonySubscriptionTrackerTest.class.getPackage().getName();
    private static final ParcelUuid TEST_PARCEL_UUID = new ParcelUuid(UUID.randomUUID());
    private static final int TEST_SIM_SLOT_INDEX = 0;
    private static final int TEST_SUBSCRIPTION_ID_1 = 2;
    private static final SubscriptionInfo TEST_SUBINFO_1 = mock(SubscriptionInfo.class);
    private static final int TEST_SUBSCRIPTION_ID_2 = 3;
    private static final SubscriptionInfo TEST_SUBINFO_2 = mock(SubscriptionInfo.class);
    private static final Map<ParcelUuid, Set<String>> TEST_PRIVILEGED_PACKAGES =
            Collections.singletonMap(TEST_PARCEL_UUID, Collections.singleton(PACKAGE_NAME));
    private static final Map<Integer, SubscriptionInfo> TEST_SUBID_TO_INFO_MAP;

    static {
        final Map<Integer, SubscriptionInfo> subIdToGroupMap = new HashMap<>();
        subIdToGroupMap.put(TEST_SUBSCRIPTION_ID_1, TEST_SUBINFO_1);
        subIdToGroupMap.put(TEST_SUBSCRIPTION_ID_2, TEST_SUBINFO_2);
        TEST_SUBID_TO_INFO_MAP = Collections.unmodifiableMap(subIdToGroupMap);
    }

    private static final String TEST_CARRIER_CONFIG_KEY_1 = "TEST_CARRIER_CONFIG_KEY_1";
    private static final String TEST_CARRIER_CONFIG_KEY_2 = "TEST_CARRIER_CONFIG_KEY_2";
    private static final PersistableBundle TEST_CARRIER_CONFIG = new PersistableBundle();
    private static final PersistableBundleWrapper TEST_CARRIER_CONFIG_WRAPPER;
    private static final Map<Integer, PersistableBundleWrapper> TEST_SUBID_TO_CARRIER_CONFIG_MAP;

    static {
        TEST_CARRIER_CONFIG.putString(
                VcnManager.VCN_NETWORK_SELECTION_WIFI_ENTRY_RSSI_THRESHOLD_KEY,
                VcnManager.VCN_NETWORK_SELECTION_WIFI_ENTRY_RSSI_THRESHOLD_KEY);
        TEST_CARRIER_CONFIG.putString(
                VcnManager.VCN_NETWORK_SELECTION_WIFI_EXIT_RSSI_THRESHOLD_KEY,
                VcnManager.VCN_NETWORK_SELECTION_WIFI_EXIT_RSSI_THRESHOLD_KEY);
        TEST_CARRIER_CONFIG_WRAPPER = new PersistableBundleWrapper(TEST_CARRIER_CONFIG);

        final Map<Integer, PersistableBundleWrapper> subIdToCarrierConfigMap = new HashMap<>();
        subIdToCarrierConfigMap.put(TEST_SUBSCRIPTION_ID_1, TEST_CARRIER_CONFIG_WRAPPER);
        TEST_SUBID_TO_CARRIER_CONFIG_MAP = Collections.unmodifiableMap(subIdToCarrierConfigMap);
    }

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();


    @NonNull private final Context mContext;
    @NonNull private final TestLooper mTestLooper;
    @NonNull private final Handler mHandler;
    @NonNull private final TelephonySubscriptionTracker.Dependencies mDeps;

    @NonNull private final TelephonyManager mTelephonyManager;
    @NonNull private final SubscriptionManager mSubscriptionManager;
    @NonNull private final CarrierConfigManager mCarrierConfigManager;

    @NonNull private TelephonySubscriptionTrackerCallback mCallback;
    @NonNull private TelephonySubscriptionTracker mTelephonySubscriptionTracker;

    @NonNull private CarrierConfigManager.CarrierConfigChangeListener mCarrierConfigChangeListener;

    public TelephonySubscriptionTrackerTest() {
        mContext = mock(Context.class);
        mTestLooper = new TestLooper();
        mHandler = new Handler(mTestLooper.getLooper());
        mDeps = mock(TelephonySubscriptionTracker.Dependencies.class);

        mTelephonyManager = mock(TelephonyManager.class);
        mSubscriptionManager = mock(SubscriptionManager.class);
        mCarrierConfigManager = mock(CarrierConfigManager.class);

        doReturn(Context.TELEPHONY_SERVICE)
                .when(mContext)
                .getSystemServiceName(TelephonyManager.class);
        doReturn(mTelephonyManager).when(mContext).getSystemService(Context.TELEPHONY_SERVICE);

        doReturn(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                .when(mContext)
                .getSystemServiceName(SubscriptionManager.class);
        doReturn(mSubscriptionManager)
                .when(mContext)
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);

        doReturn(Context.CARRIER_CONFIG_SERVICE)
                .when(mContext)
                .getSystemServiceName(CarrierConfigManager.class);
        doReturn(mCarrierConfigManager)
                .when(mContext)
                .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        doReturn(TEST_CARRIER_CONFIG)
                .when(mCarrierConfigManager)
                .getConfigForSubId(eq(TEST_SUBSCRIPTION_ID_1), any());

        // subId 1, 2 are in same subGrp, only subId 1 is active
        doReturn(TEST_PARCEL_UUID).when(TEST_SUBINFO_1).getGroupUuid();
        doReturn(TEST_PARCEL_UUID).when(TEST_SUBINFO_2).getGroupUuid();
        doReturn(TEST_SIM_SLOT_INDEX).when(TEST_SUBINFO_1).getSimSlotIndex();
        doReturn(INVALID_SIM_SLOT_INDEX).when(TEST_SUBINFO_2).getSimSlotIndex();
        doReturn(TEST_SUBSCRIPTION_ID_1).when(TEST_SUBINFO_1).getSubscriptionId();
        doReturn(TEST_SUBSCRIPTION_ID_2).when(TEST_SUBINFO_2).getSubscriptionId();
    }

    @Before
    public void setUp() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_FIX_CRASH_ON_GETTING_CONFIG_WHEN_PHONE_IS_GONE);
        doReturn(2).when(mTelephonyManager).getActiveModemCount();

        mCallback = mock(TelephonySubscriptionTrackerCallback.class);
        // Capture CarrierConfigChangeListener to emulate the carrier config change notification
        ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(CarrierConfigManager.CarrierConfigChangeListener.class);
        mTelephonySubscriptionTracker =
                new TelephonySubscriptionTracker(mContext, mHandler, mCallback, mDeps);
        mTelephonySubscriptionTracker.register();
        verify(mCarrierConfigManager).registerCarrierConfigChangeListener(any(),
                listenerArgumentCaptor.capture());
        mCarrierConfigChangeListener = listenerArgumentCaptor.getAllValues().get(0);

        doReturn(true).when(mDeps).isConfigForIdentifiedCarrier(any());
        doReturn(Arrays.asList(TEST_SUBINFO_1, TEST_SUBINFO_2))
                .when(mSubscriptionManager)
                .getAllSubscriptionInfoList();

        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        setPrivilegedPackagesForMock(Collections.singletonList(PACKAGE_NAME));
    }

    private IntentFilter getIntentFilter() {
        final ArgumentCaptor<IntentFilter> captor = ArgumentCaptor.forClass(IntentFilter.class);
        verify(mContext).registerReceiver(any(), captor.capture(), any(), any());

        return captor.getValue();
    }

    private OnSubscriptionsChangedListener getOnSubscriptionsChangedListener() {
        final ArgumentCaptor<OnSubscriptionsChangedListener> captor =
                ArgumentCaptor.forClass(OnSubscriptionsChangedListener.class);
        verify(mSubscriptionManager).addOnSubscriptionsChangedListener(any(), captor.capture());

        return captor.getValue();
    }

    private List<CarrierPrivilegesCallback> getCarrierPrivilegesCallbacks() {
        final ArgumentCaptor<CarrierPrivilegesCallback> captor =
                ArgumentCaptor.forClass(CarrierPrivilegesCallback.class);
        verify(mTelephonyManager, atLeastOnce())
                .registerCarrierPrivilegesCallback(anyInt(), any(), captor.capture());

        return captor.getAllValues();
    }

    private ActiveDataSubscriptionIdListener getActiveDataSubscriptionIdListener() {
        final ArgumentCaptor<TelephonyCallback> captor =
                ArgumentCaptor.forClass(TelephonyCallback.class);
        verify(mTelephonyManager).registerTelephonyCallback(any(), captor.capture());

        return (ActiveDataSubscriptionIdListener) captor.getValue();
    }

    private Intent buildTestMultiSimConfigBroadcastIntent() {
        Intent intent = new Intent(ACTION_MULTI_SIM_CONFIG_CHANGED);
        return intent;
    }

    private void sendCarrierConfigChange(boolean hasValidSubscription) {
        mCarrierConfigChangeListener.onCarrierConfigChanged(
                TEST_SIM_SLOT_INDEX,
                hasValidSubscription ? TEST_SUBSCRIPTION_ID_1 : INVALID_SUBSCRIPTION_ID,
                TelephonyManager.UNKNOWN_CARRIER_ID, TelephonyManager.UNKNOWN_CARRIER_ID);
    }

    private TelephonySubscriptionSnapshot buildExpectedSnapshot(
            Map<ParcelUuid, Set<String>> privilegedPackages) {
        return buildExpectedSnapshot(TEST_SUBID_TO_INFO_MAP, privilegedPackages);
    }

    private TelephonySubscriptionSnapshot buildExpectedSnapshot(
            Map<Integer, SubscriptionInfo> subIdToInfoMap,
            Map<ParcelUuid, Set<String>> privilegedPackages) {
        return buildExpectedSnapshot(0, subIdToInfoMap, privilegedPackages);
    }

    private TelephonySubscriptionSnapshot buildExpectedSnapshot(
            int activeSubId,
            Map<Integer, SubscriptionInfo> subIdToInfoMap,
            Map<ParcelUuid, Set<String>> privilegedPackages) {
        return buildExpectedSnapshot(
                activeSubId, subIdToInfoMap, TEST_SUBID_TO_CARRIER_CONFIG_MAP, privilegedPackages);
    }

    private TelephonySubscriptionSnapshot buildExpectedSnapshot(
            int activeSubId,
            Map<Integer, SubscriptionInfo> subIdToInfoMap,
            Map<Integer, PersistableBundleWrapper> subIdToCarrierConfigMap,
            Map<ParcelUuid, Set<String>> privilegedPackages) {
        return new TelephonySubscriptionSnapshot(
                activeSubId, subIdToInfoMap, subIdToCarrierConfigMap, privilegedPackages);
    }

    private void verifyNoActiveSubscriptions() {
        verify(mCallback).onNewSnapshot(
                argThat(snapshot -> snapshot.getActiveSubscriptionGroups().isEmpty()));
    }

    private void setupReadySubIds() {
        mTelephonySubscriptionTracker.setReadySubIdsBySlotId(
                Collections.singletonMap(TEST_SIM_SLOT_INDEX, TEST_SUBSCRIPTION_ID_1));
        mTelephonySubscriptionTracker.setSubIdToCarrierConfigMap(
                Collections.singletonMap(TEST_SUBSCRIPTION_ID_1, TEST_CARRIER_CONFIG_WRAPPER));
    }

    private void setPrivilegedPackagesForMock(@NonNull List<String> privilegedPackages) {
        doReturn(privilegedPackages).when(mTelephonyManager).getPackagesWithCarrierPrivileges();
    }

    @Test
    public void testRegister() throws Exception {
        verify(mContext)
                .registerReceiver(
                        eq(mTelephonySubscriptionTracker),
                        any(IntentFilter.class),
                        any(),
                        eq(mHandler));
        final IntentFilter filter = getIntentFilter();
        assertEquals(1, filter.countActions());
        assertTrue(filter.hasAction(ACTION_MULTI_SIM_CONFIG_CHANGED));

        verify(mSubscriptionManager)
                .addOnSubscriptionsChangedListener(any(HandlerExecutor.class), any());
        assertNotNull(getOnSubscriptionsChangedListener());

        verify(mCarrierConfigManager).registerCarrierConfigChangeListener(any(), any());

        verify(mTelephonyManager, times(2))
                .registerCarrierPrivilegesCallback(anyInt(), any(HandlerExecutor.class), any());
        verify(mTelephonyManager)
                .registerCarrierPrivilegesCallback(eq(0), any(HandlerExecutor.class), any());
        verify(mTelephonyManager)
                .registerCarrierPrivilegesCallback(eq(1), any(HandlerExecutor.class), any());
        assertEquals(2, getCarrierPrivilegesCallbacks().size());
    }

    @Test
    public void testUnregister() throws Exception {
        mTelephonySubscriptionTracker.unregister();

        verify(mContext).unregisterReceiver(eq(mTelephonySubscriptionTracker));

        final OnSubscriptionsChangedListener listener = getOnSubscriptionsChangedListener();
        verify(mSubscriptionManager).removeOnSubscriptionsChangedListener(eq(listener));

        for (CarrierPrivilegesCallback carrierPrivilegesCallback :
                getCarrierPrivilegesCallbacks()) {
            verify(mTelephonyManager)
                    .unregisterCarrierPrivilegesCallback(eq(carrierPrivilegesCallback));
        }
    }

    @Test
    public void testMultiSimConfigChanged() throws Exception {
        final ArrayMap<Integer, Integer> readySubIdsBySlotId = new ArrayMap<>();
        readySubIdsBySlotId.put(TEST_SIM_SLOT_INDEX, TEST_SUBSCRIPTION_ID_1);
        readySubIdsBySlotId.put(TEST_SIM_SLOT_INDEX + 1, TEST_SUBSCRIPTION_ID_1);

        mTelephonySubscriptionTracker.setReadySubIdsBySlotId(readySubIdsBySlotId);
        mTelephonySubscriptionTracker.setSubIdToCarrierConfigMap(TEST_SUBID_TO_CARRIER_CONFIG_MAP);
        doReturn(1).when(mTelephonyManager).getActiveModemCount();

        List<CarrierPrivilegesCallback> carrierPrivilegesCallbacks =
                getCarrierPrivilegesCallbacks();

        mTelephonySubscriptionTracker.onReceive(mContext, buildTestMultiSimConfigBroadcastIntent());
        mTestLooper.dispatchAll();

        for (CarrierPrivilegesCallback carrierPrivilegesCallback : carrierPrivilegesCallbacks) {
            verify(mTelephonyManager)
                    .unregisterCarrierPrivilegesCallback(eq(carrierPrivilegesCallback));
        }

        // Expect cache cleared for inactive slots.
        assertNull(
                mTelephonySubscriptionTracker
                        .getReadySubIdsBySlotId()
                        .get(TEST_SIM_SLOT_INDEX + 1));

        // Expect a new CarrierPrivilegesListener to have been registered for slot 0, and none other
        // (2 previously registered during startup, for slots 0 & 1)
        verify(mTelephonyManager, times(3))
                .registerCarrierPrivilegesCallback(anyInt(), any(HandlerExecutor.class), any());
        verify(mTelephonyManager, times(2))
                .registerCarrierPrivilegesCallback(eq(0), any(HandlerExecutor.class), any());

        // Verify that this triggers a re-evaluation
        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(TEST_PRIVILEGED_PACKAGES)));
    }

    @Test
    public void testOnSubscriptionsChangedFired_NoReadySubIds() throws Exception {
        final OnSubscriptionsChangedListener listener = getOnSubscriptionsChangedListener();
        listener.onSubscriptionsChanged();
        mTestLooper.dispatchAll();

        verifyNoActiveSubscriptions();
    }

    @Test
    public void testOnSubscriptionsChangedFired_onActiveSubIdsChanged() throws Exception {
        setupReadySubIds();
        setPrivilegedPackagesForMock(Collections.emptyList());

        doReturn(TEST_SUBSCRIPTION_ID_2).when(mDeps).getActiveDataSubscriptionId();
        final ActiveDataSubscriptionIdListener listener = getActiveDataSubscriptionIdListener();
        listener.onActiveDataSubscriptionIdChanged(TEST_SUBSCRIPTION_ID_2);
        mTestLooper.dispatchAll();

        ArgumentCaptor<TelephonySubscriptionSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(TelephonySubscriptionSnapshot.class);
        verify(mCallback).onNewSnapshot(snapshotCaptor.capture());

        TelephonySubscriptionSnapshot snapshot = snapshotCaptor.getValue();
        assertNotNull(snapshot);
        assertEquals(TEST_SUBSCRIPTION_ID_2, snapshot.getActiveDataSubscriptionId());
        assertEquals(TEST_PARCEL_UUID, snapshot.getActiveDataSubscriptionGroup());
    }

    @Test
    public void testOnSubscriptionsChangedFired_WithReadySubidsNoPrivilegedPackages()
            throws Exception {
        setupReadySubIds();
        setPrivilegedPackagesForMock(Collections.emptyList());

        final OnSubscriptionsChangedListener listener = getOnSubscriptionsChangedListener();
        listener.onSubscriptionsChanged();
        mTestLooper.dispatchAll();

        final Map<ParcelUuid, Set<String>> privilegedPackages =
                Collections.singletonMap(TEST_PARCEL_UUID, new ArraySet<>());
        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(privilegedPackages)));
    }

    @Test
    public void testOnSubscriptionsChangedFired_WithReadySubidsAndPrivilegedPackages()
            throws Exception {
        setupReadySubIds();

        final OnSubscriptionsChangedListener listener = getOnSubscriptionsChangedListener();
        listener.onSubscriptionsChanged();
        mTestLooper.dispatchAll();

        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(TEST_PRIVILEGED_PACKAGES)));
    }

    @Test
    public void testOnCarrierPrivilegesChanged() throws Exception {
        setupReadySubIds();

        final CarrierPrivilegesCallback callback = getCarrierPrivilegesCallbacks().get(0);
        callback.onCarrierPrivilegesChanged(Collections.emptySet(), Collections.emptySet());
        mTestLooper.dispatchAll();

        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(TEST_PRIVILEGED_PACKAGES)));
    }

    @Test
    public void testReceiveBroadcast_ConfigReadyWithSubscriptions() throws Exception {
        sendCarrierConfigChange(true /* hasValidSubscription */);
        mTestLooper.dispatchAll();

        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(TEST_PRIVILEGED_PACKAGES)));
    }

    @Test
    public void testReceiveBroadcast_ConfigReadyNoSubscriptions() throws Exception {
        doReturn(new ArrayList<SubscriptionInfo>())
                .when(mSubscriptionManager)
                .getAllSubscriptionInfoList();

        sendCarrierConfigChange(true /* hasValidSubscription */);
        mTestLooper.dispatchAll();

        // Expect an empty snapshot
        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(emptyMap(), emptyMap())));
    }

    @Test
    public void testReceiveBroadcast_SlotCleared() throws Exception {
        setupReadySubIds();

        sendCarrierConfigChange(false /* hasValidSubscription */);
        mTestLooper.dispatchAll();

        verifyNoActiveSubscriptions();
        assertTrue(mTelephonySubscriptionTracker.getReadySubIdsBySlotId().isEmpty());
    }

    @Test
    public void testReceiveBroadcast_ConfigNotReady() throws Exception {
        doReturn(false).when(mDeps).isConfigForIdentifiedCarrier(any());

        sendCarrierConfigChange(true /* hasValidSubscription */);
        mTestLooper.dispatchAll();

        // No interactions expected; config was not loaded
        verifyNoMoreInteractions(mCallback);
    }

    @Test
    public void testSubscriptionsClearedAfterValidTriggersCallbacks() throws Exception {
        sendCarrierConfigChange(true /* hasValidSubscription */);
        mTestLooper.dispatchAll();
        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(TEST_PRIVILEGED_PACKAGES)));
        assertNotNull(
                mTelephonySubscriptionTracker.getReadySubIdsBySlotId().get(TEST_SIM_SLOT_INDEX));

        doReturn(Collections.emptyList()).when(mSubscriptionManager).getAllSubscriptionInfoList();
        sendCarrierConfigChange(true /* hasValidSubscription */);
        mTestLooper.dispatchAll();
        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(emptyMap(), emptyMap())));
    }

    @Test
    public void testCarrierConfigUpdatedAfterValidTriggersCallbacks() throws Exception {
        sendCarrierConfigChange(true /* hasValidSubscription */);
        mTestLooper.dispatchAll();
        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(TEST_PRIVILEGED_PACKAGES)));
        reset(mCallback);

        final PersistableBundle updatedConfig = new PersistableBundle();
        updatedConfig.putIntArray(
                VCN_RESTRICTED_TRANSPORTS_INT_ARRAY_KEY,
                new int[] {TRANSPORT_WIFI, TRANSPORT_CELLULAR});
        doReturn(updatedConfig)
                .when(mCarrierConfigManager)
                .getConfigForSubId(eq(TEST_SUBSCRIPTION_ID_1), any());

        Map<Integer, PersistableBundleWrapper> subIdToCarrierConfigMap = new HashMap<>();
        subIdToCarrierConfigMap.put(
                TEST_SUBSCRIPTION_ID_1, new PersistableBundleWrapper(updatedConfig));
        sendCarrierConfigChange(true /* hasValidSubscription */);
        mTestLooper.dispatchAll();

        verify(mCallback)
                .onNewSnapshot(
                        eq(
                                buildExpectedSnapshot(
                                        0,
                                        TEST_SUBID_TO_INFO_MAP,
                                        subIdToCarrierConfigMap,
                                        TEST_PRIVILEGED_PACKAGES)));
    }

    @Test
    public void testSlotClearedAfterValidTriggersCallbacks() throws Exception {
        sendCarrierConfigChange(true /* hasValidSubscription */);
        mTestLooper.dispatchAll();
        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(TEST_PRIVILEGED_PACKAGES)));
        assertNotNull(
                mTelephonySubscriptionTracker.getReadySubIdsBySlotId().get(TEST_SIM_SLOT_INDEX));

        sendCarrierConfigChange(false /* hasValidSubscription */);
        mTestLooper.dispatchAll();
        verify(mCallback)
                .onNewSnapshot(
                        eq(
                                buildExpectedSnapshot(
                                        0, TEST_SUBID_TO_INFO_MAP, emptyMap(), emptyMap())));
        assertNull(mTelephonySubscriptionTracker.getReadySubIdsBySlotId().get(TEST_SIM_SLOT_INDEX));
        assertNull(
                mTelephonySubscriptionTracker
                        .getSubIdToCarrierConfigMap()
                        .get(TEST_SUBSCRIPTION_ID_1));
    }

    @Test
    public void testChangingPrivilegedPackagesAfterValidTriggersCallbacks() throws Exception {
        setupReadySubIds();

        // Setup initial "valid" state
        final OnSubscriptionsChangedListener listener = getOnSubscriptionsChangedListener();
        listener.onSubscriptionsChanged();
        mTestLooper.dispatchAll();

        verify(mCallback).onNewSnapshot(eq(buildExpectedSnapshot(TEST_PRIVILEGED_PACKAGES)));

        // Simulate a loss of carrier privileges
        setPrivilegedPackagesForMock(Collections.emptyList());
        listener.onSubscriptionsChanged();
        mTestLooper.dispatchAll();

        verify(mCallback)
                .onNewSnapshot(
                        eq(buildExpectedSnapshot(singletonMap(TEST_PARCEL_UUID, emptySet()))));
    }

    @Test
    public void testTelephonySubscriptionSnapshotGetGroupForSubId() throws Exception {
        final TelephonySubscriptionSnapshot snapshot =
                new TelephonySubscriptionSnapshot(
                        TEST_SUBSCRIPTION_ID_1, TEST_SUBID_TO_INFO_MAP, emptyMap(), emptyMap());

        assertEquals(TEST_PARCEL_UUID, snapshot.getGroupForSubId(TEST_SUBSCRIPTION_ID_1));
        assertEquals(TEST_PARCEL_UUID, snapshot.getGroupForSubId(TEST_SUBSCRIPTION_ID_2));
    }

    @Test
    public void testTelephonySubscriptionSnapshotGetAllSubIdsInGroup() throws Exception {
        final TelephonySubscriptionSnapshot snapshot =
                new TelephonySubscriptionSnapshot(
                        TEST_SUBSCRIPTION_ID_1, TEST_SUBID_TO_INFO_MAP, emptyMap(), emptyMap());

        assertEquals(
                new ArraySet<>(Arrays.asList(TEST_SUBSCRIPTION_ID_1, TEST_SUBSCRIPTION_ID_2)),
                snapshot.getAllSubIdsInGroup(TEST_PARCEL_UUID));
    }

    @Test
    public void testCarrierConfigChangeWhenPhoneIsGoneShouldNotCrash() throws Exception {
        doThrow(new IllegalStateException("Carrier config loader is not available."))
                .when(mCarrierConfigManager)
                .getConfigForSubId(eq(TEST_SUBSCRIPTION_ID_1), any());

        sendCarrierConfigChange(true /* hasValidSubscription */);
        mTestLooper.dispatchAll();
    }
}
