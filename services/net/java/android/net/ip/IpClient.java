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

package android.net.ip;

import com.android.internal.util.HexDump;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.WakeupMessage;

import android.content.Context;
import android.net.DhcpResults;
import android.net.INetd;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties.ProvisioningChange;
import android.net.LinkProperties;
import android.net.Network;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.apf.ApfCapabilities;
import android.net.apf.ApfFilter;
import android.net.dhcp.DhcpClient;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.IpManagerEvent;
import android.net.util.InterfaceParams;
import android.net.util.MultinetworkPolicyTracker;
import android.net.util.NetdService;
import android.net.util.NetworkConstants;
import android.net.util.SharedLog;
import android.os.ConditionVariable;
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
import com.android.internal.R;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.IState;
import com.android.internal.util.Preconditions;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.NetlinkTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import java.util.stream.Collectors;


/**
 * IpClient
 *
 * This class provides the interface to IP-layer provisioning and maintenance
 * functionality that can be used by transport layers like Wi-Fi, Ethernet,
 * et cetera.
 *
 * [ Lifetime ]
 * IpClient is designed to be instantiated as soon as the interface name is
 * known and can be as long-lived as the class containing it (i.e. declaring
 * it "private final" is okay).
 *
 * @hide
 */
public class IpClient extends StateMachine {
    private static final boolean DBG = false;

    // For message logging.
    private static final Class[] sMessageClasses = { IpClient.class, DhcpClient.class };
    private static final SparseArray<String> sWhatToString =
            MessageUtils.findMessageNames(sMessageClasses);

    /**
     * Callbacks for handling IpClient events.
     *
     * These methods are called by IpClient on its own thread. Implementations
     * of this class MUST NOT carry out long-running computations or hold locks
     * for which there might be contention with other code calling public
     * methods of the same IpClient instance.
     */
    public static class Callback {
        // In order to receive onPreDhcpAction(), call #withPreDhcpAction()
        // when constructing a ProvisioningConfiguration.
        //
        // Implementations of onPreDhcpAction() must call
        // IpClient#completedPreDhcpAction() to indicate that DHCP is clear
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

        // Called when the IpClient state machine terminates.
        public void onQuit() {}

        // Install an APF program to filter incoming packets.
        public void installPacketFilter(byte[] filter) {}

        // Asynchronously read back the APF program & data buffer from the wifi driver.
        // Due to Wifi HAL limitations, the current implementation only supports dumping the entire
        // buffer. In response to this request, the driver returns the data buffer asynchronously
        // by sending an IpClient#EVENT_READ_PACKET_FILTER_COMPLETE message.
        public void startReadPacketFilter() {}

        // If multicast filtering cannot be accomplished with APF, this function will be called to
        // actuate multicast filtering using another means.
        public void setFallbackMulticastFilter(boolean enabled) {}

        // Enabled/disable Neighbor Discover offload functionality. This is
        // called, for example, whenever 464xlat is being started or stopped.
        public void setNeighborDiscoveryOffload(boolean enable) {}
    }

    public static class WaitForProvisioningCallback extends Callback {
        private final ConditionVariable mCV = new ConditionVariable();
        private LinkProperties mCallbackLinkProperties;

        public LinkProperties waitForProvisioning() {
            mCV.block();
            return mCallbackLinkProperties;
        }

        @Override
        public void onProvisioningSuccess(LinkProperties newLp) {
            mCallbackLinkProperties = newLp;
            mCV.open();
        }

        @Override
        public void onProvisioningFailure(LinkProperties newLp) {
            mCallbackLinkProperties = null;
            mCV.open();
        }
    }

