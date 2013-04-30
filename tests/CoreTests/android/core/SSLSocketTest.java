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

import com.android.org.conscrypt.FileClientSessionCache;
import com.android.org.conscrypt.OpenSSLContextImpl;
import com.android.org.conscrypt.SSLClientSessionCache;
import org.apache.commons.codec.binary.Base64;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyManagementException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * SSL integration tests that hit real servers.
 */
public class SSLSocketTest extends TestCase {

    private static SSLSocketFactory clientFactory =
            (SSLSocketFactory) SSLSocketFactory.getDefault();

    /**
     * Does a number of HTTPS requests on some host and consumes the response.
     * We don't use the HttpsUrlConnection class, but do this on our own
     * with the SSLSocket class. This gives us a chance to test the basic
     * behavior of SSL.
     *
     * @param host      The host name the request is being sent to.
     * @param port      The port the request is being sent to.
     * @param path      The path being requested (e.g. "/index.html").
     * @param outerLoop The number of times we reconnect and do the request.
     * @param innerLoop The number of times we do the request for each
     *                  connection (using HTTP keep-alive).
     * @param delay     The delay after each request (in seconds).
     * @throws IOException When a problem occurs.
     */
    private void fetch(SSLSocketFactory socketFactory, String host, int port,
            boolean secure, String path, int outerLoop, int innerLoop,
            int delay, int timeout) throws IOException {
        InetSocketAddress address = new InetSocketAddress(host, port);

        for (int i = 0; i < outerLoop; i++) {
            // Connect to the remote host
            Socket socket = secure ? socketFactory.createSocket()
                    : new Socket();
            if (timeout >= 0) {
                socket.setKeepAlive(true);
                socket.setSoTimeout(timeout * 1000);
            }
            socket.connect(address);

            // Get the streams
            OutputStream output = socket.getOutputStream();
            PrintWriter writer = new PrintWriter(output);

            try {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                try {
                    for (int j = 0; j < innerLoop; j++) {
                        android.util.Log.d("SSLSocketTest",
                                "GET https://" + host + path + " HTTP/1.1");

                        // Send a request
                        writer.println("GET https://" + host + path + " HTTP/1.1\r");
                        writer.println("Host: " + host + "\r");
                        writer.println("Connection: " +
                                (j == innerLoop - 1 ? "Close" : "Keep-Alive")
                                + "\r");
                        writer.println("\r");
                        writer.flush();

                        int length = -1;
                        boolean chunked = false;

                        String line = input.readLine();

                        if (line == null) {
                            throw new IOException("No response from server");
                            // android.util.Log.d("SSLSocketTest", "No response from server");
                        }

                        // Consume the headers, check content length and encoding type
                        while (line != null && line.length() != 0) {
//                    System.out.println(line);
                            int dot = line.indexOf(':');
                            if (dot != -1) {
                                String key = line.substring(0, dot).trim();
                                String value = line.substring(dot + 1).trim();

                                if ("Content-Length".equalsIgnoreCase(key)) {
                                    length = Integer.valueOf(value);
                                } else if ("Transfer-Encoding".equalsIgnoreCase(key)) {
                                    chunked = "Chunked".equalsIgnoreCase(value);
                                }

                            }
                            line = input.readLine();
                        }

                        assertTrue("Need either content length or chunked encoding", length != -1
                                || chunked);

                        // Consume the content itself
                        if (chunked) {
                            length = Integer.parseInt(input.readLine(), 16);
                            while (length != 0) {
                                byte[] buffer = new byte[length];
                                input.readFully(buffer);
                                input.readLine();
                                length = Integer.parseInt(input.readLine(), 16);
                            }
                            input.readLine();
                        } else {
                            byte[] buffer = new byte[length];
                            input.readFully(buffer);
                        }

                        // Sleep for the given number of seconds
                        try {
                            Thread.sleep(delay * 1000);
                        } catch (InterruptedException ex) {
                            // Shut up!
                        }
                    }
                } finally {
                    input.close();
                }
            } finally {
                writer.close();
            }
            // Close the connection
            socket.close();
        }
    }

