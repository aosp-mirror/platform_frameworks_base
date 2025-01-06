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

package com.android.systemui.accessibility.hearingaid;

import com.android.settingslib.bluetooth.HearingAidDeviceManager.ConnectionStatus;

/**
 * A utility class to get the hearing device status drawable and its description for the
 * given connection status. Hearing device status drawable combine with base and indicator
 * drawable.
 */
public final class HearingDeviceStatusDrawableInfo {

    private static final StatusDrawableInfo DRAWABLE_DEFAULT_INFO = new StatusDrawableInfo(
            com.android.internal.R.drawable.ic_accessibility_hearing_aid,
            0,
            0);
    private static final StatusDrawableInfo DRAWABLE_DISCONNECTED_INFO = new StatusDrawableInfo(
            com.android.internal.R.drawable.ic_accessibility_hearing_aid_disconnected,
            0,
            com.android.internal.R.string.hearing_device_status_disconnected);
    private static final StatusDrawableInfo DRAWABLE_CONNECTED_INFO = new StatusDrawableInfo(
            com.android.internal.R.drawable.ic_accessibility_hearing_aid,
            com.android.internal.R.drawable.ic_accessibility_hearing_aid_blue_dot,
            com.android.internal.R.string.hearing_device_status_connected);
    private static final StatusDrawableInfo DRAWABLE_ACTIVE_INFO = new StatusDrawableInfo(
            com.android.internal.R.drawable.ic_accessibility_hearing_aid,
            com.android.internal.R.drawable.ic_accessibility_hearing_aid_green_dot,
            com.android.internal.R.string.hearing_device_status_active);

    private HearingDeviceStatusDrawableInfo() {}

    /**
     * Returns the corresponding {@link StatusDrawableInfo} for the given {@link ConnectionStatus}.
     */
    public static StatusDrawableInfo get(@ConnectionStatus int status) {
        return switch (status) {
            case ConnectionStatus.DISCONNECTED -> DRAWABLE_DISCONNECTED_INFO;
            case ConnectionStatus.CONNECTED -> DRAWABLE_CONNECTED_INFO;
            case ConnectionStatus.ACTIVE -> DRAWABLE_ACTIVE_INFO;
            // TODO: b/357882387 - Handle to show connecting or disconnecting status drawable
            case ConnectionStatus.CONNECTING_OR_DISCONNECTING, ConnectionStatus.NO_DEVICE_BONDED ->
                    DRAWABLE_DEFAULT_INFO;
            default -> DRAWABLE_DEFAULT_INFO;
        };
    }

    /**
     * A data class that holds the base drawable, indicator drawable and state description to
     * represent hearing device connection status.
     *
     * @param baseDrawableId the base drawable id for the hearing device status
     * @param indicatorDrawableId the indicator drawable id for the hearing device status
     * @param stateDescriptionId the description for the hearing device status
     */
    public record StatusDrawableInfo(int baseDrawableId, int indicatorDrawableId,
                                     int stateDescriptionId) {
    }
}
