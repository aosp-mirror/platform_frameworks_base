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

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;

import static com.android.server.VcnManagementService.VDBG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.vcn.VcnConfig;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnManager.VcnErrorCode;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.server.VcnManagementService.VcnCallback;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents an single instance of a VCN.
 *
 * <p>Each Vcn instance manages all {@link VcnGatewayConnection}(s) for a given subscription group,
 * including per-capability networks, network selection, and multi-homing.
 *
 * @hide
 */
public class Vcn extends Handler {
    private static final String TAG = Vcn.class.getSimpleName();

    private static final int MSG_EVENT_BASE = 0;
    private static final int MSG_CMD_BASE = 100;

    /**
     * A carrier app updated the configuration.
     *
     * <p>Triggers update of config, re-evaluating all active and underlying networks.
     *
     * @param obj VcnConfig
     */
    private static final int MSG_EVENT_CONFIG_UPDATED = MSG_EVENT_BASE;

    /**
     * A NetworkRequest was added or updated.
     *
     * <p>Triggers an evaluation of all active networks, bringing up a new one if necessary.
     *
     * @param obj NetworkRequest
     */
    private static final int MSG_EVENT_NETWORK_REQUESTED = MSG_EVENT_BASE + 1;

    /**
     * The TelephonySubscriptionSnapshot tracked by VcnManagementService has changed.
     *
     * <p>This updated snapshot should be cached locally and passed to all VcnGatewayConnections.
     *
     * @param obj TelephonySubscriptionSnapshot
     */
    private static final int MSG_EVENT_SUBSCRIPTIONS_CHANGED = MSG_EVENT_BASE + 2;

    /**
     * A GatewayConnection owned by this VCN quit.
     *
     * @param obj VcnGatewayConnectionConfig
     */
    private static final int MSG_EVENT_GATEWAY_CONNECTION_QUIT = MSG_EVENT_BASE + 3;

    /** Triggers an immediate teardown of the entire Vcn, including GatewayConnections. */
    private static final int MSG_CMD_TEARDOWN = MSG_CMD_BASE;

    /**
     * Causes this VCN to immediately enter safe mode.
     *
     * <p>Upon entering safe mode, the VCN will unregister its RequestListener, tear down all of its
     * VcnGatewayConnections, and notify VcnManagementService that it is in safe mode.
     */
    private static final int MSG_CMD_ENTER_SAFE_MODE = MSG_CMD_BASE + 1;

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final ParcelUuid mSubscriptionGroup;
    @NonNull private final Dependencies mDeps;
    @NonNull private final VcnNetworkRequestListener mRequestListener;
    @NonNull private final VcnCallback mVcnCallback;

    @NonNull
    private final Map<VcnGatewayConnectionConfig, VcnGatewayConnection> mVcnGatewayConnections =
            new HashMap<>();

    @NonNull private VcnConfig mConfig;
    @NonNull private TelephonySubscriptionSnapshot mLastSnapshot;

    /**
     * Whether this Vcn instance is active and running.
     *
     * <p>The value will be {@code true} while running. It will be {@code false} if the VCN has been
     * shut down or has entered safe mode.
     *
     * <p>This AtomicBoolean is required in order to ensure consistency and correctness across
     * multiple threads. Unlike the rest of the Vcn, this is queried synchronously on Binder threads
     * from VcnManagementService, and therefore cannot rely on guarantees of running on the VCN
     * Looper.
     */
    // TODO(b/179429339): update when exiting safemode (when a new VcnConfig is provided)
    private final AtomicBoolean mIsActive = new AtomicBoolean(true);

