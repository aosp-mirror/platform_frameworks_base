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

import android.content.Context;
import android.net.BaseDhcpStateMachine;
import android.net.DhcpResults;
import android.net.DhcpStateMachine;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LinkProperties.ProvisioningChange;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.dhcp.DhcpClient;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.NetlinkTracker;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;


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
    private static final String TAG = IpManager.class.getSimpleName();
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /**
     * Callbacks for both configuration of IpManager and for handling
     * events as desired.
     */
    public static class Callback {
        /**
         * Configuration callbacks.
         *
         * Override methods as desired in order to control which features
         * IpManager will use at run time.
         */

        // An IpReachabilityMonitor will always be started, if only for logging.
        // This method is checked before probing neighbors and before calling
        // onProvisioningLost() (see below).
        public boolean usingIpReachabilityMonitor() {
            return false;
        }

        /**
         * Event callbacks.
         *
         * Override methods as desired in order to handle event callbacks
         * as IpManager invokes them.
         */

        // Implementations must call IpManager#completedPreDhcpAction().
        public void onPreDhcpAction() {}
        public void onPostDhcpAction() {}

        // TODO: Kill with fire once DHCP and static configuration are moved
        // out of WifiStateMachine.
        public void onIPv4ProvisioningSuccess(DhcpResults dhcpResults) {}
        public void onIPv4ProvisioningFailure() {}

        public void onProvisioningSuccess(LinkProperties newLp) {}
        public void onProvisioningFailure(LinkProperties newLp) {}

        // Invoked on LinkProperties changes.
        public void onLinkPropertiesChange(LinkProperties newLp) {}

        // Called when the internal IpReachabilityMonitor (if enabled) has
        // detected the loss of a critical number of required neighbors.
        public void onReachabilityLost(String logMsg) {}
    }

    private static final int CMD_STOP = 1;
    private static final int CMD_START = 2;
    private static final int CMD_CONFIRM = 3;
    private static final int CMD_UPDATE_DHCPV4_RESULTS = 4;
    private static final int EVENT_PRE_DHCP_ACTION_COMPLETE = 5;
    // Sent by NetlinkTracker to communicate netlink events.
    private static final int EVENT_NETLINK_LINKPROPERTIES_CHANGED = 6;

    private static final int MAX_LOG_RECORDS = 1000;

    private final Object mLock = new Object();
    private final State mStoppedState = new StoppedState();
    private final State mStartedState = new StartedState();

    private final Context mContext;
    private final String mInterfaceName;
    @VisibleForTesting
    protected final Callback mCallback;
    private final INetworkManagementService mNwService;
    private final NetlinkTracker mNetlinkTracker;

    private int mInterfaceIndex;

    /**
     * Non-final member variables accessed only from within our StateMachine.
     */
    private IpReachabilityMonitor mIpReachabilityMonitor;
    private BaseDhcpStateMachine mDhcpStateMachine;
    private DhcpResults mDhcpResults;
    private StaticIpConfiguration mStaticIpConfig;

    /**
     * Member variables accessed both from within the StateMachine thread
     * and via accessors from other threads.
     */
    @GuardedBy("mLock")
    private LinkProperties mLinkProperties;

    public IpManager(Context context, String ifName, Callback callback)
                throws IllegalArgumentException {
        super(TAG + "." + ifName);

        mContext = context;
        mInterfaceName = ifName;

        mCallback = callback;

        mNwService = INetworkManagementService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));

        mNetlinkTracker = new NetlinkTracker(
                mInterfaceName,
                new NetlinkTracker.Callback() {
                    @Override
                    public void update() {
                        sendMessage(EVENT_NETLINK_LINKPROPERTIES_CHANGED);
                    }
                });
        try {
            mNwService.registerObserver(mNetlinkTracker);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't register NetlinkTracker: " + e.toString());
        }

        resetLinkProperties();

        // Super simple StateMachine.
        addState(mStoppedState);
        addState(mStartedState);
        setInitialState(mStoppedState);
        setLogRecSize(MAX_LOG_RECORDS);
        super.start();
    }

    /**
     * A special constructor for use in testing that bypasses some of the more
     * complicated setup bits.
     *
     * TODO: Figure out how to delete this yet preserve testability.
     */
    @VisibleForTesting
    protected IpManager(String ifName, Callback callback) {
        super(TAG + ".test-" + ifName);
        mInterfaceName = ifName;
        mCallback = callback;

        mContext = null;
        mNwService = null;
        mNetlinkTracker = null;
    }

    public void startProvisioning(StaticIpConfiguration staticIpConfig) {
        getInterfaceIndex();

        sendMessage(CMD_START, staticIpConfig);
    }

    public void startProvisioning() {
        getInterfaceIndex();

        sendMessage(CMD_START);
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

    public LinkProperties getLinkProperties() {
        synchronized (mLock) {
            return new LinkProperties(mLinkProperties);
        }
    }


    /**
     * Internals.
     */

    private void getInterfaceIndex() {
        try {
            mInterfaceIndex = NetworkInterface.getByName(mInterfaceName).getIndex();
        } catch (SocketException | NullPointerException e) {
            // TODO: throw new IllegalStateException.
            Log.e(TAG, "ALERT: Failed to get interface index: ", e);
        }
    }

    // This needs to be called with care to ensure that our LinkProperties
    // are in sync with the actual LinkProperties of the interface. For example,
    // we should only call this if we know for sure that there are no IP addresses
    // assigned to the interface, etc.
    private void resetLinkProperties() {
        mNetlinkTracker.clearLinkProperties();
        mDhcpResults = null;
        mStaticIpConfig = null;

        synchronized (mLock) {
            mLinkProperties = new LinkProperties();
            mLinkProperties.setInterfaceName(mInterfaceName);
        }
    }

    private ProvisioningChange setLinkProperties(LinkProperties newLp) {
        if (mIpReachabilityMonitor != null) {
            mIpReachabilityMonitor.updateLinkProperties(newLp);
        }

        // TODO: Figure out whether and how to incorporate static configuration
        // into the notion of provisioning.
        ProvisioningChange delta;
        synchronized (mLock) {
            delta = LinkProperties.compareProvisioning(mLinkProperties, newLp);
            mLinkProperties = new LinkProperties(newLp);
        }

        if (DBG) {
            switch (delta) {
                case GAINED_PROVISIONING:
                case LOST_PROVISIONING:
                    Log.d(TAG, "provisioning: " + delta);
                    break;
            }
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
        }

        if (VDBG) {
            Log.d(TAG, "newLp{" + newLp + "}");
        }

        return newLp;
    }

    private void clearIPv4Address() {
        try {
            final InterfaceConfiguration ifcg = new InterfaceConfiguration();
            ifcg.setLinkAddress(new LinkAddress("0.0.0.0/0"));
            mNwService.setInterfaceConfig(mInterfaceName, ifcg);
        } catch (RemoteException e) {
            Log.e(TAG, "ALERT: Failed to clear IPv4 address on interface " + mInterfaceName, e);
        }
    }

    class StoppedState extends State {
        @Override
        public void enter() {
            try {
                mNwService.disableIpv6(mInterfaceName);
                mNwService.clearInterfaceAddresses(mInterfaceName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to clear addresses or disable IPv6" + e);
            }

            resetLinkProperties();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_STOP:
                    break;

                case CMD_START:
                    mStaticIpConfig = (StaticIpConfiguration) msg.obj;
                    transitionTo(mStartedState);
                    break;

                case EVENT_NETLINK_LINKPROPERTIES_CHANGED:
                    setLinkProperties(assembleLinkProperties());
                    break;

                case DhcpStateMachine.CMD_ON_QUIT:
                    // CMD_ON_QUIT is really more like "EVENT_ON_QUIT".
                    // Shutting down DHCPv4 progresses simultaneously with
                    // transitioning to StoppedState, so we can receive this
                    // message after we've already transitioned here.
                    //
                    // TODO: Figure out if this is actually useful and if not
                    // expunge it.
                    break;

                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class StartedState extends State {
        @Override
        public void enter() {
            // Set privacy extensions.
            try {
                mNwService.setInterfaceIpv6PrivacyExtensions(mInterfaceName, true);
                mNwService.enableIpv6(mInterfaceName);
                // TODO: Perhaps clearIPv4Address() as well.
            } catch (RemoteException re) {
                Log.e(TAG, "Unable to change interface settings: " + re);
            } catch (IllegalStateException ie) {
                Log.e(TAG, "Unable to change interface settings: " + ie);
            }

            mIpReachabilityMonitor = new IpReachabilityMonitor(
                    mContext,
                    mInterfaceName,
                    new IpReachabilityMonitor.Callback() {
                        @Override
                        public void notifyLost(InetAddress ip, String logMsg) {
                            if (mCallback.usingIpReachabilityMonitor()) {
                                mCallback.onReachabilityLost(logMsg);
                            }
                        }
                    });

            // If we have a StaticIpConfiguration attempt to apply it and
            // handle the result accordingly.
            if (mStaticIpConfig != null) {
                if (applyStaticIpConfig()) {
                    sendMessage(CMD_UPDATE_DHCPV4_RESULTS, new DhcpResults(mStaticIpConfig));
                } else {
                    sendMessage(CMD_UPDATE_DHCPV4_RESULTS);
                }
            } else {
                // Start DHCPv4.
                makeDhcpStateMachine();
                mDhcpStateMachine.registerForPreDhcpNotification();
                mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_START_DHCP);
            }
        }

        @Override
        public void exit() {
            mIpReachabilityMonitor.stop();
            mIpReachabilityMonitor = null;

            if (mDhcpStateMachine != null) {
                mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_STOP_DHCP);
                mDhcpStateMachine.doQuit();
                mDhcpStateMachine = null;
            }

            resetLinkProperties();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_STOP:
                    transitionTo(mStoppedState);
                    break;

                case CMD_START:
                    Log.e(TAG, "ALERT: START received in StartedState. Please fix caller.");
                    break;

                case CMD_CONFIRM:
                    // TODO: Possibly introduce a second type of confirmation
                    // that both probes (a) on-link neighbors and (b) does
                    // a DHCPv4 RENEW.  We used to do this on Wi-Fi framework
                    // roams.
                    if (mCallback.usingIpReachabilityMonitor()) {
                        mIpReachabilityMonitor.probeAll();
                    }
                    break;

                case CMD_UPDATE_DHCPV4_RESULTS: {
                    final DhcpResults dhcpResults = (DhcpResults) msg.obj;
                    if (dhcpResults != null) {
                        mDhcpResults = new DhcpResults(dhcpResults);
                        setLinkProperties(assembleLinkProperties());
                        mCallback.onIPv4ProvisioningSuccess(dhcpResults);
                    } else {
                        clearIPv4Address();
                        mDhcpResults = null;
                        setLinkProperties(assembleLinkProperties());
                        mCallback.onIPv4ProvisioningFailure();
                    }
                    break;
                }

                case EVENT_PRE_DHCP_ACTION_COMPLETE:
                    // It's possible to reach here if, for example, someone
                    // calls completedPreDhcpAction() after provisioning with
                    // a static IP configuration.
                    if (mDhcpStateMachine != null) {
                        mDhcpStateMachine.sendMessage(
                                DhcpStateMachine.CMD_PRE_DHCP_ACTION_COMPLETE);
                    }
                    break;

                case EVENT_NETLINK_LINKPROPERTIES_CHANGED: {
                    final LinkProperties newLp = assembleLinkProperties();
                    final ProvisioningChange delta = setLinkProperties(newLp);

                    // NOTE: The only receiver of these callbacks currently
                    // treats all three of them identically, namely it calls
                    // IpManager#getLinkProperties() and makes its own determination.
                    switch (delta) {
                        case GAINED_PROVISIONING:
                            mCallback.onProvisioningSuccess(newLp);
                            break;

                        case LOST_PROVISIONING:
                            mCallback.onProvisioningFailure(newLp);
                            break;

                        default:
                            // TODO: Only notify on STILL_PROVISIONED?
                            mCallback.onLinkPropertiesChange(newLp);
                            break;
                    }
                    break;
                }

                case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                    mCallback.onPreDhcpAction();
                    break;

                case DhcpStateMachine.CMD_POST_DHCP_ACTION: {
                    // Note that onPostDhcpAction() is likely to be
                    // asynchronous, and thus there is no guarantee that we
                    // will be able to observe any of its effects here.
                    mCallback.onPostDhcpAction();

                    final DhcpResults dhcpResults = (DhcpResults) msg.obj;
                    switch (msg.arg1) {
                        case DhcpStateMachine.DHCP_SUCCESS:
                            mDhcpResults = new DhcpResults(dhcpResults);
                            setLinkProperties(assembleLinkProperties());
                            mCallback.onIPv4ProvisioningSuccess(dhcpResults);
                            break;
                        case DhcpStateMachine.DHCP_FAILURE:
                            clearIPv4Address();
                            mDhcpResults = null;
                            setLinkProperties(assembleLinkProperties());
                            mCallback.onIPv4ProvisioningFailure();
                            break;
                        default:
                            Log.e(TAG, "Unknown CMD_POST_DHCP_ACTION status:" + msg.arg1);
                    }
                    break;
                }

                case DhcpStateMachine.CMD_ON_QUIT:
                    // CMD_ON_QUIT is really more like "EVENT_ON_QUIT".
                    // Regardless, we ignore it.
                    //
                    // TODO: Figure out if this is actually useful and if not
                    // expunge it.
                    break;

                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private boolean applyStaticIpConfig() {
            final InterfaceConfiguration ifcg = new InterfaceConfiguration();
            ifcg.setLinkAddress(mStaticIpConfig.ipAddress);
            ifcg.setInterfaceUp();
            try {
                mNwService.setInterfaceConfig(mInterfaceName, ifcg);
                if (DBG) Log.d(TAG, "Static IP configuration succeeded");
            } catch (IllegalStateException | RemoteException e) {
                Log.e(TAG, "Static IP configuration failed: ", e);
                return false;
            }

            return true;
        }

        private void makeDhcpStateMachine() {
            final boolean usingLegacyDhcp = (Settings.Global.getInt(
                    mContext.getContentResolver(),
                    Settings.Global.LEGACY_DHCP_CLIENT, 0) == 1);

            if (usingLegacyDhcp) {
                mDhcpStateMachine = DhcpStateMachine.makeDhcpStateMachine(
                        mContext,
                        IpManager.this,
                        mInterfaceName);
            } else {
                mDhcpStateMachine = DhcpClient.makeDhcpStateMachine(
                        mContext,
                        IpManager.this,
                        mInterfaceName);
            }
        }
    }
}
