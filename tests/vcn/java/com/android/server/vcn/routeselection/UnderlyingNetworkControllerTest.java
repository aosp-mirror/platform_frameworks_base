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

package com.android.server.vcn.routeselection;

import static com.android.server.vcn.VcnTestUtils.setupSystemService;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.WIFI_EXIT_RSSI_THRESHOLD_DEFAULT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.os.ParcelUuid;
import android.os.test.TestLooper;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.VcnContext;
import com.android.server.vcn.VcnNetworkProvider;
import com.android.server.vcn.routeselection.UnderlyingNetworkController.NetworkBringupCallback;
import com.android.server.vcn.routeselection.UnderlyingNetworkController.UnderlyingNetworkControllerCallback;
import com.android.server.vcn.routeselection.UnderlyingNetworkController.UnderlyingNetworkListener;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

public class UnderlyingNetworkControllerTest {
    private static final ParcelUuid SUB_GROUP = new ParcelUuid(new UUID(0, 0));
    private static final int INITIAL_SUB_ID_1 = 1;
    private static final int INITIAL_SUB_ID_2 = 2;
    private static final int UPDATED_SUB_ID = 3;

    private static final Set<Integer> INITIAL_SUB_IDS =
            new ArraySet<>(Arrays.asList(INITIAL_SUB_ID_1, INITIAL_SUB_ID_2));
    private static final Set<Integer> UPDATED_SUB_IDS =
            new ArraySet<>(Arrays.asList(UPDATED_SUB_ID));

    private static final NetworkCapabilities INITIAL_NETWORK_CAPABILITIES =
            new NetworkCapabilities.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    .build();
    private static final NetworkCapabilities SUSPENDED_NETWORK_CAPABILITIES =
            new NetworkCapabilities.Builder(INITIAL_NETWORK_CAPABILITIES)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                    .build();
    private static final NetworkCapabilities UPDATED_NETWORK_CAPABILITIES =
            new NetworkCapabilities.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build();

    private static final LinkProperties INITIAL_LINK_PROPERTIES =
            getLinkPropertiesWithName("initial_iface");
    private static final LinkProperties UPDATED_LINK_PROPERTIES =
            getLinkPropertiesWithName("updated_iface");

    @Mock private Context mContext;
    @Mock private VcnNetworkProvider mVcnNetworkProvider;
    @Mock private ConnectivityManager mConnectivityManager;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private CarrierConfigManager mCarrierConfigManager;
    @Mock private TelephonySubscriptionSnapshot mSubscriptionSnapshot;
    @Mock private UnderlyingNetworkControllerCallback mNetworkControllerCb;
    @Mock private Network mNetwork;

    @Captor private ArgumentCaptor<UnderlyingNetworkListener> mUnderlyingNetworkListenerCaptor;

    private TestLooper mTestLooper;
    private VcnContext mVcnContext;
    private UnderlyingNetworkController mUnderlyingNetworkController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestLooper = new TestLooper();
        mVcnContext =
                spy(
                        new VcnContext(
                                mContext,
                                mTestLooper.getLooper(),
                                mVcnNetworkProvider,
                                false /* isInTestMode */));
        resetVcnContext();

        setupSystemService(
                mContext,
                mConnectivityManager,
                Context.CONNECTIVITY_SERVICE,
                ConnectivityManager.class);
        setupSystemService(
                mContext, mTelephonyManager, Context.TELEPHONY_SERVICE, TelephonyManager.class);
        setupSystemService(
                mContext,
                mCarrierConfigManager,
                Context.CARRIER_CONFIG_SERVICE,
                CarrierConfigManager.class);

        when(mSubscriptionSnapshot.getAllSubIdsInGroup(eq(SUB_GROUP))).thenReturn(INITIAL_SUB_IDS);

