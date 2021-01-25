/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vcn;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import static com.android.server.VcnManagementService.VDBG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.IpSecManager;
import android.net.IpSecManager.IpSecTunnelInterface;
import android.net.IpSecManager.ResourceUnavailableException;
import android.net.IpSecTransform;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.RouteInfo;
import android.net.annotations.PolicyDirection;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.ChildSessionConfiguration;
import android.net.ipsec.ike.ChildSessionParams;
import android.net.ipsec.ike.IkeSession;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.IkeSessionConfiguration;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.exceptions.IkeException;
import android.net.ipsec.ike.exceptions.IkeProtocolException;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Message;
import android.os.ParcelUuid;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.vcn.UnderlyingNetworkTracker.UnderlyingNetworkRecord;
import com.android.server.vcn.UnderlyingNetworkTracker.UnderlyingNetworkTrackerCallback;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A single VCN Gateway Connection, providing a single public-facing VCN network.
 *
 * <p>This class handles mobility events, performs retries, and tracks safe-mode conditions.
 *
 * <pre>Internal state transitions are as follows:
 *
 * +----------------------------+                 +------------------------------+
 * |     DisconnectedState      |    Teardown or  |      DisconnectingState      |
 * |                            |<--no available--|                              |
 * |       Initial state.       |    underlying   | Transitive state for tearing |
 * +----------------------------+     networks    | tearing down an IKE session. |
 *               |                                +------------------------------+
 *               |                                         ^          |
 *       Underlying Network            Teardown requested  |   Not tearing down
 *            changed               +--or retriable error--+  and has available
 *               |                  |      occurred           underlying network
 *               |                  ^                                 |
 *               v                  |                                 v
 * +----------------------------+   |             +------------------------------+
 * |      ConnectingState       |<----------------|      RetryTimeoutState       |
 * |                            |   |             |                              |
 * |    Transitive state for    |   |             |     Transitive state for     |
 * |  starting IKE negotiation. |---+             |  handling retriable errors.  |
 * +----------------------------+   |             +------------------------------+
 *               |                  |
 *          IKE session             |
 *           negotiated             |
 *               |                  |
 *               v                  |
 * +----------------------------+   ^
 * |      ConnectedState        |   |
 * |                            |   |
 * |     Stable state where     |   |
 * |  gateway connection is set |   |
 * | up, and Android Network is |   |
 * |         connected.         |---+
 * +----------------------------+
 * </pre>
 *
 * @hide
 */
public class VcnGatewayConnection extends StateMachine {
    private static final String TAG = VcnGatewayConnection.class.getSimpleName();

    private static final InetAddress DUMMY_ADDR = InetAddresses.parseNumericAddress("192.0.2.0");
    private static final int ARG_NOT_PRESENT = Integer.MIN_VALUE;

    private static final String DISCONNECT_REASON_INTERNAL_ERROR = "Uncaught exception: ";
    private static final String DISCONNECT_REASON_UNDERLYING_NETWORK_LOST =
            "Underlying Network lost";
    private static final String DISCONNECT_REASON_TEARDOWN = "teardown() called on VcnTunnel";
    private static final int TOKEN_ALL = Integer.MIN_VALUE;

    private static final int NETWORK_LOSS_DISCONNECT_TIMEOUT_SECONDS = 30;
    private static final int TEARDOWN_TIMEOUT_SECONDS = 5;

    private interface EventInfo {}

    /**
     * Sent when there are changes to the underlying network (per the UnderlyingNetworkTracker).
     *
     * <p>May indicate an entirely new underlying network, OR a change in network properties.
     *
     * <p>Relevant in ALL states.
     *
     * <p>In the Connected state, this MAY indicate a mobility even occurred.
     *
     * @param arg1 The "all" token; this event is always applicable.
     * @param obj @NonNull An EventUnderlyingNetworkChangedInfo instance with relevant data.
     */
    private static final int EVENT_UNDERLYING_NETWORK_CHANGED = 1;

    private static class EventUnderlyingNetworkChangedInfo implements EventInfo {
        @Nullable public final UnderlyingNetworkRecord newUnderlying;

        EventUnderlyingNetworkChangedInfo(@Nullable UnderlyingNetworkRecord newUnderlying) {
            this.newUnderlying = newUnderlying;
        }

