/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.unit_tests;

import android.test.suitebuilder.annotation.SmallTest;
import android.text.util.Regex;
import junit.framework.TestCase;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegexTest extends TestCase {

    @SmallTest
    public void testTldPattern() throws Exception {
        boolean t;

        t = Regex.TOP_LEVEL_DOMAIN_PATTERN.matcher("com").matches();
        assertTrue("Missed valid TLD", t);

        t = Regex.TOP_LEVEL_DOMAIN_PATTERN.matcher("xer").matches();
        assertFalse("Matched invalid TLD!", t);
    }

    @SmallTest
    public void testUrlPattern() throws Exception {
        boolean t;

        t = Regex.WEB_URL_PATTERN.matcher("http://www.google.com").matches();
        assertTrue("Valid URL", t);

        t = Regex.WEB_URL_PATTERN.matcher("ftp://www.example.com").matches();
        assertFalse("Matched invalid protocol", t);

        t = Regex.WEB_URL_PATTERN.matcher("http://www.example.com:8080").matches();
        assertTrue("Didn't match valid URL with port", t);

        t = Regex.WEB_URL_PATTERN.matcher("http://www.example.com:8080/?foo=bar").matches();
        assertTrue("Didn't match valid URL with port and query args", t);

        t = Regex.WEB_URL_PATTERN.matcher("http://www.example.com:8080/~user/?foo=bar").matches();
        assertTrue("Didn't match valid URL with ~", t);
    }

    @SmallTest
    public void testIpPattern() throws Exception {
        boolean t;

        t = Regex.IP_ADDRESS_PATTERN.matcher("172.29.86.3").matches();
        assertTrue("Valid IP", t);

        t = Regex.IP_ADDRESS_PATTERN.matcher("1234.4321.9.9").matches();
        assertFalse("Invalid IP", t);
    }

    @SmallTest
    public void testDomainPattern() throws Exception {
        boolean t;

        t = Regex.DOMAIN_NAME_PATTERN.matcher("mail.example.com").matches();
        assertTrue("Valid domain", t);

        t = Regex.DOMAIN_NAME_PATTERN.matcher("__+&42.xer").matches();
        assertFalse("Invalid domain", t);
    }

    @SmallTest
    public void testPhonePattern() throws Exception {
        boolean t;

        t = Regex.PHONE_PATTERN.matcher("(919) 555-1212").matches();
        assertTrue("Valid phone", t);

        t = Regex.PHONE_PATTERN.matcher("2334 9323/54321").matches();
        assertFalse("Invalid phone", t);

        String[] tests = {
                "Me: 16505551212 this\n",
                "Me: 6505551212 this\n",
                "Me: 5551212 this\n",

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
            Matcher m = Regex.PHONE_PATTERN.matcher(test);

            assertTrue("Valid phone " + test, m.find());
        }
    }
}
