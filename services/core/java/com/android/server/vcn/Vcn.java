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


import android.annotation.NonNull;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.vcn.VcnConfig;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.util.Slog;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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

    /** Triggers an immediate teardown of the entire Vcn, including GatewayConnections. */
    private static final int MSG_CMD_TEARDOWN = MSG_CMD_BASE;

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final ParcelUuid mSubscriptionGroup;
    @NonNull private final Dependencies mDeps;
    @NonNull private final VcnNetworkRequestListener mRequestListener;

    @NonNull
    private final Map<VcnGatewayConnectionConfig, VcnGatewayConnection> mVcnGatewayConnections =
            new HashMap<>();

    @NonNull private VcnConfig mConfig;

    private boolean mIsRunning = true;

    public Vcn(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnConfig config) {
        this(vcnContext, subscriptionGroup, config, new Dependencies());
    }

    private Vcn(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnConfig config,
            @NonNull Dependencies deps) {
        super(Objects.requireNonNull(vcnContext, "Missing vcnContext").getLooper());
        mVcnContext = vcnContext;
        mSubscriptionGroup = Objects.requireNonNull(subscriptionGroup, "Missing subscriptionGroup");
        mDeps = Objects.requireNonNull(deps, "Missing deps");
        mRequestListener = new VcnNetworkRequestListener();

        mConfig = Objects.requireNonNull(config, "Missing config");

        // Register to receive cached and future NetworkRequests
        mVcnContext.getVcnNetworkProvider().registerListener(mRequestListener);
    }

    /** Asynchronously updates the configuration and triggers a re-evaluation of Networks */
    public void updateConfig(@NonNull VcnConfig config) {
        Objects.requireNonNull(config, "Missing config");

        sendMessage(obtainMessage(MSG_EVENT_CONFIG_UPDATED, config));
    }

    /** Asynchronously tears down this Vcn instance, including VcnGatewayConnection(s) */
    public void teardownAsynchronously() {
        sendMessageAtFrontOfQueue(obtainMessage(MSG_CMD_TEARDOWN));
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
        if (!mIsRunning) {
            return;
        }

        switch (msg.what) {
            case MSG_EVENT_CONFIG_UPDATED:
                handleConfigUpdated((VcnConfig) msg.obj);
                break;
            case MSG_EVENT_NETWORK_REQUESTED:
                handleNetworkRequested((NetworkRequest) msg.obj, msg.arg1, msg.arg2);
                break;
            case MSG_CMD_TEARDOWN:
                handleTeardown();
                break;
            default:
                Slog.wtf(getLogTag(), "Unknown msg.what: " + msg.what);
        }
    }

    private void handleConfigUpdated(@NonNull VcnConfig config) {
        // TODO: Add a dump function in VcnConfig that omits PII. Until then, use hashCode()
        Slog.v(getLogTag(), String.format("Config updated: config = %s", config.hashCode()));

        mConfig = config;

        // TODO: Reevaluate active VcnGatewayConnection(s)
    }

    private void handleTeardown() {
        mVcnContext.getVcnNetworkProvider().unregisterListener(mRequestListener);

        for (VcnGatewayConnection gatewayConnection : mVcnGatewayConnections.values()) {
            gatewayConnection.teardownAsynchronously();
        }

        mIsRunning = false;
    }

    private void handleNetworkRequested(
            @NonNull NetworkRequest request, int score, int providerId) {
        if (score > getNetworkScore()) {
            Slog.v(getLogTag(),
                    "Request already satisfied by higher-scoring (" + score + ") network from "
                            + "provider " + providerId + ": " + request);
            return;
        }

        // If preexisting VcnGatewayConnection(s) satisfy request, return
        for (VcnGatewayConnectionConfig gatewayConnectionConfig : mVcnGatewayConnections.keySet()) {
            if (requestSatisfiedByGatewayConnectionConfig(request, gatewayConnectionConfig)) {
                Slog.v(getLogTag(),
                        "Request already satisfied by existing VcnGatewayConnection: " + request);
                return;
            }
        }

        // If any supported (but not running) VcnGatewayConnection(s) can satisfy request, bring it
        // up
        for (VcnGatewayConnectionConfig gatewayConnectionConfig :
                mConfig.getGatewayConnectionConfigs()) {
            if (requestSatisfiedByGatewayConnectionConfig(request, gatewayConnectionConfig)) {
                Slog.v(
                        getLogTag(),
                        "Bringing up new VcnGatewayConnection for request " + request.requestId);

                final VcnGatewayConnection vcnGatewayConnection =
                        new VcnGatewayConnection(
                                mVcnContext, mSubscriptionGroup, gatewayConnectionConfig);
                mVcnGatewayConnections.put(gatewayConnectionConfig, vcnGatewayConnection);
            }
        }
    }

    private boolean requestSatisfiedByGatewayConnectionConfig(
            @NonNull NetworkRequest request, @NonNull VcnGatewayConnectionConfig config) {
        final NetworkCapabilities.Builder builder = new NetworkCapabilities.Builder();
        for (int cap : config.getAllExposedCapabilities()) {
            builder.addCapability(cap);
        }

        return request.canBeSatisfiedBy(builder.build());
    }

    private String getLogTag() {
        return String.format("%s [%d]", TAG, mSubscriptionGroup.hashCode());
    }

    /** Retrieves the network score for a VCN Network */
    // Package visibility for use in VcnGatewayConnection
    static int getNetworkScore() {
        // TODO: STOPSHIP (b/173549607): Make this use new NetworkSelection, or some magic "max in
        //                               subGrp" value
        return 52;
    }

    private static class Dependencies {}
}
