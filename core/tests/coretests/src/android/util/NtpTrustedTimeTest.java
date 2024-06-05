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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import static java.lang.String.join;
import static java.util.Arrays.asList;

import android.net.Network;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SmallTest
@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = NtpTrustedTime.class)
public class NtpTrustedTimeTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private static final Duration VALID_TIMEOUT = Duration.ofSeconds(5);

    // Valid absolute URIs, but not ones that will be accepted as NTP server URIs.
    private static final List<String> BAD_ABSOLUTE_NTP_URIS = asList(
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
    private static final List<String> BAD_RELATIVE_NTP_URIS = asList(
            "foobar",
            "/foobar",
            "foobar:456"
    );

    // Valid NTP server URIs: A pair of {input value} -> {expected URI.toString()} value.
    private static final List<Pair<String, String>> GOOD_NTP_URIS = asList(
            identityPair("ntp://foobar"),
            identityPair("ntp://foobar/"),
            identityPair("ntp://foobar:456/"),
            identityPair("ntp://foobar:456")
    );

    private static final List<URI> VALID_SERVER_URIS = createUris("ntp://foobar/");

    @Test
    public void testParseNtpServerSetting() {
        // Valid legacy settings single value.
        assertEquals(createUris("ntp://foobar"), NtpTrustedTime.parseNtpServerSetting("foobar"));

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

        // Valid single NTP URIs.
        for (Pair<String, String> goodNtpUri : GOOD_NTP_URIS) {
            List<URI> uris = NtpTrustedTime.parseNtpServerSetting(goodNtpUri.first);
            assertNotNull(uris);
            assertEquals(1, uris.size());
            URI actualUri = uris.get(0);
            assertNotNull(goodNtpUri.first, actualUri);
            assertEquals(goodNtpUri.second, actualUri.toString());
        }

        // Valid multi-server name value. Not historically supported, but it's easier to support
        // this than rule it out.
        final String multiSettingDelimiter = NtpTrustedTime.NTP_SETTING_SERVER_NAME_DELIMITER;
        String validMultiServerNameSetting = join(multiSettingDelimiter, "foobar", "barbaz");
        List<URI> expectedMultiServerNameUris = createUris("ntp://foobar", "ntp://barbaz");
        assertParseNtpServerSettingResult(expectedMultiServerNameUris, validMultiServerNameSetting);

        // Any invalid values should result in a null return value.
        String invalidServerName = "foobar:123";
        assertNull(NtpTrustedTime.parseNtpServerSetting(
                join(multiSettingDelimiter, validMultiServerNameSetting, invalidServerName)));
        assertNull(NtpTrustedTime.parseNtpServerSetting(
                join(multiSettingDelimiter, invalidServerName, validMultiServerNameSetting)));

        // Valid multi-NTP URL string.
        Pair<String, String> goodNtpUri1 = GOOD_NTP_URIS.get(0);
        Pair<String, String> goodNtpUri2 = GOOD_NTP_URIS.get(1);
        String validMultiNtpUriSetting =
                join(multiSettingDelimiter, goodNtpUri1.first, goodNtpUri2.first);
        List<URI> expectedMultiNtpUris = createUris(goodNtpUri1.second, goodNtpUri2.second);
        assertParseNtpServerSettingResult(expectedMultiNtpUris, validMultiNtpUriSetting);

        // Valid string containing both old and new settings forms.
        String validCombinedMultiNtpSetting =
                join(multiSettingDelimiter, validMultiNtpUriSetting, validMultiServerNameSetting);
        List<URI> expectedCombinedNtpUris =
                Stream.concat(expectedMultiNtpUris.stream(), expectedMultiServerNameUris.stream())
                        .collect(Collectors.toList());
        assertParseNtpServerSettingResult(expectedCombinedNtpUris, validCombinedMultiNtpSetting);
    }

    private static void assertParseNtpServerSettingResult(
            List<URI> expectedUris, String settingsString) {
        assertEquals("Input: " + settingsString, expectedUris,
                NtpTrustedTime.parseNtpServerSetting(settingsString));
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
        for (Pair<String, String> goodNtpUri : GOOD_NTP_URIS) {
            URI uri = NtpTrustedTime.parseNtpUriStrict(goodNtpUri.first);
            assertNotNull(goodNtpUri.first, uri);
            assertEquals(goodNtpUri.second, uri.toString());
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
        new NtpTrustedTime.NtpConfig(VALID_SERVER_URIS, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNtpConfig_zeroTimeout() {
        new NtpTrustedTime.NtpConfig(VALID_SERVER_URIS, Duration.ofMillis(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNtpConfig_negativeTimeout() {
        new NtpTrustedTime.NtpConfig(VALID_SERVER_URIS, Duration.ofMillis(-1));
    }

    @Test
    public void testForceRefreshDefaultNetwork_noConnectivity() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        Network network = mock(Network.class);
        when(ntpTrustedTime.getDefaultNetwork()).thenReturn(network);
        when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(false);

        assertFalse(ntpTrustedTime.forceRefresh());

        InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
        inOrder.verify(ntpTrustedTime, times(1)).getDefaultNetwork();
        inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
        inOrder.verifyNoMoreInteractions();

        assertNoCachedTimeValueResult(ntpTrustedTime);
    }

    @Test
    public void testForceRefreshDefaultNetwork_noActiveNetwork() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        when(ntpTrustedTime.getDefaultNetwork()).thenReturn(null);

        assertFalse(ntpTrustedTime.forceRefresh());

        InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
        inOrder.verify(ntpTrustedTime, times(1)).getDefaultNetwork();
        inOrder.verifyNoMoreInteractions();

        assertNoCachedTimeValueResult(ntpTrustedTime);
    }

    @Test
    public void testForceRefreshDefaultNetwork_nullConfig() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        Network network = mock(Network.class);
        when(ntpTrustedTime.getDefaultNetwork()).thenReturn(network);
        when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(null);

        assertFalse(ntpTrustedTime.forceRefresh());

        InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
        inOrder.verify(ntpTrustedTime, times(1)).getDefaultNetwork();
        inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
        inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
        inOrder.verifyNoMoreInteractions();

        assertNoCachedTimeValueResult(ntpTrustedTime);
    }

    @Test
    public void testForceRefresh_nullConfig() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        Network network = mock(Network.class);
        when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(null);

        assertFalse(ntpTrustedTime.forceRefresh(network));

        InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
        inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
        inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
        inOrder.verifyNoMoreInteractions();

        assertNoCachedTimeValueResult(ntpTrustedTime);
    }

    @Test
    public void testForceRefresh_noConnectivity() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        Network network = mock(Network.class);
        when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(false);

        assertFalse(ntpTrustedTime.forceRefresh(network));

        InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
        inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
        inOrder.verifyNoMoreInteractions();

        assertNoCachedTimeValueResult(ntpTrustedTime);
    }

    @Test
    public void testForceRefresh_singleServer_queryFailed() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        Network network = mock(Network.class);
        when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
        List<URI> serverUris = createUris("ntp://ntpserver.name");
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
        when(ntpTrustedTime.queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT))
                .thenReturn(null);

        assertFalse(ntpTrustedTime.forceRefresh(network));

        InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
        inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
        inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
        inOrder.verify(ntpTrustedTime, times(1))
                .queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT);
        inOrder.verifyNoMoreInteractions();

        assertNoCachedTimeValueResult(ntpTrustedTime);
    }

    @Test
    public void testForceRefresh_singleServer_querySucceeded() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        Network network = mock(Network.class);
        when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
        List<URI> serverUris = createUris("ntp://ntpserver.name");
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
        NtpTrustedTime.TimeResult successResult = new NtpTrustedTime.TimeResult(123L, 456L, 789,
                InetSocketAddress.createUnresolved("placeholder", 123));
        when(ntpTrustedTime.queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT))
                .thenReturn(successResult);

        assertTrue(ntpTrustedTime.forceRefresh(network));

        InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
        inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
        inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
        inOrder.verify(ntpTrustedTime, times(1))
                .queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT);
        inOrder.verifyNoMoreInteractions();

        assertCachedTimeValueResult(ntpTrustedTime, successResult);
    }

    @Test
    public void testForceRefresh_multiServer_firstQueryFailed() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        Network network = mock(Network.class);
        when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
        List<URI> serverUris = createUris("ntp://ntpserver1.name", "ntp://ntpserver2.name");
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
        when(ntpTrustedTime.queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT))
                .thenReturn(null);
        NtpTrustedTime.TimeResult successResult = new NtpTrustedTime.TimeResult(123L, 456L, 789,
                InetSocketAddress.createUnresolved("placeholder", 123));
        when(ntpTrustedTime.queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT))
                .thenReturn(successResult);

        assertTrue(ntpTrustedTime.forceRefresh(network));

        InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
        inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
        inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
        inOrder.verify(ntpTrustedTime, times(1))
                .queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT);
        inOrder.verify(ntpTrustedTime, times(1))
                .queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT);
        inOrder.verifyNoMoreInteractions();

        assertCachedTimeValueResult(ntpTrustedTime, successResult);
    }

    @Test
    public void testForceRefresh_multiServer_firstQuerySucceeded() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        Network network = mock(Network.class);
        when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
        List<URI> serverUris = createUris("ntp://ntpserver1.name", "ntp://ntpserver2.name");
        when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
        NtpTrustedTime.TimeResult successResult = new NtpTrustedTime.TimeResult(123L, 456L, 789,
                InetSocketAddress.createUnresolved("placeholder", 123));
        when(ntpTrustedTime.queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT))
                .thenReturn(successResult);

        assertTrue(ntpTrustedTime.forceRefresh(network));

        InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
        inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
        inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
        inOrder.verify(ntpTrustedTime, times(1))
                .queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT);
        inOrder.verifyNoMoreInteractions();

        assertCachedTimeValueResult(ntpTrustedTime, successResult);
    }

    @Test
    public void testForceRefresh_multiServer_keepsOldValueOnFailure() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        Network network = mock(Network.class);
        List<URI> serverUris = createUris("ntp://ntpserver1.name", "ntp://ntpserver2.name");
        NtpTrustedTime.TimeResult successResult = new NtpTrustedTime.TimeResult(123L, 456L, 789,
                InetSocketAddress.createUnresolved("placeholder", 123));

        // First forceRefresh() succeeds.
        {
            when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
            when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                    new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT))
                    .thenReturn(successResult);

            assertTrue(ntpTrustedTime.forceRefresh(network));

            InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
            inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
            inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT);
            inOrder.verifyNoMoreInteractions();

            assertCachedTimeValueResult(ntpTrustedTime, successResult);

            reset(ntpTrustedTime);
        }

        // Next forceRefresh() fails, keeping the result of the first forceRefresh().
        {
            when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
            when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                    new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(0),
                    VALID_TIMEOUT)).thenReturn(null);
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(1),
                    VALID_TIMEOUT)).thenReturn(null);

            assertFalse(ntpTrustedTime.forceRefresh(network));

            InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
            inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
            inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT);
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT);
            inOrder.verifyNoMoreInteractions();

            assertCachedTimeValueResult(ntpTrustedTime, successResult);

            reset(ntpTrustedTime);
        }
    }

    /**
     * A complex test demonstrating the properties of the multi-server algorithm in different
     * scenarios / states.
     */
    @Test
    public void testForceRefresh_multiServer_complex() {
        NtpTrustedTime ntpTrustedTime = spy(NtpTrustedTime.class);

        Network network = mock(Network.class);
        List<URI> serverUris = createUris(
                "ntp://ntpserver1.name", "ntp://ntpserver2.name", "ntp://ntpserver3.name");
        NtpTrustedTime.TimeResult successResult1 = new NtpTrustedTime.TimeResult(111L, 111L, 111,
                InetSocketAddress.createUnresolved("placeholder", 111));
        NtpTrustedTime.TimeResult successResult2 = new NtpTrustedTime.TimeResult(222L, 222L, 222,
                InetSocketAddress.createUnresolved("placeholder", 222));
        NtpTrustedTime.TimeResult successResult3 = new NtpTrustedTime.TimeResult(333L, 333L, 333,
                InetSocketAddress.createUnresolved("placeholder", 333));

        // The first forceRefresh() should the URIs in the original order. Here, we fail the first
        // and succeed with the second.
        {
            when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
            when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                    new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT))
                    .thenReturn(null);
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT))
                    .thenReturn(successResult1);

            assertTrue(ntpTrustedTime.forceRefresh(network));

            InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
            inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
            inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT);
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT);
            inOrder.verifyNoMoreInteractions();

            assertCachedTimeValueResult(ntpTrustedTime, successResult1);

            reset(ntpTrustedTime);
        }

        // forceRefresh() should try starting with the last successful server, which will succeed.
        {
            when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
            when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                    new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT))
                    .thenReturn(successResult2);

            assertTrue(ntpTrustedTime.forceRefresh(network));

            InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
            inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
            inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT);
            inOrder.verifyNoMoreInteractions();

            assertCachedTimeValueResult(ntpTrustedTime, successResult2);

            reset(ntpTrustedTime);
        }

        // forceRefresh() should try starting with the last successful server, but try the others in
        // order. It will succeed with the final server.
        {
            when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
            when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                    new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT))
                    .thenReturn(null);
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT))
                    .thenReturn(null);
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(2), VALID_TIMEOUT))
                    .thenReturn(successResult3);

            assertTrue(ntpTrustedTime.forceRefresh(network));

            InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
            inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
            inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT);
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT);
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(2), VALID_TIMEOUT);
            inOrder.verifyNoMoreInteractions();

            assertCachedTimeValueResult(ntpTrustedTime, successResult3);

            reset(ntpTrustedTime);
        }

        // forceRefresh() should try starting with the last successful server, but try the others in
        // order. It will fail with all.
        {
            when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
            when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                    new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(2), VALID_TIMEOUT))
                    .thenReturn(null);
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT))
                    .thenReturn(null);
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT))
                    .thenReturn(null);

            assertFalse(ntpTrustedTime.forceRefresh(network));

            InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
            inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
            inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(2), VALID_TIMEOUT);
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT);
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT);
            inOrder.verifyNoMoreInteractions();

            assertCachedTimeValueResult(ntpTrustedTime, successResult3);

            reset(ntpTrustedTime);
        }

        // forceRefresh() should try starting with the last successful server, but try the others in
        // order. It will succeed on the last.
        {
            when(ntpTrustedTime.isNetworkConnected(network)).thenReturn(true);
            when(ntpTrustedTime.getNtpConfigInternal()).thenReturn(
                    new NtpTrustedTime.NtpConfig(serverUris, VALID_TIMEOUT));
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(2), VALID_TIMEOUT))
                    .thenReturn(null);
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT))
                    .thenReturn(null);
            when(ntpTrustedTime.queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT))
                    .thenReturn(successResult1);

            assertTrue(ntpTrustedTime.forceRefresh(network));

            InOrder inOrder = Mockito.inOrder(ntpTrustedTime);
            inOrder.verify(ntpTrustedTime, times(1)).isNetworkConnected(network);
            inOrder.verify(ntpTrustedTime, times(1)).getNtpConfigInternal();
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(2), VALID_TIMEOUT);
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(0), VALID_TIMEOUT);
            inOrder.verify(ntpTrustedTime, times(1))
                    .queryNtpServer(network, serverUris.get(1), VALID_TIMEOUT);
            inOrder.verifyNoMoreInteractions();

            assertCachedTimeValueResult(ntpTrustedTime, successResult1);

            reset(ntpTrustedTime);
        }
    }

    private static Pair<String, String> identityPair(String both) {
        return Pair.create(both, both);
    }

    private static List<URI> createUris(String... uriStrings) {
        return Arrays.stream(uriStrings).map(URI::create).collect(Collectors.toList());
    }

    @SuppressWarnings("deprecation")
    private static void assertNoCachedTimeValueResult(NtpTrustedTime ntpTrustedTime) {
        assertFalse(ntpTrustedTime.hasCache());
        assertEquals(0, ntpTrustedTime.getCachedNtpTime());
        assertEquals(0, ntpTrustedTime.getCachedNtpTimeReference());
        assertEquals(Long.MAX_VALUE, ntpTrustedTime.getCacheAge());
        assertNull(ntpTrustedTime.getCachedTimeResult());
    }

    @SuppressWarnings("deprecation")
    private static void assertCachedTimeValueResult(NtpTrustedTime ntpTrustedTime,
            NtpTrustedTime.TimeResult expected) {
        assertTrue(ntpTrustedTime.hasCache());
        assertEquals(expected.getTimeMillis(), ntpTrustedTime.getCachedNtpTime());
        assertEquals(expected.getElapsedRealtimeMillis(),
                ntpTrustedTime.getCachedNtpTimeReference());
        assertTrue(ntpTrustedTime.getCacheAge() != Long.MAX_VALUE);
        assertEquals(expected, ntpTrustedTime.getCachedTimeResult());
    }
}