    /**
     * Invokes fetch() with the default socket factory.
     */
    private void fetch(String host, int port, boolean secure, String path,
            int outerLoop, int innerLoop,
            int delay, int timeout) throws IOException {
        fetch(clientFactory, host, port, secure, path, outerLoop, innerLoop,
                delay, timeout);
    }

    /**
     * Does a single request for each of the hosts. Consumes the response.
     *
     * @throws IOException If a problem occurs.
     */
    public void testSimple() throws IOException {
        fetch("www.fortify.net", 443, true, "/sslcheck.html", 1, 1, 0, 60);
        fetch("mail.google.com", 443, true, "/mail/", 1, 1, 0, 60);
        fetch("www.paypal.com", 443, true, "/", 1, 1, 0, 60);
        fetch("www.yellownet.ch", 443, true, "/", 1, 1, 0, 60);
    }

    /**
     * Does repeated requests for each of the hosts, with the connection being
     * closed in between.
     *
     * @throws IOException If a problem occurs.
     */
    public void testRepeatedClose() throws IOException {
        fetch("www.fortify.net", 443, true, "/sslcheck.html", 10, 1, 0, 60);
        fetch("mail.google.com", 443, true, "/mail/", 10, 1, 0, 60);
        fetch("www.paypal.com", 443, true, "/", 10, 1, 0, 60);
        fetch("www.yellownet.ch", 443, true, "/", 10, 1, 0, 60);
    }

    /**
     * Does repeated requests for each of the hosts, with the connection being
     * kept alive in between.
     *
     * @throws IOException If a problem occurs.
     */
    public void testRepeatedKeepAlive() throws IOException {
        fetch("www.fortify.net", 443, true, "/sslcheck.html", 1, 10, 0, 60);
        fetch("mail.google.com", 443, true, "/mail/", 1, 10, 0, 60);

        // These two don't accept keep-alive
        // fetch("www.paypal.com", 443, "/", 1, 10);
        // fetch("www.yellownet.ch", 443, "/", 1, 10);
    }

    /**
     * Does repeated requests for each of the hosts, with the connection being
     * closed in between. Waits a couple of seconds after each request, but
     * stays within a reasonable timeout. Expectation is that the connection
     * stays open.
     *
     * @throws IOException If a problem occurs.
     */
    public void testShortTimeout() throws IOException {
        fetch("www.fortify.net", 443, true, "/sslcheck.html", 1, 10, 5, 60);
        fetch("mail.google.com", 443, true, "/mail/", 1, 10, 5, 60);

        // These two don't accept keep-alive
        // fetch("www.paypal.com", 443, "/", 1, 10);
        // fetch("www.yellownet.ch", 443, "/", 1, 10);
    }

    /**
     * Does repeated requests for each of the hosts, with the connection being
     * kept alive in between. Waits a longer time after each request.
     * Expectation is that the host closes the connection.
     */
    public void testLongTimeout() {
        // Seems to have a veeeery long timeout.
        // fetch("www.fortify.net", 443, "/sslcheck.html", 1, 2, 60);

        // Google has a 60s timeout, so 90s of waiting should trigger it.
        try {
            fetch("mail.google.com", 443, true, "/mail/", 1, 2, 90, 180);
            fail("Oops - timeout expected.");
        } catch (IOException ex) {
            // Expected.
        }

        // These two don't accept keep-alive
        // fetch("www.paypal.com", 443, "/", 1, 10);
        // fetch("www.yellownet.ch", 443, "/", 1, 10);
    }

    /**
     * Does repeated requests for each of the hosts, with the connection being
     * closed in between. Waits a longer time after each request. Expectation is
     * that the host closes the connection.
     */
    // These two need manual interaction to reproduce...
    public void xxtestBrokenConnection() {
        try {
            fetch("www.fortify.net", 443, true, "/sslcheck.html", 1, 2, 60, 60);
            fail("Oops - timeout expected.");
        } catch (IOException ex) {
            android.util.Log.d("SSLSocketTest", "Exception", ex);
            // Expected.
        }

        // These two don't accept keep-alive
        // fetch("www.paypal.com", 443, "/", 1, 10);
        // fetch("www.yellownet.ch", 443, "/", 1, 10);
    }

