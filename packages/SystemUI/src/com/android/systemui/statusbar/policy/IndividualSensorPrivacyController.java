/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.hardware.SensorPrivacyManager.Sensors.Sensor;
import android.hardware.SensorPrivacyManager.Sources.Source;

public interface IndividualSensorPrivacyController extends
        CallbackController<IndividualSensorPrivacyController.Callback> {
    void init();

    boolean supportsSensorToggle(@Sensor int sensor);

    boolean isSensorBlocked(@Sensor int sensor);

    /**
     * Returns {@code true} if the given sensor is blocked by a hardware toggle, {@code false}
     * if the sensor is not blocked or blocked by a software toggle.
     */
    boolean isSensorBlockedByHardwareToggle(@Sensor int sensor);

    void setSensorBlocked(@Source int source, @Sensor int sensor, boolean blocked);

    void suppressSensorPrivacyReminders(int sensor, boolean suppress);

    /**
     * @return whether lock screen authentication is required to change the toggle state
     */
    boolean requiresAuthentication();

    interface Callback {
        void onSensorBlockedChanged(@Sensor int sensor, boolean blocked);
    }
}