        @Override
        public int hashCode() {
            return Objects.hash(newUnderlying);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof EventUnderlyingNetworkChangedInfo)) {
                return false;
            }

            final EventUnderlyingNetworkChangedInfo rhs = (EventUnderlyingNetworkChangedInfo) other;
            return Objects.equals(newUnderlying, rhs.newUnderlying);
        }
    }

    /**
     * Sent (delayed) to trigger an attempt to reestablish the tunnel.
     *
     * <p>Only relevant in the Retry-timeout state, discarded in all other states.
     *
     * <p>Upon receipt of this signal, the state machine will transition from the Retry-timeout
     * state to the Connecting state.
     *
     * @param arg1 The "all" token; no sessions are active in the RetryTimeoutState.
     */
    private static final int EVENT_RETRY_TIMEOUT_EXPIRED = 2;

    /**
     * Sent when a gateway connection has been lost, either due to a IKE or child failure.
     *
     * <p>Relevant in all states that have an IKE session.
     *
     * <p>Upon receipt of this signal, the state machine will (unless loss of the session is
     * expected) transition to the Disconnecting state, to ensure IKE session closure before
     * retrying, or fully shutting down.
     *
     * @param arg1 The session token for the IKE Session that was lost, used to prevent out-of-date
     *     signals from propagating.
     * @param obj @NonNull An EventSessionLostInfo instance with relevant data.
     */
    private static final int EVENT_SESSION_LOST = 3;

    private static class EventSessionLostInfo implements EventInfo {
        @Nullable public final Exception exception;

        EventSessionLostInfo(@NonNull Exception exception) {
            this.exception = exception;
        }

        @Override
        public int hashCode() {
            return Objects.hash(exception);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof EventSessionLostInfo)) {
                return false;
            }

            final EventSessionLostInfo rhs = (EventSessionLostInfo) other;
            return Objects.equals(exception, rhs.exception);
        }
    }

    /**
     * Sent when an IKE session has completely closed.
     *
     * <p>Relevant only in the Disconnecting State, used to identify that a session being torn down
     * was fully closed. If this event is not fired within a timely fashion, the IKE session will be
     * forcibly terminated.
     *
     * <p>Upon receipt of this signal, the state machine will (unless closure of the session is
     * expected) transition to the Disconnected or RetryTimeout states, depending on whether the
     * GatewayConnection is being fully torn down.
     *
     * @param arg1 The session token for the IKE Session that was lost, used to prevent out-of-date
     *     signals from propagating.
     * @param obj @NonNull An EventSessionLostInfo instance with relevant data.
     */
    private static final int EVENT_SESSION_CLOSED = 4;

    /**
     * Sent when an IKE Child Transform was created, and should be applied to the tunnel.
     *
     * <p>Only relevant in the Connecting, Connected and Migrating states. This callback MUST be
     * handled in the Connected or Migrating states, and should be deferred if necessary.
     *
     * @param arg1 The session token for the IKE Session that had a new child created, used to
     *     prevent out-of-date signals from propagating.
     * @param obj @NonNull An EventTransformCreatedInfo instance with relevant data.
     */
    private static final int EVENT_TRANSFORM_CREATED = 5;

    private static class EventTransformCreatedInfo implements EventInfo {
        @PolicyDirection public final int direction;
        @NonNull public final IpSecTransform transform;

        EventTransformCreatedInfo(
                @PolicyDirection int direction, @NonNull IpSecTransform transform) {
            this.direction = direction;
            this.transform = Objects.requireNonNull(transform);
        }

        @Override
        public int hashCode() {
            return Objects.hash(direction, transform);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof EventTransformCreatedInfo)) {
                return false;
            }

            final EventTransformCreatedInfo rhs = (EventTransformCreatedInfo) other;
            return direction == rhs.direction && Objects.equals(transform, rhs.transform);
        }
    }

    /**
     * Sent when an IKE Child Session was completely opened and configured successfully.
     *
     * <p>Only relevant in the Connected and Migrating states.
     *
     * @param arg1 The session token for the IKE Session for which a child was opened and configured
     *     successfully, used to prevent out-of-date signals from propagating.
     * @param obj @NonNull An EventSetupCompletedInfo instance with relevant data.
     */
    private static final int EVENT_SETUP_COMPLETED = 6;

    private static class EventSetupCompletedInfo implements EventInfo {
        @NonNull public final ChildSessionConfiguration childSessionConfig;

        EventSetupCompletedInfo(@NonNull ChildSessionConfiguration childSessionConfig) {
            this.childSessionConfig = Objects.requireNonNull(childSessionConfig);
        }

        @Override
        public int hashCode() {
            return Objects.hash(childSessionConfig);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof EventSetupCompletedInfo)) {
                return false;
            }

            final EventSetupCompletedInfo rhs = (EventSetupCompletedInfo) other;
            return Objects.equals(childSessionConfig, rhs.childSessionConfig);
        }
    }

    /**
     * Sent when conditions (internal or external) require a disconnect.
     *
     * <p>Relevant in all states except the Disconnected state.
     *
     * <p>This signal is often fired with a timeout in order to prevent disconnecting during
     * transient conditions, such as network switches. Upon the transient passing, the signal is
     * canceled based on the disconnect reason.
     *
     * <p>Upon receipt of this signal, the state machine MUST tear down all active sessions, cancel
     * any pending work items, and move to the Disconnected state.
     *
     * @param arg1 The "all" token; this signal is always honored.
     * @param obj @NonNull An EventDisconnectRequestedInfo instance with relevant data.
     */
    private static final int EVENT_DISCONNECT_REQUESTED = 7;

    private static class EventDisconnectRequestedInfo implements EventInfo {
        /** The reason why the disconnect was requested. */
        @NonNull public final String reason;

        EventDisconnectRequestedInfo(@NonNull String reason) {
            this.reason = Objects.requireNonNull(reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reason);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof EventDisconnectRequestedInfo)) {
                return false;
            }

            final EventDisconnectRequestedInfo rhs = (EventDisconnectRequestedInfo) other;
            return reason.equals(rhs.reason);
        }
    }

    /**
     * Sent (delayed) to trigger a forcible close of an IKE session.
     *
     * <p>Only relevant in the Disconnecting state, discarded in all other states.
     *
     * <p>Upon receipt of this signal, the state machine will transition from the Disconnecting
     * state to the Disconnected state.
     *
     * @param arg1 The session token for the IKE Session that is being torn down, used to prevent
     *     out-of-date signals from propagating.
     */
    private static final int EVENT_TEARDOWN_TIMEOUT_EXPIRED = 8;

    @NonNull private final DisconnectedState mDisconnectedState = new DisconnectedState();
    @NonNull private final DisconnectingState mDisconnectingState = new DisconnectingState();
    @NonNull private final ConnectingState mConnectingState = new ConnectingState();
    @NonNull private final ConnectedState mConnectedState = new ConnectedState();
    @NonNull private final RetryTimeoutState mRetryTimeoutState = new RetryTimeoutState();

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final ParcelUuid mSubscriptionGroup;
    @NonNull private final UnderlyingNetworkTracker mUnderlyingNetworkTracker;
    @NonNull private final VcnGatewayConnectionConfig mConnectionConfig;
    @NonNull private final Dependencies mDeps;

    @NonNull private final VcnUnderlyingNetworkTrackerCallback mUnderlyingNetworkTrackerCallback;

    @NonNull private final IpSecManager mIpSecManager;
    @NonNull private final IpSecTunnelInterface mTunnelIface;

    /** Running state of this VcnGatewayConnection. */
    private boolean mIsRunning = true;

    /**
     * The token used by the primary/current/active session.
     *
     * <p>This token MUST be updated when a new stateful/async session becomes the
     * primary/current/active session. Example cases where the session changes are:
     *
     * <ul>
     *   <li>Switching to an IKE session as the primary session
     * </ul>
     *
     * <p>In the migrating state, where two sessions may be active, this value MUST represent the
     * primary session. This is USUALLY the existing session, and is only switched to the new
     * session when:
     *
     * <ul>
     *   <li>The new session connects successfully, and becomes the primary session
     *   <li>The existing session is lost, and the remaining (new) session becomes the primary
     *       session
     * </ul>
     */
    private int mCurrentToken = -1;

    /**
     * The next usable token.
     *
     * <p>A new token MUST be used for all new IKE sessions.
     */
    private int mNextToken = 0;

    /**
     * The number of unsuccessful attempts since the last successful connection.
     *
     * <p>This number MUST be incremented each time the RetryTimeout state is entered, and cleared
     * each time the Connected state is entered.
     */
    private int mFailedAttempts = 0;

    /**
     * The current underlying network.
     *
     * <p>Set in any states, always @NonNull in all states except Disconnected, null otherwise.
     */
    private UnderlyingNetworkRecord mUnderlying;

    /**
     * The active IKE session.
     *
     * <p>Set in Connecting or Migrating States, always @NonNull in Connecting, Connected, and
     * Migrating states, null otherwise.
     */
    private IkeSession mIkeSession;

    /**
     * The last known child configuration.
     *
     * <p>Set in Connected and Migrating states, always @NonNull in Connected, Migrating
     * states, @Nullable otherwise.
     */
    private ChildSessionConfiguration mChildConfig;

    /**
     * The active network agent.
     *
     * <p>Set in Connected state, always @NonNull in Connected, Migrating states, @Nullable
     * otherwise.
     */
    private NetworkAgent mNetworkAgent;

    public VcnGatewayConnection(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnGatewayConnectionConfig connectionConfig) {
        this(vcnContext, subscriptionGroup, connectionConfig, new Dependencies());
    }

    private VcnGatewayConnection(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnGatewayConnectionConfig connectionConfig,
            @NonNull Dependencies deps) {
        super(TAG, Objects.requireNonNull(vcnContext, "Missing vcnContext").getLooper());
        mVcnContext = vcnContext;
        mSubscriptionGroup = Objects.requireNonNull(subscriptionGroup, "Missing subscriptionGroup");
        mConnectionConfig = Objects.requireNonNull(connectionConfig, "Missing connectionConfig");
        mDeps = Objects.requireNonNull(deps, "Missing deps");

        mUnderlyingNetworkTrackerCallback = new VcnUnderlyingNetworkTrackerCallback();

        mUnderlyingNetworkTracker =
                mDeps.newUnderlyingNetworkTracker(
                        mVcnContext, subscriptionGroup, mUnderlyingNetworkTrackerCallback);
        mIpSecManager = mVcnContext.getContext().getSystemService(IpSecManager.class);

        IpSecTunnelInterface iface;
        try {
            iface =
                    mIpSecManager.createIpSecTunnelInterface(
                            DUMMY_ADDR, DUMMY_ADDR, new Network(-1));
        } catch (IOException | ResourceUnavailableException e) {
            teardownAsynchronously();
            mTunnelIface = null;

            return;
        }

        mTunnelIface = iface;

        addState(mDisconnectedState);
        addState(mDisconnectingState);
        addState(mConnectingState);
        addState(mConnectedState);
        addState(mRetryTimeoutState);

        setInitialState(mDisconnectedState);
        setDbg(VDBG);
        start();
    }

    /**
     * Asynchronously tears down this GatewayConnection, and any resources used.
     *
     * <p>Once torn down, this VcnTunnel CANNOT be started again.
     */
    public void teardownAsynchronously() {
        sendMessage(
                EVENT_DISCONNECT_REQUESTED,
                TOKEN_ALL,
                new EventDisconnectRequestedInfo(DISCONNECT_REASON_TEARDOWN));
        quit();

        // TODO: Notify VcnInstance (via callbacks) of permanent teardown of this tunnel, since this
        // is also called asynchronously when a NetworkAgent becomes unwanted
    }

    @Override
    protected void onQuitting() {
        // No need to call setInterfaceDown(); the IpSecInterface is being fully torn down.
        if (mTunnelIface != null) {
            mTunnelIface.close();
        }

        mUnderlyingNetworkTracker.teardown();
    }

    private class VcnUnderlyingNetworkTrackerCallback implements UnderlyingNetworkTrackerCallback {
        @Override
        public void onSelectedUnderlyingNetworkChanged(
                @Nullable UnderlyingNetworkRecord underlying) {
            // If underlying is null, all underlying networks have been lost. Disconnect VCN after a
            // timeout.
            if (underlying == null) {
                sendMessageDelayed(
                        EVENT_DISCONNECT_REQUESTED,
                        TOKEN_ALL,
                        new EventDisconnectRequestedInfo(DISCONNECT_REASON_UNDERLYING_NETWORK_LOST),
                        TimeUnit.SECONDS.toMillis(NETWORK_LOSS_DISCONNECT_TIMEOUT_SECONDS));
            } else if (getHandler() != null) {
                // Cancel any existing disconnect due to loss of underlying network
                // getHandler() can return null if the state machine has already quit. Since this is
                // called from other classes, this condition must be verified.

                getHandler()
                        .removeEqualMessages(
                                EVENT_DISCONNECT_REQUESTED,
                                new EventDisconnectRequestedInfo(
                                        DISCONNECT_REASON_UNDERLYING_NETWORK_LOST));
            }

            sendMessage(
                    EVENT_UNDERLYING_NETWORK_CHANGED,
                    TOKEN_ALL,
                    new EventUnderlyingNetworkChangedInfo(underlying));
        }
    }

    private void sendMessage(int what, int token, EventInfo data) {
        super.sendMessage(what, token, ARG_NOT_PRESENT, data);
    }

    private void sendMessage(int what, int token, int arg2, EventInfo data) {
        super.sendMessage(what, token, arg2, data);
    }

    private void sendMessageDelayed(int what, int token, EventInfo data, long timeout) {
        super.sendMessageDelayed(what, token, ARG_NOT_PRESENT, data, timeout);
    }

    private void sendMessageDelayed(int what, int token, int arg2, EventInfo data, long timeout) {
        super.sendMessageDelayed(what, token, arg2, data, timeout);
    }

    private void sessionLost(int token, @Nullable Exception exception) {
        sendMessage(EVENT_SESSION_LOST, token, new EventSessionLostInfo(exception));
    }

    private void sessionClosed(int token, @Nullable Exception exception) {
        // SESSION_LOST MUST be sent before SESSION_CLOSED to ensure that the SM moves to the
        // Disconnecting state.
        sessionLost(token, exception);
        sendMessage(EVENT_SESSION_CLOSED, token);
    }

    private void childTransformCreated(
            int token, @NonNull IpSecTransform transform, int direction) {
        sendMessage(
                EVENT_TRANSFORM_CREATED,
                token,
                new EventTransformCreatedInfo(direction, transform));
    }

    private void childOpened(int token, @NonNull ChildSessionConfiguration childConfig) {
        sendMessage(EVENT_SETUP_COMPLETED, token, new EventSetupCompletedInfo(childConfig));
    }

    private abstract class BaseState extends State {
        @Override
        public void enter() {
            try {
                enterState();
            } catch (Exception e) {
                Slog.wtf(TAG, "Uncaught exception", e);
                sendMessage(
                        EVENT_DISCONNECT_REQUESTED,
                        TOKEN_ALL,
                        new EventDisconnectRequestedInfo(
                                DISCONNECT_REASON_INTERNAL_ERROR + e.toString()));
            }
        }

        protected void enterState() throws Exception {}

        /**
         * Top-level processMessage with safeguards to prevent crashing the System Server on non-eng
         * builds.
         */
        @Override
        public boolean processMessage(Message msg) {
            try {
                processStateMsg(msg);
            } catch (Exception e) {
                Slog.wtf(TAG, "Uncaught exception", e);
                sendMessage(
                        EVENT_DISCONNECT_REQUESTED,
                        TOKEN_ALL,
                        new EventDisconnectRequestedInfo(
                                DISCONNECT_REASON_INTERNAL_ERROR + e.toString()));
            }

            return HANDLED;
        }

        protected abstract void processStateMsg(Message msg) throws Exception;

        protected void logUnhandledMessage(Message msg) {
            // Log as unexpected all known messages, and log all else as unknown.
            switch (msg.what) {
                case EVENT_UNDERLYING_NETWORK_CHANGED: // Fallthrough
                case EVENT_RETRY_TIMEOUT_EXPIRED: // Fallthrough
                case EVENT_SESSION_LOST: // Fallthrough
                case EVENT_SESSION_CLOSED: // Fallthrough
                case EVENT_TRANSFORM_CREATED: // Fallthrough
                case EVENT_SETUP_COMPLETED: // Fallthrough
                case EVENT_DISCONNECT_REQUESTED: // Fallthrough
                case EVENT_TEARDOWN_TIMEOUT_EXPIRED:
                    logUnexpectedEvent(msg.what);
                    break;
                default:
                    logWtfUnknownEvent(msg.what);
                    break;
            }
        }

        protected void teardownNetwork() {
            if (mNetworkAgent != null) {
                mNetworkAgent.sendNetworkInfo(buildNetworkInfo(false /* isConnected */));
                mNetworkAgent = null;
            }
        }

        protected void teardownIke() {
            if (mIkeSession != null) {
                mIkeSession.close();
            }
        }

        protected void handleDisconnectRequested(String msg) {
            Slog.v(TAG, "Tearing down. Cause: " + msg);
            teardownNetwork();
            teardownIke();

            if (mIkeSession == null) {
                // Already disconnected, go straight to DisconnectedState
                transitionTo(mDisconnectedState);
            } else {
                // Still need to wait for full closure
                transitionTo(mDisconnectingState);
            }
        }

        protected void logUnexpectedEvent(int what) {
            Slog.d(TAG, String.format(
                    "Unexpected event code %d in state %s", what, this.getClass().getSimpleName()));
        }

        protected void logWtfUnknownEvent(int what) {
            Slog.wtf(TAG, String.format(
                    "Unknown event code %d in state %s", what, this.getClass().getSimpleName()));
        }
    }

    /**
     * State representing the a disconnected VCN tunnel.
     *
     * <p>This is also is the initial state.
     */
    private class DisconnectedState extends BaseState {
        @Override
        protected void processStateMsg(Message msg) {}
    }

    private abstract class ActiveBaseState extends BaseState {
        /**
         * Handles all incoming messages, discarding messages for previous networks.
         *
         * <p>States that handle mobility events may need to override this method to receive
         * messages for all underlying networks.
         */
        @Override
        public boolean processMessage(Message msg) {
            final int token = msg.arg1;
            // Only process if a valid token is presented.
            if (isValidToken(token)) {
                return super.processMessage(msg);
            }

            Slog.v(TAG, "Message called with obsolete token: " + token + "; what: " + msg.what);
            return HANDLED;
        }

        protected boolean isValidToken(int token) {
            return (token == TOKEN_ALL || token == mCurrentToken);
        }
    }

    /**
     * Transitive state representing a VCN that is tearing down an IKE session.
     *
     * <p>In this state, the IKE session is in the process of being torn down. If the IKE session
     * does not complete teardown in a timely fashion, it will be killed (forcibly closed).
     */
    private class DisconnectingState extends ActiveBaseState {
        @Override
        protected void processStateMsg(Message msg) {}
    }

    /**
     * Transitive state representing a VCN that is making an primary (non-handover) connection.
     *
     * <p>This state starts IKE negotiation, but defers transform application & network setup to the
     * Connected state.
     */
    private class ConnectingState extends ActiveBaseState {
        @Override
        protected void processStateMsg(Message msg) {}
    }

    private abstract class ConnectedStateBase extends ActiveBaseState {}

    /**
     * Stable state representing a VCN that has a functioning connection to the mobility anchor.
     *
     * <p>This state handles IPsec transform application (initial and rekey), NetworkAgent setup,
     * and monitors for mobility events.
     */
    class ConnectedState extends ConnectedStateBase {
        @Override
        protected void processStateMsg(Message msg) {}
    }

    /**
     * Transitive state representing a VCN that failed to establish a connection, and will retry.
     *
     * <p>This state will be exited upon a new underlying network being found, or timeout expiry.
     */
    class RetryTimeoutState extends ActiveBaseState {
        @Override
        protected void processStateMsg(Message msg) {}
    }

    // TODO: Remove this when migrating to new NetworkAgent API
    private static NetworkInfo buildNetworkInfo(boolean isConnected) {
        NetworkInfo info =
                new NetworkInfo(
                        ConnectivityManager.TYPE_MOBILE,
                        TelephonyManager.NETWORK_TYPE_UNKNOWN,
                        "MOBILE",
                        "VCN");
        info.setDetailedState(
                isConnected ? DetailedState.CONNECTED : DetailedState.DISCONNECTED, null, null);

        return info;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static NetworkCapabilities buildNetworkCapabilities(
            @NonNull VcnGatewayConnectionConfig gatewayConnectionConfig) {
        final NetworkCapabilities caps = new NetworkCapabilities();

        caps.addTransportType(TRANSPORT_CELLULAR);
        caps.addCapability(NET_CAPABILITY_NOT_CONGESTED);
        caps.addCapability(NET_CAPABILITY_NOT_SUSPENDED);

        // Add exposed capabilities
        for (int cap : gatewayConnectionConfig.getAllExposedCapabilities()) {
            caps.addCapability(cap);
        }

        return caps;
    }

    private static LinkProperties buildConnectedLinkProperties(
            @NonNull VcnGatewayConnectionConfig gatewayConnectionConfig,
            @NonNull IpSecTunnelInterface tunnelIface,
            @NonNull ChildSessionConfiguration childConfig) {
        final LinkProperties lp = new LinkProperties();

        lp.setInterfaceName(tunnelIface.getInterfaceName());
        for (LinkAddress addr : childConfig.getInternalAddresses()) {
            lp.addLinkAddress(addr);
        }
        for (InetAddress addr : childConfig.getInternalDnsServers()) {
            lp.addDnsServer(addr);
        }

        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null));
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null));

        lp.setMtu(gatewayConnectionConfig.getMaxMtu());

        return lp;
    }

    private class IkeSessionCallbackImpl implements IkeSessionCallback {
        private final int mToken;

        IkeSessionCallbackImpl(int token) {
            mToken = token;
        }

        @Override
        public void onOpened(@NonNull IkeSessionConfiguration ikeSessionConfig) {
            Slog.v(TAG, "IkeOpened for token " + mToken);
            // Nothing to do here.
        }

        @Override
        public void onClosed() {
            Slog.v(TAG, "IkeClosed for token " + mToken);
            sessionClosed(mToken, null);
        }

        @Override
        public void onClosedExceptionally(@NonNull IkeException exception) {
            Slog.v(TAG, "IkeClosedExceptionally for token " + mToken, exception);
            sessionClosed(mToken, exception);
        }

        @Override
        public void onError(@NonNull IkeProtocolException exception) {
            Slog.v(TAG, "IkeError for token " + mToken, exception);
            // Non-fatal, log and continue.
        }
    }

    private class ChildSessionCallbackImpl implements ChildSessionCallback {
        private final int mToken;

        ChildSessionCallbackImpl(int token) {
            mToken = token;
        }

        @Override
        public void onOpened(@NonNull ChildSessionConfiguration childConfig) {
            Slog.v(TAG, "ChildOpened for token " + mToken);
            childOpened(mToken, childConfig);
        }

        @Override
        public void onClosed() {
            Slog.v(TAG, "ChildClosed for token " + mToken);
            sessionLost(mToken, null);
        }

        @Override
        public void onClosedExceptionally(@NonNull IkeException exception) {
            Slog.v(TAG, "ChildClosedExceptionally for token " + mToken, exception);
            sessionLost(mToken, exception);
        }

        @Override
        public void onIpSecTransformCreated(@NonNull IpSecTransform transform, int direction) {
            Slog.v(TAG, "ChildTransformCreated; Direction: " + direction + "; token " + mToken);
            childTransformCreated(mToken, transform, direction);
        }

        @Override
        public void onIpSecTransformDeleted(@NonNull IpSecTransform transform, int direction) {
            // Nothing to be done; no references to the IpSecTransform are held, and this transform
            // will be closed by the IKE library.
            Slog.v(TAG, "ChildTransformDeleted; Direction: " + direction + "; for token " + mToken);
        }
    }

    /** External dependencies used by VcnGatewayConnection, for injection in tests. */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        /** Builds a new UnderlyingNetworkTracker. */
        public UnderlyingNetworkTracker newUnderlyingNetworkTracker(
                VcnContext vcnContext,
                ParcelUuid subscriptionGroup,
                UnderlyingNetworkTrackerCallback callback) {
            return new UnderlyingNetworkTracker(vcnContext, subscriptionGroup, callback);
        }

        /** Builds a new IkeSession. */
        public IkeSession newIkeSession(
                VcnContext vcnContext,
                IkeSessionParams ikeSessionParams,
                ChildSessionParams childSessionParams,
                IkeSessionCallback ikeSessionCallback,
                ChildSessionCallback childSessionCallback) {
            return new IkeSession(
                    vcnContext.getContext(),
                    ikeSessionParams,
                    childSessionParams,
                    new HandlerExecutor(new Handler(vcnContext.getLooper())),
                    ikeSessionCallback,
                    childSessionCallback);
        }
    }
}
