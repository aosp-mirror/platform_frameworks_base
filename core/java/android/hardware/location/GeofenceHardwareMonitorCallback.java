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

import android.location.Location;

/**
 * The callback class associated with the status change of hardware montiors
 * in {@link GeofenceHardware}
 */
public abstract class GeofenceHardwareMonitorCallback {
    /**
     * The callback called when the state of a monitoring system changes.
     * {@link GeofenceHardware#MONITORING_TYPE_GPS_HARDWARE} is an example of a
     * monitoring system
     *
     * @param monitoringType The type of the monitoring system.
     * @param available Indicates whether the system is currenty available or not.
     * @param location The last known location according to the monitoring system.
     */
    public void onMonitoringSystemChange(int monitoringType, boolean available, Location location) {
    }
}