    /**
     * Does repeated requests for each of the hosts, with the connection being
     * closed in between. Waits a longer time after each request. Expectation is
     * that the host closes the connection.
     */
    // These two need manual interaction to reproduce...
    public void xxtestBrokenConnection2() {
        try {
            fetch("www.heise.de", 80, false, "/index.html", 1, 2, 60, 60);
            fail("Oops - timeout expected.");
        } catch (IOException ex) {
            android.util.Log.d("SSLSocketTest", "Exception", ex);
            // Expected.
        }

        // These two don't accept keep-alive
        // fetch("www.paypal.com", 443, "/", 1, 10);
        // fetch("www.yellownet.ch", 443, "/", 1, 10);
    }

    /**
     * Regression test for 865926: SSLContext.init() should
     * use default values for null arguments.
     */
    public void testContextInitNullArgs() throws Exception {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
    }

    /**
     * Regression test for 963650: javax.net.ssl.KeyManager has no implemented
     * (documented?) algorithms.
     */
    public void testDefaultAlgorithms() throws Exception {
            SSLContext ctx = SSLContext.getInstance("TLS");
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("X509");
            KeyStore ks = KeyStore.getInstance("BKS");

            assertEquals("X509", kmf.getAlgorithm());
            assertEquals("X509", KeyManagerFactory.getDefaultAlgorithm());

            assertEquals("BKS", ks.getType());
            assertEquals("BKS", KeyStore.getDefaultType());
    }

