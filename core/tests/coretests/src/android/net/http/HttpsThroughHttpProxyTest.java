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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.TestSSLContext;
import junit.framework.TestCase;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import tests.http.MockResponse;
import tests.http.MockWebServer;
import tests.http.RecordedRequest;

public class HttpsThroughHttpProxyTest extends TestCase {

    public void testConnectViaHttps() throws IOException, InterruptedException {
        TestSSLContext testSSLContext = TestSSLContext.create();

        MockWebServer server = new MockWebServer();
        server.useHttps(testSSLContext.serverContext.getSocketFactory(), false);
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("this response comes via HTTPS"));
        server.play();

        HttpClient httpClient = new DefaultHttpClient();
        SSLSocketFactory sslSocketFactory = new SSLSocketFactory(
                testSSLContext.clientContext.getSocketFactory());
        sslSocketFactory.setHostnameVerifier(new AllowAllHostnameVerifier());
        httpClient.getConnectionManager().getSchemeRegistry()
                .register(new Scheme("https", sslSocketFactory, server.getPort()));

        HttpResponse response = httpClient.execute(
                new HttpGet("https://localhost:" + server.getPort() + "/foo"));
        assertEquals("this response comes via HTTPS", contentToString(response));

        RecordedRequest request = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    }

    /**
     * http://code.google.com/p/android/issues/detail?id=2690
     */
    public void testConnectViaProxy() throws IOException, InterruptedException {
        MockWebServer proxy = new MockWebServer();
        MockResponse mockResponse = new MockResponse()
                .setResponseCode(200)
                .setBody("this response comes via a proxy");
        proxy.enqueue(mockResponse);
        proxy.play();

        HttpClient httpProxyClient = new DefaultHttpClient();
        httpProxyClient.getParams().setParameter(
                ConnRoutePNames.DEFAULT_PROXY, new HttpHost("localhost", proxy.getPort()));

        HttpResponse response = httpProxyClient.execute(new HttpGet("http://android.com/foo"));
        assertEquals("this response comes via a proxy", contentToString(response));

        RecordedRequest request = proxy.takeRequest();
        assertEquals("GET http://android.com/foo HTTP/1.1", request.getRequestLine());
        assertContains(request.getHeaders(), "Host: android.com");
    }

    public void testConnectViaHttpProxyToHttps() throws IOException, InterruptedException {
        TestSSLContext testSSLContext = TestSSLContext.create();

        MockWebServer proxy = new MockWebServer();
        proxy.useHttps(testSSLContext.serverContext.getSocketFactory(), true);
        MockResponse connectResponse = new MockResponse()
                .setResponseCode(200);
        connectResponse.getHeaders().clear();
        proxy.enqueue(connectResponse);
        proxy.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("this response comes via a secure proxy"));
        proxy.play();

        HttpClient httpProxyClient = new DefaultHttpClient();
        HttpHost proxyHost = new HttpHost("localhost", proxy.getPort());
        httpProxyClient.getParams().setParameter(
                ConnRoutePNames.DEFAULT_PROXY, proxyHost);
        SSLSocketFactory sslSocketFactory = new SSLSocketFactory(
                testSSLContext.clientContext.getSocketFactory());
        sslSocketFactory.setHostnameVerifier(new AllowAllHostnameVerifier());
        httpProxyClient.getConnectionManager().getSchemeRegistry()
                .register(new Scheme("https", sslSocketFactory, 443));

        HttpResponse response = httpProxyClient.execute(new HttpGet("https://android.com/foo"));
        assertEquals("this response comes via a secure proxy", contentToString(response));

        RecordedRequest connect = proxy.takeRequest();
        assertEquals("Connect line failure on proxy " + proxyHost.toHostString(),
                "CONNECT android.com:443 HTTP/1.1", connect.getRequestLine());
        assertContains(connect.getHeaders(), "Host: android.com");

        RecordedRequest get = proxy.takeRequest();
        assertEquals("GET /foo HTTP/1.1", get.getRequestLine());
        assertContains(get.getHeaders(), "Host: android.com");
    }

    private void assertContains(List<String> headers, String header) {
        assertTrue(headers.toString(), headers.contains(header));
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
}
