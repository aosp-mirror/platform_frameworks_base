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

import static android.net.NetworkUtils.numericToInetAddress;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;
import static android.net.util.NetworkConstants.FF;
import static android.net.util.NetworkConstants.RFC7421_PREFIX_LENGTH;
import static android.net.util.NetworkConstants.asByte;

import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.INetworkStackStatusCallback;
import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkStackClient;
import android.net.RouteInfo;
import android.net.dhcp.DhcpServerCallbacks;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.DhcpServingParamsParcelExt;
import android.net.dhcp.IDhcpServer;
import android.net.ip.RouterAdvertisementDaemon.RaParams;
import android.net.util.InterfaceParams;
import android.net.util.InterfaceSet;
import android.net.util.NetdService;
import android.net.util.SharedLog;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.MessageUtils;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Provides the interface to IP-layer serving functionality for a given network
 * interface, e.g. for tethering or "local-only hotspot" mode.
 *
 * @hide
 */
public class IpServer extends StateMachine {
    public static final int STATE_UNAVAILABLE = 0;
    public static final int STATE_AVAILABLE   = 1;
    public static final int STATE_TETHERED    = 2;
    public static final int STATE_LOCAL_ONLY  = 3;

    public static String getStateString(int state) {
        switch (state) {
            case STATE_UNAVAILABLE: return "UNAVAILABLE";
            case STATE_AVAILABLE:   return "AVAILABLE";
            case STATE_TETHERED:    return "TETHERED";
            case STATE_LOCAL_ONLY:  return "LOCAL_ONLY";
        }
        return "UNKNOWN: " + state;
    }

    private static final byte DOUG_ADAMS = (byte) 42;

    private static final String USB_NEAR_IFACE_ADDR = "192.168.42.129";
    private static final int USB_PREFIX_LENGTH = 24;
    private static final String WIFI_HOST_IFACE_ADDR = "192.168.43.1";
    private static final int WIFI_HOST_IFACE_PREFIX_LENGTH = 24;

    // TODO: have PanService use some visible version of this constant
    private static final String BLUETOOTH_IFACE_ADDR = "192.168.44.1";
    private static final int BLUETOOTH_DHCP_PREFIX_LENGTH = 24;

    // TODO: have this configurable
    private static final int DHCP_LEASE_TIME_SECS = 3600;

    private final static String TAG = "IpServer";
    private final static boolean DBG = false;
    private final static boolean VDBG = false;
    private static final Class[] messageClasses = {
            IpServer.class
    };
    private static final SparseArray<String> sMagicDecoderRing =
            MessageUtils.findMessageNames(messageClasses);

    public static class Callback {
        /**
         * Notify that |who| has changed its tethering state.
         *
         * @param who the calling instance of IpServer
         * @param state one of STATE_*
         * @param lastError one of ConnectivityManager.TETHER_ERROR_*
         */
        public void updateInterfaceState(IpServer who, int state, int lastError) {}

        /**
         * Notify that |who| has new LinkProperties.
         *
         * @param who the calling instance of IpServer
         * @param newLp the new LinkProperties to report
         */
        public void updateLinkProperties(IpServer who, LinkProperties newLp) {}
    }

    public static class Dependencies {
        public RouterAdvertisementDaemon getRouterAdvertisementDaemon(InterfaceParams ifParams) {
            return new RouterAdvertisementDaemon(ifParams);
        }

        public InterfaceParams getInterfaceParams(String ifName) {
            return InterfaceParams.getByName(ifName);
        }

        public INetd getNetdService() {
            return NetdService.getInstance();
        }

        /**
         * Create a DhcpServer instance to be used by IpServer.
         */
        public void makeDhcpServer(String ifName, DhcpServingParamsParcel params,
                DhcpServerCallbacks cb) {
            NetworkStackClient.getInstance().makeDhcpServer(ifName, params, cb);
        }
    }