    /**
     * Regression test for problem where close() resulted in a hand if
     * a different thread was sitting in a blocking read or write.
     */
    public void testMultithreadedClose() throws Exception {
            InetSocketAddress address = new InetSocketAddress("www.fortify.net", 443);
            final Socket socket = clientFactory.createSocket();
            socket.connect(address);

            Thread reader = new Thread() {
                @Override
                public void run() {
                    try {
                        byte[] buffer = new byte[512];
                        InputStream stream = socket.getInputStream();
                        socket.getInputStream().read(buffer);
                    } catch (Exception ex) {
                        android.util.Log.d("SSLSocketTest",
                                "testMultithreadedClose() reader got " + ex.toString());
                    }
                }
            };

            Thread closer = new Thread() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(5000);
                        socket.close();
                    } catch (Exception ex) {
                        android.util.Log.d("SSLSocketTest",
                                "testMultithreadedClose() closer got " + ex.toString());
                    }
                }
            };

            android.util.Log.d("SSLSocketTest", "testMultithreadedClose() starting reader...");
            reader.start();
            android.util.Log.d("SSLSocketTest", "testMultithreadedClose() starting closer...");
            closer.start();

            long t1 = System.currentTimeMillis();
            android.util.Log.d("SSLSocketTest", "testMultithreadedClose() joining reader...");
            reader.join(30000);
            android.util.Log.d("SSLSocketTest", "testMultithreadedClose() joining closer...");
            closer.join(30000);
            long t2 = System.currentTimeMillis();

            assertTrue("Concurrent close() hangs", t2 - t1 < 30000);
    }

    private int multithreadedFetchRuns;

    private int multithreadedFetchWins;

    private Random multithreadedFetchRandom = new Random();

    /**
     * Regression test for problem where multiple threads with multiple SSL
     * connection would cause problems due to either missing native locking
     * or the slowness of the SSL connections.
     */
    public void testMultithreadedFetch() {
        Thread[] threads = new Thread[10];

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread() {
                @Override
                public void run() {
                    for (int i = 0; i < 10; i++) {
                        try {
                            multithreadedFetchRuns++;
                            switch (multithreadedFetchRandom.nextInt(4)) {
                                case 0: {
                                    fetch("www.fortify.net", 443,
                                            true, "/sslcheck.html", 1, 1, 0, 60);
                                    break;
                                }

                                case 1: {
                                    fetch("mail.google.com", 443, true, "/mail/", 1, 1, 0, 60);
                                    break;
                                }

                                case 2: {
                                    fetch("www.paypal.com", 443, true, "/", 1, 1, 0, 60);
                                    break;
                                }

                                case 3: {
                                    fetch("www.yellownet.ch", 443, true, "/", 1, 1, 0, 60);
                                    break;
                                }
                            }
                            multithreadedFetchWins++;
                        } catch (Exception ex) {
                            android.util.Log.d("SSLSocketTest",
                                    "testMultithreadedFetch() got Exception", ex);
                        }
                    }
                }
            };
            threads[i].start();

            android.util.Log.d("SSLSocketTest", "testMultithreadedFetch() started thread #" + i);
        }

        for (int i = 0; i < threads.length; i++) {
            try {
                threads[i].join();
                android.util.Log.d("SSLSocketTest", "testMultithreadedFetch() joined thread #" + i);
            } catch (InterruptedException ex) {
                // Not interested.
            }
        }

        assertTrue("At least 95% of multithreaded SSL connections must succeed",
                multithreadedFetchWins >= (multithreadedFetchRuns * 95) / 100);
    }

    // -------------------------------------------------------------------------
    // Regression test for #1204316: Missing client cert unit test. Passes on
    // both Android and the RI. To use on the RI, install Apache Commons and
    // replace the references to the base64-encoded keys by the JKS versions.
    // -------------------------------------------------------------------------
    
    /** 
     * Defines the keystore contents for the server, JKS version. Holds just a
     * single self-generated key. The subject name is "Test Server".
     */
    private static final String SERVER_KEYS_JKS = 
        "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";

    /** 
     * Defines the keystore contents for the server, BKS version. Holds just a
     * single self-generated key. The subject name is "Test Server".
     */
    private static final String SERVER_KEYS_BKS = 
        "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";
    
    /** 
     * Defines the keystore contents for the client, JKS version. Holds just a
     * single self-generated key. The subject name is "Test Client".
     */
    private static final String CLIENT_KEYS_JKS = 
        "/u3+7QAAAAIAAAABAAAAAQAFbXlrZXkAAAEaWFhyMAAAArkwggK1MA4GCisGAQQBKgIRAQEFAASC" +
        "AqGVSfXolBStZy4nnRNn4fAr+S7kfU2BS23wwW8uB2Ru3GvtLzlK9q08Gvq/LNqBafjyFTVL5FV5" +
        "SED/8YomO5a98GpskSeRvytCiTBLJdgGhws5TOGekgIAcBROPGIyOtJPQ0HfOQs+BqgzGDHzHQhw" +
        "u/8Tm6yQwiP+W/1I9B1QnaEztZA3mhTyMMJsmsFTYroGgAog885D5Cmzd8sYGfxec3R6I+xcmBAY" +
        "eibR5kGpWwt1R+qMvRrtBqh5r6WSKhCBNax+SJVbtUNRiKyjKccdJg6fGqIWWeivwYTy0OhjA6b4" +
        "NiZ/ZZs5pxFGWUj/Rlp0RYy8fCF6aw5/5s4Bf4MI6dPSqMG8Hf7sJR91GbcELyzPdM0h5lNavgit" +
        "QPEzKeuDrGxhY1frJThBsNsS0gxeu+OgfJPEb/H4lpYX5IvuIGbWKcxoO9zq4/fimIZkdA8A+3eY" +
        "mfDaowvy65NBVQPJSxaOyFhLHfeLqOeCsVENAea02vA7andZHTZehvcrqyKtm+z8ncHGRC2H9H8O" +
        "jKwKHfxxrYY/jMAKLl00+PBb3kspO+BHI2EcQnQuMw/zr83OR9Meq4TJ0TMuNkApZELAeFckIBbS" +
        "rBr8NNjAIfjuCTuKHhsTFWiHfk9ZIzigxXagfeDRiyVc6khOuF/bGorj23N2o7Rf3uLoU6PyXWi4" +
        "uhctR1aL6NzxDoK2PbYCeA9hxbDv8emaVPIzlVwpPK3Ruvv9mkjcOhZ74J8bPK2fQmbplbOljcZi" +
        "tZijOfzcO/11JrwhuJZRA6wanTqHoujgChV9EukVrmbWGGAcewFnAsSbFXIik7/+QznXaDIt5NgL" +
        "H/Bcz4Z/fdV7Ae1eUaxKXdPbI//4J+8liVT/d8awjW2tldIaDlmGMR3aoc830+3mAAAAAQAFWC41" +
        "MDkAAAJIMIICRDCCAa0CBEhHxLgwDQYJKoZIhvcNAQEEBQAwaTELMAkGA1UEBhMCVVMxEzARBgNV" +
        "BAgTCkNhbGlmb3JuaWExDDAKBgNVBAcTA01UVjEPMA0GA1UEChMGR29vZ2xlMRAwDgYDVQQLEwdB" +
        "bmRyb2lkMRQwEgYDVQQDEwtUZXN0IENsaWVudDAeFw0wODA2MDUxMDQ5MjhaFw0wODA5MDMxMDQ5" +
        "MjhaMGkxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMQwwCgYDVQQHEwNNVFYxDzAN" +
        "BgNVBAoTBkdvb2dsZTEQMA4GA1UECxMHQW5kcm9pZDEUMBIGA1UEAxMLVGVzdCBDbGllbnQwgZ8w" +
        "DQYJKoZIhvcNAQEBBQADgY0AMIGJAoGBAIK3Q+KiFbmCGg422TAo4gggdhMH6FJhiuz8DxRyeMKR" +
        "UAfP4MK0wtc8N42waZ6OKvxpBFUy0BRfBsX0GD4Ku99yu9/tavSigTraeJtwV3WWRRjIqk7L3wX5" +
        "cmgS2KSD43Y0rNUKrko26lnt9N4qiYRBSj+tcAN3Lx9+ptqk1LApAgMBAAEwDQYJKoZIhvcNAQEE" +
        "BQADgYEANb7Q1GVSuy1RPJ0FmiXoMYCCtvlRLkmJphwxovK0cAQK12Vll+yAzBhHiQHy/RA11mng" +
        "wYudC7u3P8X/tBT8GR1Yk7QW3KgFyPafp3lQBBCraSsfrjKj+dCLig1uBLUr4f68W8VFWZWWTHqp" +
        "NMGpCX6qmjbkJQLVK/Yfo1ePaUexPSOX0G9m8+DoV3iyNw6at01NRw==";

    /** 
     * Defines the keystore contents for the client, BKS version. Holds just a
     * single self-generated key. The subject name is "Test Client".
     */
    private static final String CLIENT_KEYS_BKS = 
        "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";    
    /** 
     * Defines the password for the keystore.
     */
    private static final String PASSWORD = "android";
            
    /** 
     * Implements basically a dummy TrustManager. It stores the certificate
     * chain it sees, so it can later be queried.
     */
    class TestTrustManager implements X509TrustManager {
        
        private X509Certificate[] chain;
        
        private String authType;
        
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            this.chain = chain;
            this.authType = authType;
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            this.chain = chain;
            this.authType = authType;
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        public X509Certificate[] getChain() {
            return chain;
        }
        
        public String getAuthType() {
            return authType;
        }
        
    }
    
    /** 
     * Implements a test SSL socket server. It wait for a connection on a given
     * port, requests client authentication (if specified), and read 256 bytes
     * from the socket. 
     */
    class TestServer implements Runnable {

        public static final int CLIENT_AUTH_NONE = 0;

        public static final int CLIENT_AUTH_WANTED = 1;

        public static final int CLIENT_AUTH_NEEDED = 2;
        
        private TestTrustManager trustManager;

        private Exception exception;

        private int port;
        
        private int clientAuth;
        
        private boolean provideKeys;

        public TestServer(int port, boolean provideKeys, int clientAuth) {
            this.port = port;
            this.clientAuth = clientAuth;
            this.provideKeys = provideKeys;
            
            trustManager = new TestTrustManager(); 
        }
        
        public void run() {
            try {
                KeyManager[] keyManagers = provideKeys
                        ? getKeyManagers(SERVER_KEYS_BKS) : null;
                TrustManager[] trustManagers = new TrustManager[] {
                        trustManager };

                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(keyManagers, trustManagers, null);
                
                SSLServerSocket serverSocket
                        = (SSLServerSocket) sslContext.getServerSocketFactory()
                        .createServerSocket();
                
                if (clientAuth == CLIENT_AUTH_WANTED) {
                    serverSocket.setWantClientAuth(true);
                } else if (clientAuth == CLIENT_AUTH_NEEDED) {
                    serverSocket.setNeedClientAuth(true);
                } else {
                    serverSocket.setWantClientAuth(false);
                }
                
                serverSocket.bind(new InetSocketAddress(port));
                
                SSLSocket clientSocket = (SSLSocket) serverSocket.accept();

                InputStream stream = clientSocket.getInputStream();

                for (int i = 0; i < 256; i++) {
                    int j = stream.read();
                    if (i != j) {
                        throw new RuntimeException("Error reading socket,"
                                + " expected " + i + ", got " + j);
                    }
                }
                
                stream.close();
                clientSocket.close();
                serverSocket.close();
                
            } catch (Exception ex) {
                exception = ex;
            }
        }

        public Exception getException() {
            return exception;
        }
        
        public X509Certificate[] getChain() {
            return trustManager.getChain();
        }
        
    }

    /** 
     * Implements a test SSL socket client. It open a connection to localhost on
     * a given port and writes 256 bytes to the socket. 
     */
    class TestClient implements Runnable {
        
        private TestTrustManager trustManager;

        private Exception exception;
        
        private int port;
        
        private boolean provideKeys;
        
        public TestClient(int port, boolean provideKeys) {
            this.port = port;
            this.provideKeys = provideKeys;
            
            trustManager = new TestTrustManager(); 
        }
        
        public void run() {
            try {
                KeyManager[] keyManagers = provideKeys
                        ? getKeyManagers(CLIENT_KEYS_BKS) : null;
                TrustManager[] trustManagers = new TrustManager[] {
                        trustManager };

                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(keyManagers, trustManagers, null);
                
                SSLSocket socket = (SSLSocket) sslContext.getSocketFactory()
                        .createSocket();

                socket.connect(new InetSocketAddress(port));
                socket.startHandshake();

                OutputStream stream = socket.getOutputStream();
                
                for (int i = 0; i < 256; i++) {
                    stream.write(i);
                }
                
                stream.flush();
                stream.close();
                socket.close();
                
            } catch (Exception ex) {
                exception = ex;
            }
        }

        public Exception getException() {
            return exception;
        }

        public X509Certificate[] getChain() {
            return trustManager.getChain();
        }
        
    }
    
    /**
     * Loads a keystore from a base64-encoded String. Returns the KeyManager[]
     * for the result.
     */
    private KeyManager[] getKeyManagers(String keys) throws Exception {
        byte[] bytes = new Base64().decode(keys.getBytes());                    
        InputStream inputStream = new ByteArrayInputStream(bytes);
        
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(inputStream, PASSWORD.toCharArray());
        inputStream.close();
        
        String algorithm = KeyManagerFactory.getDefaultAlgorithm();
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(algorithm);
        keyManagerFactory.init(keyStore, PASSWORD.toCharArray());
        
        return keyManagerFactory.getKeyManagers();
    }

    /**
     * Implements the actual test case. Launches a server and a client, requires
     * client authentication and checks the certificates afterwards (not in the
     * usual sense, we just make sure that we got the expected certificates,
     * because our self-signed test certificates are not valid.)
     */
    public void testClientAuth() {
        try {
            TestServer server = new TestServer(8088, true, TestServer.CLIENT_AUTH_WANTED);
            TestClient client = new TestClient(8088, true);
            
            Thread serverThread = new Thread(server);
            Thread clientThread = new Thread(client);
            
            serverThread.start();
            clientThread.start();
            
            serverThread.join();
            clientThread.join();
            
            // The server must have completed without an exception.
            if (server.getException() != null) {
                throw new RuntimeException(server.getException());
            }

            // The client must have completed without an exception.
            if (client.getException() != null) {
                throw new RuntimeException(client.getException());
            }
            
            // Caution: The clientChain is the certificate chain from our
            // client object. It contains the server certificates, of course!
            X509Certificate[] clientChain = client.getChain();
            assertTrue("Client cert chain must not be null", clientChain != null);
            assertTrue("Client cert chain must not be empty", clientChain.length != 0);
            assertEquals("CN=Test Server, OU=Android, O=Google, L=MTV, ST=California, C=US", clientChain[0].getSubjectDN().toString());
            // Important part ------^
            
            // Caution: The serverChain is the certificate chain from our
            // server object. It contains the client certificates, of course!
            X509Certificate[] serverChain = server.getChain();
            assertTrue("Server cert chain must not be null", serverChain != null);
            assertTrue("Server cert chain must not be empty", serverChain.length != 0);
            assertEquals("CN=Test Client, OU=Android, O=Google, L=MTV, ST=California, C=US", serverChain[0].getSubjectDN().toString());
            // Important part ------^
            
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // -------------------------------------------------------------------------
    private SSLSocket handshakeSocket;

    private Exception handshakeException;

    
    public void testSSLHandshakeHangTimeout() {
        
        Thread thread = new Thread() {
            @Override
            public void run() {
                try {
                    SSLSocket socket = (SSLSocket)clientFactory.createSocket(
                            "www.heise.de", 80);
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    socket.close();
                } catch (Exception ex) {
                    handshakeException = ex;
                }
            }
        };
        
        thread.start();
        
        try {
            thread.join(10000);
        } catch (InterruptedException ex) {
            // Ignore.
        }
        
        if (handshakeException == null) {
            fail("SSL handshake should have failed.");
        }
    }

    public void testSSLHandshakeHangClose() {
        
        Thread thread = new Thread() {
            @Override
            public void run() {
                try {
                    handshakeSocket = (SSLSocket)clientFactory.createSocket(
                            "www.heise.de", 80);
                    handshakeSocket.startHandshake();
                } catch (Exception ex) {
                    handshakeException = ex;
                }
            }
        };
        
        thread.start();

        
        try {
            Thread.sleep(5000);
            try {
                handshakeSocket.close();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }

            thread.join(5000);
        } catch (InterruptedException ex) {
            // Ignore.
        }
        
        if (handshakeException == null) {
            fail("SSL handshake should have failed.");
        }
    }

    /**
     * Tests our in-memory and persistent caching support.
     */
    public void testClientSessionCaching() throws IOException,
            KeyManagementException {
        OpenSSLContextImpl context = new OpenSSLContextImpl();

        // Cache size = 2.
        FakeClientSessionCache fakeCache = new FakeClientSessionCache();
        context.engineInit(null, null, null);
        context.engineGetClientSessionContext().setPersistentCache(fakeCache);
        SSLSocketFactory socketFactory = context.engineGetSocketFactory();
        context.engineGetClientSessionContext().setSessionCacheSize(2);
        makeRequests(socketFactory);
        List<String> smallCacheOps = Arrays.asList(
                "get www.fortify.net",
                "put www.fortify.net",
                "get www.paypal.com",
                "put www.paypal.com",
                "get www.yellownet.ch",
                "put www.yellownet.ch",

                // At this point, all in-memory cache requests should miss,
                // but the sessions will still be in the persistent cache.
                "get www.fortify.net",
                "get www.paypal.com",
                "get www.yellownet.ch"
        );
        assertEquals(smallCacheOps, fakeCache.ops);

        // Cache size = 3.
        fakeCache = new FakeClientSessionCache();
        context.engineInit(null, null, null);
        context.engineGetClientSessionContext().setPersistentCache(fakeCache);
        socketFactory = context.engineGetSocketFactory();
        context.engineGetClientSessionContext().setSessionCacheSize(3);
        makeRequests(socketFactory);
        List<String> bigCacheOps = Arrays.asList(
                "get www.fortify.net",
                "put www.fortify.net",
                "get www.paypal.com",
                "put www.paypal.com",
                "get www.yellownet.ch",
                "put www.yellownet.ch"

                // At this point, all results should be in the in-memory
                // cache, and the persistent cache shouldn't be hit anymore.
        );
        assertEquals(bigCacheOps, fakeCache.ops);

        // Cache size = 4.
        fakeCache = new FakeClientSessionCache();
        context.engineInit(null, null, null);
        context.engineGetClientSessionContext().setPersistentCache(fakeCache);
        socketFactory = context.engineGetSocketFactory();
        context.engineGetClientSessionContext().setSessionCacheSize(4);
        makeRequests(socketFactory);
        assertEquals(bigCacheOps, fakeCache.ops);
    }

    /**
     * Executes sequence of requests twice using given socket factory.
     */
    private void makeRequests(SSLSocketFactory socketFactory)
            throws IOException {
        for (int i = 0; i < 2; i++) {
            fetch(socketFactory, "www.fortify.net", 443, true, "/sslcheck.html",
                    1, 1, 0, 60);
            fetch(socketFactory, "www.paypal.com", 443, true, "/",
                    1, 1, 0, 60);
            fetch(socketFactory, "www.yellownet.ch", 443, true, "/",
                    1, 1, 0, 60);
        }
    }

    /**
     * Fake in the sense that it doesn't actually persist anything.
     */
    static class FakeClientSessionCache implements SSLClientSessionCache {

        List<String> ops = new ArrayList<String>();
        Map<String, byte[]> sessions = new HashMap<String, byte[]>();

        public byte[] getSessionData(String host, int port) {
            ops.add("get " + host);
            return sessions.get(host);
        }

        public void putSessionData(SSLSession session, byte[] sessionData) {
            String host = session.getPeerHost();
            System.err.println("length: " + sessionData.length);
            ops.add("put " + host);
            sessions.put(host, sessionData);
        }
    }

    public void testFileBasedClientSessionCache() throws IOException,
            KeyManagementException {
        OpenSSLContextImpl context = new OpenSSLContextImpl();
        String tmpDir = System.getProperty("java.io.tmpdir");
        if (tmpDir == null) {
            fail("Please set 'java.io.tmpdir' system property.");
        }
        File cacheDir = new File(tmpDir
                + "/" + SSLSocketTest.class.getName() + "/cache");
        deleteDir(cacheDir);
        SSLClientSessionCache fileCache
                = FileClientSessionCache.usingDirectory(cacheDir);
        try {
            ClientSessionCacheProxy cacheProxy
                    = new ClientSessionCacheProxy(fileCache);
            context.engineInit(null, null, null);
            context.engineGetClientSessionContext().setPersistentCache(cacheProxy);
            SSLSocketFactory socketFactory = context.engineGetSocketFactory();
            context.engineGetClientSessionContext().setSessionCacheSize(1);
            makeRequests(socketFactory);
            List<String> expected = Arrays.asList(
                    "unsuccessful get www.fortify.net",
                    "put www.fortify.net",
                    "unsuccessful get www.paypal.com",
                    "put www.paypal.com",
                    "unsuccessful get www.yellownet.ch",
                    "put www.yellownet.ch",

                    // At this point, all in-memory cache requests should miss,
                    // but the sessions will still be in the persistent cache.
                    "successful get www.fortify.net",
                    "successful get www.paypal.com",
                    "successful get www.yellownet.ch"
            );
            assertEquals(expected, cacheProxy.ops);

            // Try again now that file-based cache is populated.
            fileCache = FileClientSessionCache.usingDirectory(cacheDir);
            cacheProxy = new ClientSessionCacheProxy(fileCache);
            context.engineInit(null, null, null);
            context.engineGetClientSessionContext().setPersistentCache(cacheProxy);
            socketFactory = context.engineGetSocketFactory();
            context.engineGetClientSessionContext().setSessionCacheSize(1);
            makeRequests(socketFactory);
            expected = Arrays.asList(
                    "successful get www.fortify.net",
                    "successful get www.paypal.com",
                    "successful get www.yellownet.ch",
                    "successful get www.fortify.net",
                    "successful get www.paypal.com",
                    "successful get www.yellownet.ch"
            );
            assertEquals(expected, cacheProxy.ops);
        } finally {
            deleteDir(cacheDir);
        }
    }

    private static void deleteDir(File directory) {
        if (!directory.exists()) {
            return;
        }
        for (File file : directory.listFiles()) {
            file.delete();
        }
        directory.delete();
    }

    static class ClientSessionCacheProxy implements SSLClientSessionCache {

        final SSLClientSessionCache delegate;
        final List<String> ops = new ArrayList<String>();

        ClientSessionCacheProxy(SSLClientSessionCache delegate) {
            this.delegate = delegate;
        }

        public byte[] getSessionData(String host, int port) {
            byte[] sessionData = delegate.getSessionData(host, port);
            ops.add((sessionData == null ? "unsuccessful" : "successful")
                    + " get " + host);
            return sessionData;
        }

        public void putSessionData(SSLSession session, byte[] sessionData) {
            delegate.putSessionData(session, sessionData);
            ops.add("put " + session.getPeerHost());
        }
    }

    public static void main(String[] args) throws KeyManagementException, IOException {
        new SSLSocketTest().testFileBasedClientSessionCache();
    }
}