    // Use a wrapper class to log in order to ensure complete and detailed
    // logging. This method is lighter weight than annotations/reflection
    // and has the following benefits:
    //
    //     - No invoked method can be forgotten.
    //       Any new method added to IpClient.Callback must be overridden
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
            mLog.log(PREFIX + msg);
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
        public void startReadPacketFilter() {
            mCallback.startReadPacketFilter();
            log("startReadPacketFilter()");
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
     * IpClient#startProvisioning(). A defensive copy is made by IpClient
     * and the values specified herein are in force until IpClient#stop()
     * is called.
     *
     * Example use:
     *
     *     final ProvisioningConfiguration config =
     *             mIpClient.buildProvisioningConfiguration()
     *                     .withPreDhcpAction()
     *                     .withProvisioningTimeoutMs(36 * 1000)
     *                     .build();
     *     mIpClient.startProvisioning(config);
     *     ...
     *     mIpClient.stop();
     *
     * The specified provisioning configuration will only be active until
     * IpClient#stop() is called. Future calls to IpClient#startProvisioning()
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

            public Builder withoutMultinetworkPolicyTracker() {
                mConfig.mUsingMultinetworkPolicyTracker = false;
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

            public Builder withInitialConfiguration(InitialConfiguration initialConfig) {
                mConfig.mInitialConfig = initialConfig;
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

            public Builder withRandomMacAddress() {
                mConfig.mIPv6AddrGenMode = INetd.IPV6_ADDR_GEN_MODE_EUI64;
                return this;
            }

            public Builder withStableMacAddress() {
                mConfig.mIPv6AddrGenMode = INetd.IPV6_ADDR_GEN_MODE_STABLE_PRIVACY;
                return this;
            }

            public Builder withNetwork(Network network) {
                mConfig.mNetwork = network;
                return this;
            }

            public Builder withDisplayName(String displayName) {
                mConfig.mDisplayName = displayName;
                return this;
            }

            public ProvisioningConfiguration build() {
                return new ProvisioningConfiguration(mConfig);
            }
        }

        /* package */ boolean mEnableIPv4 = true;
        /* package */ boolean mEnableIPv6 = true;
        /* package */ boolean mUsingMultinetworkPolicyTracker = true;
        /* package */ boolean mUsingIpReachabilityMonitor = true;
        /* package */ int mRequestedPreDhcpActionMs;
        /* package */ InitialConfiguration mInitialConfig;
        /* package */ StaticIpConfiguration mStaticIpConfig;
        /* package */ ApfCapabilities mApfCapabilities;
        /* package */ int mProvisioningTimeoutMs = DEFAULT_TIMEOUT_MS;
        /* package */ int mIPv6AddrGenMode = INetd.IPV6_ADDR_GEN_MODE_STABLE_PRIVACY;
        /* package */ Network mNetwork = null;
        /* package */ String mDisplayName = null;

        public ProvisioningConfiguration() {} // used by Builder

        public ProvisioningConfiguration(ProvisioningConfiguration other) {
            mEnableIPv4 = other.mEnableIPv4;
            mEnableIPv6 = other.mEnableIPv6;
            mUsingIpReachabilityMonitor = other.mUsingIpReachabilityMonitor;
            mRequestedPreDhcpActionMs = other.mRequestedPreDhcpActionMs;
            mInitialConfig = InitialConfiguration.copy(other.mInitialConfig);
            mStaticIpConfig = other.mStaticIpConfig;
            mApfCapabilities = other.mApfCapabilities;
            mProvisioningTimeoutMs = other.mProvisioningTimeoutMs;
            mIPv6AddrGenMode = other.mIPv6AddrGenMode;
            mNetwork = other.mNetwork;
            mDisplayName = other.mDisplayName;
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", getClass().getSimpleName() + "{", "}")
                    .add("mEnableIPv4: " + mEnableIPv4)
                    .add("mEnableIPv6: " + mEnableIPv6)
                    .add("mUsingMultinetworkPolicyTracker: " + mUsingMultinetworkPolicyTracker)
                    .add("mUsingIpReachabilityMonitor: " + mUsingIpReachabilityMonitor)
                    .add("mRequestedPreDhcpActionMs: " + mRequestedPreDhcpActionMs)
                    .add("mInitialConfig: " + mInitialConfig)
                    .add("mStaticIpConfig: " + mStaticIpConfig)
                    .add("mApfCapabilities: " + mApfCapabilities)
                    .add("mProvisioningTimeoutMs: " + mProvisioningTimeoutMs)
                    .add("mIPv6AddrGenMode: " + mIPv6AddrGenMode)
                    .add("mNetwork: " + mNetwork)
                    .add("mDisplayName: " + mDisplayName)
                    .toString();
        }

        public boolean isValid() {
            return (mInitialConfig == null) || mInitialConfig.isValid();
        }
    }

    public static class InitialConfiguration {
        public final Set<LinkAddress> ipAddresses = new HashSet<>();
        public final Set<IpPrefix> directlyConnectedRoutes = new HashSet<>();
        public final Set<InetAddress> dnsServers = new HashSet<>();
        public Inet4Address gateway; // WiFi legacy behavior with static ipv4 config

        public static InitialConfiguration copy(InitialConfiguration config) {
            if (config == null) {
                return null;
            }
            InitialConfiguration configCopy = new InitialConfiguration();
            configCopy.ipAddresses.addAll(config.ipAddresses);
            configCopy.directlyConnectedRoutes.addAll(config.directlyConnectedRoutes);
            configCopy.dnsServers.addAll(config.dnsServers);
            return configCopy;
        }

        @Override
        public String toString() {
            return String.format(
                    "InitialConfiguration(IPs: {%s}, prefixes: {%s}, DNS: {%s}, v4 gateway: %s)",
                    join(", ", ipAddresses), join(", ", directlyConnectedRoutes),
                    join(", ", dnsServers), gateway);
        }

        public boolean isValid() {
            if (ipAddresses.isEmpty()) {
                return false;
            }

            // For every IP address, there must be at least one prefix containing that address.
            for (LinkAddress addr : ipAddresses) {
                if (!any(directlyConnectedRoutes, (p) -> p.contains(addr.getAddress()))) {
                    return false;
                }
            }
            // For every dns server, there must be at least one prefix containing that address.
            for (InetAddress addr : dnsServers) {
                if (!any(directlyConnectedRoutes, (p) -> p.contains(addr))) {
                    return false;
                }
            }
            // All IPv6 LinkAddresses have an RFC7421-suitable prefix length
            // (read: compliant with RFC4291#section2.5.4).
            if (any(ipAddresses, not(InitialConfiguration::isPrefixLengthCompliant))) {
                return false;
            }
            // If directlyConnectedRoutes contains an IPv6 default route
            // then ipAddresses MUST contain at least one non-ULA GUA.
            if (any(directlyConnectedRoutes, InitialConfiguration::isIPv6DefaultRoute)
                    && all(ipAddresses, not(InitialConfiguration::isIPv6GUA))) {
                return false;
            }
            // The prefix length of routes in directlyConnectedRoutes be within reasonable
            // bounds for IPv6: /48-/64 just as we’d accept in RIOs.
            if (any(directlyConnectedRoutes, not(InitialConfiguration::isPrefixLengthCompliant))) {
                return false;
            }
            // There no more than one IPv4 address
            if (ipAddresses.stream().filter(Inet4Address.class::isInstance).count() > 1) {
                return false;
            }

            return true;
        }

        /**
         * @return true if the given list of addressess and routes satisfies provisioning for this
         * InitialConfiguration. LinkAddresses and RouteInfo objects are not compared with equality
         * because addresses and routes seen by Netlink will contain additional fields like flags,
         * interfaces, and so on. If this InitialConfiguration has no IP address specified, the
         * provisioning check always fails.
         *
         * If the given list of routes is null, only addresses are taken into considerations.
         */
        public boolean isProvisionedBy(List<LinkAddress> addresses, List<RouteInfo> routes) {
            if (ipAddresses.isEmpty()) {
                return false;
            }

            for (LinkAddress addr : ipAddresses) {
                if (!any(addresses, (addrSeen) -> addr.isSameAddressAs(addrSeen))) {
                    return false;
                }
            }

            if (routes != null) {
                for (IpPrefix prefix : directlyConnectedRoutes) {
                    if (!any(routes, (routeSeen) -> isDirectlyConnectedRoute(routeSeen, prefix))) {
                        return false;
                    }
                }
            }

            return true;
        }

        private static boolean isDirectlyConnectedRoute(RouteInfo route, IpPrefix prefix) {
            return !route.hasGateway() && prefix.equals(route.getDestination());
        }

        private static boolean isPrefixLengthCompliant(LinkAddress addr) {
            return addr.isIPv4() || isCompliantIPv6PrefixLength(addr.getPrefixLength());
        }

        private static boolean isPrefixLengthCompliant(IpPrefix prefix) {
            return prefix.isIPv4() || isCompliantIPv6PrefixLength(prefix.getPrefixLength());
        }

        private static boolean isCompliantIPv6PrefixLength(int prefixLength) {
            return (NetworkConstants.RFC6177_MIN_PREFIX_LENGTH <= prefixLength)
                    && (prefixLength <= NetworkConstants.RFC7421_PREFIX_LENGTH);
        }

        private static boolean isIPv6DefaultRoute(IpPrefix prefix) {
            return prefix.getAddress().equals(Inet6Address.ANY);
        }

        private static boolean isIPv6GUA(LinkAddress addr) {
            return addr.isIPv6() && addr.isGlobalPreferred();
        }
    }

    public static final String DUMP_ARG = "ipclient";
    public static final String DUMP_ARG_CONFIRM = "confirm";

    private static final int CMD_TERMINATE_AFTER_STOP             = 1;
    private static final int CMD_STOP                             = 2;
    private static final int CMD_START                            = 3;
    private static final int CMD_CONFIRM                          = 4;
    private static final int EVENT_PRE_DHCP_ACTION_COMPLETE       = 5;
    // Sent by NetlinkTracker to communicate netlink events.
    private static final int EVENT_NETLINK_LINKPROPERTIES_CHANGED = 6;
    private static final int CMD_UPDATE_TCP_BUFFER_SIZES          = 7;
    private static final int CMD_UPDATE_HTTP_PROXY                = 8;
    private static final int CMD_SET_MULTICAST_FILTER             = 9;
    private static final int EVENT_PROVISIONING_TIMEOUT           = 10;
    private static final int EVENT_DHCPACTION_TIMEOUT             = 11;
    private static final int EVENT_READ_PACKET_FILTER_COMPLETE    = 12;

    private static final int MAX_LOG_RECORDS = 500;
    private static final int MAX_PACKET_RECORDS = 100;

    private static final boolean NO_CALLBACKS = false;
    private static final boolean SEND_CALLBACKS = true;

    // This must match the interface prefix in clatd.c.
    // TODO: Revert this hack once IpClient and Nat464Xlat work in concert.
    private static final String CLAT_PREFIX = "v4-";

    private static final int IMMEDIATE_FAILURE_DURATION = 0;

    private final State mStoppedState = new StoppedState();
    private final State mStoppingState = new StoppingState();
    private final State mStartedState = new StartedState();
    private final State mRunningState = new RunningState();

    private final String mTag;
    private final Context mContext;
    private final String mInterfaceName;
    private final String mClatInterfaceName;
    @VisibleForTesting
    protected final Callback mCallback;
    private final Dependencies mDependencies;
    private final CountDownLatch mShutdownLatch;
    private final INetworkManagementService mNwService;
    private final NetlinkTracker mNetlinkTracker;
    private final WakeupMessage mProvisioningTimeoutAlarm;
    private final WakeupMessage mDhcpActionTimeoutAlarm;
    private final SharedLog mLog;
    private final LocalLog mConnectivityPacketLog;
    private final MessageHandlingLogger mMsgStateLogger;
    private final IpConnectivityLog mMetricsLog = new IpConnectivityLog();
    private final InterfaceController mInterfaceCtrl;

    private InterfaceParams mInterfaceParams;

    /**
     * Non-final member variables accessed only from within our StateMachine.
     */
    private LinkProperties mLinkProperties;
    private ProvisioningConfiguration mConfiguration;
    private MultinetworkPolicyTracker mMultinetworkPolicyTracker;
    private IpReachabilityMonitor mIpReachabilityMonitor;
    private DhcpClient mDhcpClient;
    private DhcpResults mDhcpResults;
    private String mTcpBufferSizes;
    private ProxyInfo mHttpProxy;
    private ApfFilter mApfFilter;
    private boolean mMulticastFiltering;
    private long mStartTimeMillis;
    private byte[] mApfDataSnapshot;

    public static class Dependencies {
        public INetworkManagementService getNMS() {
            return INetworkManagementService.Stub.asInterface(
                    ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        }

        public INetd getNetd() {
            return NetdService.getInstance();
        }

        public InterfaceParams getInterfaceParams(String ifname) {
            return InterfaceParams.getByName(ifname);
        }
    }

    public IpClient(Context context, String ifName, Callback callback) {
        this(context, ifName, callback, new Dependencies());
    }

    /**
     * An expanded constructor, useful for dependency injection.
     * TODO: migrate all test users to mock IpClient directly and remove this ctor.
     */
    public IpClient(Context context, String ifName, Callback callback,
            INetworkManagementService nwService) {
        this(context, ifName, callback, new Dependencies() {
            @Override
            public INetworkManagementService getNMS() { return nwService; }
        });
    }

    @VisibleForTesting
    IpClient(Context context, String ifName, Callback callback, Dependencies deps) {
        super(IpClient.class.getSimpleName() + "." + ifName);
        Preconditions.checkNotNull(ifName);
        Preconditions.checkNotNull(callback);

        mTag = getName();

        mContext = context;
        mInterfaceName = ifName;
        mClatInterfaceName = CLAT_PREFIX + ifName;
        mCallback = new LoggingCallbackWrapper(callback);
        mDependencies = deps;
        mShutdownLatch = new CountDownLatch(1);
        mNwService = deps.getNMS();

        mLog = new SharedLog(MAX_LOG_RECORDS, mTag);
        mConnectivityPacketLog = new LocalLog(MAX_PACKET_RECORDS);
        mMsgStateLogger = new MessageHandlingLogger();

        // TODO: Consider creating, constructing, and passing in some kind of
        // InterfaceController.Dependencies class.
        mInterfaceCtrl = new InterfaceController(mInterfaceName, mNwService, deps.getNetd(), mLog);

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
                } else if (!mInterfaceName.equals(iface)) {
                    return;
                }

                final String msg = "interfaceAdded(" + iface +")";
                logMsg(msg);
            }

            @Override
            public void interfaceRemoved(String iface) {
                super.interfaceRemoved(iface);
                // TODO: Also observe mInterfaceName going down and take some
                // kind of appropriate action.
                if (mClatInterfaceName.equals(iface)) {
                    // TODO: consider sending a message to the IpClient main
                    // StateMachine thread, in case "NDO enabled" state becomes
                    // tied to more things that 464xlat operation.
                    mCallback.setNeighborDiscoveryOffload(true);
                } else if (!mInterfaceName.equals(iface)) {
                    return;
                }

                final String msg = "interfaceRemoved(" + iface +")";
                logMsg(msg);
            }

            private void logMsg(String msg) {
                Log.d(mTag, msg);
                getHandler().post(() -> { mLog.log("OBSERVED " + msg); });
            }
        };

