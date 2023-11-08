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
import static android.net.vcn.VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES;
import static android.net.vcn.VcnGatewayConnectionConfig.UNDERLYING_NETWORK_TEMPLATES_KEY;
import static android.net.vcn.VcnGatewayConnectionConfig.VCN_GATEWAY_OPTION_ENABLE_DATA_STALL_RECOVERY_WITH_MOBILITY;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.NetworkCapabilities;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.IkeTunnelConnectionParams;
import android.net.vcn.persistablebundleutils.IkeSessionParamsUtilsTest;
import android.net.vcn.persistablebundleutils.TunnelConnectionParamsUtilsTest;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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

    private static final List<VcnUnderlyingNetworkTemplate> UNDERLYING_NETWORK_TEMPLATES =
            new ArrayList();

    static {
        Arrays.sort(EXPOSED_CAPS);
        Arrays.sort(UNDERLYING_CAPS);

        UNDERLYING_NETWORK_TEMPLATES.add(
                VcnCellUnderlyingNetworkTemplateTest.getTestNetworkTemplate());
        UNDERLYING_NETWORK_TEMPLATES.add(
                VcnWifiUnderlyingNetworkTemplateTest.getTestNetworkTemplate());
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
    public static final int MIN_UDP_PORT_4500_NAT_TIMEOUT = 120;

    private static final Set<Integer> GATEWAY_OPTIONS =
            Collections.singleton(VCN_GATEWAY_OPTION_ENABLE_DATA_STALL_RECOVERY_WITH_MOBILITY);

    public static final IkeTunnelConnectionParams TUNNEL_CONNECTION_PARAMS =
            TunnelConnectionParamsUtilsTest.buildTestParams();

    public static final String GATEWAY_CONNECTION_NAME_PREFIX = "gatewayConnectionName-";
    private static int sGatewayConnectionConfigCount = 0;

    private static VcnGatewayConnectionConfig buildTestConfig(
            String gatewayConnectionName, IkeTunnelConnectionParams tunnelConnectionParams) {
        return buildTestConfigWithExposedCaps(
                new VcnGatewayConnectionConfig.Builder(
                        gatewayConnectionName, tunnelConnectionParams),
                EXPOSED_CAPS);
    }

    // Public for use in UnderlyingNetworkControllerTest
    public static VcnGatewayConnectionConfig buildTestConfig(
            List<VcnUnderlyingNetworkTemplate> nwTemplates) {
        final VcnGatewayConnectionConfig.Builder builder =
                newBuilder()
                        .setVcnUnderlyingNetworkPriorities(nwTemplates)
                        .setMinUdpPort4500NatTimeoutSeconds(MIN_UDP_PORT_4500_NAT_TIMEOUT);

        return buildTestConfigWithExposedCaps(builder, EXPOSED_CAPS);
    }

    // Public for use in VcnGatewayConnectionTest
    public static VcnGatewayConnectionConfig buildTestConfig() {
        return buildTestConfig(UNDERLYING_NETWORK_TEMPLATES);
    }

    // Public for use in VcnGatewayConnectionTest
    public static VcnGatewayConnectionConfig.Builder newTestBuilderMinimal() {
        final VcnGatewayConnectionConfig.Builder builder = newBuilder();
        for (int caps : EXPOSED_CAPS) {
            builder.addExposedCapability(caps);
        }

        return builder;
    }

    private static VcnGatewayConnectionConfig.Builder newBuilder() {
        // Append a unique identifier to the name prefix to guarantee that all created
        // VcnGatewayConnectionConfigs have a unique name (required by VcnConfig).
        return new VcnGatewayConnectionConfig.Builder(
                GATEWAY_CONNECTION_NAME_PREFIX + sGatewayConnectionConfigCount++,
                TUNNEL_CONNECTION_PARAMS);
    }

    private static VcnGatewayConnectionConfig.Builder newBuilderMinimal() {
        final VcnGatewayConnectionConfig.Builder builder =
                new VcnGatewayConnectionConfig.Builder(
                        "newBuilderMinimal", TUNNEL_CONNECTION_PARAMS);
        for (int caps : EXPOSED_CAPS) {
            builder.addExposedCapability(caps);
        }

        return builder;
    }

    private static VcnGatewayConnectionConfig buildTestConfigWithExposedCapsAndOptions(
            VcnGatewayConnectionConfig.Builder builder,
            Set<Integer> gatewayOptions,
            int... exposedCaps) {
        builder.setRetryIntervalsMillis(RETRY_INTERVALS_MS).setMaxMtu(MAX_MTU);

        for (int option : gatewayOptions) {
            builder.addGatewayOption(option);
        }

        for (int caps : exposedCaps) {
            builder.addExposedCapability(caps);
        }

        return builder.build();
    }

    private static VcnGatewayConnectionConfig buildTestConfigWithExposedCaps(
            VcnGatewayConnectionConfig.Builder builder, int... exposedCaps) {
        return buildTestConfigWithExposedCapsAndOptions(
                builder, Collections.emptySet(), exposedCaps);
    }

    // Public for use in VcnGatewayConnectionTest
    public static VcnGatewayConnectionConfig buildTestConfigWithExposedCaps(int... exposedCaps) {
        return buildTestConfigWithExposedCaps(newBuilder(), exposedCaps);
    }

    private static VcnGatewayConnectionConfig buildTestConfigWithGatewayOptions(
            VcnGatewayConnectionConfig.Builder builder, Set<Integer> gatewayOptions) {
        return buildTestConfigWithExposedCapsAndOptions(builder, gatewayOptions, EXPOSED_CAPS);
    }

    // Public for use in VcnGatewayConnectionTest
    public static VcnGatewayConnectionConfig buildTestConfigWithGatewayOptions(
            Set<Integer> gatewayOptions) {
        return buildTestConfigWithExposedCapsAndOptions(newBuilder(), gatewayOptions, EXPOSED_CAPS);
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
    public void testBuilderRequiresNonNullNetworkTemplates() {
        try {
            newBuilder().setVcnUnderlyingNetworkPriorities(null);
            fail("Expected exception due to invalid underlyingNetworkTemplates");
        } catch (NullPointerException e) {
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
    public void testBuilderRequiresValidOption() {
        try {
            newBuilder().addGatewayOption(-1);
            fail("Expected exception due to the invalid VCN gateway option");
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

        assertEquals(UNDERLYING_NETWORK_TEMPLATES, config.getVcnUnderlyingNetworkPriorities());
        assertEquals(TUNNEL_CONNECTION_PARAMS, config.getTunnelConnectionParams());

        assertArrayEquals(RETRY_INTERVALS_MS, config.getRetryIntervalsMillis());
        assertEquals(MAX_MTU, config.getMaxMtu());
        assertTrue(config.isSafeModeEnabled());

        assertFalse(
                config.hasGatewayOption(
                        VCN_GATEWAY_OPTION_ENABLE_DATA_STALL_RECOVERY_WITH_MOBILITY));
    }

    @Test
    public void testBuilderAndGettersWithOptions() {
        final VcnGatewayConnectionConfig config =
                buildTestConfigWithGatewayOptions(GATEWAY_OPTIONS);

        for (int option : GATEWAY_OPTIONS) {
            assertTrue(config.hasGatewayOption(option));
        }
    }

    @Test
    public void testBuilderAndGettersSafeModeDisabled() {
        final VcnGatewayConnectionConfig config =
                newBuilderMinimal().setSafeModeEnabled(false).build();

        assertFalse(config.isSafeModeEnabled());
    }

    @Test
    public void testPersistableBundle() {
        final VcnGatewayConnectionConfig config = buildTestConfig();

        assertEquals(config, new VcnGatewayConnectionConfig(config.toPersistableBundle()));
    }

    @Test
    public void testPersistableBundleWithOptions() {
        final VcnGatewayConnectionConfig config =
                buildTestConfigWithGatewayOptions(GATEWAY_OPTIONS);

        assertEquals(config, new VcnGatewayConnectionConfig(config.toPersistableBundle()));
    }

    @Test
    public void testPersistableBundleSafeModeDisabled() {
        final VcnGatewayConnectionConfig config =
                newBuilderMinimal().setSafeModeEnabled(false).build();

        assertEquals(config, new VcnGatewayConnectionConfig(config.toPersistableBundle()));
    }

    @Test
    public void testParsePersistableBundleWithoutVcnUnderlyingNetworkTemplates() {
        PersistableBundle configBundle = buildTestConfig().toPersistableBundle();
        configBundle.putPersistableBundle(UNDERLYING_NETWORK_TEMPLATES_KEY, null);

        final VcnGatewayConnectionConfig config = new VcnGatewayConnectionConfig(configBundle);
        assertEquals(
                DEFAULT_UNDERLYING_NETWORK_TEMPLATES, config.getVcnUnderlyingNetworkPriorities());
    }

    private static IkeTunnelConnectionParams buildTunnelConnectionParams(String ikePsk) {
        final IkeSessionParams ikeParams =
                IkeSessionParamsUtilsTest.createBuilderMinimum()
                        .setAuthPsk(ikePsk.getBytes())
                        .build();
        return TunnelConnectionParamsUtilsTest.buildTestParams(ikeParams);
    }

    @Test
    public void testTunnelConnectionParamsEquals() throws Exception {
        final String connectionName = "testTunnelConnectionParamsEquals.connectionName";
        final String psk = "testTunnelConnectionParamsEquals.psk";

        final IkeTunnelConnectionParams tunnelParams = buildTunnelConnectionParams(psk);
        final VcnGatewayConnectionConfig config = buildTestConfig(connectionName, tunnelParams);

        final IkeTunnelConnectionParams anotherTunnelParams = buildTunnelConnectionParams(psk);
        final VcnGatewayConnectionConfig anotherConfig =
                buildTestConfig(connectionName, anotherTunnelParams);

        assertNotSame(tunnelParams, anotherTunnelParams);
        assertEquals(tunnelParams, anotherTunnelParams);
        assertEquals(config, anotherConfig);
    }

    @Test
    public void testTunnelConnectionParamsNotEquals() throws Exception {
        final String connectionName = "testTunnelConnectionParamsNotEquals.connectionName";

        final IkeTunnelConnectionParams tunnelParams =
                buildTunnelConnectionParams("testTunnelConnectionParamsNotEquals.pskA");
        final VcnGatewayConnectionConfig config = buildTestConfig(connectionName, tunnelParams);

        final IkeTunnelConnectionParams anotherTunnelParams =
                buildTunnelConnectionParams("testTunnelConnectionParamsNotEquals.pskB");
        final VcnGatewayConnectionConfig anotherConfig =
                buildTestConfig(connectionName, anotherTunnelParams);

        assertNotEquals(tunnelParams, anotherTunnelParams);
        assertNotEquals(config, anotherConfig);
    }

    private static VcnGatewayConnectionConfig buildTestConfigWithVcnUnderlyingNetworkTemplates(
            List<VcnUnderlyingNetworkTemplate> networkTemplates) {
        return buildTestConfigWithExposedCaps(
                new VcnGatewayConnectionConfig.Builder(
                                "buildTestConfigWithVcnUnderlyingNetworkTemplates",
                                TUNNEL_CONNECTION_PARAMS)
                        .setVcnUnderlyingNetworkPriorities(networkTemplates),
                EXPOSED_CAPS);
    }

    @Test
    public void testVcnUnderlyingNetworkTemplatesEquality() throws Exception {
        final VcnGatewayConnectionConfig config =
                buildTestConfigWithVcnUnderlyingNetworkTemplates(UNDERLYING_NETWORK_TEMPLATES);

        final List<VcnUnderlyingNetworkTemplate> networkTemplatesEqual = new ArrayList();
        networkTemplatesEqual.add(VcnCellUnderlyingNetworkTemplateTest.getTestNetworkTemplate());
        networkTemplatesEqual.add(VcnWifiUnderlyingNetworkTemplateTest.getTestNetworkTemplate());
        final VcnGatewayConnectionConfig configEqual =
                buildTestConfigWithVcnUnderlyingNetworkTemplates(networkTemplatesEqual);

        final List<VcnUnderlyingNetworkTemplate> networkTemplatesNotEqual = new ArrayList();
        networkTemplatesNotEqual.add(VcnWifiUnderlyingNetworkTemplateTest.getTestNetworkTemplate());
        final VcnGatewayConnectionConfig configNotEqual =
                buildTestConfigWithVcnUnderlyingNetworkTemplates(networkTemplatesNotEqual);

        assertEquals(UNDERLYING_NETWORK_TEMPLATES, networkTemplatesEqual);
        assertEquals(config, configEqual);

        assertNotEquals(UNDERLYING_NETWORK_TEMPLATES, networkTemplatesNotEqual);
        assertNotEquals(config, configNotEqual);
    }

    private static VcnGatewayConnectionConfig buildConfigWithGatewayOptionsForEqualityTest(
            Set<Integer> gatewayOptions) {
        return buildTestConfigWithGatewayOptions(
                new VcnGatewayConnectionConfig.Builder(
                        "buildConfigWithGatewayOptionsForEqualityTest", TUNNEL_CONNECTION_PARAMS),
                gatewayOptions);
    }

    @Test
    public void testVcnGatewayOptionsEquality() throws Exception {
        final VcnGatewayConnectionConfig config =
                buildConfigWithGatewayOptionsForEqualityTest(GATEWAY_OPTIONS);

        final VcnGatewayConnectionConfig configEqual =
                buildConfigWithGatewayOptionsForEqualityTest(GATEWAY_OPTIONS);

        final VcnGatewayConnectionConfig configNotEqual =
                buildConfigWithGatewayOptionsForEqualityTest(Collections.emptySet());

        assertEquals(config, configEqual);
        assertNotEquals(config, configNotEqual);
    }

    @Test
    public void testSafeModeEnableDisableEquality() throws Exception {
        final VcnGatewayConnectionConfig config = newBuilderMinimal().build();
        final VcnGatewayConnectionConfig configEqual = newBuilderMinimal().build();

        assertEquals(config.isSafeModeEnabled(), configEqual.isSafeModeEnabled());

        final VcnGatewayConnectionConfig configNotEqual =
                newBuilderMinimal().setSafeModeEnabled(false).build();

        assertEquals(config, configEqual);
        assertNotEquals(config, configNotEqual);
    }
}
