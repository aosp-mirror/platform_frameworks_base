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
import android.os.Build;
import android.os.RemoteException;

import java.lang.ref.WeakReference;
import java.util.HashMap;

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
 *
 * @hide
 */
@SystemApi
public final class GeofenceHardware {
    private IGeofenceHardware mService;

    // Hardware systems that do geofence monitoring.
    static final int NUM_MONITORS = 2;

    /**
     * Constant for geofence monitoring done by the GPS hardware.
     */
    public static final int MONITORING_TYPE_GPS_HARDWARE = 0;

    /**
     * Constant for geofence monitoring done by the Fused hardware.
     */
    public static final int MONITORING_TYPE_FUSED_HARDWARE = 1;

    /**
     * Constant to indicate that the monitoring system is currently
     * available for monitoring geofences.
     */
    public static final int MONITOR_CURRENTLY_AVAILABLE = 0;

    /**
     * Constant to indicate that the monitoring system is currently
     * unavailable for monitoring geofences.
     */
    public static final int MONITOR_CURRENTLY_UNAVAILABLE = 1;

    /**
     * Constant to indicate that the monitoring system is unsupported
     * for hardware geofence monitoring.
     */
    public static final int MONITOR_UNSUPPORTED = 2;

    // The following constants need to match geofence flags in gps.h and fused_location.h
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
     * geofence.
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

    /**
     * The constant used to indicate that the operation failed due to insufficient memory.
     */
    public static final int GEOFENCE_ERROR_INSUFFICIENT_MEMORY = 6;

    // the following values must match the definitions in fused_location.h

    /**
     * The constant used to indicate that the monitoring system supports GNSS.
     */
    public static final int SOURCE_TECHNOLOGY_GNSS = (1<<0);

    /**
     * The constant used to indicate that the monitoring system supports WiFi.
     */
    public static final int SOURCE_TECHNOLOGY_WIFI = (1<<1);

    /**
     * The constant used to indicate that the monitoring system supports Sensors.
     */
    public static final int SOURCE_TECHNOLOGY_SENSORS = (1<<2);

    /**
     * The constant used to indicate that the monitoring system supports Cell.
     */
    public static final int SOURCE_TECHNOLOGY_CELL = (1<<3);

    /**
     * The constant used to indicate that the monitoring system supports Bluetooth.
     */
    public static final int SOURCE_TECHNOLOGY_BLUETOOTH = (1<<4);

    private HashMap<GeofenceHardwareCallback, GeofenceHardwareCallbackWrapper>
            mCallbacks = new HashMap<GeofenceHardwareCallback, GeofenceHardwareCallbackWrapper>();
    private HashMap<GeofenceHardwareMonitorCallback, GeofenceHardwareMonitorCallbackWrapper>
            mMonitorCallbacks = new HashMap<GeofenceHardwareMonitorCallback,
                    GeofenceHardwareMonitorCallbackWrapper>();

    public GeofenceHardware(IGeofenceHardware service) {
        mService = service;
    }

    /**
     * Returns all the hardware geofence monitoring systems which are supported
     *
     * <p> Call {@link #getStatusOfMonitoringType(int)} to know the current state
     * of a monitoring system.
     *
     * <p> Requires {@link android.Manifest.permission#LOCATION_HARDWARE} permission to access
     * geofencing in hardware.
     *
     * @return An array of all the monitoring types.
     *         An array of length 0 is returned in case of errors.
     */
    public int[] getMonitoringTypes() {
        try {
            return mService.getMonitoringTypes();
        } catch (RemoteException e) {
        }
        return new int[0];
    }

    /**
     * Returns current status of a hardware geofence monitoring system.
     *
     * <p>Status can be one of {@link #MONITOR_CURRENTLY_AVAILABLE},
     * {@link #MONITOR_CURRENTLY_UNAVAILABLE} or {@link #MONITOR_UNSUPPORTED}
     *
     * <p> Some supported hardware monitoring systems might not be available
     * for monitoring geofences in certain scenarios. For example, when a user
     * enters a building, the GPS hardware subsystem might not be able monitor
     * geofences and will change from {@link #MONITOR_CURRENTLY_AVAILABLE} to
     * {@link #MONITOR_CURRENTLY_UNAVAILABLE}.
     *
     * @param monitoringType
     * @return Current status of the monitoring type.
     */
    public int getStatusOfMonitoringType(int monitoringType) {
        try {
            return mService.getStatusOfMonitoringType(monitoringType);
        } catch (RemoteException e) {
            return MONITOR_UNSUPPORTED;
        }
    }

