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

import android.content.Context;
import android.location.Location;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


/**
 * This class handles geofences managed by various hardware subsystems. It contains
 * the public APIs that is needed to accomplish the task.
 *
 * <p>The APIs should not be called directly by the app developers. A higher level api
 * which abstracts the hardware should be used instead. All the checks are done by the higher
 * level public API. Any needed locking should be handled by the higher level API.
 *
 * <p> There are 3 states associated with a Geofence: Inside, Outside, Unknown.
 * There are 3 transitions: {@link #GEOFENCE_ENTERED}, {@link #GEOFENCE_EXITED},
 * {@link #GEOFENCE_UNCERTAIN}. The APIs only expose the transitions.
 *
 * <p> Inside state: The hardware subsystem is reasonably confident that the user is inside
 * the geofence. Outside state: The hardware subsystem is reasonably confident that the user
 * is outside the geofence Unknown state: Unknown state can be interpreted as a state in which the
 * monitoring subsystem isn't confident enough that the user is either inside or
 * outside the Geofence. If the accuracy does not improve for a sufficient period of time,
 * the {@link #GEOFENCE_UNCERTAIN} transition would be triggered. If the accuracy improves later,
 * an appropriate transition would be triggered. The "reasonably confident" parameter
 * depends on the hardware system and the positioning algorithms used.
 * For instance, {@link #MONITORING_TYPE_GPS_HARDWARE} uses 95% as a confidence level.
 */
public final class GeofenceHardware {
    private IGeofenceHardware mService;

    // Hardware systems that do geofence monitoring.
    static final int NUM_MONITORS = 1;

    /**
     * Constant for geofence monitoring done by the GPS hardware.
     */
    public static final int MONITORING_TYPE_GPS_HARDWARE = 0;

    /**
     * Constant to indiciate that the monitoring system is currently
     * available for monitoring geofences.
     */
    public static final int MONITOR_CURRENTLY_AVAILABLE = 0;

    /**
     * Constant to indiciate that the monitoring system is currently
     * unavailable for monitoring geofences.
     */
    public static final int MONITOR_CURRENTLY_UNAVAILABLE = 1;

    /**
     * Constant to indiciate that the monitoring system is unsupported
     * for hardware geofence monitoring.
     */
    public static final int MONITOR_UNSUPPORTED = 2;

    // The following constants need to match geofence flags in gps.h
    /**
     * The constant to indicate that the user has entered the geofence.
     */
    public static final int GEOFENCE_ENTERED = 1<<0L;

    /**
     * The constant to indicate that the user has exited the geofence.
     */
    public static final int GEOFENCE_EXITED = 1<<1L;

    /**
     * The constant to indicate that the user is uncertain with respect to a
     * geofence.                                                  nn
     */
    public static final int GEOFENCE_UNCERTAIN = 1<<2L;

    /**
     * The constant used to indicate success of the particular geofence call
     */
    public static final int GEOFENCE_SUCCESS = 0;

    /**
     * The constant used to indicate that too many geofences have been registered.
     */
    public static final int GEOFENCE_ERROR_TOO_MANY_GEOFENCES = 1;

    /**
     * The constant used to indicate that the geofence id already exists.
     */
    public static final int GEOFENCE_ERROR_ID_EXISTS  = 2;

    /**
     * The constant used to indicate that the geofence id is unknown.
     */
    public static final int GEOFENCE_ERROR_ID_UNKNOWN = 3;

    /**
     * The constant used to indicate that the transition requested for the geofence is invalid.
     */
    public static final int GEOFENCE_ERROR_INVALID_TRANSITION = 4;

    /**
     * The constant used to indicate that the geofence operation has failed.
     */
    public static final int GEOFENCE_FAILURE = 5;

    static final int GPS_GEOFENCE_UNAVAILABLE = 1<<0L;
    static final int GPS_GEOFENCE_AVAILABLE = 1<<1L;

