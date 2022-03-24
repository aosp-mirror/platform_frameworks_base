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

package android.net;

import android.compat.annotation.UnsupportedAppUsage;
import android.net.sntp.Duration64;
import android.net.sntp.Timestamp64;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.TrafficStatsConstants;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * {@hide}
 *
 * Simple SNTP client class for retrieving network time.
 *
 * Sample usage:
 * <pre>SntpClient client = new SntpClient();
 * if (client.requestTime("time.foo.com")) {
 *     long now = client.getNtpTime() + SystemClock.elapsedRealtime() - client.getNtpTimeReference();
 * }
 * </pre>
 */
public class SntpClient {
    private static final String TAG = "SntpClient";
    private static final boolean DBG = true;

    private static final int REFERENCE_TIME_OFFSET = 16;
    private static final int ORIGINATE_TIME_OFFSET = 24;
    private static final int RECEIVE_TIME_OFFSET = 32;
    private static final int TRANSMIT_TIME_OFFSET = 40;
    private static final int NTP_PACKET_SIZE = 48;

    public static final int STANDARD_NTP_PORT = 123;
    private static final int NTP_MODE_CLIENT = 3;
    private static final int NTP_MODE_SERVER = 4;
    private static final int NTP_MODE_BROADCAST = 5;
    private static final int NTP_VERSION = 3;

    private static final int NTP_LEAP_NOSYNC = 3;
    private static final int NTP_STRATUM_DEATH = 0;
    private static final int NTP_STRATUM_MAX = 15;

    // The source of the current system clock time, replaceable for testing.
    private final Supplier<Instant> mSystemTimeSupplier;

    private final Random mRandom;

    // The last offset calculated from an NTP server response
    private long mClockOffset;

    // The last system time computed from an NTP server response
    private long mNtpTime;

    // The value of SystemClock.elapsedRealtime() corresponding to mNtpTime / mClockOffset
    private long mNtpTimeReference;

    // The round trip (network) time in milliseconds
    private long mRoundTripTime;

    private static class InvalidServerReplyException extends Exception {
        public InvalidServerReplyException(String message) {
            super(message);
        }
    }

    @UnsupportedAppUsage
    public SntpClient() {
        this(Instant::now, defaultRandom());
    }

    @VisibleForTesting
    public SntpClient(Supplier<Instant> systemTimeSupplier, Random random) {
        mSystemTimeSupplier = Objects.requireNonNull(systemTimeSupplier);
        mRandom = Objects.requireNonNull(random);
    }

