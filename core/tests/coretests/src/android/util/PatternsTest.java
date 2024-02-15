/*
 * Copyright (C) 2010 The Android Open Source Project
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
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class PatternsTest {

    // Tests for Patterns.TOP_LEVEL_DOMAIN

    @Test
    @SmallTest
    public void testTldPattern() throws Exception {
        boolean t;

        t = Patterns.TOP_LEVEL_DOMAIN.matcher("com").matches();
        assertTrue("Missed valid TLD", t);

        // One of the new top level domain.
        t = Patterns.TOP_LEVEL_DOMAIN.matcher("me").matches();
        assertTrue("Missed valid TLD", t);

        // One of the new top level test domain.
        t = Patterns.TOP_LEVEL_DOMAIN.matcher("xn--0zwm56d").matches();
        assertTrue("Missed valid TLD", t);

        // One of the new top level unicode domain.
        t = Patterns.TOP_LEVEL_DOMAIN.matcher("\uD55C\uAD6D").matches();
        assertTrue("Missed valid TLD", t);

        t = Patterns.TOP_LEVEL_DOMAIN.matcher("mem").matches();
        assertFalse("Matched invalid TLD!", t);

        t = Patterns.TOP_LEVEL_DOMAIN.matcher("xn").matches();
        assertFalse("Matched invalid TLD!", t);

        t = Patterns.TOP_LEVEL_DOMAIN.matcher("xer").matches();
        assertFalse("Matched invalid TLD!", t);
    }

    // Tests for Patterns.IANA_TOP_LEVEL_DOMAINS

    @Test
    @SmallTest
    public void testIanaTopLevelDomains_matchesValidTld() throws Exception {
        Pattern pattern = Pattern.compile(Patterns.IANA_TOP_LEVEL_DOMAINS);
        assertTrue("Should match 'com'", pattern.matcher("com").matches());
    }

    @Test
    @SmallTest
    public void testIanaTopLevelDomains_matchesValidNewTld() throws Exception {
        Pattern pattern = Pattern.compile(Patterns.IANA_TOP_LEVEL_DOMAINS);
        assertTrue("Should match 'me'", pattern.matcher("me").matches());
    }

    @Test
    @SmallTest
    public void testIanaTopLevelDomains_matchesPunycodeTld() throws Exception {
        Pattern pattern = Pattern.compile(Patterns.IANA_TOP_LEVEL_DOMAINS);
        assertTrue("Should match Punycode TLD", pattern.matcher("xn--qxam").matches());
    }

    @Test
    @SmallTest
    public void testIanaTopLevelDomains_matchesIriTLD() throws Exception {
        Pattern pattern = Pattern.compile(Patterns.IANA_TOP_LEVEL_DOMAINS);
        assertTrue("Should match IRI TLD", pattern.matcher("\uD55C\uAD6D").matches());
    }

    @Test
    @SmallTest
    public void testIanaTopLevelDomains_doesNotMatchWrongTld() throws Exception {
        Pattern pattern = Pattern.compile(Patterns.IANA_TOP_LEVEL_DOMAINS);
        assertFalse("Should not match 'mem'", pattern.matcher("mem").matches());
    }

    @Test
    @SmallTest
    public void testIanaTopLevelDomains_doesNotMatchWrongPunycodeTld() throws Exception {
        Pattern pattern = Pattern.compile(Patterns.IANA_TOP_LEVEL_DOMAINS);
        assertFalse("Should not match invalid Punycode TLD", pattern.matcher("xn").matches());
    }

    // Tests for Patterns.WEB_URL

    @Test
    @SmallTest
    public void testWebUrl_matchesValidUrlWithSchemeAndHostname() throws Exception {
        String url = "http://www.android.com";
        assertTrue("Should match URL with scheme and hostname",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesValidUrlWithSchemeHostnameAndNewTld() throws Exception {
        String url = "http://www.android.me";
        assertTrue("Should match URL with scheme, hostname and new TLD",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesValidUrlWithHostnameAndNewTld() throws Exception {
        String url = "android.me";
        assertTrue("Should match URL with hostname and new TLD",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesChinesePunycodeUrlWithProtocol() throws Exception {
        String url = "http://xn--fsqu00a.xn--0zwm56d";
        assertTrue("Should match Chinese Punycode URL with protocol",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesChinesePunycodeUrlWithoutProtocol() throws Exception {
        String url = "xn--fsqu00a.xn--0zwm56d";
        assertTrue("Should match Chinese Punycode URL without protocol",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesArabicPunycodeUrlWithProtocol() throws Exception {
        String url = "http://xn--4gbrim.xn----rmckbbajlc6dj7bxne2c.xn--wgbh1c/ar/default.aspx";
        assertTrue("Should match arabic Punycode URL with protocol",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesArabicPunycodeUrlWithoutProtocol() throws Exception {
        String url = "xn--4gbrim.xn----rmckbbajlc6dj7bxne2c.xn--wgbh1c/ar/default.aspx";
        assertTrue("Should match Arabic Punycode URL without protocol",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesUrlWithUnicodeDomainNameWithProtocol() throws Exception {
        String url = "http://\uD604\uAE08\uC601\uC218\uC99D.kr";
        assertTrue("Should match URL with Unicode domain name",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesUrlWithUnicodeDomainNameWithoutProtocol() throws Exception {
        String url = "\uD604\uAE08\uC601\uC218\uC99D.kr";
        assertTrue("Should match URL without protocol and with Unicode domain name",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesUrlWithUnicodeTld() throws Exception {
        String url = "\uB3C4\uBA54\uC778.\uD55C\uAD6D";
        assertTrue("Should match URL with Unicode TLD",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesUrlWithUnicodePath() throws Exception {
        String url = "http://brainstormtech.blogs.fortune.cnn.com/2010/03/11/" +
                "top-five-moments-from-eric-schmidt\u2019s-talk-in-abu-dhabi/";
        assertTrue("Should match URL with Unicode path",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_doesNotMatchValidUrlWithInvalidProtocol() throws Exception {
        String url = "invalid://www.example.com";
        assertFalse("Should not match URL with invalid protocol",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesValidUrlWithPort() throws Exception {
        String url = "http://www.example.com:8080";
        assertTrue("Should match URL with port", Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesUrlWithPortAndQuery() throws Exception {
        String url = "http://www.example.com:8080/?foo=bar";
        assertTrue("Should match URL with port and query",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesUrlWithTilde() throws Exception {
        String url = "http://www.example.com:8080/~user/?foo=bar";
        assertTrue("Should match URL with tilde", Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesProtocolCaseInsensitive() throws Exception {
        String url = "hTtP://android.com";
        assertTrue("Protocol matching should be case insensitive",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesDomainNameWithDash() throws Exception {
        String url = "http://a-nd.r-oid.com";
        assertTrue("Should match dash in domain name",
                Patterns.WEB_URL.matcher(url).matches());

        url = "a-nd.r-oid.com";
        assertTrue("Should match dash in domain name",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesDomainNameWithUnderscore() throws Exception {
        String url = "http://a_nd.r_oid.com";
        assertTrue("Should match underscore in domain name",
                Patterns.WEB_URL.matcher(url).matches());

        url = "a_nd.r_oid.com";
        assertTrue("Should match underscore in domain name",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesPathAndQueryWithDollarSign() throws Exception {
        String url = "http://android.com/path$?v=$val";
        assertTrue("Should match dollar sign in path/query",
                Patterns.WEB_URL.matcher(url).matches());

        url = "android.com/path$?v=$val";
        assertTrue("Should match dollar sign in path/query",
                Patterns.WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testWebUrl_matchesEmptyPathWithQueryParams() throws Exception {
        String url = "http://android.com?q=v";
        assertTrue("Should match empty path with query param",
                Patterns.WEB_URL.matcher(url).matches());

        url = "android.com?q=v";
        assertTrue("Should match empty path with query param",
                Patterns.WEB_URL.matcher(url).matches());

        url = "http://android.com/?q=v";
        assertTrue("Should match empty path with query param",
                Patterns.WEB_URL.matcher(url).matches());

        url = "android.com/?q=v";
        assertTrue("Should match empty path with query param",
                Patterns.WEB_URL.matcher(url).matches());
    }

    // Tests for Patterns.AUTOLINK_WEB_URL

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesValidUrlWithSchemeAndHostname() throws Exception {
        String url = "http://www.android.com";
        assertTrue("Should match URL with scheme and hostname",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesValidUrlWithSchemeHostnameAndNewTld() throws Exception {
        String url = "http://www.android.me";
        assertTrue("Should match URL with scheme, hostname and new TLD",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesValidUrlWithHostnameAndNewTld() throws Exception {
        String url = "android.me";
        assertTrue("Should match URL with hostname and new TLD",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());

        url = "android.camera";
        assertTrue("Should match URL with hostname and new TLD",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesChinesePunycodeUrlWithProtocol() throws Exception {
        String url = "http://xn--fsqu00a.xn--0zwm56d";
        assertTrue("Should match Chinese Punycode URL with protocol",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesChinesePunycodeUrlWithoutProtocol() throws Exception {
        String url = "xn--fsqu00a.xn--0zwm56d";
        assertTrue("Should match Chinese Punycode URL without protocol",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesArabicPunycodeUrlWithProtocol() throws Exception {
        String url = "http://xn--4gbrim.xn--rmckbbajlc6dj7bxne2c.xn--wgbh1c/ar/default.aspx";
        assertTrue("Should match Arabic Punycode URL with protocol",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesArabicPunycodeUrlWithoutProtocol() throws Exception {
        String url = "xn--4gbrim.xn--rmckbbajlc6dj7bxne2c.xn--wgbh1c/ar/default.aspx";
        assertTrue("Should match Arabic Punycode URL without protocol",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_doesNotMatchPunycodeTldThatStartsWithDash() throws Exception {
        String url = "http://xn--fsqu00a.-xn--0zwm56d";
        assertFalse("Should not match Punycode TLD that starts with dash",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_doesNotMatchPunycodeTldThatEndsWithDash() throws Exception {
        String url = "http://xn--fsqu00a.xn--0zwm56d-";
        assertFalse("Should not match Punycode TLD that ends with dash",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesUrlWithUnicodeDomainName() throws Exception {
        String url = "http://\uD604\uAE08\uC601\uC218\uC99D.kr";
        assertTrue("Should match URL with Unicode domain name",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());

        url = "\uD604\uAE08\uC601\uC218\uC99D.kr";
        assertTrue("hould match URL without protocol and with Unicode domain name",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesUrlWithUnicodeTld() throws Exception {
        String url = "\uB3C4\uBA54\uC778.\uD55C\uAD6D";
        assertTrue("Should match URL with Unicode TLD",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesUrlWithUnicodePath() throws Exception {
        String url = "http://brainstormtech.blogs.fortune.cnn.com/2010/03/11/" +
                "top-five-moments-from-eric-schmidt\u2019s-talk-in-abu-dhabi/";
        assertTrue("Should match URL with Unicode path",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_doesNotMatchValidUrlWithInvalidProtocol() throws Exception {
        String url = "invalid://www.example.com";
        assertFalse("Should not match URL with invalid protocol",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesValidUrlWithPort() throws Exception {
        String url = "http://www.example.com:8080";
        assertTrue("Should match URL with port",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesUrlWithPortAndQuery() throws Exception {
        String url = "http://www.example.com:8080/?foo=bar";
        assertTrue("Should match URL with port and query",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesUrlWithTilde() throws Exception {
        String url = "http://www.example.com:8080/~user/?foo=bar";
        assertTrue("Should match URL with tilde",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesProtocolCaseInsensitive() throws Exception {
        String url = "hTtP://android.com";
        assertTrue("Protocol matching should be case insensitive",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesUrlStartingWithHttpAndDoesNotHaveTld() throws Exception {
        String url = "http://android/#notld///a/n/d/r/o/i/d&p1=1&p2=2";
        assertTrue("Should match URL without a TLD and starting with http ",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_doesNotMatchUrlsWithoutProtocolAndWithUnknownTld()
            throws Exception {
        String url = "thank.unknowntld";
        assertFalse("Should not match URL that does not start with a protocol and " +
                "does not contain a known TLD",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_doesNotPartiallyMatchUnknownProtocol() throws Exception {
        String url = "invalid://foo.bar/baz";
        assertFalse("Should not partially match URL with unknown protocol",
                Patterns.AUTOLINK_WEB_URL.matcher(url).find());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesValidUrlWithEmoji() throws Exception {
        String url = "Thank\u263A.com";
        assertTrue("Should match URL with emoji",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_doesNotMatchUrlsWithEmojiWithoutProtocolAndWithoutKnownTld()
            throws Exception {
        String url = "Thank\u263A.unknowntld";
        assertFalse("Should not match URLs containing emoji and with unknown TLD",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_doesNotMatchEmailAddress()
            throws Exception {
        String url = "android@android.com";
        assertFalse("Should not match email address",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesDomainNameWithSurrogatePairs() throws Exception {
        String url = "android\uD83C\uDF38.com";
        assertTrue("Should match domain name with Unicode surrogate pairs",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesTldWithSurrogatePairs() throws Exception {
        String url = "http://android.\uD83C\uDF38com";
        assertTrue("Should match TLD with Unicode surrogate pairs",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesPathWithSurrogatePairs() throws Exception {
        String url = "http://android.com/path-with-\uD83C\uDF38?v=\uD83C\uDF38";
        assertTrue("Should match path and query with Unicode surrogate pairs",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_doesNotMatchUrlWithExcludedSurrogate() throws Exception {
        String url = "http://android\uD83F\uDFFE.com";
        assertFalse("Should not match URL with excluded Unicode surrogate pair",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_doesNotMatchUnicodeSpaces() throws Exception {
        String part1 = "http://and";
        String part2 = "roid";
        String[] emptySpaces = new String[]{
                "\u00A0", // no-break space
                "\u2000", // en quad
                "\u2001", // em quad
                "\u2002", // en space
                "\u2003", // em space
                "\u2004", // three-per-em space
                "\u2005", // four-per-em space
                "\u2006", // six-per-em space
                "\u2007", // figure space
                "\u2008", // punctuation space
                "\u2009", // thin space
                "\u200A", // hair space
                "\u2028", // line separator
                "\u2029", // paragraph separator
                "\u202F", // narrow no-break space
                "\u3000" // ideographic space
        };

        for (String emptySpace : emptySpaces) {
            String url = part1 + emptySpace + part2;
            assertFalse("Should not match empty space - code:" + emptySpace.codePointAt(0),
                    Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
        }
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesDomainNameWithDash() throws Exception {
        String url = "http://a-nd.r-oid.com";
        assertTrue("Should match dash in domain name",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());

        url = "a-nd.r-oid.com";
        assertTrue("Should match dash in domain name",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesDomainNameWithUnderscore() throws Exception {
        String url = "http://a_nd.r_oid.com";
        assertTrue("Should match underscore in domain name",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());

        url = "a_nd.r_oid.com";
        assertTrue("Should match underscore in domain name",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesPathAndQueryWithDollarSign() throws Exception {
        String url = "http://android.com/path$?v=$val";
        assertTrue("Should match dollar sign in path/query",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());

        url = "android.com/path$?v=$val";
        assertTrue("Should match dollar sign in path/query",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkWebUrl_matchesEmptyPathWithQueryParams() throws Exception {
        String url = "http://android.com?q=v";
        assertTrue("Should match empty path with query param",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());

        url = "android.com?q=v";
        assertTrue("Should match empty path with query param",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());

        url = "http://android.com/?q=v";
        assertTrue("Should match empty path with query param",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());

        url = "android.com/?q=v";
        assertTrue("Should match empty path with query param",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    // Tests for Patterns.IP_ADDRESS

    @Test
    @SmallTest
    public void testIpPattern() throws Exception {
        boolean t;

        t = Patterns.IP_ADDRESS.matcher("172.29.86.3").matches();
        assertTrue("Valid IP", t);

        t = Patterns.IP_ADDRESS.matcher("1234.4321.9.9").matches();
        assertFalse("Invalid IP", t);
    }

    // Tests for Patterns.DOMAIN_NAME

    @Test
    @SmallTest
    public void testDomain_matchesPunycodeTld() throws Exception {
        String domain = "xn--fsqu00a.xn--0zwm56d";
        assertTrue("Should match domain name in Punycode",
                Patterns.DOMAIN_NAME.matcher(domain).matches());
    }

    @Test
    @SmallTest
    public void testDomain_doesNotMatchPunycodeThatStartsWithDash() throws Exception {
        String domain = "xn--fsqu00a.-xn--0zwm56d";
        assertFalse("Should not match Punycode TLD that starts with a dash",
                Patterns.DOMAIN_NAME.matcher(domain).matches());
    }

    @Test
    @SmallTest
    public void testDomain_doesNotMatchPunycodeThatEndsWithDash() throws Exception {
        String domain = "xn--fsqu00a.xn--0zwm56d-";
        assertFalse("Should not match Punycode TLD that ends with a dash",
                Patterns.DOMAIN_NAME.matcher(domain).matches());
    }

    @Test
    @SmallTest
    public void testDomain_doesNotMatchPunycodeLongerThanAllowed() throws Exception {
        String tld = "xn--";
        for(int i=0; i<=6; i++) {
            tld += "0123456789";
        }
        String domain = "xn--fsqu00a." + tld;
        assertFalse("Should not match Punycode TLD that is longer than 63 chars",
                Patterns.DOMAIN_NAME.matcher(domain).matches());
    }

    @Test
    @SmallTest
    public void testDomain_matchesObsoleteTld() throws Exception {
        String domain = "test.yu";
        assertTrue("Should match domain names with obsolete TLD",
                Patterns.DOMAIN_NAME.matcher(domain).matches());
    }

    @Test
    @SmallTest
    public void testDomain_matchesWithSubDomain() throws Exception {
        String domain = "mail.example.com";
        assertTrue("Should match domain names with subdomains",
                Patterns.DOMAIN_NAME.matcher(domain).matches());
    }

    @Test
    @SmallTest
    public void testDomain_matchesWithoutSubDomain() throws Exception {
        String domain = "android.me";
        assertTrue("Should match domain names without subdomains",
                Patterns.DOMAIN_NAME.matcher(domain).matches());
    }

    @Test
    @SmallTest
    public void testDomain_matchesUnicodeDomainNames() throws Exception {
        String domain = "\uD604\uAE08\uC601\uC218\uC99D.kr";
        assertTrue("Should match unicodedomain names",
                Patterns.DOMAIN_NAME.matcher(domain).matches());
    }

    @Test
    @SmallTest
    public void testDomain_doesNotMatchInvalidDomain() throws Exception {
        String domain = "__+&42.xer";
        assertFalse("Should not match invalid domain name",
                Patterns.DOMAIN_NAME.matcher(domain).matches());
    }

    @Test
    @SmallTest
    public void testDomain_matchesPunycodeArabicDomainName() throws Exception {
        String domain = "xn--4gbrim.xn----rmckbbajlc6dj7bxne2c.xn--wgbh1c";
        assertTrue("Should match Punycode Arabic domain name",
                Patterns.DOMAIN_NAME.matcher(domain).matches());
    }

    @Test
    @SmallTest
    public void testDomain_matchesDomainNameWithDash() throws Exception {
        String url = "http://a-nd.r-oid.com";
        assertTrue("Should match dash in domain name",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());

        url = "a-nd.r-oid.com";
        assertTrue("Should match dash in domain name",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    @Test
    @SmallTest
    public void testDomain_matchesDomainNameWithUnderscore() throws Exception {
        String url = "http://a_nd.r_oid.com";
        assertTrue("Should match underscore in domain name",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());

        url = "a_nd.r_oid.com";
        assertTrue("Should match underscore in domain name",
                Patterns.AUTOLINK_WEB_URL.matcher(url).matches());
    }

    // Tests for Patterns.AUTOLINK_EMAIL_ADDRESS

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesShortValidEmail() throws Exception {
        String email = "a@a.co";
        assertTrue("Should match short valid email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesRegularEmail() throws Exception {
        String email = "email@android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesEmailWithMultipleSubdomains() throws Exception {
        String email = "email@e.somelongdomainnameforandroid.abc.uk";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesLocalPartWithDot() throws Exception {
        String email = "e.mail@android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesLocalPartWithPlus() throws Exception {
        String email = "e+mail@android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesLocalPartWithUnderscore() throws Exception {
        String email = "e_mail@android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesLocalPartWithDash() throws Exception {
        String email = "e-mail@android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesLocalPartWithApostrophe() throws Exception {
        String email = "e'mail@android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesLocalPartWithDigits() throws Exception {
        String email = "123@android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesUnicodeLocalPart() throws Exception {
        String email = "\uD604\uAE08\uC601\uC218\uC99D@android.kr";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesLocalPartWithEmoji() throws Exception {
        String email = "smiley\u263A@android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesLocalPartWithSurrogatePairs() throws Exception {
        String email = "\uD83C\uDF38@android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesDomainWithDash() throws Exception {
        String email = "email@an-droid.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesUnicodeDomain() throws Exception {
        String email = "email@\uD604\uAE08\uC601\uC218\uC99D.kr";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesUnicodeLocalPartAndDomain() throws Exception {
        String email = "\uD604\uAE08\uC601\uC218\uC99D@\uD604\uAE08\uC601\uC218\uC99D.kr";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesDomainWithEmoji() throws Exception {
        String email = "smiley@\u263Aandroid.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesDomainWithSurrogatePairs() throws Exception {
        String email = "email@\uD83C\uDF38android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesLocalPartAndDomainWithSurrogatePairs()
            throws Exception {
        String email = "\uD83C\uDF38@\uD83C\uDF38android.com";
        assertTrue("Should match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_doesNotMatchStringWithoutAtSign() throws Exception {
        String email = "android.com";
        assertFalse("Should not match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_doesNotMatchPlainString() throws Exception {
        String email = "email";
        assertFalse("Should not match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_doesNotMatchStringWithMultipleAtSigns() throws Exception {
        String email = "email@android@android.com";
        assertFalse("Should not match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_doesNotMatchEmailWithoutTld() throws Exception {
        String email = "email@android";
        assertFalse("Should not match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_doesNotMatchLocalPartEndingWithDot() throws Exception {
        String email = "email.@android.com";
        assertFalse("Should not match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_doesNotMatchLocalPartStartingWithDot() throws Exception {
        String email = ".email@android.com";
        assertFalse("Should not match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_doesNotMatchDomainStartingWithDash() throws Exception {
        String email = "email@-android.com";
        assertFalse("Should not match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_doesNotMatchDomainWithConsecutiveDots() throws Exception {
        String email = "email@android..com";
        assertFalse("Should not match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_doesNotMatchEmailWithIpAsDomain() throws Exception {
        String email = "email@127.0.0.1";
        assertFalse("Should not match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_doesNotMatchEmailWithInvalidTld() throws Exception {
        String email = "email@android.c";
        assertFalse("Should not match email: " + email,
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesLocalPartUpTo64Chars() throws Exception {
        String localPart = "";
        for (int i = 0; i < 64; i++) {
            localPart += "a";
        }
        String email = localPart + "@android.com";

        assertTrue("Should match local part of length: " + localPart.length(),
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());

        email = localPart + "a@android.com";
        assertFalse("Should not match local part of length: " + localPart.length(),
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesSubdomainUpTo63Chars() throws Exception {
        String subdomain = "";
        for (int i = 0; i < 63; i++) {
            subdomain += "a";
        }
        String email = "email@" + subdomain + ".com";

        assertTrue("Should match subdomain of length: " + subdomain.length(),
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());

        subdomain += "a";
        email = "email@" + subdomain + ".com";
        assertFalse("Should not match local part of length: " + subdomain.length(),
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    @Test
    @SmallTest
    public void testAutoLinkEmailAddress_matchesDomainUpTo255Chars() throws Exception {
        String longDomain = "";
        while (longDomain.length() <= 250) {
            longDomain += "d.";
        }
        longDomain += "com";
        assertEquals(255, longDomain.length());
        String email = "a@" + longDomain;

        assertTrue("Should match domain of length: " + longDomain.length(),
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());

        email = email + "m";
        assertEquals(258, email.length());
        assertFalse("Should not match domain of length: " + longDomain.length(),
                Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(email).matches());
    }

    // Tests for Patterns.PHONE

    @Test
    @SmallTest
    public void testPhonePattern() throws Exception {
        boolean t;

        t = Patterns.PHONE.matcher("(919) 555-1212").matches();
        assertTrue("Valid phone", t);

        t = Patterns.PHONE.matcher("2334 9323/54321").matches();
        assertFalse("Invalid phone", t);

        String[] tests = {
                "Me: 16505551212 this\n",
                "Me: 6505551212 this\n",
                "Me: 5551212 this\n",
                "Me: 2211 this\n",
                "Me: 112 this\n",

                "Me: 1-650-555-1212 this\n",
                "Me: (650) 555-1212 this\n",
                "Me: +1 (650) 555-1212 this\n",
                "Me: +1-650-555-1212 this\n",
                "Me: 650-555-1212 this\n",
                "Me: 555-1212 this\n",

                "Me: 1.650.555.1212 this\n",
                "Me: (650) 555.1212 this\n",
                "Me: +1 (650) 555.1212 this\n",
                "Me: +1.650.555.1212 this\n",
                "Me: 650.555.1212 this\n",
                "Me: 555.1212 this\n",

                "Me: 1 650 555 1212 this\n",
                "Me: (650) 555 1212 this\n",
                "Me: +1 (650) 555 1212 this\n",
                "Me: +1 650 555 1212 this\n",
                "Me: 650 555 1212 this\n",
                "Me: 555 1212 this\n",
        };

        for (String test : tests) {
            Matcher m = Patterns.PHONE.matcher(test);

            assertTrue("Valid phone " + test, m.find());
        }
    }
}
