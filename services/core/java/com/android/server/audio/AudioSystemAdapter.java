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

package com.android.server.audio;

import android.annotation.NonNull;
import android.media.AudioDeviceAttributes;
import android.media.AudioSystem;
import android.util.Log;

/**
 * Provides an adapter to access functionality of the android.media.AudioSystem class for device
 * related functionality.
 * Use the "real" AudioSystem through the default adapter.
 * Use the "always ok" adapter to avoid dealing with the APM behaviors during a test.
 */
public class AudioSystemAdapter {

    /**
     * Create a wrapper around the {@link AudioSystem} static methods, all functions are directly
     * forwarded to the AudioSystem class.
     * @return an adapter around AudioSystem
     */
    static final @NonNull AudioSystemAdapter getDefaultAdapter() {
        return new AudioSystemAdapter();
    }

    /**
     * Create an adapter for AudioSystem that always succeeds, and does nothing.
     * Overridden methods can be configured
     * @return a no-op AudioSystem adapter with configurable adapter
     */
    static final @NonNull AudioSystemAdapter getConfigurableAdapter() {
        return new AudioSystemConfigurableAdapter();
    }

    /**
     * Same as {@link AudioSystem#setDeviceConnectionState(int, int, String, String, int)}
     * @param device
     * @param state
     * @param deviceAddress
     * @param deviceName
     * @param codecFormat
     * @return
     */
    public int setDeviceConnectionState(int device, int state, String deviceAddress,
                                        String deviceName, int codecFormat) {
        return AudioSystem.setDeviceConnectionState(device, state, deviceAddress, deviceName,
                codecFormat);
    }

    /**
     * Same as {@link AudioSystem#getDeviceConnectionState(int, String)}
     * @param device
     * @param deviceAddress
     * @return
     */
    public int getDeviceConnectionState(int device, String deviceAddress) {
        return AudioSystem.getDeviceConnectionState(device, deviceAddress);
    }

    /**
     * Same as {@link AudioSystem#handleDeviceConfigChange(int, String, String, int)}
     * @param device
     * @param deviceAddress
     * @param deviceName
     * @param codecFormat
     * @return
     */
    public int handleDeviceConfigChange(int device, String deviceAddress,
                                               String deviceName, int codecFormat) {
        return AudioSystem.handleDeviceConfigChange(device, deviceAddress, deviceName,
                codecFormat);
    }

    /**
     * Same as {@link AudioSystem#setPreferredDeviceForStrategy(int, AudioDeviceAttributes)}
     * @param strategy
     * @param device
     * @return
     */
    public int setPreferredDeviceForStrategy(int strategy, @NonNull AudioDeviceAttributes device) {
        return AudioSystem.setPreferredDeviceForStrategy(strategy, device);
    }

    /**
     * Same as {@link AudioSystem#removePreferredDeviceForStrategy(int)}
     * @param strategy
     * @return
     */
    public int removePreferredDeviceForStrategy(int strategy) {
        return AudioSystem.removePreferredDeviceForStrategy(strategy);
    }

    /**
     * Same as {@link AudioSystem#setParameters(String)}
     * @param keyValuePairs
     * @return
     */
    public int setParameters(String keyValuePairs) {
        return AudioSystem.setParameters(keyValuePairs);
    }

    /**
     * Same as {@link AudioSystem#isMicrophoneMuted()}}
     * Checks whether the microphone mute is on or off.
     * @return true if microphone is muted, false if it's not
     */
    public boolean isMicrophoneMuted() {
        return AudioSystem.isMicrophoneMuted();
    }

    /**
     * Same as {@link AudioSystem#muteMicrophone(boolean)}
     * Sets the microphone mute on or off.
     *
     * @param on set <var>true</var> to mute the microphone;
     *           <var>false</var> to turn mute off
     * @return command completion status see AUDIO_STATUS_OK, see AUDIO_STATUS_ERROR
     */
    public int muteMicrophone(boolean on) {
        return AudioSystem.muteMicrophone(on);
    }

    /**
     * Same as {@link AudioSystem#setCurrentImeUid(int)}
     * Communicate UID of current InputMethodService to audio policy service.
     */
    public int setCurrentImeUid(int uid) {
        return AudioSystem.setCurrentImeUid(uid);
    }

    //--------------------------------------------------------------------
    protected static class AudioSystemConfigurableAdapter extends AudioSystemAdapter {
        private static final String TAG = "ASA";
        private boolean mIsMicMuted = false;
        private boolean mMuteMicrophoneFails = false;

        public void configureIsMicrophoneMuted(boolean muted) {
            mIsMicMuted = muted;
        }

        public void configureMuteMicrophoneToFail(boolean fail) {
            mMuteMicrophoneFails = fail;
        }

        //-----------------------------------------------------------------
        // Overrides of AudioSystemAdapter
        @Override
        public int setDeviceConnectionState(int device, int state, String deviceAddress,
                                            String deviceName, int codecFormat) {
            Log.i(TAG, String.format("setDeviceConnectionState(0x%s, %s, %s, 0x%s",
                    Integer.toHexString(device), state, deviceAddress, deviceName,
                    Integer.toHexString(codecFormat)));
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
        public int setPreferredDeviceForStrategy(int strategy,
                                                 @NonNull AudioDeviceAttributes device) {
            return AudioSystem.AUDIO_STATUS_OK;
        }

        @Override
        public int removePreferredDeviceForStrategy(int strategy) {
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
    }
}
