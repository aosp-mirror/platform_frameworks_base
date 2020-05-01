/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.net.util.TetheringUtils.uint16;

import android.hardware.tetheroffload.config.V1_0.IOffloadConfig;
import android.hardware.tetheroffload.control.V1_0.IOffloadControl;
import android.hardware.tetheroffload.control.V1_0.ITetheringOffloadCallback;
import android.hardware.tetheroffload.control.V1_0.NatTimeoutUpdate;
import android.hardware.tetheroffload.control.V1_0.NetworkProtocol;
import android.hardware.tetheroffload.control.V1_0.OffloadCallbackEvent;
import android.net.netlink.NetlinkSocket;
import android.net.util.SharedLog;
import android.net.util.SocketUtils;
import android.os.Handler;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.NoSuchElementException;


/**
 * Capture tethering dependencies, for injection.
 *
 * @hide
 */
public class OffloadHardwareInterface {
    private static final String TAG = OffloadHardwareInterface.class.getSimpleName();
    private static final String YIELDS = " -> ";
    // Change this value to control whether tether offload is enabled or
    // disabled by default in the absence of an explicit Settings value.
    // See accompanying unittest to distinguish 0 from non-0 values.
    private static final int DEFAULT_TETHER_OFFLOAD_DISABLED = 0;
    private static final String NO_INTERFACE_NAME = "";
    private static final String NO_IPV4_ADDRESS = "";
    private static final String NO_IPV4_GATEWAY = "";
    // Reference kernel/uapi/linux/netfilter/nfnetlink_compat.h
    private static final int NF_NETLINK_CONNTRACK_NEW = 1;
    private static final int NF_NETLINK_CONNTRACK_UPDATE = 2;
    private static final int NF_NETLINK_CONNTRACK_DESTROY = 4;

    private final Handler mHandler;
    private final SharedLog mLog;
    private IOffloadControl mOffloadControl;
    private TetheringOffloadCallback mTetheringOffloadCallback;
    private ControlCallback mControlCallback;

    /** The callback to notify status of offload management process. */
    public static class ControlCallback {
        /** Offload started. */
        public void onStarted() {}
        /**
         * Offload stopped because an error has occurred in lower layer.
         */
        public void onStoppedError() {}
        /**
         * Offload stopped because the device has moved to a bearer on which hardware offload is
         * not supported. Subsequent calls to setUpstreamParameters and add/removeDownstream will
         * likely fail and cannot be presumed to be saved inside of the hardware management process.
         * Upon receiving #onSupportAvailable(), the caller should reprogram the hardware to begin
         * offload again.
         */
        public void onStoppedUnsupported() {}
        /** Indicate that offload is able to proivde support for this time. */
        public void onSupportAvailable() {}
        /** Offload stopped because of usage limit reached. */
        public void onStoppedLimitReached() {}

        /** Indicate to update NAT timeout. */
        public void onNatTimeoutUpdate(int proto,
                                       String srcAddr, int srcPort,
                                       String dstAddr, int dstPort) {}
    }

    /** The object which records Tx/Rx forwarded bytes. */
    public static class ForwardedStats {
        public long rxBytes;
        public long txBytes;

        public ForwardedStats() {
            rxBytes = 0;
            txBytes = 0;
        }

        @VisibleForTesting
        public ForwardedStats(long rxBytes, long txBytes) {
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }

        /** Add Tx/Rx bytes. */
        public void add(ForwardedStats other) {
            rxBytes += other.rxBytes;
            txBytes += other.txBytes;
        }

        /** Returns the string representation of this object. */
        public String toString() {
            return String.format("rx:%s tx:%s", rxBytes, txBytes);
        }
    }

    public OffloadHardwareInterface(Handler h, SharedLog log) {
        mHandler = h;
        mLog = log.forSubComponent(TAG);
    }

    /** Get default value indicating whether offload is supported. */
    public int getDefaultTetherOffloadDisabled() {
        return DEFAULT_TETHER_OFFLOAD_DISABLED;
    }

