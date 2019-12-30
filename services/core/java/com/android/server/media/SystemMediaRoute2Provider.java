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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioRoutesInfo;
import android.media.IAudioRoutesObserver;
import android.media.IAudioService;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

/**
 * Provides routes for local playbacks such as phone speaker, wired headset, or Bluetooth speakers.
 */
class SystemMediaRoute2Provider extends MediaRoute2Provider {
    private static final String TAG = "MR2SystemProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final String DEFAULT_ROUTE_ID = "DEFAULT_ROUTE";
    static final String BLUETOOTH_ROUTE_ID = "BLUETOOTH_ROUTE";

    // TODO: Move these to a proper place
    public static final String CATEGORY_LIVE_AUDIO = "android.media.intent.category.LIVE_AUDIO";
    public static final String CATEGORY_LIVE_VIDEO = "android.media.intent.category.LIVE_VIDEO";

    private final AudioManager mAudioManager;
    private final IAudioService mAudioService;
    private final Handler mHandler;
    private final Context mContext;

    private static ComponentName sComponentName = new ComponentName(
            SystemMediaRoute2Provider.class.getPackageName$(),
            SystemMediaRoute2Provider.class.getName());

    //TODO: Clean up these when audio manager support multiple bt devices
    MediaRoute2Info mDefaultRoute;
    MediaRoute2Info mBluetoothA2dpRoute;
    final AudioRoutesInfo mCurAudioRoutesInfo = new AudioRoutesInfo();

    final IAudioRoutesObserver.Stub mAudioRoutesObserver = new IAudioRoutesObserver.Stub() {
        @Override
        public void dispatchAudioRoutesChanged(final AudioRoutesInfo newRoutes) {
            mHandler.post(new Runnable() {
                @Override public void run() {
                    updateAudioRoutes(newRoutes);
                }
            });
        }
    };

    SystemMediaRoute2Provider(Context context, Callback callback) {
        super(sComponentName);
        setCallback(callback);

        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAudioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));

        initializeRoutes();
    }

    @Override
    public void requestCreateSession(String packageName, String routeId, String controlCategory,
            int requestId) {
        // Do nothing
    }

    @Override
    public void releaseSession(int sessionId) {
        // Do nothing
    }

    @Override
    public void selectRoute(int sessionId, MediaRoute2Info route) {
        //TODO: implement method
    }

    @Override
    public void deselectRoute(int sessionId, MediaRoute2Info route) {
        //TODO: implement method
    }

    @Override
    public void transferRoute(int sessionId, MediaRoute2Info route) {
        //TODO: implement method
    }

    //TODO: implement method
    @Override
    public void sendControlRequest(@NonNull MediaRoute2Info route, @NonNull Intent request) {
    }

    //TODO: implement method
    @Override
    public void requestSetVolume(MediaRoute2Info route, int volume) {
    }

    //TODO: implement method
    @Override
    public void requestUpdateVolume(MediaRoute2Info route, int delta) {
    }

    void initializeRoutes() {
        //TODO: adds necessary info
        mDefaultRoute = new MediaRoute2Info.Builder(
                DEFAULT_ROUTE_ID,
                mContext.getResources().getText(R.string.default_audio_route_name).toString())
                .setVolumeHandling(mAudioManager.isVolumeFixed()
                        ? MediaRoute2Info.PLAYBACK_VOLUME_FIXED
                        : MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .setVolume(mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                .addSupportedCategory(CATEGORY_LIVE_AUDIO)
                .addSupportedCategory(CATEGORY_LIVE_VIDEO)
                .build();

        AudioRoutesInfo newAudioRoutes = null;
        try {
            newAudioRoutes = mAudioService.startWatchingRoutes(mAudioRoutesObserver);
        } catch (RemoteException e) {
        }
        if (newAudioRoutes != null) {
            // This will select the active BT route if there is one and the current
            // selected route is the default system route, or if there is no selected
            // route yet.
            updateAudioRoutes(newAudioRoutes);
        }

        publishRoutes();
    }

    void updateAudioRoutes(AudioRoutesInfo newRoutes) {
        int name = R.string.default_audio_route_name;
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

        mDefaultRoute = new MediaRoute2Info.Builder(
                DEFAULT_ROUTE_ID, mContext.getResources().getText(name).toString())
                .setVolumeHandling(mAudioManager.isVolumeFixed()
                        ? MediaRoute2Info.PLAYBACK_VOLUME_FIXED
                        : MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .setVolume(mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                .addSupportedCategory(CATEGORY_LIVE_AUDIO)
                .addSupportedCategory(CATEGORY_LIVE_VIDEO)
                .build();

        if (!TextUtils.equals(newRoutes.bluetoothName, mCurAudioRoutesInfo.bluetoothName)) {
            mCurAudioRoutesInfo.bluetoothName = newRoutes.bluetoothName;
            if (mCurAudioRoutesInfo.bluetoothName != null) {
                //TODO: mark as bluetooth once MediaRoute2Info has device type
                mBluetoothA2dpRoute = new MediaRoute2Info.Builder(BLUETOOTH_ROUTE_ID,
                        mCurAudioRoutesInfo.bluetoothName)
                        .setDescription(mContext.getResources().getText(
                                R.string.bluetooth_a2dp_audio_route_name).toString())
                        .addSupportedCategory(CATEGORY_LIVE_AUDIO)
                        .build();
            } else {
                mBluetoothA2dpRoute = null;
            }
        }

        publishRoutes();
    }

    /**
     * The first route should be the currently selected system route.
     * For example, if there are two system routes (BT and device speaker),
     * BT will be the first route in the list.
     *
     * TODO: Support multiple BT devices
     */
    void publishRoutes() {
        MediaRoute2ProviderInfo.Builder builder = new MediaRoute2ProviderInfo.Builder();
        if (mBluetoothA2dpRoute != null) {
            builder.addRoute(mBluetoothA2dpRoute);
        }
        builder.addRoute(mDefaultRoute);
        setAndNotifyProviderInfo(builder.build());
    }
}
