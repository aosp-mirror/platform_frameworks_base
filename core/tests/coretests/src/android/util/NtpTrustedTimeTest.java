/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Network;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NtpTrustedTimeTest {

    // Valid absolute URIs, but not ones that will be accepted as NTP server URIs.
    private static final List<String> BAD_ABSOLUTE_NTP_URIS = Arrays.asList(
            "ntp://:123/",
            "ntp://:123",
            "ntp://:/",
            "ntp://:",
            "ntp://foobar:abc/",
            "ntp://foobar:abc",
            "ntp://foobar:456:789/",
            "ntp://foobar:456:789",
            "ntp://foobar:456:abc/",
            "ntp://foobar:456:abc"
    );

    // Valid relative URIs, but not ones that will be accepted as NTP server URIs.
    private static final List<String> BAD_RELATIVE_NTP_URIS = Arrays.asList(
            "foobar",
            "/foobar",
            "foobar:456"
    );

    // Valid NTP server URIs: input value -> expected URI.toString() value.
    private static final Map<String, String> GOOD_NTP_URIS = Map.of(
            "ntp://foobar", "ntp://foobar",
            "ntp://foobar/", "ntp://foobar/",
            "ntp://foobar:456/", "ntp://foobar:456/",
            "ntp://foobar:456", "ntp://foobar:456"
    );

    private static final URI VALID_SERVER_URI = URI.create("ntp://foobar/");

    @Test
    public void testParseNtpServerSetting() {
        assertEquals(URI.create("ntp://foobar"), NtpTrustedTime.parseNtpServerSetting("foobar"));

        // Legacy settings values which could easily be confused with relative URIs. Parsing of this
        // legacy form doesn't have to be robust / treated as errors: Android has never supported
        // string like these, and so they won't work properly.
        assertNull(NtpTrustedTime.parseNtpServerSetting("foobar:123"));
        assertNull(NtpTrustedTime.parseNtpServerSetting("/foobar"));

        // NTP URI cases that must not be accepted.
        for (String badNtpUri : BAD_ABSOLUTE_NTP_URIS) {
            assertNull("Input: \"" + badNtpUri + "\"",
                    NtpTrustedTime.parseNtpServerSetting(badNtpUri));
        }

        // Valid URIs
        for (Map.Entry<String, String> goodNtpUri : GOOD_NTP_URIS.entrySet()) {
            URI uri = NtpTrustedTime.parseNtpServerSetting(goodNtpUri.getKey());
            assertNotNull(goodNtpUri.getKey(), uri);
            assertEquals(goodNtpUri.getValue(), uri.toString());
        }
    }

    @Test
    public void testParseNtpUriStrict() throws Exception {
        // ntp: URI cases that must not be accepted.
        for (String badNtpUri : BAD_ABSOLUTE_NTP_URIS) {
            assertParseNtpUriStrictThrows(badNtpUri);
        }

        for (String badNtpUri : BAD_RELATIVE_NTP_URIS) {
            assertParseNtpUriStrictThrows(badNtpUri);
        }

        // Bad scheme.
        assertParseNtpUriStrictThrows("notntp://foobar:123");

        // Valid NTP URIs
        for (Map.Entry<String, String> goodNtpUri : GOOD_NTP_URIS.entrySet()) {
            URI uri = NtpTrustedTime.parseNtpUriStrict(goodNtpUri.getKey());
            assertNotNull(goodNtpUri.getKey(), uri);
            assertEquals(goodNtpUri.getValue(), uri.toString());
        }
    }

    private void assertParseNtpUriStrictThrows(String badNtpUri) throws Exception {
        assertThrows("Input: \"" + badNtpUri, URISyntaxException.class,
                () -> NtpTrustedTime.parseNtpUriStrict(badNtpUri));
    }

    @Test(expected = NullPointerException.class)
    public void testNtpConfig_nullConstructorServerInfo() {
        new NtpTrustedTime.NtpConfig(null, Duration.ofSeconds(5));
    }

    @Test(expected = NullPointerException.class)
    public void testNtpConfig_nullConstructorTimeout() {
        new NtpTrustedTime.NtpConfig(VALID_SERVER_URI, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNtpConfig_zeroTimeout() {
        new NtpTrustedTime.NtpConfig(VALID_SERVER_URI, Duration.ofMillis(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNtpConfig_negativeTimeout() {
        new NtpTrustedTime.NtpConfig(VALID_SERVER_URI, Duration.ofMillis(-1));
    }

    @Test
    public void testForceRefresh_nullConfig() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(null);

        assertFalse(ntpTrustedTime.forceRefresh());

        assertFalse(ntpTrustedTime.hasCache());
        assertEquals(0, ntpTrustedTime.getCachedNtpTime());
        assertEquals(0, ntpTrustedTime.getCachedNtpTimeReference());
        assertEquals(Long.MAX_VALUE, ntpTrustedTime.getCacheAge());
        assertNull(ntpTrustedTime.getCachedTimeResult());

        verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
        verify(ntpTrustedTime, never()).getNetwork();
        verify(ntpTrustedTime, never()).queryNtpServer(any(), any(), any());
    }

    @Test
    public void testForceRefresh_noConnectivity() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);
        URI serverUri = URI.create("ntp://ntpserver.name");
        Duration timeout = Duration.ofSeconds(5);
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                new NtpTrustedTime.NtpConfig(serverUri, timeout));

        when(ntpTrustedTime.getNetwork()).thenReturn(null);

        assertFalse(ntpTrustedTime.forceRefresh());

        assertFalse(ntpTrustedTime.hasCache());
        assertEquals(0, ntpTrustedTime.getCachedNtpTime());
        assertEquals(0, ntpTrustedTime.getCachedNtpTimeReference());
        assertEquals(Long.MAX_VALUE, ntpTrustedTime.getCacheAge());
        assertNull(ntpTrustedTime.getCachedTimeResult());

        verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
        verify(ntpTrustedTime, times(1)).getNetwork();
        verify(ntpTrustedTime, never()).queryNtpServer(any(), any(), any());
    }

    @Test
    public void testForceRefresh_queryFailed() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);
        URI serverUri = URI.create("ntp://ntpserver.name");
        Duration timeout = Duration.ofSeconds(5);
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                new NtpTrustedTime.NtpConfig(serverUri, timeout));

        Network network = mock(Network.class);
        when(ntpTrustedTime.getNetwork()).thenReturn(network);

        when(ntpTrustedTime.queryNtpServer(network, serverUri, timeout)).thenReturn(null);

        assertFalse(ntpTrustedTime.forceRefresh());

        assertFalse(ntpTrustedTime.hasCache());
        assertEquals(0, ntpTrustedTime.getCachedNtpTime());
        assertEquals(0, ntpTrustedTime.getCachedNtpTimeReference());
        assertEquals(Long.MAX_VALUE, ntpTrustedTime.getCacheAge());
        assertNull(ntpTrustedTime.getCachedTimeResult());

        verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
        verify(ntpTrustedTime, times(1)).getNetwork();
        verify(ntpTrustedTime, times(1)).queryNtpServer(network, serverUri, timeout);
    }

    @Test
    public void testForceRefresh_querySucceeded() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);
        URI serverUri = URI.create("ntp://ntpserver.name");
        Duration timeout = Duration.ofSeconds(5);
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                new NtpTrustedTime.NtpConfig(serverUri, timeout));

        Network network = mock(Network.class);
        when(ntpTrustedTime.getNetwork()).thenReturn(network);

        NtpTrustedTime.TimeResult successResult = new NtpTrustedTime.TimeResult(123L, 456L, 789,
                InetSocketAddress.createUnresolved("placeholder", 123));
        when(ntpTrustedTime.queryNtpServer(network, serverUri, timeout)).thenReturn(successResult);

        assertTrue(ntpTrustedTime.forceRefresh());

        assertTrue(ntpTrustedTime.hasCache());
        assertEquals(successResult.getTimeMillis(), ntpTrustedTime.getCachedNtpTime());
        assertEquals(successResult.getElapsedRealtimeMillis(),
                ntpTrustedTime.getCachedNtpTimeReference());
        assertTrue(ntpTrustedTime.getCacheAge() != Long.MAX_VALUE);
        assertEquals(successResult, ntpTrustedTime.getCachedTimeResult());

        verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
        verify(ntpTrustedTime, times(1)).getNetwork();
        verify(ntpTrustedTime, times(1)).queryNtpServer(network, serverUri, timeout);
    }

    @Test
    public void testForceRefresh_keepsOldValueOnFailure() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);
        URI serverUri = URI.create("ntp://ntpserver.name");
        Duration timeout = Duration.ofSeconds(5);
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                new NtpTrustedTime.NtpConfig(serverUri, timeout));

        Network network = mock(Network.class);
        when(ntpTrustedTime.getNetwork()).thenReturn(network);

        NtpTrustedTime.TimeResult successResult = new NtpTrustedTime.TimeResult(123L, 456L, 789,
                InetSocketAddress.createUnresolved("placeholder", 123));
        when(ntpTrustedTime.queryNtpServer(network, serverUri, timeout)).thenReturn(successResult);

        assertTrue(ntpTrustedTime.forceRefresh());

        assertTrue(ntpTrustedTime.hasCache());
        assertEquals(successResult, ntpTrustedTime.getCachedTimeResult());

        when(ntpTrustedTime.queryNtpServer(network, serverUri, timeout)).thenReturn(null);

        assertFalse(ntpTrustedTime.forceRefresh());

        assertTrue(ntpTrustedTime.hasCache());
        assertEquals(successResult, ntpTrustedTime.getCachedTimeResult());
    }

    @Test
    public void testForceRefresh_keepsNewValueOnSuccess() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);
        URI serverUri = URI.create("ntp://ntpserver.name");
        Duration timeout = Duration.ofSeconds(5);
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                new NtpTrustedTime.NtpConfig(serverUri, timeout));

        Network network = mock(Network.class);
        when(ntpTrustedTime.getNetwork()).thenReturn(network);

        NtpTrustedTime.TimeResult successResult1 = new NtpTrustedTime.TimeResult(123L, 456L, 789,
                InetSocketAddress.createUnresolved("placeholder", 123));
        when(ntpTrustedTime.queryNtpServer(network, serverUri, timeout)).thenReturn(successResult1);

        assertTrue(ntpTrustedTime.forceRefresh());

        assertTrue(ntpTrustedTime.hasCache());
        assertEquals(successResult1, ntpTrustedTime.getCachedTimeResult());

        NtpTrustedTime.TimeResult successResult2 = new NtpTrustedTime.TimeResult(123L, 456L, 789,
                InetSocketAddress.createUnresolved("placeholder", 123));
        when(ntpTrustedTime.queryNtpServer(network, serverUri, timeout)).thenReturn(successResult2);

        assertTrue(ntpTrustedTime.forceRefresh());

        assertTrue(ntpTrustedTime.hasCache());
        assertEquals(successResult2, ntpTrustedTime.getCachedTimeResult());
    }
}
