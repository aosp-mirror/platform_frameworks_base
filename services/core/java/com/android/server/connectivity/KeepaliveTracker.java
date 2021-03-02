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

package com.android.server.connectivity;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NattSocketKeepalive.NATT_PORT;
import static android.net.NetworkAgent.CMD_START_SOCKET_KEEPALIVE;
import static android.net.SocketKeepalive.BINDER_DIED;
import static android.net.SocketKeepalive.DATA_RECEIVED;
import static android.net.SocketKeepalive.ERROR_INSUFFICIENT_RESOURCES;
import static android.net.SocketKeepalive.ERROR_INVALID_INTERVAL;
import static android.net.SocketKeepalive.ERROR_INVALID_IP_ADDRESS;
import static android.net.SocketKeepalive.ERROR_INVALID_NETWORK;
import static android.net.SocketKeepalive.ERROR_INVALID_SOCKET;
import static android.net.SocketKeepalive.ERROR_STOP_REASON_UNINITIALIZED;
import static android.net.SocketKeepalive.ERROR_UNSUPPORTED;
import static android.net.SocketKeepalive.MAX_INTERVAL_SEC;
import static android.net.SocketKeepalive.MIN_INTERVAL_SEC;
import static android.net.SocketKeepalive.NO_KEEPALIVE;
import static android.net.SocketKeepalive.SUCCESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ISocketKeepaliveCallback;
import android.net.InetAddresses;
import android.net.InvalidPacketException;
import android.net.KeepalivePacketData;
import android.net.NattKeepalivePacketData;
import android.net.NetworkAgent;
import android.net.SocketKeepalive.InvalidSocketException;
import android.net.TcpKeepalivePacketData;
import android.net.util.KeepaliveUtils;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.util.IndentingPrintWriter;
import com.android.net.module.util.HexDump;
import com.android.net.module.util.IpUtils;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Manages socket keepalive requests.
 *
 * Provides methods to stop and start keepalive requests, and keeps track of keepalives across all
 * networks. This class is tightly coupled to ConnectivityService. It is not thread-safe and its
 * handle* methods must be called only from the ConnectivityService handler thread.
 */
public class KeepaliveTracker {

    private static final String TAG = "KeepaliveTracker";
    private static final boolean DBG = false;

    public static final String PERMISSION = android.Manifest.permission.PACKET_KEEPALIVE_OFFLOAD;

    /** Keeps track of keepalive requests. */
    private final HashMap <NetworkAgentInfo, HashMap<Integer, KeepaliveInfo>> mKeepalives =
            new HashMap<> ();
    private final Handler mConnectivityServiceHandler;
    @NonNull
    private final TcpKeepaliveController mTcpController;
    @NonNull
    private final Context mContext;

    // Supported keepalive count for each transport type, can be configured through
    // config_networkSupportedKeepaliveCount. For better error handling, use
    // {@link getSupportedKeepalivesForNetworkCapabilities} instead of direct access.
    @NonNull
    private final int[] mSupportedKeepalives;

    // Reserved privileged keepalive slots per transport. Caller's permission will be enforced if
    // the number of remaining keepalive slots is less than or equal to the threshold.
    private final int mReservedPrivilegedSlots;

    // Allowed unprivileged keepalive slots per uid. Caller's permission will be enforced if
    // the number of remaining keepalive slots is less than or equal to the threshold.
    private final int mAllowedUnprivilegedSlotsForUid;

    public KeepaliveTracker(Context context, Handler handler) {
        mConnectivityServiceHandler = handler;
        mTcpController = new TcpKeepaliveController(handler);
        mContext = context;
        mSupportedKeepalives = KeepaliveUtils.getSupportedKeepalives(mContext);
        mReservedPrivilegedSlots = mContext.getResources().getInteger(
                R.integer.config_reservedPrivilegedKeepaliveSlots);
        mAllowedUnprivilegedSlotsForUid = mContext.getResources().getInteger(
                R.integer.config_allowedUnprivilegedKeepalivePerUid);
    }

