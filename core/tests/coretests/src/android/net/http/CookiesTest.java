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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import junit.framework.TestCase;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import tests.http.MockResponse;
import tests.http.MockWebServer;

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
}