        mUnderlyingNetworkController =
                new UnderlyingNetworkController(
                        mVcnContext,
                        VcnGatewayConnectionConfigTest.buildTestConfig(),
                        SUB_GROUP,
                        mSubscriptionSnapshot,
                        mNetworkControllerCb);
    }

    private void resetVcnContext() {
        reset(mVcnContext);
        doNothing().when(mVcnContext).ensureRunningOnLooperThread();
    }

    // Package private for use in NetworkPriorityClassifierTest
    static LinkProperties getLinkPropertiesWithName(String iface) {
        LinkProperties linkProperties = new LinkProperties();
        linkProperties.setInterfaceName(iface);
        return linkProperties;
    }

    private SubscriptionInfo getSubscriptionInfoForSubId(int subId) {
        SubscriptionInfo subInfo = mock(SubscriptionInfo.class);
        when(subInfo.getSubscriptionId()).thenReturn(subId);
        return subInfo;
    }

    @Test
    public void testNetworkCallbacksRegisteredOnStartup() {
        verifyNetworkRequestsRegistered(INITIAL_SUB_IDS);
    }

    @Test
    public void testNetworkCallbacksRegisteredOnStartupForTestMode() {
        final ConnectivityManager cm = mock(ConnectivityManager.class);
        setupSystemService(mContext, cm, Context.CONNECTIVITY_SERVICE, ConnectivityManager.class);
        final VcnContext vcnContext =
                new VcnContext(
                        mContext,
                        mTestLooper.getLooper(),
                        mVcnNetworkProvider,
                        true /* isInTestMode */);

        new UnderlyingNetworkController(
                vcnContext,
                VcnGatewayConnectionConfigTest.buildTestConfig(),
                SUB_GROUP,
                mSubscriptionSnapshot,
                mNetworkControllerCb);

        verify(cm)
                .registerNetworkCallback(
                        eq(getTestNetworkRequest(INITIAL_SUB_IDS)),
                        any(UnderlyingNetworkListener.class),
                        any());
    }

    private void verifyNetworkRequestsRegistered(Set<Integer> expectedSubIds) {
        verify(mConnectivityManager)
                .requestBackgroundNetwork(
                        eq(getWifiRequest(expectedSubIds)),
                        any(NetworkBringupCallback.class),
                        any());
        for (final int subId : expectedSubIds) {
            verify(mConnectivityManager)
                    .requestBackgroundNetwork(
                            eq(getCellRequestForSubId(subId)),
                            any(NetworkBringupCallback.class), any());
        }

        verify(mConnectivityManager)
                .registerNetworkCallback(
                        eq(getRouteSelectionRequest(expectedSubIds)),
                        any(UnderlyingNetworkListener.class),
                        any());
        verify(mConnectivityManager)
                .registerNetworkCallback(
                        eq(getWifiEntryRssiThresholdRequest(expectedSubIds)),
                        any(NetworkBringupCallback.class),
                        any());
        verify(mConnectivityManager)
                .registerNetworkCallback(
                        eq(getWifiExitRssiThresholdRequest(expectedSubIds)),
                        any(NetworkBringupCallback.class),
                        any());
    }

    @Test
    public void testUpdateSubscriptionSnapshot() {
        // Verify initial cell background requests filed
        verifyNetworkRequestsRegistered(INITIAL_SUB_IDS);

        TelephonySubscriptionSnapshot subscriptionUpdate =
                mock(TelephonySubscriptionSnapshot.class);
        when(subscriptionUpdate.getAllSubIdsInGroup(eq(SUB_GROUP))).thenReturn(UPDATED_SUB_IDS);

        mUnderlyingNetworkController.updateSubscriptionSnapshot(subscriptionUpdate);

        // verify that initially-filed bringup requests are unregistered (cell + wifi)
        verify(mConnectivityManager, times(INITIAL_SUB_IDS.size() + 3))
                .unregisterNetworkCallback(any(NetworkBringupCallback.class));
        verify(mConnectivityManager)
                .unregisterNetworkCallback(any(UnderlyingNetworkListener.class));
        verifyNetworkRequestsRegistered(UPDATED_SUB_IDS);
    }

    private NetworkRequest getWifiRequest(Set<Integer> netCapsSubIds) {
        return getExpectedRequestBase()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setSubscriptionIds(netCapsSubIds)
                .build();
    }

    private NetworkRequest getWifiEntryRssiThresholdRequest(Set<Integer> netCapsSubIds) {
        // TODO (b/187991063): Add tests for carrier-config based thresholds
        return getExpectedRequestBase()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setSubscriptionIds(netCapsSubIds)
                .setSignalStrength(WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT)
                .build();
    }

    private NetworkRequest getWifiExitRssiThresholdRequest(Set<Integer> netCapsSubIds) {
        // TODO (b/187991063): Add tests for carrier-config based thresholds
        return getExpectedRequestBase()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setSubscriptionIds(netCapsSubIds)
                .setSignalStrength(WIFI_EXIT_RSSI_THRESHOLD_DEFAULT)
                .build();
    }

    private NetworkRequest getCellRequestForSubId(int subId) {
        return getExpectedRequestBase()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .setNetworkSpecifier(new TelephonyNetworkSpecifier(subId))
                .build();
    }

    private NetworkRequest getRouteSelectionRequest(Set<Integer> netCapsSubIds) {
        return getExpectedRequestBase()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                .setSubscriptionIds(netCapsSubIds)
                .build();
    }

    private NetworkRequest getTestNetworkRequest(Set<Integer> netCapsSubIds) {
        return new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                .setSubscriptionIds(netCapsSubIds)
                .build();
    }

    private NetworkRequest.Builder getExpectedRequestBase() {
        final NetworkRequest.Builder builder =
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED);

        return builder;
    }

    @Test
    public void testTeardown() {
        mUnderlyingNetworkController.teardown();

        // Expect 5 NetworkBringupCallbacks to be unregistered: 1 for WiFi, 2 for Cellular (1x for
        // each subId), and 1 for each of the Wifi signal strength thresholds
        verify(mConnectivityManager, times(5))
                .unregisterNetworkCallback(any(NetworkBringupCallback.class));
        verify(mConnectivityManager)
                .unregisterNetworkCallback(any(UnderlyingNetworkListener.class));
    }

    @Test
    public void testUnderlyingNetworkRecordEquals() {
        UnderlyingNetworkRecord recordA =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        INITIAL_NETWORK_CAPABILITIES,
                        INITIAL_LINK_PROPERTIES,
                        false /* isBlocked */);
        UnderlyingNetworkRecord recordB =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        INITIAL_NETWORK_CAPABILITIES,
                        INITIAL_LINK_PROPERTIES,
                        false /* isBlocked */);
        UnderlyingNetworkRecord recordC =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        UPDATED_NETWORK_CAPABILITIES,
                        UPDATED_LINK_PROPERTIES,
                        false /* isBlocked */);

        assertEquals(recordA, recordB);
        assertNotEquals(recordA, recordC);
    }

    @Test
    public void testRecordTrackerCallbackNotifiedForNetworkChange() {
        verifyRegistrationOnAvailableAndGetCallback();
    }

    private UnderlyingNetworkListener verifyRegistrationOnAvailableAndGetCallback() {
        return verifyRegistrationOnAvailableAndGetCallback(INITIAL_NETWORK_CAPABILITIES);
    }

    private static NetworkCapabilities buildResponseNwCaps(
            NetworkCapabilities requestNetworkCaps, Set<Integer> netCapsSubIds) {
        final TelephonyNetworkSpecifier telephonyNetworkSpecifier =
                new TelephonyNetworkSpecifier.Builder()
                        .setSubscriptionId(netCapsSubIds.iterator().next())
                        .build();
        return new NetworkCapabilities.Builder(requestNetworkCaps)
                .setNetworkSpecifier(telephonyNetworkSpecifier)
                .build();
    }

    private UnderlyingNetworkListener verifyRegistrationOnAvailableAndGetCallback(
            NetworkCapabilities networkCapabilities) {
        verify(mConnectivityManager)
                .registerNetworkCallback(
                        eq(getRouteSelectionRequest(INITIAL_SUB_IDS)),
                        mUnderlyingNetworkListenerCaptor.capture(),
                        any());

        UnderlyingNetworkListener cb = mUnderlyingNetworkListenerCaptor.getValue();
        cb.onAvailable(mNetwork);

        final NetworkCapabilities responseNetworkCaps =
                buildResponseNwCaps(networkCapabilities, INITIAL_SUB_IDS);
        cb.onCapabilitiesChanged(mNetwork, responseNetworkCaps);
        cb.onLinkPropertiesChanged(mNetwork, INITIAL_LINK_PROPERTIES);
        cb.onBlockedStatusChanged(mNetwork, false /* isFalse */);

        UnderlyingNetworkRecord expectedRecord =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        responseNetworkCaps,
                        INITIAL_LINK_PROPERTIES,
                        false /* isBlocked */);
        verify(mNetworkControllerCb).onSelectedUnderlyingNetworkChanged(eq(expectedRecord));
        return cb;
    }

    @Test
    public void testRecordTrackerCallbackNotifiedForNetworkCapabilitiesChange() {
        UnderlyingNetworkListener cb = verifyRegistrationOnAvailableAndGetCallback();

        final NetworkCapabilities responseNetworkCaps =
                buildResponseNwCaps(UPDATED_NETWORK_CAPABILITIES, UPDATED_SUB_IDS);
        cb.onCapabilitiesChanged(mNetwork, responseNetworkCaps);

        UnderlyingNetworkRecord expectedRecord =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        responseNetworkCaps,
                        INITIAL_LINK_PROPERTIES,
                        false /* isBlocked */);
        verify(mNetworkControllerCb).onSelectedUnderlyingNetworkChanged(eq(expectedRecord));
    }

    @Test
    public void testRecordTrackerCallbackNotifiedForLinkPropertiesChange() {
        UnderlyingNetworkListener cb = verifyRegistrationOnAvailableAndGetCallback();

        cb.onLinkPropertiesChanged(mNetwork, UPDATED_LINK_PROPERTIES);

        UnderlyingNetworkRecord expectedRecord =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        buildResponseNwCaps(INITIAL_NETWORK_CAPABILITIES, INITIAL_SUB_IDS),
                        UPDATED_LINK_PROPERTIES,
                        false /* isBlocked */);
        verify(mNetworkControllerCb).onSelectedUnderlyingNetworkChanged(eq(expectedRecord));
    }

    @Test
    public void testRecordTrackerCallbackNotifiedForNetworkSuspended() {
        UnderlyingNetworkListener cb = verifyRegistrationOnAvailableAndGetCallback();

        final NetworkCapabilities responseNetworkCaps =
                buildResponseNwCaps(SUSPENDED_NETWORK_CAPABILITIES, UPDATED_SUB_IDS);
        cb.onCapabilitiesChanged(mNetwork, responseNetworkCaps);

        UnderlyingNetworkRecord expectedRecord =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        responseNetworkCaps,
                        INITIAL_LINK_PROPERTIES,
                        false /* isBlocked */);
        verify(mNetworkControllerCb, times(1))
                .onSelectedUnderlyingNetworkChanged(eq(expectedRecord));
        // onSelectedUnderlyingNetworkChanged() won't be fired twice if network capabilities doesn't
        // change.
        cb.onCapabilitiesChanged(mNetwork, responseNetworkCaps);
        verify(mNetworkControllerCb, times(1))
                .onSelectedUnderlyingNetworkChanged(eq(expectedRecord));
    }

    @Test
    public void testRecordTrackerCallbackNotifiedForNetworkResumed() {
        UnderlyingNetworkListener cb =
                verifyRegistrationOnAvailableAndGetCallback(SUSPENDED_NETWORK_CAPABILITIES);

        final NetworkCapabilities responseNetworkCaps =
                buildResponseNwCaps(INITIAL_NETWORK_CAPABILITIES, INITIAL_SUB_IDS);
        cb.onCapabilitiesChanged(mNetwork, responseNetworkCaps);

        UnderlyingNetworkRecord expectedRecord =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        responseNetworkCaps,
                        INITIAL_LINK_PROPERTIES,
                        false /* isBlocked */);
        verify(mNetworkControllerCb, times(1))
                .onSelectedUnderlyingNetworkChanged(eq(expectedRecord));
        // onSelectedUnderlyingNetworkChanged() won't be fired twice if network capabilities doesn't
        // change.
        cb.onCapabilitiesChanged(mNetwork, responseNetworkCaps);
        verify(mNetworkControllerCb, times(1))
                .onSelectedUnderlyingNetworkChanged(eq(expectedRecord));
    }

    @Test
    public void testRecordTrackerCallbackNotifiedForBlocked() {
        UnderlyingNetworkListener cb = verifyRegistrationOnAvailableAndGetCallback();

        cb.onBlockedStatusChanged(mNetwork, true /* isBlocked */);

        UnderlyingNetworkRecord expectedRecord =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        buildResponseNwCaps(INITIAL_NETWORK_CAPABILITIES, INITIAL_SUB_IDS),
                        INITIAL_LINK_PROPERTIES,
                        true /* isBlocked */);
        verify(mNetworkControllerCb).onSelectedUnderlyingNetworkChanged(eq(expectedRecord));
    }

    @Test
    public void testRecordTrackerCallbackNotifiedForNetworkLoss() {
        UnderlyingNetworkListener cb = verifyRegistrationOnAvailableAndGetCallback();

        cb.onLost(mNetwork);

        verify(mNetworkControllerCb).onSelectedUnderlyingNetworkChanged(null);
    }

    @Test
    public void testRecordTrackerCallbackIgnoresDuplicateRecord() {
        UnderlyingNetworkListener cb = verifyRegistrationOnAvailableAndGetCallback();

        cb.onCapabilitiesChanged(
                mNetwork, buildResponseNwCaps(INITIAL_NETWORK_CAPABILITIES, INITIAL_SUB_IDS));

        // Verify no more calls to the UnderlyingNetworkControllerCallback when the
        // UnderlyingNetworkRecord does not actually change
        verifyNoMoreInteractions(mNetworkControllerCb);
    }

    @Test
    public void testRecordTrackerCallbackNotifiedAfterTeardown() {
        UnderlyingNetworkListener cb = verifyRegistrationOnAvailableAndGetCallback();
        mUnderlyingNetworkController.teardown();

        cb.onCapabilitiesChanged(
                mNetwork, buildResponseNwCaps(UPDATED_NETWORK_CAPABILITIES, INITIAL_SUB_IDS));

        // Verify that the only call was during onAvailable()
        verify(mNetworkControllerCb, times(1)).onSelectedUnderlyingNetworkChanged(any());
    }

    // TODO (b/187991063): Add tests for network prioritization
}
