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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.util.Protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performs a simple DNS "ping" by sending a "server status" query packet to the
 * DNS server. As long as the server replies, we consider it a success.
 * <p>
 * We do not use a simple hostname lookup because that could be cached and the
 * API may not differentiate between a time out and a failure lookup (which we
 * really care about).
 * <p>
 *
 * @hide
 */
public final class DnsPinger extends Handler {
    private static final boolean DBG = false;

    private static final int RECEIVE_POLL_INTERVAL_MS = 200;
    private static final int DNS_PORT = 53;

    /** Short socket timeout so we don't block one any 'receive' call */
    private static final int SOCKET_TIMEOUT_MS = 1;

    /** Used to generate IDs */
    private static final Random sRandom = new Random();
    private static final AtomicInteger sCounter = new AtomicInteger();

    private ConnectivityManager mConnectivityManager = null;
    private final Context mContext;
    private final int mConnectionType;
    private final Handler mTarget;
    private final ArrayList<InetAddress> mDefaultDns;
    private String TAG;

    //Invalidates old dns requests upon a cancel
    private AtomicInteger mCurrentToken = new AtomicInteger();

    private static final int BASE = Protocol.BASE_DNS_PINGER;

    /**
     * Async response packet for dns pings.
     * arg1 is the ID of the ping, also returned by {@link #pingDnsAsync(InetAddress, int, int)}
     * arg2 is the delay, or is negative on error.
     */
    public static final int DNS_PING_RESULT = BASE;
    /** An error code for a {@link #DNS_PING_RESULT} packet */
    public static final int TIMEOUT = -1;
    /** An error code for a {@link #DNS_PING_RESULT} packet */
    public static final int SOCKET_EXCEPTION = -2;

    /**
     * Send a new ping via a socket.  arg1 is ID, arg2 is timeout, obj is InetAddress to ping
     */
    private static final int ACTION_PING_DNS = BASE + 1;
    private static final int ACTION_LISTEN_FOR_RESPONSE = BASE + 2;
    private static final int ACTION_CANCEL_ALL_PINGS = BASE + 3;

    private List<ActivePing> mActivePings = new ArrayList<ActivePing>();
    private int mEventCounter;

    private class ActivePing {
        DatagramSocket socket;
        int internalId;
        short packetId;
        int timeout;
        Integer result;
        long start = SystemClock.elapsedRealtime();
    }

    /* Message argument for ACTION_PING_DNS */
    private class DnsArg {
        InetAddress dns;
        int seq;

        DnsArg(InetAddress d, int s) {
            dns = d;
            seq = s;
        }
    }

