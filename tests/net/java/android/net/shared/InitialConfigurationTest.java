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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.net.IpPrefix;
import android.net.LinkAddress;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Tests for {@link InitialConfiguration}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class InitialConfigurationTest {
    private InitialConfiguration mConfig;

    @Before
    public void setUp() {
        mConfig = new InitialConfiguration();
        mConfig.ipAddresses.addAll(Arrays.asList(
                new LinkAddress(parseNumericAddress("192.168.45.45"), 16),
                new LinkAddress(parseNumericAddress("2001:db8::45"), 33)));
        mConfig.directlyConnectedRoutes.addAll(Arrays.asList(
                new IpPrefix(parseNumericAddress("192.168.46.46"), 17),
                new IpPrefix(parseNumericAddress("2001:db8::46"), 34)));
        mConfig.dnsServers.addAll(Arrays.asList(
                parseNumericAddress("192.168.47.47"),
                parseNumericAddress("2001:db8::47")));
        // Any added InitialConfiguration field must be included in equals() to be tested properly
        assertFieldCountEquals(3, InitialConfiguration.class);
    }

    @Test
    public void testParcelUnparcelInitialConfiguration() {
        final InitialConfiguration unparceled =
                InitialConfiguration.fromStableParcelable(mConfig.toStableParcelable());
        assertEquals(mConfig, unparceled);
    }

    @Test
    public void testEquals() {
        assertEquals(mConfig, InitialConfiguration.copy(mConfig));

        assertNotEqualsAfterChange(c -> c.ipAddresses.add(
                new LinkAddress(parseNumericAddress("192.168.47.47"), 24)));
        assertNotEqualsAfterChange(c -> c.directlyConnectedRoutes.add(
                new IpPrefix(parseNumericAddress("192.168.46.46"), 32)));
        assertNotEqualsAfterChange(c -> c.dnsServers.add(parseNumericAddress("2001:db8::49")));
        assertFieldCountEquals(3, InitialConfiguration.class);
    }

    private void assertNotEqualsAfterChange(Consumer<InitialConfiguration> mutator) {
        final InitialConfiguration newConfig = InitialConfiguration.copy(mConfig);
        mutator.accept(newConfig);
        assertNotEquals(mConfig, newConfig);
    }
}
