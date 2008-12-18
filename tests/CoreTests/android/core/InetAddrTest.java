/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.core;

import junit.framework.TestCase;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Random;

import android.test.suitebuilder.annotation.Suppress;

/**
 * Tests InetAddr class by checking methods to resolve domains to IP addresses
 * and by checking if the class returns correct addresses for local host address
 * and host name.
 */
@Suppress
public class InetAddrTest extends TestCase {
    private static final String[] HOSTS = {
            "localhost", "www.google.com", "www.slashdot.org", "www.wikipedia.org",
            "www.paypal.com", "www.cnn.com", "www.yahoo.com", "www.amazon.com",
            "www.ebay.com", "www.android.com"
    };

    public void testInetAddr() throws Exception {
        byte[] raw;

        InetAddress ia = InetAddress.getByName("localhost");

        raw = ia.getAddress();

        assertEquals(127, raw[0]);
        assertEquals(0, raw[1]);
        assertEquals(0, raw[2]);
        assertEquals(1, raw[3]);

        ia = InetAddress.getByName("127.0.0.1");

        raw = ia.getAddress();

        assertEquals(127, raw[0]);
        assertEquals(0, raw[1]);
        assertEquals(0, raw[2]);
        assertEquals(1, raw[3]);

        ia = InetAddress.getByName(null);

        try {
            InetAddress.getByName(".0.0.1");
            fail("expected ex");
        } catch (UnknownHostException ex) {
            // expected
        }

        try {
            InetAddress.getByName("thereisagoodchancethisdomaindoesnotexist.weirdtld");
            fail("expected ex");
        } catch (UnknownHostException ex) {
            // expected
        }

        try {
            InetAddress.getByName("127.0.0.");
            fail("expected ex");
        } catch (UnknownHostException ex) {
            // expected
        }

        Random random = new Random();
        int count = 0;
        for (int i = 0; i < 100; i++) {
            int index = random.nextInt(HOSTS.length);
            try {
                InetAddress.getByName(HOSTS[index]);
                count++;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                }
            } catch (UnknownHostException ex) {
            }
        }
        assertEquals("Not all host lookups succeeded", 100, count);
    }
}
