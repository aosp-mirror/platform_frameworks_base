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

import static android.media.MediaRoute2Info.FEATURE_LIVE_AUDIO;
import static android.media.MediaRoute2Info.FEATURE_LIVE_VIDEO;
import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.AudioRoutesInfo;
import android.media.IAudioRoutesObserver;
import android.media.IAudioService;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import java.util.Objects;

/**
 * Provides routes for local playbacks such as phone speaker, wired headset, or Bluetooth speakers.
 */
// TODO: check thread safety. We may need to use lock to protect variables.
class SystemMediaRoute2Provider extends MediaRoute2Provider {
    private static final String TAG = "MR2SystemProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final String DEFAULT_ROUTE_ID = "DEFAULT_ROUTE";
    static final String DEVICE_ROUTE_ID = "DEVICE_ROUTE";
    static final String SYSTEM_SESSION_ID = "SYSTEM_SESSION";

    private final AudioManager mAudioManager;
    private final IAudioService mAudioService;
    private final Handler mHandler;
    private final Context mContext;
    private final BluetoothRouteProvider mBtRouteProvider;

    private static ComponentName sComponentName = new ComponentName(
            SystemMediaRoute2Provider.class.getPackage().getName(),
            SystemMediaRoute2Provider.class.getName());

    private String mSelectedRouteId;
    // For apps without MODIFYING_AUDIO_ROUTING permission.
    // This should be the currently selected route.
    MediaRoute2Info mDefaultRoute;
    MediaRoute2Info mDeviceRoute;
    final AudioRoutesInfo mCurAudioRoutesInfo = new AudioRoutesInfo();

    final IAudioRoutesObserver.Stub mAudioRoutesObserver = new IAudioRoutesObserver.Stub() {
        @Override
        public void dispatchAudioRoutesChanged(final AudioRoutesInfo newRoutes) {
            mHandler.post(() -> {
                updateDeviceRoute(newRoutes);
                notifyProviderState();
            });
        }
    };