    /**
     * Tracks information about a socket keepalive.
     *
     * All information about this keepalive is known at construction time except the slot number,
     * which is only returned when the hardware has successfully started the keepalive.
     */
    class KeepaliveInfo implements IBinder.DeathRecipient {
        // Bookkeeping data.
        private final ISocketKeepaliveCallback mCallback;
        private final int mUid;
        private final int mPid;
        private final boolean mPrivileged;
        private final NetworkAgentInfo mNai;
        private final int mType;
        private final FileDescriptor mFd;

        public static final int TYPE_NATT = 1;
        public static final int TYPE_TCP = 2;

        // Keepalive slot. A small integer that identifies this keepalive among the ones handled
        // by this network.
        private int mSlot = NO_KEEPALIVE;

        // Packet data.
        private final KeepalivePacketData mPacket;
        private final int mInterval;

        // Whether the keepalive is started or not. The initial state is NOT_STARTED.
        private static final int NOT_STARTED = 1;
        private static final int STARTING = 2;
        private static final int STARTED = 3;
        private static final int STOPPING = 4;
        private int mStartedState = NOT_STARTED;
        private int mStopReason = ERROR_STOP_REASON_UNINITIALIZED;

        KeepaliveInfo(@NonNull ISocketKeepaliveCallback callback,
                @NonNull NetworkAgentInfo nai,
                @NonNull KeepalivePacketData packet,
                int interval,
                int type,
                @Nullable FileDescriptor fd) throws InvalidSocketException {
            mCallback = callback;
            mPid = Binder.getCallingPid();
            mUid = Binder.getCallingUid();
            mPrivileged = (PERMISSION_GRANTED == mContext.checkPermission(PERMISSION, mPid, mUid));

            mNai = nai;
            mPacket = packet;
            mInterval = interval;
            mType = type;

            // For SocketKeepalive, a dup of fd is kept in mFd so the source port from which the
            // keepalives are sent cannot be reused by another app even if the fd gets closed by
            // the user. A null is acceptable here for backward compatibility of PacketKeepalive
            // API.
            try {
                if (fd != null) {
                    mFd = Os.dup(fd);
                }  else {
                    Log.d(TAG, toString() + " calls with null fd");
                    if (!mPrivileged) {
                        throw new SecurityException(
                                "null fd is not allowed for unprivileged access.");
                    }
                    if (mType == TYPE_TCP) {
                        throw new IllegalArgumentException(
                                "null fd is not allowed for tcp socket keepalives.");
                    }
                    mFd = null;
                }
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot dup fd: ", e);
                throw new InvalidSocketException(ERROR_INVALID_SOCKET, e);
            }

            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        public NetworkAgentInfo getNai() {
            return mNai;
        }

        private String startedStateString(final int state) {
            switch (state) {
                case NOT_STARTED : return "NOT_STARTED";
                case STARTING : return "STARTING";
                case STARTED : return "STARTED";
                case STOPPING : return "STOPPING";
            }
            throw new IllegalArgumentException("Unknown state");
        }

        public String toString() {
            return "KeepaliveInfo ["
                    + " type=" + mType
                    + " network=" + mNai.network
                    + " startedState=" + startedStateString(mStartedState)
                    + " "
                    + IpUtils.addressAndPortToString(mPacket.getSrcAddress(), mPacket.getSrcPort())
                    + "->"
                    + IpUtils.addressAndPortToString(mPacket.getDstAddress(), mPacket.getDstPort())
                    + " interval=" + mInterval
                    + " uid=" + mUid + " pid=" + mPid + " privileged=" + mPrivileged
                    + " packetData=" + HexDump.toHexString(mPacket.getPacket())
                    + " ]";
        }

        /** Called when the application process is killed. */
        public void binderDied() {
            stop(BINDER_DIED);
        }

        void unlinkDeathRecipient() {
            if (mCallback != null) {
                mCallback.asBinder().unlinkToDeath(this, 0);
            }
        }

        private int checkNetworkConnected() {
            if (!mNai.networkInfo.isConnectedOrConnecting()) {
                return ERROR_INVALID_NETWORK;
            }
            return SUCCESS;
        }

        private int checkSourceAddress() {
            // Check that we have the source address.
            for (InetAddress address : mNai.linkProperties.getAddresses()) {
                if (address.equals(mPacket.getSrcAddress())) {
                    return SUCCESS;
                }
            }
            return ERROR_INVALID_IP_ADDRESS;
        }

        private int checkInterval() {
            if (mInterval < MIN_INTERVAL_SEC || mInterval > MAX_INTERVAL_SEC) {
                return ERROR_INVALID_INTERVAL;
            }
            return SUCCESS;
        }

        private int checkPermission() {
            final HashMap<Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(mNai);
            if (networkKeepalives == null) {
                return ERROR_INVALID_NETWORK;
            }

            if (mPrivileged) return SUCCESS;

            final int supported = KeepaliveUtils.getSupportedKeepalivesForNetworkCapabilities(
                    mSupportedKeepalives, mNai.networkCapabilities);

            int takenUnprivilegedSlots = 0;
            for (final KeepaliveInfo ki : networkKeepalives.values()) {
                if (!ki.mPrivileged) ++takenUnprivilegedSlots;
            }
            if (takenUnprivilegedSlots > supported - mReservedPrivilegedSlots) {
                return ERROR_INSUFFICIENT_RESOURCES;
            }

            // Count unprivileged keepalives for the same uid across networks.
            int unprivilegedCountSameUid = 0;
            for (final HashMap<Integer, KeepaliveInfo> kaForNetwork : mKeepalives.values()) {
                for (final KeepaliveInfo ki : kaForNetwork.values()) {
                    if (ki.mUid == mUid) {
                        unprivilegedCountSameUid++;
                    }
                }
            }
            if (unprivilegedCountSameUid > mAllowedUnprivilegedSlotsForUid) {
                return ERROR_INSUFFICIENT_RESOURCES;
            }
            return SUCCESS;
        }

        private int checkLimit() {
            final HashMap<Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(mNai);
            if (networkKeepalives == null) {
                return ERROR_INVALID_NETWORK;
            }
            final int supported = KeepaliveUtils.getSupportedKeepalivesForNetworkCapabilities(
                    mSupportedKeepalives, mNai.networkCapabilities);
            if (supported == 0) return ERROR_UNSUPPORTED;
            if (networkKeepalives.size() > supported) return ERROR_INSUFFICIENT_RESOURCES;
            return SUCCESS;
        }

        private int isValid() {
            synchronized (mNai) {
                int error = checkInterval();
                if (error == SUCCESS) error = checkLimit();
                if (error == SUCCESS) error = checkPermission();
                if (error == SUCCESS) error = checkNetworkConnected();
                if (error == SUCCESS) error = checkSourceAddress();
                return error;
            }
        }

        void start(int slot) {
            mSlot = slot;
            int error = isValid();
            if (error == SUCCESS) {
                Log.d(TAG, "Starting keepalive " + mSlot + " on " + mNai.toShortString());
                switch (mType) {
                    case TYPE_NATT:
                        final NattKeepalivePacketData nattData = (NattKeepalivePacketData) mPacket;
                        mNai.onAddNattKeepalivePacketFilter(slot, nattData);
                        mNai.onStartNattSocketKeepalive(slot, mInterval, nattData);
                        break;
                    case TYPE_TCP:
                        try {
                            mTcpController.startSocketMonitor(mFd, this, mSlot);
                        } catch (InvalidSocketException e) {
                            handleStopKeepalive(mNai, mSlot, ERROR_INVALID_SOCKET);
                            return;
                        }
                        final TcpKeepalivePacketData tcpData = (TcpKeepalivePacketData) mPacket;
                        mNai.onAddTcpKeepalivePacketFilter(slot, tcpData);
                        // TODO: check result from apf and notify of failure as needed.
                        mNai.onStartTcpSocketKeepalive(slot, mInterval, tcpData);
                        break;
                    default:
                        Log.wtf(TAG, "Starting keepalive with unknown type: " + mType);
                        handleStopKeepalive(mNai, mSlot, error);
                        return;
                }
                mStartedState = STARTING;
            } else {
                handleStopKeepalive(mNai, mSlot, error);
                return;
            }
        }

        void stop(int reason) {
            int uid = Binder.getCallingUid();
            if (uid != mUid && uid != Process.SYSTEM_UID) {
                if (DBG) {
                    Log.e(TAG, "Cannot stop unowned keepalive " + mSlot + " on " + mNai.network);
                }
            }
            // Ignore the case when the network disconnects immediately after stop() has been
            // called and the keepalive code is waiting for the response from the modem. This
            // might happen when the caller listens for a lower-layer network disconnect
            // callback and stop the keepalive at that time. But the stop() races with the
            // stop() generated in ConnectivityService network disconnection code path.
            if (mStartedState == STOPPING && reason == ERROR_INVALID_NETWORK) return;

            // Store the reason of stopping, and report it after the keepalive is fully stopped.
            if (mStopReason != ERROR_STOP_REASON_UNINITIALIZED) {
                throw new IllegalStateException("Unexpected stop reason: " + mStopReason);
            }
            mStopReason = reason;
            Log.d(TAG, "Stopping keepalive " + mSlot + " on " + mNai.toShortString()
                    + ": " + reason);
            switch (mStartedState) {
                case NOT_STARTED:
                    // Remove the reference of the keepalive that meet error before starting,
                    // e.g. invalid parameter.
                    cleanupStoppedKeepalive(mNai, mSlot);
                    break;
                default:
                    mStartedState = STOPPING;
                    switch (mType) {
                        case TYPE_TCP:
                            mTcpController.stopSocketMonitor(mSlot);
                            // fall through
                        case TYPE_NATT:
                            mNai.onStopSocketKeepalive(mSlot);
                            mNai.onRemoveKeepalivePacketFilter(mSlot);
                            break;
                        default:
                            Log.wtf(TAG, "Stopping keepalive with unknown type: " + mType);
                    }
            }

            // Close the duplicated fd that maintains the lifecycle of socket whenever
            // keepalive is running.
            if (mFd != null) {
                try {
                    Os.close(mFd);
                } catch (ErrnoException e) {
                    // This should not happen since system server controls the lifecycle of fd when
                    // keepalive offload is running.
                    Log.wtf(TAG, "Error closing fd for keepalive " + mSlot + ": " + e);
                }
            }
        }

        void onFileDescriptorInitiatedStop(final int socketKeepaliveReason) {
            handleStopKeepalive(mNai, mSlot, socketKeepaliveReason);
        }
    }

