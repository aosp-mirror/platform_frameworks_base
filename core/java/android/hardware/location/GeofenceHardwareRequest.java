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
 * This class represents the characteristics of the geofence.
 *
 * <p> Use this in conjunction with {@link GeofenceHardware} APIs.
 */

public final class GeofenceHardwareRequest {
    static final int GEOFENCE_TYPE_CIRCLE = 0;
    private int mType;
    private double mLatitude;
    private double mLongitude;
    private double mRadius;
    private int mLastTransition = GeofenceHardware.GEOFENCE_UNCERTAIN;
    private int mUnknownTimer = 30000; // 30 secs
    private int mMonitorTransitions = GeofenceHardware.GEOFENCE_UNCERTAIN |
        GeofenceHardware.GEOFENCE_ENTERED | GeofenceHardware.GEOFENCE_EXITED;
    private int mNotificationResponsiveness = 5000; // 5 secs

    private void setCircularGeofence(double latitude, double longitude, double radius) {
        mLatitude = latitude;
        mLongitude = longitude;
        mRadius = radius;
        mType  = GEOFENCE_TYPE_CIRCLE;
    }

    /**
     * Create a circular geofence.
     *
     * @param latitude Latitude of the geofence
     * @param longitude Longitude of the geofence
     * @param radius Radius of the geofence (in meters)
     */
    public static GeofenceHardwareRequest createCircularGeofence(double latitude,
            double longitude, double radius) {
        GeofenceHardwareRequest geofenceRequest = new GeofenceHardwareRequest();
        geofenceRequest.setCircularGeofence(latitude, longitude, radius);
        return geofenceRequest;
    }

    /**
     * Set the last known transition of the geofence.
     *
     * @param lastTransition The current state of the geofence. Can be one of
     *        {@link GeofenceHardware#GEOFENCE_ENTERED}, {@link GeofenceHardware#GEOFENCE_EXITED},
     *        {@link GeofenceHardware#GEOFENCE_UNCERTAIN}.
     */
    public void setLastTransition(int lastTransition) {
        mLastTransition = lastTransition;
    }

    /**
     * Set the unknown timer for this geofence.
     *
     * @param unknownTimer  The time limit after which the
     *        {@link GeofenceHardware#GEOFENCE_UNCERTAIN} transition
     *        should be triggered. This paramter is defined in milliseconds.
     */
    public void setUnknownTimer(int unknownTimer) {
        mUnknownTimer = unknownTimer;
    }

    /**
     * Set the transitions to be monitored.
     *
     * @param monitorTransitions Bitwise OR of {@link GeofenceHardware#GEOFENCE_ENTERED},
     *        {@link GeofenceHardware#GEOFENCE_EXITED}, {@link GeofenceHardware#GEOFENCE_UNCERTAIN}
     */
    public void setMonitorTransitions(int monitorTransitions) {
        mMonitorTransitions = monitorTransitions;
    }

    /**
     * Set the notification responsiveness of the geofence.
     *
     * @param notificationResponsiveness (milliseconds) Defines the best-effort description
     *        of how soon should the callback be called when the transition
     *        associated with the Geofence is triggered. For instance, if
     *        set to 1000 millseconds with {@link GeofenceHardware#GEOFENCE_ENTERED},
     *        the callback will be called 1000 milliseconds within entering
     *        the geofence.
     */
    public void setNotificationResponsiveness(int notificationResponsiveness) {
       mNotificationResponsiveness = notificationResponsiveness;
    }

    /**
     * Returns the latitude of this geofence.
     */
    public double getLatitude() {
        return mLatitude;
    }

    /**
     * Returns the longitude of this geofence.
     */
    public double getLongitude() {
        return mLongitude;
    }

    /**
     * Returns the radius of this geofence.
     */
    public double getRadius() {
        return mRadius;
    }

    /**
     * Returns transitions monitored for this geofence.
     */
    public int getMonitorTransitions() {
        return mMonitorTransitions;
    }

    /**
     * Returns the unknownTimer of this geofence.
     */
    public int getUnknownTimer() {
        return mUnknownTimer;
    }

    /**
     * Returns the notification responsiveness of this geofence.
     */
    public int getNotificationResponsiveness() {
        return mNotificationResponsiveness;
    }

    /**
     * Returns the last transition of this geofence.
     */
    public int getLastTransition() {
        return mLastTransition;
    }

    int getType() {
        return mType;
    }
}
