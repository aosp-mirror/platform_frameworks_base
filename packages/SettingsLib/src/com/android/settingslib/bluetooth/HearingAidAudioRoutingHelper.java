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

package com.android.settingslib.bluetooth;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A helper class to configure the routing strategy for hearing aids.
 */
public class HearingAidAudioRoutingHelper {

    private final AudioManager mAudioManager;

    public HearingAidAudioRoutingHelper(Context context) {
        mAudioManager = context.getSystemService(AudioManager.class);
    }

    /**
     * Gets the list of {@link AudioProductStrategy} referred by the given list of usage values
     * defined in {@link AudioAttributes}
     */
    public List<AudioProductStrategy> getSupportedStrategies(int[] attributeSdkUsageList) {
        final List<AudioAttributes> audioAttrList = new ArrayList<>(attributeSdkUsageList.length);
        for (int attributeSdkUsage : attributeSdkUsageList) {
            audioAttrList.add(new AudioAttributes.Builder().setUsage(attributeSdkUsage).build());
        }

        final List<AudioProductStrategy> allStrategies = getAudioProductStrategies();
        final List<AudioProductStrategy> supportedStrategies = new ArrayList<>();
        for (AudioProductStrategy strategy : allStrategies) {
            for (AudioAttributes audioAttr : audioAttrList) {
                if (strategy.supportsAudioAttributes(audioAttr)) {
                    supportedStrategies.add(strategy);
                }
            }
        }

        return supportedStrategies.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Sets the preferred device for the given strategies.
     *
     * @param supportedStrategies A list of {@link AudioProductStrategy} used to configure audio
     *                            routing
     * @param hearingDevice {@link AudioDeviceAttributes} of the device to be changed in audio
     *                      routing
     * @param routingValue one of value defined in
     *                     {@link HearingAidAudioRoutingConstants.RoutingValue}, denotes routing
     *                     destination.
     * @return {code true} if the routing value successfully configure
     */
    public boolean setPreferredDeviceRoutingStrategies(
            List<AudioProductStrategy> supportedStrategies, AudioDeviceAttributes hearingDevice,
            @HearingAidAudioRoutingConstants.RoutingValue int routingValue) {
        boolean status;
        switch (routingValue) {
            case HearingAidAudioRoutingConstants.RoutingValue.AUTO:
                status = removePreferredDeviceForStrategies(supportedStrategies);
                return status;
            case HearingAidAudioRoutingConstants.RoutingValue.HEARING_DEVICE:
                status = removePreferredDeviceForStrategies(supportedStrategies);
                status &= setPreferredDeviceForStrategies(supportedStrategies, hearingDevice);
                return status;
            case HearingAidAudioRoutingConstants.RoutingValue.DEVICE_SPEAKER:
                status = removePreferredDeviceForStrategies(supportedStrategies);
                status &= setPreferredDeviceForStrategies(supportedStrategies,
                        HearingAidAudioRoutingConstants.DEVICE_SPEAKER_OUT);
                return status;
            default:
                throw new IllegalArgumentException("Unexpected routingValue: " + routingValue);
        }
    }

    /**
     * Gets the matched hearing device {@link AudioDeviceAttributes} for {@code device}.
     *
     * <p>Will also try to match the {@link CachedBluetoothDevice#getSubDevice()} of {@code device}
     *
     * @param device the {@link CachedBluetoothDevice} need to be hearing aid device
     * @return the requested AudioDeviceAttributes or {@code null} if not match
     */
    @Nullable
    public AudioDeviceAttributes getMatchedHearingDeviceAttributes(CachedBluetoothDevice device) {
        if (device == null || !device.isHearingAidDevice()) {
            return null;
        }

        AudioDeviceInfo[] audioDevices = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo audioDevice : audioDevices) {
            // ASHA for TYPE_HEARING_AID, HAP for TYPE_BLE_HEADSET
            if (audioDevice.getType() == AudioDeviceInfo.TYPE_HEARING_AID
                    || audioDevice.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                if (matchAddress(device, audioDevice)) {
                    return new AudioDeviceAttributes(audioDevice);
                }
            }
        }
        return null;
    }

    private boolean matchAddress(CachedBluetoothDevice device, AudioDeviceInfo audioDevice) {
        final String audioDeviceAddress = audioDevice.getAddress();
        final CachedBluetoothDevice subDevice = device.getSubDevice();
        final Set<CachedBluetoothDevice> memberDevices = device.getMemberDevice();

        return device.getAddress().equals(audioDeviceAddress)
                || (subDevice != null && subDevice.getAddress().equals(audioDeviceAddress))
                || (!memberDevices.isEmpty() && memberDevices.stream().anyMatch(
                    m -> m.getAddress().equals(audioDeviceAddress)));
    }

    private boolean setPreferredDeviceForStrategies(List<AudioProductStrategy> strategies,
            AudioDeviceAttributes audioDevice) {
        boolean status = true;
        for (AudioProductStrategy strategy : strategies) {
            status &= mAudioManager.setPreferredDeviceForStrategy(strategy, audioDevice);

        }

        return status;
    }

    private boolean removePreferredDeviceForStrategies(List<AudioProductStrategy> strategies) {
        boolean status = true;
        for (AudioProductStrategy strategy : strategies) {
            if (mAudioManager.getPreferredDeviceForStrategy(strategy) != null) {
                status &= mAudioManager.removePreferredDeviceForStrategy(strategy);
            }
        }

        return status;
    }

    @VisibleForTesting
    public List<AudioProductStrategy> getAudioProductStrategies() {
        return AudioManager.getAudioProductStrategies();
    }
}
