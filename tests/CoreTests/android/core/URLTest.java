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

import android.test.suitebuilder.annotation.Suppress;
import junit.framework.TestCase;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

public class URLTest extends TestCase {

    private static void get(String u) throws IOException {
        URL url = new URL(u);
        URLConnection cn = url.openConnection();
        cn.connect();
//        System.out.println("Content-Type: " + cn.getContentType());
//        System.out.println("Content-Length: " + cn.getContentLength());

        InputStream stream = cn.getInputStream();
        if (stream == null) {
            throw new RuntimeException("stream is null");
        }
        byte[] data = new byte[1024];
        stream.read(data);

//            if (true) {
//                System.out.print("data=");
//                System.out.write(data);
//                System.out.println();
//            }

//                System.out.println("Content-Type: " + cn.getContentType());
//                System.out.print("data:");
//                System.out.write(data);
//                System.out.println();

        assertTrue(new String(data).indexOf("<html>") >= 0);
    }

    @Suppress
    public void testGetHTTP() throws Exception {
        get("http://www.google.com");
    }

    @Suppress
    public void testGetHTTPS() throws Exception {
        get("https://www.fortify.net/cgi/ssl_2.pl");
    }

    /**
     * Dummy HTTP server class for testing keep-alive behavior. Listens a
     * single time and responds to a given number of requests on the same
     * socket. Then closes the socket.
     */
    private static class DummyServer implements Runnable {

        private int keepAliveCount;
        private Map<String, String> headers = new HashMap<String, String>();

        public DummyServer(int keepAliveCount) {
            this.keepAliveCount = keepAliveCount;
        }

        public void run() {
            try {
                ServerSocket server = new ServerSocket(8182);
                Socket socket = server.accept();

                InputStream input = socket.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(input));
                try {
                    for (int i = 0; i < keepAliveCount; i++) {
                        reader.readLine();
                        headers.clear();
                        while (true) {
                            String header = reader.readLine();
                            if (header.length() == 0) {
                                break;
                            }
                            int colon = header.indexOf(":");
                            String key = header.substring(0, colon);
                            String value = header.substring(colon + 1).trim();
                            headers.put(key, value);
                        }

                        OutputStream output = socket.getOutputStream();
                        PrintWriter writer = new PrintWriter(output);

                        try {
                            writer.println("HTTP/1.1 200 OK");
                            String body = "Hello, Android world #" + i + "!";
                            writer.println("Content-Length: " + body.length());
                            writer.println("");
                            writer.print(body);
                            writer.flush();
                        } finally {
                            writer.close();
                        }
                    }
                } finally {
                    reader.close();
                }
                socket.close();
                server.close();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * Does a request to the given URL, reads and returns the result.
     */
    private String request(URL url) throws Exception {
        URLConnection connection = url.openConnection();
        connection.connect();

        InputStream input = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        try {
            return reader.readLine();
        } finally {
            reader.close();
        }
    }

    /**
     * Test case for HTTP keep-alive behavior.
     */
    @Suppress
    public void testGetKeepAlive() throws Exception {
        new Thread(new DummyServer(3)).start();
        Thread.sleep(100);

        // We expect the request to work three times, then it fails.
        URL url = new URL("http://localhost:8182");
        assertEquals("Hello, Android world #0!", request(url));
        assertEquals("Hello, Android world #1!", request(url));
        assertEquals("Hello, Android world #2!", request(url));

        try {
            request(url);
            fail("ConnectException expected.");
        } catch (Exception ex) {
            // Ok.
        }
    }

    @Suppress
    public void testUserAgentHeader() throws Exception {
        DummyServer server = new DummyServer(1);
        new Thread(server).start();
        Thread.sleep(100);

        // We expect the request to work three times, then it fails.
        request(new URL("http://localhost:8182"));

        String userAgent = server.headers.get("User-Agent");
        assertTrue("Unexpected User-Agent: " + userAgent, userAgent.matches(
                "Dalvik/[\\d.]+ \\(Linux; U; Android \\w+(;.*)?( Build/\\w+)?\\)"));
    }

    /**
     * Regression for issue 1001814.
     */
    @Suppress
    public void testHttpConnectionTimeout() throws Exception {
        int timeout = 5000;
        HttpURLConnection cn = null;
        long start = 0;
        try {
            start = System.currentTimeMillis();
            URL url = new URL("http://123.123.123.123");
            cn = (HttpURLConnection) url.openConnection();
            cn.setConnectTimeout(5000);
            cn.connect();
            fail("should have thrown an exception");
        } catch (IOException ioe) {
            long delay = System.currentTimeMillis() - start;
            if (Math.abs(timeout - delay) > 1000) {
                fail("Timeout was not accurate. it needed " + delay +
                        " instead of " + timeout + "miliseconds");
            }
        } finally {
            if (cn != null) {
                cn.disconnect();
            }
        }
    }

    /** 
     * Regression test for issue 1158780 where using '{' and '}' in an URL threw
     * an NPE. The RI accepts this URL and returns the status 404.
     */
    @Suppress
    public void testMalformedUrl() throws Exception {
        URL url = new URL("http://www.google.com/cgi-bin/myscript?g={United+States}+Borders+Mexico+{Climate+change}+Marketing+{Automotive+industry}+News+Health+Internet");
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        int status = conn.getResponseCode();
        android.util.Log.d("URLTest", "status: " + status);
    }
}
