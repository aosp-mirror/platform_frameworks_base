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

package android.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkUtils;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
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
 * TODO : More general API. Socket does not bind to specified connection type
 * TODO : Choice of DNS query location - current looks up www.android.com
 *
 * @hide
 */
public final class DnsPinger {
    private static final boolean V = true;

    /** Number of bytes for the query */
    private static final int DNS_QUERY_BASE_SIZE = 32;

    /** The DNS port */
    private static final int DNS_PORT = 53;

    /** Used to generate IDs */
    private static Random sRandom = new Random();

    private ConnectivityManager mConnectivityManager = null;
    private Context mContext;
    private int mConnectionType;
    private InetAddress mDefaultDns;

    private String TAG;

    /**
     * @param connectionType The connection type from {@link ConnectivityManager}
     */
    public DnsPinger(String TAG, Context context, int connectionType) {
        mContext = context;
        mConnectionType = connectionType;
        if (!ConnectivityManager.isNetworkTypeValid(connectionType)) {
            Slog.e(TAG, "Invalid connectionType in constructor: " + connectionType);
        }
        this.TAG = TAG;

        mDefaultDns = getDefaultDns();
    }

    /**
     * @return The first DNS in the link properties of the specified connection
     *         type or the default system DNS if the link properties has null
     *         dns set. Should not be null.
     */
    public InetAddress getDns() {
        LinkProperties curLinkProps = getCurrentLinkProperties();
        if (curLinkProps == null) {
            Slog.e(TAG, "getCurLinkProperties:: LP for type" + mConnectionType + " is null!");
            return mDefaultDns;
        }

        Collection<InetAddress> dnses = curLinkProps.getDnses();
        if (dnses == null || dnses.size() == 0) {
            Slog.v(TAG, "getDns::LinkProps has null dns - returning default");
            return mDefaultDns;
        }

        return dnses.iterator().next();
    }

    private LinkProperties getCurrentLinkProperties() {
        if (mConnectivityManager == null) {
            mConnectivityManager = (ConnectivityManager) mContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
        }

        return mConnectivityManager.getLinkProperties(mConnectionType);
    }

    private InetAddress getDefaultDns() {
        String dns = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.DEFAULT_DNS_SERVER);
        if (dns == null || dns.length() == 0) {
            dns = mContext.getResources().getString(
                    com.android.internal.R.string.config_default_dns_server);
        }
        try {
            return NetworkUtils.numericToInetAddress(dns);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "getDefaultDns::malformed default dns address");
            return null;
        }
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

            // Try to bind but continue ping if bind fails
            try {
                socket.setNetworkInterface(NetworkInterface.getByName(
                        getCurrentLinkProperties().getInterfaceName()));
            } catch (Exception e) {
                Slog.d(TAG,"pingDns::Error binding to socket", e);
            }

            byte[] buf = constructQuery();

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

    /**
     * @return google.com DNS query packet
     */
    private static byte[] constructQuery() {
        byte[] buf = new byte[DNS_QUERY_BASE_SIZE];

        // [0-1] bytes are an ID, generate random ID for this query
        buf[0] = (byte) sRandom.nextInt(256);
        buf[1] = (byte) sRandom.nextInt(256);

        // [2-3] bytes are for flags.
        buf[2] = 0x01; // Recursion desired

        // [4-5] bytes are for number of queries (QCOUNT)
        buf[5] = 0x01;

        // [6-7] [8-9] [10-11] are all counts of other fields we don't use

        // [12-15] for www
        writeString(buf, 12, "www");

        // [16-22] for google
        writeString(buf, 16, "google");

        // [23-26] for com
        writeString(buf, 23, "com");

        // [27] is a null byte terminator byte for the url

        // [28-29] bytes are for QTYPE, set to 1 = A (host address)
        buf[29] = 0x01;

        // [30-31] bytes are for QCLASS, set to 1 = IN (internet)
        buf[31] = 0x01;

        return buf;
    }

    /**
     * Writes the string's length and its contents to the buffer
     */
    private static void writeString(byte[] buf, int startPos, String string) {
        int pos = startPos;

        // Write the length first
        buf[pos++] = (byte) string.length();
        for (int i = 0; i < string.length(); i++) {
            buf[pos++] = (byte) string.charAt(i);
        }
    }
}
