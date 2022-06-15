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

package com.android.server.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.util.ArrayMap;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Unit tests for {@link IpConfigStore}
 */
@RunWith(AndroidJUnit4.class)
public class IpConfigStoreTest {

    @Test
    public void backwardCompatibility2to3() throws IOException {
        final int KEY_CONFIG = 17;
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream outputStream = new DataOutputStream(byteStream);

        final IpConfiguration expectedConfig =
                newIpConfiguration(IpAssignment.DHCP, ProxySettings.NONE, null, null);

        // Emulate writing to old format.
        writeDhcpConfigV2(outputStream, KEY_CONFIG, expectedConfig);

        InputStream in = new ByteArrayInputStream(byteStream.toByteArray());
        ArrayMap<String, IpConfiguration> configurations = IpConfigStore.readIpConfigurations(in);

        assertNotNull(configurations);
        assertEquals(1, configurations.size());
        IpConfiguration actualConfig = configurations.get(String.valueOf(KEY_CONFIG));
        assertNotNull(actualConfig);
        assertEquals(expectedConfig, actualConfig);
    }

    @Test
    public void staticIpMultiNetworks() throws Exception {
        final String IFACE_1 = "eth0";
        final String IFACE_2 = "eth1";
        final String IP_ADDR_1 = "192.168.1.10/24";
        final String IP_ADDR_2 = "192.168.1.20/24";
        final String DNS_IP_ADDR_1 = "1.2.3.4";
        final String DNS_IP_ADDR_2 = "5.6.7.8";

        final ArrayList<InetAddress> dnsServers = new ArrayList<>();
        dnsServers.add(InetAddresses.parseNumericAddress(DNS_IP_ADDR_1));
        dnsServers.add(InetAddresses.parseNumericAddress(DNS_IP_ADDR_2));
        final StaticIpConfiguration staticIpConfiguration1 = new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress(IP_ADDR_1))
                .setDnsServers(dnsServers).build();
        final StaticIpConfiguration staticIpConfiguration2 = new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress(IP_ADDR_2))
                .setDnsServers(dnsServers).build();

        ProxyInfo proxyInfo =
                ProxyInfo.buildDirectProxy("10.10.10.10", 88, Arrays.asList("host1", "host2"));

        IpConfiguration expectedConfig1 = newIpConfiguration(IpAssignment.STATIC,
                ProxySettings.STATIC, staticIpConfiguration1, proxyInfo);
        IpConfiguration expectedConfig2 = newIpConfiguration(IpAssignment.STATIC,
                ProxySettings.STATIC, staticIpConfiguration2, proxyInfo);

        ArrayMap<String, IpConfiguration> expectedNetworks = new ArrayMap<>();
        expectedNetworks.put(IFACE_1, expectedConfig1);
        expectedNetworks.put(IFACE_2, expectedConfig2);

        MockedDelayedDiskWrite writer = new MockedDelayedDiskWrite();
        IpConfigStore store = new IpConfigStore(writer);
        store.writeIpConfigurations("file/path/not/used/", expectedNetworks);

        InputStream in = new ByteArrayInputStream(writer.byteStream.toByteArray());
        ArrayMap<String, IpConfiguration> actualNetworks = IpConfigStore.readIpConfigurations(in);
        assertNotNull(actualNetworks);
        assertEquals(2, actualNetworks.size());
        assertEquals(expectedNetworks.get(IFACE_1), actualNetworks.get(IFACE_1));
        assertEquals(expectedNetworks.get(IFACE_2), actualNetworks.get(IFACE_2));
    }

    private IpConfiguration newIpConfiguration(IpAssignment ipAssignment,
            ProxySettings proxySettings, StaticIpConfiguration staticIpConfig, ProxyInfo info) {
        final IpConfiguration config = new IpConfiguration();
        config.setIpAssignment(ipAssignment);
        config.setProxySettings(proxySettings);
        config.setStaticIpConfiguration(staticIpConfig);
        config.setHttpProxy(info);
        return config;
    }

    // This is simplified snapshot of code that was used to store values in V2 format (key as int).
    private static void writeDhcpConfigV2(DataOutputStream out, int configKey,
            IpConfiguration config) throws IOException {
        out.writeInt(2);  // VERSION 2
        switch (config.getIpAssignment()) {
            case DHCP:
                out.writeUTF("ipAssignment");
                out.writeUTF(config.getIpAssignment().toString());
                break;
            default:
                fail("Not supported in test environment");
        }

        out.writeUTF("id");
        out.writeInt(configKey);
        out.writeUTF("eos");
    }

    /** Synchronously writes into given byte steam */
    private static class MockedDelayedDiskWrite extends DelayedDiskWrite {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();

        @Override
        public void write(String filePath, Writer w) {
            DataOutputStream outputStream = new DataOutputStream(byteStream);

            try {
                w.onWriteCalled(outputStream);
            } catch (IOException e) {
                fail();
            }
        }
    }
}