    public Vcn(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnConfig config,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            @NonNull VcnCallback vcnCallback) {
        this(vcnContext, subscriptionGroup, config, snapshot, vcnCallback, new Dependencies());
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public Vcn(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnConfig config,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            @NonNull VcnCallback vcnCallback,
            @NonNull Dependencies deps) {
        super(Objects.requireNonNull(vcnContext, "Missing vcnContext").getLooper());
        mVcnContext = vcnContext;
        mSubscriptionGroup = Objects.requireNonNull(subscriptionGroup, "Missing subscriptionGroup");
        mVcnCallback = Objects.requireNonNull(vcnCallback, "Missing vcnCallback");
        mDeps = Objects.requireNonNull(deps, "Missing deps");
        mRequestListener = new VcnNetworkRequestListener();

        mConfig = Objects.requireNonNull(config, "Missing config");
        mLastSnapshot = Objects.requireNonNull(snapshot, "Missing snapshot");

        // Register to receive cached and future NetworkRequests
        mVcnContext.getVcnNetworkProvider().registerListener(mRequestListener);
    }

    /** Asynchronously updates the configuration and triggers a re-evaluation of Networks */
    public void updateConfig(@NonNull VcnConfig config) {
        Objects.requireNonNull(config, "Missing config");

        sendMessage(obtainMessage(MSG_EVENT_CONFIG_UPDATED, config));
    }

    /** Asynchronously updates the Subscription snapshot for this VCN. */
    public void updateSubscriptionSnapshot(@NonNull TelephonySubscriptionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Missing snapshot");

        sendMessage(obtainMessage(MSG_EVENT_SUBSCRIPTIONS_CHANGED, snapshot));
    }

    /** Asynchronously tears down this Vcn instance, including VcnGatewayConnection(s) */
    public void teardownAsynchronously() {
        sendMessageAtFrontOfQueue(obtainMessage(MSG_CMD_TEARDOWN));
    }

    /** Synchronously checks whether this Vcn is active. */
    public boolean isActive() {
        return mIsActive.get();
    }

    /** Get current Gateways for testing purposes */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public Set<VcnGatewayConnection> getVcnGatewayConnections() {
        return Collections.unmodifiableSet(new HashSet<>(mVcnGatewayConnections.values()));
    }

    private class VcnNetworkRequestListener implements VcnNetworkProvider.NetworkRequestListener {
        @Override
        public void onNetworkRequested(@NonNull NetworkRequest request, int score, int providerId) {
            Objects.requireNonNull(request, "Missing request");

            sendMessage(obtainMessage(MSG_EVENT_NETWORK_REQUESTED, score, providerId, request));
        }
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
        if (!isActive()) {
            return;
        }

        switch (msg.what) {
            case MSG_EVENT_CONFIG_UPDATED:
                handleConfigUpdated((VcnConfig) msg.obj);
                break;
            case MSG_EVENT_NETWORK_REQUESTED:
                handleNetworkRequested((NetworkRequest) msg.obj, msg.arg1, msg.arg2);
                break;
            case MSG_EVENT_SUBSCRIPTIONS_CHANGED:
                handleSubscriptionsChanged((TelephonySubscriptionSnapshot) msg.obj);
                break;
            case MSG_EVENT_GATEWAY_CONNECTION_QUIT:
                handleGatewayConnectionQuit((VcnGatewayConnectionConfig) msg.obj);
                break;
            case MSG_CMD_TEARDOWN:
                handleTeardown();
                break;
            case MSG_CMD_ENTER_SAFE_MODE:
                handleEnterSafeMode();
                break;
            default:
                Slog.wtf(getLogTag(), "Unknown msg.what: " + msg.what);
        }
    }

    private void handleConfigUpdated(@NonNull VcnConfig config) {
        // TODO: Add a dump function in VcnConfig that omits PII. Until then, use hashCode()
        Slog.v(getLogTag(), "Config updated: config = " + config.hashCode());

        mConfig = config;

        // TODO: Reevaluate active VcnGatewayConnection(s)
    }

    private void handleTeardown() {
        mVcnContext.getVcnNetworkProvider().unregisterListener(mRequestListener);

        for (VcnGatewayConnection gatewayConnection : mVcnGatewayConnections.values()) {
            gatewayConnection.teardownAsynchronously();
        }

        mIsActive.set(false);
    }

    private void handleEnterSafeMode() {
        handleTeardown();

        mVcnCallback.onEnteredSafeMode();
    }

    private void handleNetworkRequested(
            @NonNull NetworkRequest request, int score, int providerId) {
        if (score > getNetworkScore()) {
            if (VDBG) {
                Slog.v(
                        getLogTag(),
                        "Request already satisfied by higher-scoring ("
                                + score
                                + ") network from "
                                + "provider "
                                + providerId
                                + ": "
                                + request);
            }
            return;
        }

        // If preexisting VcnGatewayConnection(s) satisfy request, return
        for (VcnGatewayConnectionConfig gatewayConnectionConfig : mVcnGatewayConnections.keySet()) {
            if (isRequestSatisfiedByGatewayConnectionConfig(request, gatewayConnectionConfig)) {
                if (VDBG) {
                    Slog.v(
                            getLogTag(),
                            "Request already satisfied by existing VcnGatewayConnection: "
                                    + request);
                }
                return;
            }
        }

        // If any supported (but not running) VcnGatewayConnection(s) can satisfy request, bring it
        // up
        for (VcnGatewayConnectionConfig gatewayConnectionConfig :
                mConfig.getGatewayConnectionConfigs()) {
            if (isRequestSatisfiedByGatewayConnectionConfig(request, gatewayConnectionConfig)) {
                Slog.v(
                        getLogTag(),
                        "Bringing up new VcnGatewayConnection for request " + request.requestId);

                final VcnGatewayConnection vcnGatewayConnection =
                        mDeps.newVcnGatewayConnection(
                                mVcnContext,
                                mSubscriptionGroup,
                                mLastSnapshot,
                                gatewayConnectionConfig,
                                new VcnGatewayStatusCallbackImpl(gatewayConnectionConfig));
                mVcnGatewayConnections.put(gatewayConnectionConfig, vcnGatewayConnection);
            }
        }
    }

    private void handleGatewayConnectionQuit(VcnGatewayConnectionConfig config) {
        Slog.v(getLogTag(), "VcnGatewayConnection quit: " + config);
        mVcnGatewayConnections.remove(config);

        // Trigger a re-evaluation of all NetworkRequests (to make sure any that can be satisfied
        // start a new GatewayConnection)
        mVcnContext.getVcnNetworkProvider().resendAllRequests(mRequestListener);
    }

    private void handleSubscriptionsChanged(@NonNull TelephonySubscriptionSnapshot snapshot) {
        mLastSnapshot = snapshot;

        if (isActive()) {
            for (VcnGatewayConnection gatewayConnection : mVcnGatewayConnections.values()) {
                gatewayConnection.updateSubscriptionSnapshot(mLastSnapshot);
            }
        }
    }

    private boolean isRequestSatisfiedByGatewayConnectionConfig(
            @NonNull NetworkRequest request, @NonNull VcnGatewayConnectionConfig config) {
        final NetworkCapabilities.Builder builder = new NetworkCapabilities.Builder();
        builder.addCapability(NET_CAPABILITY_NOT_VCN_MANAGED);
        for (int cap : config.getAllExposedCapabilities()) {
            builder.addCapability(cap);
        }

        return request.canBeSatisfiedBy(builder.build());
    }

    private String getLogTag() {
        return TAG + " [" + mSubscriptionGroup.hashCode() + "]";
    }

    /** Retrieves the network score for a VCN Network */
    // Package visibility for use in VcnGatewayConnection
    static int getNetworkScore() {
        // TODO: STOPSHIP (b/173549607): Make this use new NetworkSelection, or some magic "max in
        //                               subGrp" value
        return 52;
    }

    /** Callback used for passing status signals from a VcnGatewayConnection to its managing Vcn. */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public interface VcnGatewayStatusCallback {
        /** Called by a VcnGatewayConnection to indicate that it has entered safe mode. */
        void onEnteredSafeMode();

        /** Callback by a VcnGatewayConnection to indicate that an error occurred. */
        void onGatewayConnectionError(
                @NonNull int[] networkCapabilities,
                @VcnErrorCode int errorCode,
                @Nullable String exceptionClass,
                @Nullable String exceptionMessage);

        /** Called by a VcnGatewayConnection to indicate that it has fully torn down. */
        void onQuit();
    }

    private class VcnGatewayStatusCallbackImpl implements VcnGatewayStatusCallback {
        public final VcnGatewayConnectionConfig mGatewayConnectionConfig;

        VcnGatewayStatusCallbackImpl(VcnGatewayConnectionConfig gatewayConnectionConfig) {
            mGatewayConnectionConfig = gatewayConnectionConfig;
        }

        @Override
        public void onQuit() {
            sendMessage(obtainMessage(MSG_EVENT_GATEWAY_CONNECTION_QUIT, mGatewayConnectionConfig));
        }

        @Override
        public void onEnteredSafeMode() {
            sendMessage(obtainMessage(MSG_CMD_ENTER_SAFE_MODE));
        }

        @Override
        public void onGatewayConnectionError(
                @NonNull int[] networkCapabilities,
                @VcnErrorCode int errorCode,
                @Nullable String exceptionClass,
                @Nullable String exceptionMessage) {
            mVcnCallback.onGatewayConnectionError(
                    networkCapabilities, errorCode, exceptionClass, exceptionMessage);
        }
    }

    /** External dependencies used by Vcn, for injection in tests */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        /** Builds a new VcnGatewayConnection */
        public VcnGatewayConnection newVcnGatewayConnection(
                VcnContext vcnContext,
                ParcelUuid subscriptionGroup,
                TelephonySubscriptionSnapshot snapshot,
                VcnGatewayConnectionConfig connectionConfig,
                VcnGatewayStatusCallback gatewayStatusCallback) {
            return new VcnGatewayConnection(
                    vcnContext,
                    subscriptionGroup,
                    snapshot,
                    connectionConfig,
                    gatewayStatusCallback);
        }
    }
}
