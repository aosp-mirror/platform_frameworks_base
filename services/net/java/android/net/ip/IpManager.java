/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.ip;

import com.android.internal.util.MessageUtils;
import com.android.internal.util.WakeupMessage;

import android.content.Context;
import android.net.apf.ApfCapabilities;
import android.net.apf.ApfFilter;
import android.net.DhcpResults;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LinkProperties.ProvisioningChange;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.dhcp.DhcpClient;
import android.net.metrics.IpManagerEvent;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.NetlinkTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Objects;
import java.util.StringJoiner;


/**
 * IpManager
 *
 * This class provides the interface to IP-layer provisioning and maintenance
 * functionality that can be used by transport layers like Wi-Fi, Ethernet,
 * et cetera.
 *
 * [ Lifetime ]
 * IpManager is designed to be instantiated as soon as the interface name is
 * known and can be as long-lived as the class containing it (i.e. declaring
 * it "private final" is okay).
 *
 * @hide
 */
public class IpManager extends StateMachine {
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    // For message logging.
    private static final Class[] sMessageClasses = { IpManager.class, DhcpClient.class };
    private static final SparseArray<String> sWhatToString =
            MessageUtils.findMessageNames(sMessageClasses);

    /**
     * Callbacks for handling IpManager events.
     */
    public static class Callback {
        // In order to receive onPreDhcpAction(), call #withPreDhcpAction()
        // when constructing a ProvisioningConfiguration.
        //
        // Implementations of onPreDhcpAction() must call
        // IpManager#completedPreDhcpAction() to indicate that DHCP is clear
        // to proceed.
        public void onPreDhcpAction() {}
        public void onPostDhcpAction() {}

        // This is purely advisory and not an indication of provisioning
        // success or failure.  This is only here for callers that want to
        // expose DHCPv4 results to other APIs (e.g., WifiInfo#setInetAddress).
        // DHCPv4 or static IPv4 configuration failure or success can be
        // determined by whether or not the passed-in DhcpResults object is
        // null or not.
        public void onNewDhcpResults(DhcpResults dhcpResults) {}

        public void onProvisioningSuccess(LinkProperties newLp) {}
        public void onProvisioningFailure(LinkProperties newLp) {}

        // Invoked on LinkProperties changes.
        public void onLinkPropertiesChange(LinkProperties newLp) {}

        // Called when the internal IpReachabilityMonitor (if enabled) has
        // detected the loss of a critical number of required neighbors.
        public void onReachabilityLost(String logMsg) {}

        // Called when the IpManager state machine terminates.
        public void onQuit() {}

        // Install an APF program to filter incoming packets.
        public void installPacketFilter(byte[] filter) {}

        // If multicast filtering cannot be accomplished with APF, this function will be called to
        // actuate multicast filtering using another means.
        public void setFallbackMulticastFilter(boolean enabled) {}

        // Enabled/disable Neighbor Discover offload functionality. This is
        // called, for example, whenever 464xlat is being started or stopped.
        public void setNeighborDiscoveryOffload(boolean enable) {}
    }

    public static class WaitForProvisioningCallback extends Callback {
        private LinkProperties mCallbackLinkProperties;

