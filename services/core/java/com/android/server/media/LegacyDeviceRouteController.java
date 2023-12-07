/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static android.media.MediaRoute2Info.FEATURE_LOCAL_PLAYBACK;
import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_DOCK;
import static android.media.MediaRoute2Info.TYPE_HDMI;
import static android.media.MediaRoute2Info.TYPE_USB_DEVICE;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRoutesInfo;
import android.media.IAudioRoutesObserver;
import android.media.IAudioService;
import android.media.MediaRoute2Info;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.R;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Controls device routes.
 *
 * <p>A device route is a system wired route, for example, built-in speaker, wired
 * headsets and headphones, dock, hdmi, or usb devices.
 *
 * <p>Thread safe.
 *
 * @see SystemMediaRoute2Provider
 */
/* package */ final class LegacyDeviceRouteController implements DeviceRouteController {

    private static final String TAG = "LDeviceRouteController";

    private static final String DEVICE_ROUTE_ID = "DEVICE_ROUTE";

    @NonNull
    private final Context mContext;
    @NonNull
    private final AudioManager mAudioManager;
    @NonNull
    private final IAudioService mAudioService;

    @NonNull
    private final OnDeviceRouteChangedListener mOnDeviceRouteChangedListener;
    @NonNull
    private final AudioRoutesObserver mAudioRoutesObserver = new AudioRoutesObserver();

    private int mDeviceVolume;
    private MediaRoute2Info mDeviceRoute;

    /* package */ LegacyDeviceRouteController(@NonNull Context context,
            @NonNull AudioManager audioManager,
            @NonNull IAudioService audioService,
            @NonNull OnDeviceRouteChangedListener onDeviceRouteChangedListener) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(audioManager);
        Objects.requireNonNull(audioService);
        Objects.requireNonNull(onDeviceRouteChangedListener);

        mContext = context;
        mOnDeviceRouteChangedListener = onDeviceRouteChangedListener;

        mAudioManager = audioManager;
        mAudioService = audioService;

        AudioRoutesInfo newAudioRoutes = null;
        try {
            newAudioRoutes = mAudioService.startWatchingRoutes(mAudioRoutesObserver);
        } catch (RemoteException e) {
            Slog.w(TAG, "Cannot connect to audio service to start listen to routes", e);
        }

        mDeviceRoute = createRouteFromAudioInfo(newAudioRoutes);
    }

    @Override
    public void start(UserHandle mUser) {
        // Nothing to do.
    }

    @Override
    public void stop() {
        // Nothing to do.
    }

    @Override
    @NonNull
    public synchronized MediaRoute2Info getSelectedRoute() {
        return mDeviceRoute;
    }

    @Override
    public synchronized List<MediaRoute2Info> getAvailableRoutes() {
        return Collections.emptyList();
    }

    @Override
    public synchronized void transferTo(@Nullable String routeId) {
        // Unsupported. This implementation doesn't support transferable routes (always exposes a
        // single non-bluetooth route).
    }

    @Override
    public synchronized boolean updateVolume(int volume) {
        if (mDeviceVolume == volume) {
            return false;
        }

        mDeviceVolume = volume;
        mDeviceRoute = new MediaRoute2Info.Builder(mDeviceRoute)
                .setVolume(volume)
                .build();

        return true;
    }

    private MediaRoute2Info createRouteFromAudioInfo(@Nullable AudioRoutesInfo newRoutes) {
        int name = R.string.default_audio_route_name;
        int type = TYPE_BUILTIN_SPEAKER;

        if (newRoutes != null) {
            if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HEADPHONES) != 0) {
                type = TYPE_WIRED_HEADPHONES;
                name = com.android.internal.R.string.default_audio_route_name_headphones;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HEADSET) != 0) {
                type = TYPE_WIRED_HEADSET;
                name = com.android.internal.R.string.default_audio_route_name_headphones;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_DOCK_SPEAKERS) != 0) {
                type = TYPE_DOCK;
                name = com.android.internal.R.string.default_audio_route_name_dock_speakers;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HDMI) != 0) {
                type = TYPE_HDMI;
                name = com.android.internal.R.string.default_audio_route_name_external_device;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_USB) != 0) {
                type = TYPE_USB_DEVICE;
                name = com.android.internal.R.string.default_audio_route_name_usb;
            }
        }

        synchronized (this) {
            return new MediaRoute2Info.Builder(
                    DEVICE_ROUTE_ID, mContext.getResources().getText(name).toString())
                    .setVolumeHandling(mAudioManager.isVolumeFixed()
                            ? MediaRoute2Info.PLAYBACK_VOLUME_FIXED
                            : MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                    .setVolume(mDeviceVolume)
                    .setVolumeMax(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                    .setType(type)
                    .addFeature(FEATURE_LIVE_AUDIO)
                    .addFeature(FEATURE_LIVE_VIDEO)
                    .addFeature(FEATURE_LOCAL_PLAYBACK)
                    .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
                    .build();
        }
    }

    private void notifyDeviceRouteUpdate() {
        mOnDeviceRouteChangedListener.onDeviceRouteChanged();
    }

    private class AudioRoutesObserver extends IAudioRoutesObserver.Stub {

        @Override
        public void dispatchAudioRoutesChanged(AudioRoutesInfo newAudioRoutes) {
            MediaRoute2Info deviceRoute = createRouteFromAudioInfo(newAudioRoutes);
            synchronized (LegacyDeviceRouteController.this) {
                mDeviceRoute = deviceRoute;
            }
            notifyDeviceRouteUpdate();
        }
    }

}