    private static final int BASE_IFACE              = Protocol.BASE_TETHERING + 100;
    // request from the user that it wants to tether
    public static final int CMD_TETHER_REQUESTED            = BASE_IFACE + 2;
    // request from the user that it wants to untether
    public static final int CMD_TETHER_UNREQUESTED          = BASE_IFACE + 3;
    // notification that this interface is down
    public static final int CMD_INTERFACE_DOWN              = BASE_IFACE + 4;
    // notification from the master SM that it had trouble enabling IP Forwarding
    public static final int CMD_IP_FORWARDING_ENABLE_ERROR  = BASE_IFACE + 7;
    // notification from the master SM that it had trouble disabling IP Forwarding
    public static final int CMD_IP_FORWARDING_DISABLE_ERROR = BASE_IFACE + 8;
    // notification from the master SM that it had trouble starting tethering
    public static final int CMD_START_TETHERING_ERROR       = BASE_IFACE + 9;
    // notification from the master SM that it had trouble stopping tethering
    public static final int CMD_STOP_TETHERING_ERROR        = BASE_IFACE + 10;
    // notification from the master SM that it had trouble setting the DNS forwarders
    public static final int CMD_SET_DNS_FORWARDERS_ERROR    = BASE_IFACE + 11;
    // the upstream connection has changed
    public static final int CMD_TETHER_CONNECTION_CHANGED   = BASE_IFACE + 12;
    // new IPv6 tethering parameters need to be processed
    public static final int CMD_IPV6_TETHER_UPDATE          = BASE_IFACE + 13;

    private final State mInitialState;
    private final State mLocalHotspotState;
    private final State mTetheredState;
    private final State mUnavailableState;

    private final SharedLog mLog;
    private final INetworkManagementService mNMService;
    private final INetd mNetd;
    private final INetworkStatsService mStatsService;
    private final Callback mCallback;
    private final InterfaceController mInterfaceCtrl;

    private final String mIfaceName;
    private final int mInterfaceType;
    private final LinkProperties mLinkProperties;
    private final boolean mUsingLegacyDhcp;

    private final Dependencies mDeps;

    private int mLastError;
    private int mServingMode;
    private InterfaceSet mUpstreamIfaceSet;  // may change over time
    private InterfaceParams mInterfaceParams;
    // TODO: De-duplicate this with mLinkProperties above. Currently, these link
    // properties are those selected by the IPv6TetheringCoordinator and relayed
    // to us. By comparison, mLinkProperties contains the addresses and directly
    // connected routes that have been formed from these properties iff. we have
    // succeeded in configuring them and are able to announce them within Router
    // Advertisements (otherwise, we do not add them to mLinkProperties at all).
    private LinkProperties mLastIPv6LinkProperties;
    private RouterAdvertisementDaemon mRaDaemon;

    // To be accessed only on the handler thread
    private int mDhcpServerStartIndex = 0;
    private IDhcpServer mDhcpServer;
    private RaParams mLastRaParams;

    public IpServer(
            String ifaceName, Looper looper, int interfaceType, SharedLog log,
            INetworkManagementService nMService, INetworkStatsService statsService,
            Callback callback, boolean usingLegacyDhcp, Dependencies deps) {
        super(ifaceName, looper);
        mLog = log.forSubComponent(ifaceName);
        mNMService = nMService;
        mNetd = deps.getNetdService();
        mStatsService = statsService;
        mCallback = callback;
        mInterfaceCtrl = new InterfaceController(ifaceName, mNetd, mLog);
        mIfaceName = ifaceName;
        mInterfaceType = interfaceType;
        mLinkProperties = new LinkProperties();
        mUsingLegacyDhcp = usingLegacyDhcp;
        mDeps = deps;
        resetLinkProperties();
        mLastError = ConnectivityManager.TETHER_ERROR_NO_ERROR;
        mServingMode = STATE_AVAILABLE;

        mInitialState = new InitialState();
        mLocalHotspotState = new LocalHotspotState();
        mTetheredState = new TetheredState();
        mUnavailableState = new UnavailableState();
        addState(mInitialState);
        addState(mLocalHotspotState);
        addState(mTetheredState);
        addState(mUnavailableState);

        setInitialState(mInitialState);
    }

    public String interfaceName() { return mIfaceName; }

    public int interfaceType() { return mInterfaceType; }

    public int lastError() { return mLastError; }

    public int servingMode() { return mServingMode; }

    public LinkProperties linkProperties() { return new LinkProperties(mLinkProperties); }

    public void stop() { sendMessage(CMD_INTERFACE_DOWN); }

