/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.app.DownloadManager;
import android.app.backup.BackupManager;
import android.content.Context;
import android.media.MediaPlayer;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.server.NetworkManagementSocketTagger;

import dalvik.system.SocketTagger;

import java.net.Socket;
import java.net.SocketException;

/**
 * Class that provides network traffic statistics.  These statistics include
 * bytes transmitted and received and network packets transmitted and received,
 * over all interfaces, over the mobile interface, and on a per-UID basis.
 * <p>
 * These statistics may not be available on all platforms.  If the statistics
 * are not supported by this device, {@link #UNSUPPORTED} will be returned.
 */
public class TrafficStats {
    /**
     * The return value to indicate that the device does not support the statistic.
     */
    public final static int UNSUPPORTED = -1;

    /** @hide */
    public static final long KB_IN_BYTES = 1024;
    /** @hide */
    public static final long MB_IN_BYTES = KB_IN_BYTES * 1024;
    /** @hide */
    public static final long GB_IN_BYTES = MB_IN_BYTES * 1024;

    /**
     * Special UID value used when collecting {@link NetworkStatsHistory} for
     * removed applications.
     *
     * @hide
     */
    public static final int UID_REMOVED = -4;

    /**
     * Special UID value used when collecting {@link NetworkStatsHistory} for
     * tethering traffic.
     *
     * @hide
     */
    public static final int UID_TETHERING = -5;

    /**
     * Default tag value for {@link DownloadManager} traffic.
     *
     * @hide
     */
    public static final int TAG_SYSTEM_DOWNLOAD = 0xFFFFFF01;

    /**
     * Default tag value for {@link MediaPlayer} traffic.
     *
     * @hide
     */
    public static final int TAG_SYSTEM_MEDIA = 0xFFFFFF02;

    /**
     * Default tag value for {@link BackupManager} traffic.
     *
     * @hide
     */
    public static final int TAG_SYSTEM_BACKUP = 0xFFFFFF03;

    private static INetworkStatsService sStatsService;

