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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

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
}