        mLinkProperties = new LinkProperties();
        mLinkProperties.setInterfaceName(mInterfaceName);

        mProvisioningTimeoutAlarm = new WakeupMessage(mContext, getHandler(),
                mTag + ".EVENT_PROVISIONING_TIMEOUT", EVENT_PROVISIONING_TIMEOUT);
        mDhcpActionTimeoutAlarm = new WakeupMessage(mContext, getHandler(),
                mTag + ".EVENT_DHCPACTION_TIMEOUT", EVENT_DHCPACTION_TIMEOUT);

        // Anything the StateMachine may access must have been instantiated
        // before this point.
        configureAndStartStateMachine();

        // Anything that may send messages to the StateMachine must only be
        // configured to do so after the StateMachine has started (above).
        startStateMachineUpdaters();
    }

    private void configureAndStartStateMachine() {
        addState(mStoppedState);
        addState(mStartedState);
            addState(mRunningState, mStartedState);
        addState(mStoppingState);

        setInitialState(mStoppedState);

        super.start();
    }

    private void startStateMachineUpdaters() {
        try {
            mNwService.registerObserver(mNetlinkTracker);
        } catch (RemoteException e) {
            logError("Couldn't register NetlinkTracker: %s", e);
        }
    }

    private void stopStateMachineUpdaters() {
        try {
            mNwService.unregisterObserver(mNetlinkTracker);
        } catch (RemoteException e) {
            logError("Couldn't unregister NetlinkTracker: %s", e);
        }
    }

    @Override
    protected void onQuitting() {
        mCallback.onQuit();
        mShutdownLatch.countDown();
    }

    // Shut down this IpClient instance altogether.
    public void shutdown() {
        stop();
        sendMessage(CMD_TERMINATE_AFTER_STOP);
    }

    // In order to avoid deadlock, this method MUST NOT be called on the
    // IpClient instance's thread. This prohibition includes code executed by
    // when methods on the passed-in IpClient.Callback instance are called.
    public void awaitShutdown() {
        try {
            mShutdownLatch.await();
        } catch (InterruptedException e) {
            mLog.e("Interrupted while awaiting shutdown: " + e);
        }
    }

    public static ProvisioningConfiguration.Builder buildProvisioningConfiguration() {
        return new ProvisioningConfiguration.Builder();
    }

    public void startProvisioning(ProvisioningConfiguration req) {
        if (!req.isValid()) {
            doImmediateProvisioningFailure(IpManagerEvent.ERROR_INVALID_PROVISIONING);
            return;
        }

        mInterfaceParams = mDependencies.getInterfaceParams(mInterfaceName);
        if (mInterfaceParams == null) {
            logError("Failed to find InterfaceParams for " + mInterfaceName);
            doImmediateProvisioningFailure(IpManagerEvent.ERROR_INTERFACE_NOT_FOUND);
            return;
        }

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

    public void readPacketFilterComplete(byte[] data) {
        sendMessage(EVENT_READ_PACKET_FILTER_COMPLETE, data);
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
        if (args != null && args.length > 0 && DUMP_ARG_CONFIRM.equals(args[0])) {
            // Execute confirmConfiguration() and take no further action.
            confirmConfiguration();
            return;
        }

        // Thread-unsafe access to mApfFilter but just used for debugging.
        final ApfFilter apfFilter = mApfFilter;
        final ProvisioningConfiguration provisioningConfig = mConfiguration;
        final ApfCapabilities apfCapabilities = (provisioningConfig != null)
                ? provisioningConfig.mApfCapabilities : null;
        final byte[] apfDataSnapshot = mApfDataSnapshot;

        IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        pw.println(mTag + " APF dump:");
        pw.increaseIndent();
        if (apfFilter != null) {
            apfFilter.dump(pw);
        } else {
            pw.print("No active ApfFilter; ");
            if (provisioningConfig == null) {
                pw.println("IpClient not yet started.");
            } else if (apfCapabilities == null || apfCapabilities.apfVersionSupported == 0) {
                pw.println("Hardware does not support APF.");
            } else {
                pw.println("ApfFilter not yet started, APF capabilities: " + apfCapabilities);
            }
        }
        pw.decreaseIndent();
        pw.println(mTag + " latest APF data snapshot: ");
        pw.increaseIndent();
        if (apfDataSnapshot != null) {
            pw.println(HexDump.dumpHexString(apfDataSnapshot));
        } else {
            pw.println("No last snapshot.");
        }
        pw.decreaseIndent();

        pw.println();
        pw.println(mTag + " current ProvisioningConfiguration:");
        pw.increaseIndent();
        pw.println(Objects.toString(provisioningConfig, "N/A"));
        pw.decreaseIndent();

        final IpReachabilityMonitor iprm = mIpReachabilityMonitor;
        if (iprm != null) {
            pw.println();
            pw.println(mTag + " current IpReachabilityMonitor state:");
            pw.increaseIndent();
            iprm.dump(pw);
            pw.decreaseIndent();
        }

        pw.println();
        pw.println(mTag + " StateMachine dump:");
        pw.increaseIndent();
        mLog.dump(fd, pw, args);
        pw.decreaseIndent();

        pw.println();
        pw.println(mTag + " connectivity packet log:");
        pw.println();
        pw.println("Debug with python and scapy via:");
        pw.println("shell$ python");
        pw.println(">>> from scapy import all as scapy");
        pw.println(">>> scapy.Ether(\"<paste_hex_string>\".decode(\"hex\")).show2()");
        pw.println();

        pw.increaseIndent();
        mConnectivityPacketLog.readOnlyLocalLog().dump(fd, pw, args);
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
                "%s/%d %d %d %s [%s]",
                mInterfaceName, (mInterfaceParams == null) ? -1 : mInterfaceParams.index,
                msg.arg1, msg.arg2, Objects.toString(msg.obj), mMsgStateLogger);

        final String richerLogLine = getWhatToString(msg.what) + " " + logLine;
        mLog.log(richerLogLine);
        if (DBG) {
            Log.d(mTag, richerLogLine);
        }

        mMsgStateLogger.reset();
        return logLine;
    }

    @Override
    protected boolean recordLogRec(Message msg) {
        // Don't log EVENT_NETLINK_LINKPROPERTIES_CHANGED. They can be noisy,
        // and we already log any LinkProperties change that results in an
        // invocation of IpClient.Callback#onLinkPropertiesChange().
        final boolean shouldLog = (msg.what != EVENT_NETLINK_LINKPROPERTIES_CHANGED);
        if (!shouldLog) {
            mMsgStateLogger.reset();
        }
        return shouldLog;
    }

    private void logError(String fmt, Object... args) {
        final String msg = "ERROR " + String.format(fmt, args);
        Log.e(mTag, msg);
        mLog.log(msg);
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
        // We may record error metrics prior to starting.
        // Map this to IMMEDIATE_FAILURE_DURATION.
        final long duration = (mStartTimeMillis > 0)
                ? (SystemClock.elapsedRealtime() - mStartTimeMillis)
                : IMMEDIATE_FAILURE_DURATION;
        mMetricsLog.log(mInterfaceName, new IpManagerEvent(type, duration));
    }

    // For now: use WifiStateMachine's historical notion of provisioned.
    @VisibleForTesting
    static boolean isProvisioned(LinkProperties lp, InitialConfiguration config) {
        // For historical reasons, we should connect even if all we have is
        // an IPv4 address and nothing else.
        if (lp.hasIPv4Address() || lp.isProvisioned()) {
            return true;
        }
        if (config == null) {
            return false;
        }

        // When an InitialConfiguration is specified, ignore any difference with previous
        // properties and instead check if properties observed match the desired properties.
        return config.isProvisionedBy(lp.getLinkAddresses(), lp.getRoutes());
    }

    // TODO: Investigate folding all this into the existing static function
    // LinkProperties.compareProvisioning() or some other single function that
    // takes two LinkProperties objects and returns a ProvisioningChange
    // object that is a correct and complete assessment of what changed, taking
    // account of the asymmetries described in the comments in this function.
    // Then switch to using it everywhere (IpReachabilityMonitor, etc.).
    private ProvisioningChange compareProvisioning(LinkProperties oldLp, LinkProperties newLp) {
        ProvisioningChange delta;
        InitialConfiguration config = mConfiguration != null ? mConfiguration.mInitialConfig : null;
        final boolean wasProvisioned = isProvisioned(oldLp, config);
        final boolean isProvisioned = isProvisioned(newLp, config);

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

        final boolean lostIPv6 = oldLp.isIPv6Provisioned() && !newLp.isIPv6Provisioned();
        final boolean lostIPv4Address = oldLp.hasIPv4Address() && !newLp.hasIPv4Address();
        final boolean lostIPv6Router = oldLp.hasIPv6DefaultRoute() && !newLp.hasIPv6DefaultRoute();

        // If bad wifi avoidance is disabled, then ignore IPv6 loss of
        // provisioning. Otherwise, when a hotspot that loses Internet
        // access sends out a 0-lifetime RA to its clients, the clients
        // will disconnect and then reconnect, avoiding the bad hotspot,
        // instead of getting stuck on the bad hotspot. http://b/31827713 .
        //
        // This is incorrect because if the hotspot then regains Internet
        // access with a different prefix, TCP connections on the
        // deprecated addresses will remain stuck.
        //
        // Note that we can still be disconnected by IpReachabilityMonitor
        // if the IPv6 default gateway (but not the IPv6 DNS servers; see
        // accompanying code in IpReachabilityMonitor) is unreachable.
        final boolean ignoreIPv6ProvisioningLoss = (mMultinetworkPolicyTracker != null)
                && !mMultinetworkPolicyTracker.getAvoidBadWifi();

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
        if (lostIPv4Address || (lostIPv6 && !ignoreIPv6ProvisioningLoss)) {
            delta = ProvisioningChange.LOST_PROVISIONING;
        }

        // Additionally:
        //
        // If the previous link properties had a global IPv6 address and an
        // IPv6 default route then also consider the loss of that default route
        // to be a loss of provisioning. See b/27962810.
        if (oldLp.hasGlobalIPv6Address() && (lostIPv6Router && !ignoreIPv6ProvisioningLoss)) {
            delta = ProvisioningChange.LOST_PROVISIONING;
        }

        return delta;
    }

    private void dispatchCallback(ProvisioningChange delta, LinkProperties newLp) {
        switch (delta) {
            case GAINED_PROVISIONING:
                if (DBG) { Log.d(mTag, "onProvisioningSuccess()"); }
                recordMetric(IpManagerEvent.PROVISIONING_OK);
                mCallback.onProvisioningSuccess(newLp);
                break;

            case LOST_PROVISIONING:
                if (DBG) { Log.d(mTag, "onProvisioningFailure()"); }
                recordMetric(IpManagerEvent.PROVISIONING_FAIL);
                mCallback.onProvisioningFailure(newLp);
                break;

            default:
                if (DBG) { Log.d(mTag, "onLinkPropertiesChange()"); }
                mCallback.onLinkPropertiesChange(newLp);
                break;
        }
    }

    // Updates all IpClient-related state concerned with LinkProperties.
    // Returns a ProvisioningChange for possibly notifying other interested
    // parties that are not fronted by IpClient.
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

    private LinkProperties assembleLinkProperties() {
        // [1] Create a new LinkProperties object to populate.
        LinkProperties newLp = new LinkProperties();
        newLp.setInterfaceName(mInterfaceName);

        // [2] Pull in data from netlink:
        //         - IPv4 addresses
        //         - IPv6 addresses
        //         - IPv6 routes
        //         - IPv6 DNS servers
        //
        // N.B.: this is fundamentally race-prone and should be fixed by
        // changing NetlinkTracker from a hybrid edge/level model to an
        // edge-only model, or by giving IpClient its own netlink socket(s)
        // so as to track all required information directly.
        LinkProperties netlinkLinkProperties = mNetlinkTracker.getLinkProperties();
        newLp.setLinkAddresses(netlinkLinkProperties.getLinkAddresses());
        for (RouteInfo route : netlinkLinkProperties.getRoutes()) {
            newLp.addRoute(route);
        }
        addAllReachableDnsServers(newLp, netlinkLinkProperties.getDnsServers());

        // [3] Add in data from DHCPv4, if available.
        //
        // mDhcpResults is never shared with any other owner so we don't have
        // to worry about concurrent modification.
        if (mDhcpResults != null) {
            for (RouteInfo route : mDhcpResults.getRoutes(mInterfaceName)) {
                newLp.addRoute(route);
            }
            addAllReachableDnsServers(newLp, mDhcpResults.dnsServers);
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

        // [5] Add data from InitialConfiguration
        if (mConfiguration != null && mConfiguration.mInitialConfig != null) {
            InitialConfiguration config = mConfiguration.mInitialConfig;
            // Add InitialConfiguration routes and dns server addresses once all addresses
            // specified in the InitialConfiguration have been observed with Netlink.
            if (config.isProvisionedBy(newLp.getLinkAddresses(), null)) {
                for (IpPrefix prefix : config.directlyConnectedRoutes) {
                    newLp.addRoute(new RouteInfo(prefix, null, mInterfaceName));
                }
            }
            addAllReachableDnsServers(newLp, config.dnsServers);
        }
        final LinkProperties oldLp = mLinkProperties;
        if (DBG) {
            Log.d(mTag, String.format("Netlink-seen LPs: %s, new LPs: %s; old LPs: %s",
                    netlinkLinkProperties, newLp, oldLp));
        }

        // TODO: also learn via netlink routes specified by an InitialConfiguration and specified
        // from a static IP v4 config instead of manually patching them in in steps [3] and [5].
        return newLp;
    }

    private static void addAllReachableDnsServers(
            LinkProperties lp, Iterable<InetAddress> dnses) {
        // TODO: Investigate deleting this reachability check.  We should be
        // able to pass everything down to netd and let netd do evaluation
        // and RFC6724-style sorting.
        for (InetAddress dns : dnses) {
            if (!dns.isAnyLocalAddress() && lp.isReachable(dns)) {
                lp.addDnsServer(dns);
            }
        }
    }

    // Returns false if we have lost provisioning, true otherwise.
    private boolean handleLinkPropertiesUpdate(boolean sendCallbacks) {
        final LinkProperties newLp = assembleLinkProperties();
        if (Objects.equals(newLp, mLinkProperties)) {
            return true;
        }
        final ProvisioningChange delta = setLinkProperties(newLp);
        if (sendCallbacks) {
            dispatchCallback(delta, newLp);
        }
        return (delta != ProvisioningChange.LOST_PROVISIONING);
    }

    private void handleIPv4Success(DhcpResults dhcpResults) {
        mDhcpResults = new DhcpResults(dhcpResults);
        final LinkProperties newLp = assembleLinkProperties();
        final ProvisioningChange delta = setLinkProperties(newLp);

        if (DBG) {
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
        mInterfaceCtrl.clearIPv4Address();
        mDhcpResults = null;
        if (DBG) { Log.d(mTag, "onNewDhcpResults(null)"); }
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

    private void doImmediateProvisioningFailure(int failureType) {
        logError("onProvisioningFailure(): %s", failureType);
        recordMetric(failureType);
        mCallback.onProvisioningFailure(new LinkProperties(mLinkProperties));
    }

    private boolean startIPv4() {
        // If we have a StaticIpConfiguration attempt to apply it and
        // handle the result accordingly.
        if (mConfiguration.mStaticIpConfig != null) {
            if (mInterfaceCtrl.setIPv4Address(mConfiguration.mStaticIpConfig.ipAddress)) {
                handleIPv4Success(new DhcpResults(mConfiguration.mStaticIpConfig));
            } else {
                return false;
            }
        } else {
            // Start DHCPv4.
            mDhcpClient = DhcpClient.makeDhcpClient(mContext, IpClient.this, mInterfaceParams);
            mDhcpClient.registerForPreDhcpNotification();
            mDhcpClient.sendMessage(DhcpClient.CMD_START_DHCP);
        }

        return true;
    }

    private boolean startIPv6() {
        return mInterfaceCtrl.setIPv6PrivacyExtensions(true) &&
               mInterfaceCtrl.setIPv6AddrGenModeIfSupported(mConfiguration.mIPv6AddrGenMode) &&
               mInterfaceCtrl.enableIPv6();
    }

    private boolean applyInitialConfig(InitialConfiguration config) {
        // TODO: also support specifying a static IPv4 configuration in InitialConfiguration.
        for (LinkAddress addr : findAll(config.ipAddresses, LinkAddress::isIPv6)) {
            if (!mInterfaceCtrl.addAddress(addr)) return false;
        }

        return true;
    }

    private boolean startIpReachabilityMonitor() {
        try {
            mIpReachabilityMonitor = new IpReachabilityMonitor(
                    mContext,
                    mInterfaceParams,
                    getHandler(),
                    mLog,
                    new IpReachabilityMonitor.Callback() {
                        @Override
                        public void notifyLost(InetAddress ip, String logMsg) {
                            mCallback.onReachabilityLost(logMsg);
                        }
                    },
                    mMultinetworkPolicyTracker);
        } catch (IllegalArgumentException iae) {
            // Failed to start IpReachabilityMonitor. Log it and call
            // onProvisioningFailure() immediately.
            //
            // See http://b/31038971.
            logError("IpReachabilityMonitor failure: %s", iae);
            mIpReachabilityMonitor = null;
        }

        return (mIpReachabilityMonitor != null);
    }

    private void stopAllIP() {
        // We don't need to worry about routes, just addresses, because:
        //     - disableIpv6() will clear autoconf IPv6 routes as well, and
        //     - we don't get IPv4 routes from netlink
        // so we neither react to nor need to wait for changes in either.

        mInterfaceCtrl.disableIPv6();
        mInterfaceCtrl.clearAllAddresses();
    }

    class StoppedState extends State {
        @Override
        public void enter() {
            stopAllIP();

            resetLinkProperties();
            if (mStartTimeMillis > 0) {
                recordMetric(IpManagerEvent.COMPLETE_LIFECYCLE);
                mStartTimeMillis = 0;
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_TERMINATE_AFTER_STOP:
                    stopStateMachineUpdaters();
                    quit();
                    break;

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
                    logError("Unexpected CMD_ON_QUIT (already stopped).");
                    break;

                default:
                    return NOT_HANDLED;
            }

            mMsgStateLogger.handled(this, getCurrentState());
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
                case CMD_STOP:
                    break;

                case DhcpClient.CMD_CLEAR_LINKADDRESS:
                    mInterfaceCtrl.clearIPv4Address();
                    break;

                case DhcpClient.CMD_ON_QUIT:
                    mDhcpClient = null;
                    transitionTo(mStoppedState);
                    break;

                default:
                    deferMessage(msg);
            }

            mMsgStateLogger.handled(this, getCurrentState());
            return HANDLED;
        }
    }

    class StartedState extends State {
        @Override
        public void enter() {
            mStartTimeMillis = SystemClock.elapsedRealtime();

            if (mConfiguration.mProvisioningTimeoutMs > 0) {
                final long alarmTime = SystemClock.elapsedRealtime() +
                        mConfiguration.mProvisioningTimeoutMs;
                mProvisioningTimeoutAlarm.schedule(alarmTime);
            }

            if (readyToProceed()) {
                transitionTo(mRunningState);
            } else {
                // Clear all IPv4 and IPv6 before proceeding to RunningState.
                // Clean up any leftover state from an abnormal exit from
                // tethering or during an IpClient restart.
                stopAllIP();
            }
        }

        @Override
        public void exit() {
            mProvisioningTimeoutAlarm.cancel();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_STOP:
                    transitionTo(mStoppingState);
                    break;

                case EVENT_NETLINK_LINKPROPERTIES_CHANGED:
                    handleLinkPropertiesUpdate(NO_CALLBACKS);
                    if (readyToProceed()) {
                        transitionTo(mRunningState);
                    }
                    break;

                case EVENT_PROVISIONING_TIMEOUT:
                    handleProvisioningFailure();
                    break;

                default:
                    // It's safe to process messages out of order because the
                    // only message that can both
                    //     a) be received at this time and
                    //     b) affect provisioning state
                    // is EVENT_NETLINK_LINKPROPERTIES_CHANGED (handled above).
                    deferMessage(msg);
            }

            mMsgStateLogger.handled(this, getCurrentState());
            return HANDLED;
        }

        boolean readyToProceed() {
            return (!mLinkProperties.hasIPv4Address() &&
                    !mLinkProperties.hasGlobalIPv6Address());
        }
    }

    class RunningState extends State {
        private ConnectivityPacketTracker mPacketTracker;
        private boolean mDhcpActionInFlight;

        @Override
        public void enter() {
            ApfFilter.ApfConfiguration apfConfig = new ApfFilter.ApfConfiguration();
            apfConfig.apfCapabilities = mConfiguration.mApfCapabilities;
            apfConfig.multicastFilter = mMulticastFiltering;
            // Get the Configuration for ApfFilter from Context
            apfConfig.ieee802_3Filter =
                    mContext.getResources().getBoolean(R.bool.config_apfDrop802_3Frames);
            apfConfig.ethTypeBlackList =
                    mContext.getResources().getIntArray(R.array.config_apfEthTypeBlackList);
            mApfFilter = ApfFilter.maybeCreate(mContext, apfConfig, mInterfaceParams, mCallback);
            // TODO: investigate the effects of any multicast filtering racing/interfering with the
            // rest of this IP configuration startup.
            if (mApfFilter == null) {
                mCallback.setFallbackMulticastFilter(mMulticastFiltering);
            }

            mPacketTracker = createPacketTracker();
            if (mPacketTracker != null) mPacketTracker.start(mConfiguration.mDisplayName);

            if (mConfiguration.mEnableIPv6 && !startIPv6()) {
                doImmediateProvisioningFailure(IpManagerEvent.ERROR_STARTING_IPV6);
                transitionTo(mStoppingState);
                return;
            }

            if (mConfiguration.mEnableIPv4 && !startIPv4()) {
                doImmediateProvisioningFailure(IpManagerEvent.ERROR_STARTING_IPV4);
                transitionTo(mStoppingState);
                return;
            }

            final InitialConfiguration config = mConfiguration.mInitialConfig;
            if ((config != null) && !applyInitialConfig(config)) {
                // TODO introduce a new IpManagerEvent constant to distinguish this error case.
                doImmediateProvisioningFailure(IpManagerEvent.ERROR_INVALID_PROVISIONING);
                transitionTo(mStoppingState);
                return;
            }

            if (mConfiguration.mUsingMultinetworkPolicyTracker) {
                mMultinetworkPolicyTracker = new MultinetworkPolicyTracker(
                        mContext, getHandler(),
                        () -> { mLog.log("OBSERVED AvoidBadWifi changed"); });
                mMultinetworkPolicyTracker.start();
            }

            if (mConfiguration.mUsingIpReachabilityMonitor && !startIpReachabilityMonitor()) {
                doImmediateProvisioningFailure(
                        IpManagerEvent.ERROR_STARTING_IPREACHABILITYMONITOR);
                transitionTo(mStoppingState);
                return;
            }
        }

        @Override
        public void exit() {
            stopDhcpAction();

            if (mIpReachabilityMonitor != null) {
                mIpReachabilityMonitor.stop();
                mIpReachabilityMonitor = null;
            }

            if (mMultinetworkPolicyTracker != null) {
                mMultinetworkPolicyTracker.shutdown();
                mMultinetworkPolicyTracker = null;
            }

            if (mDhcpClient != null) {
                mDhcpClient.sendMessage(DhcpClient.CMD_STOP_DHCP);
                mDhcpClient.doQuit();
            }

            if (mPacketTracker != null) {
                mPacketTracker.stop();
                mPacketTracker = null;
            }

            if (mApfFilter != null) {
                mApfFilter.shutdown();
                mApfFilter = null;
            }

            resetLinkProperties();
        }

        private ConnectivityPacketTracker createPacketTracker() {
            try {
                return new ConnectivityPacketTracker(
                        getHandler(), mInterfaceParams, mConnectivityPacketLog);
            } catch (IllegalArgumentException e) {
                return null;
            }
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
                    logError("ALERT: START received in StartedState. Please fix caller.");
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

                case EVENT_READ_PACKET_FILTER_COMPLETE: {
                    mApfDataSnapshot = (byte[]) msg.obj;
                    break;
                }

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
                    mInterfaceCtrl.clearIPv4Address();
                    break;

                case DhcpClient.CMD_CONFIGURE_LINKADDRESS: {
                    final LinkAddress ipAddress = (LinkAddress) msg.obj;
                    if (mInterfaceCtrl.setIPv4Address(ipAddress)) {
                        mDhcpClient.sendMessage(DhcpClient.EVENT_LINKADDRESS_CONFIGURED);
                    } else {
                        logError("Failed to set IPv4 address.");
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
                            logError("Unknown CMD_POST_DHCP_ACTION status: %s", msg.arg1);
                    }
                    break;

                case DhcpClient.CMD_ON_QUIT:
                    // DHCPv4 quit early for some reason.
                    logError("Unexpected CMD_ON_QUIT.");
                    mDhcpClient = null;
                    break;

                default:
                    return NOT_HANDLED;
            }

            mMsgStateLogger.handled(this, getCurrentState());
            return HANDLED;
        }
    }

    private static class MessageHandlingLogger {
        public String processedInState;
        public String receivedInState;

        public void reset() {
            processedInState = null;
            receivedInState = null;
        }

        public void handled(State processedIn, IState receivedIn) {
            processedInState = processedIn.getClass().getSimpleName();
            receivedInState = receivedIn.getName();
        }

        public String toString() {
            return String.format("rcvd_in=%s, proc_in=%s",
                                 receivedInState, processedInState);
        }
    }

    // TODO: extract out into CollectionUtils.
    static <T> boolean any(Iterable<T> coll, Predicate<T> fn) {
        for (T t : coll) {
            if (fn.test(t)) {
                return true;
            }
        }
        return false;
    }

    static <T> boolean all(Iterable<T> coll, Predicate<T> fn) {
        return !any(coll, not(fn));
    }

    static <T> Predicate<T> not(Predicate<T> fn) {
        return (t) -> !fn.test(t);
    }

    static <T> String join(String delimiter, Collection<T> coll) {
        return coll.stream().map(Object::toString).collect(Collectors.joining(delimiter));
    }

    static <T> T find(Iterable<T> coll, Predicate<T> fn) {
        for (T t: coll) {
            if (fn.test(t)) {
              return t;
            }
        }
        return null;
    }

    static <T> List<T> findAll(Collection<T> coll, Predicate<T> fn) {
        return coll.stream().filter(fn).collect(Collectors.toList());
    }
}
