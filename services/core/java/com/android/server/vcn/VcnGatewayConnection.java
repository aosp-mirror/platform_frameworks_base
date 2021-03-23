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
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.vcn.VcnManager.VCN_ERROR_CODE_CONFIG_ERROR;
import static android.net.vcn.VcnManager.VCN_ERROR_CODE_INTERNAL_ERROR;
import static android.net.vcn.VcnManager.VCN_ERROR_CODE_NETWORK_ERROR;

import static com.android.server.VcnManagementService.VDBG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
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
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.RouteInfo;
import android.net.TelephonyNetworkSpecifier;
import android.net.Uri;
import android.net.annotations.PolicyDirection;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.ChildSessionConfiguration;
import android.net.ipsec.ike.ChildSessionParams;
import android.net.ipsec.ike.IkeSession;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.IkeSessionConfiguration;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.exceptions.AuthenticationFailedException;
import android.net.ipsec.ike.exceptions.IkeException;
import android.net.ipsec.ike.exceptions.IkeInternalException;
import android.net.ipsec.ike.exceptions.IkeProtocolException;
import android.net.vcn.VcnControlPlaneIkeConfig;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnTransportInfo;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.UnderlyingNetworkTracker.UnderlyingNetworkRecord;
import com.android.server.vcn.UnderlyingNetworkTracker.UnderlyingNetworkTrackerCallback;
import com.android.server.vcn.Vcn.VcnGatewayStatusCallback;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
 * <p>All messages in VcnGatewayConnection <b>should</b> be enqueued using {@link
 * #sendMessageAndAcquireWakeLock}. Careful consideration should be given to any uses of {@link
 * #sendMessage} directly, as they are not guaranteed to be processed in a timely manner (due to the
 * lack of WakeLocks).
 *
 * <p>Any attempt to remove messages from the Handler should be done using {@link
 * #removeEqualMessages}. This is necessary to ensure that the WakeLock is correctly released when
 * no messages remain in the Handler queue.
 *
 * @hide
 */
public class VcnGatewayConnection extends StateMachine {
    private static final String TAG = VcnGatewayConnection.class.getSimpleName();

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final InetAddress DUMMY_ADDR = InetAddresses.parseNumericAddress("192.0.2.0");

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final String TEARDOWN_TIMEOUT_ALARM = TAG + "_TEARDOWN_TIMEOUT_ALARM";

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final String DISCONNECT_REQUEST_ALARM = TAG + "_DISCONNECT_REQUEST_ALARM";

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final String RETRY_TIMEOUT_ALARM = TAG + "_RETRY_TIMEOUT_ALARM";

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final String SAFEMODE_TIMEOUT_ALARM = TAG + "_SAFEMODE_TIMEOUT_ALARM";

    private static final int[] MERGED_CAPABILITIES =
            new int[] {NET_CAPABILITY_NOT_METERED, NET_CAPABILITY_NOT_ROAMING};
    private static final int ARG_NOT_PRESENT = Integer.MIN_VALUE;

    private static final String DISCONNECT_REASON_INTERNAL_ERROR = "Uncaught exception: ";
    private static final String DISCONNECT_REASON_UNDERLYING_NETWORK_LOST =
            "Underlying Network lost";
    private static final String DISCONNECT_REASON_NETWORK_AGENT_UNWANTED =
            "NetworkAgent was unwanted";
    private static final String DISCONNECT_REASON_TEARDOWN = "teardown() called on VcnTunnel";
    private static final int TOKEN_ALL = Integer.MIN_VALUE;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int NETWORK_LOSS_DISCONNECT_TIMEOUT_SECONDS = 30;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int TEARDOWN_TIMEOUT_SECONDS = 5;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int SAFEMODE_TIMEOUT_SECONDS = 30;

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
        @NonNull public final VcnChildSessionConfiguration childSessionConfig;

        EventSetupCompletedInfo(@NonNull VcnChildSessionConfiguration childSessionConfig) {
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

        public final boolean shouldQuit;

        EventDisconnectRequestedInfo(@NonNull String reason, boolean shouldQuit) {
            this.reason = Objects.requireNonNull(reason);
            this.shouldQuit = shouldQuit;
        }

        @Override
        public int hashCode() {
            return Objects.hash(reason, shouldQuit);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof EventDisconnectRequestedInfo)) {
                return false;
            }

            final EventDisconnectRequestedInfo rhs = (EventDisconnectRequestedInfo) other;
            return reason.equals(rhs.reason) && shouldQuit == rhs.shouldQuit;
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

    /**
     * Sent when this VcnGatewayConnection is notified of a change in TelephonySubscriptions.
     *
     * <p>Relevant in all states.
     *
     * @param arg1 The "all" token; this signal is always honored.
     */
    // TODO(b/178426520): implement handling of this event
    private static final int EVENT_SUBSCRIPTIONS_CHANGED = 9;

    /**
     * Sent when this VcnGatewayConnection has entered safe mode.
     *
     * <p>A VcnGatewayConnection enters safe mode when it takes over {@link
     * #SAFEMODE_TIMEOUT_SECONDS} to enter {@link ConnectedState}.
     *
     * <p>When a VcnGatewayConnection enters safe mode, it will fire {@link
     * VcnGatewayStatusCallback#onEnteredSafeMode()} to notify its Vcn. The Vcn will then shut down
     * its VcnGatewayConnectin(s).
     *
     * <p>Relevant in DisconnectingState, ConnectingState, ConnectedState (if the Vcn Network is not
     * validated yet), and RetryTimeoutState.
     *
     * @param arg1 The "all" token; this signal is always honored.
     */
    private static final int EVENT_SAFE_MODE_TIMEOUT_EXCEEDED = 10;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @NonNull
    final DisconnectedState mDisconnectedState = new DisconnectedState();

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @NonNull
    final DisconnectingState mDisconnectingState = new DisconnectingState();

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @NonNull
    final ConnectingState mConnectingState = new ConnectingState();

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @NonNull
    final ConnectedState mConnectedState = new ConnectedState();

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @NonNull
    final RetryTimeoutState mRetryTimeoutState = new RetryTimeoutState();

    @NonNull private TelephonySubscriptionSnapshot mLastSnapshot;

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final ParcelUuid mSubscriptionGroup;
    @NonNull private final UnderlyingNetworkTracker mUnderlyingNetworkTracker;
    @NonNull private final VcnGatewayConnectionConfig mConnectionConfig;
    @NonNull private final VcnGatewayStatusCallback mGatewayStatusCallback;
    @NonNull private final Dependencies mDeps;
    @NonNull private final VcnUnderlyingNetworkTrackerCallback mUnderlyingNetworkTrackerCallback;

    @NonNull private final IpSecManager mIpSecManager;

    @Nullable private IpSecTunnelInterface mTunnelIface = null;

    /**
     * WakeLock to be held when processing messages on the Handler queue.
     *
     * <p>Used to prevent the device from going to sleep while there are VCN-related events to
     * process for this VcnGatewayConnection.
     *
     * <p>Obtain a WakeLock when enquing messages onto the Handler queue. Once all messages in the
     * Handler queue have been processed, the WakeLock can be released and cleared.
     *
     * <p>This WakeLock is also used for handling delayed messages by using WakeupMessages to send
     * delayed messages to the Handler. When the WakeupMessage fires, it will obtain the WakeLock
     * before enquing the delayed event to the Handler.
     */
    @NonNull private final VcnWakeLock mWakeLock;

    /**
     * Whether the VcnGatewayConnection is in the process of irreversibly quitting.
     *
     * <p>This variable is false for the lifecycle of the VcnGatewayConnection, until a command to
     * teardown has been received. This may be flipped due to events such as the Network becoming
     * unwanted, the owning VCN entering safe mode, or an irrecoverable internal failure.
     */
    private boolean mIsQuitting = false;

    /**
     * Whether the VcnGatewayConnection is in safe mode.
     *
     * <p>Upon hitting the safe mode timeout, this will be set to {@code true}. In safe mode, this
     * VcnGatewayConnection will continue attempting to connect, and if a successful connection is
     * made, safe mode will be exited.
     */
    private boolean mIsInSafeMode = false;

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
    private VcnIkeSession mIkeSession;

    /**
     * The last known child configuration.
     *
     * <p>Set in Connected and Migrating states, always @NonNull in Connected, Migrating
     * states, @Nullable otherwise.
     */
    private VcnChildSessionConfiguration mChildConfig;

    /**
     * The active network agent.
     *
     * <p>Set in Connected state, always @NonNull in Connected, Migrating states, @Nullable
     * otherwise.
     */
    private NetworkAgent mNetworkAgent;

    @Nullable private WakeupMessage mTeardownTimeoutAlarm;
    @Nullable private WakeupMessage mDisconnectRequestAlarm;
    @Nullable private WakeupMessage mRetryTimeoutAlarm;
    @Nullable private WakeupMessage mSafeModeTimeoutAlarm;

    public VcnGatewayConnection(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            @NonNull VcnGatewayConnectionConfig connectionConfig,
            @NonNull VcnGatewayStatusCallback gatewayStatusCallback) {
        this(
                vcnContext,
                subscriptionGroup,
                snapshot,
                connectionConfig,
                gatewayStatusCallback,
                new Dependencies());
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    VcnGatewayConnection(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            @NonNull VcnGatewayConnectionConfig connectionConfig,
            @NonNull VcnGatewayStatusCallback gatewayStatusCallback,
            @NonNull Dependencies deps) {
        super(TAG, Objects.requireNonNull(vcnContext, "Missing vcnContext").getLooper());
        mVcnContext = vcnContext;
        mSubscriptionGroup = Objects.requireNonNull(subscriptionGroup, "Missing subscriptionGroup");
        mConnectionConfig = Objects.requireNonNull(connectionConfig, "Missing connectionConfig");
        mGatewayStatusCallback =
                Objects.requireNonNull(gatewayStatusCallback, "Missing gatewayStatusCallback");
        mDeps = Objects.requireNonNull(deps, "Missing deps");

        mLastSnapshot = Objects.requireNonNull(snapshot, "Missing snapshot");

        mUnderlyingNetworkTrackerCallback = new VcnUnderlyingNetworkTrackerCallback();

        mWakeLock =
                mDeps.newWakeLock(mVcnContext.getContext(), PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mUnderlyingNetworkTracker =
                mDeps.newUnderlyingNetworkTracker(
                        mVcnContext,
                        subscriptionGroup,
                        mLastSnapshot,
                        mConnectionConfig.getAllUnderlyingCapabilities(),
                        mUnderlyingNetworkTrackerCallback);
        mIpSecManager = mVcnContext.getContext().getSystemService(IpSecManager.class);

        addState(mDisconnectedState);
        addState(mDisconnectingState);
        addState(mConnectingState);
        addState(mConnectedState);
        addState(mRetryTimeoutState);

        setInitialState(mDisconnectedState);
        setDbg(VDBG);
        start();
    }

    /** Queries whether this VcnGatewayConnection is in safe mode. */
    public boolean isInSafeMode() {
        // Accessing internal state; must only be done on looper thread.
        mVcnContext.ensureRunningOnLooperThread();

        return mIsInSafeMode;
    }

    /**
     * Asynchronously tears down this GatewayConnection, and any resources used.
     *
     * <p>Once torn down, this VcnTunnel CANNOT be started again.
     */
    public void teardownAsynchronously() {
        sendDisconnectRequestedAndAcquireWakelock(
                DISCONNECT_REASON_TEARDOWN, true /* shouldQuit */);

        // TODO: Notify VcnInstance (via callbacks) of permanent teardown of this tunnel, since this
        // is also called asynchronously when a NetworkAgent becomes unwanted
    }

    @Override
    protected void onQuitting() {
        // No need to call setInterfaceDown(); the IpSecInterface is being fully torn down.
        if (mTunnelIface != null) {
            mTunnelIface.close();
        }

        releaseWakeLock();

        cancelTeardownTimeoutAlarm();
        cancelDisconnectRequestAlarm();
        cancelRetryTimeoutAlarm();
        cancelSafeModeAlarm();

        mUnderlyingNetworkTracker.teardown();

        mGatewayStatusCallback.onQuit();
    }

    /**
     * Notify this Gateway that subscriptions have changed.
     *
     * <p>This snapshot should be used to update any keepalive requests necessary for potential
     * underlying Networks in this Gateway's subscription group.
     */
    public void updateSubscriptionSnapshot(@NonNull TelephonySubscriptionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Missing snapshot");
        mVcnContext.ensureRunningOnLooperThread();

        mLastSnapshot = snapshot;
        mUnderlyingNetworkTracker.updateSubscriptionSnapshot(mLastSnapshot);

        sendMessageAndAcquireWakeLock(EVENT_SUBSCRIPTIONS_CHANGED, TOKEN_ALL);
    }

    private class VcnUnderlyingNetworkTrackerCallback implements UnderlyingNetworkTrackerCallback {
        @Override
        public void onSelectedUnderlyingNetworkChanged(
                @Nullable UnderlyingNetworkRecord underlying) {
            // TODO(b/180132994): explore safely removing this Thread check
            mVcnContext.ensureRunningOnLooperThread();

            // TODO(b/179091925): Move the delayed-message handling to BaseState

            // If underlying is null, all underlying networks have been lost. Disconnect VCN after a
            // timeout.
            if (underlying == null) {
                setDisconnectRequestAlarm();
            } else {
                // Received a new Network so any previous alarm is irrelevant - cancel + clear it,
                // and cancel any queued EVENT_DISCONNECT_REQUEST messages
                cancelDisconnectRequestAlarm();
            }

            sendMessageAndAcquireWakeLock(
                    EVENT_UNDERLYING_NETWORK_CHANGED,
                    TOKEN_ALL,
                    new EventUnderlyingNetworkChangedInfo(underlying));
        }
    }

    private void acquireWakeLock() {
        mVcnContext.ensureRunningOnLooperThread();

        if (!mIsQuitting) {
            mWakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        mVcnContext.ensureRunningOnLooperThread();

        mWakeLock.release();
    }

    /**
     * Attempt to release mWakeLock - this can only be done if the Handler is null (meaning the
     * StateMachine has been shutdown and thus has no business keeping the WakeLock) or if there are
     * no more messags left to process in the Handler queue (at which point the WakeLock can be
     * released until more messages must be processed).
     */
    private void maybeReleaseWakeLock() {
        final Handler handler = getHandler();
        if (handler == null || !handler.hasMessagesOrCallbacks()) {
            releaseWakeLock();
        }
    }

    @Override
    public void sendMessage(int what) {
        Slog.wtf(
                TAG,
                "sendMessage should not be used in VcnGatewayConnection. See"
                        + " sendMessageAndAcquireWakeLock()");
        super.sendMessage(what);
    }

    @Override
    public void sendMessage(int what, Object obj) {
        Slog.wtf(
                TAG,
                "sendMessage should not be used in VcnGatewayConnection. See"
                        + " sendMessageAndAcquireWakeLock()");
        super.sendMessage(what, obj);
    }

    @Override
    public void sendMessage(int what, int arg1) {
        Slog.wtf(
                TAG,
                "sendMessage should not be used in VcnGatewayConnection. See"
                        + " sendMessageAndAcquireWakeLock()");
        super.sendMessage(what, arg1);
    }

    @Override
    public void sendMessage(int what, int arg1, int arg2) {
        Slog.wtf(
                TAG,
                "sendMessage should not be used in VcnGatewayConnection. See"
                        + " sendMessageAndAcquireWakeLock()");
        super.sendMessage(what, arg1, arg2);
    }

    @Override
    public void sendMessage(int what, int arg1, int arg2, Object obj) {
        Slog.wtf(
                TAG,
                "sendMessage should not be used in VcnGatewayConnection. See"
                        + " sendMessageAndAcquireWakeLock()");
        super.sendMessage(what, arg1, arg2, obj);
    }

    @Override
    public void sendMessage(Message msg) {
        Slog.wtf(
                TAG,
                "sendMessage should not be used in VcnGatewayConnection. See"
                        + " sendMessageAndAcquireWakeLock()");
        super.sendMessage(msg);
    }

    // TODO(b/180146061): also override and Log.wtf() other Message handling methods
    // In mind are sendMessageDelayed(), sendMessageAtFrontOfQueue, removeMessages, and
    // removeDeferredMessages

    /**
     * WakeLock-based alternative to {@link #sendMessage}. Use to guarantee that the device will not
     * go to sleep before processing the sent message.
     */
    private void sendMessageAndAcquireWakeLock(int what, int token) {
        acquireWakeLock();
        super.sendMessage(what, token);
    }

    /**
     * WakeLock-based alternative to {@link #sendMessage}. Use to guarantee that the device will not
     * go to sleep before processing the sent message.
     */
    private void sendMessageAndAcquireWakeLock(int what, int token, EventInfo data) {
        acquireWakeLock();
        super.sendMessage(what, token, ARG_NOT_PRESENT, data);
    }

    /**
     * WakeLock-based alternative to {@link #sendMessage}. Use to guarantee that the device will not
     * go to sleep before processing the sent message.
     */
    private void sendMessageAndAcquireWakeLock(int what, int token, int arg2, EventInfo data) {
        acquireWakeLock();
        super.sendMessage(what, token, arg2, data);
    }

    /**
     * WakeLock-based alternative to {@link #sendMessage}. Use to guarantee that the device will not
     * go to sleep before processing the sent message.
     */
    private void sendMessageAndAcquireWakeLock(Message msg) {
        acquireWakeLock();
        super.sendMessage(msg);
    }

    /**
     * Removes all messages matching the given parameters, and attempts to release mWakeLock if the
     * Handler is empty.
     *
     * @param what the Message.what value to be removed
     */
    private void removeEqualMessages(int what) {
        removeEqualMessages(what, null /* obj */);
    }

    /**
     * Removes all messages matching the given parameters, and attempts to release mWakeLock if the
     * Handler is empty.
     *
     * @param what the Message.what value to be removed
     * @param obj the Message.obj to to be removed, or null if all messages matching Message.what
     *     should be removed
     */
    private void removeEqualMessages(int what, @Nullable Object obj) {
        final Handler handler = getHandler();
        if (handler != null) {
            handler.removeEqualMessages(what, obj);
        }

        maybeReleaseWakeLock();
    }

    private WakeupMessage createScheduledAlarm(
            @NonNull String cmdName, Message delayedMessage, long delay) {
        // WakeupMessage uses Handler#dispatchMessage() to immediately handle the specified Runnable
        // at the scheduled time. dispatchMessage() immediately executes and there may be queued
        // events that resolve the scheduled alarm pending in the queue. So, use the Runnable to
        // place the alarm event at the end of the queue with sendMessageAndAcquireWakeLock (which
        // guarantees the device will stay awake).
        final WakeupMessage alarm =
                mDeps.newWakeupMessage(
                        mVcnContext,
                        getHandler(),
                        cmdName,
                        () -> sendMessageAndAcquireWakeLock(delayedMessage));
        alarm.schedule(mDeps.getElapsedRealTime() + delay);
        return alarm;
    }

    private void setTeardownTimeoutAlarm() {
        // Safe to assign this alarm because it is either 1) already null, or 2) already fired. In
        // either case, there is nothing to cancel.
        if (mTeardownTimeoutAlarm != null) {
            Slog.wtf(TAG, "mTeardownTimeoutAlarm should be null before being set");
        }

        final Message delayedMessage = obtainMessage(EVENT_TEARDOWN_TIMEOUT_EXPIRED, mCurrentToken);
        mTeardownTimeoutAlarm =
                createScheduledAlarm(
                        TEARDOWN_TIMEOUT_ALARM,
                        delayedMessage,
                        TimeUnit.SECONDS.toMillis(TEARDOWN_TIMEOUT_SECONDS));
    }

    private void cancelTeardownTimeoutAlarm() {
        if (mTeardownTimeoutAlarm != null) {
            mTeardownTimeoutAlarm.cancel();
            mTeardownTimeoutAlarm = null;
        }

        // Cancel any existing teardown timeouts
        removeEqualMessages(EVENT_TEARDOWN_TIMEOUT_EXPIRED);
    }

    private void setDisconnectRequestAlarm() {
        // Only schedule a NEW alarm if none is already set.
        if (mDisconnectRequestAlarm != null) {
            return;
        }

        final Message delayedMessage =
                obtainMessage(
                        EVENT_DISCONNECT_REQUESTED,
                        TOKEN_ALL,
                        0 /* arg2 */,
                        new EventDisconnectRequestedInfo(
                                DISCONNECT_REASON_UNDERLYING_NETWORK_LOST, false /* shouldQuit */));
        mDisconnectRequestAlarm =
                createScheduledAlarm(
                        DISCONNECT_REQUEST_ALARM,
                        delayedMessage,
                        TimeUnit.SECONDS.toMillis(NETWORK_LOSS_DISCONNECT_TIMEOUT_SECONDS));
    }

    private void cancelDisconnectRequestAlarm() {
        if (mDisconnectRequestAlarm != null) {
            mDisconnectRequestAlarm.cancel();
            mDisconnectRequestAlarm = null;
        }

        // Cancel any existing disconnect due to previous loss of underlying network
        removeEqualMessages(
                EVENT_DISCONNECT_REQUESTED,
                new EventDisconnectRequestedInfo(
                        DISCONNECT_REASON_UNDERLYING_NETWORK_LOST, false /* shouldQuit */));
    }

    private void setRetryTimeoutAlarm(long delay) {
        // Safe to assign this alarm because it is either 1) already null, or 2) already fired. In
        // either case, there is nothing to cancel.
        if (mRetryTimeoutAlarm != null) {
            Slog.wtf(TAG, "mRetryTimeoutAlarm should be null before being set");
        }

        final Message delayedMessage = obtainMessage(EVENT_RETRY_TIMEOUT_EXPIRED, mCurrentToken);
        mRetryTimeoutAlarm = createScheduledAlarm(RETRY_TIMEOUT_ALARM, delayedMessage, delay);
    }

    private void cancelRetryTimeoutAlarm() {
        if (mRetryTimeoutAlarm != null) {
            mRetryTimeoutAlarm.cancel();
            mRetryTimeoutAlarm = null;
        }

        removeEqualMessages(EVENT_RETRY_TIMEOUT_EXPIRED);
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    void setSafeModeAlarm() {
        // Only schedule a NEW alarm if none is already set.
        if (mSafeModeTimeoutAlarm != null) {
            return;
        }

        final Message delayedMessage = obtainMessage(EVENT_SAFE_MODE_TIMEOUT_EXCEEDED, TOKEN_ALL);
        mSafeModeTimeoutAlarm =
                createScheduledAlarm(
                        SAFEMODE_TIMEOUT_ALARM,
                        delayedMessage,
                        TimeUnit.SECONDS.toMillis(SAFEMODE_TIMEOUT_SECONDS));
    }

    private void cancelSafeModeAlarm() {
        if (mSafeModeTimeoutAlarm != null) {
            mSafeModeTimeoutAlarm.cancel();
            mSafeModeTimeoutAlarm = null;
        }

        removeEqualMessages(EVENT_SAFE_MODE_TIMEOUT_EXCEEDED);
    }

    private void sessionLostWithoutCallback(int token, @Nullable Exception exception) {
        sendMessageAndAcquireWakeLock(
                EVENT_SESSION_LOST, token, new EventSessionLostInfo(exception));
    }

    private void sessionLost(int token, @Nullable Exception exception) {
        // Only notify mGatewayStatusCallback if the session was lost with an error. All
        // authentication and DNS failures are sent through
        // IkeSessionCallback.onClosedExceptionally(), which calls sessionClosed()
        if (exception != null) {
            mGatewayStatusCallback.onGatewayConnectionError(
                    mConnectionConfig.getGatewayConnectionName(),
                    VCN_ERROR_CODE_INTERNAL_ERROR,
                    RuntimeException.class.getName(),
                    "Received "
                            + exception.getClass().getSimpleName()
                            + " with message: "
                            + exception.getMessage());
        }

        sessionLostWithoutCallback(token, exception);
    }

    private void notifyStatusCallbackForSessionClosed(@NonNull Exception exception) {
        final int errorCode;
        final String exceptionClass;
        final String exceptionMessage;

        if (exception instanceof AuthenticationFailedException) {
            errorCode = VCN_ERROR_CODE_CONFIG_ERROR;
            exceptionClass = exception.getClass().getName();
            exceptionMessage = exception.getMessage();
        } else if (exception instanceof IkeInternalException
                && exception.getCause() instanceof IOException) {
            errorCode = VCN_ERROR_CODE_NETWORK_ERROR;
            exceptionClass = IOException.class.getName();
            exceptionMessage = exception.getCause().getMessage();
        } else {
            errorCode = VCN_ERROR_CODE_INTERNAL_ERROR;
            exceptionClass = RuntimeException.class.getName();
            exceptionMessage =
                    "Received "
                            + exception.getClass().getSimpleName()
                            + " with message: "
                            + exception.getMessage();
        }

        mGatewayStatusCallback.onGatewayConnectionError(
                mConnectionConfig.getGatewayConnectionName(),
                errorCode,
                exceptionClass,
                exceptionMessage);
    }

    private void sessionClosed(int token, @Nullable Exception exception) {
        if (exception != null) {
            notifyStatusCallbackForSessionClosed(exception);
        }

        // SESSION_LOST MUST be sent before SESSION_CLOSED to ensure that the SM moves to the
        // Disconnecting state.
        sessionLostWithoutCallback(token, exception);
        sendMessageAndAcquireWakeLock(EVENT_SESSION_CLOSED, token);
    }

    private void childTransformCreated(
            int token, @NonNull IpSecTransform transform, int direction) {
        sendMessageAndAcquireWakeLock(
                EVENT_TRANSFORM_CREATED,
                token,
                new EventTransformCreatedInfo(direction, transform));
    }

    private void childOpened(int token, @NonNull VcnChildSessionConfiguration childConfig) {
        sendMessageAndAcquireWakeLock(
                EVENT_SETUP_COMPLETED, token, new EventSetupCompletedInfo(childConfig));
    }

    private abstract class BaseState extends State {
        @Override
        public void enter() {
            try {
                enterState();
            } catch (Exception e) {
                Slog.wtf(TAG, "Uncaught exception", e);
                sendDisconnectRequestedAndAcquireWakelock(
                        DISCONNECT_REASON_INTERNAL_ERROR + e.toString(), true /* shouldQuit */);
            }
        }

        protected void enterState() throws Exception {}

        /**
         * Returns whether the given token is valid.
         *
         * <p>By default, States consider any and all token to be 'valid'.
         *
         * <p>States should override this method if they want to restrict message handling to
         * specific tokens.
         */
        protected boolean isValidToken(int token) {
            return true;
        }

        /**
         * Top-level processMessage with safeguards to prevent crashing the System Server on non-eng
         * builds.
         *
         * <p>Here be dragons: processMessage() is final to ensure that mWakeLock is released once
         * the Handler queue is empty. Future changes (or overrides) to processMessage() to MUST
         * ensure that mWakeLock is correctly released.
         */
        @Override
        public final boolean processMessage(Message msg) {
            final int token = msg.arg1;
            if (!isValidToken(token)) {
                Slog.v(TAG, "Message called with obsolete token: " + token + "; what: " + msg.what);
                return HANDLED;
            }

            try {
                processStateMsg(msg);
            } catch (Exception e) {
                Slog.wtf(TAG, "Uncaught exception", e);
                sendDisconnectRequestedAndAcquireWakelock(
                        DISCONNECT_REASON_INTERNAL_ERROR + e.toString(), true /* shouldQuit */);
            }

            // Attempt to release the WakeLock - only possible if the Handler queue is empty
            maybeReleaseWakeLock();

            return HANDLED;
        }

        protected abstract void processStateMsg(Message msg) throws Exception;

        @Override
        public void exit() {
            try {
                exitState();
            } catch (Exception e) {
                Slog.wtf(TAG, "Uncaught exception", e);
                sendDisconnectRequestedAndAcquireWakelock(
                        DISCONNECT_REASON_INTERNAL_ERROR + e.toString(), true /* shouldQuit */);
            }
        }

        protected void exitState() throws Exception {}

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
                case EVENT_TEARDOWN_TIMEOUT_EXPIRED: // Fallthrough
                case EVENT_SUBSCRIPTIONS_CHANGED:
                    logUnexpectedEvent(msg.what);
                    break;
                default:
                    logWtfUnknownEvent(msg.what);
                    break;
            }
        }

        protected void teardownNetwork() {
            if (mNetworkAgent != null) {
                mNetworkAgent.unregister();
                mNetworkAgent = null;
            }
        }

        protected void handleDisconnectRequested(EventDisconnectRequestedInfo info) {
            // TODO(b/180526152): notify VcnStatusCallback for Network loss

            Slog.v(TAG, "Tearing down. Cause: " + info.reason);
            mIsQuitting = info.shouldQuit;

            teardownNetwork();

            if (mIkeSession == null) {
                // Already disconnected, go straight to DisconnectedState
                transitionTo(mDisconnectedState);
            } else {
                // Still need to wait for full closure
                transitionTo(mDisconnectingState);
            }
        }

        protected void handleSafeModeTimeoutExceeded() {
            mSafeModeTimeoutAlarm = null;

            // Connectivity for this GatewayConnection is broken; tear down the Network.
            teardownNetwork();
            mIsInSafeMode = true;
            mGatewayStatusCallback.onSafeModeStatusChanged();
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
        protected void enterState() {
            if (mIsQuitting) {
                quitNow(); // Ignore all queued events; cleanup is complete.
            }

            if (mIkeSession != null || mNetworkAgent != null) {
                Slog.wtf(TAG, "Active IKE Session or NetworkAgent in DisconnectedState");
            }

            cancelSafeModeAlarm();
        }

        @Override
        protected void processStateMsg(Message msg) {
            switch (msg.what) {
                case EVENT_UNDERLYING_NETWORK_CHANGED:
                    // First network found; start tunnel
                    mUnderlying = ((EventUnderlyingNetworkChangedInfo) msg.obj).newUnderlying;

                    if (mUnderlying != null) {
                        transitionTo(mConnectingState);
                    }
                    break;
                case EVENT_DISCONNECT_REQUESTED:
                    if (((EventDisconnectRequestedInfo) msg.obj).shouldQuit) {
                        mIsQuitting = true;

                        quitNow();
                    }
                    break;
                default:
                    logUnhandledMessage(msg);
                    break;
            }
        }

        @Override
        protected void exitState() {
            // Safe to blindly set up, as it is cancelled and cleared on entering this state
            setSafeModeAlarm();
        }
    }

    private abstract class ActiveBaseState extends BaseState {
        @Override
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
        /**
         * Whether to skip the RetryTimeoutState and go straight to the ConnectingState.
         *
         * <p>This is used when an underlying network change triggered a restart on a new network.
         *
         * <p>Reset (to false) upon exit of the DisconnectingState.
         */
        private boolean mSkipRetryTimeout = false;

        // TODO(b/178441390): Remove this in favor of resetting retry timers on UND_NET change.
        public void setSkipRetryTimeout(boolean shouldSkip) {
            mSkipRetryTimeout = shouldSkip;
        }

        @Override
        protected void enterState() throws Exception {
            if (mIkeSession == null) {
                Slog.wtf(TAG, "IKE session was already closed when entering Disconnecting state.");
                sendMessageAndAcquireWakeLock(EVENT_SESSION_CLOSED, mCurrentToken);
                return;
            }

            // If underlying network has already been lost, save some time and just kill the session
            if (mUnderlying == null) {
                // Will trigger a EVENT_SESSION_CLOSED as IkeSession shuts down.
                mIkeSession.kill();
                return;
            }

            mIkeSession.close();

            // Safe to blindly set up, as it is cancelled and cleared on exiting this state
            setTeardownTimeoutAlarm();
        }

        @Override
        protected void processStateMsg(Message msg) {
            switch (msg.what) {
                case EVENT_UNDERLYING_NETWORK_CHANGED: // Fallthrough
                    mUnderlying = ((EventUnderlyingNetworkChangedInfo) msg.obj).newUnderlying;

                    // If we received a new underlying network, continue.
                    if (mUnderlying != null) {
                        break;
                    }

                    // Fallthrough; no network exists to send IKE close session requests.
                case EVENT_TEARDOWN_TIMEOUT_EXPIRED:
                    // Grace period ended. Kill session, triggering EVENT_SESSION_CLOSED
                    mIkeSession.kill();

                    break;
                case EVENT_DISCONNECT_REQUESTED:
                    EventDisconnectRequestedInfo info = ((EventDisconnectRequestedInfo) msg.obj);
                    mIsQuitting = info.shouldQuit;
                    teardownNetwork();

                    if (info.reason.equals(DISCONNECT_REASON_UNDERLYING_NETWORK_LOST)) {
                        // TODO(b/180526152): notify VcnStatusCallback for Network loss

                        // Will trigger EVENT_SESSION_CLOSED immediately.
                        mIkeSession.kill();
                        break;
                    }

                    // Otherwise we are already in the process of shutting down.
                    break;
                case EVENT_SESSION_CLOSED:
                    mIkeSession = null;

                    if (!mIsQuitting && mUnderlying != null) {
                        transitionTo(mSkipRetryTimeout ? mConnectingState : mRetryTimeoutState);
                    } else {
                        teardownNetwork();
                        transitionTo(mDisconnectedState);
                    }
                    break;
                case EVENT_SAFE_MODE_TIMEOUT_EXCEEDED:
                    handleSafeModeTimeoutExceeded();
                    break;
                default:
                    logUnhandledMessage(msg);
                    break;
            }
        }

        @Override
        protected void exitState() throws Exception {
            mSkipRetryTimeout = false;

            cancelTeardownTimeoutAlarm();
        }
    }

    /**
     * Transitive state representing a VCN that is making an primary (non-handover) connection.
     *
     * <p>This state starts IKE negotiation, but defers transform application & network setup to the
     * Connected state.
     */
    private class ConnectingState extends ActiveBaseState {
        @Override
        protected void enterState() {
            if (mIkeSession != null) {
                Slog.wtf(TAG, "ConnectingState entered with active session");

                // Attempt to recover.
                mIkeSession.kill();
                mIkeSession = null;
            }

            mIkeSession = buildIkeSession(mUnderlying.network);
        }

        @Override
        protected void processStateMsg(Message msg) {
            switch (msg.what) {
                case EVENT_UNDERLYING_NETWORK_CHANGED:
                    final UnderlyingNetworkRecord oldUnderlying = mUnderlying;
                    mUnderlying = ((EventUnderlyingNetworkChangedInfo) msg.obj).newUnderlying;

                    if (oldUnderlying == null) {
                        // This should never happen, but if it does, there's likely a nasty bug.
                        Slog.wtf(TAG, "Old underlying network was null in connected state. Bug?");
                    }

                    // If new underlying is null, all underlying networks have been lost; disconnect
                    if (mUnderlying == null) {
                        transitionTo(mDisconnectingState);
                        break;
                    }

                    if (oldUnderlying != null
                            && mUnderlying.network.equals(oldUnderlying.network)) {
                        break; // Only network properties have changed; continue connecting.
                    }
                    // Else, retry on the new network.

                    // Immediately come back to the ConnectingState (skip RetryTimeout, since this
                    // isn't a failure)
                    mDisconnectingState.setSkipRetryTimeout(true);

                    // fallthrough - disconnect, and retry on new network.
                case EVENT_SESSION_LOST:
                    transitionTo(mDisconnectingState);
                    break;
                case EVENT_SESSION_CLOSED:
                    // Disconnecting state waits for EVENT_SESSION_CLOSED to shutdown, and this
                    // message may not be posted again. Defer to ensure immediate shutdown.
                    deferMessage(msg);

                    transitionTo(mDisconnectingState);
                    break;
                case EVENT_SETUP_COMPLETED: // fallthrough
                case EVENT_TRANSFORM_CREATED:
                    // Child setup complete; move to ConnectedState for NetworkAgent registration
                    deferMessage(msg);
                    transitionTo(mConnectedState);
                    break;
                case EVENT_DISCONNECT_REQUESTED:
                    handleDisconnectRequested((EventDisconnectRequestedInfo) msg.obj);
                    break;
                case EVENT_SAFE_MODE_TIMEOUT_EXCEEDED:
                    handleSafeModeTimeoutExceeded();
                    break;
                default:
                    logUnhandledMessage(msg);
                    break;
            }
        }
    }

    private abstract class ConnectedStateBase extends ActiveBaseState {
        protected void updateNetworkAgent(
                @NonNull IpSecTunnelInterface tunnelIface,
                @NonNull NetworkAgent agent,
                @NonNull VcnChildSessionConfiguration childConfig) {
            final NetworkCapabilities caps =
                    buildNetworkCapabilities(mConnectionConfig, mUnderlying);
            final LinkProperties lp =
                    buildConnectedLinkProperties(mConnectionConfig, tunnelIface, childConfig);

            agent.sendNetworkCapabilities(caps);
            agent.sendLinkProperties(lp);
        }

        protected NetworkAgent buildNetworkAgent(
                @NonNull IpSecTunnelInterface tunnelIface,
                @NonNull VcnChildSessionConfiguration childConfig) {
            final NetworkCapabilities caps =
                    buildNetworkCapabilities(mConnectionConfig, mUnderlying);
            final LinkProperties lp =
                    buildConnectedLinkProperties(mConnectionConfig, tunnelIface, childConfig);

            final NetworkAgent agent =
                    mDeps.newNetworkAgent(
                            mVcnContext,
                            TAG,
                            caps,
                            lp,
                            Vcn.getNetworkScore(),
                            new NetworkAgentConfig.Builder().build(),
                            mVcnContext.getVcnNetworkProvider(),
                            () -> {
                                Slog.d(TAG, "NetworkAgent was unwanted");
                                teardownAsynchronously();
                            } /* networkUnwantedCallback */,
                            (status) -> {
                                if (status == NetworkAgent.VALIDATION_STATUS_VALID) {
                                    clearFailedAttemptCounterAndSafeModeAlarm();
                                }
                            } /* validationStatusCallback */);

            agent.register();
            agent.markConnected();

            return agent;
        }

        protected void clearFailedAttemptCounterAndSafeModeAlarm() {
            mVcnContext.ensureRunningOnLooperThread();

            // Validated connection, clear failed attempt counter
            mFailedAttempts = 0;
            cancelSafeModeAlarm();

            if (mIsInSafeMode) {
                mIsInSafeMode = false;
                mGatewayStatusCallback.onSafeModeStatusChanged();
            }
        }

        protected void applyTransform(
                int token,
                @NonNull IpSecTunnelInterface tunnelIface,
                @NonNull Network underlyingNetwork,
                @NonNull IpSecTransform transform,
                int direction) {
            try {
                tunnelIface.setUnderlyingNetwork(underlyingNetwork);

                // Transforms do not need to be persisted; the IkeSession will keep them alive
                mIpSecManager.applyTunnelModeTransform(tunnelIface, direction, transform);
            } catch (IOException e) {
                Slog.d(TAG, "Transform application failed for network " + token, e);
                sessionLost(token, e);
            }
        }

        protected void setupInterface(
                int token,
                @NonNull IpSecTunnelInterface tunnelIface,
                @NonNull VcnChildSessionConfiguration childConfig) {
            setupInterface(token, tunnelIface, childConfig, null);
        }

        protected void setupInterface(
                int token,
                @NonNull IpSecTunnelInterface tunnelIface,
                @NonNull VcnChildSessionConfiguration childConfig,
                @Nullable VcnChildSessionConfiguration oldChildConfig) {
            try {
                final Set<LinkAddress> newAddrs =
                        new ArraySet<>(childConfig.getInternalAddresses());
                final Set<LinkAddress> existingAddrs = new ArraySet<>();
                if (oldChildConfig != null) {
                    existingAddrs.addAll(oldChildConfig.getInternalAddresses());
                }

                final Set<LinkAddress> toAdd = new ArraySet<>();
                toAdd.addAll(newAddrs);
                toAdd.removeAll(existingAddrs);

                final Set<LinkAddress> toRemove = new ArraySet<>();
                toRemove.addAll(existingAddrs);
                toRemove.removeAll(newAddrs);

                for (LinkAddress address : toAdd) {
                    tunnelIface.addAddress(address.getAddress(), address.getPrefixLength());
                }

                for (LinkAddress address : toRemove) {
                    tunnelIface.removeAddress(address.getAddress(), address.getPrefixLength());
                }
            } catch (IOException e) {
                Slog.d(TAG, "Adding address to tunnel failed for token " + token, e);
                sessionLost(token, e);
            }
        }
    }

    /**
     * Stable state representing a VCN that has a functioning connection to the mobility anchor.
     *
     * <p>This state handles IPsec transform application (initial and rekey), NetworkAgent setup,
     * and monitors for mobility events.
     */
    class ConnectedState extends ConnectedStateBase {
        @Override
        protected void enterState() throws Exception {
            if (mTunnelIface == null) {
                try {
                    // Requires a real Network object in order to be created; doing this any earlier
                    // means not having a real Network object, or picking an incorrect Network.
                    mTunnelIface =
                            mIpSecManager.createIpSecTunnelInterface(
                                    DUMMY_ADDR, DUMMY_ADDR, mUnderlying.network);
                } catch (IOException | ResourceUnavailableException e) {
                    teardownAsynchronously();
                }
            }
        }

        @Override
        protected void processStateMsg(Message msg) {
            switch (msg.what) {
                case EVENT_UNDERLYING_NETWORK_CHANGED:
                    handleUnderlyingNetworkChanged(msg);
                    break;
                case EVENT_SESSION_CLOSED:
                    // Disconnecting state waits for EVENT_SESSION_CLOSED to shutdown, and this
                    // message may not be posted again. Defer to ensure immediate shutdown.
                    deferMessage(msg);
                    transitionTo(mDisconnectingState);
                    break;
                case EVENT_SESSION_LOST:
                    transitionTo(mDisconnectingState);
                    break;
                case EVENT_TRANSFORM_CREATED:
                    final EventTransformCreatedInfo transformCreatedInfo =
                            (EventTransformCreatedInfo) msg.obj;

                    applyTransform(
                            mCurrentToken,
                            mTunnelIface,
                            mUnderlying.network,
                            transformCreatedInfo.transform,
                            transformCreatedInfo.direction);
                    break;
                case EVENT_SETUP_COMPLETED:
                    mChildConfig = ((EventSetupCompletedInfo) msg.obj).childSessionConfig;

                    setupInterfaceAndNetworkAgent(mCurrentToken, mTunnelIface, mChildConfig);
                    break;
                case EVENT_DISCONNECT_REQUESTED:
                    handleDisconnectRequested((EventDisconnectRequestedInfo) msg.obj);
                    break;
                case EVENT_SAFE_MODE_TIMEOUT_EXCEEDED:
                    handleSafeModeTimeoutExceeded();
                    break;
                default:
                    logUnhandledMessage(msg);
                    break;
            }
        }

        private void handleUnderlyingNetworkChanged(@NonNull Message msg) {
            final UnderlyingNetworkRecord oldUnderlying = mUnderlying;
            mUnderlying = ((EventUnderlyingNetworkChangedInfo) msg.obj).newUnderlying;

            if (mUnderlying == null) {
                // Ignored for now; a new network may be coming up. If none does, the delayed
                // NETWORK_LOST disconnect will be fired, and tear down the session + network.
                return;
            }

            // mUnderlying assumed non-null, given check above.
            // If network changed, migrate. Otherwise, update any existing networkAgent.
            if (oldUnderlying == null || !oldUnderlying.network.equals(mUnderlying.network)) {
                Slog.v(TAG, "Migrating to new network: " + mUnderlying.network);
                mIkeSession.setNetwork(mUnderlying.network);
            } else {
                // oldUnderlying is non-null & underlying network itself has not changed
                // (only network properties were changed).

                // Network not yet set up, or child not yet connected.
                if (mNetworkAgent != null && mChildConfig != null) {
                    // If only network properties changed and agent is active, update properties
                    updateNetworkAgent(mTunnelIface, mNetworkAgent, mChildConfig);
                }
            }
        }

        protected void setupInterfaceAndNetworkAgent(
                int token,
                @NonNull IpSecTunnelInterface tunnelIface,
                @NonNull VcnChildSessionConfiguration childConfig) {
            setupInterface(token, tunnelIface, childConfig);

            if (mNetworkAgent == null) {
                mNetworkAgent = buildNetworkAgent(tunnelIface, childConfig);
            } else {
                updateNetworkAgent(tunnelIface, mNetworkAgent, childConfig);

                // mNetworkAgent not null, so the VCN Network has already been established. Clear
                // the failed attempt counter and safe mode alarm since this transition is complete.
                clearFailedAttemptCounterAndSafeModeAlarm();
            }
        }

        @Override
        protected void exitState() {
            // Will only set a new alarm if no safe mode alarm is currently scheduled.
            setSafeModeAlarm();
        }
    }

    /**
     * Transitive state representing a VCN that failed to establish a connection, and will retry.
     *
     * <p>This state will be exited upon a new underlying network being found, or timeout expiry.
     */
    class RetryTimeoutState extends ActiveBaseState {
        @Override
        protected void enterState() throws Exception {
            // Reset upon entry to ConnectedState
            mFailedAttempts++;

            if (mUnderlying == null) {
                Slog.wtf(TAG, "Underlying network was null in retry state");
                transitionTo(mDisconnectedState);
            } else {
                // Safe to blindly set up, as it is cancelled and cleared on exiting this state
                setRetryTimeoutAlarm(getNextRetryIntervalsMs());
            }
        }

        @Override
        protected void processStateMsg(Message msg) {
            switch (msg.what) {
                case EVENT_UNDERLYING_NETWORK_CHANGED:
                    final UnderlyingNetworkRecord oldUnderlying = mUnderlying;
                    mUnderlying = ((EventUnderlyingNetworkChangedInfo) msg.obj).newUnderlying;

                    // If new underlying is null, all networks were lost; go back to disconnected.
                    if (mUnderlying == null) {
                        transitionTo(mDisconnectedState);
                        return;
                    } else if (oldUnderlying != null
                            && mUnderlying.network.equals(oldUnderlying.network)) {
                        // If the network has not changed, do nothing.
                        return;
                    }

                    // Fallthrough
                case EVENT_RETRY_TIMEOUT_EXPIRED:
                    transitionTo(mConnectingState);
                    break;
                case EVENT_DISCONNECT_REQUESTED:
                    handleDisconnectRequested((EventDisconnectRequestedInfo) msg.obj);
                    break;
                case EVENT_SAFE_MODE_TIMEOUT_EXCEEDED:
                    handleSafeModeTimeoutExceeded();
                    break;
                default:
                    logUnhandledMessage(msg);
                    break;
            }
        }

        @Override
        public void exitState() {
            cancelRetryTimeoutAlarm();
        }

        private long getNextRetryIntervalsMs() {
            final int retryDelayIndex = mFailedAttempts - 1;
            final long[] retryIntervalsMs = mConnectionConfig.getRetryIntervalsMs();

            // Repeatedly use last item in retry timeout list.
            if (retryDelayIndex >= retryIntervalsMs.length) {
                return retryIntervalsMs[retryIntervalsMs.length - 1];
            }

            return retryIntervalsMs[retryDelayIndex];
        }
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static NetworkCapabilities buildNetworkCapabilities(
            @NonNull VcnGatewayConnectionConfig gatewayConnectionConfig,
            @Nullable UnderlyingNetworkRecord underlying) {
        final NetworkCapabilities.Builder builder = new NetworkCapabilities.Builder();

        builder.addTransportType(TRANSPORT_CELLULAR);
        builder.addCapability(NET_CAPABILITY_NOT_VCN_MANAGED);
        builder.addCapability(NET_CAPABILITY_NOT_CONGESTED);
        builder.addCapability(NET_CAPABILITY_NOT_SUSPENDED);

        // Add exposed capabilities
        for (int cap : gatewayConnectionConfig.getAllExposedCapabilities()) {
            builder.addCapability(cap);
        }

        if (underlying != null) {
            final NetworkCapabilities underlyingCaps = underlying.networkCapabilities;

            // Mirror merged capabilities.
            for (int cap : MERGED_CAPABILITIES) {
                if (underlyingCaps.hasCapability(cap)) {
                    builder.addCapability(cap);
                }
            }

            // Set admin UIDs for ConnectivityDiagnostics use.
            final int[] underlyingAdminUids = underlyingCaps.getAdministratorUids();
            Arrays.sort(underlyingAdminUids); // Sort to allow contains check below.

            final int[] adminUids;
            if (underlyingCaps.getOwnerUid() > 0 // No owner UID specified
                    && 0 > Arrays.binarySearch(// Owner UID not found in admin UID list.
                            underlyingAdminUids, underlyingCaps.getOwnerUid())) {
                adminUids = Arrays.copyOf(underlyingAdminUids, underlyingAdminUids.length + 1);
                adminUids[adminUids.length - 1] = underlyingCaps.getOwnerUid();
                Arrays.sort(adminUids);
            } else {
                adminUids = underlyingAdminUids;
            }
            builder.setAdministratorUids(adminUids);

            // Set TransportInfo for SysUI use (never parcelled out of SystemServer).
            if (underlyingCaps.hasTransport(TRANSPORT_WIFI)
                    && underlyingCaps.getTransportInfo() instanceof WifiInfo) {
                final WifiInfo wifiInfo = (WifiInfo) underlyingCaps.getTransportInfo();
                builder.setTransportInfo(new VcnTransportInfo(wifiInfo));
            } else if (underlyingCaps.hasTransport(TRANSPORT_CELLULAR)
                    && underlyingCaps.getNetworkSpecifier() instanceof TelephonyNetworkSpecifier) {
                final TelephonyNetworkSpecifier telNetSpecifier =
                        (TelephonyNetworkSpecifier) underlyingCaps.getNetworkSpecifier();
                builder.setTransportInfo(new VcnTransportInfo(telNetSpecifier.getSubscriptionId()));
            } else {
                Slog.wtf(
                        TAG,
                        "Unknown transport type or missing TransportInfo/NetworkSpecifier for"
                                + " non-null underlying network");
            }
        }

        return builder.build();
    }

    private static LinkProperties buildConnectedLinkProperties(
            @NonNull VcnGatewayConnectionConfig gatewayConnectionConfig,
            @NonNull IpSecTunnelInterface tunnelIface,
            @NonNull VcnChildSessionConfiguration childConfig) {
        final LinkProperties lp = new LinkProperties();

        lp.setInterfaceName(tunnelIface.getInterfaceName());
        for (LinkAddress addr : childConfig.getInternalAddresses()) {
            lp.addLinkAddress(addr);
        }
        for (InetAddress addr : childConfig.getInternalDnsServers()) {
            lp.addDnsServer(addr);
        }

        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null /*gateway*/,
                null /*iface*/, RouteInfo.RTN_UNICAST));
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null /*gateway*/,
                null /*iface*/, RouteInfo.RTN_UNICAST));

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

    /** Implementation of ChildSessionCallback, exposed for testing. */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public class VcnChildSessionCallback implements ChildSessionCallback {
        private final int mToken;

        VcnChildSessionCallback(int token) {
            mToken = token;
        }

        /** Internal proxy method for injecting of mocked ChildSessionConfiguration */
        @VisibleForTesting(visibility = Visibility.PRIVATE)
        void onOpened(@NonNull VcnChildSessionConfiguration childConfig) {
            Slog.v(TAG, "ChildOpened for token " + mToken);
            childOpened(mToken, childConfig);
        }

        @Override
        public void onOpened(@NonNull ChildSessionConfiguration childConfig) {
            onOpened(new VcnChildSessionConfiguration(childConfig));
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
        public void onIpSecTransformsMigrated(
                @NonNull IpSecTransform inIpSecTransform,
                @NonNull IpSecTransform outIpSecTransform) {
            Slog.v(TAG, "ChildTransformsMigrated; token " + mToken);
            onIpSecTransformCreated(inIpSecTransform, IpSecManager.DIRECTION_IN);
            onIpSecTransformCreated(outIpSecTransform, IpSecManager.DIRECTION_OUT);
        }

        @Override
        public void onIpSecTransformDeleted(@NonNull IpSecTransform transform, int direction) {
            // Nothing to be done; no references to the IpSecTransform are held, and this transform
            // will be closed by the IKE library.
            Slog.v(TAG, "ChildTransformDeleted; Direction: " + direction + "; for token " + mToken);
        }
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    void setTunnelInterface(IpSecTunnelInterface tunnelIface) {
        mTunnelIface = tunnelIface;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    UnderlyingNetworkTrackerCallback getUnderlyingNetworkTrackerCallback() {
        return mUnderlyingNetworkTrackerCallback;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    UnderlyingNetworkRecord getUnderlyingNetwork() {
        return mUnderlying;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    void setUnderlyingNetwork(@Nullable UnderlyingNetworkRecord record) {
        mUnderlying = record;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    boolean isQuitting() {
        return mIsQuitting;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    void setIsQuitting(boolean isQuitting) {
        mIsQuitting = isQuitting;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    VcnIkeSession getIkeSession() {
        return mIkeSession;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    void setIkeSession(@Nullable VcnIkeSession session) {
        mIkeSession = session;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    NetworkAgent getNetworkAgent() {
        return mNetworkAgent;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    void setNetworkAgent(@Nullable NetworkAgent networkAgent) {
        mNetworkAgent = networkAgent;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    void sendDisconnectRequestedAndAcquireWakelock(String reason, boolean shouldQuit) {
        sendMessageAndAcquireWakeLock(
                EVENT_DISCONNECT_REQUESTED,
                TOKEN_ALL,
                new EventDisconnectRequestedInfo(reason, shouldQuit));
    }

    private IkeSessionParams buildIkeParams(@NonNull Network network) {
        final VcnControlPlaneIkeConfig controlPlaneConfig =
                (VcnControlPlaneIkeConfig) mConnectionConfig.getControlPlaneConfig();
        final IkeSessionParams.Builder builder =
                new IkeSessionParams.Builder(controlPlaneConfig.getIkeSessionParams());
        builder.setConfiguredNetwork(network);

        return builder.build();
    }

    private ChildSessionParams buildChildParams() {
        final VcnControlPlaneIkeConfig controlPlaneConfig =
                (VcnControlPlaneIkeConfig) mConnectionConfig.getControlPlaneConfig();
        return controlPlaneConfig.getChildSessionParams();
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    VcnIkeSession buildIkeSession(@NonNull Network network) {
        final int token = ++mCurrentToken;

        return mDeps.newIkeSession(
                mVcnContext,
                buildIkeParams(network),
                buildChildParams(),
                new IkeSessionCallbackImpl(token),
                new VcnChildSessionCallback(token));
    }

    /** External dependencies used by VcnGatewayConnection, for injection in tests */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        /** Builds a new UnderlyingNetworkTracker. */
        public UnderlyingNetworkTracker newUnderlyingNetworkTracker(
                VcnContext vcnContext,
                ParcelUuid subscriptionGroup,
                TelephonySubscriptionSnapshot snapshot,
                Set<Integer> requiredUnderlyingNetworkCapabilities,
                UnderlyingNetworkTrackerCallback callback) {
            return new UnderlyingNetworkTracker(
                    vcnContext,
                    subscriptionGroup,
                    snapshot,
                    requiredUnderlyingNetworkCapabilities,
                    callback);
        }

        /** Builds a new IkeSession. */
        public VcnIkeSession newIkeSession(
                VcnContext vcnContext,
                IkeSessionParams ikeSessionParams,
                ChildSessionParams childSessionParams,
                IkeSessionCallback ikeSessionCallback,
                ChildSessionCallback childSessionCallback) {
            return new VcnIkeSession(
                    vcnContext,
                    ikeSessionParams,
                    childSessionParams,
                    ikeSessionCallback,
                    childSessionCallback);
        }

        /** Builds a new WakeLock. */
        public VcnWakeLock newWakeLock(
                @NonNull Context context, int wakeLockFlag, @NonNull String wakeLockTag) {
            return new VcnWakeLock(context, wakeLockFlag, wakeLockTag);
        }

        /** Builds a new WakeupMessage. */
        public WakeupMessage newWakeupMessage(
                @NonNull VcnContext vcnContext,
                @NonNull Handler handler,
                @NonNull String tag,
                @NonNull Runnable runnable) {
            return new WakeupMessage(vcnContext.getContext(), handler, tag, runnable);
        }

        /** Builds a new NetworkAgent. */
        public NetworkAgent newNetworkAgent(
                @NonNull VcnContext vcnContext,
                @NonNull String tag,
                @NonNull NetworkCapabilities caps,
                @NonNull LinkProperties lp,
                @NonNull int score,
                @NonNull NetworkAgentConfig nac,
                @NonNull NetworkProvider provider,
                @NonNull Runnable networkUnwantedCallback,
                @NonNull Consumer<Integer> validationStatusCallback) {
            return new NetworkAgent(
                    vcnContext.getContext(),
                    vcnContext.getLooper(),
                    tag,
                    caps,
                    lp,
                    score,
                    nac,
                    provider) {
                @Override
                public void onNetworkUnwanted() {
                    networkUnwantedCallback.run();
                }

                @Override
                public void onValidationStatus(int status, @Nullable Uri redirectUri) {
                    validationStatusCallback.accept(status);
                }
            };
        }

        /** Gets the elapsed real time since boot, in millis. */
        public long getElapsedRealTime() {
            return SystemClock.elapsedRealtime();
        }
    }

    /**
     * Proxy implementation of Child Session Configuration, used for testing.
     *
     * <p>This wrapper allows mocking of the final, parcelable ChildSessionConfiguration object for
     * testing purposes. This is the unfortunate result of mockito-inline (for mocking final
     * classes) not working properly with system services & associated classes.
     *
     * <p>This class MUST EXCLUSIVELY be a passthrough, proxying calls directly to the actual
     * ChildSessionConfiguration.
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class VcnChildSessionConfiguration {
        private final ChildSessionConfiguration mChildConfig;

        public VcnChildSessionConfiguration(ChildSessionConfiguration childConfig) {
            mChildConfig = childConfig;
        }

        /** Retrieves the addresses to be used inside the tunnel. */
        public List<LinkAddress> getInternalAddresses() {
            return mChildConfig.getInternalAddresses();
        }

        /** Retrieves the DNS servers to be used inside the tunnel. */
        public List<InetAddress> getInternalDnsServers() {
            return mChildConfig.getInternalDnsServers();
        }
    }

    /** Proxy implementation of IKE session, used for testing. */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class VcnIkeSession {
        private final IkeSession mImpl;

        public VcnIkeSession(
                VcnContext vcnContext,
                IkeSessionParams ikeSessionParams,
                ChildSessionParams childSessionParams,
                IkeSessionCallback ikeSessionCallback,
                ChildSessionCallback childSessionCallback) {
            mImpl =
                    new IkeSession(
                            vcnContext.getContext(),
                            ikeSessionParams,
                            childSessionParams,
                            new HandlerExecutor(new Handler(vcnContext.getLooper())),
                            ikeSessionCallback,
                            childSessionCallback);
        }

        /** Creates a new IKE Child session. */
        public void openChildSession(
                @NonNull ChildSessionParams childSessionParams,
                @NonNull ChildSessionCallback childSessionCallback) {
            mImpl.openChildSession(childSessionParams, childSessionCallback);
        }

        /** Closes an IKE session as identified by the ChildSessionCallback. */
        public void closeChildSession(@NonNull ChildSessionCallback childSessionCallback) {
            mImpl.closeChildSession(childSessionCallback);
        }

        /** Gracefully closes this IKE Session, waiting for remote acknowledgement. */
        public void close() {
            mImpl.close();
        }

        /** Forcibly kills this IKE Session, without waiting for a closure confirmation. */
        public void kill() {
            mImpl.kill();
        }

        /** Sets the underlying network used by the IkeSession. */
        public void setNetwork(@NonNull Network network) {
            mImpl.setNetwork(network);
        }
    }

    /** Proxy Implementation of WakeLock, used for testing. */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class VcnWakeLock {
        private final WakeLock mImpl;

        public VcnWakeLock(@NonNull Context context, int flags, @NonNull String tag) {
            final PowerManager powerManager = context.getSystemService(PowerManager.class);
            mImpl = powerManager.newWakeLock(flags, tag);
            mImpl.setReferenceCounted(false /* isReferenceCounted */);
        }

        /**
         * Acquire this WakeLock.
         *
         * <p>Synchronize this action to minimize locking around WakeLock use.
         */
        public synchronized void acquire() {
            mImpl.acquire();
        }

        /**
         * Release this Wakelock.
         *
         * <p>Synchronize this action to minimize locking around WakeLock use.
         */
        public synchronized void release() {
            mImpl.release();
        }
    }
}
