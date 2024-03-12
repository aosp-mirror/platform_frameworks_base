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
    private volatile SessionCreationRequest mPendingSessionCreationRequest;

    SystemMediaRoute2Provider(Context context, UserHandle user) {
        super(COMPONENT_NAME);
        mIsSystemRouteProvider = true;
        mContext = context;
        mUser = user;
        Looper looper = Looper.getMainLooper();
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
    public void requestCreateSession(long requestId, String packageName, String routeId,
            Bundle sessionHints) {
        // Assume a router without MODIFY_AUDIO_ROUTING permission can't request with
        // a route ID different from the default route ID. The service should've filtered.
        if (TextUtils.equals(routeId, MediaRoute2Info.ROUTE_ID_DEFAULT)) {
            mCallback.onSessionCreated(this, requestId, mDefaultSessionInfo);
            return;
        }
        if (TextUtils.equals(routeId, mSelectedRouteId)) {
            mCallback.onSessionCreated(this, requestId, mSessionInfos.get(0));
            return;
        }

        synchronized (mRequestLock) {
            // Handle the previous request as a failure if exists.
            if (mPendingSessionCreationRequest != null) {
                mCallback.onRequestFailed(this, mPendingSessionCreationRequest.mRequestId,
                        MediaRoute2ProviderService.REASON_UNKNOWN_ERROR);
            }
            mPendingSessionCreationRequest = new SessionCreationRequest(requestId, routeId);
        }

        transferToRoute(requestId, SYSTEM_SESSION_ID, routeId);
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
    public void transferToRoute(long requestId, String sessionId, String routeId) {
        if (TextUtils.equals(routeId, MediaRoute2Info.ROUTE_ID_DEFAULT)) {
            // The currently selected route is the default route.
            Log.w(TAG, "Ignoring transfer to " + MediaRoute2Info.ROUTE_ID_DEFAULT);
            return;
        }
        MediaRoute2Info selectedDeviceRoute = mDeviceRouteController.getSelectedRoute();
        boolean isAvailableDeviceRoute =
                mDeviceRouteController.getAvailableRoutes().stream()
                        .anyMatch(it -> it.getId().equals(routeId));
        boolean isSelectedDeviceRoute = TextUtils.equals(routeId, selectedDeviceRoute.getId());

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
            if (selectedBtRoute != null) {
                selectedRoute = selectedBtRoute;
                builder.addTransferableRoute(selectedDeviceRoute.getId());
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
                        builder.addTransferableRoute(routeId);
                    }
                }
            }
            for (MediaRoute2Info route : mBluetoothRouteController.getTransferableRoutes()) {
                builder.addTransferableRoute(route.getId());
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
                                .build();
                return true;
            }
        }
    }

    @GuardedBy("mRequestLock")
    private void reportPendingSessionRequestResultLockedIfNeeded(
            RoutingSessionInfo newSessionInfo) {
        if (mPendingSessionCreationRequest == null) {
            // No pending request, nothing to report.
            return;
        }

        long pendingRequestId = mPendingSessionCreationRequest.mRequestId;
        if (TextUtils.equals(mSelectedRouteId, mPendingSessionCreationRequest.mRouteId)) {
            if (DEBUG) {
                Slog.w(
                        TAG,
                        "Session creation success to route "
                                + mPendingSessionCreationRequest.mRouteId);
            }
            mPendingSessionCreationRequest = null;
            mCallback.onSessionCreated(this, pendingRequestId, newSessionInfo);
        } else {
            boolean isRequestedRouteConnectedBtRoute = isRequestedRouteConnectedBtRoute();
            if (!Flags.enableWaitingStateForSystemSessionCreationRequest()
                    || !isRequestedRouteConnectedBtRoute) {
                if (DEBUG) {
                    Slog.w(
                            TAG,
                            "Session creation failed to route "
                                    + mPendingSessionCreationRequest.mRouteId);
                }
                mPendingSessionCreationRequest = null;
                mCallback.onRequestFailed(
                        this, pendingRequestId, MediaRoute2ProviderService.REASON_UNKNOWN_ERROR);
            } else if (DEBUG) {
                Slog.w(
                        TAG,
                        "Session creation waiting state to route "
                                + mPendingSessionCreationRequest.mRouteId);
            }
        }
    }

    @GuardedBy("mRequestLock")
    private boolean isRequestedRouteConnectedBtRoute() {
        // Using AllRoutes instead of TransferableRoutes as BT Stack sends an intermediate update
        // where two BT routes are active so the transferable routes list is empty.
        // See b/307723189 for context
        for (MediaRoute2Info btRoute : mBluetoothRouteController.getAllBluetoothRoutes()) {
            if (TextUtils.equals(btRoute.getId(), mPendingSessionCreationRequest.mRouteId)) {
                return true;
            }
        }
        return false;
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

    private static class SessionCreationRequest {
        final long mRequestId;
        final String mRouteId;

        SessionCreationRequest(long requestId, String routeId) {
            this.mRequestId = requestId;
            this.mRouteId = routeId;
        }
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

            updateVolume();
        }
    }
}