    /**
     * Offload management process need to know conntrack rules to support NAT, but it may not have
     * permission to create netlink netfilter sockets. Create two netlink netfilter sockets and
     * share them with offload management process.
     */
    public boolean initOffloadConfig() {
        IOffloadConfig offloadConfig;
        try {
            offloadConfig = IOffloadConfig.getService(true /*retry*/);
        } catch (RemoteException | NoSuchElementException e) {
            mLog.e("getIOffloadConfig error " + e);
            return false;
        }
        if (offloadConfig == null) {
            mLog.e("Could not find IOffloadConfig service");
            return false;
        }
        // Per the IConfigOffload definition:
        //
        // h1    provides a file descriptor bound to the following netlink groups
        //       (NF_NETLINK_CONNTRACK_NEW | NF_NETLINK_CONNTRACK_DESTROY).
        //
        // h2    provides a file descriptor bound to the following netlink groups
        //       (NF_NETLINK_CONNTRACK_UPDATE | NF_NETLINK_CONNTRACK_DESTROY).
        final NativeHandle h1 = createConntrackSocket(
                NF_NETLINK_CONNTRACK_NEW | NF_NETLINK_CONNTRACK_DESTROY);
        if (h1 == null) return false;

        final NativeHandle h2 = createConntrackSocket(
                NF_NETLINK_CONNTRACK_UPDATE | NF_NETLINK_CONNTRACK_DESTROY);
        if (h2 == null) {
            closeFdInNativeHandle(h1);
            return false;
        }

        final CbResults results = new CbResults();
        try {
            offloadConfig.setHandles(h1, h2,
                    (boolean success, String errMsg) -> {
                        results.mSuccess = success;
                        results.mErrMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record("initOffloadConfig, setHandles fail", e);
            return false;
        }
        // Explicitly close FDs.
        closeFdInNativeHandle(h1);
        closeFdInNativeHandle(h2);

        record("initOffloadConfig, setHandles results:", results);
        return results.mSuccess;
    }

    private void closeFdInNativeHandle(final NativeHandle h) {
        try {
            h.close();
        } catch (IOException | IllegalStateException e) {
            // IllegalStateException means fd is already closed, do nothing here.
            // Also nothing we can do if IOException.
        }
    }

    private NativeHandle createConntrackSocket(final int groups) {
        FileDescriptor fd;
        try {
            fd = NetlinkSocket.forProto(OsConstants.NETLINK_NETFILTER);
        } catch (ErrnoException e) {
            mLog.e("Unable to create conntrack socket " + e);
            return null;
        }

        final SocketAddress sockAddr = SocketUtils.makeNetlinkSocketAddress(0, groups);
        try {
            Os.bind(fd, sockAddr);
        } catch (ErrnoException | SocketException e) {
            mLog.e("Unable to bind conntrack socket for groups " + groups + " error: " + e);
            try {
                SocketUtils.closeSocket(fd);
            } catch (IOException ie) {
                // Nothing we can do here
            }
            return null;
        }
        try {
            Os.connect(fd, sockAddr);
        } catch (ErrnoException | SocketException e) {
            mLog.e("connect to kernel fail for groups " + groups + " error: " + e);
            try {
                SocketUtils.closeSocket(fd);
            } catch (IOException ie) {
                // Nothing we can do here
            }
            return null;
        }

        return new NativeHandle(fd, true);
    }

    /** Initialize the tethering offload HAL. */
    public boolean initOffloadControl(ControlCallback controlCb) {
        mControlCallback = controlCb;

        if (mOffloadControl == null) {
            try {
                mOffloadControl = IOffloadControl.getService(true /*retry*/);
            } catch (RemoteException | NoSuchElementException e) {
                mLog.e("tethering offload control not supported: " + e);
                return false;
            }
            if (mOffloadControl == null) {
                mLog.e("tethering IOffloadControl.getService() returned null");
                return false;
            }
        }

        final String logmsg = String.format("initOffloadControl(%s)",
                (controlCb == null) ? "null"
                        : "0x" + Integer.toHexString(System.identityHashCode(controlCb)));

        mTetheringOffloadCallback = new TetheringOffloadCallback(mHandler, mControlCallback, mLog);
        final CbResults results = new CbResults();
        try {
            mOffloadControl.initOffload(
                    mTetheringOffloadCallback,
                    (boolean success, String errMsg) -> {
                        results.mSuccess = success;
                        results.mErrMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.mSuccess;
    }

    /** Stop IOffloadControl. */
    public void stopOffloadControl() {
        if (mOffloadControl != null) {
            try {
                mOffloadControl.stopOffload(
                        (boolean success, String errMsg) -> {
                            if (!success) mLog.e("stopOffload failed: " + errMsg);
                        });
            } catch (RemoteException e) {
                mLog.e("failed to stopOffload: " + e);
            }
        }
        mOffloadControl = null;
        mTetheringOffloadCallback = null;
        mControlCallback = null;
        mLog.log("stopOffloadControl()");
    }

    /** Get Tx/Rx usage from last query. */
    public ForwardedStats getForwardedStats(String upstream) {
        final String logmsg = String.format("getForwardedStats(%s)",  upstream);

        final ForwardedStats stats = new ForwardedStats();
        try {
            mOffloadControl.getForwardedStats(
                    upstream,
                    (long rxBytes, long txBytes) -> {
                        stats.rxBytes = (rxBytes > 0) ? rxBytes : 0;
                        stats.txBytes = (txBytes > 0) ? txBytes : 0;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return stats;
        }

        return stats;
    }

    /** Set local prefixes to offload management process. */
    public boolean setLocalPrefixes(ArrayList<String> localPrefixes) {
        final String logmsg = String.format("setLocalPrefixes([%s])",
                String.join(",", localPrefixes));

        final CbResults results = new CbResults();
        try {
            mOffloadControl.setLocalPrefixes(localPrefixes,
                    (boolean success, String errMsg) -> {
                        results.mSuccess = success;
                        results.mErrMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.mSuccess;
    }

    /** Set data limit value to offload management process. */
    public boolean setDataLimit(String iface, long limit) {

        final String logmsg = String.format("setDataLimit(%s, %d)", iface, limit);

        final CbResults results = new CbResults();
        try {
            mOffloadControl.setDataLimit(
                    iface, limit,
                    (boolean success, String errMsg) -> {
                        results.mSuccess = success;
                        results.mErrMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.mSuccess;
    }

    /** Set upstream parameters to offload management process. */
    public boolean setUpstreamParameters(
            String iface, String v4addr, String v4gateway, ArrayList<String> v6gws) {
        iface = (iface != null) ? iface : NO_INTERFACE_NAME;
        v4addr = (v4addr != null) ? v4addr : NO_IPV4_ADDRESS;
        v4gateway = (v4gateway != null) ? v4gateway : NO_IPV4_GATEWAY;
        v6gws = (v6gws != null) ? v6gws : new ArrayList<>();

        final String logmsg = String.format("setUpstreamParameters(%s, %s, %s, [%s])",
                iface, v4addr, v4gateway, String.join(",", v6gws));

        final CbResults results = new CbResults();
        try {
            mOffloadControl.setUpstreamParameters(
                    iface, v4addr, v4gateway, v6gws,
                    (boolean success, String errMsg) -> {
                        results.mSuccess = success;
                        results.mErrMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.mSuccess;
    }

    /** Add downstream prefix to offload management process. */
    public boolean addDownstreamPrefix(String ifname, String prefix) {
        final String logmsg = String.format("addDownstreamPrefix(%s, %s)", ifname, prefix);

        final CbResults results = new CbResults();
        try {
            mOffloadControl.addDownstream(ifname, prefix,
                    (boolean success, String errMsg) -> {
                        results.mSuccess = success;
                        results.mErrMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.mSuccess;
    }

    /** Remove downstream prefix from offload management process. */
    public boolean removeDownstreamPrefix(String ifname, String prefix) {
        final String logmsg = String.format("removeDownstreamPrefix(%s, %s)", ifname, prefix);

        final CbResults results = new CbResults();
        try {
            mOffloadControl.removeDownstream(ifname, prefix,
                    (boolean success, String errMsg) -> {
                        results.mSuccess = success;
                        results.mErrMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.mSuccess;
    }

    private void record(String msg, Throwable t) {
        mLog.e(msg + YIELDS + "exception: " + t);
    }

    private void record(String msg, CbResults results) {
        final String logmsg = msg + YIELDS + results;
        if (!results.mSuccess) {
            mLog.e(logmsg);
        } else {
            mLog.log(logmsg);
        }
    }

    private static class TetheringOffloadCallback extends ITetheringOffloadCallback.Stub {
        public final Handler handler;
        public final ControlCallback controlCb;
        public final SharedLog log;

        TetheringOffloadCallback(Handler h, ControlCallback cb, SharedLog sharedLog) {
            handler = h;
            controlCb = cb;
            log = sharedLog;
        }

        @Override
        public void onEvent(int event) {
            handler.post(() -> {
                switch (event) {
                    case OffloadCallbackEvent.OFFLOAD_STARTED:
                        controlCb.onStarted();
                        break;
                    case OffloadCallbackEvent.OFFLOAD_STOPPED_ERROR:
                        controlCb.onStoppedError();
                        break;
                    case OffloadCallbackEvent.OFFLOAD_STOPPED_UNSUPPORTED:
                        controlCb.onStoppedUnsupported();
                        break;
                    case OffloadCallbackEvent.OFFLOAD_SUPPORT_AVAILABLE:
                        controlCb.onSupportAvailable();
                        break;
                    case OffloadCallbackEvent.OFFLOAD_STOPPED_LIMIT_REACHED:
                        controlCb.onStoppedLimitReached();
                        break;
                    default:
                        log.e("Unsupported OffloadCallbackEvent: " + event);
                }
            });
        }

        @Override
        public void updateTimeout(NatTimeoutUpdate params) {
            handler.post(() -> {
                controlCb.onNatTimeoutUpdate(
                        networkProtocolToOsConstant(params.proto),
                        params.src.addr, uint16(params.src.port),
                        params.dst.addr, uint16(params.dst.port));
            });
        }
    }

    private static int networkProtocolToOsConstant(int proto) {
        switch (proto) {
            case NetworkProtocol.TCP: return OsConstants.IPPROTO_TCP;
            case NetworkProtocol.UDP: return OsConstants.IPPROTO_UDP;
            default:
                // The caller checks this value and will log an error. Just make
                // sure it won't collide with valid OsContants.IPPROTO_* values.
                return -Math.abs(proto);
        }
    }

    private static class CbResults {
        boolean mSuccess;
        String mErrMsg;

        @Override
        public String toString() {
            if (mSuccess) {
                return "ok";
            } else {
                return "fail: " + mErrMsg;
            }
        }
    }
}
