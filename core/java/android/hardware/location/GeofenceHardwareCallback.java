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
 * The callback class associated with the APIs in {@link GeofenceHardware}
 *
 * @hide
 */
@SystemApi
public abstract class GeofenceHardwareCallback {
    /**
     * The callback called when there is a transition to report for the specific
     * geofence.
     *
     * @param geofenceId The geofence ID of the geofence
     * @param transition One of {@link GeofenceHardware#GEOFENCE_ENTERED},
     *        {@link GeofenceHardware#GEOFENCE_EXITED}, {@link GeofenceHardware#GEOFENCE_UNCERTAIN}
     * @param location The last known location according to the monitoring system.
     * @param timestamp The timestamp (elapsed real time in milliseconds) when the transition was
     *        detected
     * @param monitoringType Type of the monitoring system.
     */
    public void onGeofenceTransition(int geofenceId, int transition, Location location,
            long timestamp, int monitoringType) {
    }

    /**
     * The callback called to notify the success or failure of the add call.
     *
     * @param geofenceId The ID of the geofence.
     * @param status One of {@link GeofenceHardware#GEOFENCE_SUCCESS},
     *        {@link GeofenceHardware#GEOFENCE_ERROR_ID_EXISTS},
     *        {@link GeofenceHardware#GEOFENCE_ERROR_INVALID_TRANSITION},
     *        {@link GeofenceHardware#GEOFENCE_ERROR_TOO_MANY_GEOFENCES},
     *        {@link GeofenceHardware#GEOFENCE_FAILURE}
     */
    public void onGeofenceAdd(int geofenceId, int status) {
    }

    /**
     * The callback called to notify the success or failure of the remove call.
     *
     * @param geofenceId The ID of the geofence.
     * @param status  One of {@link GeofenceHardware#GEOFENCE_SUCCESS},
     *        {@link GeofenceHardware#GEOFENCE_ERROR_ID_UNKNOWN},
     *        {@link GeofenceHardware#GEOFENCE_FAILURE}
     */
    public void onGeofenceRemove(int geofenceId, int status) {
    }

    /**
     * The callback called to notify the success or failure of the pause call.
     *
     * @param geofenceId The ID of the geofence.
     * @param status One of {@link GeofenceHardware#GEOFENCE_SUCCESS},
     *        {@link GeofenceHardware#GEOFENCE_ERROR_ID_UNKNOWN},
     *        {@link GeofenceHardware#GEOFENCE_FAILURE}
     */
    public void onGeofencePause(int geofenceId, int status) {
    }

    /**
     * The callback called to notify the success or failure of the resume call.
     *
     * @param geofenceId The ID of the geofence.
     * @param status One of {@link GeofenceHardware#GEOFENCE_SUCCESS},
     *        {@link GeofenceHardware#GEOFENCE_ERROR_ID_UNKNOWN},
     *        {@link GeofenceHardware#GEOFENCE_ERROR_INVALID_TRANSITION},
     *        {@link GeofenceHardware#GEOFENCE_FAILURE}
     */
    public void onGeofenceResume(int geofenceId, int status) {
    }
}
