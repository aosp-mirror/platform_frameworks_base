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

import android.net.SSLCertificateSocketFactory;
import android.test.suitebuilder.annotation.Suppress;
import junit.framework.TestCase;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

//This test relies on network resources.
@Suppress
public class SSLTest extends TestCase {
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
}
