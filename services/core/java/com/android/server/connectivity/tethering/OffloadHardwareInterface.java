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

package com.android.server.connectivity.tethering;

import static com.android.internal.util.BitUtils.uint16;

import android.hardware.tetheroffload.control.V1_0.IOffloadControl;
import android.hardware.tetheroffload.control.V1_0.ITetheringOffloadCallback;
import android.hardware.tetheroffload.control.V1_0.NatTimeoutUpdate;
import android.hardware.tetheroffload.control.V1_0.NetworkProtocol;
import android.hardware.tetheroffload.control.V1_0.OffloadCallbackEvent;
import android.os.Handler;
import android.os.RemoteException;
import android.net.util.SharedLog;
import android.system.OsConstants;

import java.util.ArrayList;


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

    private static native boolean configOffload();

    private final Handler mHandler;
    private final SharedLog mLog;
    private IOffloadControl mOffloadControl;
    private TetheringOffloadCallback mTetheringOffloadCallback;
    private ControlCallback mControlCallback;

    public static class ControlCallback {
        public void onStarted() {}
        public void onStoppedError() {}
        public void onStoppedUnsupported() {}
        public void onSupportAvailable() {}
        public void onStoppedLimitReached() {}

        public void onNatTimeoutUpdate(int proto,
                                       String srcAddr, int srcPort,
                                       String dstAddr, int dstPort) {}
    }

    public static class ForwardedStats {
        public long rxBytes;
        public long txBytes;

        public ForwardedStats() {
            rxBytes = 0;
            txBytes = 0;
        }

        public void add(ForwardedStats other) {
            rxBytes += other.rxBytes;
            txBytes += other.txBytes;
        }

        public String toString() {
            return String.format("rx:%s tx:%s", rxBytes, txBytes);
        }
    }

    public OffloadHardwareInterface(Handler h, SharedLog log) {
        mHandler = h;
        mLog = log.forSubComponent(TAG);
    }

    public int getDefaultTetherOffloadDisabled() {
        return DEFAULT_TETHER_OFFLOAD_DISABLED;
    }

    public boolean initOffloadConfig() {
        return configOffload();
    }

    public boolean initOffloadControl(ControlCallback controlCb) {
        mControlCallback = controlCb;

        if (mOffloadControl == null) {
            try {
                mOffloadControl = IOffloadControl.getService();
            } catch (RemoteException e) {
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
                        results.success = success;
                        results.errMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.success;
    }

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

        mLog.log(logmsg + YIELDS + stats);
        return stats;
    }

    public boolean setLocalPrefixes(ArrayList<String> localPrefixes) {
        final String logmsg = String.format("setLocalPrefixes([%s])",
                String.join(",", localPrefixes));

        final CbResults results = new CbResults();
        try {
            mOffloadControl.setLocalPrefixes(localPrefixes,
                    (boolean success, String errMsg) -> {
                        results.success = success;
                        results.errMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.success;
    }

    public boolean setDataLimit(String iface, long limit) {

        final String logmsg = String.format("setDataLimit(%s, %d)", iface, limit);

        final CbResults results = new CbResults();
        try {
            mOffloadControl.setDataLimit(
                    iface, limit,
                    (boolean success, String errMsg) -> {
                        results.success = success;
                        results.errMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.success;
    }

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
                        results.success = success;
                        results.errMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.success;
    }

    public boolean addDownstreamPrefix(String ifname, String prefix) {
        final String logmsg = String.format("addDownstreamPrefix(%s, %s)", ifname, prefix);

        final CbResults results = new CbResults();
        try {
            mOffloadControl.addDownstream(ifname, prefix,
                    (boolean success, String errMsg) -> {
                        results.success = success;
                        results.errMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.success;
    }

    public boolean removeDownstreamPrefix(String ifname, String prefix) {
        final String logmsg = String.format("removeDownstreamPrefix(%s, %s)", ifname, prefix);

        final CbResults results = new CbResults();
        try {
            mOffloadControl.removeDownstream(ifname, prefix,
                    (boolean success, String errMsg) -> {
                        results.success = success;
                        results.errMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.success;
    }

    private void record(String msg, Throwable t) {
        mLog.e(msg + YIELDS + "exception: " + t);
    }

    private void record(String msg, CbResults results) {
        final String logmsg = msg + YIELDS + results;
        if (!results.success) {
            mLog.e(logmsg);
        } else {
            mLog.log(logmsg);
        }
    }

    private static class TetheringOffloadCallback extends ITetheringOffloadCallback.Stub {
        public final Handler handler;
        public final ControlCallback controlCb;
        public final SharedLog log;

        public TetheringOffloadCallback(Handler h, ControlCallback cb, SharedLog sharedLog) {
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
        boolean success;
        String errMsg;

        public String toString() {
            if (success) {
                return "ok";
            } else {
                return "fail: " + errMsg;
            }
        }
    }
}
