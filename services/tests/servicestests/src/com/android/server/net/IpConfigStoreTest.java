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

import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArrayMap;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

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

        IpConfiguration expectedConfig = new IpConfiguration(IpAssignment.DHCP,
                ProxySettings.NONE, null, null);

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

        StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
        staticIpConfiguration.ipAddress = new LinkAddress(IP_ADDR_1);
        staticIpConfiguration.dnsServers.add(NetworkUtils.numericToInetAddress(DNS_IP_ADDR_1));
        staticIpConfiguration.dnsServers.add(NetworkUtils.numericToInetAddress(DNS_IP_ADDR_2));

        ProxyInfo proxyInfo = new ProxyInfo("10.10.10.10", 88, "host1,host2");

        IpConfiguration expectedConfig1 = new IpConfiguration(IpAssignment.STATIC,
                ProxySettings.STATIC, staticIpConfiguration, proxyInfo);
        IpConfiguration expectedConfig2 = new IpConfiguration(expectedConfig1);
        expectedConfig2.getStaticIpConfiguration().ipAddress = new LinkAddress(IP_ADDR_2);

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

    // This is simplified snapshot of code that was used to store values in V2 format (key as int).
    private static void writeDhcpConfigV2(DataOutputStream out, int configKey,
            IpConfiguration config) throws IOException {
        out.writeInt(2);  // VERSION 2
        switch (config.ipAssignment) {
            case DHCP:
                out.writeUTF("ipAssignment");
                out.writeUTF(config.ipAssignment.toString());
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