    private HashMap<GeofenceHardwareCallback, GeofenceHardwareCallbackWrapper>
            mCallbacks = new HashMap<GeofenceHardwareCallback, GeofenceHardwareCallbackWrapper>();
    /**
     * @hide
     */
    public GeofenceHardware(IGeofenceHardware service) {
        mService = service;
    }

    /**
     * Returns all the hardware geofence monitoring systems and their status.
     * Status can be one of {@link #MONITOR_CURRENTLY_AVAILABLE},
     * {@link #MONITOR_CURRENTLY_UNAVAILABLE} or {@link #MONITOR_UNSUPPORTED}
     *
     * <p> Some supported hardware monitoring systems might not be available
     * for monitoring geofences in certain scenarios. For example, when a user
     * enters a building, the GPS hardware subsystem might not be able monitor
     * geofences and will change from {@link #MONITOR_CURRENTLY_AVAILABLE} to
     * {@link #MONITOR_CURRENTLY_UNAVAILABLE}.
     *
     * <p> Requires {@link android.Manifest.permission#LOCATION_HARDWARE} permission to access
     * geofencing in hardware.
     *
     * @return An array indexed by the various monitoring types and their status.
     *         An array of length 0 is returned in case of errors.
     */
    public int[] getMonitoringTypesAndStatus() {
        try {
            return mService.getMonitoringTypesAndStatus();
        } catch (RemoteException e) {
        }
        return new int[0];
    }