        public LinkProperties waitForProvisioning() {
            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException e) {}
                return mCallbackLinkProperties;
            }
        }

        @Override
        public void onProvisioningSuccess(LinkProperties newLp) {
            synchronized (this) {
                mCallbackLinkProperties = newLp;
                notify();
            }
        }

        @Override
        public void onProvisioningFailure(LinkProperties newLp) {
            synchronized (this) {
                mCallbackLinkProperties = null;
                notify();
            }
        }
    }

    // Use a wrapper class to log in order to ensure complete and detailed
    // logging. This method is lighter weight than annotations/reflection
    // and has the following benefits:
    //
    //     - No invoked method can be forgotten.
    //       Any new method added to IpManager.Callback must be overridden
    //       here or it will never be called.
    //
    //     - No invoking call site can be forgotten.
    //       Centralized logging in this way means call sites don't need to
    //       remember to log, and therefore no call site can be forgotten.
    //
    //     - No variation in log format among call sites.
    //       Encourages logging of any available arguments, and all call sites
    //       are necessarily logged identically.
    //
    // TODO: Find an lighter weight approach.
    private class LoggingCallbackWrapper extends Callback {
        private static final String PREFIX = "INVOKE ";
        private Callback mCallback;

        public LoggingCallbackWrapper(Callback callback) {
            mCallback = callback;
        }

        private void log(String msg) {
            mLocalLog.log(PREFIX + msg);
        }

        @Override
        public void onPreDhcpAction() {
            mCallback.onPreDhcpAction();
            log("onPreDhcpAction()");
        }
        @Override
        public void onPostDhcpAction() {
            mCallback.onPostDhcpAction();
            log("onPostDhcpAction()");
        }
        @Override
        public void onNewDhcpResults(DhcpResults dhcpResults) {
            mCallback.onNewDhcpResults(dhcpResults);
            log("onNewDhcpResults({" + dhcpResults + "})");
        }
        @Override
        public void onProvisioningSuccess(LinkProperties newLp) {
            mCallback.onProvisioningSuccess(newLp);
            log("onProvisioningSuccess({" + newLp + "})");
        }
        @Override
        public void onProvisioningFailure(LinkProperties newLp) {
            mCallback.onProvisioningFailure(newLp);
            log("onProvisioningFailure({" + newLp + "})");
        }
        @Override
        public void onLinkPropertiesChange(LinkProperties newLp) {
            mCallback.onLinkPropertiesChange(newLp);
            log("onLinkPropertiesChange({" + newLp + "})");
        }
        @Override
        public void onReachabilityLost(String logMsg) {
            mCallback.onReachabilityLost(logMsg);
            log("onReachabilityLost(" + logMsg + ")");
        }
        @Override
        public void onQuit() {
            mCallback.onQuit();
            log("onQuit()");
        }
        @Override
        public void installPacketFilter(byte[] filter) {
            mCallback.installPacketFilter(filter);
            log("installPacketFilter(byte[" + filter.length + "])");
        }
        @Override
        public void setFallbackMulticastFilter(boolean enabled) {
            mCallback.setFallbackMulticastFilter(enabled);
            log("setFallbackMulticastFilter(" + enabled + ")");
        }
        @Override
        public void setNeighborDiscoveryOffload(boolean enable) {
            mCallback.setNeighborDiscoveryOffload(enable);
            log("setNeighborDiscoveryOffload(" + enable + ")");
        }
    }

    /**
     * This class encapsulates parameters to be passed to
     * IpManager#startProvisioning(). A defensive copy is made by IpManager
     * and the values specified herein are in force until IpManager#stop()
     * is called.
     *
     * Example use:
     *
     *     final ProvisioningConfiguration config =
     *             mIpManager.buildProvisioningConfiguration()
     *                     .withPreDhcpAction()
     *                     .withProvisioningTimeoutMs(36 * 1000)
     *                     .build();
     *     mIpManager.startProvisioning(config);
     *     ...
     *     mIpManager.stop();
     *
     * The specified provisioning configuration will only be active until
     * IpManager#stop() is called. Future calls to IpManager#startProvisioning()
     * must specify the configuration again.
     */
    public static class ProvisioningConfiguration {
        // TODO: Delete this default timeout once those callers that care are
        // fixed to pass in their preferred timeout.
        //
        // We pick 36 seconds so we can send DHCP requests at
        //
        //     t=0, t=2, t=6, t=14, t=30
        //
        // allowing for 10% jitter.
        private static final int DEFAULT_TIMEOUT_MS = 36 * 1000;

        public static class Builder {
            private ProvisioningConfiguration mConfig = new ProvisioningConfiguration();

            public Builder withoutIPv4() {
                mConfig.mEnableIPv4 = false;
                return this;
            }

            public Builder withoutIPv6() {
                mConfig.mEnableIPv6 = false;
                return this;
            }

            public Builder withoutIpReachabilityMonitor() {
                mConfig.mUsingIpReachabilityMonitor = false;
                return this;
            }

            public Builder withPreDhcpAction() {
                mConfig.mRequestedPreDhcpActionMs = DEFAULT_TIMEOUT_MS;
                return this;
            }

            public Builder withPreDhcpAction(int dhcpActionTimeoutMs) {
                mConfig.mRequestedPreDhcpActionMs = dhcpActionTimeoutMs;
                return this;
            }

            public Builder withStaticConfiguration(StaticIpConfiguration staticConfig) {
                mConfig.mStaticIpConfig = staticConfig;
                return this;
            }

            public Builder withApfCapabilities(ApfCapabilities apfCapabilities) {
                mConfig.mApfCapabilities = apfCapabilities;
                return this;
            }

            public Builder withProvisioningTimeoutMs(int timeoutMs) {
                mConfig.mProvisioningTimeoutMs = timeoutMs;
                return this;
            }

            public ProvisioningConfiguration build() {
                return new ProvisioningConfiguration(mConfig);
            }
        }

        /* package */ boolean mEnableIPv4 = true;
        /* package */ boolean mEnableIPv6 = true;
        /* package */ boolean mUsingIpReachabilityMonitor = true;
        /* package */ int mRequestedPreDhcpActionMs;
        /* package */ StaticIpConfiguration mStaticIpConfig;
        /* package */ ApfCapabilities mApfCapabilities;
        /* package */ int mProvisioningTimeoutMs = DEFAULT_TIMEOUT_MS;

        public ProvisioningConfiguration() {}

        public ProvisioningConfiguration(ProvisioningConfiguration other) {
            mEnableIPv4 = other.mEnableIPv4;
            mEnableIPv6 = other.mEnableIPv6;
            mUsingIpReachabilityMonitor = other.mUsingIpReachabilityMonitor;
            mRequestedPreDhcpActionMs = other.mRequestedPreDhcpActionMs;
            mStaticIpConfig = other.mStaticIpConfig;
            mApfCapabilities = other.mApfCapabilities;
            mProvisioningTimeoutMs = other.mProvisioningTimeoutMs;
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", getClass().getSimpleName() + "{", "}")
                    .add("mEnableIPv4: " + mEnableIPv4)
                    .add("mEnableIPv6: " + mEnableIPv6)
                    .add("mUsingIpReachabilityMonitor: " + mUsingIpReachabilityMonitor)
                    .add("mRequestedPreDhcpActionMs: " + mRequestedPreDhcpActionMs)
                    .add("mStaticIpConfig: " + mStaticIpConfig)
                    .add("mApfCapabilities: " + mApfCapabilities)
                    .add("mProvisioningTimeoutMs: " + mProvisioningTimeoutMs)
                    .toString();
        }
    }

    public static final String DUMP_ARG = "ipmanager";

    private static final int CMD_STOP = 1;
    private static final int CMD_START = 2;
    private static final int CMD_CONFIRM = 3;
    private static final int EVENT_PRE_DHCP_ACTION_COMPLETE = 4;
    // Sent by NetlinkTracker to communicate netlink events.
    private static final int EVENT_NETLINK_LINKPROPERTIES_CHANGED = 5;
    private static final int CMD_UPDATE_TCP_BUFFER_SIZES = 6;
    private static final int CMD_UPDATE_HTTP_PROXY = 7;
    private static final int CMD_SET_MULTICAST_FILTER = 8;
    private static final int EVENT_PROVISIONING_TIMEOUT = 9;
    private static final int EVENT_DHCPACTION_TIMEOUT = 10;

    private static final int MAX_LOG_RECORDS = 500;

    private static final boolean NO_CALLBACKS = false;
    private static final boolean SEND_CALLBACKS = true;

    // This must match the interface prefix in clatd.c.
    // TODO: Revert this hack once IpManager and Nat464Xlat work in concert.
    private static final String CLAT_PREFIX = "v4-";

    private final State mStoppedState = new StoppedState();
    private final State mStoppingState = new StoppingState();
    private final State mStartedState = new StartedState();

    private final String mTag;
    private final Context mContext;
    private final String mInterfaceName;
    private final String mClatInterfaceName;
    @VisibleForTesting
    protected final Callback mCallback;
    private final INetworkManagementService mNwService;
    private final NetlinkTracker mNetlinkTracker;
    private final WakeupMessage mProvisioningTimeoutAlarm;
    private final WakeupMessage mDhcpActionTimeoutAlarm;
    private final LocalLog mLocalLog;

    private NetworkInterface mNetworkInterface;

    /**
     * Non-final member variables accessed only from within our StateMachine.
     */
    private LinkProperties mLinkProperties;
    private ProvisioningConfiguration mConfiguration;
    private IpReachabilityMonitor mIpReachabilityMonitor;
    private DhcpClient mDhcpClient;
    private DhcpResults mDhcpResults;
    private String mTcpBufferSizes;
    private ProxyInfo mHttpProxy;
    private ApfFilter mApfFilter;
    private boolean mMulticastFiltering;
    private long mStartTimeMillis;

    public IpManager(Context context, String ifName, Callback callback)
                throws IllegalArgumentException {
        this(context, ifName, callback, INetworkManagementService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE)));
    }

    /**
     * An expanded constructor, useful for dependency injection.
     */
    public IpManager(Context context, String ifName, Callback callback,
            INetworkManagementService nwService) throws IllegalArgumentException {
        super(IpManager.class.getSimpleName() + "." + ifName);
        mTag = getName();

        mContext = context;
        mInterfaceName = ifName;
        mClatInterfaceName = CLAT_PREFIX + ifName;
        mCallback = new LoggingCallbackWrapper(callback);
        mNwService = nwService;

        mNetlinkTracker = new NetlinkTracker(
                mInterfaceName,
                new NetlinkTracker.Callback() {
                    @Override
                    public void update() {
                        sendMessage(EVENT_NETLINK_LINKPROPERTIES_CHANGED);
                    }
                }) {
            @Override
            public void interfaceAdded(String iface) {
                super.interfaceAdded(iface);
                if (mClatInterfaceName.equals(iface)) {
                    mCallback.setNeighborDiscoveryOffload(false);
                }
            }

            @Override
            public void interfaceRemoved(String iface) {
                super.interfaceRemoved(iface);
                if (mClatInterfaceName.equals(iface)) {
                    // TODO: consider sending a message to the IpManager main
                    // StateMachine thread, in case "NDO enabled" state becomes
                    // tied to more things that 464xlat operation.
                    mCallback.setNeighborDiscoveryOffload(true);
                }
            }
        };

        try {
            mNwService.registerObserver(mNetlinkTracker);
        } catch (RemoteException e) {
            Log.e(mTag, "Couldn't register NetlinkTracker: " + e.toString());
        }

        resetLinkProperties();

        mProvisioningTimeoutAlarm = new WakeupMessage(mContext, getHandler(),
                mTag + ".EVENT_PROVISIONING_TIMEOUT", EVENT_PROVISIONING_TIMEOUT);
        mDhcpActionTimeoutAlarm = new WakeupMessage(mContext, getHandler(),
                mTag + ".EVENT_DHCPACTION_TIMEOUT", EVENT_DHCPACTION_TIMEOUT);

        // Super simple StateMachine.
        addState(mStoppedState);
        addState(mStartedState);
        addState(mStoppingState);

        setInitialState(mStoppedState);
        mLocalLog = new LocalLog(MAX_LOG_RECORDS);
        super.start();
    }

    @Override
    protected void onQuitting() {
        mCallback.onQuit();
    }

    // Shut down this IpManager instance altogether.
    public void shutdown() {
        stop();
        quit();
    }

    public static ProvisioningConfiguration.Builder buildProvisioningConfiguration() {
        return new ProvisioningConfiguration.Builder();
    }

    public void startProvisioning(ProvisioningConfiguration req) {
        getNetworkInterface();

        mCallback.setNeighborDiscoveryOffload(true);
        sendMessage(CMD_START, new ProvisioningConfiguration(req));
    }

    // TODO: Delete this.
    public void startProvisioning(StaticIpConfiguration staticIpConfig) {
        startProvisioning(buildProvisioningConfiguration()
                .withStaticConfiguration(staticIpConfig)
                .build());
    }

    public void startProvisioning() {
        startProvisioning(new ProvisioningConfiguration());
    }

    public void stop() {
        sendMessage(CMD_STOP);
    }

    public void confirmConfiguration() {
        sendMessage(CMD_CONFIRM);
    }

    public void completedPreDhcpAction() {
        sendMessage(EVENT_PRE_DHCP_ACTION_COMPLETE);
    }

    /**
     * Set the TCP buffer sizes to use.
     *
     * This may be called, repeatedly, at any time before or after a call to
     * #startProvisioning(). The setting is cleared upon calling #stop().
     */
    public void setTcpBufferSizes(String tcpBufferSizes) {
        sendMessage(CMD_UPDATE_TCP_BUFFER_SIZES, tcpBufferSizes);
    }

    /**
     * Set the HTTP Proxy configuration to use.
     *
     * This may be called, repeatedly, at any time before or after a call to
     * #startProvisioning(). The setting is cleared upon calling #stop().
     */
    public void setHttpProxy(ProxyInfo proxyInfo) {
        sendMessage(CMD_UPDATE_HTTP_PROXY, proxyInfo);
    }

    /**
     * Enable or disable the multicast filter.  Attempts to use APF to accomplish the filtering,
     * if not, Callback.setFallbackMulticastFilter() is called.
     */
    public void setMulticastFilter(boolean enabled) {
        sendMessage(CMD_SET_MULTICAST_FILTER, enabled);
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        pw.println("APF dump:");
        pw.increaseIndent();
        // Thread-unsafe access to mApfFilter but just used for debugging.
        ApfFilter apfFilter = mApfFilter;
        if (apfFilter != null) {
            apfFilter.dump(pw);
        } else {
            pw.println("No apf support");
        }
        pw.decreaseIndent();

        pw.println();
        pw.println("StateMachine dump:");
        pw.increaseIndent();
        mLocalLog.readOnlyLocalLog().dump(fd, pw, args);
        pw.decreaseIndent();
    }


    /**
     * Internals.
     */

    @Override
    protected String getWhatToString(int what) {
        return sWhatToString.get(what, "UNKNOWN: " + Integer.toString(what));
    }

    @Override
    protected String getLogRecString(Message msg) {
        final String logLine = String.format(
                "%s/%d %d %d %s",
                mInterfaceName, mNetworkInterface == null ? -1 : mNetworkInterface.getIndex(),
                msg.arg1, msg.arg2, Objects.toString(msg.obj));

        final String richerLogLine = getWhatToString(msg.what) + " " + logLine;
        mLocalLog.log(richerLogLine);
        if (VDBG) {
            Log.d(mTag, richerLogLine);
        }

        return logLine;
    }

    @Override
    protected boolean recordLogRec(Message msg) {
        // Don't log EVENT_NETLINK_LINKPROPERTIES_CHANGED. They can be noisy,
        // and we already log any LinkProperties change that results in an
        // invocation of IpManager.Callback#onLinkPropertiesChange().
        return (msg.what != EVENT_NETLINK_LINKPROPERTIES_CHANGED);
    }

    private void getNetworkInterface() {
        try {
            mNetworkInterface = NetworkInterface.getByName(mInterfaceName);
        } catch (SocketException | NullPointerException e) {
            // TODO: throw new IllegalStateException.
            Log.e(mTag, "ALERT: Failed to get interface object: ", e);
        }
    }

    // This needs to be called with care to ensure that our LinkProperties
    // are in sync with the actual LinkProperties of the interface. For example,
    // we should only call this if we know for sure that there are no IP addresses
    // assigned to the interface, etc.
    private void resetLinkProperties() {
        mNetlinkTracker.clearLinkProperties();
        mConfiguration = null;
        mDhcpResults = null;
        mTcpBufferSizes = "";
        mHttpProxy = null;

        mLinkProperties = new LinkProperties();
        mLinkProperties.setInterfaceName(mInterfaceName);
    }

    private void recordMetric(final int type) {
        if (mStartTimeMillis <= 0) { Log.wtf(mTag, "Start time undefined!"); }
        IpManagerEvent.logEvent(type, mInterfaceName,
                SystemClock.elapsedRealtime() - mStartTimeMillis);
    }

    // For now: use WifiStateMachine's historical notion of provisioned.
    private static boolean isProvisioned(LinkProperties lp) {
        // For historical reasons, we should connect even if all we have is
        // an IPv4 address and nothing else.
        return lp.isProvisioned() || lp.hasIPv4Address();
    }

    // TODO: Investigate folding all this into the existing static function
    // LinkProperties.compareProvisioning() or some other single function that
    // takes two LinkProperties objects and returns a ProvisioningChange
    // object that is a correct and complete assessment of what changed, taking
    // account of the asymmetries described in the comments in this function.
    // Then switch to using it everywhere (IpReachabilityMonitor, etc.).
    private static ProvisioningChange compareProvisioning(
            LinkProperties oldLp, LinkProperties newLp) {
        ProvisioningChange delta;

        final boolean wasProvisioned = isProvisioned(oldLp);
        final boolean isProvisioned = isProvisioned(newLp);

        if (!wasProvisioned && isProvisioned) {
            delta = ProvisioningChange.GAINED_PROVISIONING;
        } else if (wasProvisioned && isProvisioned) {
            delta = ProvisioningChange.STILL_PROVISIONED;
        } else if (!wasProvisioned && !isProvisioned) {
            delta = ProvisioningChange.STILL_NOT_PROVISIONED;
        } else {
            // (wasProvisioned && !isProvisioned)
            //
            // Note that this is true even if we lose a configuration element
            // (e.g., a default gateway) that would not be required to advance
            // into provisioned state. This is intended: if we have a default
            // router and we lose it, that's a sure sign of a problem, but if
            // we connect to a network with no IPv4 DNS servers, we consider
            // that to be a network without DNS servers and connect anyway.
            //
            // See the comment below.
            delta = ProvisioningChange.LOST_PROVISIONING;
        }

        // Additionally:
        //
        // Partial configurations (e.g., only an IPv4 address with no DNS
        // servers and no default route) are accepted as long as DHCPv4
        // succeeds. On such a network, isProvisioned() will always return
        // false, because the configuration is not complete, but we want to
        // connect anyway. It might be a disconnected network such as a
        // Chromecast or a wireless printer, for example.
        //
        // Because on such a network isProvisioned() will always return false,
        // delta will never be LOST_PROVISIONING. So check for loss of
        // provisioning here too.
        if ((oldLp.hasIPv4Address() && !newLp.hasIPv4Address()) ||
                (oldLp.isIPv6Provisioned() && !newLp.isIPv6Provisioned())) {
            delta = ProvisioningChange.LOST_PROVISIONING;
        }

        // Additionally:
        //
        // If the previous link properties had a global IPv6 address and an
        // IPv6 default route then also consider the loss of that default route
        // to be a loss of provisioning. See b/27962810.
        if (oldLp.hasGlobalIPv6Address() && oldLp.hasIPv6DefaultRoute() &&
                !newLp.hasIPv6DefaultRoute()) {
            delta = ProvisioningChange.LOST_PROVISIONING;
        }

        return delta;
    }

    private void dispatchCallback(ProvisioningChange delta, LinkProperties newLp) {
        switch (delta) {
            case GAINED_PROVISIONING:
                if (VDBG) { Log.d(mTag, "onProvisioningSuccess()"); }
                recordMetric(IpManagerEvent.PROVISIONING_OK);
                mCallback.onProvisioningSuccess(newLp);
                break;

            case LOST_PROVISIONING:
                if (VDBG) { Log.d(mTag, "onProvisioningFailure()"); }
                recordMetric(IpManagerEvent.PROVISIONING_FAIL);
                mCallback.onProvisioningFailure(newLp);
                break;

            default:
                if (VDBG) { Log.d(mTag, "onLinkPropertiesChange()"); }
                mCallback.onLinkPropertiesChange(newLp);
                break;
        }
    }

    // Updates all IpManager-related state concerned with LinkProperties.
    // Returns a ProvisioningChange for possibly notifying other interested
    // parties that are not fronted by IpManager.
    private ProvisioningChange setLinkProperties(LinkProperties newLp) {
        if (mApfFilter != null) {
            mApfFilter.setLinkProperties(newLp);
        }
        if (mIpReachabilityMonitor != null) {
            mIpReachabilityMonitor.updateLinkProperties(newLp);
        }

        ProvisioningChange delta = compareProvisioning(mLinkProperties, newLp);
        mLinkProperties = new LinkProperties(newLp);

        if (delta == ProvisioningChange.GAINED_PROVISIONING) {
            // TODO: Add a proper ProvisionedState and cancel the alarm in
            // its enter() method.
            mProvisioningTimeoutAlarm.cancel();
        }

        return delta;
    }

    private boolean linkPropertiesUnchanged(LinkProperties newLp) {
        return Objects.equals(newLp, mLinkProperties);
    }

    private LinkProperties assembleLinkProperties() {
        // [1] Create a new LinkProperties object to populate.
        LinkProperties newLp = new LinkProperties();
        newLp.setInterfaceName(mInterfaceName);

        // [2] Pull in data from netlink:
        //         - IPv4 addresses
        //         - IPv6 addresses
        //         - IPv6 routes
        //         - IPv6 DNS servers
        LinkProperties netlinkLinkProperties = mNetlinkTracker.getLinkProperties();
        newLp.setLinkAddresses(netlinkLinkProperties.getLinkAddresses());
        for (RouteInfo route : netlinkLinkProperties.getRoutes()) {
            newLp.addRoute(route);
        }
        for (InetAddress dns : netlinkLinkProperties.getDnsServers()) {
            // Only add likely reachable DNS servers.
            // TODO: investigate deleting this.
            if (newLp.isReachable(dns)) {
                newLp.addDnsServer(dns);
            }
        }

        // [3] Add in data from DHCPv4, if available.
        //
        // mDhcpResults is never shared with any other owner so we don't have
        // to worry about concurrent modification.
        if (mDhcpResults != null) {
            for (RouteInfo route : mDhcpResults.getRoutes(mInterfaceName)) {
                newLp.addRoute(route);
            }
            for (InetAddress dns : mDhcpResults.dnsServers) {
                // Only add likely reachable DNS servers.
                // TODO: investigate deleting this.
                if (newLp.isReachable(dns)) {
                    newLp.addDnsServer(dns);
                }
            }
            newLp.setDomains(mDhcpResults.domains);

            if (mDhcpResults.mtu != 0) {
                newLp.setMtu(mDhcpResults.mtu);
            }
        }

        // [4] Add in TCP buffer sizes and HTTP Proxy config, if available.
        if (!TextUtils.isEmpty(mTcpBufferSizes)) {
            newLp.setTcpBufferSizes(mTcpBufferSizes);
        }
        if (mHttpProxy != null) {
            newLp.setHttpProxy(mHttpProxy);
        }

        if (VDBG) {
            Log.d(mTag, "newLp{" + newLp + "}");
        }
        return newLp;
    }

    // Returns false if we have lost provisioning, true otherwise.
    private boolean handleLinkPropertiesUpdate(boolean sendCallbacks) {
        final LinkProperties newLp = assembleLinkProperties();
        if (linkPropertiesUnchanged(newLp)) {
            return true;
        }
        final ProvisioningChange delta = setLinkProperties(newLp);
        if (sendCallbacks) {
            dispatchCallback(delta, newLp);
        }
        return (delta != ProvisioningChange.LOST_PROVISIONING);
    }

    private boolean setIPv4Address(LinkAddress address) {
        final InterfaceConfiguration ifcg = new InterfaceConfiguration();
        ifcg.setLinkAddress(address);
        try {
            mNwService.setInterfaceConfig(mInterfaceName, ifcg);
            if (VDBG) Log.d(mTag, "IPv4 configuration succeeded");
        } catch (IllegalStateException | RemoteException e) {
            Log.e(mTag, "IPv4 configuration failed: ", e);
            return false;
        }
        return true;
    }

    private void clearIPv4Address() {
        try {
            final InterfaceConfiguration ifcg = new InterfaceConfiguration();
            ifcg.setLinkAddress(new LinkAddress("0.0.0.0/0"));
            mNwService.setInterfaceConfig(mInterfaceName, ifcg);
        } catch (IllegalStateException | RemoteException e) {
            Log.e(mTag, "ALERT: Failed to clear IPv4 address on interface " + mInterfaceName, e);
        }
    }

    private void handleIPv4Success(DhcpResults dhcpResults) {
        mDhcpResults = new DhcpResults(dhcpResults);
        final LinkProperties newLp = assembleLinkProperties();
        final ProvisioningChange delta = setLinkProperties(newLp);

        if (VDBG) {
            Log.d(mTag, "onNewDhcpResults(" + Objects.toString(dhcpResults) + ")");
        }
        mCallback.onNewDhcpResults(dhcpResults);
        dispatchCallback(delta, newLp);
    }

    private void handleIPv4Failure() {
        // TODO: Investigate deleting this clearIPv4Address() call.
        //
        // DhcpClient will send us CMD_CLEAR_LINKADDRESS in all circumstances
        // that could trigger a call to this function. If we missed handling
        // that message in StartedState for some reason we would still clear
        // any addresses upon entry to StoppedState.
        clearIPv4Address();
        mDhcpResults = null;
        if (VDBG) { Log.d(mTag, "onNewDhcpResults(null)"); }
        mCallback.onNewDhcpResults(null);

        handleProvisioningFailure();
    }

    private void handleProvisioningFailure() {
        final LinkProperties newLp = assembleLinkProperties();
        ProvisioningChange delta = setLinkProperties(newLp);
        // If we've gotten here and we're still not provisioned treat that as
        // a total loss of provisioning.
        //
        // Either (a) static IP configuration failed or (b) DHCPv4 failed AND
        // there was no usable IPv6 obtained before a non-zero provisioning
        // timeout expired.
        //
        // Regardless: GAME OVER.
        if (delta == ProvisioningChange.STILL_NOT_PROVISIONED) {
            delta = ProvisioningChange.LOST_PROVISIONING;
        }

        dispatchCallback(delta, newLp);
        if (delta == ProvisioningChange.LOST_PROVISIONING) {
            transitionTo(mStoppingState);
        }
    }

    private boolean startIPv4() {
        // If we have a StaticIpConfiguration attempt to apply it and
        // handle the result accordingly.
        if (mConfiguration.mStaticIpConfig != null) {
            if (setIPv4Address(mConfiguration.mStaticIpConfig.ipAddress)) {
                handleIPv4Success(new DhcpResults(mConfiguration.mStaticIpConfig));
            } else {
                if (VDBG) { Log.d(mTag, "onProvisioningFailure()"); }
                recordMetric(IpManagerEvent.PROVISIONING_FAIL);
                mCallback.onProvisioningFailure(new LinkProperties(mLinkProperties));
                return false;
            }
        } else {
            // Start DHCPv4.
            mDhcpClient = DhcpClient.makeDhcpClient(mContext, IpManager.this, mInterfaceName);
            mDhcpClient.registerForPreDhcpNotification();
            mDhcpClient.sendMessage(DhcpClient.CMD_START_DHCP);

            if (mConfiguration.mProvisioningTimeoutMs > 0) {
                final long alarmTime = SystemClock.elapsedRealtime() +
                        mConfiguration.mProvisioningTimeoutMs;
                mProvisioningTimeoutAlarm.schedule(alarmTime);
            }
        }

        return true;
    }

    private boolean startIPv6() {
        // Set privacy extensions.
        try {
            mNwService.setInterfaceIpv6PrivacyExtensions(mInterfaceName, true);
            mNwService.enableIpv6(mInterfaceName);
        } catch (RemoteException re) {
            Log.e(mTag, "Unable to change interface settings: " + re);
            return false;
        } catch (IllegalStateException ie) {
            Log.e(mTag, "Unable to change interface settings: " + ie);
            return false;
        }

        return true;
    }


    class StoppedState extends State {
        @Override
        public void enter() {
            try {
                mNwService.disableIpv6(mInterfaceName);
                mNwService.clearInterfaceAddresses(mInterfaceName);
            } catch (Exception e) {
                Log.e(mTag, "Failed to clear addresses or disable IPv6" + e);
            }

            resetLinkProperties();
            if (mStartTimeMillis > 0) {
                recordMetric(IpManagerEvent.COMPLETE_LIFECYCLE);
                mStartTimeMillis = 0;
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_STOP:
                    break;

                case CMD_START:
                    mConfiguration = (ProvisioningConfiguration) msg.obj;
                    transitionTo(mStartedState);
                    break;

                case EVENT_NETLINK_LINKPROPERTIES_CHANGED:
                    handleLinkPropertiesUpdate(NO_CALLBACKS);
                    break;

                case CMD_UPDATE_TCP_BUFFER_SIZES:
                    mTcpBufferSizes = (String) msg.obj;
                    handleLinkPropertiesUpdate(NO_CALLBACKS);
                    break;

                case CMD_UPDATE_HTTP_PROXY:
                    mHttpProxy = (ProxyInfo) msg.obj;
                    handleLinkPropertiesUpdate(NO_CALLBACKS);
                    break;

                case CMD_SET_MULTICAST_FILTER:
                    mMulticastFiltering = (boolean) msg.obj;
                    break;

                case DhcpClient.CMD_ON_QUIT:
                    // Everything is already stopped.
                    Log.e(mTag, "Unexpected CMD_ON_QUIT (already stopped).");
                    break;

                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class StoppingState extends State {
        @Override
        public void enter() {
            if (mDhcpClient == null) {
                // There's no DHCPv4 for which to wait; proceed to stopped.
                transitionTo(mStoppedState);
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case DhcpClient.CMD_ON_QUIT:
                    mDhcpClient = null;
                    transitionTo(mStoppedState);
                    break;

                default:
                    deferMessage(msg);
            }
            return HANDLED;
        }
    }

    class StartedState extends State {
        private boolean mDhcpActionInFlight;

        @Override
        public void enter() {
            mStartTimeMillis = SystemClock.elapsedRealtime();

            mApfFilter = ApfFilter.maybeCreate(mConfiguration.mApfCapabilities, mNetworkInterface,
                    mCallback, mMulticastFiltering);
            // TODO: investigate the effects of any multicast filtering racing/interfering with the
            // rest of this IP configuration startup.
            if (mApfFilter == null) {
                mCallback.setFallbackMulticastFilter(mMulticastFiltering);
            }

            if (mConfiguration.mEnableIPv6) {
                // TODO: Consider transitionTo(mStoppingState) if this fails.
                startIPv6();
            }

            if (mConfiguration.mUsingIpReachabilityMonitor) {
                mIpReachabilityMonitor = new IpReachabilityMonitor(
                        mContext,
                        mInterfaceName,
                        new IpReachabilityMonitor.Callback() {
                            @Override
                            public void notifyLost(InetAddress ip, String logMsg) {
                                mCallback.onReachabilityLost(logMsg);
                            }
                        });
            }

            if (mConfiguration.mEnableIPv4) {
                if (!startIPv4()) {
                    transitionTo(mStoppingState);
                }
            }
        }

        @Override
        public void exit() {
            mProvisioningTimeoutAlarm.cancel();
            stopDhcpAction();

            if (mIpReachabilityMonitor != null) {
                mIpReachabilityMonitor.stop();
                mIpReachabilityMonitor = null;
            }

            if (mDhcpClient != null) {
                mDhcpClient.sendMessage(DhcpClient.CMD_STOP_DHCP);
                mDhcpClient.doQuit();
            }

            if (mApfFilter != null) {
                mApfFilter.shutdown();
                mApfFilter = null;
            }

            resetLinkProperties();
        }

        private void ensureDhcpAction() {
            if (!mDhcpActionInFlight) {
                mCallback.onPreDhcpAction();
                mDhcpActionInFlight = true;
                final long alarmTime = SystemClock.elapsedRealtime() +
                        mConfiguration.mRequestedPreDhcpActionMs;
                mDhcpActionTimeoutAlarm.schedule(alarmTime);
            }
        }

        private void stopDhcpAction() {
            mDhcpActionTimeoutAlarm.cancel();
            if (mDhcpActionInFlight) {
                mCallback.onPostDhcpAction();
                mDhcpActionInFlight = false;
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_STOP:
                    transitionTo(mStoppingState);
                    break;

                case CMD_START:
                    Log.e(mTag, "ALERT: START received in StartedState. Please fix caller.");
                    break;

                case CMD_CONFIRM:
                    // TODO: Possibly introduce a second type of confirmation
                    // that both probes (a) on-link neighbors and (b) does
                    // a DHCPv4 RENEW.  We used to do this on Wi-Fi framework
                    // roams.
                    if (mIpReachabilityMonitor != null) {
                        mIpReachabilityMonitor.probeAll();
                    }
                    break;

                case EVENT_PRE_DHCP_ACTION_COMPLETE:
                    // It's possible to reach here if, for example, someone
                    // calls completedPreDhcpAction() after provisioning with
                    // a static IP configuration.
                    if (mDhcpClient != null) {
                        mDhcpClient.sendMessage(DhcpClient.CMD_PRE_DHCP_ACTION_COMPLETE);
                    }
                    break;

                case EVENT_NETLINK_LINKPROPERTIES_CHANGED:
                    if (!handleLinkPropertiesUpdate(SEND_CALLBACKS)) {
                        transitionTo(mStoppingState);
                    }
                    break;

                case CMD_UPDATE_TCP_BUFFER_SIZES:
                    mTcpBufferSizes = (String) msg.obj;
                    // This cannot possibly change provisioning state.
                    handleLinkPropertiesUpdate(SEND_CALLBACKS);
                    break;

                case CMD_UPDATE_HTTP_PROXY:
                    mHttpProxy = (ProxyInfo) msg.obj;
                    // This cannot possibly change provisioning state.
                    handleLinkPropertiesUpdate(SEND_CALLBACKS);
                    break;

                case CMD_SET_MULTICAST_FILTER: {
                    mMulticastFiltering = (boolean) msg.obj;
                    if (mApfFilter != null) {
                        mApfFilter.setMulticastFilter(mMulticastFiltering);
                    } else {
                        mCallback.setFallbackMulticastFilter(mMulticastFiltering);
                    }
                    break;
                }

                case EVENT_PROVISIONING_TIMEOUT:
                    handleProvisioningFailure();
                    break;

                case EVENT_DHCPACTION_TIMEOUT:
                    stopDhcpAction();
                    break;

                case DhcpClient.CMD_PRE_DHCP_ACTION:
                    if (mConfiguration.mRequestedPreDhcpActionMs > 0) {
                        ensureDhcpAction();
                    } else {
                        sendMessage(EVENT_PRE_DHCP_ACTION_COMPLETE);
                    }
                    break;

                case DhcpClient.CMD_CLEAR_LINKADDRESS:
                    clearIPv4Address();
                    break;

                case DhcpClient.CMD_CONFIGURE_LINKADDRESS: {
                    final LinkAddress ipAddress = (LinkAddress) msg.obj;
                    if (setIPv4Address(ipAddress)) {
                        mDhcpClient.sendMessage(DhcpClient.EVENT_LINKADDRESS_CONFIGURED);
                    } else {
                        Log.e(mTag, "Failed to set IPv4 address!");
                        dispatchCallback(ProvisioningChange.LOST_PROVISIONING,
                                new LinkProperties(mLinkProperties));
                        transitionTo(mStoppingState);
                    }
                    break;
                }

                // This message is only received when:
                //
                //     a) initial address acquisition succeeds,
                //     b) renew succeeds or is NAK'd,
                //     c) rebind succeeds or is NAK'd, or
                //     c) the lease expires,
                //
                // but never when initial address acquisition fails. The latter
                // condition is now governed by the provisioning timeout.
                case DhcpClient.CMD_POST_DHCP_ACTION:
                    stopDhcpAction();

                    switch (msg.arg1) {
                        case DhcpClient.DHCP_SUCCESS:
                            handleIPv4Success((DhcpResults) msg.obj);
                            break;
                        case DhcpClient.DHCP_FAILURE:
                            handleIPv4Failure();
                            break;
                        default:
                            Log.e(mTag, "Unknown CMD_POST_DHCP_ACTION status:" + msg.arg1);
                    }
                    break;

                case DhcpClient.CMD_ON_QUIT:
                    // DHCPv4 quit early for some reason.
                    Log.e(mTag, "Unexpected CMD_ON_QUIT.");
                    mDhcpClient = null;
                    break;

                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }
}
