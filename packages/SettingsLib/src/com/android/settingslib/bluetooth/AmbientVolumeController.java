/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import static android.bluetooth.AudioInputControl.MUTE_DISABLED;
import static android.bluetooth.AudioInputControl.MUTE_MUTED;
import static android.bluetooth.AudioInputControl.MUTE_NOT_MUTED;

import static com.android.settingslib.bluetooth.HearingDeviceLocalDataManager.Data.INVALID_VOLUME;

import android.bluetooth.AudioInputControl;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * AmbientVolumeController manages the {@link AudioInputControl}s of
 * {@link AudioInputControl#AUDIO_INPUT_TYPE_AMBIENT} on the remote device.
 */
public class AmbientVolumeController implements LocalBluetoothProfileManager.ServiceListener {

    private static final boolean DEBUG = true;
    private static final String TAG = "AmbientController";

    private final LocalBluetoothProfileManager mProfileManager;
    private final VolumeControlProfile mVolumeControlProfile;
    private final Map<BluetoothDevice, List<AudioInputControl>> mDeviceAmbientControlsMap =
            new ArrayMap<>();
    private final Map<BluetoothDevice, AmbientCallback> mDeviceCallbackMap = new ArrayMap<>();
    final Map<BluetoothDevice, RemoteAmbientState> mDeviceAmbientStateMap =
            new ArrayMap<>();
    @Nullable
    private final AmbientVolumeControlCallback mCallback;

    public AmbientVolumeController(
            @NonNull LocalBluetoothProfileManager profileManager,
            @Nullable AmbientVolumeControlCallback callback) {
        mProfileManager = profileManager;
        mVolumeControlProfile = profileManager.getVolumeControlProfile();
        if (mVolumeControlProfile != null && !mVolumeControlProfile.isProfileReady()) {
            mProfileManager.addServiceListener(this);
        }
        mCallback = callback;
    }

    @Override
    public void onServiceConnected() {
        if (mVolumeControlProfile != null && mVolumeControlProfile.isProfileReady()) {
            mProfileManager.removeServiceListener(this);
            if (mCallback != null) {
                mCallback.onVolumeControlServiceConnected();
            }
        }
    }

    @Override
    public void onServiceDisconnected() {
        // Do nothing
    }

    /**
     * Registers the same {@link AmbientCallback} on all ambient control points of the remote
     * device. The {@link AmbientCallback} will pass the event to registered
     * {@link AmbientVolumeControlCallback} if exists.
     *
     * @param executor the executor to run the callback
     * @param device the remote device
     */
    public void registerCallback(@NonNull Executor executor, @NonNull BluetoothDevice device) {
        AmbientCallback ambientCallback = new AmbientCallback(device, mCallback);
        synchronized (mDeviceCallbackMap) {
            mDeviceCallbackMap.put(device, ambientCallback);
        }

        // register callback on all ambient input control points of this device
        List<AudioInputControl> controls = getAmbientControls(device);
        controls.forEach((control) -> {
            try {
                control.registerCallback(executor, ambientCallback);
            } catch (IllegalArgumentException e) {
                // The callback was already registered
                Log.i(TAG, "Skip registering the callback, " + e.getMessage());
            }
        });
    }

    /**
     * Unregisters the {@link AmbientCallback} on all ambient control points of the remote
     * device which is previously registered with {@link #registerCallback}.
     *
     * @param device the remote device
     */
    public void unregisterCallback(@NonNull BluetoothDevice device) {
        AmbientCallback ambientCallback;
        synchronized (mDeviceCallbackMap) {
            ambientCallback = mDeviceCallbackMap.remove(device);
        }
        if (ambientCallback == null) {
            // callback not found, no need to unregister
            return;
        }

        // unregister callback on all ambient input control points of this device
        List<AudioInputControl> controls = getAmbientControls(device);
        controls.forEach(control -> {
            try {
                control.unregisterCallback(ambientCallback);
            } catch (IllegalArgumentException e) {
                // The callback was never registered or was already unregistered
                Log.i(TAG, "Skip unregistering the callback, " + e.getMessage());
            }
        });
    }

    /**
     * Gets the gain setting max value from first ambient control point of the remote device.
     *
     * @param device the remote device
     */
    public int getAmbientMax(@NonNull BluetoothDevice device) {
        List<AudioInputControl> ambientControls = getAmbientControls(device);
        int value = INVALID_VOLUME;
        if (!ambientControls.isEmpty()) {
            value = ambientControls.getFirst().getGainSettingMax();
        }
        return value;
    }

    /**
     * Gets the gain setting min value from first ambient control point of the remote device.
     *
     * @param device the remote device
     */
    public int getAmbientMin(@NonNull BluetoothDevice device) {
        List<AudioInputControl> ambientControls = getAmbientControls(device);
        int value = INVALID_VOLUME;
        if (!ambientControls.isEmpty()) {
            value = ambientControls.getFirst().getGainSettingMin();
        }
        return value;
    }

    /**
     * Gets the latest values in {@link RemoteAmbientState}.
     *
     * @param device the remote device
     * @return the {@link RemoteAmbientState} represents current remote ambient control point state
     */
    @Nullable
    public RemoteAmbientState refreshAmbientState(@Nullable BluetoothDevice device) {
        if (device == null || !device.isConnected()) {
            return null;
        }
        int gainSetting = getAmbient(device);
        int mute = getMute(device);
        return new RemoteAmbientState(gainSetting, mute);
    }

    /**
     * Gets the gain setting value from first ambient control point of the remote device and
     * stores it in cached {@link RemoteAmbientState}.
     *
     * When any audio input point receives {@link AmbientCallback#onGainSettingChanged(int)}
     * callback, only the changed value which is different from the value stored in the cached
     * state will be notified to the {@link AmbientVolumeControlCallback} of this controller.
     *
     * @param device the remote device
     */
    public int getAmbient(@NonNull BluetoothDevice device) {
        List<AudioInputControl> ambientControls = getAmbientControls(device);
        int value = INVALID_VOLUME;
        if (!ambientControls.isEmpty()) {
            synchronized (mDeviceAmbientStateMap) {
                value = ambientControls.getFirst().getGainSetting();
                RemoteAmbientState state = mDeviceAmbientStateMap.getOrDefault(device,
                        new RemoteAmbientState(INVALID_VOLUME, MUTE_DISABLED));
                RemoteAmbientState updatedState = new RemoteAmbientState(value, state.mute);
                mDeviceAmbientStateMap.put(device, updatedState);
            }
        }
        return value;
    }

    /**
     * Sets the gain setting value to all ambient control points of the remote device.
     *
     * @param device the remote device
     * @param value the gain setting value to be updated
     */
    public void setAmbient(@NonNull BluetoothDevice device, int value) {
        if (DEBUG) {
            Log.d(TAG, "setAmbient, value:" + value + ", device:" + device);
        }
        List<AudioInputControl> ambientControls = getAmbientControls(device);
        ambientControls.forEach(control -> control.setGainSetting(value));
    }

    /**
     * Gets the mute state from first ambient control point of the remote device and
     * stores it in cached {@link RemoteAmbientState}. The value will be one of
     * {@link AudioInputControl.Mute}.
     *
     * When any audio input point receives {@link AmbientCallback#onMuteChanged(int)} callback,
     * only the changed value which is different from the value stored in the cached state will
     * be notified to the {@link AmbientVolumeControlCallback} of this controller.
     *
     * @param device the remote device
     */
    public int getMute(@NonNull BluetoothDevice device) {
        List<AudioInputControl> ambientControls = getAmbientControls(device);
        int value = MUTE_DISABLED;
        if (!ambientControls.isEmpty()) {
            synchronized (mDeviceAmbientStateMap) {
                value = ambientControls.getFirst().getMute();
                RemoteAmbientState state = mDeviceAmbientStateMap.getOrDefault(device,
                        new RemoteAmbientState(INVALID_VOLUME, MUTE_DISABLED));
                RemoteAmbientState updatedState = new RemoteAmbientState(state.gainSetting, value);
                mDeviceAmbientStateMap.put(device, updatedState);
            }
        }
        return value;
    }

    /**
     * Sets the mute state to all ambient control points of the remote device.
     *
     * @param device the remote device
     * @param muted the mute state to be updated
     */
    public void setMuted(@NonNull BluetoothDevice device, boolean muted) {
        if (DEBUG) {
            Log.d(TAG, "setMuted, muted:" + muted + ", device:" + device);
        }
        List<AudioInputControl> ambientControls = getAmbientControls(device);
        ambientControls.forEach(control -> {
            try {
                control.setMute(muted ? MUTE_MUTED : MUTE_NOT_MUTED);
            } catch (IllegalStateException e) {
                // Sometimes remote will throw this exception due to initialization not done
                // yet. Catch it to prevent crashes on UI.
                Log.w(TAG, "Remote mute state is currently disabled.");
            }
        });
    }

    /**
     * Checks if there's any valid ambient control point exists on the remote device
     *
     * @param device the remote device
     */
    public boolean isAmbientControlAvailable(@NonNull BluetoothDevice device) {
        final boolean hasAmbientControlPoint = !getAmbientControls(device).isEmpty();
        final boolean connectedToVcp = mVolumeControlProfile.getConnectionStatus(device)
                == BluetoothProfile.STATE_CONNECTED;
        return hasAmbientControlPoint && connectedToVcp;
    }

    @NonNull
    private List<AudioInputControl> getAmbientControls(@NonNull BluetoothDevice device) {
        if (mVolumeControlProfile == null) {
            return Collections.emptyList();
        }
        synchronized (mDeviceAmbientControlsMap) {
            if (mDeviceAmbientControlsMap.containsKey(device)) {
                return mDeviceAmbientControlsMap.get(device);
            }
            List<AudioInputControl> ambientControls =
                    mVolumeControlProfile.getAudioInputControlServices(device).stream().filter(
                            this::isValidAmbientControl).toList();
            if (!ambientControls.isEmpty()) {
                mDeviceAmbientControlsMap.put(device, ambientControls);
            }
            return ambientControls;
        }
    }

    private boolean isValidAmbientControl(AudioInputControl control) {
        boolean isAmbientControl =
                control.getAudioInputType() == AudioInputControl.AUDIO_INPUT_TYPE_AMBIENT;
        boolean isManual = control.getGainMode() == AudioInputControl.GAIN_MODE_MANUAL
                || control.getGainMode() == AudioInputControl.GAIN_MODE_MANUAL_ONLY;
        boolean isActive =
                control.getAudioInputStatus() == AudioInputControl.AUDIO_INPUT_STATUS_ACTIVE;

        return isAmbientControl && isManual && isActive;
    }

    /**
     * Callback providing information about the status and received events of
     * {@link AmbientVolumeController}.
     */
    public interface AmbientVolumeControlCallback {

        /** This method is called when the Volume Control Service is connected */
        default void onVolumeControlServiceConnected() {
        }

        /**
         * This method is called when one of the remote device's ambient control point's gain
         * settings value is changed.
         *
         * @param device the remote device
         * @param gainSettings the new gain setting value
         */
        default void onAmbientChanged(@NonNull BluetoothDevice device, int gainSettings) {
        }

        /**
         * This method is called when one of the remote device's ambient control point's mute
         * state is changed.
         *
         * @param device the remote device
         * @param mute the new mute state
         */
        default void onMuteChanged(@NonNull BluetoothDevice device, int mute) {
        }

        /**
         * This method is called when any command to the remote device's ambient control point
         * is failed.
         *
         * @param device the remote device.
         */
        default void onCommandFailed(@NonNull BluetoothDevice device) {
        }
    }

    /**
     * A wrapper callback that will pass {@link AudioInputControl.AudioInputCallback} with extra
     * device information to {@link AmbientVolumeControlCallback}.
     */
    class AmbientCallback implements AudioInputControl.AudioInputCallback {

        private final BluetoothDevice mDevice;
        private final AmbientVolumeControlCallback mCallback;

        AmbientCallback(@NonNull BluetoothDevice device,
                @Nullable AmbientVolumeControlCallback callback) {
            mDevice = device;
            mCallback = callback;
        }

        @Override
        public void onGainSettingChanged(int gainSetting) {
            if (mCallback != null) {
                synchronized (mDeviceAmbientStateMap) {
                    RemoteAmbientState previousState = mDeviceAmbientStateMap.get(mDevice);
                    if (previousState.gainSetting != gainSetting) {
                        mCallback.onAmbientChanged(mDevice, gainSetting);
                    }
                }
            }
        }

        @Override
        public void onSetGainSettingFailed() {
            Log.w(TAG, "onSetGainSettingFailed, device=" + mDevice);
            if (mCallback != null) {
                mCallback.onCommandFailed(mDevice);
            }
        }

        @Override
        public void onMuteChanged(int mute) {
            if (mCallback != null) {
                synchronized (mDeviceAmbientStateMap) {
                    RemoteAmbientState previousState = mDeviceAmbientStateMap.get(mDevice);
                    if (previousState.mute != mute) {
                        mCallback.onMuteChanged(mDevice, mute);
                    }
                }
            }
        }

        @Override
        public void onSetMuteFailed() {
            Log.w(TAG, "onSetMuteFailed, device=" + mDevice);
            if (mCallback != null) {
                mCallback.onCommandFailed(mDevice);
            }
        }
    }

    public record RemoteAmbientState(int gainSetting, int mute) {
        public boolean isMutable() {
            return mute != MUTE_DISABLED;
        }
        public boolean isMuted() {
            return mute == MUTE_MUTED;
        }
    }
}
