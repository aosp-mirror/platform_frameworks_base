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

import android.test.suitebuilder.annotation.SmallTest;
import android.util.Patterns;

import java.util.regex.Matcher;

import junit.framework.TestCase;

public class PatternsTest extends TestCase {

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

        // One of the new top level internationalized domain.
        t = Patterns.TOP_LEVEL_DOMAIN.matcher("\uD55C\uAD6D").matches();
        assertTrue("Missed valid TLD", t);

        t = Patterns.TOP_LEVEL_DOMAIN.matcher("mem").matches();
        assertFalse("Matched invalid TLD!", t);

        t = Patterns.TOP_LEVEL_DOMAIN.matcher("xn").matches();
        assertFalse("Matched invalid TLD!", t);

        t = Patterns.TOP_LEVEL_DOMAIN.matcher("xer").matches();
        assertFalse("Matched invalid TLD!", t);
    }

    @SmallTest
    public void testUrlPattern() throws Exception {
        boolean t;

        t = Patterns.WEB_URL.matcher("http://www.google.com").matches();
        assertTrue("Valid URL", t);

        // Google in one of the new top level domain.
        t = Patterns.WEB_URL.matcher("http://www.google.me").matches();
        assertTrue("Valid URL", t);
        t = Patterns.WEB_URL.matcher("google.me").matches();
        assertTrue("Valid URL", t);

        // Test url in Chinese: http://xn--fsqu00a.xn--0zwm56d
        t = Patterns.WEB_URL.matcher("http://xn--fsqu00a.xn--0zwm56d").matches();
        assertTrue("Valid URL", t);
        t = Patterns.WEB_URL.matcher("xn--fsqu00a.xn--0zwm56d").matches();
        assertTrue("Valid URL", t);

        // Url for testing top level Arabic country code domain in Punycode:
        //   http://xn--4gbrim.xn----rmckbbajlc6dj7bxne2c.xn--wgbh1c/ar/default.aspx
        t = Patterns.WEB_URL.matcher("http://xn--4gbrim.xn----rmckbbajlc6dj7bxne2c.xn--wgbh1c/ar/default.aspx").matches();
        assertTrue("Valid URL", t);
        t = Patterns.WEB_URL.matcher("xn--4gbrim.xn----rmckbbajlc6dj7bxne2c.xn--wgbh1c/ar/default.aspx").matches();
        assertTrue("Valid URL", t);

        // Internationalized URL.
        t = Patterns.WEB_URL.matcher("http://\uD604\uAE08\uC601\uC218\uC99D.kr").matches();
        assertTrue("Valid URL", t);
        t = Patterns.WEB_URL.matcher("\uD604\uAE08\uC601\uC218\uC99D.kr").matches();
        assertTrue("Valid URL", t);
        // URL with international TLD.
        t = Patterns.WEB_URL.matcher("\uB3C4\uBA54\uC778.\uD55C\uAD6D").matches();
        assertTrue("Valid URL", t);

        t = Patterns.WEB_URL.matcher("http://brainstormtech.blogs.fortune.cnn.com/2010/03/11/" +
            "top-five-moments-from-eric-schmidt\u2019s-talk-in-abu-dhabi/").matches();
        assertTrue("Valid URL", t);

        t = Patterns.WEB_URL.matcher("ftp://www.example.com").matches();
        assertFalse("Matched invalid protocol", t);

        t = Patterns.WEB_URL.matcher("http://www.example.com:8080").matches();
        assertTrue("Didn't match valid URL with port", t);

        t = Patterns.WEB_URL.matcher("http://www.example.com:8080/?foo=bar").matches();
        assertTrue("Didn't match valid URL with port and query args", t);

        t = Patterns.WEB_URL.matcher("http://www.example.com:8080/~user/?foo=bar").matches();
        assertTrue("Didn't match valid URL with ~", t);
    }

    @SmallTest
    public void testIpPattern() throws Exception {
        boolean t;

        t = Patterns.IP_ADDRESS.matcher("172.29.86.3").matches();
        assertTrue("Valid IP", t);

        t = Patterns.IP_ADDRESS.matcher("1234.4321.9.9").matches();
        assertFalse("Invalid IP", t);
    }

    @SmallTest
    public void testDomainPattern() throws Exception {
        boolean t;

        t = Patterns.DOMAIN_NAME.matcher("mail.example.com").matches();
        assertTrue("Valid domain", t);

        t = Patterns.DOMAIN_NAME.matcher("google.me").matches();
        assertTrue("Valid domain", t);

        // Internationalized domains.
        t = Patterns.DOMAIN_NAME.matcher("\uD604\uAE08\uC601\uC218\uC99D.kr").matches();
        assertTrue("Valid domain", t);

        t = Patterns.DOMAIN_NAME.matcher("__+&42.xer").matches();
        assertFalse("Invalid domain", t);

        // Obsolete domain .yu
        t = Patterns.DOMAIN_NAME.matcher("test.yu").matches();
        assertFalse("Obsolete country code top level domain", t);

        // Testing top level Arabic country code domain in Punycode:
        t = Patterns.DOMAIN_NAME.matcher("xn--4gbrim.xn----rmckbbajlc6dj7bxne2c.xn--wgbh1c").matches();
        assertTrue("Valid domain", t);
    }

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