    /**
     * Creates a circular geofence which is monitored by subsystems in the hardware.
     *
     * <p> When the device detects that is has entered, exited or is uncertain
     * about the area specified by the geofence, the given callback will be called.
     *
     * <p> The {@link GeofenceHardwareCallback#onGeofenceChange} callback will be called,
     * with the following parameters
     * <ul>
     * <li> The geofence Id
     * <li> The location object indicating the last known location.
     * <li> The transition associated with the geofence. One of
     *      {@link #GEOFENCE_ENTERED}, {@link #GEOFENCE_EXITED}, {@link #GEOFENCE_UNCERTAIN}
     * <li> The timestamp when the geofence transition occured.
     * <li> The monitoring type ({@link #MONITORING_TYPE_GPS_HARDWARE} is one such example)
     *      that was used.
     * </ul>
     *
     * <p> The geofence will be monitored by the subsystem specified by monitoring_type parameter.
     * The application does not need to hold a wakelock when the monitoring
     * is being done by the underlying hardware subsystem. If the same geofence Id is being
     * monitored by two different monitoring systems, the same id can be used for both calls, as
     * long as the same callback object is used.
     *
     * <p> Requires {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission when
     * {@link #MONITORING_TYPE_GPS_HARDWARE} is used.
     *
     * <p> Requires {@link android.Manifest.permission#LOCATION_HARDWARE} permission to access
     * geofencing in hardware.
     *
     * <p>This API should not be called directly by the app developers. A higher level api
     * which abstracts the hardware should be used instead. All the checks are done by the higher
     * level public API. Any needed locking should be handled by the higher level API.
     *
     * @param latitude Latitude of the area to be monitored.
     * @param longitude Longitude of the area to be monitored.
     * @param radius Radius (in meters) of the area to be monitored.
     * @param lastTransition The current state of the geofence. Can be one of
     *        {@link #GEOFENCE_ENTERED}, {@link #GEOFENCE_EXITED},
     *        {@link #GEOFENCE_UNCERTAIN}.
     * @param monitorTransitions Bitwise OR of {@link #GEOFENCE_ENTERED},
     *        {@link #GEOFENCE_EXITED}, {@link #GEOFENCE_UNCERTAIN}
     * @param notificationResponsivenes Defines the best-effort description
     *        of how soon should the callback be called when the transition
     *        associated with the Geofence is triggered. For instance, if
     *        set to 1000 millseconds with {@link #GEOFENCE_ENTERED},
     *        the callback will be called 1000 milliseconds within entering
     *        the geofence. This parameter is defined in milliseconds.
     * @param unknownTimer The time limit after which the
     *        {@link #GEOFENCE_UNCERTAIN} transition
     *        should be triggered. This paramter is defined in milliseconds.
     * @param monitoringType The type of the hardware subsystem that should be used
     *        to monitor the geofence.
     * @param callback {@link GeofenceHardwareCallback} that will be use to notify the
     *        transition.
     * @return true on success.
     */
    public boolean addCircularFence(int geofenceId, double latitude, double longitude,
            double radius, int lastTransition,int monitorTransitions, int notificationResponsivenes,
            int unknownTimer, int monitoringType, GeofenceHardwareCallback callback) {
        try {
            return mService.addCircularFence(geofenceId, latitude, longitude, radius,
                    lastTransition, monitorTransitions, notificationResponsivenes, unknownTimer,
                    monitoringType, getCallbackWrapper(callback));
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Removes a geofence added by {@link #addCircularFence} call.
     *
     * <p> Requires {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission when
     * {@link #MONITORING_TYPE_GPS_HARDWARE} is used.
     *
     * <p> Requires {@link android.Manifest.permission#LOCATION_HARDWARE} permission to access
     * geofencing in hardware.
     *
     * <p>This API should not be called directly by the app developers. A higher level api
     * which abstracts the hardware should be used instead. All the checks are done by the higher
     * level public API. Any needed locking should be handled by the higher level API.
     *
     * @param geofenceId The id of the geofence.
     * @param monitoringType The type of the hardware subsystem that should be used
     *        to monitor the geofence.
     * @return true on success.
     */
   public boolean removeGeofence(int geofenceId, int monitoringType) {
       try {
           return mService.removeGeofence(geofenceId, monitoringType);
       } catch (RemoteException e) {
       }
       return false;
   }

    /**
     * Pauses the monitoring of a geofence added by {@link #addCircularFence} call.
     *
     * <p> Requires {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission when
     * {@link #MONITORING_TYPE_GPS_HARDWARE} is used.
     *
     * <p> Requires {@link android.Manifest.permission#LOCATION_HARDWARE} permission to access
     * geofencing in hardware.
     *
     * <p>This API should not be called directly by the app developers. A higher level api
     * which abstracts the hardware should be used instead. All the checks are done by the higher
     * level public API. Any needed locking should be handled by the higher level API.
     *
     * @param geofenceId The id of the geofence.
     * @param monitoringType The type of the hardware subsystem that should be used
     *        to monitor the geofence.
     * @return true on success.
     */
    public boolean pauseGeofence(int geofenceId, int monitoringType) {
        try {
            return mService.pauseGeofence(geofenceId, monitoringType);
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Resumes the monitoring of a geofence added by {@link #pauseGeofence} call.
     *
     * <p> Requires {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission when
     * {@link #MONITORING_TYPE_GPS_HARDWARE} is used.
     *
     * <p> Requires {@link android.Manifest.permission#LOCATION_HARDWARE} permission to access
     * geofencing in hardware.
     *
     * <p>This API should not be called directly by the app developers. A higher level api
     * which abstracts the hardware should be used instead. All the checks are done by the higher
     * level public API. Any needed locking should be handled by the higher level API.
     *
     * @param geofenceId The id of the geofence.
     * @param monitorTransition Bitwise OR of {@link #GEOFENCE_ENTERED},
     *        {@link #GEOFENCE_EXITED}, {@link #GEOFENCE_UNCERTAIN}
     * @param monitoringType The type of the hardware subsystem that should be used
     *        to monitor the geofence.
     * @return true on success.
     */
    public boolean resumeGeofence(int geofenceId, int monitorTransition, int monitoringType) {
        try {
            return mService.resumeGeofence(geofenceId, monitorTransition, monitoringType);
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Register the callback to be notified when the state of a hardware geofence
     * monitoring system changes. For instance, it can change from
     * {@link #MONITOR_CURRENTLY_AVAILABLE} to {@link #MONITOR_CURRENTLY_UNAVAILABLE}
     *
     * <p> Requires {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission when
     * {@link #MONITORING_TYPE_GPS_HARDWARE} is used.
     *
     * <p> Requires {@link android.Manifest.permission#LOCATION_HARDWARE} permission to access
     * geofencing in hardware.
     *
     * <p>This API should not be called directly by the app developers. A higher level api
     * which abstracts the hardware should be used instead. All the checks are done by the higher
     * level public API. Any needed locking should be handled by the higher level API.
     *
     * <p> The same callback object can be used to be informed of geofence transitions
     * and state changes of the underlying hardware subsystem.
     *
     * @param monitoringType Type of the monitor
     * @param callback Callback that will be called.
     * @return true on success
     */
    public boolean registerForMonitorStateChangeCallback(int monitoringType,
            GeofenceHardwareCallback callback) {
        try {
            return mService.registerForMonitorStateChangeCallback(monitoringType,
                    getCallbackWrapper(callback));
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Unregister the callback that was used with {@link #registerForMonitorStateChangeCallback}
     * to notify when the state of the hardware geofence monitoring system changes.
     *
     * <p> Requires {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission when
     * {@link #MONITORING_TYPE_GPS_HARDWARE} is used.
     *
     * <p> Requires {@link android.Manifest.permission#LOCATION_HARDWARE} permission to access
     * geofencing in hardware.
     *
     * <p>This API should not be called directly by the app developers. A higher level api
     * which abstracts the hardware should be used instead. All the checks are done by the higher
     * level public API. Any needed locking should be handled by the higher level API.
     *
     * @param monitoringType Type of the monitor
     * @param callback Callback that will be called.
     * @return true on success
     */
    public boolean unregisterForMonitorStateChangeCallback(int monitoringType,
            GeofenceHardwareCallback callback) {
        boolean  result = false;
        try {
            result = mService.unregisterForMonitorStateChangeCallback(monitoringType,
                    getCallbackWrapper(callback));
            if (result) removeCallback(callback);

        } catch (RemoteException e) {
        }
        return result;
    }


    private void removeCallback(GeofenceHardwareCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    private GeofenceHardwareCallbackWrapper getCallbackWrapper(GeofenceHardwareCallback callback) {
        synchronized (mCallbacks) {
            GeofenceHardwareCallbackWrapper wrapper = mCallbacks.get(callback);
            if (wrapper == null) {
                wrapper = new GeofenceHardwareCallbackWrapper(callback);
                mCallbacks.put(callback, wrapper);
            }
            return wrapper;
        }
    }

    class GeofenceHardwareCallbackWrapper extends IGeofenceHardwareCallback.Stub {
        private WeakReference<GeofenceHardwareCallback> mCallback;

        GeofenceHardwareCallbackWrapper(GeofenceHardwareCallback c) {
            mCallback = new WeakReference<GeofenceHardwareCallback>(c);
        }

        public void onMonitoringSystemChange(int monitoringType, boolean available,
                Location location) {
            GeofenceHardwareCallback c = mCallback.get();
            if (c != null) c.onMonitoringSystemChange(monitoringType, available, location);
        }

        public void onGeofenceChange(int geofenceId, int transition, Location location,
                long timestamp, int monitoringType) {
            GeofenceHardwareCallback c = mCallback.get();
            if (c != null) {
                c.onGeofenceChange(geofenceId, transition, location, timestamp,
                        monitoringType);
            }
        }

        public void onGeofenceAdd(int geofenceId, int status) {
            GeofenceHardwareCallback c = mCallback.get();
            if (c != null) c.onGeofenceAdd(geofenceId, status);
        }

        public void onGeofenceRemove(int geofenceId, int status) {
            GeofenceHardwareCallback c = mCallback.get();
            if (c != null) {
                c.onGeofenceRemove(geofenceId, status);
                removeCallback(c);
            }
        }

        public void onGeofencePause(int geofenceId, int status) {
            GeofenceHardwareCallback c = mCallback.get();
            if (c != null) c.onGeofencePause(geofenceId, status);
        }

        public void onGeofenceResume(int geofenceId, int status) {
            GeofenceHardwareCallback c = mCallback.get();
            if (c != null) c.onGeofenceResume(geofenceId, status);
        }
    }
}
