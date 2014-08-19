/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.location;

import android.annotation.SystemApi;
import android.location.Location;

/**
 * The callback class associated with the status change of hardware monitors
 * in {@link GeofenceHardware}
 *
 * @hide
 */
@SystemApi
public abstract class GeofenceHardwareMonitorCallback {
    /**
     * The callback called when the state of a monitoring system changes.
     * {@link GeofenceHardware#MONITORING_TYPE_GPS_HARDWARE} is an example of a
     * monitoring system.
     *
     * @deprecated use {@link #onMonitoringSystemChange(GeofenceHardwareMonitorEvent)} instead.
     * NOTE: this API is will remain to be called on Android API 21 and above for backwards
     * compatibility. But clients must stop implementing it when updating their code.
     *
     * @param monitoringType The type of the monitoring system.
     * @param available Indicates whether the system is currently available or not.
     * @param location The last known location according to the monitoring system.
     */
    @Deprecated
    public void onMonitoringSystemChange(int monitoringType, boolean available, Location location) {
    }

    /**
     * The callback called when the sate of a monitoring system changes.
     * {@link GeofenceHardware#MONITORING_TYPE_GPS_HARDWARE} is an example of a monitoring system.
     * {@link GeofenceHardware#MONITOR_CURRENTLY_AVAILABLE} is an example of a monitoring status.
     * {@link GeofenceHardware#SOURCE_TECHNOLOGY_GNSS} is an example of a source.
     *
     * This callback must be used instead of
     * {@link #onMonitoringSystemChange(int, boolean, android.location.Location)}.
     *
     * NOTE: this API is only called on Android API 21 and above.
     *
     * @param event An object representing the monitoring system change event.
     */
    public void onMonitoringSystemChange(GeofenceHardwareMonitorEvent event) {}
}