    void notifyErrorCallback(ISocketKeepaliveCallback cb, int error) {
        if (DBG) Log.w(TAG, "Sending onError(" + error + ") callback");
        try {
            cb.onError(error);
        } catch (RemoteException e) {
            Log.w(TAG, "Discarded onError(" + error + ") callback");
        }
    }

    private  int findFirstFreeSlot(NetworkAgentInfo nai) {
        HashMap networkKeepalives = mKeepalives.get(nai);
        if (networkKeepalives == null) {
            networkKeepalives = new HashMap<Integer, KeepaliveInfo>();
            mKeepalives.put(nai, networkKeepalives);
        }

        // Find the lowest-numbered free slot. Slot numbers start from 1, because that's what two
        // separate chipset implementations independently came up with.
        int slot;
        for (slot = 1; slot <= networkKeepalives.size(); slot++) {
            if (networkKeepalives.get(slot) == null) {
                return slot;
            }
        }
        return slot;
    }

    public void handleStartKeepalive(Message message) {
        KeepaliveInfo ki = (KeepaliveInfo) message.obj;
        NetworkAgentInfo nai = ki.getNai();
        int slot = findFirstFreeSlot(nai);
        mKeepalives.get(nai).put(slot, ki);
        ki.start(slot);
    }

    public void handleStopAllKeepalives(NetworkAgentInfo nai, int reason) {
        final HashMap<Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(nai);
        if (networkKeepalives != null) {
            final ArrayList<KeepaliveInfo> kalist = new ArrayList(networkKeepalives.values());
            for (KeepaliveInfo ki : kalist) {
                ki.stop(reason);
                // Clean up keepalives since the network agent is disconnected and unable to pass
                // back asynchronous result of stop().
                cleanupStoppedKeepalive(nai, ki.mSlot);
            }
        }
    }