    SystemMediaRoute2Provider(Context context, Callback callback) {
        super(sComponentName);
        setCallback(callback);

        mIsSystemRouteProvider = true;

        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAudioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));
        AudioRoutesInfo newAudioRoutes = null;
        try {
            newAudioRoutes = mAudioService.startWatchingRoutes(mAudioRoutesObserver);
        } catch (RemoteException e) {
        }
        updateDeviceRoute(newAudioRoutes);

        mBtRouteProvider = BluetoothRouteProvider.getInstance(context, (routes) -> {
            publishProviderState();

            boolean sessionInfoChanged;
            sessionInfoChanged = updateSessionInfosIfNeeded();
            if (sessionInfoChanged) {
                notifySessionInfoUpdated();
            }
        });
        updateSessionInfosIfNeeded();
        mContext.registerReceiver(new VolumeChangeReceiver(),
                new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION));

        mHandler.post(() -> {
            mBtRouteProvider.start();
            notifyProviderState();
        });
    }

    @Override
    public void requestCreateSession(long requestId, String packageName, String routeId,
            Bundle sessionHints) {

        transferToRoute(requestId, SYSTEM_SESSION_ID, routeId);
        mCallback.onSessionCreated(this, requestId, mSessionInfos.get(0));
        //TODO: We should call after the session info is changed.
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
        if (TextUtils.equals(routeId, mDeviceRoute.getId())) {
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

    public MediaRoute2Info getDefaultRoute() {
        return mDefaultRoute;
    }

    private void updateDeviceRoute(AudioRoutesInfo newRoutes) {
        int name = R.string.default_audio_route_name;
        if (newRoutes != null) {
            mCurAudioRoutesInfo.mainType = newRoutes.mainType;
            if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HEADPHONES) != 0
                    || (newRoutes.mainType & AudioRoutesInfo.MAIN_HEADSET) != 0) {
                name = com.android.internal.R.string.default_audio_route_name_headphones;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_DOCK_SPEAKERS) != 0) {
                name = com.android.internal.R.string.default_audio_route_name_dock_speakers;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HDMI) != 0) {
                name = com.android.internal.R.string.default_audio_route_name_hdmi;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_USB) != 0) {
                name = com.android.internal.R.string.default_audio_route_name_usb;
            }
        }
        mDeviceRoute = new MediaRoute2Info.Builder(
                DEVICE_ROUTE_ID, mContext.getResources().getText(name).toString())
                .setVolumeHandling(mAudioManager.isVolumeFixed()
                        ? MediaRoute2Info.PLAYBACK_VOLUME_FIXED
                        : MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .setVolume(mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                //TODO: Guess the exact type using AudioDevice
                .setType(TYPE_BUILTIN_SPEAKER)
                .addFeature(FEATURE_LIVE_AUDIO)
                .addFeature(FEATURE_LIVE_VIDEO)
                .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
                .build();
        updateProviderState();
    }

    private void updateProviderState() {
        MediaRoute2ProviderInfo.Builder builder = new MediaRoute2ProviderInfo.Builder();
        builder.addRoute(mDeviceRoute);
        if (mBtRouteProvider != null) {
            for (MediaRoute2Info route : mBtRouteProvider.getAllBluetoothRoutes()) {
                builder.addRoute(route);
            }
        }
        setProviderState(builder.build());
    }

    /**
     * Updates the mSessionInfo. Returns true if the session info is changed.
     */
    boolean updateSessionInfosIfNeeded() {
        synchronized (mLock) {
            // Prevent to execute this method before mBtRouteProvider is created.
            if (mBtRouteProvider == null) return false;
            RoutingSessionInfo oldSessionInfo = mSessionInfos.isEmpty() ? null : mSessionInfos.get(
                    0);

            RoutingSessionInfo.Builder builder = new RoutingSessionInfo.Builder(
                    SYSTEM_SESSION_ID, "" /* clientPackageName */)
                    .setSystemSession(true);

            MediaRoute2Info selectedRoute = mBtRouteProvider.getSelectedRoute();
            if (selectedRoute == null) {
                selectedRoute = mDeviceRoute;
            } else {
                builder.addTransferableRoute(mDeviceRoute.getId());
            }
            mSelectedRouteId = selectedRoute.getId();
            mDefaultRoute = new MediaRoute2Info.Builder(DEFAULT_ROUTE_ID, selectedRoute).build();
            builder.addSelectedRoute(mSelectedRouteId);

            for (MediaRoute2Info route : mBtRouteProvider.getTransferableRoutes()) {
                builder.addTransferableRoute(route.getId());
            }

            RoutingSessionInfo newSessionInfo = builder.setProviderId(mUniqueId).build();
            if (Objects.equals(oldSessionInfo, newSessionInfo)) {
                return false;
            } else {
                mSessionInfos.clear();
                mSessionInfos.add(newSessionInfo);
                return true;
            }
        }
    }

    void publishProviderState() {
        updateProviderState();
        notifyProviderState();
    }

    void notifySessionInfoUpdated() {
        RoutingSessionInfo sessionInfo;
        synchronized (mLock) {
            sessionInfo = mSessionInfos.get(0);
        }

        mCallback.onSessionUpdated(this, sessionInfo);
    }

    private class VolumeChangeReceiver extends BroadcastReceiver {
        // This will be called in the main thread.
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.getAction().equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                return;
            }

            final int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
            if (streamType != AudioManager.STREAM_MUSIC) {
                return;
            }

            final int newVolume = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0);
            final int oldVolume = intent.getIntExtra(
                    AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 0);

            if (newVolume != oldVolume) {
                if (TextUtils.equals(mDeviceRoute.getId(), mSelectedRouteId)) {
                    mDeviceRoute = new MediaRoute2Info.Builder(mDeviceRoute)
                            .setVolume(newVolume)
                            .build();
                } else {
                    mBtRouteProvider.setSelectedRouteVolume(newVolume);
                }
                publishProviderState();
            }
        }
    }
}
