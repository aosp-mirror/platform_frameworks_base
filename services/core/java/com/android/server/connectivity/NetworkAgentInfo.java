/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.net.ConnectivityDiagnosticsManager.ConnectivityReport;
import static android.net.NetworkCapabilities.transportNamesOf;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.CaptivePortalData;
import android.net.IDnsResolver;
import android.net.INetd;
import android.net.INetworkMonitor;
import android.net.LinkProperties;
import android.net.NattKeepalivePacketData;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkMonitorManager;
import android.net.NetworkRequest;
import android.net.NetworkStateSnapshot;
import android.net.QosCallbackException;
import android.net.QosFilter;
import android.net.QosFilterParcelable;
import android.net.QosSession;
import android.net.TcpKeepalivePacketData;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.telephony.data.EpsBearerQosSessionAttributes;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.connectivity.aidl.INetworkAgent;
import com.android.connectivity.aidl.INetworkAgentRegistry;
import com.android.internal.util.WakeupMessage;
import com.android.server.ConnectivityService;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A bag class used by ConnectivityService for holding a collection of most recent
 * information published by a particular NetworkAgent as well as the
 * AsyncChannel/messenger for reaching that NetworkAgent and lists of NetworkRequests
 * interested in using it.  Default sort order is descending by score.
 */
// States of a network:
// --------------------
// 1. registered, uncreated, disconnected, unvalidated
//    This state is entered when a NetworkFactory registers a NetworkAgent in any state except
//    the CONNECTED state.
// 2. registered, uncreated, connecting, unvalidated
//    This state is entered when a registered NetworkAgent for a VPN network transitions to the
//    CONNECTING state (TODO: go through this state for every network, not just VPNs).
//    ConnectivityService will tell netd to create the network early in order to add extra UID
//    routing rules referencing the netID. These rules need to be in place before the network is
//    connected to avoid racing against client apps trying to connect to a half-setup network.
// 3. registered, uncreated, connected, unvalidated
//    This state is entered when a registered NetworkAgent transitions to the CONNECTED state.
//    ConnectivityService will tell netd to create the network if it was not already created, and
//    immediately transition to state #4.
// 4. registered, created, connected, unvalidated
//    If this network can satisfy the default NetworkRequest, then NetworkMonitor will
//    probe for Internet connectivity.
//    If this network cannot satisfy the default NetworkRequest, it will immediately be
//    transitioned to state #5.
//    A network may remain in this state if NetworkMonitor fails to find Internet connectivity,
//    for example:
//    a. a captive portal is present, or
//    b. a WiFi router whose Internet backhaul is down, or
//    c. a wireless connection stops transfering packets temporarily (e.g. device is in elevator
//       or tunnel) but does not disconnect from the AP/cell tower, or
//    d. a stand-alone device offering a WiFi AP without an uplink for configuration purposes.
// 5. registered, created, connected, validated
//
// The device's default network connection:
// ----------------------------------------
// Networks in states #4 and #5 may be used as a device's default network connection if they
// satisfy the default NetworkRequest.
// A network, that satisfies the default NetworkRequest, in state #5 should always be chosen
// in favor of a network, that satisfies the default NetworkRequest, in state #4.
// When deciding between two networks, that both satisfy the default NetworkRequest, to select
// for the default network connection, the one with the higher score should be chosen.
//
// When a network disconnects:
// ---------------------------
// If a network's transport disappears, for example:
// a. WiFi turned off, or
// b. cellular data turned off, or
// c. airplane mode is turned on, or
// d. a wireless connection disconnects from AP/cell tower entirely (e.g. device is out of range
//    of AP for an extended period of time, or switches to another AP without roaming)
// then that network can transition from any state (#1-#5) to unregistered.  This happens by
// the transport disconnecting their NetworkAgent's AsyncChannel with ConnectivityManager.
// ConnectivityService also tells netd to destroy the network.
//
// When ConnectivityService disconnects a network:
// -----------------------------------------------
// If a network is just connected, ConnectivityService will think it will be used soon, but might
// not be used. Thus, a 5s timer will be held to prevent the network being torn down immediately.
// This "nascent" state is implemented by the "lingering" logic below without relating to any
// request, and is used in some cases where network requests race with network establishment. The
// nascent state ends when the 5-second timer fires, or as soon as the network satisfies a
// request, whichever is earlier. In this state, the network is considered in the background.
//
// If a network has no chance of satisfying any requests (even if it were to become validated
// and enter state #5), ConnectivityService will disconnect the NetworkAgent's AsyncChannel.
//
// If the network was satisfying a foreground NetworkRequest (i.e. had been the highest scoring that
// satisfied the NetworkRequest's constraints), but is no longer the highest scoring network for any
// foreground NetworkRequest, then there will be a 30s pause to allow network communication to be
// wrapped up rather than abruptly terminated. During this pause the network is said to be
// "lingering". During this pause if the network begins satisfying a foreground NetworkRequest,
// ConnectivityService will cancel the future disconnection of the NetworkAgent's AsyncChannel, and
// the network is no longer considered "lingering". After the linger timer expires, if the network
// is satisfying one or more background NetworkRequests it is kept up in the background. If it is
// not, ConnectivityService disconnects the NetworkAgent's AsyncChannel.
public class NetworkAgentInfo implements Comparable<NetworkAgentInfo> {

