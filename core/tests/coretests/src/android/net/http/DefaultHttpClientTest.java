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
import com.google.mockwebserver.SocketPolicy;
import static com.google.mockwebserver.SocketPolicy.DISCONNECT_AT_END;
import static com.google.mockwebserver.SocketPolicy.SHUTDOWN_INPUT_AT_END;
import static com.google.mockwebserver.SocketPolicy.SHUTDOWN_OUTPUT_AT_END;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import junit.framework.TestCase;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.auth.DigestScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;

/**
 * Tests for various regressions and problems with DefaultHttpClient. This is
 * not a comprehensive test!
 */
public final class DefaultHttpClientTest extends TestCase {

    private MockWebServer server = new MockWebServer();

    @Override protected void tearDown() throws Exception {
        server.shutdown();
        super.tearDown();
    }

    public void testServerClosesSocket() throws Exception {
        testServerClosesOutput(DISCONNECT_AT_END);
    }

    public void testServerShutdownInput() throws Exception {
        testServerClosesOutput(SHUTDOWN_INPUT_AT_END);
    }

    /**
     * DefaultHttpClient fails if the server shutdown the output after the
     * response was sent. http://b/2612240
     */
    public void testServerShutdownOutput() throws Exception {
        testServerClosesOutput(SHUTDOWN_OUTPUT_AT_END);
    }

    private void testServerClosesOutput(SocketPolicy socketPolicy) throws Exception {
        server.enqueue(new MockResponse()
                .setBody("This connection won't pool properly")
                .setSocketPolicy(socketPolicy));
        server.enqueue(new MockResponse()
                .setBody("This comes after a busted connection"));
        server.play();

        DefaultHttpClient client = new DefaultHttpClient();

        HttpResponse a = client.execute(new HttpGet(server.getUrl("/a").toURI()));
        assertEquals("This connection won't pool properly", contentToString(a));
        assertEquals(0, server.takeRequest().getSequenceNumber());

        HttpResponse b = client.execute(new HttpGet(server.getUrl("/b").toURI()));
        assertEquals("This comes after a busted connection", contentToString(b));
        // sequence number 0 means the HTTP socket connection was not reused
        assertEquals(0, server.takeRequest().getSequenceNumber());
    }

    private String contentToString(HttpResponse response) throws IOException {
        StringWriter writer = new StringWriter();
        char[] buffer = new char[1024];
        Reader reader = new InputStreamReader(response.getEntity().getContent());
        int length;
        while ((length = reader.read(buffer)) != -1) {
            writer.write(buffer, 0, length);
        }
        reader.close();
        return writer.toString();
    }

    // http://code.google.com/p/android/issues/detail?id=16051
    public void testDigestSchemeAlgorithms() throws Exception {
        authenticateDigestAlgorithm("MD5");
        authenticateDigestAlgorithm("MD5-sess");
        authenticateDigestAlgorithm("md5");
        authenticateDigestAlgorithm("md5-sess");
        authenticateDigestAlgorithm("md5-SESS");
        authenticateDigestAlgorithm("MD5-SESS");
        try {
            authenticateDigestAlgorithm("MD5-");
        } catch (AuthenticationException expected) {
        }
        try {
            authenticateDigestAlgorithm("MD6");
        } catch (AuthenticationException expected) {
        }
        try {
            authenticateDigestAlgorithm("MD");
        } catch (AuthenticationException expected) {
        }
        try {
            authenticateDigestAlgorithm("");
        } catch (AuthenticationException expected) {
        }
    }

    private void authenticateDigestAlgorithm(String algorithm) throws Exception {
        String challenge = "Digest realm=\"protected area\", "
                + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", "
                + "algorithm=" + algorithm;
        DigestScheme digestScheme = new DigestScheme();
        digestScheme.processChallenge(new BasicHeader("WWW-Authenticate", challenge));
        HttpGet get = new HttpGet();
        digestScheme.authenticate(new UsernamePasswordCredentials("username", "password"), get);
    }
}
