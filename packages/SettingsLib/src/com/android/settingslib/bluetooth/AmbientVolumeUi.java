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

import static com.android.settingslib.bluetooth.HearingAidInfo.DeviceSide.SIDE_LEFT;
import static com.android.settingslib.bluetooth.HearingAidInfo.DeviceSide.SIDE_RIGHT;

import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;

/** Interface for the ambient volume UI. */
public interface AmbientVolumeUi {

    /** Interface definition for a callback to be invoked when event happens in AmbientVolumeUi. */
    interface AmbientVolumeUiListener {
        /** Called when the expand icon is clicked. */
        void onExpandIconClick();

        /** Called when the ambient volume icon is clicked. */
        void onAmbientVolumeIconClick();

        /** Called when the slider of the specified side is changed. */
        void onSliderValueChange(int side, int value);
    };

    /** The rotation degree of the expand icon when the UI is in collapsed mode. */
    float ROTATION_COLLAPSED = 0f;
    /** The rotation degree of the expand icon when the UI is in expanded mode. */
    float ROTATION_EXPANDED = 180f;

    /**
     * The default ambient volume level for hearing device ambient volume icon
     *
     * <p> This icon visually represents the current ambient volume. It displays separate
     * levels for the left and right sides, each with 5 levels ranging from 0 to 4.
     *
     * <p> To represent the combined left/right levels with a single value, the following
     * calculation is used:
     *      finalLevel = (leftLevel * 5) + rightLevel
     * For example:
     * <ul>
     *    <li>If left level is 2 and right level is 3, the final level will be 13 (2 * 5 + 3)</li>
     *    <li>If both left and right levels are 0, the final level will be 0</li>
     *    <li>If both left and right levels are 4, the final level will be 24</li>
     * </ul>
     */
    int AMBIENT_VOLUME_LEVEL_DEFAULT = 24;
    /**
     * The minimum ambient volume level for hearing device ambient volume icon
     *
     * @see #AMBIENT_VOLUME_LEVEL_DEFAULT
     */
    int AMBIENT_VOLUME_LEVEL_MIN = 0;
    /**
     * The maximum ambient volume level for hearing device ambient volume icon
     *
     * @see #AMBIENT_VOLUME_LEVEL_DEFAULT
     */
    int AMBIENT_VOLUME_LEVEL_MAX = 24;

    /**
     * Ths side identifier for slider in collapsed mode which can unified control the ambient
     * volume of all devices in the same set.
     */
    int SIDE_UNIFIED = 999;

    /** All valid side of the sliders in the UI. */
    List<Integer> VALID_SIDES = List.of(SIDE_UNIFIED, SIDE_LEFT, SIDE_RIGHT);

    /** Sets if the UI is visible. */
    void setVisible(boolean visible);

    /**
     * Sets if the UI is expandable between expanded and collapsed mode.
     *
     * <p> If the UI is not expandable, it implies the UI will always stay in collapsed mode
     */
    void setExpandable(boolean expandable);

    /** @return if the UI is expandable. */
    boolean isExpandable();

    /** Sets if the UI is in expanded mode. */
    void setExpanded(boolean expanded);

    /** @return if the UI is in expanded mode. */
    boolean isExpanded();

    /**
     * Sets if the UI is capable to mute the ambient of the remote device.
     *
     * <p> If the value is {@code false}, it implies the remote device ambient will always be
     * unmute and can not be mute from the UI
     */
    void setMutable(boolean mutable);

    /** @return if the UI is capable to mute the ambient of remote device. */
    boolean isMutable();

    /** Sets if the UI shows mute state. */
    void setMuted(boolean muted);

    /** @return if the UI shows mute state */
    boolean isMuted();

    /**
     * Sets listener on the UI.
     *
     * @see AmbientVolumeUiListener
     */
    void setListener(@Nullable AmbientVolumeUiListener listener);

    /**
     * Sets up sliders in the UI.
     *
     * <p> For each side of device, the UI should hava a corresponding slider to control it's
     * ambient volume.
     * <p> For all devices in the same set, the UI should have a slider to control all devices'
     * ambient volume at once.
     * @param sideToDeviceMap the side and device mapping of all devices in the same set
     */
    void setupSliders(@NonNull Map<Integer, BluetoothDevice> sideToDeviceMap);

    /**
     * Sets if the slider is enabled.
     *
     * @param side the side of the slider
     * @param enabled the enabled state
     */
    void setSliderEnabled(int side, boolean enabled);

    /**
     * Sets the slider value.
     *
     * @param side the side of the slider
     * @param value the ambient value
     */
    void setSliderValue(int side, int value);

    /**
     * Sets the slider's minimum and maximum value.
     *
     * @param side the side of the slider
     * @param min the minimum ambient value
     * @param max the maximum ambient value
     */
    void setSliderRange(int side, int min, int max);

    /** Updates the UI according to current state. */
    void updateLayout();
}