    public void handleStopKeepalive(NetworkAgentInfo nai, int slot, int reason) {
        final String networkName = NetworkAgentInfo.toShortString(nai);
        HashMap <Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(nai);
        if (networkKeepalives == null) {
            Log.e(TAG, "Attempt to stop keepalive on nonexistent network " + networkName);
            return;
        }
        KeepaliveInfo ki = networkKeepalives.get(slot);
        if (ki == null) {
            Log.e(TAG, "Attempt to stop nonexistent keepalive " + slot + " on " + networkName);
            return;
        }
        ki.stop(reason);
        // Clean up keepalives will be done as a result of calling ki.stop() after the slots are
        // freed.
    }

    private void cleanupStoppedKeepalive(NetworkAgentInfo nai, int slot) {
        final String networkName = NetworkAgentInfo.toShortString(nai);
        HashMap<Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(nai);
        if (networkKeepalives == null) {
            Log.e(TAG, "Attempt to remove keepalive on nonexistent network " + networkName);
            return;
        }
        KeepaliveInfo ki = networkKeepalives.get(slot);
        if (ki == null) {
            Log.e(TAG, "Attempt to remove nonexistent keepalive " + slot + " on " + networkName);
            return;
        }

        // Remove the keepalive from hash table so the slot can be considered available when reusing
        // it.
        networkKeepalives.remove(slot);
        Log.d(TAG, "Remove keepalive " + slot + " on " + networkName + ", "
                + networkKeepalives.size() + " remains.");
        if (networkKeepalives.isEmpty()) {
            mKeepalives.remove(nai);
        }

        // Notify app that the keepalive is stopped.
        final int reason = ki.mStopReason;
        if (reason == SUCCESS) {
            try {
                ki.mCallback.onStopped();
            } catch (RemoteException e) {
                Log.w(TAG, "Discarded onStop callback: " + reason);
            }
        } else if (reason == DATA_RECEIVED) {
            try {
                ki.mCallback.onDataReceived();
            } catch (RemoteException e) {
                Log.w(TAG, "Discarded onDataReceived callback: " + reason);
            }
        } else if (reason == ERROR_STOP_REASON_UNINITIALIZED) {
            throw new IllegalStateException("Unexpected stop reason: " + reason);
        } else {
            notifyErrorCallback(ki.mCallback, reason);
        }

        ki.unlinkDeathRecipient();
    }

