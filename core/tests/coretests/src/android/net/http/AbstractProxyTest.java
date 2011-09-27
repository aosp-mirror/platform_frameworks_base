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
import com.google.mockwebserver.SocketPolicy;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.util.List;
import junit.framework.TestCase;
import libcore.javax.net.ssl.TestSSLContext;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.params.ConnRouteParams;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;

public abstract class AbstractProxyTest extends TestCase {

    private MockWebServer server = new MockWebServer();

    protected abstract HttpClient newHttpClient();

    @Override protected void tearDown() throws Exception {
        System.clearProperty("proxyHost");
        System.clearProperty("proxyPort");
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");

        server.shutdown();
        super.tearDown();
    }

    public void testConnectToHttps() throws Exception {
        TestSSLContext testSSLContext = TestSSLContext.create();

        server.useHttps(testSSLContext.serverContext.getSocketFactory(), false);
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("this response comes via HTTPS"));
        server.play();

        HttpClient httpClient = newHttpClient();

        SSLSocketFactory sslSocketFactory = newSslSocketFactory(testSSLContext);
        sslSocketFactory.setHostnameVerifier(new AllowAllHostnameVerifier());
        httpClient.getConnectionManager().getSchemeRegistry()
                .register(new Scheme("https", sslSocketFactory, server.getPort()));

        HttpResponse response = httpClient.execute(
                new HttpGet("https://localhost:" + server.getPort() + "/foo"));
        assertEquals("this response comes via HTTPS", contentToString(response));

