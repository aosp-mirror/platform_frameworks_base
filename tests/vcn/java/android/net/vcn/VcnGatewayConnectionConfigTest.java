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

package android.net.vcn;

import static android.net.ipsec.ike.IkeSessionParams.IKE_OPTION_MOBIKE;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.NetworkCapabilities;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.IkeTunnelConnectionParams;
import android.net.vcn.persistablebundleutils.IkeSessionParamsUtilsTest;
import android.net.vcn.persistablebundleutils.TunnelConnectionParamsUtilsTest;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionConfigTest {
    // Public for use in VcnGatewayConnectionTest
    public static final int[] EXPOSED_CAPS =
            new int[] {
                NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_MMS
            };
    public static final int[] UNDERLYING_CAPS = new int[] {NetworkCapabilities.NET_CAPABILITY_DUN};

    static {
        Arrays.sort(EXPOSED_CAPS);
        Arrays.sort(UNDERLYING_CAPS);
    }

    public static final long[] RETRY_INTERVALS_MS =
            new long[] {
                TimeUnit.SECONDS.toMillis(5),
                TimeUnit.SECONDS.toMillis(30),
                TimeUnit.MINUTES.toMillis(1),
                TimeUnit.MINUTES.toMillis(5),
                TimeUnit.MINUTES.toMillis(15),
                TimeUnit.MINUTES.toMillis(30)
            };
    public static final int MAX_MTU = 1360;

    public static final IkeTunnelConnectionParams TUNNEL_CONNECTION_PARAMS =
            TunnelConnectionParamsUtilsTest.buildTestParams();

    public static final String GATEWAY_CONNECTION_NAME_PREFIX = "gatewayConnectionName-";
    private static int sGatewayConnectionConfigCount = 0;

    // Public for use in VcnGatewayConnectionTest
    public static VcnGatewayConnectionConfig buildTestConfig() {
        return buildTestConfigWithExposedCaps(EXPOSED_CAPS);
    }

    private static VcnGatewayConnectionConfig.Builder newBuilder() {
        // Append a unique identifier to the name prefix to guarantee that all created
        // VcnGatewayConnectionConfigs have a unique name (required by VcnConfig).
        return new VcnGatewayConnectionConfig.Builder(
                GATEWAY_CONNECTION_NAME_PREFIX + sGatewayConnectionConfigCount++,
                TUNNEL_CONNECTION_PARAMS);
    }

    // Public for use in VcnGatewayConnectionTest
    public static VcnGatewayConnectionConfig buildTestConfigWithExposedCaps(int... exposedCaps) {
        final VcnGatewayConnectionConfig.Builder builder =
                newBuilder().setRetryIntervalsMillis(RETRY_INTERVALS_MS).setMaxMtu(MAX_MTU);

        for (int caps : exposedCaps) {
            builder.addExposedCapability(caps);
        }

        return builder.build();
    }

    @Test
    public void testBuilderRequiresNonNullGatewayConnectionName() {
        try {
            new VcnGatewayConnectionConfig.Builder(
                            null /* gatewayConnectionName */, TUNNEL_CONNECTION_PARAMS)
                    .build();

            fail("Expected exception due to invalid gateway connection name");
        } catch (NullPointerException e) {
        }
    }

    @Test
    public void testBuilderRequiresNonNullTunnelConnectionParams() {
        try {
            new VcnGatewayConnectionConfig.Builder(
                            GATEWAY_CONNECTION_NAME_PREFIX, null /* tunnelConnectionParams */)
                    .build();

            fail("Expected exception due to the absence of tunnel connection parameters");
        } catch (NullPointerException e) {
        }
    }

    @Test
    public void testBuilderRequiresMobikeEnabled() {
        try {
            final IkeSessionParams ikeParams =
                    IkeSessionParamsUtilsTest.createBuilderMinimum()
                            .removeIkeOption(IKE_OPTION_MOBIKE)
                            .build();
            final IkeTunnelConnectionParams tunnelParams =
                    TunnelConnectionParamsUtilsTest.buildTestParams(ikeParams);
            new VcnGatewayConnectionConfig.Builder(GATEWAY_CONNECTION_NAME_PREFIX, tunnelParams);
            fail("Expected exception due to MOBIKE not enabled");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testBuilderRequiresNonEmptyExposedCaps() {
        try {
            newBuilder().build();

            fail("Expected exception due to invalid exposed capabilities");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testBuilderRequiresNonNullRetryInterval() {
        try {
            newBuilder().setRetryIntervalsMillis(null);
            fail("Expected exception due to invalid retryIntervalMs");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testBuilderRequiresNonEmptyRetryInterval() {
        try {
            newBuilder().setRetryIntervalsMillis(new long[0]);
            fail("Expected exception due to invalid retryIntervalMs");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testBuilderRequiresValidMtu() {
        try {
            newBuilder().setMaxMtu(VcnGatewayConnectionConfig.MIN_MTU_V6 - 1);
            fail("Expected exception due to invalid mtu");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testBuilderAndGetters() {
        final VcnGatewayConnectionConfig config = buildTestConfig();

        assertTrue(config.getGatewayConnectionName().startsWith(GATEWAY_CONNECTION_NAME_PREFIX));

        int[] exposedCaps = config.getExposedCapabilities();
        Arrays.sort(exposedCaps);
        assertArrayEquals(EXPOSED_CAPS, exposedCaps);

        assertEquals(TUNNEL_CONNECTION_PARAMS, config.getTunnelConnectionParams());

        assertArrayEquals(RETRY_INTERVALS_MS, config.getRetryIntervalsMillis());
        assertEquals(MAX_MTU, config.getMaxMtu());
    }

    @Test
    public void testPersistableBundle() {
        final VcnGatewayConnectionConfig config = buildTestConfig();

        assertEquals(config, new VcnGatewayConnectionConfig(config.toPersistableBundle()));
    }
}
