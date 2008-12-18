/*
 * $HeadURL: http://svn.apache.org/repos/asf/jakarta/httpcomponents/httpcore/tags/4.0-alpha6/module-main/src/test/java/org/apache/http/protocol/TestHttpServiceAndExecutor.java $
 * $Revision: 576073 $
 * $Date: 2007-09-16 03:53:13 -0700 (Sun, 16 Sep 2007) $
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package com.android.unit_tests;

import org.apache.http.protocol.HttpExpectationVerifier;
import org.apache.http.protocol.HttpRequestHandler;
import android.test.PerformanceTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;

import junit.framework.TestCase;

import org.apache.http.Header;
import org.apache.http.HttpConnectionMetrics;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultHttpClientConnection;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EncodingUtils;
import org.apache.http.util.EntityUtils;


import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TestHttpService extends TestCase implements PerformanceTestCase {

    public boolean isPerformanceOnly() {
        // TODO Auto-generated method stub
        return false;
    }

    public int startPerformance(Intermediates intermediates) {
        // TODO Auto-generated method stub
        return 0;
    }
    
    private TestHttpServer server;
    private TestHttpClient client;
    
    protected void setUp() throws Exception {
        this.server = new TestHttpServer();
        this.client = new TestHttpClient();
    }

    protected void tearDown() throws Exception {
        if (server != null) {
          this.server.shutdown();
        }
    }    
   
    /**
     * This test case executes a series of simple GET requests 
     */
    @LargeTest
    public void testSimpleBasicHttpRequests() throws Exception {
        
        int reqNo = 20;
        
        Random rnd = new Random();
        
        // Prepare some random data
        final List testData = new ArrayList(reqNo);
        for (int i = 0; i < reqNo; i++) {
            int size = rnd.nextInt(5000);
            byte[] data = new byte[size];
            rnd.nextBytes(data);
            testData.add(data);
        }

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            public void handle(
                    final HttpRequest request, 
                    final HttpResponse response, 
                    final HttpContext context) throws HttpException, IOException {
                
                String s = request.getRequestLine().getUri();
                if (s.startsWith("/?")) {
                    s = s.substring(2);
                }
                int index = Integer.parseInt(s);
                byte[] data = (byte []) testData.get(index);
                ByteArrayEntity entity = new ByteArrayEntity(data); 
                response.setEntity(entity);
            }
            
        });
        
        this.server.start();
        
        DefaultHttpClientConnection conn = new DefaultHttpClientConnection();
        HttpHost host = new HttpHost("localhost", this.server.getPort());
        
        try {
            for (int r = 0; r < reqNo; r++) {
                if (!conn.isOpen()) {
                    Socket socket = new Socket(host.getHostName(), host.getPort());
                    conn.bind(socket, this.client.getParams());
                }
                
                BasicHttpRequest get = new BasicHttpRequest("GET", "/?" + r);
                HttpResponse response = this.client.execute(get, host, conn);
                byte[] received = EntityUtils.toByteArray(response.getEntity());
                byte[] expected = (byte[]) testData.get(r);
                
                assertEquals(expected.length, received.length);
                for (int i = 0; i < expected.length; i++) {
                    assertEquals(expected[i], received[i]);
                }
                if (!this.client.keepAlive(response)) {
                    conn.close();
                }
            }
            
            //Verify the connection metrics
            HttpConnectionMetrics cm = conn.getMetrics();
            assertEquals(reqNo, cm.getRequestCount());
            assertEquals(reqNo, cm.getResponseCount());
            
        } finally {
            conn.close();
            this.server.shutdown();
        }
    }

    /**
     * This test case executes a series of simple POST requests with content length 
     * delimited content. 
     */
    @LargeTest
    public void testSimpleHttpPostsWithContentLength() throws Exception {
        
        int reqNo = 20;
        
        Random rnd = new Random();
        
        // Prepare some random data
        List testData = new ArrayList(reqNo);
        for (int i = 0; i < reqNo; i++) {
            int size = rnd.nextInt(5000);
            byte[] data = new byte[size];
            rnd.nextBytes(data);
            testData.add(data);
        }

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            public void handle(
                    final HttpRequest request, 
                    final HttpResponse response, 
                    final HttpContext context) throws HttpException, IOException {
                
                if (request instanceof HttpEntityEnclosingRequest) {
                    HttpEntity incoming = ((HttpEntityEnclosingRequest) request).getEntity();
                    byte[] data = EntityUtils.toByteArray(incoming);
                    
                    ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    outgoing.setChunked(false);
                    response.setEntity(outgoing);
                } else {
                    StringEntity outgoing = new StringEntity("No content"); 
                    response.setEntity(outgoing);
                }
            }
            
        });
        
        this.server.start();
        
        DefaultHttpClientConnection conn = new DefaultHttpClientConnection();
        HttpHost host = new HttpHost("localhost", this.server.getPort());
        
        try {
            for (int r = 0; r < reqNo; r++) {
                if (!conn.isOpen()) {
                    Socket socket = new Socket(host.getHostName(), host.getPort());
                    conn.bind(socket, this.client.getParams());
                }
                
                BasicHttpEntityEnclosingRequest post = new BasicHttpEntityEnclosingRequest("POST", "/");
                byte[] data = (byte[]) testData.get(r);
                ByteArrayEntity outgoing = new ByteArrayEntity(data);
                post.setEntity(outgoing);

                HttpResponse response = this.client.execute(post, host, conn);
                byte[] received = EntityUtils.toByteArray(response.getEntity());
                byte[] expected = (byte[]) testData.get(r);
                
                assertEquals(expected.length, received.length);
                for (int i = 0; i < expected.length; i++) {
                    assertEquals(expected[i], received[i]);
                }
                if (!this.client.keepAlive(response)) {
                    conn.close();
                }
            }
            //Verify the connection metrics
            HttpConnectionMetrics cm = conn.getMetrics();
            assertEquals(reqNo, cm.getRequestCount());
            assertEquals(reqNo, cm.getResponseCount());
            
        } finally {
            conn.close();
            this.server.shutdown();
        }
    }

    /**
     * This test case executes a series of simple POST requests with chunk 
     * coded content content. 
     */
    @LargeTest
    public void testSimpleHttpPostsChunked() throws Exception {
        
        int reqNo = 20;
        
        Random rnd = new Random();
        
        // Prepare some random data
        List testData = new ArrayList(reqNo);
        for (int i = 0; i < reqNo; i++) {
            int size = rnd.nextInt(20000);
            byte[] data = new byte[size];
            rnd.nextBytes(data);
            testData.add(data);
        }

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            public void handle(
                    final HttpRequest request, 
                    final HttpResponse response, 
                    final HttpContext context) throws HttpException, IOException {
                
                if (request instanceof HttpEntityEnclosingRequest) {
                    HttpEntity incoming = ((HttpEntityEnclosingRequest) request).getEntity();
                    byte[] data = EntityUtils.toByteArray(incoming);
                    
                    ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    outgoing.setChunked(true);
                    response.setEntity(outgoing);
                } else {
                    StringEntity outgoing = new StringEntity("No content"); 
                    response.setEntity(outgoing);
                }
            }
            
        });
        
        this.server.start();
        
        DefaultHttpClientConnection conn = new DefaultHttpClientConnection();
        HttpHost host = new HttpHost("localhost", this.server.getPort());
        
        try {
            for (int r = 0; r < reqNo; r++) {
                if (!conn.isOpen()) {
                    Socket socket = new Socket(host.getHostName(), host.getPort());
                    conn.bind(socket, this.client.getParams());
                }
                
                BasicHttpEntityEnclosingRequest post = new BasicHttpEntityEnclosingRequest("POST", "/");
                byte[] data = (byte[]) testData.get(r);
                ByteArrayEntity outgoing = new ByteArrayEntity(data);
                outgoing.setChunked(true);
                post.setEntity(outgoing);

                HttpResponse response = this.client.execute(post, host, conn);
                byte[] received = EntityUtils.toByteArray(response.getEntity());
                byte[] expected = (byte[]) testData.get(r);
                
                assertEquals(expected.length, received.length);
                for (int i = 0; i < expected.length; i++) {
                    assertEquals(expected[i], received[i]);
                }
                if (!this.client.keepAlive(response)) {
                    conn.close();
                }
            }
            //Verify the connection metrics
            HttpConnectionMetrics cm = conn.getMetrics();
            assertEquals(reqNo, cm.getRequestCount());
            assertEquals(reqNo, cm.getResponseCount());
        } finally {
            conn.close();
            this.server.shutdown();
        }
    }

    /**
     * This test case executes a series of simple HTTP/1.0 POST requests. 
     */
    @LargeTest
    public void testSimpleHttpPostsHTTP10() throws Exception {
        
        int reqNo = 20;
        
        Random rnd = new Random();
        
        // Prepare some random data
        List testData = new ArrayList(reqNo);
        for (int i = 0; i < reqNo; i++) {
            int size = rnd.nextInt(5000);
            byte[] data = new byte[size];
            rnd.nextBytes(data);
            testData.add(data);
        }

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            public void handle(
                    final HttpRequest request, 
                    final HttpResponse response, 
                    final HttpContext context) throws HttpException, IOException {
                
                if (request instanceof HttpEntityEnclosingRequest) {
                    HttpEntity incoming = ((HttpEntityEnclosingRequest) request).getEntity();
                    byte[] data = EntityUtils.toByteArray(incoming);
                    
                    ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    outgoing.setChunked(false);
                    response.setEntity(outgoing);
                } else {
                    StringEntity outgoing = new StringEntity("No content"); 
                    response.setEntity(outgoing);
                }
            }
            
        });
        
        this.server.start();
        
        // Set protocol level to HTTP/1.0
        this.client.getParams().setParameter(
                CoreProtocolPNames.PROTOCOL_VERSION, HttpVersion.HTTP_1_0);
        
        DefaultHttpClientConnection conn = new DefaultHttpClientConnection();
        HttpHost host = new HttpHost("localhost", this.server.getPort());
        
        try {
            for (int r = 0; r < reqNo; r++) {
                if (!conn.isOpen()) {
                    Socket socket = new Socket(host.getHostName(), host.getPort());
                    conn.bind(socket, this.client.getParams());
                }
                
                BasicHttpEntityEnclosingRequest post = new BasicHttpEntityEnclosingRequest("POST", "/");
                byte[] data = (byte[]) testData.get(r);
                ByteArrayEntity outgoing = new ByteArrayEntity(data);
                post.setEntity(outgoing);

                HttpResponse response = this.client.execute(post, host, conn);
                assertEquals(HttpVersion.HTTP_1_0, response.getStatusLine().getProtocolVersion());
                byte[] received = EntityUtils.toByteArray(response.getEntity());
                byte[] expected = (byte[]) testData.get(r);
                
                assertEquals(expected.length, received.length);
                for (int i = 0; i < expected.length; i++) {
                    assertEquals(expected[i], received[i]);
                }
                if (!this.client.keepAlive(response)) {
                    conn.close();
                }
            }
            
            //Verify the connection metrics
            HttpConnectionMetrics cm = conn.getMetrics();
            assertEquals(reqNo, cm.getRequestCount());
            assertEquals(reqNo, cm.getResponseCount());
        } finally {
            conn.close();
            this.server.shutdown();
        }
    }

    /**
     * This test case executes a series of simple POST requests using 
     * the 'expect: continue' handshake. 
     */
    @LargeTest
    public void testHttpPostsWithExpectContinue() throws Exception {
        
        int reqNo = 20;
        
        Random rnd = new Random();
        
        // Prepare some random data
        List testData = new ArrayList(reqNo);
        for (int i = 0; i < reqNo; i++) {
            int size = rnd.nextInt(5000);
            byte[] data = new byte[size];
            rnd.nextBytes(data);
            testData.add(data);
        }

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            public void handle(
                    final HttpRequest request, 
                    final HttpResponse response, 
                    final HttpContext context) throws HttpException, IOException {
                
                if (request instanceof HttpEntityEnclosingRequest) {
                    HttpEntity incoming = ((HttpEntityEnclosingRequest) request).getEntity();
                    byte[] data = EntityUtils.toByteArray(incoming);
                    
                    ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    outgoing.setChunked(true);
                    response.setEntity(outgoing);
                } else {
                    StringEntity outgoing = new StringEntity("No content"); 
                    response.setEntity(outgoing);
                }
            }
            
        });
        
        this.server.start();
        
        // Activate 'expect: continue' handshake
        this.client.getParams().setBooleanParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, true);
        
        DefaultHttpClientConnection conn = new DefaultHttpClientConnection();
        HttpHost host = new HttpHost("localhost", this.server.getPort());
        
        try {
            for (int r = 0; r < reqNo; r++) {
                if (!conn.isOpen()) {
                    Socket socket = new Socket(host.getHostName(), host.getPort());
                    conn.bind(socket, this.client.getParams());
                }
                
                BasicHttpEntityEnclosingRequest post = new BasicHttpEntityEnclosingRequest("POST", "/");
                byte[] data = (byte[]) testData.get(r);
                ByteArrayEntity outgoing = new ByteArrayEntity(data);
                outgoing.setChunked(true);
                post.setEntity(outgoing);

                HttpResponse response = this.client.execute(post, host, conn);
                byte[] received = EntityUtils.toByteArray(response.getEntity());
                byte[] expected = (byte[]) testData.get(r);
                
                assertEquals(expected.length, received.length);
                for (int i = 0; i < expected.length; i++) {
                    assertEquals(expected[i], received[i]);
                }
                if (!this.client.keepAlive(response)) {
                    conn.close();
                }
            }
            
            //Verify the connection metrics
            HttpConnectionMetrics cm = conn.getMetrics();
            assertEquals(reqNo, cm.getRequestCount());
            assertEquals(reqNo, cm.getResponseCount());
        } finally {
            conn.close();
            this.server.shutdown();
        }
    }
    
    
    /**
     * This test case executes a series of simple POST requests that do not 
     * meet the target server expectations. 
     */
    @LargeTest
    public void testHttpPostsWithExpectationVerification() throws Exception {
        
        int reqNo = 3;
        
        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            public void handle(
                    final HttpRequest request, 
                    final HttpResponse response, 
                    final HttpContext context) throws HttpException, IOException {
                
                StringEntity outgoing = new StringEntity("No content"); 
                response.setEntity(outgoing);
            }
            
        });
        
        this.server.setExpectationVerifier(new HttpExpectationVerifier() {

            public void verify(
                    final HttpRequest request, 
                    final HttpResponse response, 
                    final HttpContext context) throws HttpException {
                Header someheader = request.getFirstHeader("Secret");
                if (someheader != null) {
                    int secretNumber;
                    try {
                        secretNumber = Integer.parseInt(someheader.getValue());
                    } catch (NumberFormatException ex) {
                        response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
                        return;
                    }
                    if (secretNumber < 2) {
                        response.setStatusCode(HttpStatus.SC_EXPECTATION_FAILED);
                        ByteArrayEntity outgoing = new ByteArrayEntity(
                                EncodingUtils.getAsciiBytes("Wrong secret number")); 
                        response.setEntity(outgoing);
                    }
                }
            }
            
        });
        
        this.server.start();
        
        // Activate 'expect: continue' handshake
        this.client.getParams().setBooleanParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, true);
        
        DefaultHttpClientConnection conn = new DefaultHttpClientConnection();
        HttpHost host = new HttpHost("localhost", this.server.getPort());
        
        try {
            for (int r = 0; r < reqNo; r++) {
                if (!conn.isOpen()) {
                    Socket socket = new Socket(host.getHostName(), host.getPort());
                    conn.bind(socket, this.client.getParams());
                }
                
                BasicHttpEntityEnclosingRequest post = new BasicHttpEntityEnclosingRequest("POST", "/");
                post.addHeader("Secret", Integer.toString(r));
                ByteArrayEntity outgoing = new ByteArrayEntity(
                        EncodingUtils.getAsciiBytes("No content")); 
                post.setEntity(outgoing);

                HttpResponse response = this.client.execute(post, host, conn);

                HttpEntity entity = response.getEntity();
                assertNotNull(entity);
                entity.consumeContent();
                
                if (r < 2) {
                    assertEquals(HttpStatus.SC_EXPECTATION_FAILED, response.getStatusLine().getStatusCode());
                } else {
                    assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
                }
                
                if (!this.client.keepAlive(response)) {
                    conn.close();
                }
            }
            //Verify the connection metrics
            HttpConnectionMetrics cm = conn.getMetrics();
            assertEquals(reqNo, cm.getRequestCount());
            assertEquals(reqNo, cm.getResponseCount());
        } finally {
            conn.close();
            this.server.shutdown();
        }
    }
    
}
