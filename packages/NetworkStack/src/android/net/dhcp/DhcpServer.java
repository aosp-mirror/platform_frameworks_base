/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.dhcp;

import static android.net.TrafficStats.TAG_SYSTEM_DHCP_SERVER;
import static android.net.dhcp.DhcpPacket.DHCP_CLIENT;
import static android.net.dhcp.DhcpPacket.DHCP_HOST_NAME;
import static android.net.dhcp.DhcpPacket.DHCP_SERVER;
import static android.net.dhcp.DhcpPacket.ENCAP_BOOTP;
import static android.net.dhcp.IDhcpServer.STATUS_INVALID_ARGUMENT;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;
import static android.net.shared.Inet4AddressUtils.getBroadcastAddress;
import static android.net.shared.Inet4AddressUtils.getPrefixMaskAsInet4Address;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_NONBLOCK;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_BROADCAST;
import static android.system.OsConstants.SO_REUSEADDR;

import static com.android.server.util.NetworkStackConstants.INFINITE_LEASE;
import static com.android.server.util.NetworkStackConstants.IPV4_ADDR_ALL;
import static com.android.server.util.NetworkStackConstants.IPV4_ADDR_ANY;
import static com.android.server.util.PermissionUtil.checkNetworkStackCallingPermission;

import static java.lang.Integer.toUnsignedLong;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.INetworkStackStatusCallback;
import android.net.MacAddress;
import android.net.TrafficStats;
import android.net.util.SharedLog;
import android.net.util.SocketUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.HexDump;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * A DHCPv4 server.
 *
 * <p>This server listens for and responds to packets on a single interface. It considers itself
 * authoritative for all leases on the subnet, which means that DHCP requests for unknown leases of
 * unknown hosts receive a reply instead of being ignored.
 *
 * <p>The server is single-threaded (including send/receive operations): all internal operations are
 * done on the provided {@link Looper}. Public methods are thread-safe and will schedule operations
 * on the looper asynchronously.
 * @hide
 */
public class DhcpServer extends IDhcpServer.Stub {
    private static final String REPO_TAG = "Repository";

    // Lease time to transmit to client instead of a negative time in case a lease expired before
    // the server could send it (if the server process is suspended for example).
    private static final int EXPIRED_FALLBACK_LEASE_TIME_SECS = 120;

    private static final int CMD_START_DHCP_SERVER = 1;
    private static final int CMD_STOP_DHCP_SERVER = 2;
    private static final int CMD_UPDATE_PARAMS = 3;

    @NonNull
    private final HandlerThread mHandlerThread;
    @NonNull
    private final String mIfName;
    @NonNull
    private final DhcpLeaseRepository mLeaseRepo;
    @NonNull
    private final SharedLog mLog;
    @NonNull
    private final Dependencies mDeps;
    @NonNull
    private final Clock mClock;

    @Nullable
    private volatile ServerHandler mHandler;

    // Accessed only on the handler thread
    @Nullable
    private DhcpPacketListener mPacketListener;
    @Nullable
    private FileDescriptor mSocket;
    @NonNull
    private DhcpServingParams mServingParams;

