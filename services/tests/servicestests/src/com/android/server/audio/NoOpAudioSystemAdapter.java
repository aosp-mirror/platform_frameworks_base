/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.audio;

import android.annotation.NonNull;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioSystem;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides an adapter for AudioSystem that does nothing.
 * Overridden methods can be configured.
 */
public class NoOpAudioSystemAdapter extends AudioSystemAdapter {
    private static final String TAG = "ASA";
    private boolean mIsMicMuted = false;
    private boolean mMuteMicrophoneFails = false;
    private boolean mIsStreamActive = false;

    public void configureIsMicrophoneMuted(boolean muted) {
        mIsMicMuted = muted;
    }

    public void configureIsStreamActive(boolean active) {
        mIsStreamActive = active;
    }

    public void configureMuteMicrophoneToFail(boolean fail) {
        mMuteMicrophoneFails = fail;
    }

    //-----------------------------------------------------------------
    // Overrides of AudioSystemAdapter
    @Override
    public int setDeviceConnectionState(AudioDeviceAttributes attributes, int state,
            int codecFormat) {
        Log.i(TAG, String.format("setDeviceConnectionState(0x%s, %d, 0x%s",
                attributes.toString(), state, Integer.toHexString(codecFormat)));
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public int getDeviceConnectionState(int device, String deviceAddress) {
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public int handleDeviceConfigChange(int device, String deviceAddress,
            String deviceName, int codecFormat) {
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public int setDevicesRoleForStrategy(int strategy, int role,
            @NonNull List<AudioDeviceAttributes> devices) {
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public int removeDevicesRoleForStrategy(int strategy, int role) {
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public int setDevicesRoleForCapturePreset(int capturePreset, int role,
                                              @NonNull List<AudioDeviceAttributes> devices) {
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public int removeDevicesRoleForCapturePreset(
            int capturePreset, int role, @NonNull List<AudioDeviceAttributes> devicesToRemove) {
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public int clearDevicesRoleForCapturePreset(int capturePreset, int role) {
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public int setParameters(String keyValuePairs) {
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public boolean isMicrophoneMuted() {
        return mIsMicMuted;
    }

    @Override
    public int muteMicrophone(boolean on) {
        if (mMuteMicrophoneFails) {
            return AudioSystem.AUDIO_STATUS_ERROR;
        }
        mIsMicMuted = on;
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public int setCurrentImeUid(int uid) {
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    public boolean isStreamActive(int stream, int inPastMs) {
        return mIsStreamActive;
    }

    @Override
    public int setStreamVolumeIndexAS(int stream, int index, int device) {
        return AudioSystem.AUDIO_STATUS_OK;
    }

    @Override
    @NonNull
    public ArrayList<AudioDeviceAttributes> getDevicesForAttributes(
            @NonNull AudioAttributes attributes, boolean forVolume) {
        return new ArrayList<>();
    }
}
