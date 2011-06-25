/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server;

import android.content.ContentResolver;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.os.SystemClock;
import android.util.Slog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.Random;

/**
 * Performs a simple DNS "ping" by sending a "server status" query packet to the
 * DNS server. As long as the server replies, we consider it a success.
 * <p>
 * We do not use a simple hostname lookup because that could be cached and the
 * API may not differentiate between a time out and a failure lookup (which we
 * really care about).
 * <p>
 * TODO : More general API.  Wifi is currently hard coded
 * TODO : Choice of DNS query location - current looks up www.android.com
 *
 * @hide
 */
public final class DnsPinger {
    private static final boolean V = true;

    /** Number of bytes for the query */
    private static final int DNS_QUERY_BASE_SIZE = 33;

    /** The DNS port */
    private static final int DNS_PORT = 53;

    /** Used to generate IDs */
    private static Random sRandom = new Random();

    private ConnectivityManager mConnectivityManager = null;
    private ContentResolver mContentResolver;
    private Context mContext;

    private String TAG;

    public DnsPinger(String TAG, Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        this.TAG = TAG;
    }

    /**
     * Gets the first DNS of the current Wifi AP.
     * @return The first DNS of the current AP.
     */
    public InetAddress getDns() {
        if (mConnectivityManager == null) {
            mConnectivityManager = (ConnectivityManager) mContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
        }

        LinkProperties linkProperties = mConnectivityManager.getLinkProperties(
                ConnectivityManager.TYPE_WIFI);
        if (linkProperties == null)
            return null;

        Collection<InetAddress> dnses = linkProperties.getDnses();
        if (dnses == null || dnses.size() == 0)
            return null;

        return dnses.iterator().next();
    }

    /**
     * @return time to response. Negative value on error.
     */
    public long pingDns(InetAddress dnsAddress, int timeout) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();

            // Set some socket properties
            socket.setSoTimeout(timeout);

            byte[] buf = new byte[DNS_QUERY_BASE_SIZE];
            fillQuery(buf);

            // Send the DNS query

            DatagramPacket packet = new DatagramPacket(buf,
                    buf.length, dnsAddress, DNS_PORT);
            long start = SystemClock.elapsedRealtime();
            socket.send(packet);

            // Wait for reply (blocks for the above timeout)
            DatagramPacket replyPacket = new DatagramPacket(buf, buf.length);
            socket.receive(replyPacket);

            // If a timeout occurred, an exception would have been thrown. We
            // got a reply!
            return SystemClock.elapsedRealtime() - start;

        } catch (SocketTimeoutException e) {
            // Squelch this exception.
            return -1;
        } catch (Exception e) {
            if (V) {
                Slog.v(TAG, "DnsPinger.pingDns got socket exception: ", e);
            }
            return -2;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }

    }

    private static void fillQuery(byte[] buf) {

        /*
         * See RFC2929 (though the bit tables in there are misleading for us.
         * For example, the recursion desired bit is the 0th bit for us, but
         * looking there it would appear as the 7th bit of the byte
         */

        // Make sure it's all zeroed out
        for (int i = 0; i < buf.length; i++)
            buf[i] = 0;

        // Form a query for www.android.com

        // [0-1] bytes are an ID, generate random ID for this query
        buf[0] = (byte) sRandom.nextInt(256);
        buf[1] = (byte) sRandom.nextInt(256);

        // [2-3] bytes are for flags.
        buf[2] = 1; // Recursion desired

        // [4-5] bytes are for the query count
        buf[5] = 1; // One query

        // [6-7] [8-9] [10-11] are all counts of other fields we don't use

        // [12-15] for www
        writeString(buf, 12, "www");

        // [16-23] for android
        writeString(buf, 16, "android");

        // [24-27] for com
        writeString(buf, 24, "com");

        // [29-30] bytes are for QTYPE, set to 1
        buf[30] = 1;

        // [31-32] bytes are for QCLASS, set to 1
        buf[32] = 1;
    }

    private static void writeString(byte[] buf, int startPos, String string) {
        int pos = startPos;

        // Write the length first
        buf[pos++] = (byte) string.length();
        for (int i = 0; i < string.length(); i++) {
            buf[pos++] = (byte) string.charAt(i);
        }
    }
}
