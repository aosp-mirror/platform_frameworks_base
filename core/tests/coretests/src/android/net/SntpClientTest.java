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
import static org.mockito.Mockito.when;

import android.net.sntp.Duration64;
import android.net.sntp.Timestamp64;
import android.platform.test.annotations.Presubmit;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Supplier;

@Presubmit
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
    //   Reference Timestamp:
    //     d9ca9446.820a5000 / ERA0: 2015-10-15 21:08:22 UTC / ERA1: 2151-11-22 03:36:38 UTC
    //   Originator Timestamp:
    //     d9ca9451.938a3771 / ERA0: 2015-10-15 21:08:33 UTC / ERA1: 2151-11-22 03:36:49 UTC
    //   Receive Timestamp:
    //     d9ca9451.94bd3fff / ERA0: 2015-10-15 21:08:33 UTC / ERA1: 2151-11-22 03:36:49 UTC
    //   Transmit Timestamp:
    //     d9ca9451.94bd4001 / ERA0: 2015-10-15 21:08:33 UTC / ERA1: 2151-11-22 03:36:49 UTC
    //
    //     Originator - Receive Timestamp:  +0.004684958
    //     Originator - Transmit Timestamp: +0.004684958
    private static final String LATE_ERA_RESPONSE =
            "240206ec"
                    + "00000165"
                    + "000000b2"
                    + "ddfd4729"
                    + "d9ca9446820a5000"
                    + "d9ca9451938a3771"
                    + "d9ca945194bd3fff"
                    + "d9ca945194bd4001";

    /** This is the actual UTC time in the server if it is in ERA0 */
    private static final Instant LATE_ERA0_SERVER_TIME =
            calculateIdealServerTime("d9ca9451.94bd3fff", "d9ca9451.94bd4001", 0);

    /**
     * This is the Unix epoch time matches the originate timestamp from {@link #LATE_ERA_RESPONSE}
     * when interpreted as an ERA0 timestamp.
     */
    private static final Instant LATE_ERA0_REQUEST_TIME =
            Timestamp64.fromString("d9ca9451.938a3771").toInstant(0);

    // A tweaked version of the ERA0 response to represent an ERA 1 response.
    //
    // Server, Leap indicator:  (0), Stratum 2 (secondary reference), poll 6 (64s), precision -20
    // Root Delay: 0.005447, Root dispersion: 0.002716, Reference-ID: 221.253.71.41
    //   Reference Timestamp:
    //     1db2d246.820a5000 / ERA0: 1915-10-16 21:08:22 UTC / ERA1: 2051-11-22 03:36:38 UTC
    //   Originate Timestamp:
    //     1db2d251.938a3771 / ERA0: 1915-10-16 21:08:33 UTC / ERA1: 2051-11-22 03:36:49 UTC
    //   Receive Timestamp:
    //     1db2d251.94bd3fff / ERA0: 1915-10-16 21:08:33 UTC / ERA1: 2051-11-22 03:36:49 UTC
    //   Transmit Timestamp:
    //     1db2d251.94bd4001 / ERA0: 1915-10-16 21:08:33 UTC / ERA1: 2051-11-22 03:36:49 UTC
    //
    //     Originate - Receive Timestamp:  +0.004684958
    //     Originate - Transmit Timestamp: +0.004684958
    private static final String EARLY_ERA_RESPONSE =
            "240206ec"
                    + "00000165"
                    + "000000b2"
                    + "ddfd4729"
                    + "1db2d246820a5000"
                    + "1db2d251938a3771"
                    + "1db2d25194bd3fff"
                    + "1db2d25194bd4001";

    /** This is the actual UTC time in the server if it is in ERA0 */
    private static final Instant EARLY_ERA1_SERVER_TIME =
            calculateIdealServerTime("1db2d251.94bd3fff", "1db2d251.94bd4001", 1);

    /**
     * This is the Unix epoch time matches the originate timestamp from {@link #EARLY_ERA_RESPONSE}
     * when interpreted as an ERA1 timestamp.
     */
    private static final Instant EARLY_ERA1_REQUEST_TIME =
            Timestamp64.fromString("1db2d251.938a3771").toInstant(1);

    private SntpTestServer mServer;
    private SntpClient mClient;
    private Network mNetwork;
    private Supplier<Instant> mSystemTimeSupplier;
    private Random mRandom;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() throws Exception {
        mServer = new SntpTestServer();

        // A mock network has NETID_UNSET, which allows the test to run, with a loopback server,
        // even w/o external networking.
        mNetwork = mock(Network.class, CALLS_REAL_METHODS);
        mRandom = mock(Random.class);

        mSystemTimeSupplier = mock(Supplier.class);
        // Returning zero means the "randomized" bottom bits of the clients transmit timestamp /
        // server's originate timestamp will be zeros.
        when(mRandom.nextInt()).thenReturn(0);
        mClient = new SntpClient(mSystemTimeSupplier, mRandom);
    }

    /** Tests when the client and server are in ERA0. b/199481251. */
    @Test
    public void testRequestTime_era0ClientEra0RServer() throws Exception {
        when(mSystemTimeSupplier.get()).thenReturn(LATE_ERA0_REQUEST_TIME);

        mServer.setServerReply(HexEncoding.decode(LATE_ERA_RESPONSE.toCharArray(), false));
        assertTrue(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(1, mServer.numRepliesSent());

        checkRequestTimeCalcs(LATE_ERA0_REQUEST_TIME, LATE_ERA0_SERVER_TIME, mClient);
    }

    /** Tests when the client is behind the server and in the previous ERA. b/199481251. */
    @Test
    public void testRequestTime_era0ClientEra1Server() throws Exception {
        when(mSystemTimeSupplier.get()).thenReturn(LATE_ERA0_REQUEST_TIME);

        mServer.setServerReply(HexEncoding.decode(EARLY_ERA_RESPONSE.toCharArray(), false));
        assertTrue(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(1, mServer.numRepliesSent());

        checkRequestTimeCalcs(LATE_ERA0_REQUEST_TIME, EARLY_ERA1_SERVER_TIME, mClient);

    }

    /** Tests when the client is ahead of the server and in the next ERA. b/199481251. */
    @Test
    public void testRequestTime_era1ClientEra0Server() throws Exception {
        when(mSystemTimeSupplier.get()).thenReturn(EARLY_ERA1_REQUEST_TIME);

        mServer.setServerReply(HexEncoding.decode(LATE_ERA_RESPONSE.toCharArray(), false));
        assertTrue(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(1, mServer.numRepliesSent());

        checkRequestTimeCalcs(EARLY_ERA1_REQUEST_TIME, LATE_ERA0_SERVER_TIME, mClient);
    }

    /** Tests when the client and server are in ERA1. b/199481251. */
    @Test
    public void testRequestTime_era1ClientEra1Server() throws Exception {
        when(mSystemTimeSupplier.get()).thenReturn(EARLY_ERA1_REQUEST_TIME);

        mServer.setServerReply(HexEncoding.decode(EARLY_ERA_RESPONSE.toCharArray(), false));
        assertTrue(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(1, mServer.numRepliesSent());

        checkRequestTimeCalcs(EARLY_ERA1_REQUEST_TIME, EARLY_ERA1_SERVER_TIME, mClient);
    }

    private static void checkRequestTimeCalcs(
            Instant clientTime, Instant serverTime, SntpClient client) {
        // The tests don't attempt to control the elapsed time tracking, which influences the
        // round trip time (i.e. time spent in due to the network), but they control everything
        // else, so assertions are allowed some slop and round trip time just has to be >= 0.
        assertTrue("getRoundTripTime()=" + client.getRoundTripTime(),
                client.getRoundTripTime() >= 0);

        // Calculate the ideal offset if nothing took any time.
        long expectedOffset = serverTime.toEpochMilli() - clientTime.toEpochMilli();
        long allowedSlop = (client.getRoundTripTime() / 2) + 1; // +1 to allow for truncation loss.
        assertNearlyEquals(expectedOffset, client.getClockOffset(), allowedSlop);
        assertNearlyEquals(clientTime.toEpochMilli() + expectedOffset,
                client.getNtpTime(), allowedSlop);
    }

    /**
     * Unit tests for the low-level offset calculations. More targeted / easier to write than the
     * end-to-end tests above that simulate the server. b/199481251.
     */
    @Test
    public void testCalculateClockOffset() {
        Instant era0Time1 = utcInstant(2021, 10, 5, 2, 2, 2, 2);
        // Confirm what happens when the client and server are completely in sync.
        checkCalculateClockOffset(era0Time1, era0Time1);

        Instant era0Time2 = utcInstant(2021, 10, 6, 1, 1, 1, 1);
        checkCalculateClockOffset(era0Time1, era0Time2);
        checkCalculateClockOffset(era0Time2, era0Time1);

        Instant era1Time1 = utcInstant(2061, 10, 5, 2, 2, 2, 2);
        checkCalculateClockOffset(era1Time1, era1Time1);

        Instant era1Time2 = utcInstant(2061, 10, 6, 1, 1, 1, 1);
        checkCalculateClockOffset(era1Time1, era1Time2);
        checkCalculateClockOffset(era1Time2, era1Time1);

        // Cross-era calcs (requires they are still within 68 years of each other).
        checkCalculateClockOffset(era0Time1, era1Time1);
        checkCalculateClockOffset(era1Time1, era0Time1);
    }

    private void checkCalculateClockOffset(Instant clientTime, Instant serverTime) {
        // The expected (ideal) offset is the difference between the client and server clocks. NTP
        // assumes delays are symmetric, i.e. that the server time is between server
        // receive/transmit time, client time is between request/response time, and send networking
        // delay == receive networking delay.
        Duration expectedOffset = Duration.between(clientTime, serverTime);

        // Try simulating various round trip delays, including zero.
        for (long totalElapsedTimeMillis : Arrays.asList(0, 20, 200, 2000, 20000)) {
            // Simulate that a 10% of the elapsed time is due to time spent in the server, the rest
            // is network / client processing time.
            long simulatedServerElapsedTimeMillis = totalElapsedTimeMillis / 10;
            long simulatedClientElapsedTimeMillis = totalElapsedTimeMillis;

            // Create some symmetrical timestamps.
            Timestamp64 clientRequestTimestamp = Timestamp64.fromInstant(
                    clientTime.minusMillis(simulatedClientElapsedTimeMillis / 2));
            Timestamp64 clientResponseTimestamp = Timestamp64.fromInstant(
                    clientTime.plusMillis(simulatedClientElapsedTimeMillis / 2));
            Timestamp64 serverReceiveTimestamp = Timestamp64.fromInstant(
                    serverTime.minusMillis(simulatedServerElapsedTimeMillis / 2));
            Timestamp64 serverTransmitTimestamp = Timestamp64.fromInstant(
                    serverTime.plusMillis(simulatedServerElapsedTimeMillis / 2));

            Duration actualOffset = SntpClient.calculateClockOffset(
                    clientRequestTimestamp, serverReceiveTimestamp,
                    serverTransmitTimestamp, clientResponseTimestamp);

            // We allow up to 1ms variation because NTP types are lossy and the simulated elapsed
            // time millis may not divide exactly.
            int allowedSlopMillis = 1;
            assertNearlyEquals(
                    expectedOffset.toMillis(), actualOffset.toMillis(), allowedSlopMillis);
        }
    }

    private static Instant utcInstant(
            int year, int monthOfYear, int day, int hour, int minute, int second, int nanos) {
        return LocalDateTime.of(year, monthOfYear, day, hour, minute, second, nanos)
                .toInstant(ZoneOffset.UTC);
    }

    @Test
    public void testDnsResolutionFailure() throws Exception {
        assertFalse(mClient.requestTime("ntp.server.doesnotexist.example",
                SntpClient.STANDARD_NTP_PORT, 5000, mNetwork));
    }

    @Test
    public void testTimeoutFailure() throws Exception {
        when(mSystemTimeSupplier.get()).thenReturn(LATE_ERA0_REQUEST_TIME);

        mServer.clearServerReply();
        assertFalse(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(0, mServer.numRepliesSent());
    }

    @Test
    public void testIgnoreLeapNoSync() throws Exception {
        when(mSystemTimeSupplier.get()).thenReturn(LATE_ERA0_REQUEST_TIME);

        final byte[] reply = HexEncoding.decode(LATE_ERA_RESPONSE.toCharArray(), false);
        reply[0] |= (byte) 0xc0;
        mServer.setServerReply(reply);
        assertFalse(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(1, mServer.numRepliesSent());
    }

    @Test
    public void testAcceptOnlyServerAndBroadcastModes() throws Exception {
        when(mSystemTimeSupplier.get()).thenReturn(LATE_ERA0_REQUEST_TIME);

        final byte[] reply = HexEncoding.decode(LATE_ERA_RESPONSE.toCharArray(), false);
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
        when(mSystemTimeSupplier.get()).thenReturn(LATE_ERA0_REQUEST_TIME);

        final int STRATUM_MIN = 1;
        final int STRATUM_MAX = 15;

        final byte[] reply = HexEncoding.decode(LATE_ERA_RESPONSE.toCharArray(), false);
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
        when(mSystemTimeSupplier.get()).thenReturn(LATE_ERA0_REQUEST_TIME);

        final byte[] reply = HexEncoding.decode(LATE_ERA_RESPONSE.toCharArray(), false);
        Arrays.fill(reply, TRANSMIT_TIME_OFFSET, TRANSMIT_TIME_OFFSET + 8, (byte) 0x00);
        mServer.setServerReply(reply);
        assertFalse(mClient.requestTime(mServer.getAddress(), mServer.getPort(), 500, mNetwork));
        assertEquals(1, mServer.numRequestsReceived());
        assertEquals(1, mServer.numRepliesSent());
    }

    @Test
    public void testNonMatchingOriginateTime() throws Exception {
        when(mSystemTimeSupplier.get()).thenReturn(LATE_ERA0_REQUEST_TIME);

        final byte[] reply = HexEncoding.decode(LATE_ERA_RESPONSE.toCharArray(), false);
        mServer.setServerReply(reply);
        mServer.setGenerateValidOriginateTimestamp(false);

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
        private boolean mGenerateValidOriginateTimestamp = true;
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
                            if (mGenerateValidOriginateTimestamp) {
                                // Copy the transmit timestamp into originate timestamp: This is
                                // validated by well-behaved clients.
                                System.arraycopy(ntpMsg.getData(), TRANSMIT_TIME_OFFSET,
                                        mReply, ORIGINATE_TIME_OFFSET, 8);
                            } else {
                                // Fill it with junk instead.
                                Arrays.fill(mReply, ORIGINATE_TIME_OFFSET,
                                        ORIGINATE_TIME_OFFSET + 8, (byte) 0xFF);
                            }
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

        /**
         * Controls the test server's behavior of copying the client's transmit timestamp into the
         * response's originate timestamp (which is required of a real server).
         */
        public void setGenerateValidOriginateTimestamp(boolean enabled) {
            synchronized (mLock) {
                mGenerateValidOriginateTimestamp = enabled;
            }
        }

        public InetAddress getAddress() { return mAddress; }
        public int getPort() { return mPort; }
        public int numRequestsReceived() { synchronized (mLock) { return mRcvd; } }
        public int numRepliesSent() { synchronized (mLock) { return mSent; } }
    }

    /**
     * Generates the "real" server time assuming it is exactly between the receive and transmit
     * timestamp and in the NTP era specified.
     */
    private static Instant calculateIdealServerTime(String receiveTimestampString,
            String transmitTimestampString, int era) {
        Timestamp64 receiveTimestamp = Timestamp64.fromString(receiveTimestampString);
        Timestamp64 transmitTimestamp = Timestamp64.fromString(transmitTimestampString);
        Duration serverProcessingTime =
                Duration64.between(receiveTimestamp, transmitTimestamp).toDuration();
        return receiveTimestamp.toInstant(era)
                .plusMillis(serverProcessingTime.dividedBy(2).toMillis());
    }

    private static void assertNearlyEquals(long expected, long actual, long allowedSlop) {
        assertTrue("expected=" + expected + ", actual=" + actual + ", allowedSlop=" + allowedSlop,
                actual >= expected - allowedSlop && actual <= expected + allowedSlop);
    }
}
