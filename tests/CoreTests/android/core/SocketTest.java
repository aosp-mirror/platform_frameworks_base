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

import junit.framework.Assert;
import junit.framework.TestCase;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Semaphore;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;

/**
 * Regression tests for various socket related problems. And a few general
 * socket tests.
 */
public class SocketTest extends TestCase {

    private static final String NON_EXISTING_ADDRESS = "123.123.123.123";

    private static final String KNOW_GOOD_ADDRESS = "209.85.129.147";

    private static final String PACKAGE_DROPPING_ADDRESS = "191.167.0.1";
    
    // Test for basic bind/connect/accept behavior.
    @SmallTest
    public void testSocketSimple() throws Exception {
        ServerSocket ss;
        Socket s, s1;
        int port;

        IOException lastEx = null;

        ss = new ServerSocket();

        for (port = 9900; port < 9999; port++) {
            try {
                ss.bind(new InetSocketAddress("127.0.0.1", port));
                lastEx = null;
                break;
            } catch (IOException ex) {
                lastEx = ex;
            }
        }

        if (lastEx != null) {
            throw lastEx;
        }

        s = new Socket("127.0.0.1", port);

        s1 = ss.accept();

        s.getOutputStream().write(0xa5);

        assertEquals(0xa5, s1.getInputStream().read());

        s1.getOutputStream().write(0x5a);
        assertEquals(0x5a, s.getInputStream().read());
    }
    
    // Regression test for #820068: Wildcard address
    @SmallTest
    public void testWildcardAddress() throws Exception {
        Socket s2 = new Socket();
        s2.bind(new InetSocketAddress((InetAddress) null, 12345));
        byte[] addr = s2.getLocalAddress().getAddress();
        for (int i = 0; i < 4; i++) {
            assertEquals("Not the wildcard address", 0, addr[i]);
        }
    }
    
    // Regression test for #865753: server sockets not closing properly
    @SmallTest
    public void testServerSocketClose() throws Exception {
        ServerSocket s3 = new ServerSocket(23456);
        s3.close();
        ServerSocket s4 = new ServerSocket(23456);
        s4.close();
    }
    
    // Regression test for #876985: SO_REUSEADDR not working properly
    
    private Exception serverError = null;
    
    @LargeTest
    public void testSetReuseAddress() throws IOException {
        InetSocketAddress addr = new InetSocketAddress(8383);

        final ServerSocket serverSock = new ServerSocket();
        serverSock.setReuseAddress(true);
        serverSock.bind(addr);

        final Semaphore semThreadEnd = new Semaphore(0);
        new Thread() {
            @Override
            public void run() {
                try {
                    Socket sock = serverSock.accept();
                    sock.getInputStream().read();
                    sock.close();
                } catch (IOException e) {
                    serverError = e;
                }
                semThreadEnd.release();
            }
        }.start();

        // Give the server a bit of time for startup
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ex) {
            // Ignored.
        }
        
        Socket client = new Socket("localhost", 8383);
        client.getOutputStream().write(1);
        // Just leave this connection open from the client side. It will be
        // closed from the server side so the server stays in the TIME_WAIT
        // state for a while. setReuseAddress() should be able to handle this.

        try {
            semThreadEnd.acquire();
        } catch (InterruptedException e) {
            // ignore
        }
        serverSock.close();

        ServerSocket serverSock2 = new ServerSocket();
        serverSock2.setReuseAddress(true);
        serverSock2.bind(addr);
        serverSock2.close();
        
        if (serverError != null) {
            throw new RuntimeException("Server must complete without error", serverError);
        }
    }

    // Regression for 916701, a wrong exception was thrown after timeout of
    // a ServerSocket.
    @LargeTest
    public void testTimeoutException() throws IOException {
        ServerSocket s = new ServerSocket(9800);
        s.setSoTimeout(2000);
        try {
            s.accept();
        } catch (SocketTimeoutException e) {
            // this is ok.
        }
    }

    // Regression for issue 1001980, openening a SocketChannel threw an Exception
    @SmallTest
    public void testNativeSocketChannelOpen() throws IOException {
        SocketChannel.open();
    }

// Regression test for issue 1018016, connecting ignored a set timeout.
//
// Disabled because test behaves differently depending on networking
// environment. It works fine in the emulator and one the device with
// WLAN, but when 3G comes into play, the possible existence of a
// proxy makes it fail.
//
//    @LargeTest
//    public void testSocketSetSOTimeout() throws IOException {
//        Socket sock = new Socket();
//        int timeout = 5000;
//        long start = System.currentTimeMillis();
//        try {
//            sock.connect(new InetSocketAddress(NON_EXISTING_ADDRESS, 80), timeout);
//        } catch (SocketTimeoutException e) {
//            // expected
//            long delay = System.currentTimeMillis() - start;
//            if (Math.abs(delay - timeout) > 1000) {
//                fail("timeout was not accurate. expected: " + timeout
//                        + " actual: " + delay + " miliseconds.");
//            }
//        } finally {
//            try {
//                sock.close();
//            } catch (IOException ioe) {
//                // ignore
//            }
//        }
//    }
    
    /**
     * Regression test for 1062928: Dotted IP addresses (e.g., 192.168.100.1)
     * appear to be broken in the M5 SDK.
     * 
     * Tests that a connection given a ip-addressv4 such as 192.168.100.100 does
     * not fail - sdk m5 seems only to accept dns names instead of ip numbers.
     * ip 209.85.129.147 (one address of www.google.com) on port 80 (http) is
     * used to test the connection.
     */

// Commenting out this test since it is flaky, even at the best of times.  See
// #1191317 for Info.
    @Suppress
    public void disable_testConnectWithIP4IPAddr() {
        // call a Google Web server
        InetSocketAddress scktAddrss = new InetSocketAddress(KNOW_GOOD_ADDRESS,
                80);
        Socket clntSckt = new Socket();
        try {
            clntSckt.connect(scktAddrss, 5000);
        } catch (Throwable e) {
            fail("connection problem:" + e.getClass().getName() + ": "
                    + e.getMessage());
        } finally {
            try {
                clntSckt.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    
    // Regression test for #1058962: Socket.close() does not cause
    // socket.connect() to return immediately.
    private Socket client;
    
    private Exception error;
    
    private boolean connected;

// This test isn't working now, but really should work.
// TODO Enable this test again.

    @Suppress
    public void disable_testSocketConnectClose() {
        try {
            client = new Socket();
            
            new Thread() {
                @Override
                public void run() {
                    try {
                        client.connect(new InetSocketAddress(PACKAGE_DROPPING_ADDRESS, 1357));
                    } catch (Exception ex) {
                        error = ex;
                    }
                    
                    connected = true;
                }
            }.start();
            
            Thread.sleep(1000);
            
            Assert.assertNull("Connect must not fail immediately. Maybe try different address.", error);
            Assert.assertFalse("Connect must not succeed. Maybe try different address.", connected);
            
            client.close();
            
            Thread.sleep(1000);

            if (error == null) {
                fail("Socket connect still ongoing");
            } else if (!(error instanceof SocketException)) {
                fail("Socket connect interrupted with wrong error: " + error.toString());
            }
            
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
            
    }
    
}
