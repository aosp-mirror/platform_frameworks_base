/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.net.INetd.FIREWALL_CHAIN_DOZABLE;
import static android.net.INetd.FIREWALL_CHAIN_POWERSAVE;
import static android.net.INetd.FIREWALL_CHAIN_STANDBY;
import static android.net.INetd.FIREWALL_RULE_ALLOW;
import static android.net.INetd.FIREWALL_RULE_DENY;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_DEFAULT;
import static android.util.DebugUtils.valueToString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.NetworkPolicyManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArrayMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.BiFunction;

/**
 * Test class for {@link NetworkManagementInternal}.
 *
 * To run the tests, use
 *
 * runtest -c com.android.server.NetworkManagementInternalTest frameworks-services
 *
 * or the following steps:
 *
 * Build: m FrameworksServicesTests
 * Install: adb install -r \
 *     ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -e class com.android.server.NetworkManagementInternalTest -w \
 *     com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class NetworkManagementInternalTest {
    private static final int TEST_UID = 111;

    private NetworkManagementService.Injector mInjector;
    private NetworkManagementInternal mNmi;

    @Before
    public void setUp() {
        final NetworkManagementService service = new NetworkManagementService();
        mInjector = service.getInjector();
        mNmi = service.new LocalService();
    }

    @Test
    public void testIsNetworkRestrictedForUid() {
        // No firewall chains enabled
        assertFalse(mNmi.isNetworkRestrictedForUid(TEST_UID));

        // Restrict usage of mobile data in background
        mInjector.setUidOnMeteredNetworkList(true, TEST_UID, true);
        assertTrue("Should be true since mobile data usage is restricted",
                mNmi.isNetworkRestrictedForUid(TEST_UID));
        mInjector.reset();

        // Data saver is on and uid is not whitelisted
        mInjector.setDataSaverMode(true);
        mInjector.setUidOnMeteredNetworkList(false, TEST_UID, false);
        assertTrue("Should be true since data saver is on and the uid is not whitelisted",
                mNmi.isNetworkRestrictedForUid(TEST_UID));
        mInjector.reset();

        // Data saver is on and uid is whitelisted
        mInjector.setDataSaverMode(true);
        mInjector.setUidOnMeteredNetworkList(false, TEST_UID, true);
        assertFalse("Should be false since data saver is on and the uid is whitelisted",
                mNmi.isNetworkRestrictedForUid(TEST_UID));
        mInjector.reset();

        final ArrayMap<Integer, ArrayMap<Integer, Boolean>> expected = new ArrayMap<>();
        // Dozable chain
        final ArrayMap<Integer, Boolean> isRestrictedForDozable = new ArrayMap<>();
        isRestrictedForDozable.put(FIREWALL_RULE_DEFAULT, true);
        isRestrictedForDozable.put(FIREWALL_RULE_ALLOW, false);
        isRestrictedForDozable.put(FIREWALL_RULE_DENY, true);
        expected.put(FIREWALL_CHAIN_DOZABLE, isRestrictedForDozable);
        // Powersaver chain
        final ArrayMap<Integer, Boolean> isRestrictedForPowerSave = new ArrayMap<>();
        isRestrictedForPowerSave.put(FIREWALL_RULE_DEFAULT, true);
        isRestrictedForPowerSave.put(FIREWALL_RULE_ALLOW, false);
        isRestrictedForPowerSave.put(FIREWALL_RULE_DENY, true);
        expected.put(FIREWALL_CHAIN_POWERSAVE, isRestrictedForPowerSave);
        // Standby chain
        final ArrayMap<Integer, Boolean> isRestrictedForStandby = new ArrayMap<>();
        isRestrictedForStandby.put(FIREWALL_RULE_DEFAULT, false);
        isRestrictedForStandby.put(FIREWALL_RULE_ALLOW, false);
        isRestrictedForStandby.put(FIREWALL_RULE_DENY, true);
        expected.put(FIREWALL_CHAIN_STANDBY, isRestrictedForStandby);

        final int[] chains = {
                FIREWALL_CHAIN_STANDBY,
                FIREWALL_CHAIN_POWERSAVE,
                FIREWALL_CHAIN_DOZABLE
        };
        final int[] states = {
                FIREWALL_RULE_ALLOW,
                FIREWALL_RULE_DENY,
                FIREWALL_RULE_DEFAULT
        };
        BiFunction<Integer, Integer, String> errorMsg = (chain, state) -> {
            return String.format("Unexpected value for chain: %s and state: %s",
                    valueToString(NetworkPolicyManager.class, "FIREWALL_CHAIN_", chain),
                    valueToString(NetworkPolicyManager.class, "FIREWALL_RULE_", state));
        };
        for (int chain : chains) {
            final ArrayMap<Integer, Boolean> expectedValues = expected.get(chain);
            mInjector.setFirewallChainState(chain, true);
            for (int state : states) {
                mInjector.setFirewallRule(chain, TEST_UID, state);
                assertEquals(errorMsg.apply(chain, state),
                        expectedValues.get(state), mNmi.isNetworkRestrictedForUid(TEST_UID));
            }
            mInjector.reset();
        }
    }
}
