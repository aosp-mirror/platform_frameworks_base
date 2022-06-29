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

package com.android.systemui.power;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

/**
 * Events related to the battery warning.
 */
public class BatteryWarningEvents {

    /** Enums for logging low battery warning notification and dialog */
    public enum LowBatteryWarningEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Low battery warning notification displayed")
        LOW_BATTERY_NOTIFICATION(1048),

        @UiEvent(doc = "Low battery warning notification positive button clicked")
        LOW_BATTERY_NOTIFICATION_TURN_ON(1049),

        @UiEvent(doc = "Low battery warning notification negative button clicked")
        LOW_BATTERY_NOTIFICATION_CANCEL(1050),

        @UiEvent(doc = "Low battery warning notification content clicked")
        LOW_BATTERY_NOTIFICATION_SETTINGS(1051),

        @UiEvent(doc = "Battery saver confirm dialog displayed")
        SAVER_CONFIRM_DIALOG(1052),

        @UiEvent(doc = "Battery saver confirm dialog positive button clicked")
        SAVER_CONFIRM_OK(1053),

        @UiEvent(doc = "Battery saver confirm dialog negative button clicked")
        SAVER_CONFIRM_CANCEL(1054),

        @UiEvent(doc = "Battery saver confirm dialog dismissed")
        SAVER_CONFIRM_DISMISS(1055);

        private final int mId;

        LowBatteryWarningEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }
}
