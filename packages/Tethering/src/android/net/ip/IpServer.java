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

import static android.net.InetAddresses.parseNumericAddress;
import static android.net.RouteInfo.RTN_UNICAST;
import static android.net.TetheringManager.TetheringRequest.checkStaticAddressConfiguration;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;
import static android.net.shared.Inet4AddressUtils.intToInet4AddressHTH;
import static android.net.util.NetworkConstants.FF;
import static android.net.util.NetworkConstants.RFC7421_PREFIX_LENGTH;
import static android.net.util.NetworkConstants.asByte;
import static android.net.util.TetheringMessageBase.BASE_IPSERVER;
import static android.system.OsConstants.RT_SCOPE_UNIVERSE;

import android.net.INetd;
import android.net.INetworkStackStatusCallback;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.RouteInfo;
import android.net.TetherOffloadRuleParcel;
import android.net.TetheredClient;
import android.net.TetheringManager;
import android.net.TetheringRequestParcel;
import android.net.dhcp.DhcpLeaseParcelable;
import android.net.dhcp.DhcpServerCallbacks;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.DhcpServingParamsParcelExt;
import android.net.dhcp.IDhcpEventCallbacks;
import android.net.dhcp.IDhcpServer;
import android.net.ip.IpNeighborMonitor.NeighborEvent;
import android.net.ip.RouterAdvertisementDaemon.RaParams;
import android.net.shared.NetdUtils;
import android.net.shared.RouteUtils;
import android.net.util.InterfaceParams;
import android.net.util.InterfaceSet;
import android.net.util.PrefixUtils;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.util.MessageUtils;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Get string name of |state|.*/
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
    private static final String WIFI_P2P_IFACE_ADDR = "192.168.49.1";
    private static final int WIFI_P2P_IFACE_PREFIX_LENGTH = 24;
    private static final String ETHERNET_IFACE_ADDR = "192.168.50.1";
    private static final int ETHERNET_IFACE_PREFIX_LENGTH = 24;

    // TODO: remove this constant after introducing PrivateAddressCoordinator.
    private static final List<IpPrefix> NCM_PREFIXES = Collections.unmodifiableList(
            Arrays.asList(
                    new IpPrefix("192.168.42.0/24"),
                    new IpPrefix("192.168.51.0/24"),
                    new IpPrefix("192.168.52.0/24"),
                    new IpPrefix("192.168.53.0/24")
    ));

    // TODO: have PanService use some visible version of this constant
    private static final String BLUETOOTH_IFACE_ADDR = "192.168.44.1";
    private static final int BLUETOOTH_DHCP_PREFIX_LENGTH = 24;

    // TODO: have this configurable
    private static final int DHCP_LEASE_TIME_SECS = 3600;

    private static final MacAddress NULL_MAC_ADDRESS = MacAddress.fromString("00:00:00:00:00:00");

    private static final String TAG = "IpServer";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;
    private static final Class[] sMessageClasses = {
            IpServer.class
    };
    private static final SparseArray<String> sMagicDecoderRing =
            MessageUtils.findMessageNames(sMessageClasses);

    /** IpServer callback. */
    public static class Callback {
        /**
         * Notify that |who| has changed its tethering state.
         *
         * @param who the calling instance of IpServer
         * @param state one of STATE_*
         * @param lastError one of TetheringManager.TETHER_ERROR_*
         */
        public void updateInterfaceState(IpServer who, int state, int lastError) { }

        /**
         * Notify that |who| has new LinkProperties.
         *
         * @param who the calling instance of IpServer
         * @param newLp the new LinkProperties to report
         */
        public void updateLinkProperties(IpServer who, LinkProperties newLp) { }

        /**
         * Notify that the DHCP leases changed in one of the IpServers.
         */
        public void dhcpLeasesChanged() { }
    }

    /** Capture IpServer dependencies, for injection. */
    public abstract static class Dependencies {
        /** Create an IpNeighborMonitor to be used by this IpServer */
        public IpNeighborMonitor getIpNeighborMonitor(Handler handler, SharedLog log,
                IpNeighborMonitor.NeighborEventConsumer consumer) {
            return new IpNeighborMonitor(handler, log, consumer);
        }

        /** Create a RouterAdvertisementDaemon instance to be used by IpServer.*/
        public RouterAdvertisementDaemon getRouterAdvertisementDaemon(InterfaceParams ifParams) {
            return new RouterAdvertisementDaemon(ifParams);
        }

        /** Get |ifName|'s interface information.*/
        public InterfaceParams getInterfaceParams(String ifName) {
            return InterfaceParams.getByName(ifName);
        }

        /** Get |ifName|'s interface index. */
        public int getIfindex(String ifName) {
            try {
                return NetworkInterface.getByName(ifName).getIndex();
            } catch (IOException | NullPointerException e) {
                Log.e(TAG, "Can't determine interface index for interface " + ifName);
                return 0;
            }
        }
        /** Create a DhcpServer instance to be used by IpServer. */
        public abstract void makeDhcpServer(String ifName, DhcpServingParamsParcel params,
                DhcpServerCallbacks cb);
    }

    // request from the user that it wants to tether
    public static final int CMD_TETHER_REQUESTED            = BASE_IPSERVER + 1;
    // request from the user that it wants to untether
    public static final int CMD_TETHER_UNREQUESTED          = BASE_IPSERVER + 2;
    // notification that this interface is down
    public static final int CMD_INTERFACE_DOWN              = BASE_IPSERVER + 3;
    // notification from the master SM that it had trouble enabling IP Forwarding
    public static final int CMD_IP_FORWARDING_ENABLE_ERROR  = BASE_IPSERVER + 4;
    // notification from the master SM that it had trouble disabling IP Forwarding
    public static final int CMD_IP_FORWARDING_DISABLE_ERROR = BASE_IPSERVER + 5;
    // notification from the master SM that it had trouble starting tethering
    public static final int CMD_START_TETHERING_ERROR       = BASE_IPSERVER + 6;
    // notification from the master SM that it had trouble stopping tethering
    public static final int CMD_STOP_TETHERING_ERROR        = BASE_IPSERVER + 7;
    // notification from the master SM that it had trouble setting the DNS forwarders
    public static final int CMD_SET_DNS_FORWARDERS_ERROR    = BASE_IPSERVER + 8;
    // the upstream connection has changed
    public static final int CMD_TETHER_CONNECTION_CHANGED   = BASE_IPSERVER + 9;
    // new IPv6 tethering parameters need to be processed
    public static final int CMD_IPV6_TETHER_UPDATE          = BASE_IPSERVER + 10;
    // new neighbor cache entry on our interface
    public static final int CMD_NEIGHBOR_EVENT              = BASE_IPSERVER + 11;
    // request from DHCP server that it wants to have a new prefix
    public static final int CMD_NEW_PREFIX_REQUEST          = BASE_IPSERVER + 12;

    private final State mInitialState;
    private final State mLocalHotspotState;
    private final State mTetheredState;
    private final State mUnavailableState;

    private final SharedLog mLog;
    private final INetd mNetd;
    private final Callback mCallback;
    private final InterfaceController mInterfaceCtrl;

    private final String mIfaceName;
    private final int mInterfaceType;
    private final LinkProperties mLinkProperties;
    private final boolean mUsingLegacyDhcp;
    private final boolean mUsingBpfOffload;

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
    private LinkAddress mIpv4Address;

    private LinkAddress mStaticIpv4ServerAddr;
    private LinkAddress mStaticIpv4ClientAddr;

    @NonNull
    private List<TetheredClient> mDhcpLeases = Collections.emptyList();

    private int mLastIPv6UpstreamIfindex = 0;

    private class MyNeighborEventConsumer implements IpNeighborMonitor.NeighborEventConsumer {
        public void accept(NeighborEvent e) {
            sendMessage(CMD_NEIGHBOR_EVENT, e);
        }
    }

    static class Ipv6ForwardingRule {
        public final int upstreamIfindex;
        public final int downstreamIfindex;
        public final Inet6Address address;
        public final MacAddress srcMac;
        public final MacAddress dstMac;

        Ipv6ForwardingRule(int upstreamIfindex, int downstreamIfIndex, Inet6Address address,
                MacAddress srcMac, MacAddress dstMac) {
            this.upstreamIfindex = upstreamIfindex;
            this.downstreamIfindex = downstreamIfIndex;
            this.address = address;
            this.srcMac = srcMac;
            this.dstMac = dstMac;
        }

        public Ipv6ForwardingRule onNewUpstream(int newUpstreamIfindex) {
            return new Ipv6ForwardingRule(newUpstreamIfindex, downstreamIfindex, address, srcMac,
                    dstMac);
        }

        // Don't manipulate TetherOffloadRuleParcel directly because implementing onNewUpstream()
        // would be error-prone due to generated stable AIDL classes not having a copy constructor.
        public TetherOffloadRuleParcel toTetherOffloadRuleParcel() {
            final TetherOffloadRuleParcel parcel = new TetherOffloadRuleParcel();
            parcel.inputInterfaceIndex = upstreamIfindex;
            parcel.outputInterfaceIndex = downstreamIfindex;
            parcel.destination = address.getAddress();
            parcel.prefixLength = 128;
            parcel.srcL2Address = srcMac.toByteArray();
            parcel.dstL2Address = dstMac.toByteArray();
            return parcel;
        }
    }
    private final LinkedHashMap<Inet6Address, Ipv6ForwardingRule> mIpv6ForwardingRules =
            new LinkedHashMap<>();

    private final IpNeighborMonitor mIpNeighborMonitor;

    // TODO: Add a dependency object to pass the data members or variables from the tethering
    // object. It helps to reduce the arguments of the constructor.
    public IpServer(
            String ifaceName, Looper looper, int interfaceType, SharedLog log,
            INetd netd, Callback callback, boolean usingLegacyDhcp, boolean usingBpfOffload,
            Dependencies deps) {
        super(ifaceName, looper);
        mLog = log.forSubComponent(ifaceName);
        mNetd = netd;
        mCallback = callback;
        mInterfaceCtrl = new InterfaceController(ifaceName, mNetd, mLog);
        mIfaceName = ifaceName;
        mInterfaceType = interfaceType;
        mLinkProperties = new LinkProperties();
        mUsingLegacyDhcp = usingLegacyDhcp;
        mUsingBpfOffload = usingBpfOffload;
        mDeps = deps;
        resetLinkProperties();
        mLastError = TetheringManager.TETHER_ERROR_NO_ERROR;
        mServingMode = STATE_AVAILABLE;

        mIpNeighborMonitor = mDeps.getIpNeighborMonitor(getHandler(), mLog,
                new MyNeighborEventConsumer());

        // IP neighbor monitor monitors the neighbor events for adding/removing offload
        // forwarding rules per client. If BPF offload is not supported, don't start listening
        // for neighbor events. See updateIpv6ForwardingRules, addIpv6ForwardingRule,
        // removeIpv6ForwardingRule.
        if (mUsingBpfOffload && !mIpNeighborMonitor.start()) {
            mLog.e("Failed to create IpNeighborMonitor on " + mIfaceName);
        }

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

    /** Interface name which IpServer served.*/
    public String interfaceName() {
        return mIfaceName;
    }

    /**
     * Tethering downstream type. It would be one of TetheringManager#TETHERING_*.
     */
    public int interfaceType() {
        return mInterfaceType;
    }

    /** Last error from this IpServer. */
    public int lastError() {
        return mLastError;
    }

    /** Serving mode is the current state of IpServer state machine. */
    public int servingMode() {
        return mServingMode;
    }

    /** The properties of the network link which IpServer is serving. */
    public LinkProperties linkProperties() {
        return new LinkProperties(mLinkProperties);
    }

    /**
     * Get the latest list of DHCP leases that was reported. Must be called on the IpServer looper
     * thread.
     */
    public List<TetheredClient> getAllLeases() {
        return Collections.unmodifiableList(mDhcpLeases);
    }

    /** Stop this IpServer. After this is called this IpServer should not be used any more. */
    public void stop() {
        sendMessage(CMD_INTERFACE_DOWN);
    }

    /**
     * Tethering is canceled. IpServer state machine will be available and wait for
     * next tethering request.
     */
    public void unwanted() {
        sendMessage(CMD_TETHER_UNREQUESTED);
    }

    /** Internals. */

    private boolean startIPv4() {
        return configureIPv4(true);
    }

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

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }
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
                    mDhcpServer.startWithCallbacks(new OnHandlerStatusCallback() {
                        @Override
                        public void callback(int startStatusCode) {
                            if (startStatusCode != STATUS_SUCCESS) {
                                mLog.e("Error starting DHCP server: " + startStatusCode);
                                handleError();
                            }
                        }
                    }, new DhcpEventCallback());
                } catch (RemoteException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        private void handleError() {
            mLastError = TetheringManager.TETHER_ERROR_DHCPSERVER_ERROR;
            transitionTo(mInitialState);
        }
    }

    private class DhcpEventCallback extends IDhcpEventCallbacks.Stub {
        @Override
        public void onLeasesChanged(List<DhcpLeaseParcelable> leaseParcelables) {
            final ArrayList<TetheredClient> leases = new ArrayList<>();
            for (DhcpLeaseParcelable lease : leaseParcelables) {
                final LinkAddress address = new LinkAddress(
                        intToInet4AddressHTH(lease.netAddr), lease.prefixLength,
                        0 /* flags */, RT_SCOPE_UNIVERSE /* as per RFC6724#3.2 */,
                        lease.expTime /* deprecationTime */, lease.expTime /* expirationTime */);

                final MacAddress macAddress;
                try {
                    macAddress = MacAddress.fromBytes(lease.hwAddr);
                } catch (IllegalArgumentException e) {
                    Log.wtf(TAG, "Invalid address received from DhcpServer: "
                            + Arrays.toString(lease.hwAddr));
                    return;
                }

                final TetheredClient.AddressInfo addressInfo = new TetheredClient.AddressInfo(
                        address, lease.hostname);
                leases.add(new TetheredClient(
                        macAddress,
                        Collections.singletonList(addressInfo),
                        mInterfaceType));
            }

            getHandler().post(() -> {
                mDhcpLeases = leases;
                mCallback.dhcpLeasesChanged();
            });
        }

        @Override
        public void onNewPrefixRequest(@NonNull final IpPrefix currentPrefix) {
            Objects.requireNonNull(currentPrefix);
            sendMessage(CMD_NEW_PREFIX_REQUEST, currentPrefix);
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() throws RemoteException {
            return this.HASH;
        }
    }

    private RouteInfo getDirectConnectedRoute(@NonNull final LinkAddress ipv4Address) {
        Objects.requireNonNull(ipv4Address);
        return new RouteInfo(PrefixUtils.asIpPrefix(ipv4Address), null, mIfaceName, RTN_UNICAST);
    }

    private DhcpServingParamsParcel makeServingParams(@NonNull final Inet4Address defaultRouter,
            @NonNull final Inet4Address dnsServer, @NonNull LinkAddress serverAddr,
            @Nullable Inet4Address clientAddr) {
        final boolean changePrefixOnDecline =
                (mInterfaceType == TetheringManager.TETHERING_NCM && clientAddr == null);
        return new DhcpServingParamsParcelExt()
            .setDefaultRouters(defaultRouter)
            .setDhcpLeaseTimeSecs(DHCP_LEASE_TIME_SECS)
            .setDnsServers(dnsServer)
            .setServerAddr(serverAddr)
            .setMetered(true)
            .setSingleClientAddr(clientAddr)
            .setChangePrefixOnDecline(changePrefixOnDecline);
            // TODO: also advertise link MTU
    }

    private boolean startDhcp(final LinkAddress serverLinkAddr, final LinkAddress clientLinkAddr) {
        if (mUsingLegacyDhcp) {
            return true;
        }

        final Inet4Address addr = (Inet4Address) serverLinkAddr.getAddress();
        final Inet4Address clientAddr = clientLinkAddr == null ? null :
                (Inet4Address) clientLinkAddr.getAddress();

        final DhcpServingParamsParcel params = makeServingParams(addr /* defaultRouter */,
                addr /* dnsServer */, serverLinkAddr, clientAddr);
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
                            mLastError = TetheringManager.TETHER_ERROR_DHCPSERVER_ERROR;
                            // Not much more we can do here
                        }
                        mDhcpLeases.clear();
                        getHandler().post(mCallback::dhcpLeasesChanged);
                    }
                });
                mDhcpServer = null;
            } catch (RemoteException e) {
                mLog.e("Error stopping DHCP server", e);
                // Not much more we can do here
            }
        }
    }

    private boolean configureDhcp(boolean enable, final LinkAddress serverAddr,
            final LinkAddress clientAddr) {
        if (enable) {
            return startDhcp(serverAddr, clientAddr);
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
        mIpv4Address = null;
        mStaticIpv4ServerAddr = null;
        mStaticIpv4ClientAddr = null;
    }

    private boolean configureIPv4(boolean enabled) {
        if (VDBG) Log.d(TAG, "configureIPv4(" + enabled + ")");

        // TODO: Replace this hard-coded information with dynamically selected
        // config passed down to us by a higher layer IP-coordinating element.
        final Inet4Address srvAddr;
        int prefixLen = 0;
        try {
            if (mStaticIpv4ServerAddr != null) {
                srvAddr = (Inet4Address) mStaticIpv4ServerAddr.getAddress();
                prefixLen = mStaticIpv4ServerAddr.getPrefixLength();
            } else if (mInterfaceType == TetheringManager.TETHERING_USB
                    || mInterfaceType == TetheringManager.TETHERING_NCM) {
                srvAddr = (Inet4Address) parseNumericAddress(USB_NEAR_IFACE_ADDR);
                prefixLen = USB_PREFIX_LENGTH;
            } else if (mInterfaceType == TetheringManager.TETHERING_WIFI) {
                srvAddr = (Inet4Address) parseNumericAddress(getRandomWifiIPv4Address());
                prefixLen = WIFI_HOST_IFACE_PREFIX_LENGTH;
            } else if (mInterfaceType == TetheringManager.TETHERING_WIFI_P2P) {
                srvAddr = (Inet4Address) parseNumericAddress(WIFI_P2P_IFACE_ADDR);
                prefixLen = WIFI_P2P_IFACE_PREFIX_LENGTH;
            } else if (mInterfaceType == TetheringManager.TETHERING_ETHERNET) {
                // TODO: randomize address for tethering too, similarly to wifi
                srvAddr = (Inet4Address) parseNumericAddress(ETHERNET_IFACE_ADDR);
                prefixLen = ETHERNET_IFACE_PREFIX_LENGTH;
            } else {
                // BT configures the interface elsewhere: only start DHCP.
                // TODO: make all tethering types behave the same way, and delete the bluetooth
                // code that calls into NetworkManagementService directly.
                srvAddr = (Inet4Address) parseNumericAddress(BLUETOOTH_IFACE_ADDR);
                mIpv4Address = new LinkAddress(srvAddr, BLUETOOTH_DHCP_PREFIX_LENGTH);
                return configureDhcp(enabled, mIpv4Address, null /* clientAddress */);
            }
            mIpv4Address = new LinkAddress(srvAddr, prefixLen);
        } catch (IllegalArgumentException e) {
            mLog.e("Error selecting ipv4 address", e);
            if (!enabled) stopDhcp();
            return false;
        }

        final Boolean setIfaceUp;
        if (mInterfaceType == TetheringManager.TETHERING_WIFI
                || mInterfaceType == TetheringManager.TETHERING_WIFI_P2P) {
            // The WiFi stack has ownership of the interface up/down state.
            // It is unclear whether the Bluetooth or USB stacks will manage their own
            // state.
            setIfaceUp = null;
        } else {
            setIfaceUp = enabled;
        }
        if (!mInterfaceCtrl.setInterfaceConfiguration(mIpv4Address, setIfaceUp)) {
            mLog.e("Error configuring interface");
            if (!enabled) stopDhcp();
            return false;
        }

        if (enabled) {
            mLinkProperties.addLinkAddress(mIpv4Address);
            mLinkProperties.addRoute(getDirectConnectedRoute(mIpv4Address));
        } else {
            mLinkProperties.removeLinkAddress(mIpv4Address);
            mLinkProperties.removeRoute(getDirectConnectedRoute(mIpv4Address));
        }
        return configureDhcp(enabled, mIpv4Address, mStaticIpv4ClientAddr);
    }

    private Inet4Address getRandomIPv4Address(@NonNull final byte[] rawAddr) {
        final byte[] ipv4Addr = rawAddr;
        ipv4Addr[3] = getRandomSanitizedByte(DOUG_ADAMS, asByte(0), asByte(1), FF);
        try {
            return (Inet4Address) InetAddress.getByAddress(ipv4Addr);
        } catch (UnknownHostException e) {
            mLog.e("Failed to construct Inet4Address from raw IPv4 addr");
            return null;
        }
    }

    private String getRandomWifiIPv4Address() {
        final Inet4Address ipv4Addr =
                getRandomIPv4Address(parseNumericAddress(WIFI_HOST_IFACE_ADDR).getAddress());
        return ipv4Addr != null ? ipv4Addr.getHostAddress() : WIFI_HOST_IFACE_ADDR;
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
    private void updateUpstreamIPv6LinkProperties(LinkProperties v6only, int ttlAdjustment) {
        if (mRaDaemon == null) return;

        // Avoid unnecessary work on spurious updates.
        if (Objects.equals(mLastIPv6LinkProperties, v6only)) {
            return;
        }

        RaParams params = null;
        int upstreamIfindex = 0;

        if (v6only != null) {
            final String upstreamIface = v6only.getInterfaceName();

            params = new RaParams();
            // When BPF offload is enabled, we advertise an mtu lower by 16, which is the closest
            // multiple of 8 >= 14, the ethernet header size. This makes kernel ebpf tethering
            // offload happy. This hack should be reverted once we have the kernel fixed up.
            // Note: this will automatically clamp to at least 1280 (ipv6 minimum mtu)
            // see RouterAdvertisementDaemon.java putMtu()
            params.mtu = mUsingBpfOffload ? v6only.getMtu() - 16 : v6only.getMtu();
            params.hasDefaultRoute = v6only.hasIpv6DefaultRoute();

            if (params.hasDefaultRoute) params.hopLimit = getHopLimit(upstreamIface, ttlAdjustment);

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

            upstreamIfindex = mDeps.getIfindex(upstreamIface);
        }

        // If v6only is null, we pass in null to setRaParams(), which handles
        // deprecation of any existing RA data.

        setRaParams(params);
        mLastIPv6LinkProperties = v6only;

        updateIpv6ForwardingRules(mLastIPv6UpstreamIfindex, upstreamIfindex, null);
        mLastIPv6UpstreamIfindex = upstreamIfindex;
    }

    private void removeRoutesFromLocalNetwork(@NonNull final List<RouteInfo> toBeRemoved) {
        final int removalFailures = RouteUtils.removeRoutesFromLocalNetwork(
                mNetd, toBeRemoved);
        if (removalFailures > 0) {
            mLog.e(String.format("Failed to remove %d IPv6 routes from local table.",
                    removalFailures));
        }

        for (RouteInfo route : toBeRemoved) mLinkProperties.removeRoute(route);
    }

    private void addRoutesToLocalNetwork(@NonNull final List<RouteInfo> toBeAdded) {
        try {
            // It's safe to call networkAddInterface() even if
            // the interface is already in the local_network.
            mNetd.networkAddInterface(INetd.LOCAL_NET_ID, mIfaceName);
            try {
                // Add routes from local network. Note that adding routes that
                // already exist does not cause an error (EEXIST is silently ignored).
                RouteUtils.addRoutesToLocalNetwork(mNetd, mIfaceName, toBeAdded);
            } catch (IllegalStateException e) {
                mLog.e("Failed to add IPv4/v6 routes to local table: " + e);
                return;
            }
        } catch (ServiceSpecificException | RemoteException e) {
            mLog.e("Failed to add " + mIfaceName + " to local table: ", e);
            return;
        }

        for (RouteInfo route : toBeAdded) mLinkProperties.addRoute(route);
    }

    private void configureLocalIPv6Routes(
            HashSet<IpPrefix> deprecatedPrefixes, HashSet<IpPrefix> newPrefixes) {
        // [1] Remove the routes that are deprecated.
        if (!deprecatedPrefixes.isEmpty()) {
            removeRoutesFromLocalNetwork(getLocalRoutesFor(mIfaceName, deprecatedPrefixes));
        }

        // [2] Add only the routes that have not previously been added.
        if (newPrefixes != null && !newPrefixes.isEmpty()) {
            HashSet<IpPrefix> addedPrefixes = (HashSet) newPrefixes.clone();
            if (mLastRaParams != null) {
                addedPrefixes.removeAll(mLastRaParams.prefixes);
            }

            if (!addedPrefixes.isEmpty()) {
                addRoutesToLocalNetwork(getLocalRoutesFor(mIfaceName, addedPrefixes));
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

    private void addIpv6ForwardingRule(Ipv6ForwardingRule rule) {
        // Theoretically, we don't need this check because IP neighbor monitor doesn't start if BPF
        // offload is disabled. Add this check just in case.
        // TODO: Perhaps remove this protection check.
        if (!mUsingBpfOffload) return;

        try {
            mNetd.tetherOffloadRuleAdd(rule.toTetherOffloadRuleParcel());
            mIpv6ForwardingRules.put(rule.address, rule);
        } catch (RemoteException | ServiceSpecificException e) {
            mLog.e("Could not add IPv6 downstream rule: ", e);
        }
    }

    private void removeIpv6ForwardingRule(Ipv6ForwardingRule rule, boolean removeFromMap) {
        // Theoretically, we don't need this check because IP neighbor monitor doesn't start if BPF
        // offload is disabled. Add this check just in case.
        // TODO: Perhaps remove this protection check.
        if (!mUsingBpfOffload) return;

        try {
            mNetd.tetherOffloadRuleRemove(rule.toTetherOffloadRuleParcel());
            if (removeFromMap) {
                mIpv6ForwardingRules.remove(rule.address);
            }
        } catch (RemoteException | ServiceSpecificException e) {
            mLog.e("Could not remove IPv6 downstream rule: ", e);
        }
    }

    private void clearIpv6ForwardingRules() {
        for (Ipv6ForwardingRule rule : mIpv6ForwardingRules.values()) {
            removeIpv6ForwardingRule(rule, false /*removeFromMap*/);
        }
        mIpv6ForwardingRules.clear();
    }

    // Convenience method to replace a rule with the same rule on a new upstream interface.
    // Allows replacing the rules in one iteration pass without ConcurrentModificationExceptions.
    // Relies on the fact that rules are in a map indexed by IP address.
    private void updateIpv6ForwardingRule(Ipv6ForwardingRule rule, int newIfindex) {
        addIpv6ForwardingRule(rule.onNewUpstream(newIfindex));
        removeIpv6ForwardingRule(rule, false /*removeFromMap*/);
    }

    // Handles all updates to IPv6 forwarding rules. These can currently change only if the upstream
    // changes or if a neighbor event is received.
    private void updateIpv6ForwardingRules(int prevUpstreamIfindex, int upstreamIfindex,
            NeighborEvent e) {
        // If we no longer have an upstream, clear forwarding rules and do nothing else.
        if (upstreamIfindex == 0) {
            clearIpv6ForwardingRules();
            return;
        }

        // If the upstream interface has changed, remove all rules and re-add them with the new
        // upstream interface.
        if (prevUpstreamIfindex != upstreamIfindex) {
            for (Ipv6ForwardingRule rule : mIpv6ForwardingRules.values()) {
                updateIpv6ForwardingRule(rule, upstreamIfindex);
            }
        }

        // If we're here to process a NeighborEvent, do so now.
        // mInterfaceParams must be non-null or the event would not have arrived.
        if (e == null) return;
        if (!(e.ip instanceof Inet6Address) || e.ip.isMulticastAddress()
                || e.ip.isLoopbackAddress() || e.ip.isLinkLocalAddress()) {
            return;
        }

        // When deleting rules, we still need to pass a non-null MAC, even though it's ignored.
        // Do this here instead of in the Ipv6ForwardingRule constructor to ensure that we never
        // add rules with a null MAC, only delete them.
        MacAddress dstMac = e.isValid() ? e.macAddr : NULL_MAC_ADDRESS;
        Ipv6ForwardingRule rule = new Ipv6ForwardingRule(upstreamIfindex,
                mInterfaceParams.index, (Inet6Address) e.ip, mInterfaceParams.macAddr, dstMac);
        if (e.isValid()) {
            addIpv6ForwardingRule(rule);
        } else {
            removeIpv6ForwardingRule(rule, true /*removeFromMap*/);
        }
    }

    private void handleNeighborEvent(NeighborEvent e) {
        if (mInterfaceParams != null
                && mInterfaceParams.index == e.ifindex
                && mInterfaceParams.hasMacAddress) {
            updateIpv6ForwardingRules(mLastIPv6UpstreamIfindex, mLastIPv6UpstreamIfindex, e);
        }
    }

    // TODO: call PrivateAddressCoordinator.requestDownstreamAddress instead of this temporary
    // logic.
    private Inet4Address requestDownstreamAddress(@NonNull final IpPrefix currentPrefix) {
        final int oldIndex = NCM_PREFIXES.indexOf(currentPrefix);
        if (oldIndex == -1) {
            mLog.e("current prefix isn't supported for NCM link: " + currentPrefix);
            return null;
        }

        final IpPrefix newPrefix = NCM_PREFIXES.get((oldIndex + 1) % NCM_PREFIXES.size());
        return getRandomIPv4Address(newPrefix.getRawAddress());
    }

    private void handleNewPrefixRequest(@NonNull final IpPrefix currentPrefix) {
        if (!currentPrefix.contains(mIpv4Address.getAddress())
                || currentPrefix.getPrefixLength() != mIpv4Address.getPrefixLength()) {
            Log.e(TAG, "Invalid prefix: " + currentPrefix);
            return;
        }

        final LinkAddress deprecatedLinkAddress = mIpv4Address;
        final Inet4Address srvAddr = requestDownstreamAddress(currentPrefix);
        if (srvAddr == null) {
            mLog.e("Fail to request a new downstream prefix");
            return;
        }
        mIpv4Address = new LinkAddress(srvAddr, currentPrefix.getPrefixLength());

        // Add new IPv4 address on the interface.
        if (!mInterfaceCtrl.addAddress(srvAddr, currentPrefix.getPrefixLength())) {
            mLog.e("Failed to add new IP " + srvAddr);
            return;
        }

        // Remove deprecated routes from local network.
        removeRoutesFromLocalNetwork(
                Collections.singletonList(getDirectConnectedRoute(deprecatedLinkAddress)));
        mLinkProperties.removeLinkAddress(deprecatedLinkAddress);

        // Add new routes to local network.
        addRoutesToLocalNetwork(
                Collections.singletonList(getDirectConnectedRoute(mIpv4Address)));
        mLinkProperties.addLinkAddress(mIpv4Address);

        // Update local DNS caching server with new IPv4 address, otherwise, dnsmasq doesn't
        // listen on the interface configured with new IPv4 address, that results DNS validation
        // failure of downstream client even if appropriate routes have been configured.
        try {
            mNetd.tetherApplyDnsInterfaces();
        } catch (ServiceSpecificException | RemoteException e) {
            mLog.e("Failed to update local DNS caching server");
            return;
        }
        sendLinkProperties();

        // Notify DHCP server that new prefix/route has been applied on IpServer.
        final Inet4Address clientAddr = mStaticIpv4ClientAddr == null ? null :
                (Inet4Address) mStaticIpv4ClientAddr.getAddress();
        final DhcpServingParamsParcel params = makeServingParams(srvAddr /* defaultRouter */,
                srvAddr /* dnsServer */, mIpv4Address /* serverLinkAddress */, clientAddr);
        try {
            mDhcpServer.updateParams(params, new OnHandlerStatusCallback() {
                    @Override
                    public void callback(int statusCode) {
                        if (statusCode != STATUS_SUCCESS) {
                            mLog.e("Error updating DHCP serving params: " + statusCode);
                        }
                    }
            });
        } catch (RemoteException e) {
            mLog.e("Error updating DHCP serving params", e);
        }
    }

    private byte getHopLimit(String upstreamIface, int adjustTTL) {
        try {
            int upstreamHopLimit = Integer.parseUnsignedInt(
                    mNetd.getProcSysNet(INetd.IPV6, INetd.CONF, upstreamIface, "hop_limit"));
            upstreamHopLimit = upstreamHopLimit + adjustTTL;
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

    private void maybeConfigureStaticIp(final TetheringRequestParcel request) {
        // Ignore static address configuration if they are invalid or null. In theory, static
        // addresses should not be invalid here because TetheringManager do not allow caller to
        // specify invalid static address configuration.
        if (request == null || request.localIPv4Address == null
                || request.staticClientAddress == null || !checkStaticAddressConfiguration(
                request.localIPv4Address, request.staticClientAddress)) {
            return;
        }

        mStaticIpv4ServerAddr = request.localIPv4Address;
        mStaticIpv4ClientAddr = request.staticClientAddress;
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
                    mLastError = TetheringManager.TETHER_ERROR_NO_ERROR;
                    switch (message.arg1) {
                        case STATE_LOCAL_ONLY:
                            maybeConfigureStaticIp((TetheringRequestParcel) message.obj);
                            transitionTo(mLocalHotspotState);
                            break;
                        case STATE_TETHERED:
                            maybeConfigureStaticIp((TetheringRequestParcel) message.obj);
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
                    updateUpstreamIPv6LinkProperties((LinkProperties) message.obj, message.arg1);
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
                mLastError = TetheringManager.TETHER_ERROR_IFACE_CFG_ERROR;
                return;
            }

            try {
                NetdUtils.tetherInterface(mNetd, mIfaceName, PrefixUtils.asIpPrefix(mIpv4Address));
            } catch (RemoteException | ServiceSpecificException | IllegalStateException e) {
                mLog.e("Error Tethering", e);
                mLastError = TetheringManager.TETHER_ERROR_TETHER_IFACE_ERROR;
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
                NetdUtils.untetherInterface(mNetd, mIfaceName);
            } catch (RemoteException | ServiceSpecificException e) {
                mLastError = TetheringManager.TETHER_ERROR_UNTETHER_IFACE_ERROR;
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
                    updateUpstreamIPv6LinkProperties((LinkProperties) message.obj, message.arg1);
                    sendLinkProperties();
                    break;
                case CMD_IP_FORWARDING_ENABLE_ERROR:
                case CMD_IP_FORWARDING_DISABLE_ERROR:
                case CMD_START_TETHERING_ERROR:
                case CMD_STOP_TETHERING_ERROR:
                case CMD_SET_DNS_FORWARDERS_ERROR:
                    mLastError = TetheringManager.TETHER_ERROR_INTERNAL_ERROR;
                    transitionTo(mInitialState);
                    break;
                case CMD_NEW_PREFIX_REQUEST:
                    handleNewPrefixRequest((IpPrefix) message.obj);
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
            if (mLastError != TetheringManager.TETHER_ERROR_NO_ERROR) {
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
            if (mLastError != TetheringManager.TETHER_ERROR_NO_ERROR) {
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
            clearIpv6ForwardingRules();
        }

        private void cleanupUpstreamInterface(String upstreamIface) {
            // Note that we don't care about errors here.
            // Sometimes interfaces are gone before we get
            // to remove their rules, which generates errors.
            // Just do the best we can.
            try {
                mNetd.ipfwdRemoveInterfaceForward(mIfaceName, upstreamIface);
            } catch (RemoteException | ServiceSpecificException e) {
                mLog.e("Exception in ipfwdRemoveInterfaceForward: " + e.toString());
            }
            try {
                mNetd.tetherRemoveForward(mIfaceName, upstreamIface);
            } catch (RemoteException | ServiceSpecificException e) {
                mLog.e("Exception in disableNat: " + e.toString());
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
                            mNetd.tetherAddForward(mIfaceName, ifname);
                            mNetd.ipfwdAddInterfaceForward(mIfaceName, ifname);
                        } catch (RemoteException | ServiceSpecificException e) {
                            mLog.e("Exception enabling NAT: " + e.toString());
                            cleanupUpstream();
                            mLastError = TetheringManager.TETHER_ERROR_ENABLE_FORWARDING_ERROR;
                            transitionTo(mInitialState);
                            return true;
                        }
                    }
                    break;
                case CMD_NEIGHBOR_EVENT:
                    handleNeighborEvent((NeighborEvent) message.obj);
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
            mLastError = TetheringManager.TETHER_ERROR_NO_ERROR;
            sendInterfaceState(STATE_UNAVAILABLE);
        }
    }

    // Accumulate routes representing "prefixes to be assigned to the local
    // interface", for subsequent modification of local_network routing.
    private static ArrayList<RouteInfo> getLocalRoutesFor(
            String ifname, HashSet<IpPrefix> prefixes) {
        final ArrayList<RouteInfo> localRoutes = new ArrayList<RouteInfo>();
        for (IpPrefix ipp : prefixes) {
            localRoutes.add(new RouteInfo(ipp, null, ifname, RTN_UNICAST));
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
            Log.wtf(TAG, "Failed to construct Inet6Address from: " + localPrefix);
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
