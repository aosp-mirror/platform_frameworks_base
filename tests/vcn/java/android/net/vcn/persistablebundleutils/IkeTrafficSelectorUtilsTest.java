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

package android.net.vcn.persistablebundleutils;

import static org.junit.Assert.assertEquals;

import android.net.InetAddresses;
import android.net.ipsec.ike.IkeTrafficSelector;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IkeTrafficSelectorUtilsTest {
    private static final int START_PORT = 16;
    private static final int END_PORT = 65520;

    private static final InetAddress IPV4_START_ADDRESS =
            InetAddresses.parseNumericAddress("192.0.2.100");
    private static final InetAddress IPV4_END_ADDRESS =
            InetAddresses.parseNumericAddress("192.0.2.101");

    private static final InetAddress IPV6_START_ADDRESS =
            InetAddresses.parseNumericAddress("2001:db8:2::100");
    private static final InetAddress IPV6_END_ADDRESS =
            InetAddresses.parseNumericAddress("2001:db8:2::101");

    private static void verifyPersistableBundleEncodeDecodeIsLossless(IkeTrafficSelector ts) {
        final PersistableBundle bundle = IkeTrafficSelectorUtils.toPersistableBundle(ts);
        final IkeTrafficSelector resultTs = IkeTrafficSelectorUtils.fromPersistableBundle(bundle);
        assertEquals(ts, resultTs);
    }

    @Test
    public void testPersistableBundleEncodeDecodeIsLosslessIpv4Ts() throws Exception {
        verifyPersistableBundleEncodeDecodeIsLossless(
                new IkeTrafficSelector(START_PORT, END_PORT, IPV4_START_ADDRESS, IPV4_END_ADDRESS));
    }

    @Test
    public void testPersistableBundleEncodeDecodeIsLosslessIpv6Ts() throws Exception {
        verifyPersistableBundleEncodeDecodeIsLossless(
                new IkeTrafficSelector(START_PORT, END_PORT, IPV6_START_ADDRESS, IPV6_END_ADDRESS));
    }
}
