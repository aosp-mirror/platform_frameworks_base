/*
 * Copyright 2024 The Android Open Source Project
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
package com.android.settingslib.media;

import static com.android.settingslib.media.LocalMediaManager.MediaDeviceState.STATE_SELECTED;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Slog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Provides functionalities to get/observe input routes, control input routing and volume gain. */
public final class InputRouteManager {

    private static final String TAG = "InputRouteManager";

    @VisibleForTesting
    static final AudioAttributes INPUT_ATTRIBUTES =
            new AudioAttributes.Builder().setCapturePreset(MediaRecorder.AudioSource.MIC).build();

    private final Context mContext;

    private final AudioManager mAudioManager;

    @VisibleForTesting final List<MediaDevice> mInputMediaDevices = new CopyOnWriteArrayList<>();

    private MediaDevice mSelectedInputDevice;

    private final Collection<InputDeviceCallback> mCallbacks = new CopyOnWriteArrayList<>();

    @VisibleForTesting
    final AudioDeviceCallback mAudioDeviceCallback =
            new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(@NonNull AudioDeviceInfo[] addedDevices) {
                    dispatchInputDeviceListUpdate();
                }

                @Override
                public void onAudioDevicesRemoved(@NonNull AudioDeviceInfo[] removedDevices) {
                    dispatchInputDeviceListUpdate();
                }
            };

    public InputRouteManager(@NonNull Context context, @NonNull AudioManager audioManager) {
        mContext = context;
        mAudioManager = audioManager;
        Handler handler = new Handler(context.getMainLooper());

        mAudioManager.registerAudioDeviceCallback(mAudioDeviceCallback, handler);
    }

    public void registerCallback(@NonNull InputDeviceCallback callback) {
        if (!mCallbacks.contains(callback)) {
            mCallbacks.add(callback);
            dispatchInputDeviceListUpdate();
        }
    }

    public void unregisterCallback(@NonNull InputDeviceCallback callback) {
        mCallbacks.remove(callback);
    }

    public @Nullable MediaDevice getSelectedInputDevice() {
        return mSelectedInputDevice;
    }

    private void dispatchInputDeviceListUpdate() {
        // Get selected input device.
        List<AudioDeviceAttributes> attributesOfSelectedInputDevices =
                mAudioManager.getDevicesForAttributes(INPUT_ATTRIBUTES);
        int selectedInputDeviceAttributesType;
        if (attributesOfSelectedInputDevices.isEmpty()) {
            Slog.e(TAG, "Unexpected empty list of input devices. Using built-in mic.");
            selectedInputDeviceAttributesType = AudioDeviceInfo.TYPE_BUILTIN_MIC;
        } else {
            if (attributesOfSelectedInputDevices.size() > 1) {
                Slog.w(
                        TAG,
                        "AudioManager.getDevicesForAttributes returned more than one element."
                                + " Using the first one.");
            }
            selectedInputDeviceAttributesType = attributesOfSelectedInputDevices.get(0).getType();
        }

        // Get all input devices.
        AudioDeviceInfo[] audioDeviceInfos =
                mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        mInputMediaDevices.clear();
        for (AudioDeviceInfo info : audioDeviceInfos) {
            MediaDevice mediaDevice =
                    InputMediaDevice.create(
                            mContext,
                            String.valueOf(info.getId()),
                            info.getType(),
                            getMaxInputGain(),
                            getCurrentInputGain(),
                            isInputGainFixed());
            if (mediaDevice != null) {
                if (info.getType() == selectedInputDeviceAttributesType) {
                    mediaDevice.setState(STATE_SELECTED);
                    mSelectedInputDevice = mediaDevice;
                }
                mInputMediaDevices.add(mediaDevice);
            }
        }

        final List<MediaDevice> inputMediaDevices = new ArrayList<>(mInputMediaDevices);
        for (InputDeviceCallback callback : mCallbacks) {
            callback.onInputDeviceListUpdated(inputMediaDevices);
        }
    }

    public int getMaxInputGain() {
        // TODO (b/357123335): use real input gain implementation.
        // Using 15 for now since it matches the max index for output.
        return 15;
    }

    public int getCurrentInputGain() {
        // TODO (b/357123335): use real input gain implementation.
        return 8;
    }

    public boolean isInputGainFixed() {
        // TODO (b/357123335): use real input gain implementation.
        return true;
    }

    /** Callback for listening to input device changes. */
    public interface InputDeviceCallback {
        void onInputDeviceListUpdated(@NonNull List<MediaDevice> devices);
    }
}
