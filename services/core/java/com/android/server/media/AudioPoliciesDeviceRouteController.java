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
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/* package */ final class AudioPoliciesDeviceRouteController implements DeviceRouteController {

    private static final String TAG = "APDeviceRoutesController";

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

    @NonNull
    private MediaRoute2Info mDeviceRoute;
    @Nullable
    private MediaRoute2Info mSelectedRoute;

    @VisibleForTesting
    /* package */ AudioPoliciesDeviceRouteController(@NonNull Context context,
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
    public synchronized boolean selectRoute(@Nullable Integer type) {
        if (type == null) {
            mSelectedRoute = null;
            return true;
        }

        if (!isDeviceRouteType(type)) {
            return false;
        }

        mSelectedRoute = createRouteFromAudioInfo(type);
        return true;
    }

    @Override
    @NonNull
    public synchronized MediaRoute2Info getDeviceRoute() {
        if (mSelectedRoute != null) {
            return mSelectedRoute;
        }
        return mDeviceRoute;
    }

    @Override
    public synchronized boolean updateVolume(int volume) {
        if (mDeviceVolume == volume) {
            return false;
        }

        mDeviceVolume = volume;

        if (mSelectedRoute != null) {
            mSelectedRoute = new MediaRoute2Info.Builder(mSelectedRoute)
                    .setVolume(volume)
                    .build();
        }

        mDeviceRoute = new MediaRoute2Info.Builder(mDeviceRoute)
                .setVolume(volume)
                .build();

        return true;
    }

    @NonNull
    private MediaRoute2Info createRouteFromAudioInfo(@Nullable AudioRoutesInfo newRoutes) {
        int type = TYPE_BUILTIN_SPEAKER;

        if (newRoutes != null) {
            if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HEADPHONES) != 0) {
                type = TYPE_WIRED_HEADPHONES;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HEADSET) != 0) {
                type = TYPE_WIRED_HEADSET;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_DOCK_SPEAKERS) != 0) {
                type = TYPE_DOCK;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HDMI) != 0) {
                type = TYPE_HDMI;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_USB) != 0) {
                type = TYPE_USB_DEVICE;
            }
        }

        return createRouteFromAudioInfo(type);
    }

    @NonNull
    private MediaRoute2Info createRouteFromAudioInfo(@MediaRoute2Info.Type int type) {
        int name = R.string.default_audio_route_name;

        switch (type) {
            case TYPE_WIRED_HEADPHONES:
            case TYPE_WIRED_HEADSET:
                name = R.string.default_audio_route_name_headphones;
                break;
            case TYPE_DOCK:
                name = R.string.default_audio_route_name_dock_speakers;
                break;
            case TYPE_HDMI:
                name = R.string.default_audio_route_name_external_device;
                break;
            case TYPE_USB_DEVICE:
                name = R.string.default_audio_route_name_usb;
                break;
        }

        synchronized (this) {
            return new MediaRoute2Info.Builder(
                            MediaRoute2Info.ROUTE_ID_DEVICE,
                            mContext.getResources().getText(name).toString())
                    .setVolumeHandling(
                            mAudioManager.isVolumeFixed()
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

    /**
     * Checks if the given type is a device route.
     *
     * <p>Device route means a route which is either built-in or wired to the current device.
     *
     * @param type specifies the type of the device.
     * @return {@code true} if the device is wired or built-in and {@code false} otherwise.
     */
    private boolean isDeviceRouteType(@MediaRoute2Info.Type int type) {
        switch (type) {
            case TYPE_BUILTIN_SPEAKER:
            case TYPE_WIRED_HEADPHONES:
            case TYPE_WIRED_HEADSET:
            case TYPE_DOCK:
            case TYPE_HDMI:
            case TYPE_USB_DEVICE:
                return true;
            default:
                return false;
        }
    }

    private class AudioRoutesObserver extends IAudioRoutesObserver.Stub {

        @Override
        public void dispatchAudioRoutesChanged(AudioRoutesInfo newAudioRoutes) {
            boolean isDeviceRouteChanged;
            MediaRoute2Info deviceRoute = createRouteFromAudioInfo(newAudioRoutes);

            synchronized (AudioPoliciesDeviceRouteController.this) {
                mDeviceRoute = deviceRoute;
                isDeviceRouteChanged = mSelectedRoute == null;
            }

            if (isDeviceRouteChanged) {
                mOnDeviceRouteChangedListener.onDeviceRouteChanged(deviceRoute);
            }
        }
    }

}