    @NonNull public NetworkInfo networkInfo;
    // This Network object should always be used if possible, so as to encourage reuse of the
    // enclosed socket factory and connection pool.  Avoid creating other Network objects.
    // This Network object is always valid.
    @NonNull public final Network network;
    @NonNull public LinkProperties linkProperties;
    // This should only be modified by ConnectivityService, via setNetworkCapabilities().
    // TODO: make this private with a getter.
    @NonNull public NetworkCapabilities networkCapabilities;
    @NonNull public final NetworkAgentConfig networkAgentConfig;

    // Underlying networks declared by the agent. Only set if supportsUnderlyingNetworks is true.
    // The networks in this list might be declared by a VPN app using setUnderlyingNetworks and are
    // not guaranteed to be current or correct, or even to exist.
    //
    // This array is read and iterated on multiple threads with no locking so its contents must
    // never be modified. When the list of networks changes, replace with a new array, on the
    // handler thread.
    public @Nullable volatile Network[] declaredUnderlyingNetworks;

    // The capabilities originally announced by the NetworkAgent, regardless of any capabilities
    // that were added or removed due to this network's underlying networks.
    // Only set if #supportsUnderlyingNetworks is true.
    public @Nullable NetworkCapabilities declaredCapabilities;

    // Indicates if netd has been told to create this Network. From this point on the appropriate
    // routing rules are setup and routes are added so packets can begin flowing over the Network.
    // This is a sticky bit; once set it is never cleared.
    public boolean created;
    // Set to true after the first time this network is marked as CONNECTED. Once set, the network
    // shows up in API calls, is able to satisfy NetworkRequests and can become the default network.
    // This is a sticky bit; once set it is never cleared.
    public boolean everConnected;
    // Set to true if this Network successfully passed validation or if it did not satisfy the
    // default NetworkRequest in which case validation will not be attempted.
    // This is a sticky bit; once set it is never cleared even if future validation attempts fail.
    public boolean everValidated;

    // The result of the last validation attempt on this network (true if validated, false if not).
    public boolean lastValidated;

    // If true, becoming unvalidated will lower the network's score. This is only meaningful if the
    // system is configured not to do this for certain networks, e.g., if the
    // config_networkAvoidBadWifi option is set to 0 and the user has not overridden that via
    // Settings.Global.NETWORK_AVOID_BAD_WIFI.
    public boolean avoidUnvalidated;

    // Whether a captive portal was ever detected on this network.
    // This is a sticky bit; once set it is never cleared.
    public boolean everCaptivePortalDetected;

    // Whether a captive portal was found during the last network validation attempt.
    public boolean lastCaptivePortalDetected;

    // Set to true when partial connectivity was detected.
    public boolean partialConnectivity;

    // Captive portal info of the network from RFC8908, if any.
    // Obtained by ConnectivityService and merged into NetworkAgent-provided information.
    public CaptivePortalData capportApiData;

    // The UID of the remote entity that created this Network.
    public final int creatorUid;

    // Network agent portal info of the network, if any. This information is provided from
    // non-RFC8908 sources, such as Wi-Fi Passpoint, which can provide information such as Venue
    // URL, Terms & Conditions URL, and network friendly name.
    public CaptivePortalData networkAgentPortalData;

    // Networks are lingered when they become unneeded as a result of their NetworkRequests being
    // satisfied by a higher-scoring network. so as to allow communication to wrap up before the
    // network is taken down.  This usually only happens to the default network. Lingering ends with
    // either the linger timeout expiring and the network being taken down, or the network
    // satisfying a request again.
    public static class InactivityTimer implements Comparable<InactivityTimer> {
        public final int requestId;
        public final long expiryMs;

        public InactivityTimer(int requestId, long expiryMs) {
            this.requestId = requestId;
            this.expiryMs = expiryMs;
        }
        public boolean equals(Object o) {
            if (!(o instanceof InactivityTimer)) return false;
            InactivityTimer other = (InactivityTimer) o;
            return (requestId == other.requestId) && (expiryMs == other.expiryMs);
        }
        public int hashCode() {
            return Objects.hash(requestId, expiryMs);
        }
        public int compareTo(InactivityTimer other) {
            return (expiryMs != other.expiryMs) ?
                    Long.compare(expiryMs, other.expiryMs) :
                    Integer.compare(requestId, other.requestId);
        }
        public String toString() {
            return String.format("%s, expires %dms", requestId,
                    expiryMs - SystemClock.elapsedRealtime());
        }
    }

    /**
     * Inform ConnectivityService that the network LINGER period has
     * expired.
     * obj = this NetworkAgentInfo
     */
    public static final int EVENT_NETWORK_LINGER_COMPLETE = 1001;

