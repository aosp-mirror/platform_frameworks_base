/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import static android.util.DebugUtils.valueToString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.annotation.NonNull;
import android.content.Context;
import android.net.INetd;
import android.net.INetdUnsolicitedEventListener;
import android.net.LinkAddress;
import android.net.NetworkPolicyManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArrayMap;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.IBatteryStats;
import com.android.server.NetworkManagementService.Dependencies;
import com.android.server.net.BaseNetworkObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.BiFunction;

/**
 * Tests for {@link NetworkManagementService}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkManagementServiceTest {
    private NetworkManagementService mNMService;
    @Mock private Context mContext;
    @Mock private IBatteryStats.Stub mBatteryStatsService;
    @Mock private INetd.Stub mNetdService;

    private static final int TEST_UID = 111;

    @NonNull
    @Captor
    private ArgumentCaptor<INetdUnsolicitedEventListener> mUnsolListenerCaptor;

    private final MockDependencies mDeps = new MockDependencies();

    private final class MockDependencies extends Dependencies {
        @Override
        public IBinder getService(String name) {
            switch (name) {
                case BatteryStats.SERVICE_NAME:
                    return mBatteryStatsService;
                default:
                    throw new UnsupportedOperationException("Unknown service " + name);
            }
        }

        @Override
        public void registerLocalService(NetworkManagementInternal nmi) {
        }

        @Override
        public INetd getNetd() {
            return mNetdService;
        }

        @Override
        public int getCallingUid() {
            return Process.SYSTEM_UID;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doNothing().when(mNetdService)
                .registerUnsolicitedEventListener(mUnsolListenerCaptor.capture());
        // Start the service and wait until it connects to our socket.
        mNMService = NetworkManagementService.create(mContext, mDeps);
    }

    @After
    public void tearDown() throws Exception {
        mNMService.shutdown();
    }

    private static <T> T expectSoon(T mock) {
        return verify(mock, timeout(200));
    }

    /**
     * Tests that network observers work properly.
     */
    @Test
    public void testNetworkObservers() throws Exception {
        BaseNetworkObserver observer = mock(BaseNetworkObserver.class);
        doReturn(new Binder()).when(observer).asBinder();  // Used by registerObserver.
        mNMService.registerObserver(observer);

        // Forget everything that happened to the mock so far, so we can explicitly verify
        // everything that happens and does not happen to it from now on.

        INetdUnsolicitedEventListener unsolListener = mUnsolListenerCaptor.getValue();
        reset(observer);
        // Now call unsolListener methods and ensure that the observer methods are
        // called. After every method we expect a callback soon after; to ensure that
        // invalid messages don't cause any callbacks, we call verifyNoMoreInteractions at the end.

        /**
         * Interface changes.
         */
        unsolListener.onInterfaceAdded("rmnet12");
        expectSoon(observer).interfaceAdded("rmnet12");

        unsolListener.onInterfaceRemoved("eth1");
        expectSoon(observer).interfaceRemoved("eth1");

        unsolListener.onInterfaceChanged("clat4", true);
        expectSoon(observer).interfaceStatusChanged("clat4", true);

        unsolListener.onInterfaceLinkStateChanged("rmnet0", false);
        expectSoon(observer).interfaceLinkStateChanged("rmnet0", false);

        /**
         * Bandwidth control events.
         */
        unsolListener.onQuotaLimitReached("data", "rmnet_usb0");
        expectSoon(observer).limitReached("data", "rmnet_usb0");

        /**
         * Interface class activity.
         */
        unsolListener.onInterfaceClassActivityChanged(true, 1, 1234, TEST_UID);
        expectSoon(observer).interfaceClassDataActivityChanged(1, true, 1234, TEST_UID);

        unsolListener.onInterfaceClassActivityChanged(false, 9, 5678, TEST_UID);
        expectSoon(observer).interfaceClassDataActivityChanged(9, false, 5678, TEST_UID);

        unsolListener.onInterfaceClassActivityChanged(false, 9, 4321, TEST_UID);
        expectSoon(observer).interfaceClassDataActivityChanged(9, false, 4321, TEST_UID);

        /**
         * IP address changes.
         */
        unsolListener.onInterfaceAddressUpdated("fe80::1/64", "wlan0", 128, 253);
        expectSoon(observer).addressUpdated("wlan0", new LinkAddress("fe80::1/64", 128, 253));

        unsolListener.onInterfaceAddressRemoved("fe80::1/64", "wlan0", 128, 253);
        expectSoon(observer).addressRemoved("wlan0", new LinkAddress("fe80::1/64", 128, 253));

        unsolListener.onInterfaceAddressRemoved("2001:db8::1/64", "wlan0", 1, 0);
        expectSoon(observer).addressRemoved("wlan0", new LinkAddress("2001:db8::1/64", 1, 0));

        /**
         * DNS information broadcasts.
         */
        unsolListener.onInterfaceDnsServerInfo("rmnet_usb0", 3600, new String[]{"2001:db8::1"});
        expectSoon(observer).interfaceDnsServerInfo("rmnet_usb0", 3600,
                new String[]{"2001:db8::1"});

        unsolListener.onInterfaceDnsServerInfo("wlan0", 14400,
                new String[]{"2001:db8::1", "2001:db8::2"});
        expectSoon(observer).interfaceDnsServerInfo("wlan0", 14400,
                new String[]{"2001:db8::1", "2001:db8::2"});

        // We don't check for negative lifetimes, only for parse errors.
        unsolListener.onInterfaceDnsServerInfo("wlan0", -3600, new String[]{"::1"});
        expectSoon(observer).interfaceDnsServerInfo("wlan0", -3600,
                new String[]{"::1"});

        // No syntax checking on the addresses.
        unsolListener.onInterfaceDnsServerInfo("wlan0", 600,
                new String[]{"", "::", "", "foo", "::1"});
        expectSoon(observer).interfaceDnsServerInfo("wlan0", 600,
                new String[]{"", "::", "", "foo", "::1"});

        // Make sure nothing else was called.
        verifyNoMoreInteractions(observer);
    }

    @Test
    public void testFirewallEnabled() {
        mNMService.setFirewallEnabled(true);
        assertTrue(mNMService.isFirewallEnabled());

        mNMService.setFirewallEnabled(false);
        assertFalse(mNMService.isFirewallEnabled());
    }

    @Test
    public void testNetworkRestrictedDefault() {
        assertFalse(mNMService.isNetworkRestricted(TEST_UID));
    }

    @Test
    public void testMeteredNetworkRestrictions() throws RemoteException {
        // Make sure the mocked netd method returns true.
        doReturn(true).when(mNetdService).bandwidthEnableDataSaver(anyBoolean());

        // Restrict usage of mobile data in background
        mNMService.setUidOnMeteredNetworkDenylist(TEST_UID, true);
        assertTrue("Should be true since mobile data usage is restricted",
                mNMService.isNetworkRestricted(TEST_UID));

        mNMService.setDataSaverModeEnabled(true);
        verify(mNetdService).bandwidthEnableDataSaver(true);

        mNMService.setUidOnMeteredNetworkDenylist(TEST_UID, false);
        assertTrue("Should be true since data saver is on and the uid is not allowlisted",
                mNMService.isNetworkRestricted(TEST_UID));

        mNMService.setUidOnMeteredNetworkAllowlist(TEST_UID, true);
        assertFalse("Should be false since data saver is on and the uid is allowlisted",
                mNMService.isNetworkRestricted(TEST_UID));

        // remove uid from allowlist and turn datasaver off again
        mNMService.setUidOnMeteredNetworkAllowlist(TEST_UID, false);
        mNMService.setDataSaverModeEnabled(false);
        verify(mNetdService).bandwidthEnableDataSaver(false);
        assertFalse("Network should not be restricted when data saver is off",
                mNMService.isNetworkRestricted(TEST_UID));
    }

    @Test
    public void testFirewallChains() {
        final ArrayMap<Integer, ArrayMap<Integer, Boolean>> expected = new ArrayMap<>();
        // Dozable chain
        final ArrayMap<Integer, Boolean> isRestrictedForDozable = new ArrayMap<>();
        isRestrictedForDozable.put(NetworkPolicyManager.FIREWALL_RULE_DEFAULT, true);
        isRestrictedForDozable.put(INetd.FIREWALL_RULE_ALLOW, false);
        isRestrictedForDozable.put(INetd.FIREWALL_RULE_DENY, true);
        expected.put(INetd.FIREWALL_CHAIN_DOZABLE, isRestrictedForDozable);
        // Powersaver chain
        final ArrayMap<Integer, Boolean> isRestrictedForPowerSave = new ArrayMap<>();
        isRestrictedForPowerSave.put(NetworkPolicyManager.FIREWALL_RULE_DEFAULT, true);
        isRestrictedForPowerSave.put(INetd.FIREWALL_RULE_ALLOW, false);
        isRestrictedForPowerSave.put(INetd.FIREWALL_RULE_DENY, true);
        expected.put(INetd.FIREWALL_CHAIN_POWERSAVE, isRestrictedForPowerSave);
        // Standby chain
        final ArrayMap<Integer, Boolean> isRestrictedForStandby = new ArrayMap<>();
        isRestrictedForStandby.put(NetworkPolicyManager.FIREWALL_RULE_DEFAULT, false);
        isRestrictedForStandby.put(INetd.FIREWALL_RULE_ALLOW, false);
        isRestrictedForStandby.put(INetd.FIREWALL_RULE_DENY, true);
        expected.put(INetd.FIREWALL_CHAIN_STANDBY, isRestrictedForStandby);
        // Restricted mode chain
        final ArrayMap<Integer, Boolean> isRestrictedForRestrictedMode = new ArrayMap<>();
        isRestrictedForRestrictedMode.put(NetworkPolicyManager.FIREWALL_RULE_DEFAULT, true);
        isRestrictedForRestrictedMode.put(INetd.FIREWALL_RULE_ALLOW, false);
        isRestrictedForRestrictedMode.put(INetd.FIREWALL_RULE_DENY, true);
        expected.put(INetd.FIREWALL_CHAIN_RESTRICTED, isRestrictedForRestrictedMode);

        final int[] chains = {
                INetd.FIREWALL_CHAIN_STANDBY,
                INetd.FIREWALL_CHAIN_POWERSAVE,
                INetd.FIREWALL_CHAIN_DOZABLE,
                INetd.FIREWALL_CHAIN_RESTRICTED
        };
        final int[] states = {
                INetd.FIREWALL_RULE_ALLOW,
                INetd.FIREWALL_RULE_DENY,
                NetworkPolicyManager.FIREWALL_RULE_DEFAULT
        };
        BiFunction<Integer, Integer, String> errorMsg = (chain, state) -> {
            return String.format("Unexpected value for chain: %s and state: %s",
                    valueToString(INetd.class, "FIREWALL_CHAIN_", chain),
                    valueToString(INetd.class, "FIREWALL_RULE_", state));
        };
        for (int chain : chains) {
            final ArrayMap<Integer, Boolean> expectedValues = expected.get(chain);
            mNMService.setFirewallChainEnabled(chain, true);
            for (int state : states) {
                mNMService.setFirewallUidRule(chain, TEST_UID, state);
                assertEquals(errorMsg.apply(chain, state),
                        expectedValues.get(state), mNMService.isNetworkRestricted(TEST_UID));
            }
            mNMService.setFirewallChainEnabled(chain, false);
        }
    }
}
