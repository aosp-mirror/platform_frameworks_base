/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.AF_UNIX;
import static android.system.OsConstants.EPERM;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_STREAM;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.fail;

import android.system.ErrnoException;
import android.system.Os;

import androidx.test.runner.AndroidJUnit4;

import libcore.io.IoUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;
import java.util.TreeSet;

@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SmallTest
public class NetworkUtilsTest {
    @Test
    public void testRoutedIPv4AddressCount() {
        final TreeSet<IpPrefix> set = new TreeSet<>(IpPrefix.lengthComparator());
        // No routes routes to no addresses.
        assertEquals(0, NetworkUtils.routedIPv4AddressCount(set));

        set.add(new IpPrefix("0.0.0.0/0"));
        assertEquals(1l << 32, NetworkUtils.routedIPv4AddressCount(set));

        set.add(new IpPrefix("20.18.0.0/16"));
        set.add(new IpPrefix("20.18.0.0/24"));
        set.add(new IpPrefix("20.18.0.0/8"));
        // There is a default route, still covers everything
        assertEquals(1l << 32, NetworkUtils.routedIPv4AddressCount(set));

        set.clear();
        set.add(new IpPrefix("20.18.0.0/24"));
        set.add(new IpPrefix("20.18.0.0/8"));
        // The 8-length includes the 24-length prefix
        assertEquals(1l << 24, NetworkUtils.routedIPv4AddressCount(set));

        set.add(new IpPrefix("10.10.10.126/25"));
        // The 8-length does not include this 25-length prefix
        assertEquals((1l << 24) + (1 << 7), NetworkUtils.routedIPv4AddressCount(set));

        set.clear();
        set.add(new IpPrefix("1.2.3.4/32"));
        set.add(new IpPrefix("1.2.3.4/32"));
        set.add(new IpPrefix("1.2.3.4/32"));
        set.add(new IpPrefix("1.2.3.4/32"));
        assertEquals(1l, NetworkUtils.routedIPv4AddressCount(set));

        set.add(new IpPrefix("1.2.3.5/32"));
        set.add(new IpPrefix("1.2.3.6/32"));

        set.add(new IpPrefix("1.2.3.7/32"));
        set.add(new IpPrefix("1.2.3.8/32"));
        set.add(new IpPrefix("1.2.3.9/32"));
        set.add(new IpPrefix("1.2.3.0/32"));
        assertEquals(7l, NetworkUtils.routedIPv4AddressCount(set));

        // 1.2.3.4/30 eats 1.2.3.{4-7}/32
        set.add(new IpPrefix("1.2.3.4/30"));
        set.add(new IpPrefix("6.2.3.4/28"));
        set.add(new IpPrefix("120.2.3.4/16"));
        assertEquals(7l - 4 + 4 + 16 + 65536, NetworkUtils.routedIPv4AddressCount(set));
    }

    @Test
    public void testRoutedIPv6AddressCount() {
        final TreeSet<IpPrefix> set = new TreeSet<>(IpPrefix.lengthComparator());
        // No routes routes to no addresses.
        assertEquals(BigInteger.ZERO, NetworkUtils.routedIPv6AddressCount(set));

        set.add(new IpPrefix("::/0"));
        assertEquals(BigInteger.ONE.shiftLeft(128), NetworkUtils.routedIPv6AddressCount(set));

        set.add(new IpPrefix("1234:622a::18/64"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6adb/96"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6adb/8"));
        // There is a default route, still covers everything
        assertEquals(BigInteger.ONE.shiftLeft(128), NetworkUtils.routedIPv6AddressCount(set));

        set.clear();
        set.add(new IpPrefix("add4:f00:80:f7:1111::6adb/96"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6adb/8"));
        // The 8-length includes the 96-length prefix
        assertEquals(BigInteger.ONE.shiftLeft(120), NetworkUtils.routedIPv6AddressCount(set));

        set.add(new IpPrefix("10::26/64"));
        // The 8-length does not include this 64-length prefix
        assertEquals(BigInteger.ONE.shiftLeft(120).add(BigInteger.ONE.shiftLeft(64)),
                NetworkUtils.routedIPv6AddressCount(set));

        set.clear();
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad4/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad4/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad4/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad4/128"));
        assertEquals(BigInteger.ONE, NetworkUtils.routedIPv6AddressCount(set));

        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad5/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad6/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad7/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad8/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad9/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad0/128"));
        assertEquals(BigInteger.valueOf(7), NetworkUtils.routedIPv6AddressCount(set));

        // add4:f00:80:f7:1111::6ad4/126 eats add4:f00:8[:f7:1111::6ad{4-7}/128
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad4/126"));
        set.add(new IpPrefix("d00d:f00:80:f7:1111::6ade/124"));
        set.add(new IpPrefix("f00b:a33::/112"));
        assertEquals(BigInteger.valueOf(7l - 4 + 4 + 16 + 65536),
                NetworkUtils.routedIPv6AddressCount(set));
    }

    private static void expectSocketSuccess(String msg, int domain, int type) {
        try {
            IoUtils.closeQuietly(Os.socket(domain, type, 0));
        } catch (ErrnoException e) {
            fail(msg + e.getMessage());
        }
    }

    private static void expectSocketPemissionError(String msg, int domain, int type) {
        try {
            IoUtils.closeQuietly(Os.socket(domain, type, 0));
            fail(msg);
        } catch (ErrnoException e) {
            assertEquals(msg, e.errno, EPERM);
        }
    }

    private static void expectHasNetworking() {
        expectSocketSuccess("Creating a UNIX socket should not have thrown ErrnoException",
                AF_UNIX, SOCK_STREAM);
        expectSocketSuccess("Creating a AF_INET socket shouldn't have thrown ErrnoException",
                AF_INET, SOCK_DGRAM);
        expectSocketSuccess("Creating a AF_INET6 socket shouldn't have thrown ErrnoException",
                AF_INET6, SOCK_DGRAM);
    }

    private static void expectNoNetworking() {
        expectSocketSuccess("Creating a UNIX socket should not have thrown ErrnoException",
                AF_UNIX, SOCK_STREAM);
        expectSocketPemissionError(
                "Creating a AF_INET socket should have thrown ErrnoException(EPERM)",
                AF_INET, SOCK_DGRAM);
        expectSocketPemissionError(
                "Creating a AF_INET6 socket should have thrown ErrnoException(EPERM)",
                AF_INET6, SOCK_DGRAM);
    }

    @Test
    public void testSetAllowNetworkingForProcess() {
        expectHasNetworking();
        NetworkUtils.setAllowNetworkingForProcess(false);
        expectNoNetworking();
        NetworkUtils.setAllowNetworkingForProcess(true);
        expectHasNetworking();
    }
}