    /**
     * Sends an SNTP request to the given host and processes the response.
     *
     * @param host host name of the server.
     * @param port port of the server.
     * @param timeout network timeout in milliseconds. the timeout doesn't include the DNS lookup
     *                time, and it applies to each individual query to the resolved addresses of
     *                the NTP server.
     * @param network network over which to send the request.
     * @return true if the transaction was successful.
     */
    public boolean requestTime(String host, int port, int timeout, Network network) {
        final Network networkForResolv = network.getPrivateDnsBypassingCopy();
        try {
            final InetAddress[] addresses = networkForResolv.getAllByName(host);
            for (int i = 0; i < addresses.length; i++) {
                if (requestTime(addresses[i], port, timeout, networkForResolv)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            Log.w(TAG, "Unknown host: " + host);
            EventLogTags.writeNtpFailure(host, e.toString());
        }

        if (DBG) Log.d(TAG, "request time failed");
        return false;
    }

    public boolean requestTime(InetAddress address, int port, int timeout, Network network) {
        DatagramSocket socket = null;
        final int oldTag = TrafficStats.getAndSetThreadStatsTag(
                TrafficStatsConstants.TAG_SYSTEM_NTP);
        try {
            socket = new DatagramSocket();
            network.bindSocket(socket);
            socket.setSoTimeout(timeout);
            byte[] buffer = new byte[NTP_PACKET_SIZE];
            DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, port);

            // set mode = 3 (client) and version = 3
            // mode is in low 3 bits of first byte
            // version is in bits 3-5 of first byte
            buffer[0] = NTP_MODE_CLIENT | (NTP_VERSION << 3);

            // get current time and write it to the request packet
            final Instant requestTime = mSystemTimeSupplier.get();
            final Timestamp64 requestTimestamp = Timestamp64.fromInstant(requestTime);

            final Timestamp64 randomizedRequestTimestamp =
                    requestTimestamp.randomizeSubMillis(mRandom);
            final long requestTicks = SystemClock.elapsedRealtime();
            writeTimeStamp(buffer, TRANSMIT_TIME_OFFSET, randomizedRequestTimestamp);

            socket.send(request);

            // read the response
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            final long responseTicks = SystemClock.elapsedRealtime();
            final Instant responseTime = requestTime.plusMillis(responseTicks - requestTicks);
            final Timestamp64 responseTimestamp = Timestamp64.fromInstant(responseTime);

            // extract the results
            final byte leap = (byte) ((buffer[0] >> 6) & 0x3);
            final byte mode = (byte) (buffer[0] & 0x7);
            final int stratum = (int) (buffer[1] & 0xff);
            final Timestamp64 referenceTimestamp = readTimeStamp(buffer, REFERENCE_TIME_OFFSET);
            final Timestamp64 originateTimestamp = readTimeStamp(buffer, ORIGINATE_TIME_OFFSET);
            final Timestamp64 receiveTimestamp = readTimeStamp(buffer, RECEIVE_TIME_OFFSET);
            final Timestamp64 transmitTimestamp = readTimeStamp(buffer, TRANSMIT_TIME_OFFSET);

            /* Do validation according to RFC */
            checkValidServerReply(leap, mode, stratum, transmitTimestamp, referenceTimestamp,
                    randomizedRequestTimestamp, originateTimestamp);

            long totalTransactionDurationMillis = responseTicks - requestTicks;
            long serverDurationMillis =
                    Duration64.between(receiveTimestamp, transmitTimestamp).toDuration().toMillis();
            long roundTripTimeMillis = totalTransactionDurationMillis - serverDurationMillis;

            Duration clockOffsetDuration = calculateClockOffset(requestTimestamp,
                    receiveTimestamp, transmitTimestamp, responseTimestamp);
            long clockOffsetMillis = clockOffsetDuration.toMillis();

            EventLogTags.writeNtpSuccess(
                    address.toString(), roundTripTimeMillis, clockOffsetMillis);
            if (DBG) {
                Log.d(TAG, "round trip: " + roundTripTimeMillis + "ms, "
                        + "clock offset: " + clockOffsetMillis + "ms");
            }

            // save our results - use the times on this side of the network latency
            // (response rather than request time)
            mClockOffset = clockOffsetMillis;
            mNtpTime = responseTime.plus(clockOffsetDuration).toEpochMilli();
            mNtpTimeReference = responseTicks;
            mRoundTripTime = roundTripTimeMillis;
        } catch (Exception e) {
            EventLogTags.writeNtpFailure(address.toString(), e.toString());
            if (DBG) Log.d(TAG, "request time failed: " + e);
            return false;
        } finally {
            if (socket != null) {
                socket.close();
            }
            TrafficStats.setThreadStatsTag(oldTag);
        }

        return true;
    }

    /** Performs the NTP clock offset calculation. */
    @VisibleForTesting
    public static Duration calculateClockOffset(Timestamp64 clientRequestTimestamp,
            Timestamp64 serverReceiveTimestamp, Timestamp64 serverTransmitTimestamp,
            Timestamp64 clientResponseTimestamp) {
        // According to RFC4330:
        // t is the system clock offset (the adjustment we are trying to find)
        // t = ((T2 - T1) + (T3 - T4)) / 2
        //
        // Which is:
        // t = (([server]receiveTimestamp - [client]requestTimestamp)
        //       + ([server]transmitTimestamp - [client]responseTimestamp)) / 2
        //
        // See the NTP spec and tests: the numeric types used are deliberate:
        // + Duration64.between() uses 64-bit arithmetic (32-bit for the seconds).
        // + plus() / dividedBy() use Duration, which isn't the double precision floating point
        //   used in NTPv4, but is good enough.
        return Duration64.between(clientRequestTimestamp, serverReceiveTimestamp)
                .plus(Duration64.between(clientResponseTimestamp, serverTransmitTimestamp))
                .dividedBy(2);
    }

    @Deprecated
    @UnsupportedAppUsage
    public boolean requestTime(String host, int timeout) {
        Log.w(TAG, "Shame on you for calling the hidden API requestTime()!");
        return false;
    }

    /**
     * Returns the offset calculated to apply to the client clock to arrive at {@link #getNtpTime()}
     */
    @VisibleForTesting
    public long getClockOffset() {
        return mClockOffset;
    }

    /**
     * Returns the time computed from the NTP transaction.
     *
     * @return time value computed from NTP server response.
     */
    @UnsupportedAppUsage
    public long getNtpTime() {
        return mNtpTime;
    }

    /**
     * Returns the reference clock value (value of SystemClock.elapsedRealtime())
     * corresponding to the NTP time.
     *
     * @return reference clock corresponding to the NTP time.
     */
    @UnsupportedAppUsage
    public long getNtpTimeReference() {
        return mNtpTimeReference;
    }

    /**
     * Returns the round trip time of the NTP transaction
     *
     * @return round trip time in milliseconds.
     */
    @UnsupportedAppUsage
    public long getRoundTripTime() {
        return mRoundTripTime;
    }

    private static void checkValidServerReply(
            byte leap, byte mode, int stratum, Timestamp64 transmitTimestamp,
            Timestamp64 referenceTimestamp, Timestamp64 randomizedRequestTimestamp,
            Timestamp64 originateTimestamp) throws InvalidServerReplyException {
        if (leap == NTP_LEAP_NOSYNC) {
            throw new InvalidServerReplyException("unsynchronized server");
        }
        if ((mode != NTP_MODE_SERVER) && (mode != NTP_MODE_BROADCAST)) {
            throw new InvalidServerReplyException("untrusted mode: " + mode);
        }
        if ((stratum == NTP_STRATUM_DEATH) || (stratum > NTP_STRATUM_MAX)) {
            throw new InvalidServerReplyException("untrusted stratum: " + stratum);
        }
        if (!randomizedRequestTimestamp.equals(originateTimestamp)) {
            throw new InvalidServerReplyException(
                    "originateTimestamp != randomizedRequestTimestamp");
        }
        if (transmitTimestamp.equals(Timestamp64.ZERO)) {
            throw new InvalidServerReplyException("zero transmitTimestamp");
        }
        if (referenceTimestamp.equals(Timestamp64.ZERO)) {
            throw new InvalidServerReplyException("zero referenceTimestamp");
        }
    }

    /**
     * Reads an unsigned 32 bit big endian number from the given offset in the buffer.
     */
    private long readUnsigned32(byte[] buffer, int offset) {
        int i0 = buffer[offset++] & 0xFF;
        int i1 = buffer[offset++] & 0xFF;
        int i2 = buffer[offset++] & 0xFF;
        int i3 = buffer[offset] & 0xFF;

        int bits = (i0 << 24) | (i1 << 16) | (i2 << 8) | i3;
        return bits & 0xFFFF_FFFFL;
    }

    /**
     * Reads the NTP time stamp from the given offset in the buffer.
     */
    private Timestamp64 readTimeStamp(byte[] buffer, int offset) {
        long seconds = readUnsigned32(buffer, offset);
        int fractionBits = (int) readUnsigned32(buffer, offset + 4);
        return Timestamp64.fromComponents(seconds, fractionBits);
    }

    /**
     * Writes the NTP time stamp at the given offset in the buffer.
     */
    private void writeTimeStamp(byte[] buffer, int offset, Timestamp64 timestamp) {
        long seconds = timestamp.getEraSeconds();
        // write seconds in big endian format
        buffer[offset++] = (byte) (seconds >>> 24);
        buffer[offset++] = (byte) (seconds >>> 16);
        buffer[offset++] = (byte) (seconds >>> 8);
        buffer[offset++] = (byte) (seconds);

        int fractionBits = timestamp.getFractionBits();
        // write fraction in big endian format
        buffer[offset++] = (byte) (fractionBits >>> 24);
        buffer[offset++] = (byte) (fractionBits >>> 16);
        buffer[offset++] = (byte) (fractionBits >>> 8);
        buffer[offset] = (byte) (fractionBits);
    }

    private static Random defaultRandom() {
        Random random;
        try {
            random = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            // This should never happen.
            Slog.wtf(TAG, "Unable to access SecureRandom", e);
            random = new Random(System.currentTimeMillis());
        }
        return random;
    }
}
