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

import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_ACTIVE;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_INACTIVE;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_SAFE_MODE;

import static com.android.server.VcnManagementService.LOCAL_LOG;
import static com.android.server.VcnManagementService.VDBG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.Uri;
import android.net.vcn.VcnConfig;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnManager.VcnErrorCode;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.VcnManagementService.VcnCallback;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.util.LogUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

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

    private static final int VCN_LEGACY_SCORE_INT = 52;

    private static final List<Integer> CAPS_REQUIRING_MOBILE_DATA =
            Arrays.asList(NET_CAPABILITY_INTERNET, NET_CAPABILITY_DUN);

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

    /**
     * Triggers reevaluation of safe mode conditions.
     *
     * <p>Upon entering safe mode, the VCN will only provide gateway connections opportunistically,
     * leaving the underlying networks marked as NOT_VCN_MANAGED.
     *
     * <p>Any VcnGatewayConnection in safe mode will result in the entire Vcn instance being put
     * into safe mode. Upon receiving this message, the Vcn MUST query all VcnGatewayConnections to
     * determine if any are in safe mode.
     */
    private static final int MSG_EVENT_SAFE_MODE_STATE_CHANGED = MSG_EVENT_BASE + 4;

    /**
     * Triggers reevaluation of mobile data enabled conditions.
     *
     * <p>Upon this notification, the VCN will check if any of the underlying subIds have mobile
     * data enabled. If not, the VCN will restart any GatewayConnections providing INTERNET or DUN
     * with the current mobile data toggle status.
     */
    private static final int MSG_EVENT_MOBILE_DATA_TOGGLED = MSG_EVENT_BASE + 5;

    /** Triggers an immediate teardown of the entire Vcn, including GatewayConnections. */
    private static final int MSG_CMD_TEARDOWN = MSG_CMD_BASE;

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final ParcelUuid mSubscriptionGroup;
    @NonNull private final Dependencies mDeps;
    @NonNull private final VcnNetworkRequestListener mRequestListener;
    @NonNull private final VcnCallback mVcnCallback;
    @NonNull private final VcnContentResolver mContentResolver;
    @NonNull private final ContentObserver mMobileDataSettingsObserver;

    /**
     * Map containing all VcnGatewayConnections and their VcnGatewayConnectionConfigs.
     *
     * <p>Due to potential for race conditions, VcnGatewayConnections MUST only be created and added
     * to this map in {@link #handleNetworkRequested(NetworkRequest, int, int)}, when a VCN receives
     * a NetworkRequest that matches a VcnGatewayConnectionConfig for this VCN's VcnConfig.
     *
     * <p>A VcnGatewayConnection instance MUST NEVER overwrite an existing instance - otherwise
     * there is potential for a orphaned VcnGatewayConnection instance that does not get properly
     * shut down.
     *
     * <p>Due to potential for race conditions, VcnGatewayConnections MUST only be removed from this
     * map once they have finished tearing down, which is reported to this VCN via {@link
     * VcnGatewayStatusCallback#onQuit()}. Once this is done, all NetworkRequests are retrieved from
     * the NetworkProvider so that another VcnGatewayConnectionConfig can match the
     * previously-matched request.
     */
    // TODO(b/182533200): remove the invariant on VcnGatewayConnection lifecycles
    @NonNull
    private final Map<VcnGatewayConnectionConfig, VcnGatewayConnection> mVcnGatewayConnections =
            new HashMap<>();

    @NonNull private VcnConfig mConfig;
    @NonNull private TelephonySubscriptionSnapshot mLastSnapshot;

    /**
     * The current status of this Vcn instance
     *
     * <p>The value will be {@link VCN_STATUS_CODE_ACTIVE} while all VcnGatewayConnections are in
     * good standing, {@link VCN_STATUS_CODE_SAFE_MODE} if any VcnGatewayConnections are in safe
     * mode, and {@link VCN_STATUS_CODE_INACTIVE} once a teardown has been commanded.
     */
    // Accessed from different threads, but always under lock in VcnManagementService
    private volatile int mCurrentStatus = VCN_STATUS_CODE_ACTIVE;

    private boolean mIsMobileDataEnabled = false;

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
        mContentResolver = mDeps.newVcnContentResolver(mVcnContext);
        mMobileDataSettingsObserver = new VcnMobileDataContentObserver(this /* handler */);

        final Uri uri = Settings.Global.getUriFor(Settings.Global.MOBILE_DATA);
        mContentResolver.registerContentObserver(
                uri, true /* notifyForDescendants */, mMobileDataSettingsObserver);

        mConfig = Objects.requireNonNull(config, "Missing config");
        mLastSnapshot = Objects.requireNonNull(snapshot, "Missing snapshot");

        // Update mIsMobileDataEnabled before starting handling of NetworkRequests.
        mIsMobileDataEnabled = getMobileDataStatus();

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

    /** Synchronously retrieves the current status code. */
    public int getStatus() {
        return mCurrentStatus;
    }

    /** Sets the status of this VCN */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public void setStatus(int status) {
        mCurrentStatus = status;
    }

    /** Get current Gateways for testing purposes */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public Set<VcnGatewayConnection> getVcnGatewayConnections() {
        return Collections.unmodifiableSet(new HashSet<>(mVcnGatewayConnections.values()));
    }

    /** Get current Configs and Gateways for testing purposes */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public Map<VcnGatewayConnectionConfig, VcnGatewayConnection>
            getVcnGatewayConnectionConfigMap() {
        return Collections.unmodifiableMap(new HashMap<>(mVcnGatewayConnections));
    }

    private class VcnNetworkRequestListener implements VcnNetworkProvider.NetworkRequestListener {
        @Override
        public void onNetworkRequested(@NonNull NetworkRequest request) {
            Objects.requireNonNull(request, "Missing request");

            sendMessage(obtainMessage(MSG_EVENT_NETWORK_REQUESTED, request));
        }
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
        if (mCurrentStatus != VCN_STATUS_CODE_ACTIVE
                && mCurrentStatus != VCN_STATUS_CODE_SAFE_MODE) {
            return;
        }

        switch (msg.what) {
            case MSG_EVENT_CONFIG_UPDATED:
                handleConfigUpdated((VcnConfig) msg.obj);
                break;
            case MSG_EVENT_NETWORK_REQUESTED:
                handleNetworkRequested((NetworkRequest) msg.obj);
                break;
            case MSG_EVENT_SUBSCRIPTIONS_CHANGED:
                handleSubscriptionsChanged((TelephonySubscriptionSnapshot) msg.obj);
                break;
            case MSG_EVENT_GATEWAY_CONNECTION_QUIT:
                handleGatewayConnectionQuit((VcnGatewayConnectionConfig) msg.obj);
                break;
            case MSG_EVENT_SAFE_MODE_STATE_CHANGED:
                handleSafeModeStatusChanged();
                break;
            case MSG_EVENT_MOBILE_DATA_TOGGLED:
                handleMobileDataToggled();
                break;
            case MSG_CMD_TEARDOWN:
                handleTeardown();
                break;
            default:
                logWtf("Unknown msg.what: " + msg.what);
        }
    }

    private void handleConfigUpdated(@NonNull VcnConfig config) {
        // TODO: Add a dump function in VcnConfig that omits PII. Until then, use hashCode()
        logDbg("Config updated: old = " + mConfig.hashCode() + "; new = " + config.hashCode());

        mConfig = config;

        // Teardown any GatewayConnections whose configs have been removed and get all current
        // requests
        for (final Entry<VcnGatewayConnectionConfig, VcnGatewayConnection> entry :
                mVcnGatewayConnections.entrySet()) {
            final VcnGatewayConnectionConfig gatewayConnectionConfig = entry.getKey();
            final VcnGatewayConnection gatewayConnection = entry.getValue();

            // GatewayConnectionConfigs must match exactly (otherwise authentication or
            // connection details may have changed).
            if (!mConfig.getGatewayConnectionConfigs().contains(gatewayConnectionConfig)) {
                if (gatewayConnection == null) {
                    logWtf("Found gatewayConnectionConfig without GatewayConnection");
                } else {
                    gatewayConnection.teardownAsynchronously();
                }
            }
        }

        // Trigger a re-evaluation of all NetworkRequests (to make sure any that can be
        // satisfied start a new GatewayConnection)
        mVcnContext.getVcnNetworkProvider().resendAllRequests(mRequestListener);
    }

    private void handleTeardown() {
        logDbg("Tearing down");
        mVcnContext.getVcnNetworkProvider().unregisterListener(mRequestListener);

        for (VcnGatewayConnection gatewayConnection : mVcnGatewayConnections.values()) {
            gatewayConnection.teardownAsynchronously();
        }

        mCurrentStatus = VCN_STATUS_CODE_INACTIVE;
    }

    private void handleSafeModeStatusChanged() {
        logDbg("VcnGatewayConnection safe mode status changed");
        boolean hasSafeModeGatewayConnection = false;

        // If any VcnGatewayConnection is in safe mode, mark the entire VCN as being in safe mode
        for (VcnGatewayConnection gatewayConnection : mVcnGatewayConnections.values()) {
            if (gatewayConnection.isInSafeMode()) {
                hasSafeModeGatewayConnection = true;
                break;
            }
        }

        final int oldStatus = mCurrentStatus;
        mCurrentStatus =
                hasSafeModeGatewayConnection ? VCN_STATUS_CODE_SAFE_MODE : VCN_STATUS_CODE_ACTIVE;
        if (oldStatus != mCurrentStatus) {
            mVcnCallback.onSafeModeStatusChanged(hasSafeModeGatewayConnection);
            logDbg(
                    "Safe mode "
                            + (mCurrentStatus == VCN_STATUS_CODE_SAFE_MODE ? "entered" : "exited"));
        }
    }

    private void handleNetworkRequested(@NonNull NetworkRequest request) {
        logVdbg("Received request " + request);

        // If preexisting VcnGatewayConnection(s) satisfy request, return
        for (VcnGatewayConnectionConfig gatewayConnectionConfig : mVcnGatewayConnections.keySet()) {
            if (isRequestSatisfiedByGatewayConnectionConfig(request, gatewayConnectionConfig)) {
                logDbg("Request already satisfied by existing VcnGatewayConnection: " + request);
                return;
            }
        }

        // If any supported (but not running) VcnGatewayConnection(s) can satisfy request, bring it
        // up
        for (VcnGatewayConnectionConfig gatewayConnectionConfig :
                mConfig.getGatewayConnectionConfigs()) {
            if (isRequestSatisfiedByGatewayConnectionConfig(request, gatewayConnectionConfig)) {
                logDbg("Bringing up new VcnGatewayConnection for request " + request);

                if (getExposedCapabilitiesForMobileDataState(gatewayConnectionConfig).isEmpty()) {
                    // Skip; this network does not provide any services if mobile data is disabled.
                    continue;
                }

                // This should never happen, by virtue of checking for the above check for
                // pre-existing VcnGatewayConnections that satisfy a given request, but if state
                // that affects the satsifying of requests changes, this is theoretically possible.
                if (mVcnGatewayConnections.containsKey(gatewayConnectionConfig)) {
                    logWtf(
                            "Attempted to bring up VcnGatewayConnection for config "
                                    + "with existing VcnGatewayConnection");
                    return;
                }

                final VcnGatewayConnection vcnGatewayConnection =
                        mDeps.newVcnGatewayConnection(
                                mVcnContext,
                                mSubscriptionGroup,
                                mLastSnapshot,
                                gatewayConnectionConfig,
                                new VcnGatewayStatusCallbackImpl(gatewayConnectionConfig),
                                mIsMobileDataEnabled);
                mVcnGatewayConnections.put(gatewayConnectionConfig, vcnGatewayConnection);

                return;
            }
        }

        logVdbg("Request could not be fulfilled by VCN: " + request);
    }

    private Set<Integer> getExposedCapabilitiesForMobileDataState(
            VcnGatewayConnectionConfig gatewayConnectionConfig) {
        if (mIsMobileDataEnabled) {
            return gatewayConnectionConfig.getAllExposedCapabilities();
        }

        final Set<Integer> exposedCapsWithoutMobileData =
                new ArraySet<>(gatewayConnectionConfig.getAllExposedCapabilities());
        exposedCapsWithoutMobileData.removeAll(CAPS_REQUIRING_MOBILE_DATA);

        return exposedCapsWithoutMobileData;
    }

    private void handleGatewayConnectionQuit(VcnGatewayConnectionConfig config) {
        logDbg("VcnGatewayConnection quit: " + config);
        mVcnGatewayConnections.remove(config);

        // Trigger a re-evaluation of all NetworkRequests (to make sure any that can be satisfied
        // start a new GatewayConnection). VCN is always alive here, courtesy of the liveness check
        // in handleMessage()
        mVcnContext.getVcnNetworkProvider().resendAllRequests(mRequestListener);
    }

    private void handleSubscriptionsChanged(@NonNull TelephonySubscriptionSnapshot snapshot) {
        mLastSnapshot = snapshot;

        for (VcnGatewayConnection gatewayConnection : mVcnGatewayConnections.values()) {
            gatewayConnection.updateSubscriptionSnapshot(mLastSnapshot);
        }

        // Update the mobile data state after updating the subscription snapshot as a change in
        // subIds for a subGroup may affect the mobile data state.
        handleMobileDataToggled();
    }

    private void handleMobileDataToggled() {
        final boolean oldMobileDataEnabledStatus = mIsMobileDataEnabled;
        mIsMobileDataEnabled = getMobileDataStatus();

        if (oldMobileDataEnabledStatus != mIsMobileDataEnabled) {
            // Teardown any GatewayConnections that advertise INTERNET or DUN. If they provide other
            // services, the VcnGatewayConnections will be restarted without advertising INTERNET or
            // DUN.
            for (Entry<VcnGatewayConnectionConfig, VcnGatewayConnection> entry :
                    mVcnGatewayConnections.entrySet()) {
                final VcnGatewayConnectionConfig gatewayConnectionConfig = entry.getKey();
                final VcnGatewayConnection gatewayConnection = entry.getValue();

                final Set<Integer> exposedCaps =
                        gatewayConnectionConfig.getAllExposedCapabilities();
                if (exposedCaps.contains(NET_CAPABILITY_INTERNET)
                        || exposedCaps.contains(NET_CAPABILITY_DUN)) {
                    if (gatewayConnection == null) {
                        logWtf("Found gatewayConnectionConfig without" + " GatewayConnection");
                    } else {
                        // TODO(b/184868850): Optimize by restarting NetworkAgents without teardown.
                        gatewayConnection.teardownAsynchronously();
                    }
                }
            }

            // Trigger re-evaluation of all requests; mobile data state impacts supported caps.
            mVcnContext.getVcnNetworkProvider().resendAllRequests(mRequestListener);

            logDbg("Mobile data " + (mIsMobileDataEnabled ? "enabled" : "disabled"));
        }
    }

    private boolean getMobileDataStatus() {
        final TelephonyManager genericTelMan =
                mVcnContext.getContext().getSystemService(TelephonyManager.class);

        for (int subId : mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup)) {
            if (genericTelMan.createForSubscriptionId(subId).isDataEnabled()) {
                return true;
            }
        }

        return false;
    }

    private boolean isRequestSatisfiedByGatewayConnectionConfig(
            @NonNull NetworkRequest request, @NonNull VcnGatewayConnectionConfig config) {
        final NetworkCapabilities.Builder builder = new NetworkCapabilities.Builder();
        builder.addTransportType(TRANSPORT_CELLULAR);
        builder.addCapability(NET_CAPABILITY_NOT_VCN_MANAGED);
        for (int cap : getExposedCapabilitiesForMobileDataState(config)) {
            builder.addCapability(cap);
        }

        return request.canBeSatisfiedBy(builder.build());
    }

    private String getLogPrefix() {
        return "["
                + LogUtils.getHashedSubscriptionGroup(mSubscriptionGroup)
                + "-"
                + System.identityHashCode(this)
                + "] ";
    }

    private void logVdbg(String msg) {
        if (VDBG) {
            Slog.v(TAG, getLogPrefix() + msg);
        }
    }

    private void logDbg(String msg) {
        Slog.d(TAG, getLogPrefix() + msg);
    }

    private void logDbg(String msg, Throwable tr) {
        Slog.d(TAG, getLogPrefix() + msg, tr);
    }

    private void logErr(String msg) {
        Slog.e(TAG, getLogPrefix() + msg);
        LOCAL_LOG.log(getLogPrefix() + "ERR: " + msg);
    }

    private void logErr(String msg, Throwable tr) {
        Slog.e(TAG, getLogPrefix() + msg, tr);
        LOCAL_LOG.log(getLogPrefix() + "ERR: " + msg + tr);
    }

    private void logWtf(String msg) {
        Slog.wtf(TAG, getLogPrefix() + msg);
        LOCAL_LOG.log(getLogPrefix() + "WTF: " + msg);
    }

    private void logWtf(String msg, Throwable tr) {
        Slog.wtf(TAG, getLogPrefix() + msg, tr);
        LOCAL_LOG.log(getLogPrefix() + "WTF: " + msg + tr);
    }

    /**
     * Dumps the state of this Vcn for logging and debugging purposes.
     *
     * <p>PII and credentials MUST NEVER be dumped here.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println("Vcn (" + mSubscriptionGroup + "):");
        pw.increaseIndent();

        pw.println("mCurrentStatus: " + mCurrentStatus);
        pw.println("mIsMobileDataEnabled: " + mIsMobileDataEnabled);
        pw.println();

        pw.println("mVcnGatewayConnections:");
        pw.increaseIndent();
        for (VcnGatewayConnection gw : mVcnGatewayConnections.values()) {
            gw.dump(pw);
        }
        pw.decreaseIndent();
        pw.println();

        pw.decreaseIndent();
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public boolean isMobileDataEnabled() {
        return mIsMobileDataEnabled;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public void setMobileDataEnabled(boolean isMobileDataEnabled) {
        mIsMobileDataEnabled = isMobileDataEnabled;
    }

    /** Retrieves the network score for a VCN Network */
    // Package visibility for use in VcnGatewayConnection and VcnNetworkProvider
    static NetworkScore getNetworkScore() {
        // TODO(b/193687515): Stop setting TRANSPORT_PRIMARY, define a TRANSPORT_VCN, and set in
        //                    NetworkOffer/NetworkAgent.
        return new NetworkScore.Builder()
                .setLegacyInt(VCN_LEGACY_SCORE_INT)
                .setTransportPrimary(true)
                .build();
    }

    /** Callback used for passing status signals from a VcnGatewayConnection to its managing Vcn. */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public interface VcnGatewayStatusCallback {
        /** Called by a VcnGatewayConnection to indicate that it's safe mode status has changed. */
        void onSafeModeStatusChanged();

        /** Callback by a VcnGatewayConnection to indicate that an error occurred. */
        void onGatewayConnectionError(
                @NonNull String gatewayConnectionName,
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
        public void onSafeModeStatusChanged() {
            sendMessage(obtainMessage(MSG_EVENT_SAFE_MODE_STATE_CHANGED));
        }

        @Override
        public void onGatewayConnectionError(
                @NonNull String gatewayConnectionName,
                @VcnErrorCode int errorCode,
                @Nullable String exceptionClass,
                @Nullable String exceptionMessage) {
            mVcnCallback.onGatewayConnectionError(
                    gatewayConnectionName, errorCode, exceptionClass, exceptionMessage);
        }
    }

    private class VcnMobileDataContentObserver extends ContentObserver {
        private VcnMobileDataContentObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            sendMessage(obtainMessage(MSG_EVENT_MOBILE_DATA_TOGGLED));
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
                VcnGatewayStatusCallback gatewayStatusCallback,
                boolean isMobileDataEnabled) {
            return new VcnGatewayConnection(
                    vcnContext,
                    subscriptionGroup,
                    snapshot,
                    connectionConfig,
                    gatewayStatusCallback,
                    isMobileDataEnabled);
        }

        /** Builds a new VcnContentResolver instance */
        public VcnContentResolver newVcnContentResolver(VcnContext vcnContext) {
            return new VcnContentResolver(vcnContext);
        }
    }

    /** Proxy Implementation of NetworkAgent, used for testing. */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class VcnContentResolver {
        private final ContentResolver mImpl;

        public VcnContentResolver(VcnContext vcnContext) {
            mImpl = vcnContext.getContext().getContentResolver();
        }

        /** Registers the content observer */
        public void registerContentObserver(
                @NonNull Uri uri, boolean notifyForDescendants, @NonNull ContentObserver observer) {
            mImpl.registerContentObserver(uri, notifyForDescendants, observer);
        }
    }
}