    /**
     * Clock to be used by DhcpServer to track time for lease expiration.
     *
     * <p>The clock should track time as may be measured by clients obtaining a lease. It does not
     * need to be monotonous across restarts of the server as long as leases are cleared when the
     * server is stopped.
     */
    public static class Clock {
        /**
         * @see SystemClock#elapsedRealtime()
         */
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }
    }

    /**
     * Dependencies for the DhcpServer. Useful to be mocked in tests.
     */
    public interface Dependencies {
        /**
         * Send a packet to the specified datagram socket.
         *
         * @param fd File descriptor of the socket.
         * @param buffer Data to be sent.
         * @param dst Destination address of the packet.
         */
        void sendPacket(@NonNull FileDescriptor fd, @NonNull ByteBuffer buffer,
                @NonNull InetAddress dst) throws ErrnoException, IOException;

        /**
         * Create a DhcpLeaseRepository for the server.
         * @param servingParams Parameters used to serve DHCP requests.
         * @param log Log to be used by the repository.
         * @param clock Clock that the repository must use to track time.
         */
        DhcpLeaseRepository makeLeaseRepository(@NonNull DhcpServingParams servingParams,
                @NonNull SharedLog log, @NonNull Clock clock);

        /**
         * Create a packet listener that will send packets to be processed.
         */
        DhcpPacketListener makePacketListener();

        /**
         * Create a clock that the server will use to track time.
         */
        Clock makeClock();

        /**
         * Add an entry to the ARP cache table.
         * @param fd Datagram socket file descriptor that must use the new entry.
         */
        void addArpEntry(@NonNull Inet4Address ipv4Addr, @NonNull MacAddress ethAddr,
                @NonNull String ifname, @NonNull FileDescriptor fd) throws IOException;

        /**
         * Verify that the caller is allowed to call public methods on DhcpServer.
         * @throws SecurityException The caller is not allowed to call public methods on DhcpServer.
         */
        void checkCaller() throws SecurityException;
    }

    private class DependenciesImpl implements Dependencies {
        @Override
        public void sendPacket(@NonNull FileDescriptor fd, @NonNull ByteBuffer buffer,
                @NonNull InetAddress dst) throws ErrnoException, IOException {
            Os.sendto(fd, buffer, 0, dst, DhcpPacket.DHCP_CLIENT);
        }

        @Override
        public DhcpLeaseRepository makeLeaseRepository(@NonNull DhcpServingParams servingParams,
                @NonNull SharedLog log, @NonNull Clock clock) {
            return new DhcpLeaseRepository(
                    DhcpServingParams.makeIpPrefix(servingParams.serverAddr),
                    servingParams.excludedAddrs,
                    servingParams.dhcpLeaseTimeSecs * 1000, log.forSubComponent(REPO_TAG), clock);
        }

        @Override
        public DhcpPacketListener makePacketListener() {
            return new PacketListener();
        }

        @Override
        public Clock makeClock() {
            return new Clock();
        }

        @Override
        public void addArpEntry(@NonNull Inet4Address ipv4Addr, @NonNull MacAddress ethAddr,
                @NonNull String ifname, @NonNull FileDescriptor fd) throws IOException {
            SocketUtils.addArpEntry(ipv4Addr, ethAddr, ifname, fd);
        }

        @Override
        public void checkCaller() {
            checkNetworkStackCallingPermission();
        }
    }

    private static class MalformedPacketException extends Exception {
        MalformedPacketException(String message, Throwable t) {
            super(message, t);
        }
    }

    public DhcpServer(@NonNull String ifName,
            @NonNull DhcpServingParams params, @NonNull SharedLog log) {
        this(new HandlerThread(DhcpServer.class.getSimpleName() + "." + ifName),
                ifName, params, log, null);
    }

    @VisibleForTesting
    DhcpServer(@NonNull HandlerThread handlerThread, @NonNull String ifName,
            @NonNull DhcpServingParams params, @NonNull SharedLog log,
            @Nullable Dependencies deps) {
        if (deps == null) {
            deps = new DependenciesImpl();
        }
        mHandlerThread = handlerThread;
        mIfName = ifName;
        mServingParams = params;
        mLog = log;
        mDeps = deps;
        mClock = deps.makeClock();
        mLeaseRepo = deps.makeLeaseRepository(mServingParams, mLog, mClock);
    }

    /**
     * Start listening for and responding to packets.
     *
     * <p>It is not legal to call this method more than once; in particular the server cannot be
     * restarted after being stopped.
     */
    @Override
    public void start(@Nullable INetworkStackStatusCallback cb) {
        mDeps.checkCaller();
        mHandlerThread.start();
        mHandler = new ServerHandler(mHandlerThread.getLooper());
        sendMessage(CMD_START_DHCP_SERVER, cb);
    }

    /**
     * Update serving parameters. All subsequently received requests will be handled with the new
     * parameters, and current leases that are incompatible with the new parameters are dropped.
     */
    @Override
    public void updateParams(@Nullable DhcpServingParamsParcel params,
            @Nullable INetworkStackStatusCallback cb) throws RemoteException {
        mDeps.checkCaller();
        final DhcpServingParams parsedParams;
        try {
            // throws InvalidParameterException with null params
            parsedParams = DhcpServingParams.fromParcelableObject(params);
        } catch (DhcpServingParams.InvalidParameterException e) {
            mLog.e("Invalid parameters sent to DhcpServer", e);
            if (cb != null) {
                cb.onStatusAvailable(STATUS_INVALID_ARGUMENT);
            }
            return;
        }
        sendMessage(CMD_UPDATE_PARAMS, new Pair<>(parsedParams, cb));
    }

    /**
     * Stop listening for packets.
     *
     * <p>As the server is stopped asynchronously, some packets may still be processed shortly after
     * calling this method.
     */
    @Override
    public void stop(@Nullable INetworkStackStatusCallback cb) {
        mDeps.checkCaller();
        sendMessage(CMD_STOP_DHCP_SERVER, cb);
    }

    private void sendMessage(int what, @Nullable Object obj) {
        if (mHandler == null) {
            mLog.e("Attempting to send a command to stopped DhcpServer: " + what);
            return;
        }
        mHandler.sendMessage(mHandler.obtainMessage(what, obj));
    }

    private class ServerHandler extends Handler {
        ServerHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            final INetworkStackStatusCallback cb;
            switch (msg.what) {
                case CMD_UPDATE_PARAMS:
                    final Pair<DhcpServingParams, INetworkStackStatusCallback> pair =
                            (Pair<DhcpServingParams, INetworkStackStatusCallback>) msg.obj;
                    final DhcpServingParams params = pair.first;
                    mServingParams = params;
                    mLeaseRepo.updateParams(
                            DhcpServingParams.makeIpPrefix(mServingParams.serverAddr),
                            params.excludedAddrs,
                            params.dhcpLeaseTimeSecs);

                    cb = pair.second;
                    break;
                case CMD_START_DHCP_SERVER:
                    mPacketListener = mDeps.makePacketListener();
                    mPacketListener.start();
                    cb = (INetworkStackStatusCallback) msg.obj;
                    break;
                case CMD_STOP_DHCP_SERVER:
                    if (mPacketListener != null) {
                        mPacketListener.stop();
                        mPacketListener = null;
                    }
                    mHandlerThread.quitSafely();
                    cb = (INetworkStackStatusCallback) msg.obj;
                    break;
                default:
                    return;
            }
            if (cb != null) {
                try {
                    cb.onStatusAvailable(STATUS_SUCCESS);
                } catch (RemoteException e) {
                    mLog.e("Could not send status back to caller", e);
                }
            }
        }
    }

    @VisibleForTesting
    void processPacket(@NonNull DhcpPacket packet, int srcPort) {
        final String packetType = packet.getClass().getSimpleName();
        if (srcPort != DHCP_CLIENT) {
            mLog.logf("Ignored packet of type %s sent from client port %d", packetType, srcPort);
            return;
        }

        mLog.log("Received packet of type " + packetType);
        final Inet4Address sid = packet.mServerIdentifier;
        if (sid != null && !sid.equals(mServingParams.serverAddr.getAddress())) {
            mLog.log("Packet ignored due to wrong server identifier: " + sid);
            return;
        }

        try {
            if (packet instanceof DhcpDiscoverPacket) {
                processDiscover((DhcpDiscoverPacket) packet);
            } else if (packet instanceof DhcpRequestPacket) {
                processRequest((DhcpRequestPacket) packet);
            } else if (packet instanceof DhcpReleasePacket) {
                processRelease((DhcpReleasePacket) packet);
            } else {
                mLog.e("Unknown packet type: " + packet.getClass().getSimpleName());
            }
        } catch (MalformedPacketException e) {
            // Not an internal error: only logging exception message, not stacktrace
            mLog.e("Ignored malformed packet: " + e.getMessage());
        }
    }

    private void logIgnoredPacketInvalidSubnet(DhcpLeaseRepository.InvalidSubnetException e) {
        // Not an internal error: only logging exception message, not stacktrace
        mLog.e("Ignored packet from invalid subnet: " + e.getMessage());
    }

    private void processDiscover(@NonNull DhcpDiscoverPacket packet)
            throws MalformedPacketException {
        final DhcpLease lease;
        final MacAddress clientMac = getMacAddr(packet);
        try {
            lease = mLeaseRepo.getOffer(packet.getExplicitClientIdOrNull(), clientMac,
                    packet.mRelayIp, packet.mRequestedIp, packet.mHostName);
        } catch (DhcpLeaseRepository.OutOfAddressesException e) {
            transmitNak(packet, "Out of addresses to offer");
            return;
        } catch (DhcpLeaseRepository.InvalidSubnetException e) {
            logIgnoredPacketInvalidSubnet(e);
            return;
        }

        transmitOffer(packet, lease, clientMac);
    }

    private void processRequest(@NonNull DhcpRequestPacket packet) throws MalformedPacketException {
        // If set, packet SID matches with this server's ID as checked in processPacket().
        final boolean sidSet = packet.mServerIdentifier != null;
        final DhcpLease lease;
        final MacAddress clientMac = getMacAddr(packet);
        try {
            lease = mLeaseRepo.requestLease(packet.getExplicitClientIdOrNull(), clientMac,
                    packet.mClientIp, packet.mRelayIp, packet.mRequestedIp, sidSet,
                    packet.mHostName);
        } catch (DhcpLeaseRepository.InvalidAddressException e) {
            transmitNak(packet, "Invalid requested address");
            return;
        } catch (DhcpLeaseRepository.InvalidSubnetException e) {
            logIgnoredPacketInvalidSubnet(e);
            return;
        }

        transmitAck(packet, lease, clientMac);
    }

    private void processRelease(@NonNull DhcpReleasePacket packet)
            throws MalformedPacketException {
        final byte[] clientId = packet.getExplicitClientIdOrNull();
        final MacAddress macAddr = getMacAddr(packet);
        // Don't care about success (there is no ACK/NAK); logging is already done in the repository
        mLeaseRepo.releaseLease(clientId, macAddr, packet.mClientIp);
    }

    private Inet4Address getAckOrOfferDst(@NonNull DhcpPacket request, @NonNull DhcpLease lease,
            boolean broadcastFlag) {
        // Unless relayed or broadcast, send to client IP if already configured on the client, or to
        // the lease address if the client has no configured address
        if (!isEmpty(request.mRelayIp)) {
            return request.mRelayIp;
        } else if (broadcastFlag) {
            return IPV4_ADDR_ALL;
        } else if (!isEmpty(request.mClientIp)) {
            return request.mClientIp;
        } else {
            return lease.getNetAddr();
        }
    }

    /**
     * Determine whether the broadcast flag should be set in the BOOTP packet flags. This does not
     * apply to NAK responses, which should always have it set.
     */
    private static boolean getBroadcastFlag(@NonNull DhcpPacket request, @NonNull DhcpLease lease) {
        // No broadcast flag if the client already has a configured IP to unicast to. RFC2131 #4.1
        // has some contradictions regarding broadcast behavior if a client already has an IP
        // configured and sends a request with both ciaddr (renew/rebind) and the broadcast flag
        // set. Sending a unicast response to ciaddr matches previous behavior and is more
        // efficient.
        // If the client has no configured IP, broadcast if requested by the client or if the lease
        // address cannot be used to send a unicast reply either.
        return isEmpty(request.mClientIp) && (request.mBroadcast || isEmpty(lease.getNetAddr()));
    }

    /**
     * Get the hostname from a lease if non-empty and requested in the incoming request.
     * @param request The incoming request.
     * @return The hostname, or null if not requested or empty.
     */
    @Nullable
    private static String getHostnameIfRequested(@NonNull DhcpPacket request,
            @NonNull DhcpLease lease) {
        return request.hasRequestedParam(DHCP_HOST_NAME) && !TextUtils.isEmpty(lease.getHostname())
                ? lease.getHostname()
                : null;
    }

    private boolean transmitOffer(@NonNull DhcpPacket request, @NonNull DhcpLease lease,
            @NonNull MacAddress clientMac) {
        final boolean broadcastFlag = getBroadcastFlag(request, lease);
        final int timeout = getLeaseTimeout(lease);
        final Inet4Address prefixMask =
                getPrefixMaskAsInet4Address(mServingParams.serverAddr.getPrefixLength());
        final Inet4Address broadcastAddr = getBroadcastAddress(
                mServingParams.getServerInet4Addr(), mServingParams.serverAddr.getPrefixLength());
        final String hostname = getHostnameIfRequested(request, lease);
        final ByteBuffer offerPacket = DhcpPacket.buildOfferPacket(
                ENCAP_BOOTP, request.mTransId, broadcastFlag, mServingParams.getServerInet4Addr(),
                request.mRelayIp, lease.getNetAddr(), request.mClientMac, timeout, prefixMask,
                broadcastAddr, new ArrayList<>(mServingParams.defaultRouters),
                new ArrayList<>(mServingParams.dnsServers),
                mServingParams.getServerInet4Addr(), null /* domainName */, hostname,
                mServingParams.metered, (short) mServingParams.linkMtu);

        return transmitOfferOrAckPacket(offerPacket, request, lease, clientMac, broadcastFlag);
    }

    private boolean transmitAck(@NonNull DhcpPacket request, @NonNull DhcpLease lease,
            @NonNull MacAddress clientMac) {
        // TODO: replace DhcpPacket's build methods with real builders and use common code with
        // transmitOffer above
        final boolean broadcastFlag = getBroadcastFlag(request, lease);
        final int timeout = getLeaseTimeout(lease);
        final String hostname = getHostnameIfRequested(request, lease);
        final ByteBuffer ackPacket = DhcpPacket.buildAckPacket(ENCAP_BOOTP, request.mTransId,
                broadcastFlag, mServingParams.getServerInet4Addr(), request.mRelayIp,
                lease.getNetAddr(), request.mClientIp, request.mClientMac, timeout,
                mServingParams.getPrefixMaskAsAddress(), mServingParams.getBroadcastAddress(),
                new ArrayList<>(mServingParams.defaultRouters),
                new ArrayList<>(mServingParams.dnsServers),
                mServingParams.getServerInet4Addr(), null /* domainName */, hostname,
                mServingParams.metered, (short) mServingParams.linkMtu);

        return transmitOfferOrAckPacket(ackPacket, request, lease, clientMac, broadcastFlag);
    }

    private boolean transmitNak(DhcpPacket request, String message) {
        mLog.w("Transmitting NAK: " + message);
        // Always set broadcast flag for NAK: client may not have a correct IP
        final ByteBuffer nakPacket = DhcpPacket.buildNakPacket(
                ENCAP_BOOTP, request.mTransId, mServingParams.getServerInet4Addr(),
                request.mRelayIp, request.mClientMac, true /* broadcast */, message);

        final Inet4Address dst = isEmpty(request.mRelayIp)
                ? IPV4_ADDR_ALL
                : request.mRelayIp;
        return transmitPacket(nakPacket, DhcpNakPacket.class.getSimpleName(), dst);
    }

    private boolean transmitOfferOrAckPacket(@NonNull ByteBuffer buf, @NonNull DhcpPacket request,
            @NonNull DhcpLease lease, @NonNull MacAddress clientMac, boolean broadcastFlag) {
        mLog.logf("Transmitting %s with lease %s", request.getClass().getSimpleName(), lease);
        // Client may not yet respond to ARP for the lease address, which may be the destination
        // address. Add an entry to the ARP cache to save future ARP probes and make sure the
        // packet reaches its destination.
        if (!addArpEntry(clientMac, lease.getNetAddr())) {
            // Logging for error already done
            return false;
        }
        final Inet4Address dst = getAckOrOfferDst(request, lease, broadcastFlag);
        return transmitPacket(buf, request.getClass().getSimpleName(), dst);
    }

    private boolean transmitPacket(@NonNull ByteBuffer buf, @NonNull String packetTypeTag,
            @NonNull Inet4Address dst) {
        try {
            mDeps.sendPacket(mSocket, buf, dst);
        } catch (ErrnoException | IOException e) {
            mLog.e("Can't send packet " + packetTypeTag, e);
            return false;
        }
        return true;
    }

    private boolean addArpEntry(@NonNull MacAddress macAddr, @NonNull Inet4Address inetAddr) {
        try {
            mDeps.addArpEntry(inetAddr, macAddr, mIfName, mSocket);
            return true;
        } catch (IOException e) {
            mLog.e("Error adding client to ARP table", e);
            return false;
        }
    }

    /**
     * Get the remaining lease time in seconds, starting from {@link Clock#elapsedRealtime()}.
     *
     * <p>This is an unsigned 32-bit integer, so it cannot be read as a standard (signed) Java int.
     * The return value is only intended to be used to populate the lease time field in a DHCP
     * response, considering that lease time is an unsigned 32-bit integer field in DHCP packets.
     *
     * <p>Lease expiration times are tracked internally with millisecond precision: this method
     * returns a rounded down value.
     */
    private int getLeaseTimeout(@NonNull DhcpLease lease) {
        final long remainingTimeSecs = (lease.getExpTime() - mClock.elapsedRealtime()) / 1000;
        if (remainingTimeSecs < 0) {
            mLog.e("Processing expired lease " + lease);
            return EXPIRED_FALLBACK_LEASE_TIME_SECS;
        }

        if (remainingTimeSecs >= toUnsignedLong(INFINITE_LEASE)) {
            return INFINITE_LEASE;
        }

        return (int) remainingTimeSecs;
    }

    /**
     * Get the client MAC address from a packet.
     *
     * @throws MalformedPacketException The address in the packet uses an unsupported format.
     */
    @NonNull
    private MacAddress getMacAddr(@NonNull DhcpPacket packet) throws MalformedPacketException {
        try {
            return MacAddress.fromBytes(packet.getClientMac());
        } catch (IllegalArgumentException e) {
            final String message = "Invalid MAC address in packet: "
                    + HexDump.dumpHexString(packet.getClientMac());
            throw new MalformedPacketException(message, e);
        }
    }

    private static boolean isEmpty(@Nullable Inet4Address address) {
        return address == null || IPV4_ADDR_ANY.equals(address);
    }

    private class PacketListener extends DhcpPacketListener {
        PacketListener() {
            super(mHandler);
        }

        @Override
        protected void onReceive(@NonNull DhcpPacket packet, @NonNull Inet4Address srcAddr,
                int srcPort) {
            processPacket(packet, srcPort);
        }

        @Override
        protected void logError(@NonNull String msg, Exception e) {
            mLog.e("Error receiving packet: " + msg, e);
        }

        @Override
        protected void logParseError(@NonNull byte[] packet, int length,
                @NonNull DhcpPacket.ParseException e) {
            mLog.e("Error parsing packet", e);
        }

        @Override
        protected FileDescriptor createFd() {
            // TODO: have and use an API to set a socket tag without going through the thread tag
            final int oldTag = TrafficStats.getAndSetThreadStatsTag(TAG_SYSTEM_DHCP_SERVER);
            try {
                mSocket = Os.socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK, IPPROTO_UDP);
                SocketUtils.bindSocketToInterface(mSocket, mIfName);
                Os.setsockoptInt(mSocket, SOL_SOCKET, SO_REUSEADDR, 1);
                Os.setsockoptInt(mSocket, SOL_SOCKET, SO_BROADCAST, 1);
                Os.bind(mSocket, IPV4_ADDR_ANY, DHCP_SERVER);

                return mSocket;
            } catch (IOException | ErrnoException e) {
                mLog.e("Error creating UDP socket", e);
                DhcpServer.this.stop(null);
                return null;
            } finally {
                TrafficStats.setThreadStatsTag(oldTag);
            }
        }
    }
}