    public void handleCheckKeepalivesStillValid(NetworkAgentInfo nai) {
        HashMap <Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(nai);
        if (networkKeepalives != null) {
            ArrayList<Pair<Integer, Integer>> invalidKeepalives = new ArrayList<>();
            for (int slot : networkKeepalives.keySet()) {
                int error = networkKeepalives.get(slot).isValid();
                if (error != SUCCESS) {
                    invalidKeepalives.add(Pair.create(slot, error));
                }
            }
            for (Pair<Integer, Integer> slotAndError: invalidKeepalives) {
                handleStopKeepalive(nai, slotAndError.first, slotAndError.second);
            }
        }
    }

    /** Handle keepalive events from lower layer. */
    public void handleEventSocketKeepalive(@NonNull NetworkAgentInfo nai, int slot, int reason) {
        KeepaliveInfo ki = null;
        try {
            ki = mKeepalives.get(nai).get(slot);
        } catch(NullPointerException e) {}
        if (ki == null) {
            Log.e(TAG, "Event " + NetworkAgent.EVENT_SOCKET_KEEPALIVE + "," + slot + "," + reason
                    + " for unknown keepalive " + slot + " on " + nai.toShortString());
            return;
        }

        // This can be called in a number of situations :
        // - startedState is STARTING.
        //   - reason is SUCCESS => go to STARTED.
        //   - reason isn't SUCCESS => it's an error starting. Go to NOT_STARTED and stop keepalive.
        // - startedState is STARTED.
        //   - reason is SUCCESS => it's a success stopping. Go to NOT_STARTED and stop keepalive.
        //   - reason isn't SUCCESS => it's an error in exec. Go to NOT_STARTED and stop keepalive.
        // The control is not supposed to ever come here if the state is NOT_STARTED. This is
        // because in NOT_STARTED state, the code will switch to STARTING before sending messages
        // to start, and the only way to NOT_STARTED is this function, through the edges outlined
        // above : in all cases, keepalive gets stopped and can't restart without going into
        // STARTING as messages are ordered. This also depends on the hardware processing the
        // messages in order.
        // TODO : clarify this code and get rid of mStartedState. Using a StateMachine is an
        // option.
        if (KeepaliveInfo.STARTING == ki.mStartedState) {
            if (SUCCESS == reason) {
                // Keepalive successfully started.
                Log.d(TAG, "Started keepalive " + slot + " on " + nai.toShortString());
                ki.mStartedState = KeepaliveInfo.STARTED;
                try {
                    ki.mCallback.onStarted(slot);
                } catch (RemoteException e) {
                    Log.w(TAG, "Discarded onStarted(" + slot + ") callback");
                }
            } else {
                Log.d(TAG, "Failed to start keepalive " + slot + " on " + nai.toShortString()
                        + ": " + reason);
                // The message indicated some error trying to start: do call handleStopKeepalive.
                handleStopKeepalive(nai, slot, reason);
            }
        } else if (KeepaliveInfo.STOPPING == ki.mStartedState) {
            // The message indicated result of stopping : clean up keepalive slots.
            Log.d(TAG, "Stopped keepalive " + slot + " on " + nai.toShortString()
                    + " stopped: " + reason);
            ki.mStartedState = KeepaliveInfo.NOT_STARTED;
            cleanupStoppedKeepalive(nai, slot);
        } else {
            Log.wtf(TAG, "Event " + NetworkAgent.EVENT_SOCKET_KEEPALIVE + "," + slot + "," + reason
                    + " for keepalive in wrong state: " + ki.toString());
        }
    }

