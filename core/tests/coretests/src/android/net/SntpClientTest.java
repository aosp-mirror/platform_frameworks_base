/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class SntpClientTest {
    private static final String TAG = "SntpClientTest";

    private static final int ORIGINATE_TIME_OFFSET = 24;
    private static final int TRANSMIT_TIME_OFFSET = 40;

    private static final int NTP_MODE_SERVER = 4;
    private static final int NTP_MODE_BROADCAST = 5;

    // From tcpdump (admittedly, an NTPv4 packet):
    //
    // Server, Leap indicator:  (0), Stratum 2 (secondary reference), poll 6 (64s), precision -20
    // Root Delay: 0.005447, Root dispersion: 0.002716, Reference-ID: 221.253.71.41
    //   Reference Timestamp:  3653932102.507969856 (2015/10/15 14:08:22)
    //   Originator Timestamp: 3653932113.576327741 (2015/10/15 14:08:33)
    //   Receive Timestamp:    3653932113.581012725 (2015/10/15 14:08:33)
    //   Transmit Timestamp:   3653932113.581012725 (2015/10/15 14:08:33)
    //     Originator - Receive Timestamp:  +0.004684958
    //     Originator - Transmit Timestamp: +0.004684958
    private static final String WORKING_VERSION4 =
            "240206ec" +
            "00000165" +
            "000000b2" +
            "ddfd4729" +
            "d9ca9446820a5000" +
            "d9ca9451938a3771" +
            "d9ca945194bd3fff" +
            "d9ca945194bd4001";

    private SntpTestServer mServer;
    private SntpClient mClient;
    private Network mNetwork;

    @Before
    public void setUp() throws Exception {
        // A mock network has NETID_UNSET, which allows the test to run, with a loopback server,
        // even w/o external networking.
        mNetwork = mock(Network.class, CALLS_REAL_METHODS);
        mServer = new SntpTestServer();
        mClient = new SntpClient();
    }

    @Test
    public void testBasicWorkingSntpClientQuery() throws Exception {
        mServer.setServerReply(HexEncoding.decode(WORKING_VERSION4.toCharArray(), false));
        assertTrue(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(1, mServer.numRepliesSent());
    }

    @Test
    public void testDnsResolutionFailure() throws Exception {
        assertFalse(mClient.requestTime("ntp.server.doesnotexist.example", 5000, mNetwork));
    }

    @Test
    public void testTimeoutFailure() throws Exception {
        mServer.clearServerReply();
        assertFalse(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(0, mServer.numRepliesSent());
    }

    @Test
    public void testIgnoreLeapNoSync() throws Exception {
        final byte[] reply = HexEncoding.decode(WORKING_VERSION4.toCharArray(), false);
        reply[0] |= (byte) 0xc0;
        mServer.setServerReply(reply);
        assertFalse(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(1, mServer.numRepliesSent());
    }

    @Test
    public void testAcceptOnlyServerAndBroadcastModes() throws Exception {
        final byte[] reply = HexEncoding.decode(WORKING_VERSION4.toCharArray(), false);
        for (int i = 0; i <= 7; i++) {
            final String logMsg = "mode: " + i;
            reply[0] &= (byte) 0xf8;
            reply[0] |= (byte) i;
            mServer.setServerReply(reply);
            final boolean rval = mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500,
                    mNetwork);
            switch (i) {
                case NTP_MODE_SERVER:
                case NTP_MODE_BROADCAST:
                    assertTrue(logMsg, rval);
                    break;
                default:
                    assertFalse(logMsg, rval);
                    break;
            }
            assertEquals(logMsg, 1, mServer.numRequestsReceived());
            assertEquals(logMsg, 1, mServer.numRepliesSent());
        }
    }

    @Test
    public void testAcceptableStrataOnly() throws Exception {
        final int STRATUM_MIN = 1;
        final int STRATUM_MAX = 15;

        final byte[] reply = HexEncoding.decode(WORKING_VERSION4.toCharArray(), false);
        for (int i = 0; i < 256; i++) {
            final String logMsg = "stratum: " + i;
            reply[1] = (byte) i;
            mServer.setServerReply(reply);
            final boolean rval = mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500,
                    mNetwork);
            if (STRATUM_MIN <= i && i <= STRATUM_MAX) {
                assertTrue(logMsg, rval);
            } else {
                assertFalse(logMsg, rval);
            }
            assertEquals(logMsg, 1, mServer.numRequestsReceived());
            assertEquals(logMsg, 1, mServer.numRepliesSent());
        }
    }

    @Test
    public void testZeroTransmitTime() throws Exception {
        final byte[] reply = HexEncoding.decode(WORKING_VERSION4.toCharArray(), false);
        Arrays.fill(reply, TRANSMIT_TIME_OFFSET, TRANSMIT_TIME_OFFSET + 8, (byte) 0x00);
        mServer.setServerReply(reply);
        assertFalse(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(1, mServer.numRepliesSent());
    }


    private static class SntpTestServer {
        private final Object mLock = new Object();
        private final DatagramSocket mSocket;
        private final InetAddress mAddress;
        private final int mPort;
        private byte[] mReply;
        private int mRcvd;
        private int mSent;
        private Thread mListeningThread;

        public SntpTestServer() {
            mSocket = makeSocket();
            mAddress = mSocket.getLocalAddress();
            mPort = mSocket.getLocalPort();
            Log.d(TAG, "testing server listening on (" + mAddress + ", " + mPort + ")");

            mListeningThread = new Thread() {
                public void run() {
                    while (true) {
                        byte[] buffer = new byte[512];
                        DatagramPacket ntpMsg = new DatagramPacket(buffer, buffer.length);
                        try {
                            mSocket.receive(ntpMsg);
                        } catch (IOException e) {
                            Log.e(TAG, "datagram receive error: " + e);
                            break;
                        }
                        synchronized (mLock) {
                            mRcvd++;
                            if (mReply == null) { continue; }
                            // Copy transmit timestamp into originate timestamp.
                            // TODO: bounds checking.
                            System.arraycopy(ntpMsg.getData(), TRANSMIT_TIME_OFFSET,
                                             mReply, ORIGINATE_TIME_OFFSET, 8);
                            ntpMsg.setData(mReply);
                            ntpMsg.setLength(mReply.length);
                            try {
                                mSocket.send(ntpMsg);
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

        public void clearServerReply() {
            setServerReply(null);
        }

        public void setServerReply(byte[] reply) {
            synchronized (mLock) {
                mReply = reply;
                mRcvd = 0;
                mSent = 0;
            }
        }

        public InetAddress getAddress() { return mAddress; }
        public int getPort() { return mPort; }
        public int numRequestsReceived() { synchronized (mLock) { return mRcvd; } }
        public int numRepliesSent() { synchronized (mLock) { return mSent; } }
    }
}
