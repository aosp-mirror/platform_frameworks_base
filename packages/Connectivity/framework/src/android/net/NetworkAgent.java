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

package android.net;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.data.EpsBearerQosSessionAttributes;
import android.telephony.data.NrQosSessionAttributes;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A utility class for handling for communicating between bearer-specific
 * code and ConnectivityService.
 *
 * An agent manages the life cycle of a network. A network starts its
 * life cycle when {@link register} is called on NetworkAgent. The network
 * is then connecting. When full L3 connectivity has been established,
 * the agent should call {@link markConnected} to inform the system that
 * this network is ready to use. When the network disconnects its life
 * ends and the agent should call {@link unregister}, at which point the
 * system will clean up and free resources.
 * Any reconnection becomes a new logical network, so after a network
 * is disconnected the agent cannot be used any more. Network providers
 * should create a new NetworkAgent instance to handle new connections.
 *
 * A bearer may have more than one NetworkAgent if it can simultaneously
 * support separate networks (IMS / Internet / MMS Apns on cellular, or
 * perhaps connections with different SSID or P2P for Wi-Fi).
 *
 * This class supports methods to start and stop sending keepalive packets.
 * Keepalive packets are typically sent at periodic intervals over a network
 * with NAT when there is no other traffic to avoid the network forcefully
 * closing the connection. NetworkAgents that manage technologies that
 * have hardware support for keepalive should implement the related
 * methods to save battery life. NetworkAgent that cannot get support
 * without waking up the CPU should not, as this would be prohibitive in
 * terms of battery - these agents should simply not override the related
 * methods, which results in the implementation returning
 * {@link SocketKeepalive.ERROR_UNSUPPORTED} as appropriate.
 *
 * Keepalive packets need to be sent at relatively frequent intervals
 * (a few seconds to a few minutes). As the contents of keepalive packets
 * depend on the current network status, hardware needs to be configured
 * to send them and has a limited amount of memory to do so. The HAL
 * formalizes this as slots that an implementation can configure to send
 * the correct packets. Devices typically have a small number of slots
 * per radio technology, and the specific number of slots for each
 * technology is specified in configuration files.
 * {@see SocketKeepalive} for details.
 *
 * @hide
 */
@SystemApi
public abstract class NetworkAgent {
    /**
     * The {@link Network} corresponding to this object.
     */
    @Nullable
    private volatile Network mNetwork;

    @Nullable
    private volatile INetworkAgentRegistry mRegistry;

    private interface RegistryAction {
        void execute(@NonNull INetworkAgentRegistry registry) throws RemoteException;
    }

    private final Handler mHandler;
    private final String LOG_TAG;
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private final ArrayList<RegistryAction> mPreConnectedQueue = new ArrayList<>();
    private volatile long mLastBwRefreshTime = 0;
    private static final long BW_REFRESH_MIN_WIN_MS = 500;
    private boolean mBandwidthUpdateScheduled = false;
    private AtomicBoolean mBandwidthUpdatePending = new AtomicBoolean(false);
    @NonNull
    private NetworkInfo mNetworkInfo;
    @NonNull
    private final Object mRegisterLock = new Object();

    /**
     * The ID of the {@link NetworkProvider} that created this object, or
     * {@link NetworkProvider#ID_NONE} if unknown.
     * @hide
     */
    public final int providerId;

    // ConnectivityService parses message constants from itself and NetworkAgent with MessageUtils
    // for debugging purposes, and crashes if some messages have the same values.
    // TODO: have ConnectivityService store message names in different maps and remove this base
    private static final int BASE = 200;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform it of
     * suspected connectivity problems on its network.  The NetworkAgent
     * should take steps to verify and correct connectivity.
     * @hide
     */
    public static final int CMD_SUSPECT_BAD = BASE;

    /**
     * Sent by the NetworkAgent (note the EVENT vs CMD prefix) to
     * ConnectivityService to pass the current NetworkInfo (connection state).
     * Sent when the NetworkInfo changes, mainly due to change of state.
     * obj = NetworkInfo
     * @hide
     */
    public static final int EVENT_NETWORK_INFO_CHANGED = BASE + 1;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * NetworkCapabilties.
     * obj = NetworkCapabilities
     * @hide
     */
    public static final int EVENT_NETWORK_CAPABILITIES_CHANGED = BASE + 2;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * NetworkProperties.
     * obj = NetworkProperties
     * @hide
     */
    public static final int EVENT_NETWORK_PROPERTIES_CHANGED = BASE + 3;

    /**
     * Centralize the place where base network score, and network score scaling, will be
     * stored, so as we can consistently compare apple and oranges, or wifi, ethernet and LTE
     * @hide
     */
    public static final int WIFI_BASE_SCORE = 60;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * network score.
     * arg1 = network score int
     * @hide
     */
    public static final int EVENT_NETWORK_SCORE_CHANGED = BASE + 4;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * list of underlying networks.
     * obj = array of Network objects
     * @hide
     */
    public static final int EVENT_UNDERLYING_NETWORKS_CHANGED = BASE + 5;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform the agent of the
     * networks status - whether we could use the network or could not, due to
     * either a bad network configuration (no internet link) or captive portal.
     *
     * arg1 = either {@code VALID_NETWORK} or {@code INVALID_NETWORK}
     * obj = Bundle containing map from {@code REDIRECT_URL_KEY} to {@code String}
     *       representing URL that Internet probe was redirect to, if it was redirected,
     *       or mapping to {@code null} otherwise.
     * @hide
     */
    public static final int CMD_REPORT_NETWORK_STATUS = BASE + 7;


    /**
     * Network validation suceeded.
     * Corresponds to {@link NetworkCapabilities.NET_CAPABILITY_VALIDATED}.
     */
    public static final int VALIDATION_STATUS_VALID = 1;