    /**
     * Creates a circular geofence which is monitored by subsystems in the hardware.
     *
     * <p> When the device detects that is has entered, exited or is uncertain
     * about the area specified by the geofence, the given callback will be called.
     *
     * <p> If this call returns true, it means that the geofence has been sent to the hardware.
     * {@link GeofenceHardwareCallback#onGeofenceAdd} will be called with the result of the
     * add call from the hardware. The {@link GeofenceHardwareCallback#onGeofenceAdd} will be
     * called with the following parameters when a transition event occurs.
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
     * <p> Create a geofence request object using the methods in {@link GeofenceHardwareRequest} to
     * set all the characteristics of the geofence. Use the created GeofenceHardwareRequest object
     * in this call.
     *
     * @param geofenceId The id associated with the geofence.
     * @param monitoringType The type of the hardware subsystem that should be used
     *        to monitor the geofence.
     * @param geofenceRequest The {@link GeofenceHardwareRequest} object associated with the
     *        geofence.
     * @param callback {@link GeofenceHardwareCallback} that will be use to notify the
     *        transition.
     * @return true when the geofence is successfully sent to the hardware for addition.
     * @throws IllegalArgumentException when the geofence request type is not supported.
     */
    public boolean addGeofence(int geofenceId, int monitoringType, GeofenceHardwareRequest
            geofenceRequest, GeofenceHardwareCallback callback) {
        try {
            if (geofenceRequest.getType() == GeofenceHardwareRequest.GEOFENCE_TYPE_CIRCLE) {
                return mService.addCircularFence(
                        monitoringType,
                        new GeofenceHardwareRequestParcelable(geofenceId, geofenceRequest),
                        getCallbackWrapper(callback));
            } else {
                throw new IllegalArgumentException("Geofence Request type not supported");
            }
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Removes a geofence added by {@link #addGeofence} call.
     *
     * <p> If this call returns true, it means that the geofence has been sent to the hardware.
     * {@link GeofenceHardwareCallback#onGeofenceRemove} will be called with the result of the
     * remove call from the hardware.
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
     * @return true when the geofence is successfully sent to the hardware for removal.                     .
     */
   public boolean removeGeofence(int geofenceId, int monitoringType) {
       try {
           return mService.removeGeofence(geofenceId, monitoringType);
       } catch (RemoteException e) {
       }
       return false;
   }

    /**
     * Pauses the monitoring of a geofence added by {@link #addGeofence} call.
     *
     * <p> If this call returns true, it means that the geofence has been sent to the hardware.
     * {@link GeofenceHardwareCallback#onGeofencePause} will be called with the result of the
     * pause call from the hardware.
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
     * @return true when the geofence is successfully sent to the hardware for pausing.
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
     * <p> If this call returns true, it means that the geofence has been sent to the hardware.
     * {@link GeofenceHardwareCallback#onGeofenceResume} will be called with the result of the
     * resume call from the hardware.
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
     * @param monitorTransition Bitwise OR of {@link #GEOFENCE_ENTERED},
     *        {@link #GEOFENCE_EXITED}, {@link #GEOFENCE_UNCERTAIN}
     * @return true when the geofence is successfully sent to the hardware for resumption.
     */
    public boolean resumeGeofence(int geofenceId, int monitoringType, int monitorTransition) {
        try {
            return mService.resumeGeofence(geofenceId, monitoringType, monitorTransition);
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
            GeofenceHardwareMonitorCallback callback) {
        try {
            return mService.registerForMonitorStateChangeCallback(monitoringType,
                    getMonitorCallbackWrapper(callback));
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
            GeofenceHardwareMonitorCallback callback) {
        boolean  result = false;
        try {
            result = mService.unregisterForMonitorStateChangeCallback(monitoringType,
                    getMonitorCallbackWrapper(callback));
            if (result) removeMonitorCallback(callback);

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

    private void removeMonitorCallback(GeofenceHardwareMonitorCallback callback) {
        synchronized (mMonitorCallbacks) {
            mMonitorCallbacks.remove(callback);
        }
    }

    private GeofenceHardwareMonitorCallbackWrapper getMonitorCallbackWrapper(
            GeofenceHardwareMonitorCallback callback) {
        synchronized (mMonitorCallbacks) {
            GeofenceHardwareMonitorCallbackWrapper wrapper = mMonitorCallbacks.get(callback);
            if (wrapper == null) {
                wrapper = new GeofenceHardwareMonitorCallbackWrapper(callback);
                mMonitorCallbacks.put(callback, wrapper);
            }
            return wrapper;
        }
    }

    class GeofenceHardwareMonitorCallbackWrapper extends IGeofenceHardwareMonitorCallback.Stub {
        private WeakReference<GeofenceHardwareMonitorCallback> mCallback;

        GeofenceHardwareMonitorCallbackWrapper(GeofenceHardwareMonitorCallback c) {
            mCallback = new WeakReference<GeofenceHardwareMonitorCallback>(c);
        }

        public void onMonitoringSystemChange(GeofenceHardwareMonitorEvent event) {
            GeofenceHardwareMonitorCallback c = mCallback.get();
            if (c == null) return;

            // report the legacy event first, so older clients are not broken
            c.onMonitoringSystemChange(
                    event.getMonitoringType(),
                    event.getMonitoringStatus() == GeofenceHardware.MONITOR_CURRENTLY_AVAILABLE,
                    event.getLocation());

            // and only call the updated callback on on L and above, this complies with the
            // documentation of GeofenceHardwareMonitorCallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                c.onMonitoringSystemChange(event);
            }
        }
    }

    class GeofenceHardwareCallbackWrapper extends IGeofenceHardwareCallback.Stub {
        private WeakReference<GeofenceHardwareCallback> mCallback;

        GeofenceHardwareCallbackWrapper(GeofenceHardwareCallback c) {
            mCallback = new WeakReference<GeofenceHardwareCallback>(c);
        }

        public void onGeofenceTransition(int geofenceId, int transition, Location location,
                long timestamp, int monitoringType) {
            GeofenceHardwareCallback c = mCallback.get();
            if (c != null) {
                c.onGeofenceTransition(geofenceId, transition, location, timestamp,
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
            if (c != null) {
                c.onGeofencePause(geofenceId, status);
            }
        }

        public void onGeofenceResume(int geofenceId, int status) {
            GeofenceHardwareCallback c = mCallback.get();
            if (c != null) c.onGeofenceResume(geofenceId, status);
        }
    }
}