    public void unwanted() { sendMessage(CMD_TETHER_UNREQUESTED); }

    /**
     * Internals.
     */

    private boolean startIPv4() { return configureIPv4(true); }

    /**
     * Convenience wrapper around INetworkStackStatusCallback to run callbacks on the IpServer
     * handler.
     *
     * <p>Different instances of this class can be created for each call to IDhcpServer methods,
     * with different implementations of the callback, to differentiate handling of success/error in
     * each call.
     */
    private abstract class OnHandlerStatusCallback extends INetworkStackStatusCallback.Stub {
        @Override
        public void onStatusAvailable(int statusCode) {
            getHandler().post(() -> callback(statusCode));
        }

        public abstract void callback(int statusCode);
    }

    private class DhcpServerCallbacksImpl extends DhcpServerCallbacks {
        private final int mStartIndex;

        private DhcpServerCallbacksImpl(int startIndex) {
            mStartIndex = startIndex;
        }

        @Override
        public void onDhcpServerCreated(int statusCode, IDhcpServer server) throws RemoteException {
            getHandler().post(() -> {
                // We are on the handler thread: mDhcpServerStartIndex can be read safely.
                if (mStartIndex != mDhcpServerStartIndex) {
                    // This start request is obsolete. When the |server| binder token goes out of
                    // scope, the garbage collector will finalize it, which causes the network stack
                    // process garbage collector to collect the server itself.
                    return;
                }

                if (statusCode != STATUS_SUCCESS) {
                    mLog.e("Error obtaining DHCP server: " + statusCode);
                    handleError();
                    return;
                }

                mDhcpServer = server;
                try {
                    mDhcpServer.start(new OnHandlerStatusCallback() {
                        @Override
                        public void callback(int startStatusCode) {
                            if (startStatusCode != STATUS_SUCCESS) {
                                mLog.e("Error starting DHCP server: " + startStatusCode);
                                handleError();
                            }
                        }
                    });
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            });
        }

        private void handleError() {
            mLastError = ConnectivityManager.TETHER_ERROR_DHCPSERVER_ERROR;
            transitionTo(mInitialState);
        }
    }

    private boolean startDhcp(Inet4Address addr, int prefixLen) {
        if (mUsingLegacyDhcp) {
            return true;
        }
        final DhcpServingParamsParcel params;
        params = new DhcpServingParamsParcelExt()
                .setDefaultRouters(addr)
                .setDhcpLeaseTimeSecs(DHCP_LEASE_TIME_SECS)
                .setDnsServers(addr)
                .setServerAddr(new LinkAddress(addr, prefixLen))
                .setMetered(true);
        // TODO: also advertise link MTU

        mDhcpServerStartIndex++;
        mDeps.makeDhcpServer(
                mIfaceName, params, new DhcpServerCallbacksImpl(mDhcpServerStartIndex));
        return true;
    }

