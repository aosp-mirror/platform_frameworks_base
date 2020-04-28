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

package android.net.util;

import static android.net.util.DnsUtils.IPV6_ADDR_SCOPE_GLOBAL;
import static android.net.util.DnsUtils.IPV6_ADDR_SCOPE_LINKLOCAL;
import static android.net.util.DnsUtils.IPV6_ADDR_SCOPE_SITELOCAL;

import static org.junit.Assert.assertEquals;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.InetAddresses;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DnsUtilsTest {
    private InetAddress stringToAddress(@NonNull String addr) {
        return InetAddresses.parseNumericAddress(addr);
    }

    private DnsUtils.SortableAddress makeSortableAddress(@NonNull String addr) {
        return makeSortableAddress(addr, null);
    }

    private DnsUtils.SortableAddress makeSortableAddress(@NonNull String addr,
            @Nullable String srcAddr) {
        return new DnsUtils.SortableAddress(stringToAddress(addr),
                srcAddr != null ? stringToAddress(srcAddr) : null);
    }

    @Test
    public void testRfc6724Comparator() {
        final List<DnsUtils.SortableAddress> test = Arrays.asList(
                makeSortableAddress("216.58.200.36"),             // Ipv4
                makeSortableAddress("2404:6800:4008:801::2004"),  // global
                makeSortableAddress("::1"),                       // loop back
                makeSortableAddress("fe80::c46f:1cff:fe04:39b4"), // link local
                makeSortableAddress("::ffff:192.168.95.3"),       // IPv4-mapped IPv6
                makeSortableAddress("2001::47c1"),                // teredo tunneling
                makeSortableAddress("::216.58.200.36"),           // IPv4-compatible
                makeSortableAddress("3ffe::1234:5678"));          // 6bone

        final List<InetAddress> expected = Arrays.asList(
                stringToAddress("::1"),                       // loop back
                stringToAddress("fe80::c46f:1cff:fe04:39b4"), // link local
                stringToAddress("2404:6800:4008:801::2004"),  // global
                stringToAddress("216.58.200.36"),             // Ipv4
                stringToAddress("::ffff:192.168.95.3"),       // IPv4-mapped IPv6
                stringToAddress("2001::47c1"),                // teredo tunneling
                stringToAddress("::216.58.200.36"),            // IPv4-compatible
                stringToAddress("3ffe::1234:5678"));          // 6bone

        Collections.sort(test, new DnsUtils.Rfc6724Comparator());

        for (int i = 0; i < test.size(); ++i) {
            assertEquals(test.get(i).address, expected.get(i));
        }

        // TODO: add more combinations
    }

    @Test
    public void testV4SortableAddress() {
        // Test V4 address
        DnsUtils.SortableAddress test = makeSortableAddress("216.58.200.36");
        assertEquals(test.hasSrcAddr, 0);
        assertEquals(test.prefixMatchLen, 0);
        assertEquals(test.address, stringToAddress("216.58.200.36"));
        assertEquals(test.labelMatch, 0);
        assertEquals(test.scopeMatch, 0);
        assertEquals(test.scope, IPV6_ADDR_SCOPE_GLOBAL);
        assertEquals(test.label, 4);
        assertEquals(test.precedence, 35);

        // Test V4 loopback address with the same source address
        test = makeSortableAddress("127.1.2.3", "127.1.2.3");
        assertEquals(test.hasSrcAddr, 1);
        assertEquals(test.prefixMatchLen, 0);
        assertEquals(test.address, stringToAddress("127.1.2.3"));
        assertEquals(test.labelMatch, 1);
        assertEquals(test.scopeMatch, 1);
        assertEquals(test.scope, IPV6_ADDR_SCOPE_LINKLOCAL);
        assertEquals(test.label, 4);
        assertEquals(test.precedence, 35);
    }

    @Test
    public void testV6SortableAddress() {
        // Test global address
        DnsUtils.SortableAddress test = makeSortableAddress("2404:6800:4008:801::2004");
        assertEquals(test.address, stringToAddress("2404:6800:4008:801::2004"));
        assertEquals(test.scope, IPV6_ADDR_SCOPE_GLOBAL);
        assertEquals(test.label, 1);
        assertEquals(test.precedence, 40);

        // Test global address with global source address
        test = makeSortableAddress("2404:6800:4008:801::2004",
                "2401:fa00:fc:fd00:6d6c:7199:b8e7:41d6");
        assertEquals(test.address, stringToAddress("2404:6800:4008:801::2004"));
        assertEquals(test.hasSrcAddr, 1);
        assertEquals(test.scope, IPV6_ADDR_SCOPE_GLOBAL);
        assertEquals(test.labelMatch, 1);
        assertEquals(test.scopeMatch, 1);
        assertEquals(test.label, 1);
        assertEquals(test.precedence, 40);
        assertEquals(test.prefixMatchLen, 13);

        // Test global address with linklocal source address
        test = makeSortableAddress("2404:6800:4008:801::2004", "fe80::c46f:1cff:fe04:39b4");
        assertEquals(test.hasSrcAddr, 1);
        assertEquals(test.scope, IPV6_ADDR_SCOPE_GLOBAL);
        assertEquals(test.labelMatch, 1);
        assertEquals(test.scopeMatch, 0);
        assertEquals(test.label, 1);
        assertEquals(test.precedence, 40);
        assertEquals(test.prefixMatchLen, 0);

        // Test loopback address with the same source address
        test = makeSortableAddress("::1", "::1");
        assertEquals(test.hasSrcAddr, 1);
        assertEquals(test.prefixMatchLen, 16 * 8);
        assertEquals(test.labelMatch, 1);
        assertEquals(test.scopeMatch, 1);
        assertEquals(test.scope, IPV6_ADDR_SCOPE_LINKLOCAL);
        assertEquals(test.label, 0);
        assertEquals(test.precedence, 50);

        // Test linklocal address
        test = makeSortableAddress("fe80::c46f:1cff:fe04:39b4");
        assertEquals(test.scope, IPV6_ADDR_SCOPE_LINKLOCAL);
        assertEquals(test.label, 1);
        assertEquals(test.precedence, 40);

        // Test linklocal address
        test = makeSortableAddress("fe80::");
        assertEquals(test.scope, IPV6_ADDR_SCOPE_LINKLOCAL);
        assertEquals(test.label, 1);
        assertEquals(test.precedence, 40);

        // Test 6to4 address
        test = makeSortableAddress("2002:c000:0204::");
        assertEquals(test.scope, IPV6_ADDR_SCOPE_GLOBAL);
        assertEquals(test.label, 2);
        assertEquals(test.precedence, 30);

        // Test unique local address
        test = makeSortableAddress("fc00::c000:13ab");
        assertEquals(test.scope, IPV6_ADDR_SCOPE_GLOBAL);
        assertEquals(test.label, 13);
        assertEquals(test.precedence, 3);

        // Test teredo tunneling address
        test = makeSortableAddress("2001::47c1");
        assertEquals(test.scope, IPV6_ADDR_SCOPE_GLOBAL);
        assertEquals(test.label, 5);
        assertEquals(test.precedence, 5);

        // Test IPv4-compatible addresses
        test = makeSortableAddress("::216.58.200.36");
        assertEquals(test.scope, IPV6_ADDR_SCOPE_GLOBAL);
        assertEquals(test.label, 3);
        assertEquals(test.precedence, 1);

        // Test site-local address
        test = makeSortableAddress("fec0::cafe:3ab2");
        assertEquals(test.scope, IPV6_ADDR_SCOPE_SITELOCAL);
        assertEquals(test.label, 11);
        assertEquals(test.precedence, 1);

        // Test 6bone address
        test = makeSortableAddress("3ffe::1234:5678");
        assertEquals(test.scope, IPV6_ADDR_SCOPE_GLOBAL);
        assertEquals(test.label, 12);
        assertEquals(test.precedence, 1);
    }
}
