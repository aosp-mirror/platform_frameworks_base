/*
 * Copyright (C) 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/license/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package android.location;

import android.hardware.location.GeofenceHardwareRequestParcelable;

/**
 * Fused Geofence Hardware interface.
 *
 * <p>This interface is the basic set of supported functionality by Fused Hardware modules that offer
 * Geofencing capabilities.
 *
 * All operations are asynchronous and the status codes can be obtained via a set of callbacks.
 *
 * @hide
 */
interface IFusedGeofenceHardware {
    /**
     * Flags if the interface functionality is supported by the platform.
     *
     * @return true if the functionality is supported, false otherwise.
     */
    boolean isSupported();

    /**
     * Adds a given list of geofences to the system.
     *
     * @param geofenceRequestsArray    The list of geofences to add.
     */
    void addGeofences(in GeofenceHardwareRequestParcelable[] geofenceRequestsArray);

    /**
     * Removes a give list of geofences from the system.
     *
     * @param geofences     The list of geofences to remove.
     */
    void removeGeofences(in int[] geofenceIds);

    /**
     * Pauses monitoring a particular geofence.
     * 
     * @param geofenceId    The geofence to pause monitoring.
     */
    void pauseMonitoringGeofence(in int geofenceId);

    /**
     * Resumes monitoring a particular geofence.
     *
     * @param geofenceid            The geofence to resume monitoring.
     * @param transitionsToMonitor  The transitions to monitor upon resume.
     *
     * Remarks: keep naming of geofence request options consistent with the naming used in
     *          GeofenceHardwareRequest
     */
    void resumeMonitoringGeofence(in int geofenceId, in int monitorTransitions);

    /**
     * Modifies the request options if a geofence that is already known by the
     * system.
     *  
     * @param geofenceId                    The geofence to modify.
     * @param lastTransition                The last known transition state of
     *                                      the geofence.
     * @param monitorTransitions            The set of transitions to monitor.
     * @param notificationResponsiveness    The notification responsivness needed.
     * @param unknownTimer                  The time span associated with the.
     * @param sourcesToUse                  The source technologies to use.
     *
     * Remarks: keep the options as separate fields to be able to leverage the class
     * GeofenceHardwareRequest without any changes
     */
    void modifyGeofenceOptions(
            in int geofenceId,
            in int lastTransition,
            in int monitorTransitions,
            in int notificationResponsiveness,
            in int unknownTimer,
            in int sourcesToUse);
}
