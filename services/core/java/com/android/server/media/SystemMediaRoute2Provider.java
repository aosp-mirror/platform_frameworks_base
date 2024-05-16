/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.media.MediaRouter2Utils;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.media.flags.Flags;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provides routes for local playbacks such as phone speaker, wired headset, or Bluetooth speakers.
 */
// TODO: check thread safety. We may need to use lock to protect variables.
class SystemMediaRoute2Provider extends MediaRoute2Provider {
    // Package-visible to use this tag for all system routing logic (done across multiple classes).
    /* package */ static final String TAG = "MR2SystemProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final ComponentName COMPONENT_NAME = new ComponentName(
            SystemMediaRoute2Provider.class.getPackage().getName(),
            SystemMediaRoute2Provider.class.getName());

    static final String SYSTEM_SESSION_ID = "SYSTEM_SESSION";

    private final AudioManager mAudioManager;
    private final Handler mHandler;
    private final Context mContext;
    private final UserHandle mUser;

    private final DeviceRouteController mDeviceRouteController;
    private final BluetoothRouteController mBluetoothRouteController;

    private String mSelectedRouteId;
    // For apps without MODIFYING_AUDIO_ROUTING permission.
    // This should be the currently selected route.
    MediaRoute2Info mDefaultRoute;
    RoutingSessionInfo mDefaultSessionInfo;

    private final AudioManagerBroadcastReceiver mAudioReceiver =
            new AudioManagerBroadcastReceiver();

    private final Object mRequestLock = new Object();

    @GuardedBy("mRequestLock")
    private volatile SessionCreationOrTransferRequest mPendingSessionCreationOrTransferRequest;

    private final Object mTransferLock = new Object();

    @GuardedBy("mTransferLock")
    @Nullable
    private volatile SessionCreationOrTransferRequest mPendingTransferRequest;

    SystemMediaRoute2Provider(Context context, UserHandle user, Looper looper) {
        super(COMPONENT_NAME);
        mIsSystemRouteProvider = true;
        mContext = context;
        mUser = user;
        mHandler = new Handler(looper);

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        mBluetoothRouteController =
                BluetoothRouteController.createInstance(
                        context,
                        () -> {
                            publishProviderState();
                            if (updateSessionInfosIfNeeded()) {
                                notifySessionInfoUpdated();
                            }
                        });

        mDeviceRouteController =
                DeviceRouteController.createInstance(
                        context,
                        looper,
                        () ->
                                mHandler.post(
                                        () -> {
                                            publishProviderState();
                                            if (updateSessionInfosIfNeeded()) {
                                                notifySessionInfoUpdated();
                                            }
                                        }));
        updateProviderState();
        updateSessionInfosIfNeeded();
    }

    public void start() {
        IntentFilter intentFilter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
        intentFilter.addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION);
        mContext.registerReceiverAsUser(mAudioReceiver, mUser,
                intentFilter, null, null);
        mHandler.post(
                () -> {
                    mDeviceRouteController.start(mUser);
                    mBluetoothRouteController.start(mUser);
                });
        updateVolume();
    }

    public void stop() {
        mContext.unregisterReceiver(mAudioReceiver);
        mHandler.post(
                () -> {
                    mBluetoothRouteController.stop();
                    mDeviceRouteController.stop();
                    notifyProviderState();
                });
    }

    @Override
    public void setCallback(Callback callback) {
        super.setCallback(callback);
        notifyProviderState();
        notifySessionInfoUpdated();
    }

    @Override
    public void requestCreateSession(
            long requestId,
            String packageName,
            String routeId,
            Bundle sessionHints,
            @RoutingSessionInfo.TransferReason int transferReason,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {
        // Assume a router without MODIFY_AUDIO_ROUTING permission can't request with
        // a route ID different from the default route ID. The service should've filtered.
        if (TextUtils.equals(routeId, MediaRoute2Info.ROUTE_ID_DEFAULT)) {
            mCallback.onSessionCreated(this, requestId, mDefaultSessionInfo);
            return;
        }

        if (!Flags.enableBuiltInSpeakerRouteSuitabilityStatuses()) {
            if (TextUtils.equals(routeId, mSelectedRouteId)) {
                RoutingSessionInfo currentSessionInfo;
                synchronized (mLock) {
                    currentSessionInfo = mSessionInfos.get(0);
                }
                mCallback.onSessionCreated(this, requestId, currentSessionInfo);
                return;
            }
        }

        synchronized (mRequestLock) {
            // Handle the previous request as a failure if exists.
            if (mPendingSessionCreationOrTransferRequest != null) {
                mCallback.onRequestFailed(
                        /* provider= */ this,
                        mPendingSessionCreationOrTransferRequest.mRequestId,
                        MediaRoute2ProviderService.REASON_UNKNOWN_ERROR);
            }
            mPendingSessionCreationOrTransferRequest =
                    new SessionCreationOrTransferRequest(
                            requestId,
                            routeId,
                            RoutingSessionInfo.TRANSFER_REASON_FALLBACK,
                            transferInitiatorUserHandle,
                            transferInitiatorPackageName);
        }

        // Only unprivileged routers call this method, therefore we use TRANSFER_REASON_APP.
        transferToRoute(
                requestId,
                transferInitiatorUserHandle,
                transferInitiatorPackageName,
                SYSTEM_SESSION_ID,
                routeId,
                transferReason);
    }

    @Override
    public void releaseSession(long requestId, String sessionId) {
        // Do nothing
    }

    @Override
    public void updateDiscoveryPreference(
            Set<String> activelyScanningPackages, RouteDiscoveryPreference discoveryPreference) {
        // Do nothing
    }

    @Override
    public void selectRoute(long requestId, String sessionId, String routeId) {
        // Do nothing since we don't support multiple BT yet.
    }

    @Override
    public void deselectRoute(long requestId, String sessionId, String routeId) {
        // Do nothing since we don't support multiple BT yet.
    }

    @Override
    public void transferToRoute(
            long requestId,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName,
            String sessionId,
            String routeId,
            @RoutingSessionInfo.TransferReason int transferReason) {
        String selectedDeviceRouteId = mDeviceRouteController.getSelectedRoute().getId();
        if (TextUtils.equals(routeId, MediaRoute2Info.ROUTE_ID_DEFAULT)) {
            if (Flags.enableBuiltInSpeakerRouteSuitabilityStatuses()) {
                // Transfer to the default route (which is the selected route). We replace the id to
                // be the selected route id so that the transfer reason gets updated.
                routeId = selectedDeviceRouteId;
            } else {
                Log.w(TAG, "Ignoring transfer to " + MediaRoute2Info.ROUTE_ID_DEFAULT);
                return;
            }
        }

        if (Flags.enableBuiltInSpeakerRouteSuitabilityStatuses()) {
            synchronized (mTransferLock) {
                mPendingTransferRequest =
                        new SessionCreationOrTransferRequest(
                                requestId,
                                routeId,
                                transferReason,
                                transferInitiatorUserHandle,
                                transferInitiatorPackageName);
            }
        }

        String finalRouteId = routeId; // Make a final copy to use it in the lambda.
        boolean isAvailableDeviceRoute =
                mDeviceRouteController.getAvailableRoutes().stream()
                        .anyMatch(it -> it.getId().equals(finalRouteId));
        boolean isSelectedDeviceRoute = TextUtils.equals(routeId, selectedDeviceRouteId);

        if (isSelectedDeviceRoute || isAvailableDeviceRoute) {
            // The requested route is managed by the device route controller. Note that the selected
            // device route doesn't necessarily match mSelectedRouteId (which is the selected route
            // of the routing session). If the selected device route is transferred to, we need to
            // make the bluetooth routes inactive so that the device route becomes the selected
            // route of the routing session.
            mDeviceRouteController.transferTo(routeId);
            mBluetoothRouteController.transferTo(null);
        } else {
            // The requested route is managed by the bluetooth route controller.
            mDeviceRouteController.transferTo(null);
            mBluetoothRouteController.transferTo(routeId);
        }

        if (Flags.enableBuiltInSpeakerRouteSuitabilityStatuses()
                && updateSessionInfosIfNeeded()) {
            notifySessionInfoUpdated();
        }
    }

    @Override
    public void setRouteVolume(long requestId, String routeId, int volume) {
        if (!TextUtils.equals(routeId, mSelectedRouteId)) {
            return;
        }
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
    }

    @Override
    public void setSessionVolume(long requestId, String sessionId, int volume) {
        // Do nothing since we don't support grouping volume yet.
    }

    @Override
    public void prepareReleaseSession(String sessionId) {
        // Do nothing since the system session persists.
    }

    public MediaRoute2Info getDefaultRoute() {
        return mDefaultRoute;
    }

    public RoutingSessionInfo getDefaultSessionInfo() {
        return mDefaultSessionInfo;
    }

    /**
     * Builds a system {@link RoutingSessionInfo} with the selected route set to the currently
     * selected <b>device</b> route (wired or built-in, but not bluetooth) and transferable routes
     * set to the currently available (connected) bluetooth routes.
     *
     * <p>The session's client package name is set to the provided package name.
     *
     * <p>Returns {@code null} if there are no registered system sessions.
     */
    @Nullable
    public RoutingSessionInfo generateDeviceRouteSelectedSessionInfo(String packageName) {
        synchronized (mLock) {
            if (mSessionInfos.isEmpty()) {
                return null;
            }

            MediaRoute2Info selectedDeviceRoute = mDeviceRouteController.getSelectedRoute();

            RoutingSessionInfo.Builder builder =
                    new RoutingSessionInfo.Builder(SYSTEM_SESSION_ID, packageName)
                            .setSystemSession(true);
            builder.addSelectedRoute(selectedDeviceRoute.getId());
            for (MediaRoute2Info route : mBluetoothRouteController.getAllBluetoothRoutes()) {
                builder.addTransferableRoute(route.getId());
            }

            if (Flags.enableAudioPoliciesDeviceAndBluetoothController()) {
                for (MediaRoute2Info route : mDeviceRouteController.getAvailableRoutes()) {
                    if (!TextUtils.equals(selectedDeviceRoute.getId(), route.getId())) {
                        builder.addTransferableRoute(route.getId());
                    }
                }
            }

            if (Flags.enableBuiltInSpeakerRouteSuitabilityStatuses()) {
                RoutingSessionInfo oldSessionInfo = mSessionInfos.get(0);
                builder.setTransferReason(oldSessionInfo.getTransferReason())
                        .setTransferInitiator(oldSessionInfo.getTransferInitiatorUserHandle(),
                                oldSessionInfo.getTransferInitiatorPackageName());
            }

            return builder.setProviderId(mUniqueId).build();
        }
    }

    private void updateProviderState() {
        MediaRoute2ProviderInfo.Builder builder = new MediaRoute2ProviderInfo.Builder();

        // We must have a device route in the provider info.
        if (Flags.enableAudioPoliciesDeviceAndBluetoothController()) {
            List<MediaRoute2Info> deviceRoutes = mDeviceRouteController.getAvailableRoutes();
            for (MediaRoute2Info route : deviceRoutes) {
                builder.addRoute(route);
            }
            setProviderState(builder.build());
        } else {
            builder.addRoute(mDeviceRouteController.getSelectedRoute());
        }

        for (MediaRoute2Info route : mBluetoothRouteController.getAllBluetoothRoutes()) {
            builder.addRoute(route);
        }
        MediaRoute2ProviderInfo providerInfo = builder.build();
        setProviderState(providerInfo);
        if (DEBUG) {
            Slog.d(TAG, "Updating system provider info : " + providerInfo);
        }
    }

    /**
     * Updates the mSessionInfo. Returns true if the session info is changed.
     */
    boolean updateSessionInfosIfNeeded() {
        synchronized (mLock) {
            RoutingSessionInfo oldSessionInfo = mSessionInfos.isEmpty() ? null : mSessionInfos.get(
                    0);

            RoutingSessionInfo.Builder builder = new RoutingSessionInfo.Builder(
                    SYSTEM_SESSION_ID, "" /* clientPackageName */)
                    .setSystemSession(true);

            MediaRoute2Info selectedDeviceRoute = mDeviceRouteController.getSelectedRoute();
            MediaRoute2Info selectedRoute = selectedDeviceRoute;
            MediaRoute2Info selectedBtRoute = mBluetoothRouteController.getSelectedRoute();
            List<String> transferableRoutes = new ArrayList<>();

            if (selectedBtRoute != null) {
                selectedRoute = selectedBtRoute;
                transferableRoutes.add(selectedDeviceRoute.getId());
            }
            mSelectedRouteId = selectedRoute.getId();
            mDefaultRoute =
                    new MediaRoute2Info.Builder(MediaRoute2Info.ROUTE_ID_DEFAULT, selectedRoute)
                            .setSystemRoute(true)
                            .setProviderId(mUniqueId)
                            .build();
            builder.addSelectedRoute(mSelectedRouteId);
            if (Flags.enableAudioPoliciesDeviceAndBluetoothController()) {
                for (MediaRoute2Info route : mDeviceRouteController.getAvailableRoutes()) {
                    String routeId = route.getId();
                    if (!mSelectedRouteId.equals(routeId)) {
                        transferableRoutes.add(routeId);
                    }
                }
            }
            for (MediaRoute2Info route : mBluetoothRouteController.getTransferableRoutes()) {
                transferableRoutes.add(route.getId());
            }

            for (String route : transferableRoutes) {
                builder.addTransferableRoute(route);
            }

            if (Flags.enableBuiltInSpeakerRouteSuitabilityStatuses()) {
                int transferReason = RoutingSessionInfo.TRANSFER_REASON_FALLBACK;
                UserHandle transferInitiatorUserHandle = null;
                String transferInitiatorPackageName = null;

                if (oldSessionInfo != null
                        && containsSelectedRouteWithId(oldSessionInfo, selectedRoute.getId())) {
                    transferReason = oldSessionInfo.getTransferReason();
                    transferInitiatorUserHandle = oldSessionInfo.getTransferInitiatorUserHandle();
                    transferInitiatorPackageName = oldSessionInfo.getTransferInitiatorPackageName();
                }

                synchronized (mTransferLock) {
                    if (mPendingTransferRequest != null) {
                        boolean isTransferringToTheSelectedRoute =
                                mPendingTransferRequest.isTargetRoute(selectedRoute);
                        boolean canBePotentiallyTransferred =
                                mPendingTransferRequest.isTargetRouteIdInList(transferableRoutes);

                        if (isTransferringToTheSelectedRoute) {
                            transferReason = mPendingTransferRequest.mTransferReason;
                            transferInitiatorUserHandle =
                                    mPendingTransferRequest.mTransferInitiatorUserHandle;
                            transferInitiatorPackageName =
                                    mPendingTransferRequest.mTransferInitiatorPackageName;

                            mPendingTransferRequest = null;
                        } else if (!canBePotentiallyTransferred) {
                            mPendingTransferRequest = null;
                        }
                    }
                }

                builder.setTransferReason(transferReason)
                        .setTransferInitiator(
                                transferInitiatorUserHandle, transferInitiatorPackageName);
            }

            RoutingSessionInfo newSessionInfo = builder.setProviderId(mUniqueId).build();

            synchronized (mRequestLock) {
                reportPendingSessionRequestResultLockedIfNeeded(newSessionInfo);
            }

            if (Objects.equals(oldSessionInfo, newSessionInfo)) {
                return false;
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Updating system routing session info : " + newSessionInfo);
                }
                mSessionInfos.clear();
                mSessionInfos.add(newSessionInfo);
                mDefaultSessionInfo =
                        new RoutingSessionInfo.Builder(
                                        SYSTEM_SESSION_ID, "" /* clientPackageName */)
                                .setProviderId(mUniqueId)
                                .setSystemSession(true)
                                .addSelectedRoute(MediaRoute2Info.ROUTE_ID_DEFAULT)
                                .setTransferReason(newSessionInfo.getTransferReason())
                                .setTransferInitiator(
                                        newSessionInfo.getTransferInitiatorUserHandle(),
                                        newSessionInfo.getTransferInitiatorPackageName())
                                .build();
                return true;
            }
        }
    }

    @GuardedBy("mRequestLock")
    private void reportPendingSessionRequestResultLockedIfNeeded(
            RoutingSessionInfo newSessionInfo) {
        if (mPendingSessionCreationOrTransferRequest == null) {
            // No pending request, nothing to report.
            return;
        }

        long pendingRequestId = mPendingSessionCreationOrTransferRequest.mRequestId;
        if (mPendingSessionCreationOrTransferRequest.mTargetRouteId.equals(mSelectedRouteId)) {
            if (DEBUG) {
                Slog.w(
                        TAG,
                        "Session creation success to route "
                                + mPendingSessionCreationOrTransferRequest.mTargetRouteId);
            }
            mPendingSessionCreationOrTransferRequest = null;
            mCallback.onSessionCreated(this, pendingRequestId, newSessionInfo);
        } else {
            boolean isRequestedRouteConnectedBtRoute = isRequestedRouteConnectedBtRoute();
            if (!Flags.enableWaitingStateForSystemSessionCreationRequest()
                    || !isRequestedRouteConnectedBtRoute) {
                if (DEBUG) {
                    Slog.w(
                            TAG,
                            "Session creation failed to route "
                                    + mPendingSessionCreationOrTransferRequest.mTargetRouteId);
                }
                mPendingSessionCreationOrTransferRequest = null;
                mCallback.onRequestFailed(
                        this, pendingRequestId, MediaRoute2ProviderService.REASON_UNKNOWN_ERROR);
            } else if (DEBUG) {
                Slog.w(
                        TAG,
                        "Session creation waiting state to route "
                                + mPendingSessionCreationOrTransferRequest.mTargetRouteId);
            }
        }
    }

    @GuardedBy("mRequestLock")
    private boolean isRequestedRouteConnectedBtRoute() {
        // Using AllRoutes instead of TransferableRoutes as BT Stack sends an intermediate update
        // where two BT routes are active so the transferable routes list is empty.
        // See b/307723189 for context
        for (MediaRoute2Info btRoute : mBluetoothRouteController.getAllBluetoothRoutes()) {
            if (TextUtils.equals(
                    btRoute.getId(), mPendingSessionCreationOrTransferRequest.mTargetRouteId)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSelectedRouteWithId(
            @Nullable RoutingSessionInfo sessionInfo, @NonNull String selectedRouteId) {
        if (sessionInfo == null) {
            return false;
        }

        List<String> selectedRoutes = sessionInfo.getSelectedRoutes();

        if (selectedRoutes.size() != 1) {
            throw new IllegalStateException("Selected routes list should contain only 1 route id.");
        }

        String oldSelectedRouteId = MediaRouter2Utils.getOriginalId(selectedRoutes.get(0));
        return oldSelectedRouteId != null && oldSelectedRouteId.equals(selectedRouteId);
    }

    void publishProviderState() {
        updateProviderState();
        notifyProviderState();
    }

    void notifySessionInfoUpdated() {
        if (mCallback == null) {
            return;
        }

        RoutingSessionInfo sessionInfo;
        synchronized (mLock) {
            sessionInfo = mSessionInfos.get(0);
        }

        mCallback.onSessionUpdated(this, sessionInfo);
    }

    @Override
    protected String getDebugString() {
        return TextUtils.formatSimple(
                "SystemMR2Provider - package: %s, selected route id: %s, bluetooth impl: %s",
                mComponentName.getPackageName(),
                mSelectedRouteId,
                mBluetoothRouteController.getClass().getSimpleName());
    }

    void updateVolume() {
        int devices = mAudioManager.getDevicesForStream(AudioManager.STREAM_MUSIC);
        int volume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        if (mDefaultRoute.getVolume() != volume) {
            mDefaultRoute = new MediaRoute2Info.Builder(mDefaultRoute)
                    .setVolume(volume)
                    .build();
        }

        if (mBluetoothRouteController.updateVolumeForDevices(devices, volume)) {
            return;
        }

        mDeviceRouteController.updateVolume(volume);

        publishProviderState();
    }

    private class AudioManagerBroadcastReceiver extends BroadcastReceiver {
        // This will be called in the main thread.
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.getAction().equals(AudioManager.VOLUME_CHANGED_ACTION)
                    && !intent.getAction().equals(AudioManager.STREAM_DEVICES_CHANGED_ACTION)) {
                return;
            }

            int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
            if (streamType != AudioManager.STREAM_MUSIC) {
                return;
            }

            if (Flags.enableMr2ServiceNonMainBgThread()) {
                mHandler.post(SystemMediaRoute2Provider.this::updateVolume);
            } else {
                updateVolume();
            }
        }
    }
}
