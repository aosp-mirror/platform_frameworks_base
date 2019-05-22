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

package android.net.captiveportal;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.MalformedURLException;
import java.text.ParseException;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CaptivePortalProbeSpecTest {

    @Test
    public void testGetResult_Regex() throws MalformedURLException, ParseException {
        // 2xx status or 404, with an empty (match everything) location regex
        CaptivePortalProbeSpec statusRegexSpec = CaptivePortalProbeSpec.parseSpec(
                "http://www.google.com@@/@@2[0-9]{2}|404@@/@@");

        // 404, or 301/302 redirect to some HTTPS page under google.com
        CaptivePortalProbeSpec redirectSpec = CaptivePortalProbeSpec.parseSpec(
                "http://google.com@@/@@404|30[12]@@/@@https://([0-9a-z]+\\.)*google\\.com.*");

        assertSuccess(statusRegexSpec.getResult(200, null));
        assertSuccess(statusRegexSpec.getResult(299, "qwer"));
        assertSuccess(statusRegexSpec.getResult(404, null));
        assertSuccess(statusRegexSpec.getResult(404, ""));

        assertPortal(statusRegexSpec.getResult(300, null));
        assertPortal(statusRegexSpec.getResult(399, "qwer"));
        assertPortal(statusRegexSpec.getResult(500, null));

        assertSuccess(redirectSpec.getResult(404, null));
        assertSuccess(redirectSpec.getResult(404, ""));
        assertSuccess(redirectSpec.getResult(301, "https://www.google.com"));
        assertSuccess(redirectSpec.getResult(301, "https://www.google.com/test?q=3"));
        assertSuccess(redirectSpec.getResult(302, "https://google.com/test?q=3"));

        assertPortal(redirectSpec.getResult(299, "https://google.com/test?q=3"));
        assertPortal(redirectSpec.getResult(299, ""));
        assertPortal(redirectSpec.getResult(499, null));
        assertPortal(redirectSpec.getResult(301, "http://login.portal.example.com/loginpage"));
        assertPortal(redirectSpec.getResult(302, "http://www.google.com/test?q=3"));
    }

    @Test(expected = ParseException.class)
    public void testParseSpec_Empty() throws MalformedURLException, ParseException {
        CaptivePortalProbeSpec.parseSpec("");
    }

    @Test(expected = ParseException.class)
    public void testParseSpec_Null() throws MalformedURLException, ParseException {
        CaptivePortalProbeSpec.parseSpec(null);
    }

    @Test(expected = ParseException.class)
    public void testParseSpec_MissingParts() throws MalformedURLException, ParseException {
        CaptivePortalProbeSpec.parseSpec("http://google.com/@@/@@123");
    }

    @Test(expected = ParseException.class)
    public void testParseSpec_TooManyParts() throws MalformedURLException, ParseException {
        CaptivePortalProbeSpec.parseSpec("http://google.com/@@/@@123@@/@@456@@/@@extra");
    }

    @Test(expected = ParseException.class)
    public void testParseSpec_InvalidStatusRegex() throws MalformedURLException, ParseException {
        CaptivePortalProbeSpec.parseSpec("http://google.com/@@/@@unmatched(parenthesis@@/@@456");
    }

    @Test(expected = ParseException.class)
    public void testParseSpec_InvalidLocationRegex() throws MalformedURLException, ParseException {
        CaptivePortalProbeSpec.parseSpec("http://google.com/@@/@@123@@/@@unmatched[[]bracket");
    }

    @Test(expected = MalformedURLException.class)
    public void testParseSpec_EmptyURL() throws MalformedURLException, ParseException {
        CaptivePortalProbeSpec.parseSpec("@@/@@123@@/@@123");
    }

    @Test(expected = ParseException.class)
    public void testParseSpec_NoParts() throws MalformedURLException, ParseException {
        CaptivePortalProbeSpec.parseSpec("invalid");
    }

    @Test(expected = MalformedURLException.class)
    public void testParseSpec_RegexInvalidUrl() throws MalformedURLException, ParseException {
        CaptivePortalProbeSpec.parseSpec("notaurl@@/@@123@@/@@123");
    }

    @Test
    public void testParseSpecOrNull_UsesSpec() {
        final String specUrl = "http://google.com/probe";
        final String redirectUrl = "https://google.com/probe";
        CaptivePortalProbeSpec spec = CaptivePortalProbeSpec.parseSpecOrNull(
                specUrl + "@@/@@302@@/@@" + redirectUrl);
        assertEquals(specUrl, spec.getUrl().toString());

        assertPortal(spec.getResult(302, "http://portal.example.com"));
        assertSuccess(spec.getResult(302, redirectUrl));
    }

    @Test
    public void testParseSpecOrNull_UsesFallback() throws MalformedURLException {
        CaptivePortalProbeSpec spec = CaptivePortalProbeSpec.parseSpecOrNull(null);
        assertNull(spec);

        spec = CaptivePortalProbeSpec.parseSpecOrNull("");
        assertNull(spec);

        spec = CaptivePortalProbeSpec.parseSpecOrNull("@@/@@ @@/@@ @@/@@");
        assertNull(spec);

        spec = CaptivePortalProbeSpec.parseSpecOrNull("invalid@@/@@123@@/@@456");
        assertNull(spec);
    }

    @Test
    public void testParseSpecOrUseStatusCodeFallback_EmptySpec() throws MalformedURLException {
        CaptivePortalProbeSpec spec = CaptivePortalProbeSpec.parseSpecOrNull("");
        assertNull(spec);
    }

    private void assertIsStatusSpec(CaptivePortalProbeSpec spec) {
        assertSuccess(spec.getResult(204, null));
        assertSuccess(spec.getResult(204, "1234"));

        assertPortal(spec.getResult(200, null));
        assertPortal(spec.getResult(301, null));
        assertPortal(spec.getResult(302, "1234"));
        assertPortal(spec.getResult(399, ""));

        assertFailed(spec.getResult(404, null));
        assertFailed(spec.getResult(500, "1234"));
    }

    private void assertPortal(CaptivePortalProbeResult result) {
        assertTrue(result.isPortal());
    }

    private void assertSuccess(CaptivePortalProbeResult result) {
        assertTrue(result.isSuccessful());
    }

    private void assertFailed(CaptivePortalProbeResult result) {
        assertTrue(result.isFailed());
    }
}