    private void stopDhcp() {
        // Make all previous start requests obsolete so servers are not started later
        mDhcpServerStartIndex++;

        if (mDhcpServer != null) {
            try {
                mDhcpServer.stop(new OnHandlerStatusCallback() {
                    @Override
                    public void callback(int statusCode) {
                        if (statusCode != STATUS_SUCCESS) {
                            mLog.e("Error stopping DHCP server: " + statusCode);
                            mLastError = ConnectivityManager.TETHER_ERROR_DHCPSERVER_ERROR;
                            // Not much more we can do here
                        }
                    }
                });
                mDhcpServer = null;
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    private boolean configureDhcp(boolean enable, Inet4Address addr, int prefixLen) {
        if (enable) {
            return startDhcp(addr, prefixLen);
        } else {
            stopDhcp();
            return true;
        }
    }

    private void stopIPv4() {
        configureIPv4(false);
        // NOTE: All of configureIPv4() will be refactored out of existence
        // into calls to InterfaceController, shared with startIPv4().
        mInterfaceCtrl.clearIPv4Address();
    }

    // TODO: Refactor this in terms of calls to InterfaceController.
    private boolean configureIPv4(boolean enabled) {
        if (VDBG) Log.d(TAG, "configureIPv4(" + enabled + ")");

        // TODO: Replace this hard-coded information with dynamically selected
        // config passed down to us by a higher layer IP-coordinating element.
        String ipAsString = null;
        int prefixLen = 0;
        if (mInterfaceType == ConnectivityManager.TETHERING_USB) {
            ipAsString = USB_NEAR_IFACE_ADDR;
            prefixLen = USB_PREFIX_LENGTH;
        } else if (mInterfaceType == ConnectivityManager.TETHERING_WIFI) {
            ipAsString = getRandomWifiIPv4Address();
            prefixLen = WIFI_HOST_IFACE_PREFIX_LENGTH;
        } else {
            // BT configures the interface elsewhere: only start DHCP.
            final Inet4Address srvAddr = (Inet4Address) numericToInetAddress(BLUETOOTH_IFACE_ADDR);
            return configureDhcp(enabled, srvAddr, BLUETOOTH_DHCP_PREFIX_LENGTH);
        }

        final LinkAddress linkAddr;
        try {
            final InterfaceConfiguration ifcg = mNMService.getInterfaceConfig(mIfaceName);
            if (ifcg == null) {
                mLog.e("Received null interface config");
                return false;
            }

            InetAddress addr = numericToInetAddress(ipAsString);
            linkAddr = new LinkAddress(addr, prefixLen);
            ifcg.setLinkAddress(linkAddr);
            if (mInterfaceType == ConnectivityManager.TETHERING_WIFI) {
                // The WiFi stack has ownership of the interface up/down state.
                // It is unclear whether the Bluetooth or USB stacks will manage their own
                // state.
                ifcg.ignoreInterfaceUpDownStatus();
            } else {
                if (enabled) {
                    ifcg.setInterfaceUp();
                } else {
                    ifcg.setInterfaceDown();
                }
            }
            ifcg.clearFlag("running");
            mNMService.setInterfaceConfig(mIfaceName, ifcg);

            if (!configureDhcp(enabled, (Inet4Address) addr, prefixLen)) {
                return false;
            }
        } catch (Exception e) {
            mLog.e("Error configuring interface " + e);
            return false;
        }

        // Directly-connected route.
        final RouteInfo route = new RouteInfo(linkAddr);
        if (enabled) {
            mLinkProperties.addLinkAddress(linkAddr);
            mLinkProperties.addRoute(route);
        } else {
            mLinkProperties.removeLinkAddress(linkAddr);
            mLinkProperties.removeRoute(route);
        }
        return true;
    }

    private String getRandomWifiIPv4Address() {
        try {
            byte[] bytes = numericToInetAddress(WIFI_HOST_IFACE_ADDR).getAddress();
            bytes[3] = getRandomSanitizedByte(DOUG_ADAMS, asByte(0), asByte(1), FF);
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (Exception e) {
            return WIFI_HOST_IFACE_ADDR;
        }
    }

    private boolean startIPv6() {
        mInterfaceParams = mDeps.getInterfaceParams(mIfaceName);
        if (mInterfaceParams == null) {
            mLog.e("Failed to find InterfaceParams");
            stopIPv6();
            return false;
        }

        mRaDaemon = mDeps.getRouterAdvertisementDaemon(mInterfaceParams);
        if (!mRaDaemon.start()) {
            stopIPv6();
            return false;
        }

        return true;
    }

    private void stopIPv6() {
        mInterfaceParams = null;
        setRaParams(null);

        if (mRaDaemon != null) {
            mRaDaemon.stop();
            mRaDaemon = null;
        }
    }

    // IPv6TetheringCoordinator sends updates with carefully curated IPv6-only
    // LinkProperties. These have extraneous data filtered out and only the
    // necessary prefixes included (per its prefix distribution policy).
    //
    // TODO: Evaluate using a data structure than is more directly suited to
    // communicating only the relevant information.
    private void updateUpstreamIPv6LinkProperties(LinkProperties v6only) {
        if (mRaDaemon == null) return;

        // Avoid unnecessary work on spurious updates.
        if (Objects.equals(mLastIPv6LinkProperties, v6only)) {
            return;
        }

        RaParams params = null;

        if (v6only != null) {
            params = new RaParams();
            params.mtu = v6only.getMtu();
            params.hasDefaultRoute = v6only.hasIPv6DefaultRoute();

            if (params.hasDefaultRoute) params.hopLimit = getHopLimit(v6only.getInterfaceName());

            for (LinkAddress linkAddr : v6only.getLinkAddresses()) {
                if (linkAddr.getPrefixLength() != RFC7421_PREFIX_LENGTH) continue;

                final IpPrefix prefix = new IpPrefix(
                        linkAddr.getAddress(), linkAddr.getPrefixLength());
                params.prefixes.add(prefix);

                final Inet6Address dnsServer = getLocalDnsIpFor(prefix);
                if (dnsServer != null) {
                    params.dnses.add(dnsServer);
                }
            }
        }
        // If v6only is null, we pass in null to setRaParams(), which handles
        // deprecation of any existing RA data.

        setRaParams(params);
        mLastIPv6LinkProperties = v6only;
    }

    private void configureLocalIPv6Routes(
            HashSet<IpPrefix> deprecatedPrefixes, HashSet<IpPrefix> newPrefixes) {
        // [1] Remove the routes that are deprecated.
        if (!deprecatedPrefixes.isEmpty()) {
            final ArrayList<RouteInfo> toBeRemoved =
                    getLocalRoutesFor(mIfaceName, deprecatedPrefixes);
            try {
                final int removalFailures = mNMService.removeRoutesFromLocalNetwork(toBeRemoved);
                if (removalFailures > 0) {
                    mLog.e(String.format("Failed to remove %d IPv6 routes from local table.",
                            removalFailures));
                }
            } catch (RemoteException e) {
                mLog.e("Failed to remove IPv6 routes from local table: " + e);
            }

            for (RouteInfo route : toBeRemoved) mLinkProperties.removeRoute(route);
        }

        // [2] Add only the routes that have not previously been added.
        if (newPrefixes != null && !newPrefixes.isEmpty()) {
            HashSet<IpPrefix> addedPrefixes = (HashSet) newPrefixes.clone();
            if (mLastRaParams != null) {
                addedPrefixes.removeAll(mLastRaParams.prefixes);
            }

            if (!addedPrefixes.isEmpty()) {
                final ArrayList<RouteInfo> toBeAdded =
                        getLocalRoutesFor(mIfaceName, addedPrefixes);
                try {
                    // It's safe to call addInterfaceToLocalNetwork() even if
                    // the interface is already in the local_network. Note also
                    // that adding routes that already exist does not cause an
                    // error (EEXIST is silently ignored).
                    mNMService.addInterfaceToLocalNetwork(mIfaceName, toBeAdded);
                } catch (Exception e) {
                    mLog.e("Failed to add IPv6 routes to local table: " + e);
                }

                for (RouteInfo route : toBeAdded) mLinkProperties.addRoute(route);
            }
        }
    }

    private void configureLocalIPv6Dns(
            HashSet<Inet6Address> deprecatedDnses, HashSet<Inet6Address> newDnses) {
        // TODO: Is this really necessary? Can we not fail earlier if INetd cannot be located?
        if (mNetd == null) {
            if (newDnses != null) newDnses.clear();
            mLog.e("No netd service instance available; not setting local IPv6 addresses");
            return;
        }

        // [1] Remove deprecated local DNS IP addresses.
        if (!deprecatedDnses.isEmpty()) {
            for (Inet6Address dns : deprecatedDnses) {
                if (!mInterfaceCtrl.removeAddress(dns, RFC7421_PREFIX_LENGTH)) {
                    mLog.e("Failed to remove local dns IP " + dns);
                }

                mLinkProperties.removeLinkAddress(new LinkAddress(dns, RFC7421_PREFIX_LENGTH));
            }
        }

        // [2] Add only the local DNS IP addresses that have not previously been added.
        if (newDnses != null && !newDnses.isEmpty()) {
            final HashSet<Inet6Address> addedDnses = (HashSet) newDnses.clone();
            if (mLastRaParams != null) {
                addedDnses.removeAll(mLastRaParams.dnses);
            }

            for (Inet6Address dns : addedDnses) {
                if (!mInterfaceCtrl.addAddress(dns, RFC7421_PREFIX_LENGTH)) {
                    mLog.e("Failed to add local dns IP " + dns);
                    newDnses.remove(dns);
                }

                mLinkProperties.addLinkAddress(new LinkAddress(dns, RFC7421_PREFIX_LENGTH));
            }
        }

        try {
            mNetd.tetherApplyDnsInterfaces();
        } catch (ServiceSpecificException | RemoteException e) {
            mLog.e("Failed to update local DNS caching server");
            if (newDnses != null) newDnses.clear();
        }
    }

    private byte getHopLimit(String upstreamIface) {
        try {
            int upstreamHopLimit = Integer.parseUnsignedInt(
                    mNetd.getProcSysNet(INetd.IPV6, INetd.CONF, upstreamIface, "hop_limit"));
            // Add one hop to account for this forwarding device
            upstreamHopLimit++;
            // Cap the hop limit to 255.
            return (byte) Integer.min(upstreamHopLimit, 255);
        } catch (Exception e) {
            mLog.e("Failed to find upstream interface hop limit", e);
        }
        return RaParams.DEFAULT_HOPLIMIT;
    }

    private void setRaParams(RaParams newParams) {
        if (mRaDaemon != null) {
            final RaParams deprecatedParams =
                    RaParams.getDeprecatedRaParams(mLastRaParams, newParams);

            configureLocalIPv6Routes(deprecatedParams.prefixes,
                    (newParams != null) ? newParams.prefixes : null);

            configureLocalIPv6Dns(deprecatedParams.dnses,
                    (newParams != null) ? newParams.dnses : null);

            mRaDaemon.buildNewRa(deprecatedParams, newParams);
        }

        mLastRaParams = newParams;
    }

    private void logMessage(State state, int what) {
        mLog.log(state.getName() + " got " + sMagicDecoderRing.get(what, Integer.toString(what)));
    }

    private void sendInterfaceState(int newInterfaceState) {
        mServingMode = newInterfaceState;
        mCallback.updateInterfaceState(this, newInterfaceState, mLastError);
        sendLinkProperties();
    }

    private void sendLinkProperties() {
        mCallback.updateLinkProperties(this, new LinkProperties(mLinkProperties));
    }

    private void resetLinkProperties() {
        mLinkProperties.clear();
        mLinkProperties.setInterfaceName(mIfaceName);
    }

    class InitialState extends State {
        @Override
        public void enter() {
            sendInterfaceState(STATE_AVAILABLE);
        }

        @Override
        public boolean processMessage(Message message) {
            logMessage(this, message.what);
            switch (message.what) {
                case CMD_TETHER_REQUESTED:
                    mLastError = ConnectivityManager.TETHER_ERROR_NO_ERROR;
                    switch (message.arg1) {
                        case STATE_LOCAL_ONLY:
                            transitionTo(mLocalHotspotState);
                            break;
                        case STATE_TETHERED:
                            transitionTo(mTetheredState);
                            break;
                        default:
                            mLog.e("Invalid tethering interface serving state specified.");
                    }
                    break;
                case CMD_INTERFACE_DOWN:
                    transitionTo(mUnavailableState);
                    break;
                case CMD_IPV6_TETHER_UPDATE:
                    updateUpstreamIPv6LinkProperties((LinkProperties) message.obj);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class BaseServingState extends State {
        @Override
        public void enter() {
            if (!startIPv4()) {
                mLastError = ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR;
                return;
            }

            try {
                mNMService.tetherInterface(mIfaceName);
            } catch (Exception e) {
                mLog.e("Error Tethering: " + e);
                mLastError = ConnectivityManager.TETHER_ERROR_TETHER_IFACE_ERROR;
                return;
            }

            if (!startIPv6()) {
                mLog.e("Failed to startIPv6");
                // TODO: Make this a fatal error once Bluetooth IPv6 is sorted.
                return;
            }
        }

        @Override
        public void exit() {
            // Note that at this point, we're leaving the tethered state.  We can fail any
            // of these operations, but it doesn't really change that we have to try them
            // all in sequence.
            stopIPv6();

            try {
                mNMService.untetherInterface(mIfaceName);
            } catch (Exception e) {
                mLastError = ConnectivityManager.TETHER_ERROR_UNTETHER_IFACE_ERROR;
                mLog.e("Failed to untether interface: " + e);
            }

            stopIPv4();

            resetLinkProperties();
        }

        @Override
        public boolean processMessage(Message message) {
            logMessage(this, message.what);
            switch (message.what) {
                case CMD_TETHER_UNREQUESTED:
                    transitionTo(mInitialState);
                    if (DBG) Log.d(TAG, "Untethered (unrequested)" + mIfaceName);
                    break;
                case CMD_INTERFACE_DOWN:
                    transitionTo(mUnavailableState);
                    if (DBG) Log.d(TAG, "Untethered (ifdown)" + mIfaceName);
                    break;
                case CMD_IPV6_TETHER_UPDATE:
                    updateUpstreamIPv6LinkProperties((LinkProperties) message.obj);
                    sendLinkProperties();
                    break;
                case CMD_IP_FORWARDING_ENABLE_ERROR:
                case CMD_IP_FORWARDING_DISABLE_ERROR:
                case CMD_START_TETHERING_ERROR:
                case CMD_STOP_TETHERING_ERROR:
                case CMD_SET_DNS_FORWARDERS_ERROR:
                    mLastError = ConnectivityManager.TETHER_ERROR_MASTER_ERROR;
                    transitionTo(mInitialState);
                    break;
                default:
                    return false;
            }
            return true;
        }
    }

    // Handling errors in BaseServingState.enter() by transitioning is
    // problematic because transitioning during a multi-state jump yields
    // a Log.wtf(). Ultimately, there should be only one ServingState,
    // and forwarding and NAT rules should be handled by a coordinating
    // functional element outside of IpServer.
    class LocalHotspotState extends BaseServingState {
        @Override
        public void enter() {
            super.enter();
            if (mLastError != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                transitionTo(mInitialState);
            }

            if (DBG) Log.d(TAG, "Local hotspot " + mIfaceName);
            sendInterfaceState(STATE_LOCAL_ONLY);
        }

        @Override
        public boolean processMessage(Message message) {
            if (super.processMessage(message)) return true;

            logMessage(this, message.what);
            switch (message.what) {
                case CMD_TETHER_REQUESTED:
                    mLog.e("CMD_TETHER_REQUESTED while in local-only hotspot mode.");
                    break;
                case CMD_TETHER_CONNECTION_CHANGED:
                    // Ignored in local hotspot state.
                    break;
                default:
                    return false;
            }
            return true;
        }
    }

    // Handling errors in BaseServingState.enter() by transitioning is
    // problematic because transitioning during a multi-state jump yields
    // a Log.wtf(). Ultimately, there should be only one ServingState,
    // and forwarding and NAT rules should be handled by a coordinating
    // functional element outside of IpServer.
    class TetheredState extends BaseServingState {
        @Override
        public void enter() {
            super.enter();
            if (mLastError != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                transitionTo(mInitialState);
            }

            if (DBG) Log.d(TAG, "Tethered " + mIfaceName);
            sendInterfaceState(STATE_TETHERED);
        }

        @Override
        public void exit() {
            cleanupUpstream();
            super.exit();
        }

        private void cleanupUpstream() {
            if (mUpstreamIfaceSet == null) return;

            for (String ifname : mUpstreamIfaceSet.ifnames) cleanupUpstreamInterface(ifname);
            mUpstreamIfaceSet = null;
        }

        private void cleanupUpstreamInterface(String upstreamIface) {
            // Note that we don't care about errors here.
            // Sometimes interfaces are gone before we get
            // to remove their rules, which generates errors.
            // Just do the best we can.
            try {
                // About to tear down NAT; gather remaining statistics.
                mStatsService.forceUpdate();
            } catch (Exception e) {
                if (VDBG) Log.e(TAG, "Exception in forceUpdate: " + e.toString());
            }
            try {
                mNMService.stopInterfaceForwarding(mIfaceName, upstreamIface);
            } catch (Exception e) {
                if (VDBG) Log.e(TAG, "Exception in removeInterfaceForward: " + e.toString());
            }
            try {
                mNMService.disableNat(mIfaceName, upstreamIface);
            } catch (Exception e) {
                if (VDBG) Log.e(TAG, "Exception in disableNat: " + e.toString());
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (super.processMessage(message)) return true;

            logMessage(this, message.what);
            switch (message.what) {
                case CMD_TETHER_REQUESTED:
                    mLog.e("CMD_TETHER_REQUESTED while already tethering.");
                    break;
                case CMD_TETHER_CONNECTION_CHANGED:
                    final InterfaceSet newUpstreamIfaceSet = (InterfaceSet) message.obj;
                    if (noChangeInUpstreamIfaceSet(newUpstreamIfaceSet)) {
                        if (VDBG) Log.d(TAG, "Connection changed noop - dropping");
                        break;
                    }

                    if (newUpstreamIfaceSet == null) {
                        cleanupUpstream();
                        break;
                    }

                    for (String removed : upstreamInterfacesRemoved(newUpstreamIfaceSet)) {
                        cleanupUpstreamInterface(removed);
                    }

                    final Set<String> added = upstreamInterfacesAdd(newUpstreamIfaceSet);
                    // This makes the call to cleanupUpstream() in the error
                    // path for any interface neatly cleanup all the interfaces.
                    mUpstreamIfaceSet = newUpstreamIfaceSet;

                    for (String ifname : added) {
                        try {
                            mNMService.enableNat(mIfaceName, ifname);
                            mNMService.startInterfaceForwarding(mIfaceName, ifname);
                        } catch (Exception e) {
                            mLog.e("Exception enabling NAT: " + e);
                            cleanupUpstream();
                            mLastError = ConnectivityManager.TETHER_ERROR_ENABLE_NAT_ERROR;
                            transitionTo(mInitialState);
                            return true;
                        }
                    }
                    break;
                default:
                    return false;
            }
            return true;
        }

        private boolean noChangeInUpstreamIfaceSet(InterfaceSet newIfaces) {
            if (mUpstreamIfaceSet == null && newIfaces == null) return true;
            if (mUpstreamIfaceSet != null && newIfaces != null) {
                return mUpstreamIfaceSet.equals(newIfaces);
            }
            return false;
        }

        private Set<String> upstreamInterfacesRemoved(InterfaceSet newIfaces) {
            if (mUpstreamIfaceSet == null) return new HashSet<>();

            final HashSet<String> removed = new HashSet<>(mUpstreamIfaceSet.ifnames);
            removed.removeAll(newIfaces.ifnames);
            return removed;
        }

        private Set<String> upstreamInterfacesAdd(InterfaceSet newIfaces) {
            final HashSet<String> added = new HashSet<>(newIfaces.ifnames);
            if (mUpstreamIfaceSet != null) added.removeAll(mUpstreamIfaceSet.ifnames);
            return added;
        }
    }

    /**
     * This state is terminal for the per interface state machine.  At this
     * point, the master state machine should have removed this interface
     * specific state machine from its list of possible recipients of
     * tethering requests.  The state machine itself will hang around until
     * the garbage collector finds it.
     */
    class UnavailableState extends State {
        @Override
        public void enter() {
            mLastError = ConnectivityManager.TETHER_ERROR_NO_ERROR;
            sendInterfaceState(STATE_UNAVAILABLE);
        }
    }

    // Accumulate routes representing "prefixes to be assigned to the local
    // interface", for subsequent modification of local_network routing.
    private static ArrayList<RouteInfo> getLocalRoutesFor(
            String ifname, HashSet<IpPrefix> prefixes) {
        final ArrayList<RouteInfo> localRoutes = new ArrayList<RouteInfo>();
        for (IpPrefix ipp : prefixes) {
            localRoutes.add(new RouteInfo(ipp, null, ifname));
        }
        return localRoutes;
    }

    // Given a prefix like 2001:db8::/64 return an address like 2001:db8::1.
    private static Inet6Address getLocalDnsIpFor(IpPrefix localPrefix) {
        final byte[] dnsBytes = localPrefix.getRawAddress();
        dnsBytes[dnsBytes.length - 1] = getRandomSanitizedByte(DOUG_ADAMS, asByte(0), asByte(1));
        try {
            return Inet6Address.getByAddress(null, dnsBytes, 0);
        } catch (UnknownHostException e) {
            Slog.wtf(TAG, "Failed to construct Inet6Address from: " + localPrefix);
            return null;
        }
    }

    private static byte getRandomSanitizedByte(byte dflt, byte... excluded) {
        final byte random = (byte) (new Random()).nextInt();
        for (int value : excluded) {
            if (random == value) return dflt;
        }
        return random;
    }
}
