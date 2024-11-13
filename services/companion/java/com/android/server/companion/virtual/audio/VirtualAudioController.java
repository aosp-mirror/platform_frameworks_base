/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.virtual.audio;

import static android.media.AudioPlaybackConfiguration.PLAYER_STATE_STARTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.companion.virtual.audio.IAudioConfigChangedCallback;
import android.companion.virtual.audio.IAudioRoutingCallback;
import android.content.AttributionSource;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.expresslog.Counter;
import com.android.server.companion.virtual.GenericWindowPolicyController;
import com.android.server.companion.virtual.GenericWindowPolicyController.RunningAppsChangedListener;
import com.android.server.companion.virtual.audio.AudioPlaybackDetector.AudioPlaybackCallback;
import com.android.server.companion.virtual.audio.AudioRecordingDetector.AudioRecordingCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages audio streams associated with a {@link VirtualAudioDevice}. Responsible for monitoring
 * running applications and playback configuration changes in order to correctly re-route audio and
 * then notify clients of these changes.
 */
public final class VirtualAudioController implements AudioPlaybackCallback,
        AudioRecordingCallback, RunningAppsChangedListener {
    private static final String TAG = "VirtualAudioController";
    private static final int UPDATE_REROUTING_APPS_DELAY_MS = 2000;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mUpdateAudioRoutingRunnable = this::notifyAppsNeedingAudioRoutingChanged;
    private final AudioPlaybackDetector mAudioPlaybackDetector;
    private final AudioRecordingDetector mAudioRecordingDetector;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArraySet<Integer> mRunningAppUids = new ArraySet<>();
    @GuardedBy("mLock")
    private ArraySet<Integer> mPlayingAppUids = new ArraySet<>();
    private GenericWindowPolicyController mGenericWindowPolicyController;
    private final Object mCallbackLock = new Object();
    @GuardedBy("mCallbackLock")
    private IAudioRoutingCallback mRoutingCallback;
    @GuardedBy("mCallbackLock")
    private IAudioConfigChangedCallback mConfigChangedCallback;

    public VirtualAudioController(Context context, AttributionSource attributionSource) {
        mContext = context;
        mAudioPlaybackDetector = new AudioPlaybackDetector(context);
        mAudioRecordingDetector = new AudioRecordingDetector(context);

        Counter.logIncrementWithUid(
                "virtual_devices.value_virtual_audio_created_count",
                attributionSource.getUid());
    }

    /**
     * Starts to listen to running applications and audio configuration changes on virtual display
     * for audio capture and injection.
     */
    public void startListening(
            @NonNull GenericWindowPolicyController genericWindowPolicyController,
            @NonNull IAudioRoutingCallback routingCallback,
            @Nullable IAudioConfigChangedCallback configChangedCallback) {
        mGenericWindowPolicyController = genericWindowPolicyController;
        mGenericWindowPolicyController.registerRunningAppsChangedListener(/* listener= */ this);
        synchronized (mCallbackLock) {
            mRoutingCallback = routingCallback;
            mConfigChangedCallback = configChangedCallback;
        }
        synchronized (mLock) {
            mRunningAppUids.clear();
            mPlayingAppUids.clear();
        }
        if (configChangedCallback != null) {
            mAudioPlaybackDetector.register(/* callback= */ this);
            mAudioRecordingDetector.register(/* callback= */ this);
        }
    }

    /**
     * Stops listening to running applications and audio configuration changes on virtual display
     * for audio capture and injection.
     */
    public void stopListening() {
        if (mHandler.hasCallbacks(mUpdateAudioRoutingRunnable)) {
            mHandler.removeCallbacks(mUpdateAudioRoutingRunnable);
        }
        mAudioPlaybackDetector.unregister();
        mAudioRecordingDetector.unregister();
        if (mGenericWindowPolicyController != null) {
            mGenericWindowPolicyController.unregisterRunningAppsChangedListener(
                    /* listener= */ this);
            mGenericWindowPolicyController = null;
        }
        synchronized (mCallbackLock) {
            mRoutingCallback = null;
            mConfigChangedCallback = null;
        }
    }

    @Override
    public void onRunningAppsChanged(ArraySet<Integer> runningUids) {
        synchronized (mLock) {
            if (mRunningAppUids.equals(runningUids)) {
                // Ignore no-op events.
                return;
            }
            mRunningAppUids.clear();
            mRunningAppUids.addAll(runningUids);

            ArraySet<Integer> oldPlayingAppUids = mPlayingAppUids;

            // Update the list of playing apps after caching the old list, and before checking if
            // the list of playing apps is empty. This is a subset of the running apps, so we need
            // to update this here as well.
            AudioManager audioManager = mContext.getSystemService(AudioManager.class);
            List<AudioPlaybackConfiguration> configs =
                    audioManager.getActivePlaybackConfigurations();
            mPlayingAppUids = findPlayingAppUids(configs, mRunningAppUids);

            // Do not change rerouted applications while any application is playing, or the sound
            // will be leaked from phone during the transition. Delay the change until we detect
            // there is no application is playing in onPlaybackConfigChanged().
            if (!mPlayingAppUids.isEmpty()) {
                Slog.i(TAG, "Audio is playing, do not change rerouted apps");
                return;
            }

            // An application previously playing audio was removed from the display.
            if (!oldPlayingAppUids.isEmpty()) {
                // Delay changing the rerouted application when the last application playing audio
                // was removed from virtual device, or the sound will be leaked from phone side
                // during the transition.
                Slog.i(TAG, "The last playing app removed, delay change rerouted apps");
                if (mHandler.hasCallbacks(mUpdateAudioRoutingRunnable)) {
                    mHandler.removeCallbacks(mUpdateAudioRoutingRunnable);
                }
                mHandler.postDelayed(mUpdateAudioRoutingRunnable, UPDATE_REROUTING_APPS_DELAY_MS);
                return;
            }
        }

        // Normal case with no application playing, just update routing.
        notifyAppsNeedingAudioRoutingChanged();
    }

    @Override
    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        updatePlayingApplications(configs);

        List<AudioPlaybackConfiguration> audioPlaybackConfigurations;
        synchronized (mLock) {
            // Filter configurations of applications running on virtual device.
            audioPlaybackConfigurations = findPlaybackConfigurations(configs, mRunningAppUids);
        }
        synchronized (mCallbackLock) {
            if (mConfigChangedCallback != null) {
                try {
                    mConfigChangedCallback.onPlaybackConfigChanged(audioPlaybackConfigurations);
                } catch (RemoteException e) {
                    Slog.e(TAG, "RemoteException when calling onPlaybackConfigChanged", e);
                }
            }
        }
    }

    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    @Override
    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
        List<AudioRecordingConfiguration> audioRecordingConfigurations;
        synchronized (mLock) {
            // Filter configurations of applications running on virtual device.
            audioRecordingConfigurations = findRecordingConfigurations(configs, mRunningAppUids);
        }
        synchronized (mCallbackLock) {
            if (mConfigChangedCallback != null) {
                try {
                    mConfigChangedCallback.onRecordingConfigChanged(audioRecordingConfigurations);
                } catch (RemoteException e) {
                    Slog.e(TAG, "RemoteException when calling onRecordingConfigChanged", e);
                }
            }
        }
    }

    private void updatePlayingApplications(List<AudioPlaybackConfiguration> configs) {
        synchronized (mLock) {
            ArraySet<Integer> playingAppUids = findPlayingAppUids(configs, mRunningAppUids);
            if (mPlayingAppUids.equals(playingAppUids)) {
                return;
            }
            mPlayingAppUids = playingAppUids;
        }

        // Updated rerouted apps, even if the app is already playing. It originally should be done
        // when onRunningAppsChanged() is called, but we don't want to interrupt the audio
        // streaming and cause the sound leak from phone when it's playing, so delay until here.
        notifyAppsNeedingAudioRoutingChanged();
    }

    private void notifyAppsNeedingAudioRoutingChanged() {
        if (mHandler.hasCallbacks(mUpdateAudioRoutingRunnable)) {
            mHandler.removeCallbacks(mUpdateAudioRoutingRunnable);
        }

        int[] runningUids;
        synchronized (mLock) {
            runningUids = new int[mRunningAppUids.size()];
            for (int i = 0; i < mRunningAppUids.size(); i++) {
                runningUids[i] = mRunningAppUids.valueAt(i);
            }
        }

        synchronized (mCallbackLock) {
            if (mRoutingCallback != null) {
                try {
                    mRoutingCallback.onAppsNeedingAudioRoutingChanged(runningUids);
                } catch (RemoteException e) {
                    Slog.e(TAG, "RemoteException when calling updateReroutingApps", e);
                }
            }
        }
    }

    /**
     * Finds uid of playing applications from the given running applications.
     *
     * @param configs a list of playback configs which get from {@link AudioManager}
     */
    private static ArraySet<Integer> findPlayingAppUids(List<AudioPlaybackConfiguration> configs,
            ArraySet<Integer> runningAppUids) {
        ArraySet<Integer> playingAppUids = new ArraySet<>();
        for (AudioPlaybackConfiguration config : configs) {
            if (runningAppUids.contains(config.getClientUid())
                    && config.getPlayerState() == PLAYER_STATE_STARTED) {
                playingAppUids.add(config.getClientUid());
            }
        }
        return playingAppUids;
    }

    /** Finds a list of {@link AudioPlaybackConfiguration} for the given running applications. */
    private static List<AudioPlaybackConfiguration> findPlaybackConfigurations(
            List<AudioPlaybackConfiguration> configs,
            ArraySet<Integer> runningAppUids) {
        List<AudioPlaybackConfiguration> runningConfigs = new ArrayList<>();
        for (AudioPlaybackConfiguration config : configs) {
            if (runningAppUids.contains(config.getClientUid())) {
                runningConfigs.add(config);
            }
        }
        return runningConfigs;
    }

    /** Finds a list of {@link AudioRecordingConfiguration} for the given running applications. */
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    private static List<AudioRecordingConfiguration> findRecordingConfigurations(
            List<AudioRecordingConfiguration> configs, ArraySet<Integer> runningAppUids) {
        List<AudioRecordingConfiguration> runningConfigs = new ArrayList<>();
        for (AudioRecordingConfiguration config : configs) {
            if (runningAppUids.contains(config.getClientUid())) {
                runningConfigs.add(config);
            }
        }
        return runningConfigs;
    }

    @VisibleForTesting
    boolean hasPendingRunnable() {
        return mHandler.hasCallbacks(mUpdateAudioRoutingRunnable);
    }

    @VisibleForTesting
    void addPlayingAppsForTesting(int appUid) {
        synchronized (mLock) {
            mPlayingAppUids.add(appUid);
        }
    }
}