    /**
     * Inform ConnectivityService that the agent is half-connected.
     * arg1 = ARG_AGENT_SUCCESS or ARG_AGENT_FAILURE
     * obj = NetworkAgentInfo
     * @hide
     */
    public static final int EVENT_AGENT_REGISTERED = 1002;

    /**
     * Inform ConnectivityService that the agent was disconnected.
     * obj = NetworkAgentInfo
     * @hide
     */
    public static final int EVENT_AGENT_DISCONNECTED = 1003;

    /**
     * Argument for EVENT_AGENT_HALF_CONNECTED indicating failure.
     */
    public static final int ARG_AGENT_FAILURE = 0;

    /**
     * Argument for EVENT_AGENT_HALF_CONNECTED indicating success.
     */
    public static final int ARG_AGENT_SUCCESS = 1;

    // All inactivity timers for this network, sorted by expiry time. A timer is added whenever
    // a request is moved to a network with a better score, regardless of whether the network is or
    // was lingering or not. An inactivity timer is also added when a network connects
    // without immediately satisfying any requests.
    // TODO: determine if we can replace this with a smaller or unsorted data structure. (e.g.,
    // SparseLongArray) combined with the timestamp of when the last timer is scheduled to fire.
    private final SortedSet<InactivityTimer> mInactivityTimers = new TreeSet<>();

    // For fast lookups. Indexes into mInactivityTimers by request ID.
    private final SparseArray<InactivityTimer> mInactivityTimerForRequest = new SparseArray<>();

    // Inactivity expiry timer. Armed whenever mInactivityTimers is non-empty, regardless of
    // whether the network is inactive or not. Always set to the expiry of the mInactivityTimers
    // that expires last. When the timer fires, all inactivity state is cleared, and if the network
    // has no requests, it is torn down.
    private WakeupMessage mInactivityMessage;

    // Inactivity expiry. Holds the expiry time of the inactivity timer, or 0 if the timer is not
    // armed.
    private long mInactivityExpiryMs;

    // Whether the network is inactive or not. Must be maintained separately from the above because
    // it depends on the state of other networks and requests, which only ConnectivityService knows.
    // (Example: we don't linger a network if it would become the best for a NetworkRequest if it
    // validated).
    private boolean mInactive;

    // This represents the quality of the network with no clear scale.
    private int mScore;

    // The list of NetworkRequests being satisfied by this Network.
    private final SparseArray<NetworkRequest> mNetworkRequests = new SparseArray<>();

    // How many of the satisfied requests are actual requests and not listens.
    private int mNumRequestNetworkRequests = 0;

    // How many of the satisfied requests are of type BACKGROUND_REQUEST.
    private int mNumBackgroundNetworkRequests = 0;

    // The last ConnectivityReport made available for this network. This value is only null before a
    // report is generated. Once non-null, it will never be null again.
    @Nullable private ConnectivityReport mConnectivityReport;

    public final INetworkAgent networkAgent;
    // Only accessed from ConnectivityService handler thread
    private final AgentDeathMonitor mDeathMonitor = new AgentDeathMonitor();

    public final int factorySerialNumber;

    // Used by ConnectivityService to keep track of 464xlat.
    public final Nat464Xlat clatd;

    // Set after asynchronous creation of the NetworkMonitor.
    private volatile NetworkMonitorManager mNetworkMonitor;

    private static final String TAG = ConnectivityService.class.getSimpleName();
    private static final boolean VDBG = false;
    private final ConnectivityService mConnService;
    private final Context mContext;
    private final Handler mHandler;
    private final QosCallbackTracker mQosCallbackTracker;

    public NetworkAgentInfo(INetworkAgent na, Network net, NetworkInfo info,
            @NonNull LinkProperties lp, @NonNull NetworkCapabilities nc, int score, Context context,
            Handler handler, NetworkAgentConfig config, ConnectivityService connService, INetd netd,
            IDnsResolver dnsResolver, int factorySerialNumber, int creatorUid,
            QosCallbackTracker qosCallbackTracker) {
        Objects.requireNonNull(net);
        Objects.requireNonNull(info);
        Objects.requireNonNull(lp);
        Objects.requireNonNull(nc);
        Objects.requireNonNull(context);
        Objects.requireNonNull(config);
        Objects.requireNonNull(qosCallbackTracker);
        networkAgent = na;
        network = net;
        networkInfo = info;
        linkProperties = lp;
        networkCapabilities = nc;
        mScore = score;
        clatd = new Nat464Xlat(this, netd, dnsResolver);
        mConnService = connService;
        mContext = context;
        mHandler = handler;
        networkAgentConfig = config;
        this.factorySerialNumber = factorySerialNumber;
        this.creatorUid = creatorUid;
        mQosCallbackTracker = qosCallbackTracker;
    }

