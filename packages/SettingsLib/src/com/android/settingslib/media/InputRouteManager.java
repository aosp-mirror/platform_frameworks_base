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
import android.media.AudioDeviceInfo.AudioDeviceType;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerExecutor;
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

    @VisibleForTesting
    static final int[] PRESETS = {
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_PERFORMANCE
    };

    private final Context mContext;

    private final AudioManager mAudioManager;

    @VisibleForTesting final List<MediaDevice> mInputMediaDevices = new CopyOnWriteArrayList<>();

    private @AudioDeviceType int mSelectedInputDeviceType;

    private final Collection<InputDeviceCallback> mCallbacks = new CopyOnWriteArrayList<>();
    private final Object mCallbackLock = new Object();

    @VisibleForTesting
    final AudioDeviceCallback mAudioDeviceCallback =
            new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(@NonNull AudioDeviceInfo[] addedDevices) {
                    applyDefaultSelectedTypeToAllPresets();

                    // Activate the last hot plugged valid input device, to match the output device
                    // behavior.
                    @AudioDeviceType int deviceTypeToActivate = mSelectedInputDeviceType;
                    for (AudioDeviceInfo info : addedDevices) {
                        @AudioDeviceType int type = info.getType();
                        // Since onAudioDevicesAdded is called not only when new device is hot
                        // plugged, but also when the switcher dialog is opened, make sure to check
                        // against existing device list and only activate if the device does not
                        // exist previously.
                        if (InputMediaDevice.isSupportedInputDevice(type)
                                && findDeviceByType(type) == null) {
                            deviceTypeToActivate = type;
                        }
                    }

                    // Only activate if we find a different valid input device. e.g. if none of the
                    // addedDevices is supported input device, we don't need to activate anything.
                    if (mSelectedInputDeviceType != deviceTypeToActivate) {
                        mSelectedInputDeviceType = deviceTypeToActivate;
                        AudioDeviceAttributes deviceAttributes =
                                createInputDeviceAttributes(mSelectedInputDeviceType);
                        setPreferredDeviceForAllPresets(deviceAttributes);
                    }
                }

                @Override
                public void onAudioDevicesRemoved(@NonNull AudioDeviceInfo[] removedDevices) {
                    applyDefaultSelectedTypeToAllPresets();
                }
            };

    public InputRouteManager(@NonNull Context context, @NonNull AudioManager audioManager) {
        mContext = context;
        mAudioManager = audioManager;
        Handler handler = new Handler(context.getMainLooper());

        mAudioManager.registerAudioDeviceCallback(mAudioDeviceCallback, handler);

        mAudioManager.addOnPreferredDevicesForCapturePresetChangedListener(
                new HandlerExecutor(handler),
                this::onPreferredDevicesForCapturePresetChangedListener);

        applyDefaultSelectedTypeToAllPresets();
    }

    @VisibleForTesting
    void onPreferredDevicesForCapturePresetChangedListener(
            @MediaRecorder.SystemSource int capturePreset,
            @NonNull List<AudioDeviceAttributes> devices) {
        if (capturePreset == MediaRecorder.AudioSource.MIC) {
            dispatchInputDeviceListUpdate();
        }
    }

    public void registerCallback(@NonNull InputDeviceCallback callback) {
        synchronized (mCallbackLock) {
            if (!mCallbacks.contains(callback)) {
                mCallbacks.add(callback);
                dispatchInputDeviceListUpdate();
            }
        }
    }

    public void unregisterCallback(@NonNull InputDeviceCallback callback) {
        synchronized (mCallbackLock) {
            mCallbacks.remove(callback);
        }
    }

    // TODO(b/355684672): handle edge case where there are two devices with the same type. Only
    // using a single type might not be enough to recognize the correct device.
    @Nullable
    private MediaDevice findDeviceByType(@AudioDeviceType int type) {
        for (MediaDevice device : mInputMediaDevices) {
            if (((InputMediaDevice) device).getAudioDeviceInfoType() == type) {
                return device;
            }
        }
        return null;
    }

    @Nullable
    public MediaDevice getSelectedInputDevice() {
        return findDeviceByType(mSelectedInputDeviceType);
    }

    private void applyDefaultSelectedTypeToAllPresets() {
        mSelectedInputDeviceType = retrieveDefaultSelectedDeviceType();
        AudioDeviceAttributes deviceAttributes =
                createInputDeviceAttributes(mSelectedInputDeviceType);
        setPreferredDeviceForAllPresets(deviceAttributes);
    }

    private AudioDeviceAttributes createInputDeviceAttributes(@AudioDeviceType int type) {
        // Address is not used.
        return new AudioDeviceAttributes(AudioDeviceAttributes.ROLE_INPUT, type, /* address= */ "");
    }

    private @AudioDeviceType int retrieveDefaultSelectedDeviceType() {
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
        return selectedInputDeviceAttributesType;
    }

    private void dispatchInputDeviceListUpdate() {
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
                            isInputGainFixed(),
                            getProductNameFromAudioDeviceInfo(info));
            if (mediaDevice != null) {
                if (info.getType() == mSelectedInputDeviceType) {
                    mediaDevice.setState(STATE_SELECTED);
                }
                mInputMediaDevices.add(mediaDevice);
            }
        }

        final List<MediaDevice> inputMediaDevices = new ArrayList<>(mInputMediaDevices);
        synchronized (mCallbackLock) {
            for (InputDeviceCallback callback : mCallbacks) {
                callback.onInputDeviceListUpdated(inputMediaDevices);
            }
        }
    }

    /**
     * Gets the product name for the given {@link AudioDeviceInfo}.
     *
     * @return The product name for the given {@link AudioDeviceInfo}, or null if a suitable name
     *     cannot be found.
     */
    @Nullable
    private String getProductNameFromAudioDeviceInfo(AudioDeviceInfo deviceInfo) {
        CharSequence productName = deviceInfo.getProductName();
        if (productName == null) {
            return null;
        }
        String productNameString = productName.toString();
        if (productNameString.isBlank()) {
            return null;
        }
        return productNameString;
    }

    public void selectDevice(@NonNull MediaDevice device) {
        if (!(device instanceof InputMediaDevice inputMediaDevice)) {
            Slog.w(TAG, "This device is not an InputMediaDevice: " + device.getName());
            return;
        }

        if (inputMediaDevice.getAudioDeviceInfoType() == mSelectedInputDeviceType) {
            Slog.w(TAG, "This device is already selected: " + device.getName());
            return;
        }

        // Handle edge case where the targeting device is not available, e.g. disconnected.
        if (!mInputMediaDevices.contains(device)) {
            Slog.w(TAG, "This device is not available: " + device.getName());
            return;
        }

        // Update mSelectedInputDeviceType directly based on user action.
        mSelectedInputDeviceType = inputMediaDevice.getAudioDeviceInfoType();

        AudioDeviceAttributes deviceAttributes =
                createInputDeviceAttributes(inputMediaDevice.getAudioDeviceInfoType());
        try {
            setPreferredDeviceForAllPresets(deviceAttributes);
        } catch (IllegalArgumentException e) {
            Slog.e(
                    TAG,
                    "Illegal argument exception while setPreferredDeviceForAllPreset: "
                            + device.getName(),
                    e);
        }
    }

    private void setPreferredDeviceForAllPresets(@NonNull AudioDeviceAttributes deviceAttributes) {
        // The input routing via system setting takes effect on all capture presets.
        for (@MediaRecorder.Source int preset : PRESETS) {
            mAudioManager.setPreferredDeviceForCapturePreset(preset, deviceAttributes);
        }
    }

    public int getMaxInputGain() {
        // TODO (b/357123335): use real input gain implementation.
        // Using 100 for now since it matches the maximum input gain index in classic ChromeOS.
        return 100;
    }

    public int getCurrentInputGain() {
        // TODO (b/357123335): use real input gain implementation.
        // Show a fixed full gain in UI before it really works per UX requirement.
        return 100;
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