    public DnsPinger(Context context, String TAG, Looper looper,
            Handler target, int connectionType) {
        super(looper);
        this.TAG = TAG;
        mContext = context;
        mTarget = target;
        mConnectionType = connectionType;
        if (!ConnectivityManager.isNetworkTypeValid(connectionType)) {
            throw new IllegalArgumentException("Invalid connectionType in constructor: "
                    + connectionType);
        }
        mDefaultDns = new ArrayList<InetAddress>();
        mDefaultDns.add(getDefaultDns());
        mEventCounter = 0;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case ACTION_PING_DNS:
                DnsArg dnsArg = (DnsArg) msg.obj;
                if (dnsArg.seq != mCurrentToken.get()) {
                    break;
                }
                try {
                    ActivePing newActivePing = new ActivePing();
                    InetAddress dnsAddress = dnsArg.dns;
                    newActivePing.internalId = msg.arg1;
                    newActivePing.timeout = msg.arg2;
                    newActivePing.socket = new DatagramSocket();
                    // Set some socket properties
                    newActivePing.socket.setSoTimeout(SOCKET_TIMEOUT_MS);

                    // Try to bind but continue ping if bind fails
                    try {
                        newActivePing.socket.setNetworkInterface(NetworkInterface.getByName(
                                getCurrentLinkProperties().getInterfaceName()));
                    } catch (Exception e) {
                        loge("sendDnsPing::Error binding to socket " + e);
                    }

                    newActivePing.packetId = (short) sRandom.nextInt();
                    byte[] buf = mDnsQuery.clone();
                    buf[0] = (byte) (newActivePing.packetId >> 8);
                    buf[1] = (byte) newActivePing.packetId;

                    // Send the DNS query
                    DatagramPacket packet = new DatagramPacket(buf,
                            buf.length, dnsAddress, DNS_PORT);
                    if (DBG) {
                        log("Sending a ping " + newActivePing.internalId +
                                " to " + dnsAddress.getHostAddress()
                                + " with packetId " + newActivePing.packetId + ".");
                    }

                    newActivePing.socket.send(packet);
                    mActivePings.add(newActivePing);
                    mEventCounter++;
                    sendMessageDelayed(obtainMessage(ACTION_LISTEN_FOR_RESPONSE, mEventCounter, 0),
                            RECEIVE_POLL_INTERVAL_MS);
                } catch (IOException e) {
                    sendResponse(msg.arg1, -9999, SOCKET_EXCEPTION);
                }
                break;
            case ACTION_LISTEN_FOR_RESPONSE:
                if (msg.arg1 != mEventCounter) {
                    break;
                }
                for (ActivePing curPing : mActivePings) {
                    try {
                        /** Each socket will block for {@link #SOCKET_TIMEOUT_MS} in receive() */
                        byte[] responseBuf = new byte[2];
                        DatagramPacket replyPacket = new DatagramPacket(responseBuf, 2);
                        curPing.socket.receive(replyPacket);
                        // Check that ID field matches (we're throwing out the rest of the packet)
                        if (responseBuf[0] == (byte) (curPing.packetId >> 8) &&
                                responseBuf[1] == (byte) curPing.packetId) {
                            curPing.result =
                                    (int) (SystemClock.elapsedRealtime() - curPing.start);
                        } else {
                            if (DBG) {
                                log("response ID didn't match, ignoring packet");
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        // A timeout here doesn't mean anything - squelsh this exception
                    } catch (Exception e) {
                        if (DBG) {
                            log("DnsPinger.pingDns got socket exception: " + e);
                        }
                        curPing.result = SOCKET_EXCEPTION;
                    }
                }
                Iterator<ActivePing> iter = mActivePings.iterator();
                while (iter.hasNext()) {
                   ActivePing curPing = iter.next();
                   if (curPing.result != null) {
                       sendResponse(curPing.internalId, curPing.packetId, curPing.result);
                       curPing.socket.close();
                       iter.remove();
                   } else if (SystemClock.elapsedRealtime() >
                                  curPing.start + curPing.timeout) {
                       sendResponse(curPing.internalId, curPing.packetId, TIMEOUT);
                       curPing.socket.close();
                       iter.remove();
                   }
                }
                if (!mActivePings.isEmpty()) {
                    sendMessageDelayed(obtainMessage(ACTION_LISTEN_FOR_RESPONSE, mEventCounter, 0),
                            RECEIVE_POLL_INTERVAL_MS);
                }
                break;
            case ACTION_CANCEL_ALL_PINGS:
                for (ActivePing activePing : mActivePings)
                    activePing.socket.close();
                mActivePings.clear();
                break;
        }
    }

    /**
     * Returns a list of DNS addresses, coming from either the link properties of the
     * specified connection or the default system DNS if the link properties has no dnses.
     * @return a non-empty non-null list
     */
    public List<InetAddress> getDnsList() {
        LinkProperties curLinkProps = getCurrentLinkProperties();
        if (curLinkProps == null) {
            loge("getCurLinkProperties:: LP for type" + mConnectionType + " is null!");
            return mDefaultDns;
        }

        Collection<InetAddress> dnses = curLinkProps.getDnses();
        if (dnses == null || dnses.size() == 0) {
            loge("getDns::LinkProps has null dns - returning default");
            return mDefaultDns;
        }

        return new ArrayList<InetAddress>(dnses);
    }

    /**
     * Send a ping.  The response will come via a {@link #DNS_PING_RESULT} to the handler
     * specified at creation.
     * @param dns address of dns server to ping
     * @param timeout timeout for ping
     * @return an ID field, which will also be included in the {@link #DNS_PING_RESULT} message.
     */
    public int pingDnsAsync(InetAddress dns, int timeout, int delay) {
        int id = sCounter.incrementAndGet();
        sendMessageDelayed(obtainMessage(ACTION_PING_DNS, id, timeout,
                new DnsArg(dns, mCurrentToken.get())), delay);
        return id;
    }

    public void cancelPings() {
        mCurrentToken.incrementAndGet();
        obtainMessage(ACTION_CANCEL_ALL_PINGS).sendToTarget();
    }

    private void sendResponse(int internalId, int externalId, int responseVal) {
        if(DBG) {
            log("Responding to packet " + internalId +
                    " externalId " + externalId +
                    " and val " + responseVal);
        }
        mTarget.sendMessage(obtainMessage(DNS_PING_RESULT, internalId, responseVal));
    }

    private LinkProperties getCurrentLinkProperties() {
        if (mConnectivityManager == null) {
            mConnectivityManager = (ConnectivityManager) mContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
        }

        return mConnectivityManager.getLinkProperties(mConnectionType);
    }

    private InetAddress getDefaultDns() {
        String dns = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.DEFAULT_DNS_SERVER);
        if (dns == null || dns.length() == 0) {
            dns = mContext.getResources().getString(
                    com.android.internal.R.string.config_default_dns_server);
        }
        try {
            return NetworkUtils.numericToInetAddress(dns);
        } catch (IllegalArgumentException e) {
            loge("getDefaultDns::malformed default dns address");
            return null;
        }
    }

    private static final byte[] mDnsQuery = new byte[] {
        0, 0, // [0-1] is for ID (will set each time)
        1, 0, // [2-3] are flags.  Set byte[2] = 1 for recursion desired (RD) on.  Currently on.
        0, 1, // [4-5] bytes are for number of queries (QCOUNT)
        0, 0, // [6-7] unused count field for dns response packets
        0, 0, // [8-9] unused count field for dns response packets
        0, 0, // [10-11] unused count field for dns response packets
        3, 'w', 'w', 'w',
        6, 'g', 'o', 'o', 'g', 'l', 'e',
        3, 'c', 'o', 'm',
        0,    // null terminator of address (also called empty TLD)
        0, 1, // QTYPE, set to 1 = A (host address)
        0, 1  // QCLASS, set to 1 = IN (internet)
    };

    private void log(String s) {
        Log.d(TAG, s);
    }

    private void loge(String s) {
        Log.e(TAG, s);
    }
}
