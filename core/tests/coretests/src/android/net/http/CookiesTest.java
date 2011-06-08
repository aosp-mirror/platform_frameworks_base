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

package android.net.http;

import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;
import com.google.mockwebserver.RecordedRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import junit.framework.TestCase;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;

public final class CookiesTest extends TestCase {

    private MockWebServer server = new MockWebServer();

    @Override protected void tearDown() throws Exception {
        server.shutdown();
        super.tearDown();
    }

    /**
     * Test that we don't log potentially sensitive cookie values.
     * http://b/3095990
     */
    public void testCookiesAreNotLogged() throws IOException, URISyntaxException {
        // enqueue an HTTP response with a cookie that will be rejected
        server.enqueue(new MockResponse()
                .addHeader("Set-Cookie: password=secret; Domain=fake.domain"));
        server.play();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Logger logger = Logger.getLogger("org.apache.http");
        StreamHandler handler = new StreamHandler(out, new SimpleFormatter());
        logger.addHandler(handler);
        try {
            HttpClient client = new DefaultHttpClient();
            client.execute(new HttpGet(server.getUrl("/").toURI()));
            handler.close();

            String log = out.toString("UTF-8");
            assertTrue(log, log.contains("password"));
            assertTrue(log, log.contains("fake.domain"));
            assertFalse(log, log.contains("secret"));

        } finally {
            logger.removeHandler(handler);
        }
    }

    /**
     * Test that cookies aren't case-sensitive with respect to hostname.
     * http://b/3167208
     */
    public void testCookiesWithNonMatchingCase() throws Exception {
        // use a proxy so we can manipulate the origin server's host name
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .addHeader("Set-Cookie: a=first; Domain=my.t-mobile.com")
                .addHeader("Set-Cookie: b=second; Domain=.T-mobile.com")
                .addHeader("Set-Cookie: c=third; Domain=.t-mobile.com")
                .setBody("This response sets some cookies."));
        server.enqueue(new MockResponse()
                .setBody("This response gets those cookies back."));
        server.play();

        HttpClient client = new DefaultHttpClient();
        client.getParams().setParameter(
                ConnRoutePNames.DEFAULT_PROXY, new HttpHost("localhost", server.getPort()));

        HttpResponse getCookies = client.execute(new HttpGet("http://my.t-mobile.com/"));
        getCookies.getEntity().consumeContent();
        server.takeRequest();

        HttpResponse sendCookies = client.execute(new HttpGet("http://my.t-mobile.com/"));
        sendCookies.getEntity().consumeContent();
        RecordedRequest sendCookiesRequest = server.takeRequest();
        assertContains(sendCookiesRequest.getHeaders(), "Cookie: a=first; b=second; c=third");
    }

    private void assertContains(List<String> headers, String header) {
        assertTrue(headers.toString(), headers.contains(header));
    }
}