    private synchronized static INetworkStatsService getStatsService() {
        if (sStatsService == null) {
            sStatsService = INetworkStatsService.Stub.asInterface(
                    ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
        }
        return sStatsService;
    }

    /**
     * Snapshot of {@link NetworkStats} when the currently active profiling
     * session started, or {@code null} if no session active.
     *
     * @see #startDataProfiling(Context)
     * @see #stopDataProfiling(Context)
     */
    private static NetworkStats sActiveProfilingStart;

    private static Object sProfilingLock = new Object();

    /**
     * Set active tag to use when accounting {@link Socket} traffic originating
     * from the current thread. Only one active tag per thread is supported.
     * <p>
     * Changes only take effect during subsequent calls to
     * {@link #tagSocket(Socket)}.
     * <p>
     * Tags between {@code 0xFFFFFF00} and {@code 0xFFFFFFFF} are reserved and
     * used internally by system services like {@link DownloadManager} when
     * performing traffic on behalf of an application.
     */
    public static void setThreadStatsTag(int tag) {
        NetworkManagementSocketTagger.setThreadSocketStatsTag(tag);
    }

    /**
     * Get the active tag used when accounting {@link Socket} traffic originating
     * from the current thread. Only one active tag per thread is supported.
     * {@link #tagSocket(Socket)}.
     */
    public static int getThreadStatsTag() {
        return NetworkManagementSocketTagger.getThreadSocketStatsTag();
    }

    public static void clearThreadStatsTag() {
        NetworkManagementSocketTagger.setThreadSocketStatsTag(-1);
    }

    /**
     * Set specific UID to use when accounting {@link Socket} traffic
     * originating from the current thread. Designed for use when performing an
     * operation on behalf of another application.
     * <p>
     * Changes only take effect during subsequent calls to
     * {@link #tagSocket(Socket)}.
     * <p>
     * To take effect, caller must hold
     * {@link android.Manifest.permission#UPDATE_DEVICE_STATS} permission.
     *
     * {@hide}
     */
    public static void setThreadStatsUid(int uid) {
        NetworkManagementSocketTagger.setThreadSocketStatsUid(uid);
    }

    /** {@hide} */
    public static void clearThreadStatsUid() {
        NetworkManagementSocketTagger.setThreadSocketStatsUid(-1);
    }

    /**
     * Tag the given {@link Socket} with any statistics parameters active for
     * the current thread. Subsequent calls always replace any existing
     * parameters. When finished, call {@link #untagSocket(Socket)} to remove
     * statistics parameters.
     *
     * @see #setThreadStatsTag(int)
     * @see #setThreadStatsUid(int)
     */
    public static void tagSocket(Socket socket) throws SocketException {
        SocketTagger.get().tag(socket);
    }

    /**
     * Remove any statistics parameters from the given {@link Socket}.
     */
    public static void untagSocket(Socket socket) throws SocketException {
        SocketTagger.get().untag(socket);
    }

    /**
     * Start profiling data usage for current UID. Only one profiling session
     * can be active at a time.
     *
     * @hide
     */
    public static void startDataProfiling(Context context) {
        synchronized (sProfilingLock) {
            if (sActiveProfilingStart != null) {
                throw new IllegalStateException("already profiling data");
            }

            // take snapshot in time; we calculate delta later
            sActiveProfilingStart = getDataLayerSnapshotForUid(context);
        }
    }

    /**
     * Stop profiling data usage for current UID.
     *
     * @return Detailed {@link NetworkStats} of data that occurred since last
     *         {@link #startDataProfiling(Context)} call.
     * @hide
     */
    public static NetworkStats stopDataProfiling(Context context) {
        synchronized (sProfilingLock) {
            if (sActiveProfilingStart == null) {
                throw new IllegalStateException("not profiling data");
            }

            // subtract starting values and return delta
            final NetworkStats profilingStop = getDataLayerSnapshotForUid(context);
            final NetworkStats profilingDelta = NetworkStats.subtract(
                    profilingStop, sActiveProfilingStart, null, null);
            sActiveProfilingStart = null;
            return profilingDelta;
        }
    }

    /**
     * Increment count of network operations performed under the accounting tag
     * currently active on the calling thread. This can be used to derive
     * bytes-per-operation.
     *
     * @param operationCount Number of operations to increment count by.
     */
    public static void incrementOperationCount(int operationCount) {
        final int tag = getThreadStatsTag();
        incrementOperationCount(tag, operationCount);
    }

    /**
     * Increment count of network operations performed under the given
     * accounting tag. This can be used to derive bytes-per-operation.
     *
     * @param tag Accounting tag used in {@link #setThreadStatsTag(int)}.
     * @param operationCount Number of operations to increment count by.
     */
    public static void incrementOperationCount(int tag, int operationCount) {
        final int uid = android.os.Process.myUid();
        try {
            getStatsService().incrementOperationCount(uid, tag, operationCount);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** {@hide} */
    public static void closeQuietly(INetworkStatsSession session) {
        // TODO: move to NetworkStatsService once it exists
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Get the total number of packets transmitted through the mobile interface.
     *
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getMobileTxPackets() {
        long total = 0;
        for (String iface : getMobileIfaces()) {
            total += getTxPackets(iface);
        }
        return total;
    }

    /**
     * Get the total number of packets received through the mobile interface.
     *
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getMobileRxPackets() {
        long total = 0;
        for (String iface : getMobileIfaces()) {
            total += getRxPackets(iface);
        }
        return total;
    }

    /**
     * Get the total number of bytes transmitted through the mobile interface.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getMobileTxBytes() {
        long total = 0;
        for (String iface : getMobileIfaces()) {
            total += getTxBytes(iface);
        }
        return total;
    }

    /**
     * Get the total number of bytes received through the mobile interface.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getMobileRxBytes() {
        long total = 0;
        for (String iface : getMobileIfaces()) {
            total += getRxBytes(iface);
        }
        return total;
    }

    /**
     * Get the total number of packets transmitted through the specified interface.
     *
     * @return number of packets.  If the statistics are not supported by this interface,
     * {@link #UNSUPPORTED} will be returned.
     * @hide
     */
    public static long getTxPackets(String iface) {
        return nativeGetIfaceStat(iface, TYPE_TX_PACKETS);
    }

    /**
     * Get the total number of packets received through the specified interface.
     *
     * @return number of packets.  If the statistics are not supported by this interface,
     * {@link #UNSUPPORTED} will be returned.
     * @hide
     */
    public static long getRxPackets(String iface) {
        return nativeGetIfaceStat(iface, TYPE_RX_PACKETS);
    }

    /**
     * Get the total number of bytes transmitted through the specified interface.
     *
     * @return number of bytes.  If the statistics are not supported by this interface,
     * {@link #UNSUPPORTED} will be returned.
     * @hide
     */
    public static long getTxBytes(String iface) {
        return nativeGetIfaceStat(iface, TYPE_TX_BYTES);
    }

    /**
     * Get the total number of bytes received through the specified interface.
     *
     * @return number of bytes.  If the statistics are not supported by this interface,
     * {@link #UNSUPPORTED} will be returned.
     * @hide
     */
    public static long getRxBytes(String iface) {
        return nativeGetIfaceStat(iface, TYPE_RX_BYTES);
    }

    /**
     * Get the total number of packets sent through all network interfaces.
     *
     * @return the number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getTotalTxPackets() {
        return nativeGetTotalStat(TYPE_TX_PACKETS);
    }

    /**
     * Get the total number of packets received through all network interfaces.
     *
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getTotalRxPackets() {
        return nativeGetTotalStat(TYPE_RX_PACKETS);
    }

    /**
     * Get the total number of bytes sent through all network interfaces.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getTotalTxBytes() {
        return nativeGetTotalStat(TYPE_TX_BYTES);
    }

    /**
     * Get the total number of bytes received through all network interfaces.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getTotalRxBytes() {
        return nativeGetTotalStat(TYPE_RX_BYTES);
    }

    /**
     * Get the number of bytes sent through the network for this UID.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTxBytes(int uid);

    /**
     * Get the number of bytes received through the network for this UID.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes
     */
    public static native long getUidRxBytes(int uid);

    /**
     * Get the number of packets (TCP segments + UDP) sent through
     * the network for this UID.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of packets.
     * If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTxPackets(int uid);

    /**
     * Get the number of packets (TCP segments + UDP) received through
     * the network for this UID.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of packets
     */
    public static native long getUidRxPackets(int uid);

    /**
     * Get the number of TCP payload bytes sent for this UID.
     * This total does not include protocol and control overheads at
     * the transport and the lower layers of the networking stack.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTcpTxBytes(int uid);

    /**
     * Get the number of TCP payload bytes received for this UID.
     * This total does not include protocol and control overheads at
     * the transport and the lower layers of the networking stack.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTcpRxBytes(int uid);

    /**
     * Get the number of UDP payload bytes sent for this UID.
     * This total does not include protocol and control overheads at
     * the transport and the lower layers of the networking stack.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidUdpTxBytes(int uid);

    /**
     * Get the number of UDP payload bytes received for this UID.
     * This total does not include protocol and control overheads at
     * the transport and the lower layers of the networking stack.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidUdpRxBytes(int uid);

    /**
     * Get the number of TCP segments sent for this UID.
     * Does not include TCP control packets (SYN/ACKs/FIN/..).
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of TCP segments.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTcpTxSegments(int uid);

    /**
     * Get the number of TCP segments received for this UID.
     * Does not include TCP control packets (SYN/ACKs/FIN/..).
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of TCP segments.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTcpRxSegments(int uid);

    /**
     * Get the number of UDP packets sent for this UID.
     * Includes DNS requests.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidUdpTxPackets(int uid);

    /**
     * Get the number of UDP packets received for this UID.
     * Includes DNS responses.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidUdpRxPackets(int uid);

    /**
     * Return detailed {@link NetworkStats} for the current UID. Requires no
     * special permission.
     */
    private static NetworkStats getDataLayerSnapshotForUid(Context context) {
        final int uid = android.os.Process.myUid();
        try {
            return getStatsService().getDataLayerSnapshotForUid(uid);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Return set of any ifaces associated with mobile networks since boot.
     * Interfaces are never removed from this list, so counters should always be
     * monotonic.
     */
    private static String[] getMobileIfaces() {
        try {
            return getStatsService().getMobileIfaces();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    // NOTE: keep these in sync with android_net_TrafficStats.cpp
    private static final int TYPE_RX_BYTES = 0;
    private static final int TYPE_RX_PACKETS = 1;
    private static final int TYPE_TX_BYTES = 2;
    private static final int TYPE_TX_PACKETS = 3;

    private static native long nativeGetTotalStat(int type);
    private static native long nativeGetIfaceStat(String iface, int type);
}
