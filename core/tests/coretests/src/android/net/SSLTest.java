/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.test.suitebuilder.annotation.Suppress;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import junit.framework.TestCase;

public class SSLTest extends TestCase {
    //This test relies on network resources.
    @Suppress
    public void testCertificate() throws Exception {
        // test www.fortify.net/sslcheck.html
        Socket ssl = SSLCertificateSocketFactory.getDefault().createSocket("www.fortify.net",443);
        assertNotNull(ssl);

        OutputStream out = ssl.getOutputStream();
        assertNotNull(out);

        InputStream in = ssl.getInputStream();
        assertNotNull(in);

        String get = "GET /sslcheck.html HTTP/1.1\r\nHost: 68.178.217.222\r\n\r\n";

        // System.out.println("going for write...");
        out.write(get.getBytes());

        byte[] b = new byte[1024];
        // System.out.println("going for read...");
        int ret = in.read(b);

        // System.out.println(new String(b));
    }

    public void testStringsToLengthPrefixedBytes() {
        byte[] expected = {
                6, 's', 'p', 'd', 'y', '/', '2',
                8, 'h', 't', 't', 'p', '/', '1', '.', '1',
        };
        assertTrue(Arrays.equals(expected, SSLCertificateSocketFactory.toLengthPrefixedList(
                new byte[] { 's', 'p', 'd', 'y', '/', '2' },
                new byte[] { 'h', 't', 't', 'p', '/', '1', '.', '1' })));
    }

    public void testStringsToLengthPrefixedBytesEmptyArray() {
        try {
            SSLCertificateSocketFactory.toLengthPrefixedList();
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testStringsToLengthPrefixedBytesEmptyByteArray() {
        try {
            SSLCertificateSocketFactory.toLengthPrefixedList(new byte[0]);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testStringsToLengthPrefixedBytesOversizedInput() {
        try {
            SSLCertificateSocketFactory.toLengthPrefixedList(new byte[256]);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }
}