    /**
     * Called when requesting that keepalives be started on a IPsec NAT-T socket. See
     * {@link android.net.SocketKeepalive}.
     **/
    public void startNattKeepalive(@Nullable NetworkAgentInfo nai,
            @Nullable FileDescriptor fd,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb,
            @NonNull String srcAddrString,
            int srcPort,
            @NonNull String dstAddrString,
            int dstPort) {
        if (nai == null) {
            notifyErrorCallback(cb, ERROR_INVALID_NETWORK);
            return;
        }

        InetAddress srcAddress, dstAddress;
        try {
            srcAddress = InetAddresses.parseNumericAddress(srcAddrString);
            dstAddress = InetAddresses.parseNumericAddress(dstAddrString);
        } catch (IllegalArgumentException e) {
            notifyErrorCallback(cb, ERROR_INVALID_IP_ADDRESS);
            return;
        }

        KeepalivePacketData packet;
        try {
            packet = NattKeepalivePacketData.nattKeepalivePacket(
                    srcAddress, srcPort, dstAddress, NATT_PORT);
        } catch (InvalidPacketException e) {
            notifyErrorCallback(cb, e.getError());
            return;
        }
        KeepaliveInfo ki = null;
        try {
            ki = new KeepaliveInfo(cb, nai, packet, intervalSeconds,
                    KeepaliveInfo.TYPE_NATT, fd);
        } catch (InvalidSocketException | IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Fail to construct keepalive", e);
            notifyErrorCallback(cb, ERROR_INVALID_SOCKET);
            return;
        }
        Log.d(TAG, "Created keepalive: " + ki.toString());
        mConnectivityServiceHandler.obtainMessage(
                NetworkAgent.CMD_START_SOCKET_KEEPALIVE, ki).sendToTarget();
    }