    private class AgentDeathMonitor implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            notifyDisconnected();
        }
    }

    /**
     * Notify the NetworkAgent that it was registered, and should be unregistered if it dies.
     *
     * Must be called from the ConnectivityService handler thread. A NetworkAgent can only be
     * registered once.
     */
    public void notifyRegistered() {
        try {
            networkAgent.asBinder().linkToDeath(mDeathMonitor, 0);
            networkAgent.onRegistered(new NetworkAgentMessageHandler(mHandler));
        } catch (RemoteException e) {
            Log.e(TAG, "Error registering NetworkAgent", e);
            maybeUnlinkDeathMonitor();
            mHandler.obtainMessage(EVENT_AGENT_REGISTERED, ARG_AGENT_FAILURE, 0, this)
                    .sendToTarget();
            return;
        }

        mHandler.obtainMessage(EVENT_AGENT_REGISTERED, ARG_AGENT_SUCCESS, 0, this).sendToTarget();
    }

    /**
     * Disconnect the NetworkAgent. Must be called from the ConnectivityService handler thread.
     */
    public void disconnect() {
        try {
            networkAgent.onDisconnected();
        } catch (RemoteException e) {
            Log.i(TAG, "Error disconnecting NetworkAgent", e);
            // Fall through: it's fine if the remote has died
        }

        notifyDisconnected();
        maybeUnlinkDeathMonitor();
    }

    private void maybeUnlinkDeathMonitor() {
        try {
            networkAgent.asBinder().unlinkToDeath(mDeathMonitor, 0);
        } catch (NoSuchElementException e) {
            // Was not linked: ignore
        }
    }

    private void notifyDisconnected() {
        // Note this may be called multiple times if ConnectivityService disconnects while the
        // NetworkAgent also dies. ConnectivityService ignores disconnects of already disconnected
        // agents.
        mHandler.obtainMessage(EVENT_AGENT_DISCONNECTED, this).sendToTarget();
    }

    /**
     * Notify the NetworkAgent that bandwidth update was requested.
     */
    public void onBandwidthUpdateRequested() {
        try {
            networkAgent.onBandwidthUpdateRequested();
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending bandwidth update request event", e);
        }
    }

    /**
     * Notify the NetworkAgent that validation status has changed.
     */
    public void onValidationStatusChanged(int validationStatus, @Nullable String captivePortalUrl) {
        try {
            networkAgent.onValidationStatusChanged(validationStatus, captivePortalUrl);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending validation status change event", e);
        }
    }

    /**
     * Notify the NetworkAgent that the acceptUnvalidated setting should be saved.
     */
    public void onSaveAcceptUnvalidated(boolean acceptUnvalidated) {
        try {
            networkAgent.onSaveAcceptUnvalidated(acceptUnvalidated);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending accept unvalidated event", e);
        }
    }

    /**
     * Notify the NetworkAgent that NATT socket keepalive should be started.
     */
    public void onStartNattSocketKeepalive(int slot, int intervalDurationMs,
            @NonNull NattKeepalivePacketData packetData) {
        try {
            networkAgent.onStartNattSocketKeepalive(slot, intervalDurationMs, packetData);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending NATT socket keepalive start event", e);
        }
    }

    /**
     * Notify the NetworkAgent that TCP socket keepalive should be started.
     */
    public void onStartTcpSocketKeepalive(int slot, int intervalDurationMs,
            @NonNull TcpKeepalivePacketData packetData) {
        try {
            networkAgent.onStartTcpSocketKeepalive(slot, intervalDurationMs, packetData);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending TCP socket keepalive start event", e);
        }
    }

    /**
     * Notify the NetworkAgent that socket keepalive should be stopped.
     */
    public void onStopSocketKeepalive(int slot) {
        try {
            networkAgent.onStopSocketKeepalive(slot);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending TCP socket keepalive stop event", e);
        }
    }

    /**
     * Notify the NetworkAgent that signal strength thresholds should be updated.
     */
    public void onSignalStrengthThresholdsUpdated(@NonNull int[] thresholds) {
        try {
            networkAgent.onSignalStrengthThresholdsUpdated(thresholds);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending signal strength thresholds event", e);
        }
    }

    /**
     * Notify the NetworkAgent that automatic reconnect should be prevented.
     */
    public void onPreventAutomaticReconnect() {
        try {
            networkAgent.onPreventAutomaticReconnect();
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending prevent automatic reconnect event", e);
        }
    }

    /**
     * Notify the NetworkAgent that a NATT keepalive packet filter should be added.
     */
    public void onAddNattKeepalivePacketFilter(int slot,
            @NonNull NattKeepalivePacketData packetData) {
        try {
            networkAgent.onAddNattKeepalivePacketFilter(slot, packetData);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending add NATT keepalive packet filter event", e);
        }
    }

    /**
     * Notify the NetworkAgent that a TCP keepalive packet filter should be added.
     */
    public void onAddTcpKeepalivePacketFilter(int slot,
            @NonNull TcpKeepalivePacketData packetData) {
        try {
            networkAgent.onAddTcpKeepalivePacketFilter(slot, packetData);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending add TCP keepalive packet filter event", e);
        }
    }

    /**
     * Notify the NetworkAgent that a keepalive packet filter should be removed.
     */
    public void onRemoveKeepalivePacketFilter(int slot) {
        try {
            networkAgent.onRemoveKeepalivePacketFilter(slot);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending remove keepalive packet filter event", e);
        }
    }

    /**
     * Notify the NetworkAgent that the qos filter should be registered against the given qos
     * callback id.
     */
    public void onQosFilterCallbackRegistered(final int qosCallbackId,
            final QosFilter qosFilter) {
        try {
            networkAgent.onQosFilterCallbackRegistered(qosCallbackId,
                    new QosFilterParcelable(qosFilter));
        } catch (final RemoteException e) {
            Log.e(TAG, "Error registering a qos callback id against a qos filter", e);
        }
    }

    /**
     * Notify the NetworkAgent that the given qos callback id should be unregistered.
     */
    public void onQosCallbackUnregistered(final int qosCallbackId) {
        try {
            networkAgent.onQosCallbackUnregistered(qosCallbackId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error unregistering a qos callback id", e);
        }
    }

    // TODO: consider moving out of NetworkAgentInfo into its own class
    private class NetworkAgentMessageHandler extends INetworkAgentRegistry.Stub {
        private final Handler mHandler;

        private NetworkAgentMessageHandler(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void sendNetworkCapabilities(@NonNull NetworkCapabilities nc) {
            Objects.requireNonNull(nc);
            mHandler.obtainMessage(NetworkAgent.EVENT_NETWORK_CAPABILITIES_CHANGED,
                    new Pair<>(NetworkAgentInfo.this, nc)).sendToTarget();
        }

        @Override
        public void sendLinkProperties(@NonNull LinkProperties lp) {
            Objects.requireNonNull(lp);
            mHandler.obtainMessage(NetworkAgent.EVENT_NETWORK_PROPERTIES_CHANGED,
                    new Pair<>(NetworkAgentInfo.this, lp)).sendToTarget();
        }

        @Override
        public void sendNetworkInfo(@NonNull NetworkInfo info) {
            Objects.requireNonNull(info);
            mHandler.obtainMessage(NetworkAgent.EVENT_NETWORK_INFO_CHANGED,
                    new Pair<>(NetworkAgentInfo.this, info)).sendToTarget();
        }

        @Override
        public void sendScore(int score) {
            mHandler.obtainMessage(NetworkAgent.EVENT_NETWORK_SCORE_CHANGED, score, 0,
                    new Pair<>(NetworkAgentInfo.this, null)).sendToTarget();
        }

        @Override
        public void sendExplicitlySelected(boolean explicitlySelected, boolean acceptPartial) {
            mHandler.obtainMessage(NetworkAgent.EVENT_SET_EXPLICITLY_SELECTED,
                    explicitlySelected ? 1 : 0, acceptPartial ? 1 : 0,
                    new Pair<>(NetworkAgentInfo.this, null)).sendToTarget();
        }

        @Override
        public void sendSocketKeepaliveEvent(int slot, int reason) {
            mHandler.obtainMessage(NetworkAgent.EVENT_SOCKET_KEEPALIVE,
                    slot, reason, new Pair<>(NetworkAgentInfo.this, null)).sendToTarget();
        }

        @Override
        public void sendUnderlyingNetworks(@Nullable List<Network> networks) {
            mHandler.obtainMessage(NetworkAgent.EVENT_UNDERLYING_NETWORKS_CHANGED,
                    new Pair<>(NetworkAgentInfo.this, networks)).sendToTarget();
        }

        @Override
        public void sendEpsQosSessionAvailable(final int qosCallbackId, final QosSession session,
                final EpsBearerQosSessionAttributes attributes) {
            mQosCallbackTracker.sendEventQosSessionAvailable(qosCallbackId, session, attributes);
        }

        @Override
        public void sendQosSessionLost(final int qosCallbackId, final QosSession session) {
            mQosCallbackTracker.sendEventQosSessionLost(qosCallbackId, session);
        }

        @Override
        public void sendQosCallbackError(final int qosCallbackId,
                @QosCallbackException.ExceptionType final int exceptionType) {
            mQosCallbackTracker.sendEventQosCallbackError(qosCallbackId, exceptionType);
        }
    }

    /**
     * Inform NetworkAgentInfo that a new NetworkMonitor was created.
     */
    public void onNetworkMonitorCreated(INetworkMonitor networkMonitor) {
        mNetworkMonitor = new NetworkMonitorManager(networkMonitor);
    }

    /**
     * Set the NetworkCapabilities on this NetworkAgentInfo. Also attempts to notify NetworkMonitor
     * of the new capabilities, if NetworkMonitor has been created.
     *
     * <p>If {@link NetworkMonitor#notifyNetworkCapabilitiesChanged(NetworkCapabilities)} fails,
     * the exception is logged but not reported to callers.
     *
     * @return the old capabilities of this network.
     */
    @NonNull public synchronized NetworkCapabilities getAndSetNetworkCapabilities(
            @NonNull final NetworkCapabilities nc) {
        final NetworkCapabilities oldNc = networkCapabilities;
        networkCapabilities = nc;
        final NetworkMonitorManager nm = mNetworkMonitor;
        if (nm != null) {
            nm.notifyNetworkCapabilitiesChanged(nc);
        }
        return oldNc;
    }

    public ConnectivityService connService() {
        return mConnService;
    }

    public NetworkAgentConfig netAgentConfig() {
        return networkAgentConfig;
    }

    public Handler handler() {
        return mHandler;
    }

    public Network network() {
        return network;
    }

    /**
     * Get the NetworkMonitorManager in this NetworkAgentInfo.
     *
     * <p>This will be null before {@link #onNetworkMonitorCreated(INetworkMonitor)} is called.
     */
    public NetworkMonitorManager networkMonitor() {
        return mNetworkMonitor;
    }

    // Functions for manipulating the requests satisfied by this network.
    //
    // These functions must only called on ConnectivityService's main thread.

    private static final boolean ADD = true;
    private static final boolean REMOVE = false;

    private void updateRequestCounts(boolean add, NetworkRequest request) {
        int delta = add ? +1 : -1;
        switch (request.type) {
            case REQUEST:
                mNumRequestNetworkRequests += delta;
                break;

            case BACKGROUND_REQUEST:
                mNumRequestNetworkRequests += delta;
                mNumBackgroundNetworkRequests += delta;
                break;

            case LISTEN:
            case TRACK_DEFAULT:
            case TRACK_SYSTEM_DEFAULT:
                break;

            case NONE:
            default:
                Log.wtf(TAG, "Unhandled request type " + request.type);
                break;
        }
    }

    /**
     * Add {@code networkRequest} to this network as it's satisfied by this network.
     * @return true if {@code networkRequest} was added or false if {@code networkRequest} was
     *         already present.
     */
    public boolean addRequest(NetworkRequest networkRequest) {
        NetworkRequest existing = mNetworkRequests.get(networkRequest.requestId);
        if (existing == networkRequest) return false;
        if (existing != null) {
            // Should only happen if the requestId wraps. If that happens lots of other things will
            // be broken as well.
            Log.wtf(TAG, String.format("Duplicate requestId for %s and %s on %s",
                    networkRequest, existing, toShortString()));
            updateRequestCounts(REMOVE, existing);
        }
        mNetworkRequests.put(networkRequest.requestId, networkRequest);
        updateRequestCounts(ADD, networkRequest);
        return true;
    }

    /**
     * Remove the specified request from this network.
     */
    public void removeRequest(int requestId) {
        NetworkRequest existing = mNetworkRequests.get(requestId);
        if (existing == null) return;
        updateRequestCounts(REMOVE, existing);
        mNetworkRequests.remove(requestId);
        if (existing.isRequest()) {
            unlingerRequest(existing.requestId);
        }
    }

    /**
     * Returns whether this network is currently satisfying the request with the specified ID.
     */
    public boolean isSatisfyingRequest(int id) {
        return mNetworkRequests.get(id) != null;
    }

    /**
     * Returns the request at the specified position in the list of requests satisfied by this
     * network.
     */
    public NetworkRequest requestAt(int index) {
        return mNetworkRequests.valueAt(index);
    }

    /**
     * Returns the number of requests currently satisfied by this network for which
     * {@link android.net.NetworkRequest#isRequest} returns {@code true}.
     */
    public int numRequestNetworkRequests() {
        return mNumRequestNetworkRequests;
    }

    /**
     * Returns the number of requests currently satisfied by this network of type
     * {@link android.net.NetworkRequest.Type.BACKGROUND_REQUEST}.
     */
    public int numBackgroundNetworkRequests() {
        return mNumBackgroundNetworkRequests;
    }

    /**
     * Returns the number of foreground requests currently satisfied by this network.
     */
    public int numForegroundNetworkRequests() {
        return mNumRequestNetworkRequests - mNumBackgroundNetworkRequests;
    }

    /**
     * Returns the number of requests of any type currently satisfied by this network.
     */
    public int numNetworkRequests() {
        return mNetworkRequests.size();
    }

    /**
     * Returns whether the network is a background network. A network is a background network if it
     * does not have the NET_CAPABILITY_FOREGROUND capability, which implies it is satisfying no
     * foreground request, is not lingering (i.e. kept for a while after being outscored), and is
     * not a speculative network (i.e. kept pending validation when validation would have it
     * outscore another foreground network). That implies it is being kept up by some background
     * request (otherwise it would be torn down), maybe the mobile always-on request.
     */
    public boolean isBackgroundNetwork() {
        return !isVPN() && numForegroundNetworkRequests() == 0 && mNumBackgroundNetworkRequests > 0
                && !isLingering();
    }

    // Does this network satisfy request?
    public boolean satisfies(NetworkRequest request) {
        return created &&
                request.networkCapabilities.satisfiedByNetworkCapabilities(networkCapabilities);
    }

    public boolean satisfiesImmutableCapabilitiesOf(NetworkRequest request) {
        return created &&
                request.networkCapabilities.satisfiedByImmutableNetworkCapabilities(
                        networkCapabilities);
    }

    /** Whether this network is a VPN. */
    public boolean isVPN() {
        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    /** Whether this network might have underlying networks. Currently only true for VPNs. */
    public boolean supportsUnderlyingNetworks() {
        return isVPN();
    }

    private int getCurrentScore(boolean pretendValidated) {
        // TODO: We may want to refactor this into a NetworkScore class that takes a base score from
        // the NetworkAgent and signals from the NetworkAgent and uses those signals to modify the
        // score.  The NetworkScore class would provide a nice place to centralize score constants
        // so they are not scattered about the transports.

        // If this network is explicitly selected and the user has decided to use it even if it's
        // unvalidated, give it the maximum score. Also give it the maximum score if it's explicitly
        // selected and we're trying to see what its score could be. This ensures that we don't tear
        // down an explicitly selected network before the user gets a chance to prefer it when
        // a higher-scoring network (e.g., Ethernet) is available.
        if (networkAgentConfig.explicitlySelected
                && (networkAgentConfig.acceptUnvalidated || pretendValidated)) {
            return ConnectivityConstants.EXPLICITLY_SELECTED_NETWORK_SCORE;
        }

        int score = mScore;
        if (!lastValidated && !pretendValidated && !ignoreWifiUnvalidationPenalty() && !isVPN()) {
            score -= ConnectivityConstants.UNVALIDATED_SCORE_PENALTY;
        }
        if (score < 0) score = 0;
        return score;
    }

    // Return true on devices configured to ignore score penalty for wifi networks
    // that become unvalidated (b/31075769).
    private boolean ignoreWifiUnvalidationPenalty() {
        boolean isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean avoidBadWifi = mConnService.avoidBadWifi() || avoidUnvalidated;
        return isWifi && !avoidBadWifi && everValidated;
    }

    // Get the current score for this Network.  This may be modified from what the
    // NetworkAgent sent, as it has modifiers applied to it.
    public int getCurrentScore() {
        return getCurrentScore(false);
    }

    // Get the current score for this Network as if it was validated.  This may be modified from
    // what the NetworkAgent sent, as it has modifiers applied to it.
    public int getCurrentScoreAsValidated() {
        return getCurrentScore(true);
    }

    public void setScore(final int score) {
        mScore = score;
    }

    /**
     * Return a {@link NetworkStateSnapshot} for this network.
     */
    @NonNull
    public NetworkStateSnapshot getNetworkStateSnapshot() {
        synchronized (this) {
            // Network objects are outwardly immutable so there is no point in duplicating.
            // Duplicating also precludes sharing socket factories and connection pools.
            final String subscriberId = (networkAgentConfig != null)
                    ? networkAgentConfig.subscriberId : null;
            return new NetworkStateSnapshot(network, new NetworkCapabilities(networkCapabilities),
                    new LinkProperties(linkProperties), subscriberId, networkInfo.getType());
        }
    }

    /**
     * Sets the specified requestId to linger on this network for the specified time. Called by
     * ConnectivityService when the request is moved to another network with a higher score, or
     * when a network is newly created.
     *
     * @param requestId The requestId of the request that no longer need to be served by this
     *                  network. Or {@link NetworkRequest.REQUEST_ID_NONE} if this is the
     *                  {@code LingerTimer} for a newly created network.
     */
    public void lingerRequest(int requestId, long now, long duration) {
        if (mInactivityTimerForRequest.get(requestId) != null) {
            // Cannot happen. Once a request is lingering on a particular network, we cannot
            // re-linger it unless that network becomes the best for that request again, in which
            // case we should have unlingered it.
            Log.wtf(TAG, toShortString() + ": request " + requestId + " already lingered");
        }
        final long expiryMs = now + duration;
        InactivityTimer timer = new InactivityTimer(requestId, expiryMs);
        if (VDBG) Log.d(TAG, "Adding InactivityTimer " + timer + " to " + toShortString());
        mInactivityTimers.add(timer);
        mInactivityTimerForRequest.put(requestId, timer);
    }

    /**
     * Cancel lingering. Called by ConnectivityService when a request is added to this network.
     * Returns true if the given requestId was lingering on this network, false otherwise.
     */
    public boolean unlingerRequest(int requestId) {
        InactivityTimer timer = mInactivityTimerForRequest.get(requestId);
        if (timer != null) {
            if (VDBG) {
                Log.d(TAG, "Removing InactivityTimer " + timer + " from " + toShortString());
            }
            mInactivityTimers.remove(timer);
            mInactivityTimerForRequest.remove(requestId);
            return true;
        }
        return false;
    }

    public long getInactivityExpiry() {
        return mInactivityExpiryMs;
    }

    public void updateInactivityTimer() {
        long newExpiry = mInactivityTimers.isEmpty() ? 0 : mInactivityTimers.last().expiryMs;
        if (newExpiry == mInactivityExpiryMs) return;

        // Even if we're going to reschedule the timer, cancel it first. This is because the
        // semantics of WakeupMessage guarantee that if cancel is called then the alarm will
        // never call its callback (handleLingerComplete), even if it has already fired.
        // WakeupMessage makes no such guarantees about rescheduling a message, so if mLingerMessage
        // has already been dispatched, rescheduling to some time in the future won't stop it
        // from calling its callback immediately.
        if (mInactivityMessage != null) {
            mInactivityMessage.cancel();
            mInactivityMessage = null;
        }

        if (newExpiry > 0) {
            mInactivityMessage = new WakeupMessage(
                    mContext, mHandler,
                    "NETWORK_LINGER_COMPLETE." + network.getNetId() /* cmdName */,
                    EVENT_NETWORK_LINGER_COMPLETE /* cmd */,
                    0 /* arg1 (unused) */, 0 /* arg2 (unused) */,
                    this /* obj (NetworkAgentInfo) */);
            mInactivityMessage.schedule(newExpiry);
        }

        mInactivityExpiryMs = newExpiry;
    }

    public void setInactive() {
        mInactive = true;
    }

    public void unsetInactive() {
        mInactive = false;
    }

    public boolean isInactive() {
        return mInactive;
    }

    public boolean isLingering() {
        return mInactive && !isNascent();
    }

    /**
     * Return whether the network is just connected and about to be torn down because of not
     * satisfying any request.
     */
    public boolean isNascent() {
        return mInactive && mInactivityTimers.size() == 1
                && mInactivityTimers.first().requestId == NetworkRequest.REQUEST_ID_NONE;
    }

    public void clearInactivityState() {
        if (mInactivityMessage != null) {
            mInactivityMessage.cancel();
            mInactivityMessage = null;
        }
        mInactivityTimers.clear();
        mInactivityTimerForRequest.clear();
        // Sets mInactivityExpiryMs, cancels and nulls out mInactivityMessage.
        updateInactivityTimer();
        mInactive = false;
    }

    public void dumpInactivityTimers(PrintWriter pw) {
        for (InactivityTimer timer : mInactivityTimers) {
            pw.println(timer);
        }
    }

    /**
     * Sets the most recent ConnectivityReport for this network.
     *
     * <p>This should only be called from the ConnectivityService thread.
     *
     * @hide
     */
    public void setConnectivityReport(@NonNull ConnectivityReport connectivityReport) {
        mConnectivityReport = connectivityReport;
    }

    /**
     * Returns the most recent ConnectivityReport for this network, or null if none have been
     * reported yet.
     *
     * <p>This should only be called from the ConnectivityService thread.
     *
     * @hide
     */
    @Nullable
    public ConnectivityReport getConnectivityReport() {
        return mConnectivityReport;
    }

    // TODO: Print shorter members first and only print the boolean variable which value is true
    // to improve readability.
    public String toString() {
        return "NetworkAgentInfo{"
                + "network{" + network + "}  handle{" + network.getNetworkHandle() + "}  ni{"
                + networkInfo.toShortString() + "} "
                + "  Score{" + getCurrentScore() + "} "
                + (isNascent() ? " nascent" : (isLingering() ? " lingering" : ""))
                + (everValidated ? " everValidated" : "")
                + (lastValidated ? " lastValidated" : "")
                + (partialConnectivity ? " partialConnectivity" : "")
                + (everCaptivePortalDetected ? " everCaptivePortal" : "")
                + (lastCaptivePortalDetected ? " isCaptivePortal" : "")
                + (networkAgentConfig.explicitlySelected ? " explicitlySelected" : "")
                + (networkAgentConfig.acceptUnvalidated ? " acceptUnvalidated" : "")
                + (networkAgentConfig.acceptPartialConnectivity ? " acceptPartialConnectivity" : "")
                + (clatd.isStarted() ? " clat{" + clatd + "} " : "")
                + (declaredUnderlyingNetworks != null
                        ? " underlying{" + Arrays.toString(declaredUnderlyingNetworks) + "}" : "")
                + "  lp{" + linkProperties + "}"
                + "  nc{" + networkCapabilities + "}"
                + "}";
    }

    /**
     * Show a short string representing a Network.
     *
     * This is often not enough for debugging purposes for anything complex, but the full form
     * is very long and hard to read, so this is useful when there isn't a lot of ambiguity.
     * This represents the network with something like "[100 WIFI|VPN]" or "[108 MOBILE]".
     */
    public String toShortString() {
        return "[" + network.getNetId() + " "
                + transportNamesOf(networkCapabilities.getTransportTypes()) + "]";
    }

    // Enables sorting in descending order of score.
    @Override
    public int compareTo(NetworkAgentInfo other) {
        return other.getCurrentScore() - getCurrentScore();
    }

    /**
     * Null-guarding version of NetworkAgentInfo#toShortString()
     */
    @NonNull
    public static String toShortString(@Nullable final NetworkAgentInfo nai) {
        return null != nai ? nai.toShortString() : "[null]";
    }
}