        RecordedRequest request = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    }

    private SSLSocketFactory newSslSocketFactory(TestSSLContext testSSLContext) throws Exception {
        // call through to Apache HTTP's non-public SSLSocketFactory constructor
        return SSLSocketFactory.class.getConstructor(javax.net.ssl.SSLSocketFactory.class)
                .newInstance(testSSLContext.clientContext.getSocketFactory());
    }

    /**
     * We had bugs where proxy system properties weren't being honored.
     * http://b/3254717
     */
    public void testConnectViaProxyUsingProxySystemProperty() throws Exception {
        testConnectViaProxy(ProxyConfig.PROXY_SYSTEM_PROPERTY);
    }

    public void testConnectViaProxyUsingHttpProxySystemProperty() throws Exception {
        testConnectViaProxy(ProxyConfig.HTTP_PROXY_SYSTEM_PROPERTY);
    }

    public void testConnectViaProxyUsingRequestParameter() throws Exception {
        testConnectViaProxy(ProxyConfig.REQUEST_PARAMETER);
    }

    public void testConnectViaProxyUsingClientParameter() throws Exception {
        testConnectViaProxy(ProxyConfig.CLIENT_PARAMETER);
    }

    /**
     * http://code.google.com/p/android/issues/detail?id=2690
     */
    private void testConnectViaProxy(ProxyConfig proxyConfig) throws Exception {
        MockResponse mockResponse = new MockResponse()
                .setResponseCode(200)
                .setBody("this response comes via a proxy");
        server.enqueue(mockResponse);
        server.play();

        HttpClient httpProxyClient = newHttpClient();

        HttpGet request = new HttpGet("http://android.com/foo");
        proxyConfig.configure(server, httpProxyClient, request);

        HttpResponse response = httpProxyClient.execute(request);
        assertEquals("this response comes via a proxy", contentToString(response));

        RecordedRequest get = server.takeRequest();
        assertEquals("GET http://android.com/foo HTTP/1.1", get.getRequestLine());
        assertContains(get.getHeaders(), "Host: android.com");
    }

    public void testConnectViaHttpProxyToHttpsUsingProxySystemProperty() throws Exception {
        testConnectViaHttpProxyToHttps(ProxyConfig.PROXY_SYSTEM_PROPERTY);
    }

    public void testConnectViaHttpProxyToHttpsUsingHttpsProxySystemProperty() throws Exception {
        testConnectViaHttpProxyToHttps(ProxyConfig.HTTPS_PROXY_SYSTEM_PROPERTY);
    }

    public void testConnectViaHttpProxyToHttpsUsingClientParameter() throws Exception {
        testConnectViaHttpProxyToHttps(ProxyConfig.CLIENT_PARAMETER);
    }

    public void testConnectViaHttpProxyToHttpsUsingRequestParameter() throws Exception {
        testConnectViaHttpProxyToHttps(ProxyConfig.REQUEST_PARAMETER);
    }

    private void testConnectViaHttpProxyToHttps(ProxyConfig proxyConfig) throws Exception {
        TestSSLContext testSSLContext = TestSSLContext.create();

        server.useHttps(testSSLContext.serverContext.getSocketFactory(), true);
        server.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
                .clearHeaders());
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("this response comes via a secure proxy"));
        server.play();

        HttpClient httpProxyClient = newHttpClient();
        SSLSocketFactory sslSocketFactory = newSslSocketFactory(testSSLContext);
        sslSocketFactory.setHostnameVerifier(new AllowAllHostnameVerifier());
        httpProxyClient.getConnectionManager().getSchemeRegistry()
                .register(new Scheme("https", sslSocketFactory, 443));

        HttpGet request = new HttpGet("https://android.com/foo");
        proxyConfig.configure(server, httpProxyClient, request);

        HttpResponse response = httpProxyClient.execute(request);
        assertEquals("this response comes via a secure proxy", contentToString(response));

        RecordedRequest connect = server.takeRequest();
        assertEquals("Connect line failure on proxy " + proxyConfig,
                "CONNECT android.com:443 HTTP/1.1", connect.getRequestLine());
        assertContains(connect.getHeaders(), "Host: android.com");

        RecordedRequest get = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", get.getRequestLine());
        assertContains(get.getHeaders(), "Host: android.com");
    }

    public void testClientParamPreferredOverSystemProperty() throws Exception {
        testParamPreferredOverSystemProperty(ProxyConfig.CLIENT_PARAMETER);
    }

    public void testRequestParamPreferredOverSystemProperty() throws Exception {
        testParamPreferredOverSystemProperty(ProxyConfig.REQUEST_PARAMETER);
    }

    private void testParamPreferredOverSystemProperty(ProxyConfig proxyConfig) throws Exception {
        server.enqueue(new MockResponse().setBody("Via request parameter proxy!"));
        server.play();
        System.setProperty("http.proxyHost", "proxy.foo");
        System.setProperty("http.proxyPort", "8080");

        HttpClient client = newHttpClient();
        HttpGet request = new HttpGet("http://origin.foo/bar");
        proxyConfig.configure(server, client, request);
        HttpResponse response = client.execute(request);
        assertEquals("Via request parameter proxy!", contentToString(response));

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("GET http://origin.foo/bar HTTP/1.1", recordedRequest.getRequestLine());
    }

    public void testExplicitNoProxyCancelsSystemProperty() throws Exception {
        server.enqueue(new MockResponse().setBody("Via the origin server!"));
        server.play();
        System.setProperty("http.proxyHost", "proxy.foo");
        System.setProperty("http.proxyPort", "8080");

        HttpClient client = newHttpClient();
        HttpGet request = new HttpGet(server.getUrl("/bar").toURI());
        request.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, ConnRouteParams.NO_HOST);
        HttpResponse response = client.execute(request);
        assertEquals("Via the origin server!", contentToString(response));

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("GET /bar HTTP/1.1", recordedRequest.getRequestLine());
    }

    // http://b/5372438
    public void testRetryWithProxy() throws Exception {
        server.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.play();

        HttpClient httpProxyClient = newHttpClient();
        HttpGet request = new HttpGet("http://android.com/foo");
        ProxyConfig.REQUEST_PARAMETER.configure(server, httpProxyClient, request);

        try {
            httpProxyClient.execute(request);
            fail();
        } catch (IOException expected) {
        }
    }

    enum ProxyConfig {
        PROXY_SYSTEM_PROPERTY() {
            @Override void configure(MockWebServer server, HttpClient client, HttpRequest request) {
                System.setProperty("proxyHost", "localhost");
                System.setProperty("proxyPort", Integer.toString(server.getPort()));
            }
        },
        HTTP_PROXY_SYSTEM_PROPERTY() {
            @Override void configure(MockWebServer server, HttpClient client, HttpRequest request) {
                System.setProperty("http.proxyHost", "localhost");
                System.setProperty("http.proxyPort", Integer.toString(server.getPort()));
            }
        },
        HTTPS_PROXY_SYSTEM_PROPERTY() {
            @Override void configure(MockWebServer server, HttpClient client, HttpRequest request) {
                System.setProperty("https.proxyHost", "localhost");
                System.setProperty("https.proxyPort", Integer.toString(server.getPort()));
            }
        },
        CLIENT_PARAMETER() {
            @Override void configure(MockWebServer server, HttpClient client, HttpRequest request) {
                client.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
                        new HttpHost("localhost", server.getPort()));
            }
        },
        REQUEST_PARAMETER() {
            @Override void configure(MockWebServer server, HttpClient client, HttpRequest request) {
                request.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
                        new HttpHost("localhost", server.getPort()));
            }
        };

        abstract void configure(MockWebServer proxy, HttpClient client, HttpRequest request);
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