    /**
     * Network validation was attempted and failed. This may be received more than once as
     * subsequent validation attempts are made.
     */
    public static final int VALIDATION_STATUS_NOT_VALID = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "VALIDATION_STATUS_" }, value = {
            VALIDATION_STATUS_VALID,
            VALIDATION_STATUS_NOT_VALID
    })
    public @interface ValidationStatus {}

    // TODO: remove.
    /** @hide */
    public static final int VALID_NETWORK = 1;
    /** @hide */
    public static final int INVALID_NETWORK = 2;

    /**
     * The key for the redirect URL in the Bundle argument of {@code CMD_REPORT_NETWORK_STATUS}.
     * @hide
     */
    public static final String REDIRECT_URL_KEY = "redirect URL";

    /**
     * Sent by the NetworkAgent to ConnectivityService to indicate this network was
     * explicitly selected.  This should be sent before the NetworkInfo is marked
     * CONNECTED so it can be given special treatment at that time.
     *
     * obj = boolean indicating whether to use this network even if unvalidated
     * @hide
     */
    public static final int EVENT_SET_EXPLICITLY_SELECTED = BASE + 8;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform the agent of
     * whether the network should in the future be used even if not validated.
     * This decision is made by the user, but it is the network transport's
     * responsibility to remember it.
     *
     * arg1 = 1 if true, 0 if false
     * @hide
     */
    public static final int CMD_SAVE_ACCEPT_UNVALIDATED = BASE + 9;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform the agent to pull
     * the underlying network connection for updated bandwidth information.
     * @hide
     */
    public static final int CMD_REQUEST_BANDWIDTH_UPDATE = BASE + 10;

    /**
     * Sent by ConnectivityService to the NetworkAgent to request that the specified packet be sent
     * periodically on the given interval.
     *
     *   arg1 = the hardware slot number of the keepalive to start
     *   arg2 = interval in seconds
     *   obj = KeepalivePacketData object describing the data to be sent
     *
     * Also used internally by ConnectivityService / KeepaliveTracker, with different semantics.
     * @hide
     */
    public static final int CMD_START_SOCKET_KEEPALIVE = BASE + 11;

    /**
     * Requests that the specified keepalive packet be stopped.
     *
     * arg1 = hardware slot number of the keepalive to stop.
     *
     * Also used internally by ConnectivityService / KeepaliveTracker, with different semantics.
     * @hide
     */
    public static final int CMD_STOP_SOCKET_KEEPALIVE = BASE + 12;

    /**
     * Sent by the NetworkAgent to ConnectivityService to provide status on a socket keepalive
     * request. This may either be the reply to a CMD_START_SOCKET_KEEPALIVE, or an asynchronous
     * error notification.
     *
     * This is also sent by KeepaliveTracker to the app's {@link SocketKeepalive},
     * so that the app's {@link SocketKeepalive.Callback} methods can be called.
     *
     * arg1 = hardware slot number of the keepalive
     * arg2 = error code
     * @hide
     */
    public static final int EVENT_SOCKET_KEEPALIVE = BASE + 13;

    /**
     * Sent by ConnectivityService to inform this network transport of signal strength thresholds
     * that when crossed should trigger a system wakeup and a NetworkCapabilities update.
     *
     *   obj = int[] describing signal strength thresholds.
     * @hide
     */
    public static final int CMD_SET_SIGNAL_STRENGTH_THRESHOLDS = BASE + 14;

    /**
     * Sent by ConnectivityService to the NeworkAgent to inform the agent to avoid
     * automatically reconnecting to this network (e.g. via autojoin).  Happens
     * when user selects "No" option on the "Stay connected?" dialog box.
     * @hide
     */
    public static final int CMD_PREVENT_AUTOMATIC_RECONNECT = BASE + 15;

    /**
     * Sent by the KeepaliveTracker to NetworkAgent to add a packet filter.
     *
     * For TCP keepalive offloads, keepalive packets are sent by the firmware. However, because the
     * remote site will send ACK packets in response to the keepalive packets, the firmware also
     * needs to be configured to properly filter the ACKs to prevent the system from waking up.
     * This does not happen with UDP, so this message is TCP-specific.
     * arg1 = hardware slot number of the keepalive to filter for.
     * obj = the keepalive packet to send repeatedly.
     * @hide
     */
    public static final int CMD_ADD_KEEPALIVE_PACKET_FILTER = BASE + 16;

    /**
     * Sent by the KeepaliveTracker to NetworkAgent to remove a packet filter. See
     * {@link #CMD_ADD_KEEPALIVE_PACKET_FILTER}.
     * arg1 = hardware slot number of the keepalive packet filter to remove.
     * @hide
     */
    public static final int CMD_REMOVE_KEEPALIVE_PACKET_FILTER = BASE + 17;

    /**
     * Sent by ConnectivityService to the NetworkAgent to complete the bidirectional connection.
     * obj = INetworkAgentRegistry
     */
    private static final int EVENT_AGENT_CONNECTED = BASE + 18;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform the agent that it was disconnected.
     */
    private static final int EVENT_AGENT_DISCONNECTED = BASE + 19;

    /**
     * Sent by QosCallbackTracker to {@link NetworkAgent} to register a new filter with
     * callback.
     *
     * arg1 = QoS agent callback ID
     * obj = {@link QosFilter}
     * @hide
     */
    public static final int CMD_REGISTER_QOS_CALLBACK = BASE + 20;

    /**
     * Sent by QosCallbackTracker to {@link NetworkAgent} to unregister a callback.
     *
     * arg1 = QoS agent callback ID
     * @hide
     */
    public static final int CMD_UNREGISTER_QOS_CALLBACK = BASE + 21;

    private static NetworkInfo getLegacyNetworkInfo(final NetworkAgentConfig config) {
        // The subtype can be changed with (TODO) setLegacySubtype, but it starts
        // with 0 (TelephonyManager.NETWORK_TYPE_UNKNOWN) and an empty description.
        final NetworkInfo ni = new NetworkInfo(config.legacyType, 0, config.legacyTypeName, "");
        ni.setIsAvailable(true);
        ni.setDetailedState(NetworkInfo.DetailedState.CONNECTING, null /* reason */,
                config.getLegacyExtraInfo());
        return ni;
    }

    // Temporary backward compatibility constructor
    public NetworkAgent(@NonNull Context context, @NonNull Looper looper, @NonNull String logTag,
            @NonNull NetworkCapabilities nc, @NonNull LinkProperties lp, int score,
            @NonNull NetworkAgentConfig config, @Nullable NetworkProvider provider) {
        this(context, looper, logTag, nc, lp,
                new NetworkScore.Builder().setLegacyInt(score).build(), config, provider);
    }

    /**
     * Create a new network agent.
     * @param context a {@link Context} to get system services from.
     * @param looper the {@link Looper} on which to invoke the callbacks.
     * @param logTag the tag for logs
     * @param nc the initial {@link NetworkCapabilities} of this network. Update with
     *           sendNetworkCapabilities.
     * @param lp the initial {@link LinkProperties} of this network. Update with sendLinkProperties.
     * @param score the initial score of this network. Update with sendNetworkScore.
     * @param config an immutable {@link NetworkAgentConfig} for this agent.
     * @param provider the {@link NetworkProvider} managing this agent.
     * @hide TODO : unhide when impl is complete
     */
    public NetworkAgent(@NonNull Context context, @NonNull Looper looper, @NonNull String logTag,
            @NonNull NetworkCapabilities nc, @NonNull LinkProperties lp,
            @NonNull NetworkScore score, @NonNull NetworkAgentConfig config,
            @Nullable NetworkProvider provider) {
        this(looper, context, logTag, nc, lp, score, config,
                provider == null ? NetworkProvider.ID_NONE : provider.getProviderId(),
                getLegacyNetworkInfo(config));
    }

    private static class InitialConfiguration {
        public final Context context;
        public final NetworkCapabilities capabilities;
        public final LinkProperties properties;
        public final NetworkScore score;
        public final NetworkAgentConfig config;
        public final NetworkInfo info;
        InitialConfiguration(@NonNull Context context, @NonNull NetworkCapabilities capabilities,
                @NonNull LinkProperties properties, @NonNull NetworkScore score,
                @NonNull NetworkAgentConfig config, @NonNull NetworkInfo info) {
            this.context = context;
            this.capabilities = capabilities;
            this.properties = properties;
            this.score = score;
            this.config = config;
            this.info = info;
        }
    }
    private volatile InitialConfiguration mInitialConfiguration;

    private NetworkAgent(@NonNull Looper looper, @NonNull Context context, @NonNull String logTag,
            @NonNull NetworkCapabilities nc, @NonNull LinkProperties lp,
            @NonNull NetworkScore score, @NonNull NetworkAgentConfig config, int providerId,
            @NonNull NetworkInfo ni) {
        mHandler = new NetworkAgentHandler(looper);
        LOG_TAG = logTag;
        mNetworkInfo = new NetworkInfo(ni);
        this.providerId = providerId;
        if (ni == null || nc == null || lp == null) {
            throw new IllegalArgumentException();
        }

        mInitialConfiguration = new InitialConfiguration(context,
                new NetworkCapabilities(nc, NetworkCapabilities.REDACT_NONE),
                new LinkProperties(lp), score, config, ni);
    }

    private class NetworkAgentHandler extends Handler {
        NetworkAgentHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_AGENT_CONNECTED: {
                    if (mRegistry != null) {
                        log("Received new connection while already connected!");
                    } else {
                        if (VDBG) log("NetworkAgent fully connected");
                        synchronized (mPreConnectedQueue) {
                            final INetworkAgentRegistry registry = (INetworkAgentRegistry) msg.obj;
                            mRegistry = registry;
                            for (RegistryAction a : mPreConnectedQueue) {
                                try {
                                    a.execute(registry);
                                } catch (RemoteException e) {
                                    Log.wtf(LOG_TAG, "Communication error with registry", e);
                                    // Fall through
                                }
                            }
                            mPreConnectedQueue.clear();
                        }
                    }
                    break;
                }
                case EVENT_AGENT_DISCONNECTED: {
                    if (DBG) log("NetworkAgent channel lost");
                    // let the client know CS is done with us.
                    onNetworkUnwanted();
                    synchronized (mPreConnectedQueue) {
                        mRegistry = null;
                    }
                    break;
                }
                case CMD_SUSPECT_BAD: {
                    log("Unhandled Message " + msg);
                    break;
                }
                case CMD_REQUEST_BANDWIDTH_UPDATE: {
                    long currentTimeMs = System.currentTimeMillis();
                    if (VDBG) {
                        log("CMD_REQUEST_BANDWIDTH_UPDATE request received.");
                    }
                    if (currentTimeMs >= (mLastBwRefreshTime + BW_REFRESH_MIN_WIN_MS)) {
                        mBandwidthUpdateScheduled = false;
                        if (!mBandwidthUpdatePending.getAndSet(true)) {
                            onBandwidthUpdateRequested();
                        }
                    } else {
                        // deliver the request at a later time rather than discard it completely.
                        if (!mBandwidthUpdateScheduled) {
                            long waitTime = mLastBwRefreshTime + BW_REFRESH_MIN_WIN_MS
                                    - currentTimeMs + 1;
                            mBandwidthUpdateScheduled = sendEmptyMessageDelayed(
                                    CMD_REQUEST_BANDWIDTH_UPDATE, waitTime);
                        }
                    }
                    break;
                }
                case CMD_REPORT_NETWORK_STATUS: {
                    String redirectUrl = ((Bundle) msg.obj).getString(REDIRECT_URL_KEY);
                    if (VDBG) {
                        log("CMD_REPORT_NETWORK_STATUS("
                                + (msg.arg1 == VALID_NETWORK ? "VALID, " : "INVALID, ")
                                + redirectUrl);
                    }
                    Uri uri = null;
                    try {
                        if (null != redirectUrl) {
                            uri = Uri.parse(redirectUrl);
                        }
                    } catch (Exception e) {
                        Log.wtf(LOG_TAG, "Surprising URI : " + redirectUrl, e);
                    }
                    onValidationStatus(msg.arg1 /* status */, uri);
                    break;
                }
                case CMD_SAVE_ACCEPT_UNVALIDATED: {
                    onSaveAcceptUnvalidated(msg.arg1 != 0);
                    break;
                }
                case CMD_START_SOCKET_KEEPALIVE: {
                    onStartSocketKeepalive(msg.arg1 /* slot */,
                            Duration.ofSeconds(msg.arg2) /* interval */,
                            (KeepalivePacketData) msg.obj /* packet */);
                    break;
                }
                case CMD_STOP_SOCKET_KEEPALIVE: {
                    onStopSocketKeepalive(msg.arg1 /* slot */);
                    break;
                }

                case CMD_SET_SIGNAL_STRENGTH_THRESHOLDS: {
                    onSignalStrengthThresholdsUpdated((int[]) msg.obj);
                    break;
                }
                case CMD_PREVENT_AUTOMATIC_RECONNECT: {
                    onAutomaticReconnectDisabled();
                    break;
                }
                case CMD_ADD_KEEPALIVE_PACKET_FILTER: {
                    onAddKeepalivePacketFilter(msg.arg1 /* slot */,
                            (KeepalivePacketData) msg.obj /* packet */);
                    break;
                }
                case CMD_REMOVE_KEEPALIVE_PACKET_FILTER: {
                    onRemoveKeepalivePacketFilter(msg.arg1 /* slot */);
                    break;
                }
                case CMD_REGISTER_QOS_CALLBACK: {
                    onQosCallbackRegistered(
                            msg.arg1 /* QoS callback id */,
                            (QosFilter) msg.obj /* QoS filter */);
                    break;
                }
                case CMD_UNREGISTER_QOS_CALLBACK: {
                    onQosCallbackUnregistered(
                            msg.arg1 /* QoS callback id */);
                    break;
                }
            }
        }
    }

    /**
     * Register this network agent with ConnectivityService.
     *
     * This method can only be called once per network agent.
     *
     * @return the Network associated with this network agent (which can also be obtained later
     *         by calling getNetwork() on this agent).
     * @throws IllegalStateException thrown by the system server if this network agent is
     *         already registered.
     */
    @NonNull
    public Network register() {
        if (VDBG) log("Registering NetworkAgent");
        synchronized (mRegisterLock) {
            if (mNetwork != null) {
                throw new IllegalStateException("Agent already registered");
            }
            final ConnectivityManager cm = (ConnectivityManager) mInitialConfiguration.context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            mNetwork = cm.registerNetworkAgent(new NetworkAgentBinder(mHandler),
                    new NetworkInfo(mInitialConfiguration.info),
                    mInitialConfiguration.properties, mInitialConfiguration.capabilities,
                    mInitialConfiguration.score, mInitialConfiguration.config, providerId);
            mInitialConfiguration = null; // All this memory can now be GC'd
        }
        return mNetwork;
    }

    private static class NetworkAgentBinder extends INetworkAgent.Stub {
        private static final String LOG_TAG = NetworkAgentBinder.class.getSimpleName();

        private final Handler mHandler;

        private NetworkAgentBinder(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void onRegistered(@NonNull INetworkAgentRegistry registry) {
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_AGENT_CONNECTED, registry));
        }

        @Override
        public void onDisconnected() {
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_AGENT_DISCONNECTED));
        }

        @Override
        public void onBandwidthUpdateRequested() {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_REQUEST_BANDWIDTH_UPDATE));
        }

        @Override
        public void onValidationStatusChanged(
                int validationStatus, @Nullable String captivePortalUrl) {
            // TODO: consider using a parcelable as argument when the interface is structured
            Bundle redirectUrlBundle = new Bundle();
            redirectUrlBundle.putString(NetworkAgent.REDIRECT_URL_KEY, captivePortalUrl);
            mHandler.sendMessage(mHandler.obtainMessage(CMD_REPORT_NETWORK_STATUS,
                    validationStatus, 0, redirectUrlBundle));
        }

        @Override
        public void onSaveAcceptUnvalidated(boolean acceptUnvalidated) {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_SAVE_ACCEPT_UNVALIDATED,
                    acceptUnvalidated ? 1 : 0, 0));
        }

        @Override
        public void onStartNattSocketKeepalive(int slot, int intervalDurationMs,
                @NonNull NattKeepalivePacketData packetData) {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_START_SOCKET_KEEPALIVE,
                    slot, intervalDurationMs, packetData));
        }

        @Override
        public void onStartTcpSocketKeepalive(int slot, int intervalDurationMs,
                @NonNull TcpKeepalivePacketData packetData) {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_START_SOCKET_KEEPALIVE,
                    slot, intervalDurationMs, packetData));
        }

        @Override
        public void onStopSocketKeepalive(int slot) {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_STOP_SOCKET_KEEPALIVE, slot, 0));
        }

        @Override
        public void onSignalStrengthThresholdsUpdated(@NonNull int[] thresholds) {
            mHandler.sendMessage(mHandler.obtainMessage(
                    CMD_SET_SIGNAL_STRENGTH_THRESHOLDS, thresholds));
        }

        @Override
        public void onPreventAutomaticReconnect() {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_PREVENT_AUTOMATIC_RECONNECT));
        }

        @Override
        public void onAddNattKeepalivePacketFilter(int slot,
                @NonNull NattKeepalivePacketData packetData) {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_ADD_KEEPALIVE_PACKET_FILTER,
                    slot, 0, packetData));
        }

        @Override
        public void onAddTcpKeepalivePacketFilter(int slot,
                @NonNull TcpKeepalivePacketData packetData) {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_ADD_KEEPALIVE_PACKET_FILTER,
                    slot, 0, packetData));
        }

        @Override
        public void onRemoveKeepalivePacketFilter(int slot) {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_REMOVE_KEEPALIVE_PACKET_FILTER,
                    slot, 0));
        }

        @Override
        public void onQosFilterCallbackRegistered(final int qosCallbackId,
                final QosFilterParcelable qosFilterParcelable) {
            if (qosFilterParcelable.getQosFilter() != null) {
                mHandler.sendMessage(
                        mHandler.obtainMessage(CMD_REGISTER_QOS_CALLBACK, qosCallbackId, 0,
                                qosFilterParcelable.getQosFilter()));
                return;
            }

            Log.wtf(LOG_TAG, "onQosFilterCallbackRegistered: qos filter is null.");
        }

        @Override
        public void onQosCallbackUnregistered(final int qosCallbackId) {
            mHandler.sendMessage(mHandler.obtainMessage(
                    CMD_UNREGISTER_QOS_CALLBACK, qosCallbackId, 0, null));
        }
    }

    /**
     * Register this network agent with a testing harness.
     *
     * The returned Messenger sends messages to the Handler. This allows a test to send
     * this object {@code CMD_*} messages as if they came from ConnectivityService, which
     * is useful for testing the behavior.
     *
     * @hide
     */
    public INetworkAgent registerForTest(final Network network) {
        log("Registering NetworkAgent for test");
        synchronized (mRegisterLock) {
            mNetwork = network;
            mInitialConfiguration = null;
        }
        return new NetworkAgentBinder(mHandler);
    }

    /**
     * Waits for the handler to be idle.
     * This is useful for testing, and has smaller scope than an accessor to mHandler.
     * TODO : move the implementation in common library with the tests
     * @hide
     */
    @VisibleForTesting
    public boolean waitForIdle(final long timeoutMs) {
        final ConditionVariable cv = new ConditionVariable(false);
        mHandler.post(cv::open);
        return cv.block(timeoutMs);
    }

    /**
     * @return The Network associated with this agent, or null if it's not registered yet.
     */
    @Nullable
    public Network getNetwork() {
        return mNetwork;
    }

    private void queueOrSendMessage(@NonNull RegistryAction action) {
        synchronized (mPreConnectedQueue) {
            if (mRegistry != null) {
                try {
                    action.execute(mRegistry);
                } catch (RemoteException e) {
                    Log.wtf(LOG_TAG, "Error executing registry action", e);
                    // Fall through: the channel is asynchronous and does not report errors back
                }
            } else {
                mPreConnectedQueue.add(action);
            }
        }
    }

    /**
     * Must be called by the agent when the network's {@link LinkProperties} change.
     * @param linkProperties the new LinkProperties.
     */
    public final void sendLinkProperties(@NonNull LinkProperties linkProperties) {
        Objects.requireNonNull(linkProperties);
        final LinkProperties lp = new LinkProperties(linkProperties);
        queueOrSendMessage(reg -> reg.sendLinkProperties(lp));
    }

    /**
     * Must be called by the agent when the network's underlying networks change.
     *
     * <p>{@code networks} is one of the following:
     * <ul>
     * <li><strong>a non-empty array</strong>: an array of one or more {@link Network}s, in
     * decreasing preference order. For example, if this VPN uses both wifi and mobile (cellular)
     * networks to carry app traffic, but prefers or uses wifi more than mobile, wifi should appear
     * first in the array.</li>
     * <li><strong>an empty array</strong>: a zero-element array, meaning that the VPN has no
     * underlying network connection, and thus, app traffic will not be sent or received.</li>
     * <li><strong>null</strong>: (default) signifies that the VPN uses whatever is the system's
     * default network. I.e., it doesn't use the {@code bindSocket} or {@code bindDatagramSocket}
     * APIs mentioned above to send traffic over specific channels.</li>
     * </ul>
     *
     * @param underlyingNetworks the new list of underlying networks.
     * @see {@link VpnService.Builder#setUnderlyingNetworks(Network[])}
     */
    public final void setUnderlyingNetworks(
            @SuppressLint("NullableCollection") @Nullable List<Network> underlyingNetworks) {
        final ArrayList<Network> underlyingArray = (underlyingNetworks != null)
                ? new ArrayList<>(underlyingNetworks) : null;
        queueOrSendMessage(reg -> reg.sendUnderlyingNetworks(underlyingArray));
    }

    /**
     * Inform ConnectivityService that this agent has now connected.
     * Call {@link #unregister} to disconnect.
     */
    public void markConnected() {
        mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null /* reason */,
                mNetworkInfo.getExtraInfo());
        queueOrSendNetworkInfo(mNetworkInfo);
    }

    /**
     * Unregister this network agent.
     *
     * This signals the network has disconnected and ends its lifecycle. After this is called,
     * the network is torn down and this agent can no longer be used.
     */
    public void unregister() {
        // When unregistering an agent nobody should use the extrainfo (or reason) any more.
        mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null /* reason */,
                null /* extraInfo */);
        queueOrSendNetworkInfo(mNetworkInfo);
    }

    /**
     * Change the legacy subtype of this network agent.
     *
     * This is only for backward compatibility and should not be used by non-legacy network agents,
     * or agents that did not use to set a subtype. As such, only TYPE_MOBILE type agents can use
     * this and others will be thrown an exception if they try.
     *
     * @deprecated this is for backward compatibility only.
     * @param legacySubtype the legacy subtype.
     * @hide
     */
    @Deprecated
    public void setLegacySubtype(final int legacySubtype, @NonNull final String legacySubtypeName) {
        mNetworkInfo.setSubtype(legacySubtype, legacySubtypeName);
        queueOrSendNetworkInfo(mNetworkInfo);
    }

    /**
     * Set the ExtraInfo of this network agent.
     *
     * This sets the ExtraInfo field inside the NetworkInfo returned by legacy public API and the
     * broadcasts about the corresponding Network.
     * This is only for backward compatibility and should not be used by non-legacy network agents,
     * who will be thrown an exception if they try. The extra info should only be :
     * <ul>
     *   <li>For cellular agents, the APN name.</li>
     *   <li>For ethernet agents, the interface name.</li>
     * </ul>
     *
     * @deprecated this is for backward compatibility only.
     * @param extraInfo the ExtraInfo.
     * @hide
     */
    @Deprecated
    public void setLegacyExtraInfo(@Nullable final String extraInfo) {
        mNetworkInfo.setExtraInfo(extraInfo);
        queueOrSendNetworkInfo(mNetworkInfo);
    }

    /**
     * Must be called by the agent when it has a new NetworkInfo object.
     * @hide TODO: expose something better.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public final void sendNetworkInfo(NetworkInfo networkInfo) {
        queueOrSendNetworkInfo(new NetworkInfo(networkInfo));
    }

    private void queueOrSendNetworkInfo(NetworkInfo networkInfo) {
        queueOrSendMessage(reg -> reg.sendNetworkInfo(networkInfo));
    }

    /**
     * Must be called by the agent when the network's {@link NetworkCapabilities} change.
     * @param networkCapabilities the new NetworkCapabilities.
     */
    public final void sendNetworkCapabilities(@NonNull NetworkCapabilities networkCapabilities) {
        Objects.requireNonNull(networkCapabilities);
        mBandwidthUpdatePending.set(false);
        mLastBwRefreshTime = System.currentTimeMillis();
        final NetworkCapabilities nc =
                new NetworkCapabilities(networkCapabilities, NetworkCapabilities.REDACT_NONE);
        queueOrSendMessage(reg -> reg.sendNetworkCapabilities(nc));
    }

    /**
     * Must be called by the agent to update the score of this network.
     *
     * @param score the new score.
     * @hide TODO : unhide when impl is complete
     */
    public final void sendNetworkScore(@NonNull NetworkScore score) {
        Objects.requireNonNull(score);
        queueOrSendMessage(reg -> reg.sendScore(score));
    }

    /**
     * Must be called by the agent to update the score of this network.
     *
     * @param score the new score, between 0 and 99.
     * deprecated use sendNetworkScore(NetworkScore) TODO : remove in S.
     */
    public final void sendNetworkScore(@IntRange(from = 0, to = 99) int score) {
        sendNetworkScore(new NetworkScore.Builder().setLegacyInt(score).build());
    }

    /**
     * Must be called by the agent to indicate this network was manually selected by the user.
     * This should be called before the NetworkInfo is marked CONNECTED so that this
     * Network can be given special treatment at that time. If {@code acceptUnvalidated} is
     * {@code true}, then the system will switch to this network. If it is {@code false} and the
     * network cannot be validated, the system will ask the user whether to switch to this network.
     * If the user confirms and selects "don't ask again", then the system will call
     * {@link #saveAcceptUnvalidated} to persist the user's choice. Thus, if the transport ever
     * calls this method with {@code acceptUnvalidated} set to {@code false}, it must also implement
     * {@link #saveAcceptUnvalidated} to respect the user's choice.
     * @hide should move to NetworkAgentConfig.
     */
    public void explicitlySelected(boolean acceptUnvalidated) {
        explicitlySelected(true /* explicitlySelected */, acceptUnvalidated);
    }

    /**
     * Must be called by the agent to indicate whether the network was manually selected by the
     * user. This should be called before the network becomes connected, so it can be given
     * special treatment when it does.
     *
     * If {@code explicitlySelected} is {@code true}, and {@code acceptUnvalidated} is {@code true},
     * then the system will switch to this network. If {@code explicitlySelected} is {@code true}
     * and {@code acceptUnvalidated} is {@code false}, and the  network cannot be validated, the
     * system will ask the user whether to switch to this network.  If the user confirms and selects
     * "don't ask again", then the system will call {@link #saveAcceptUnvalidated} to persist the
     * user's choice. Thus, if the transport ever calls this method with {@code explicitlySelected}
     * set to {@code true} and {@code acceptUnvalidated} set to {@code false}, it must also
     * implement {@link #saveAcceptUnvalidated} to respect the user's choice.
     *
     * If  {@code explicitlySelected} is {@code false} and {@code acceptUnvalidated} is
     * {@code true}, the system will interpret this as the user having accepted partial connectivity
     * on this network. Thus, the system will switch to the network and consider it validated even
     * if it only provides partial connectivity, but the network is not otherwise treated specially.
     * @hide should move to NetworkAgentConfig.
     */
    public void explicitlySelected(boolean explicitlySelected, boolean acceptUnvalidated) {
        queueOrSendMessage(reg -> reg.sendExplicitlySelected(
                explicitlySelected, acceptUnvalidated));
    }

    /**
     * Called when ConnectivityService has indicated they no longer want this network.
     * The parent factory should (previously) have received indication of the change
     * as well, either canceling NetworkRequests or altering their score such that this
     * network won't be immediately requested again.
     */
    public void onNetworkUnwanted() {
        unwanted();
    }
    /** @hide TODO delete once subclasses have moved to onNetworkUnwanted. */
    protected void unwanted() {
    }

    /**
     * Called when ConnectivityService request a bandwidth update. The parent factory
     * shall try to overwrite this method and produce a bandwidth update if capable.
     * @hide
     */
    public void onBandwidthUpdateRequested() {
        pollLceData();
    }
    /** @hide TODO delete once subclasses have moved to onBandwidthUpdateRequested. */
    protected void pollLceData() {
    }

    /**
     * Called when the system determines the usefulness of this network.
     *
     * The system attempts to validate Internet connectivity on networks that provide the
     * {@link NetworkCapabilities#NET_CAPABILITY_INTERNET} capability.
     *
     * Currently there are two possible values:
     * {@code VALIDATION_STATUS_VALID} if Internet connectivity was validated,
     * {@code VALIDATION_STATUS_NOT_VALID} if Internet connectivity was not validated.
     *
     * This is guaranteed to be called again when the network status changes, but the system
     * may also call this multiple times even if the status does not change.
     *
     * @param status one of {@code VALIDATION_STATUS_VALID} or {@code VALIDATION_STATUS_NOT_VALID}.
     * @param redirectUri If Internet connectivity is being redirected (e.g., on a captive portal),
     *        this is the destination the probes are being redirected to, otherwise {@code null}.
     */
    public void onValidationStatus(@ValidationStatus int status, @Nullable Uri redirectUri) {
        networkStatus(status, null == redirectUri ? "" : redirectUri.toString());
    }
    /** @hide TODO delete once subclasses have moved to onValidationStatus */
    protected void networkStatus(int status, String redirectUrl) {
    }


    /**
     * Called when the user asks to remember the choice to use this network even if unvalidated.
     * The transport is responsible for remembering the choice, and the next time the user connects
     * to the network, should explicitlySelected with {@code acceptUnvalidated} set to {@code true}.
     * This method will only be called if {@link #explicitlySelected} was called with
     * {@code acceptUnvalidated} set to {@code false}.
     * @param accept whether the user wants to use the network even if unvalidated.
     */
    public void onSaveAcceptUnvalidated(boolean accept) {
        saveAcceptUnvalidated(accept);
    }
    /** @hide TODO delete once subclasses have moved to onSaveAcceptUnvalidated */
    protected void saveAcceptUnvalidated(boolean accept) {
    }

    /**
     * Requests that the network hardware send the specified packet at the specified interval.
     *
     * @param slot the hardware slot on which to start the keepalive.
     * @param interval the interval between packets, between 10 and 3600. Note that this API
     *                 does not support sub-second precision and will round off the request.
     * @param packet the packet to send.
     */
    // seconds is from SocketKeepalive.MIN_INTERVAL_SEC to MAX_INTERVAL_SEC, but these should
    // not be exposed as constants because they may change in the future (API guideline 4.8)
    // and should have getters if exposed at all. Getters can't be used in the annotation,
    // so the values unfortunately need to be copied.
    public void onStartSocketKeepalive(int slot, @NonNull Duration interval,
            @NonNull KeepalivePacketData packet) {
        final long intervalSeconds = interval.getSeconds();
        if (intervalSeconds < SocketKeepalive.MIN_INTERVAL_SEC
                || intervalSeconds > SocketKeepalive.MAX_INTERVAL_SEC) {
            throw new IllegalArgumentException("Interval needs to be comprised between "
                    + SocketKeepalive.MIN_INTERVAL_SEC + " and " + SocketKeepalive.MAX_INTERVAL_SEC
                    + " but was " + intervalSeconds);
        }
        final Message msg = mHandler.obtainMessage(CMD_START_SOCKET_KEEPALIVE, slot,
                (int) intervalSeconds, packet);
        startSocketKeepalive(msg);
        msg.recycle();
    }
    /** @hide TODO delete once subclasses have moved to onStartSocketKeepalive */
    protected void startSocketKeepalive(Message msg) {
        onSocketKeepaliveEvent(msg.arg1, SocketKeepalive.ERROR_UNSUPPORTED);
    }

    /**
     * Requests that the network hardware stop a previously-started keepalive.
     *
     * @param slot the hardware slot on which to stop the keepalive.
     */
    public void onStopSocketKeepalive(int slot) {
        Message msg = mHandler.obtainMessage(CMD_STOP_SOCKET_KEEPALIVE, slot, 0, null);
        stopSocketKeepalive(msg);
        msg.recycle();
    }
    /** @hide TODO delete once subclasses have moved to onStopSocketKeepalive */
    protected void stopSocketKeepalive(Message msg) {
        onSocketKeepaliveEvent(msg.arg1, SocketKeepalive.ERROR_UNSUPPORTED);
    }

    /**
     * Must be called by the agent when a socket keepalive event occurs.
     *
     * @param slot the hardware slot on which the event occurred.
     * @param event the event that occurred, as one of the SocketKeepalive.ERROR_*
     *              or SocketKeepalive.SUCCESS constants.
     */
    public final void sendSocketKeepaliveEvent(int slot,
            @SocketKeepalive.KeepaliveEvent int event) {
        queueOrSendMessage(reg -> reg.sendSocketKeepaliveEvent(slot, event));
    }
    /** @hide TODO delete once callers have moved to sendSocketKeepaliveEvent */
    public void onSocketKeepaliveEvent(int slot, int reason) {
        sendSocketKeepaliveEvent(slot, reason);
    }

    /**
     * Called by ConnectivityService to add specific packet filter to network hardware to block
     * replies (e.g., TCP ACKs) matching the sent keepalive packets. Implementations that support
     * this feature must override this method.
     *
     * @param slot the hardware slot on which the keepalive should be sent.
     * @param packet the packet that is being sent.
     */
    public void onAddKeepalivePacketFilter(int slot, @NonNull KeepalivePacketData packet) {
        Message msg = mHandler.obtainMessage(CMD_ADD_KEEPALIVE_PACKET_FILTER, slot, 0, packet);
        addKeepalivePacketFilter(msg);
        msg.recycle();
    }
    /** @hide TODO delete once subclasses have moved to onAddKeepalivePacketFilter */
    protected void addKeepalivePacketFilter(Message msg) {
    }

    /**
     * Called by ConnectivityService to remove a packet filter installed with
     * {@link #addKeepalivePacketFilter(Message)}. Implementations that support this feature
     * must override this method.
     *
     * @param slot the hardware slot on which the keepalive is being sent.
     */
    public void onRemoveKeepalivePacketFilter(int slot) {
        Message msg = mHandler.obtainMessage(CMD_REMOVE_KEEPALIVE_PACKET_FILTER, slot, 0, null);
        removeKeepalivePacketFilter(msg);
        msg.recycle();
    }
    /** @hide TODO delete once subclasses have moved to onRemoveKeepalivePacketFilter */
    protected void removeKeepalivePacketFilter(Message msg) {
    }

    /**
     * Called by ConnectivityService to inform this network agent of signal strength thresholds
     * that when crossed should trigger a system wakeup and a NetworkCapabilities update.
     *
     * When the system updates the list of thresholds that should wake up the CPU for a
     * given agent it will call this method on the agent. The agent that implement this
     * should implement it in hardware so as to ensure the CPU will be woken up on breach.
     * Agents are expected to react to a breach by sending an updated NetworkCapabilities
     * object with the appropriate signal strength to sendNetworkCapabilities.
     *
     * The specific units are bearer-dependent. See details on the units and requests in
     * {@link NetworkCapabilities.Builder#setSignalStrength}.
     *
     * @param thresholds the array of thresholds that should trigger wakeups.
     */
    public void onSignalStrengthThresholdsUpdated(@NonNull int[] thresholds) {
        setSignalStrengthThresholds(thresholds);
    }
    /** @hide TODO delete once subclasses have moved to onSetSignalStrengthThresholds */
    protected void setSignalStrengthThresholds(int[] thresholds) {
    }

    /**
     * Called when the user asks to not stay connected to this network because it was found to not
     * provide Internet access.  Usually followed by call to {@code unwanted}.  The transport is
     * responsible for making sure the device does not automatically reconnect to the same network
     * after the {@code unwanted} call.
     */
    public void onAutomaticReconnectDisabled() {
        preventAutomaticReconnect();
    }
    /** @hide TODO delete once subclasses have moved to onAutomaticReconnectDisabled */
    protected void preventAutomaticReconnect() {
    }

    /**
     * Called when a qos callback is registered with a filter.
     * @param qosCallbackId the id for the callback registered
     * @param filter the filter being registered
     */
    public void onQosCallbackRegistered(final int qosCallbackId, final @NonNull QosFilter filter) {
    }

    /**
     * Called when a qos callback is registered with a filter.
     * <p/>
     * Any QoS events that are sent with the same callback id after this method is called
     * are a no-op.
     *
     * @param qosCallbackId the id for the callback being unregistered
     */
    public void onQosCallbackUnregistered(final int qosCallbackId) {
    }


    /**
     * Sends the attributes of Qos Session back to the Application
     *
     * @param qosCallbackId the callback id that the session belongs to
     * @param sessionId the unique session id across all Qos Sessions
     * @param attributes the attributes of the Qos Session
     */
    public final void sendQosSessionAvailable(final int qosCallbackId, final int sessionId,
            @NonNull final QosSessionAttributes attributes) {
        Objects.requireNonNull(attributes, "The attributes must be non-null");
        if (attributes instanceof EpsBearerQosSessionAttributes) {
            queueOrSendMessage(ra -> ra.sendEpsQosSessionAvailable(qosCallbackId,
                    new QosSession(sessionId, QosSession.TYPE_EPS_BEARER),
                    (EpsBearerQosSessionAttributes)attributes));
        } else if (attributes instanceof NrQosSessionAttributes) {
            queueOrSendMessage(ra -> ra.sendNrQosSessionAvailable(qosCallbackId,
                    new QosSession(sessionId, QosSession.TYPE_NR_BEARER),
                    (NrQosSessionAttributes)attributes));
        }
    }

    /**
     * Sends event that the Qos Session was lost.
     *
     * @param qosCallbackId the callback id that the session belongs to
     * @param sessionId the unique session id across all Qos Sessions
     * @param qosSessionType the session type {@code QosSesson#QosSessionType}
     */
    public final void sendQosSessionLost(final int qosCallbackId,
            final int sessionId, final int qosSessionType) {
        queueOrSendMessage(ra -> ra.sendQosSessionLost(qosCallbackId,
                new QosSession(sessionId, qosSessionType)));
    }

    /**
     * Sends the exception type back to the application.
     *
     * The NetworkAgent should not send anymore messages with this id.
     *
     * @param qosCallbackId the callback id this exception belongs to
     * @param exceptionType the type of exception
     */
    public final void sendQosCallbackError(final int qosCallbackId,
            @QosCallbackException.ExceptionType final int exceptionType) {
        queueOrSendMessage(ra -> ra.sendQosCallbackError(qosCallbackId, exceptionType));
    }


    /** @hide */
    protected void log(final String s) {
        Log.d(LOG_TAG, "NetworkAgent: " + s);
    }
}