    /**
     * Called by ConnectivityService to start TCP keepalive on a file descriptor.
     *
     * In order to offload keepalive for application correctly, sequence number, ack number and
     * other fields are needed to form the keepalive packet. Thus, this function synchronously
     * puts the socket into repair mode to get the necessary information. After the socket has been
     * put into repair mode, the application cannot access the socket until reverted to normal.
     *
     * See {@link android.net.SocketKeepalive}.
     **/
    public void startTcpKeepalive(@Nullable NetworkAgentInfo nai,
            @NonNull FileDescriptor fd,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb) {
        if (nai == null) {
            notifyErrorCallback(cb, ERROR_INVALID_NETWORK);
            return;
        }

        final TcpKeepalivePacketData packet;
        try {
            packet = TcpKeepaliveController.getTcpKeepalivePacket(fd);
        } catch (InvalidSocketException e) {
            notifyErrorCallback(cb, e.error);
            return;
        } catch (InvalidPacketException e) {
            notifyErrorCallback(cb, e.getError());
            return;
        }
        KeepaliveInfo ki = null;
        try {
            ki = new KeepaliveInfo(cb, nai, packet, intervalSeconds,
                    KeepaliveInfo.TYPE_TCP, fd);
        } catch (InvalidSocketException | IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Fail to construct keepalive e=" + e);
            notifyErrorCallback(cb, ERROR_INVALID_SOCKET);
            return;
        }
        Log.d(TAG, "Created keepalive: " + ki.toString());
        mConnectivityServiceHandler.obtainMessage(CMD_START_SOCKET_KEEPALIVE, ki).sendToTarget();
    }

   /**
    * Called when requesting that keepalives be started on a IPsec NAT-T socket. This function is
    * identical to {@link #startNattKeepalive}, but also takes a {@code resourceId}, which is the
    * resource index bound to the {@link UdpEncapsulationSocket} when creating by
    * {@link com.android.server.IpSecService} to verify whether the given
    * {@link UdpEncapsulationSocket} is legitimate.
    **/
    public void startNattKeepalive(@Nullable NetworkAgentInfo nai,
            @Nullable FileDescriptor fd,
            int resourceId,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb,
            @NonNull String srcAddrString,
            @NonNull String dstAddrString,
            int dstPort) {
        // Ensure that the socket is created by IpSecService.
        if (!isNattKeepaliveSocketValid(fd, resourceId)) {
            notifyErrorCallback(cb, ERROR_INVALID_SOCKET);
        }

        // Get src port to adopt old API.
        int srcPort = 0;
        try {
            final SocketAddress srcSockAddr = Os.getsockname(fd);
            srcPort = ((InetSocketAddress) srcSockAddr).getPort();
        } catch (ErrnoException e) {
            notifyErrorCallback(cb, ERROR_INVALID_SOCKET);
        }

        // Forward request to old API.
        startNattKeepalive(nai, fd, intervalSeconds, cb, srcAddrString, srcPort,
                dstAddrString, dstPort);
    }

    /**
     * Verify if the IPsec NAT-T file descriptor and resource Id hold for IPsec keepalive is valid.
     **/
    public static boolean isNattKeepaliveSocketValid(@Nullable FileDescriptor fd, int resourceId) {
        // TODO: 1. confirm whether the fd is called from system api or created by IpSecService.
        //       2. If the fd is created from the system api, check that it's bounded. And
        //          call dup to keep the fd open.
        //       3. If the fd is created from IpSecService, check if the resource ID is valid. And
        //          hold the resource needed in IpSecService.
        if (null == fd) {
            return false;
        }
        return true;
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("Supported Socket keepalives: " + Arrays.toString(mSupportedKeepalives));
        pw.println("Reserved Privileged keepalives: " + mReservedPrivilegedSlots);
        pw.println("Allowed Unprivileged keepalives per uid: " + mAllowedUnprivilegedSlotsForUid);
        pw.println("Socket keepalives:");
        pw.increaseIndent();
        for (NetworkAgentInfo nai : mKeepalives.keySet()) {
            pw.println(nai.toShortString());
            pw.increaseIndent();
            for (int slot : mKeepalives.get(nai).keySet()) {
                KeepaliveInfo ki = mKeepalives.get(nai).get(slot);
                pw.println(slot + ": " + ki.toString());
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }
}
