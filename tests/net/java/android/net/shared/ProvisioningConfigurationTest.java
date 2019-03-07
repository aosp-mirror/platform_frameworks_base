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

package android.net.shared;

import static android.net.InetAddresses.parseNumericAddress;
import static android.net.shared.ParcelableTestUtil.assertFieldCountEquals;
import static android.net.shared.ProvisioningConfiguration.fromStableParcelable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.net.LinkAddress;
import android.net.Network;
import android.net.StaticIpConfiguration;
import android.net.apf.ApfCapabilities;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

/**
 * Tests for {@link ProvisioningConfiguration}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ProvisioningConfigurationTest {
    private ProvisioningConfiguration mConfig;

    @Before
    public void setUp() {
        mConfig = new ProvisioningConfiguration();
        mConfig.mEnableIPv4 = true;
        mConfig.mEnableIPv6 = true;
        mConfig.mUsingMultinetworkPolicyTracker = true;
        mConfig.mUsingIpReachabilityMonitor = true;
        mConfig.mRequestedPreDhcpActionMs = 42;
        mConfig.mInitialConfig = new InitialConfiguration();
        mConfig.mInitialConfig.ipAddresses.add(
                new LinkAddress(parseNumericAddress("192.168.42.42"), 24));
        mConfig.mStaticIpConfig = new StaticIpConfiguration();
        mConfig.mStaticIpConfig.ipAddress =
                new LinkAddress(parseNumericAddress("2001:db8::42"), 90);
        // Not testing other InitialConfig or StaticIpConfig members: they have their own unit tests
        mConfig.mApfCapabilities = new ApfCapabilities(1, 2, 3);
        mConfig.mProvisioningTimeoutMs = 4200;
        mConfig.mIPv6AddrGenMode = 123;
        mConfig.mNetwork = new Network(321);
        mConfig.mDisplayName = "test_config";
        // Any added field must be included in equals() to be tested properly
        assertFieldCountEquals(12, ProvisioningConfiguration.class);
    }

    @Test
    public void testParcelUnparcel() {
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_NullInitialConfiguration() {
        mConfig.mInitialConfig = null;
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_NullStaticConfiguration() {
        mConfig.mStaticIpConfig = null;
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_NullApfCapabilities() {
        mConfig.mApfCapabilities = null;
        doParcelUnparcelTest();
    }

    @Test
    public void testParcelUnparcel_NullNetwork() {
        mConfig.mNetwork = null;
        doParcelUnparcelTest();
    }

    private void doParcelUnparcelTest() {
        final ProvisioningConfiguration unparceled =
                fromStableParcelable(mConfig.toStableParcelable());
        assertEquals(mConfig, unparceled);
    }

    @Test
    public void testEquals() {
        assertEquals(mConfig, new ProvisioningConfiguration(mConfig));

        assertNotEqualsAfterChange(c -> c.mEnableIPv4 = false);
        assertNotEqualsAfterChange(c -> c.mEnableIPv6 = false);
        assertNotEqualsAfterChange(c -> c.mUsingMultinetworkPolicyTracker = false);
        assertNotEqualsAfterChange(c -> c.mUsingIpReachabilityMonitor = false);
        assertNotEqualsAfterChange(c -> c.mRequestedPreDhcpActionMs++);
        assertNotEqualsAfterChange(c -> c.mInitialConfig.ipAddresses.add(
                new LinkAddress(parseNumericAddress("192.168.47.47"), 16)));
        assertNotEqualsAfterChange(c -> c.mInitialConfig = null);
        assertNotEqualsAfterChange(c -> c.mStaticIpConfig.ipAddress =
                new LinkAddress(parseNumericAddress("2001:db8::47"), 64));
        assertNotEqualsAfterChange(c -> c.mStaticIpConfig = null);
        assertNotEqualsAfterChange(c -> c.mApfCapabilities = new ApfCapabilities(4, 5, 6));
        assertNotEqualsAfterChange(c -> c.mApfCapabilities = null);
        assertNotEqualsAfterChange(c -> c.mProvisioningTimeoutMs++);
        assertNotEqualsAfterChange(c -> c.mIPv6AddrGenMode++);
        assertNotEqualsAfterChange(c -> c.mNetwork = new Network(123));
        assertNotEqualsAfterChange(c -> c.mNetwork = null);
        assertNotEqualsAfterChange(c -> c.mDisplayName = "other_test");
        assertNotEqualsAfterChange(c -> c.mDisplayName = null);
        assertFieldCountEquals(12, ProvisioningConfiguration.class);
    }

    private void assertNotEqualsAfterChange(Consumer<ProvisioningConfiguration> mutator) {
        final ProvisioningConfiguration newConfig = new ProvisioningConfiguration(mConfig);
        mutator.accept(newConfig);
        assertNotEquals(mConfig, newConfig);
    }
}
