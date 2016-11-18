/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.net.RoughtimeClient;
import android.util.Log;
import libcore.util.HexEncoding;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.security.MessageDigest;
import java.util.Arrays;
import junit.framework.TestCase;


public class RoughtimeClientTest extends TestCase {
    private static final String TAG = "RoughtimeClientTest";

    private static final long TEST_TIME = 8675309;
    private static final int TEST_RADIUS = 42;

    private final RoughtimeTestServer mServer = new RoughtimeTestServer();
    private final RoughtimeClient mClient = new RoughtimeClient();

    public void testBasicWorkingRoughtimeClientQuery() throws Exception {
        mServer.shouldRespond(true);
        assertTrue(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(1, mServer.numRepliesSent());
    }

    public void testDnsResolutionFailure() throws Exception {
        mServer.shouldRespond(true);
        assertFalse(mClient.requestTime("roughtime.server.doesnotexist.example", 5000));
    }

    public void testTimeoutFailure() throws Exception {
        mServer.shouldRespond(false);
        assertFalse(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(0, mServer.numRepliesSent());
    }

    private static MessageDigest md = null;

    private static byte[] signedResponse(byte[] nonce) {
        RoughtimeClient.Message signed = new RoughtimeClient.Message();

        try {
            if (md == null) {
                md = MessageDigest.getInstance("SHA-512");
            }
        } catch(Exception e) {
            return null;
        }

        md.update(new byte[]{0});
        byte[] hash = md.digest(nonce);
        signed.put(RoughtimeClient.Tag.ROOT, hash);
        signed.putLong(RoughtimeClient.Tag.MIDP, TEST_TIME);
        signed.putInt(RoughtimeClient.Tag.RADI, TEST_RADIUS);

        return signed.serialize();
    }

    private static byte[] response(byte[] nonce) {
        RoughtimeClient.Message msg = new RoughtimeClient.Message();

        msg.put(RoughtimeClient.Tag.SREP, signedResponse(nonce));
        msg.putInt(RoughtimeClient.Tag.INDX, 0);
        msg.put(RoughtimeClient.Tag.PATH, new byte[0]);

        return msg.serialize();
    }

    private static class RoughtimeTestServer {
        private final Object mLock = new Object();
        private final DatagramSocket mSocket;
        private final InetAddress mAddress;
        private final int mPort;
        private int mRcvd;
        private int mSent;
        private Thread mListeningThread;
        private boolean mShouldRespond = true;

        public RoughtimeTestServer() {
            mSocket = makeSocket();
            mAddress = mSocket.getLocalAddress();
            mPort = mSocket.getLocalPort();
            Log.d(TAG, "testing server listening on (" + mAddress + ", " + mPort + ")");

            mListeningThread = new Thread() {
                public void run() {
                    while (true) {
                        byte[] buffer = new byte[2048];
                        DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                        try {
                            mSocket.receive(request);
                        } catch (IOException e) {
                            Log.e(TAG, "datagram receive error: " + e);
                            break;
                        }
                        synchronized (mLock) {
                            mRcvd++;

                            if (! mShouldRespond) {
                                continue;
                            }

                            RoughtimeClient.Message msg =
                                RoughtimeClient.Message.deserialize(
                                    Arrays.copyOf(buffer, request.getLength()));

                            byte[] nonce = msg.get(RoughtimeClient.Tag.NONC);
                            if (nonce.length != 64) {
                                Log.e(TAG, "Nonce is wrong length.");
                            }

                            try {
                                request.setData(response(nonce));
                                mSocket.send(request);
                            } catch (IOException e) {
                                Log.e(TAG, "datagram send error: " + e);
                                break;
                            }
                            mSent++;
                        }
                    }
                    mSocket.close();
                }
            };
            mListeningThread.start();
        }

        private DatagramSocket makeSocket() {
            DatagramSocket socket;
            try {
                socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            } catch (SocketException e) {
                Log.e(TAG, "Failed to create test server socket: " + e);
                return null;
            }
            return socket;
        }

        public void shouldRespond(boolean value) { mShouldRespond = value; }

        public InetAddress getAddress() { return mAddress; }
        public int getPort() { return mPort; }
        public int numRequestsReceived() { synchronized (mLock) { return mRcvd; } }
        public int numRepliesSent() { synchronized (mLock) { return mSent; } }
    }
}
