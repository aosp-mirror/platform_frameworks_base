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

import java.util.Objects;

/**
 * Provides routes for local playbacks such as phone speaker, wired headset, or Bluetooth speakers.
 */
// TODO: check thread safety. We may need to use lock to protect variables.
class SystemMediaRoute2Provider extends MediaRoute2Provider {
    private static final String TAG = "MR2SystemProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final ComponentName COMPONENT_NAME = new ComponentName(
            SystemMediaRoute2Provider.class.getPackage().getName(),
            SystemMediaRoute2Provider.class.getName());

    static final String DEFAULT_ROUTE_ID = "DEFAULT_ROUTE";
    static final String SYSTEM_SESSION_ID = "SYSTEM_SESSION";

    private final AudioManager mAudioManager;
    private final Handler mHandler;
    private final Context mContext;
    private final UserHandle mUser;

    private final DeviceRouteController mDeviceRouteController;
    private final BluetoothRouteController mBtRouteProvider;

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
        mHandler = new Handler(Looper.getMainLooper());

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        mBtRouteProvider = BluetoothRouteController.createInstance(context, (routes) -> {
            publishProviderState();
            if (updateSessionInfosIfNeeded()) {
                notifySessionInfoUpdated();
            }
        });

        mDeviceRouteController = DeviceRouteController.createInstance(context, (deviceRoute) -> {
            mHandler.post(() -> {
                publishProviderState();
                if (updateSessionInfosIfNeeded()) {
                    notifySessionInfoUpdated();
                }
            });
        });

        // These methods below should be called after all fields are initialized, as they
        // access the fields inside.
        updateProviderState();
        updateSessionInfosIfNeeded();
    }

    public void start() {
        IntentFilter intentFilter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
        intentFilter.addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION);
        mContext.registerReceiverAsUser(mAudioReceiver, mUser,
                intentFilter, null, null);

        mHandler.post(() -> {
            mBtRouteProvider.start(mUser);
            notifyProviderState();
        });
        updateVolume();
    }

    public void stop() {
        mContext.unregisterReceiver(mAudioReceiver);
        mHandler.post(() -> {
            mBtRouteProvider.stop();
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
        if (TextUtils.equals(routeId, DEFAULT_ROUTE_ID)) {
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
    public void updateDiscoveryPreference(RouteDiscoveryPreference discoveryPreference) {
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
        if (TextUtils.equals(routeId, DEFAULT_ROUTE_ID)) {
            // The currently selected route is the default route.
            return;
        }

        MediaRoute2Info deviceRoute = mDeviceRouteController.getDeviceRoute();
        if (TextUtils.equals(routeId, deviceRoute.getId())) {
            mBtRouteProvider.transferTo(null);
        } else {
            mBtRouteProvider.transferTo(routeId);
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

    public RoutingSessionInfo generateDeviceRouteSelectedSessionInfo(String packageName) {
        synchronized (mLock) {
            if (mSessionInfos.isEmpty()) {
                return null;
            }

            MediaRoute2Info deviceRoute = mDeviceRouteController.getDeviceRoute();

            RoutingSessionInfo.Builder builder = new RoutingSessionInfo.Builder(
                    SYSTEM_SESSION_ID, packageName).setSystemSession(true);
            builder.addSelectedRoute(deviceRoute.getId());
            for (MediaRoute2Info route : mBtRouteProvider.getAllBluetoothRoutes()) {
                builder.addTransferableRoute(route.getId());
            }
            return builder.setProviderId(mUniqueId).build();
        }
    }

    private void updateProviderState() {
        MediaRoute2ProviderInfo.Builder builder = new MediaRoute2ProviderInfo.Builder();

        // We must have a device route in the provider info.
        builder.addRoute(mDeviceRouteController.getDeviceRoute());

        for (MediaRoute2Info route : mBtRouteProvider.getAllBluetoothRoutes()) {
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

            MediaRoute2Info deviceRoute = mDeviceRouteController.getDeviceRoute();
            MediaRoute2Info selectedRoute = deviceRoute;
            MediaRoute2Info selectedBtRoute = mBtRouteProvider.getSelectedRoute();
            if (selectedBtRoute != null) {
                selectedRoute = selectedBtRoute;
                builder.addTransferableRoute(deviceRoute.getId());
            }
            mSelectedRouteId = selectedRoute.getId();
            mDefaultRoute = new MediaRoute2Info.Builder(DEFAULT_ROUTE_ID, selectedRoute)
                    .setSystemRoute(true)
                    .setProviderId(mUniqueId)
                    .build();
            builder.addSelectedRoute(mSelectedRouteId);

            for (MediaRoute2Info route : mBtRouteProvider.getTransferableRoutes()) {
                builder.addTransferableRoute(route.getId());
            }

            RoutingSessionInfo newSessionInfo = builder.setProviderId(mUniqueId).build();

            if (mPendingSessionCreationRequest != null) {
                SessionCreationRequest sessionCreationRequest;
                synchronized (mRequestLock) {
                    sessionCreationRequest = mPendingSessionCreationRequest;
                    mPendingSessionCreationRequest = null;
                }
                if (sessionCreationRequest != null) {
                    if (TextUtils.equals(mSelectedRouteId, sessionCreationRequest.mRouteId)) {
                        mCallback.onSessionCreated(this,
                                sessionCreationRequest.mRequestId, newSessionInfo);
                    } else {
                        mCallback.onRequestFailed(this, sessionCreationRequest.mRequestId,
                                MediaRoute2ProviderService.REASON_UNKNOWN_ERROR);
                    }
                }
            }

            if (Objects.equals(oldSessionInfo, newSessionInfo)) {
                return false;
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Updating system routing session info : " + newSessionInfo);
                }
                mSessionInfos.clear();
                mSessionInfos.add(newSessionInfo);
                mDefaultSessionInfo = new RoutingSessionInfo.Builder(
                        SYSTEM_SESSION_ID, "" /* clientPackageName */)
                        .setProviderId(mUniqueId)
                        .setSystemSession(true)
                        .addSelectedRoute(DEFAULT_ROUTE_ID)
                        .build();
                return true;
            }
        }
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

        if (mBtRouteProvider.updateVolumeForDevices(devices, volume)) {
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
