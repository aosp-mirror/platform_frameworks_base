/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net;

import static com.android.testutils.ParcelUtils.assertParcelSane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.wifi.WifiNetworkSpecifier;
import android.telephony.SubscriptionManager;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit test for {@link android.net.TelephonyNetworkSpecifier}.
 */
@SmallTest
public class TelephonyNetworkSpecifierTest {
    private static final int TEST_SUBID = 5;
    private static final String TEST_SSID = "Test123";

    /**
     * Validate that IllegalArgumentException will be thrown if build TelephonyNetworkSpecifier
     * without calling {@link TelephonyNetworkSpecifier.Builder#setSubscriptionId(int)}.
     */
    @Test
    public void testBuilderBuildWithDefault() {
        try {
            new TelephonyNetworkSpecifier.Builder().build();
        } catch (IllegalArgumentException iae) {
            // expected, test pass
        }
    }

    /**
     * Validate that no exception will be thrown even if pass invalid subscription id to
     * {@link TelephonyNetworkSpecifier.Builder#setSubscriptionId(int)}.
     */
    @Test
    public void testBuilderBuildWithInvalidSubId() {
        TelephonyNetworkSpecifier specifier = new TelephonyNetworkSpecifier.Builder()
                .setSubscriptionId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                .build();
        assertEquals(specifier.getSubscriptionId(), SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    /**
     * Validate the correctness of TelephonyNetworkSpecifier when provide valid subId.
     */
    @Test
    public void testBuilderBuildWithValidSubId() {
        final TelephonyNetworkSpecifier specifier = new TelephonyNetworkSpecifier.Builder()
                .setSubscriptionId(TEST_SUBID)
                .build();
        assertEquals(TEST_SUBID, specifier.getSubscriptionId());
    }

    /**
     * Validate that parcel marshalling/unmarshalling works.
     */
    @Test
    public void testParcel() {
        TelephonyNetworkSpecifier specifier = new TelephonyNetworkSpecifier.Builder()
                .setSubscriptionId(TEST_SUBID)
                .build();
        assertParcelSane(specifier, 1 /* fieldCount */);
    }

    /**
     * Validate the behavior of method canBeSatisfiedBy().
     */
    @Test
    public void testCanBeSatisfiedBy() {
        final TelephonyNetworkSpecifier tns1 = new TelephonyNetworkSpecifier.Builder()
                .setSubscriptionId(TEST_SUBID)
                .build();
        final TelephonyNetworkSpecifier tns2 = new TelephonyNetworkSpecifier.Builder()
                .setSubscriptionId(TEST_SUBID)
                .build();
        final WifiNetworkSpecifier wns = new WifiNetworkSpecifier.Builder()
                .setSsid(TEST_SSID)
                .build();
        final MatchAllNetworkSpecifier mans = new MatchAllNetworkSpecifier();

        // Test equality
        assertEquals(tns1, tns2);
        assertTrue(tns1.canBeSatisfiedBy(tns1));
        assertTrue(tns1.canBeSatisfiedBy(tns2));

        // Test other edge cases.
        assertFalse(tns1.canBeSatisfiedBy(null));
        assertFalse(tns1.canBeSatisfiedBy(wns));
        assertTrue(tns1.canBeSatisfiedBy(mans));
    }
}
