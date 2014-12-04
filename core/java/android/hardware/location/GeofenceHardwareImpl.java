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
import android.content.pm.PackageManager;
import android.location.IFusedGeofenceHardware;
import android.location.IGpsGeofenceHardware;
import android.location.Location;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * This class manages the geofences which are handled by hardware.
 *
 * @hide
 */
public final class GeofenceHardwareImpl {
    private static final String TAG = "GeofenceHardwareImpl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private static GeofenceHardwareImpl sInstance;
    private PowerManager.WakeLock mWakeLock;
    private final SparseArray<IGeofenceHardwareCallback> mGeofences =
            new SparseArray<IGeofenceHardwareCallback>();
    private final ArrayList<IGeofenceHardwareMonitorCallback>[] mCallbacks =
            new ArrayList[GeofenceHardware.NUM_MONITORS];
    private final ArrayList<Reaper> mReapers = new ArrayList<Reaper>();

    private IFusedGeofenceHardware mFusedService;
    private IGpsGeofenceHardware mGpsService;

    private int[] mSupportedMonitorTypes = new int[GeofenceHardware.NUM_MONITORS];

    // mGeofenceHandler message types
    private static final int GEOFENCE_TRANSITION_CALLBACK = 1;
    private static final int ADD_GEOFENCE_CALLBACK = 2;
    private static final int REMOVE_GEOFENCE_CALLBACK = 3;
    private static final int PAUSE_GEOFENCE_CALLBACK = 4;
    private static final int RESUME_GEOFENCE_CALLBACK = 5;
    private static final int GEOFENCE_CALLBACK_BINDER_DIED = 6;

    // mCallbacksHandler message types
    private static final int GEOFENCE_STATUS = 1;
    private static final int CALLBACK_ADD = 2;
    private static final int CALLBACK_REMOVE = 3;
    private static final int MONITOR_CALLBACK_BINDER_DIED = 4;

    // mReaperHandler message types
    private static final int REAPER_GEOFENCE_ADDED = 1;
    private static final int REAPER_MONITOR_CALLBACK_ADDED = 2;
    private static final int REAPER_REMOVED = 3;

    // The following constants need to match GpsLocationFlags enum in gps.h
    private static final int LOCATION_INVALID = 0;
    private static final int LOCATION_HAS_LAT_LONG = 1;
    private static final int LOCATION_HAS_ALTITUDE = 2;
    private static final int LOCATION_HAS_SPEED = 4;
    private static final int LOCATION_HAS_BEARING = 8;
    private static final int LOCATION_HAS_ACCURACY = 16;

    // Resolution level constants used for permission checks.
    // These constants must be in increasing order of finer resolution.
    private static final int RESOLUTION_LEVEL_NONE = 1;
    private static final int RESOLUTION_LEVEL_COARSE = 2;
    private static final int RESOLUTION_LEVEL_FINE = 3;

    public synchronized static GeofenceHardwareImpl getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GeofenceHardwareImpl(context);
        }
        return sInstance;
    }

    private GeofenceHardwareImpl(Context context) {
        mContext = context;
        // Init everything to unsupported.
        setMonitorAvailability(GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE,
                GeofenceHardware.MONITOR_UNSUPPORTED);
        setMonitorAvailability(
                GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE,
                GeofenceHardware.MONITOR_UNSUPPORTED);

    }

    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager powerManager =
                    (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        }
        mWakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (mWakeLock.isHeld()) mWakeLock.release();
    }

    private void updateGpsHardwareAvailability() {
        //Check which monitors are available.
        boolean gpsSupported;
        try {
            gpsSupported = mGpsService.isHardwareGeofenceSupported();
        } catch (RemoteException e) {
            Log.e(TAG, "Remote Exception calling LocationManagerService");
            gpsSupported = false;
        }

        if (gpsSupported) {
            // Its assumed currently available at startup.
            // native layer will update later.
            setMonitorAvailability(GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE,
                    GeofenceHardware.MONITOR_CURRENTLY_AVAILABLE);
        }
    }

    private void updateFusedHardwareAvailability() {
        boolean fusedSupported;
        try {
            fusedSupported = (mFusedService != null ? mFusedService.isSupported() : false);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling LocationManagerService");
            fusedSupported = false;
        }

        if(fusedSupported) {
            setMonitorAvailability(
                    GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE,
                    GeofenceHardware.MONITOR_CURRENTLY_AVAILABLE);
        }
    }

    public void setGpsHardwareGeofence(IGpsGeofenceHardware service) {
        if (mGpsService == null) {
            mGpsService = service;
            updateGpsHardwareAvailability();
        } else if (service == null) {
            mGpsService = null;
            Log.w(TAG, "GPS Geofence Hardware service seems to have crashed");
        } else {
            Log.e(TAG, "Error: GpsService being set again.");
        }
    }

    public void setFusedGeofenceHardware(IFusedGeofenceHardware service) {
        if(mFusedService == null) {
            mFusedService = service;
            updateFusedHardwareAvailability();
        } else if(service == null) {
            mFusedService = null;
            Log.w(TAG, "Fused Geofence Hardware service seems to have crashed");
        } else {
            Log.e(TAG, "Error: FusedService being set again");
        }
    }

    public int[] getMonitoringTypes() {
        boolean gpsSupported;
        boolean fusedSupported;
        synchronized (mSupportedMonitorTypes) {
            gpsSupported = mSupportedMonitorTypes[GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE]
                    != GeofenceHardware.MONITOR_UNSUPPORTED;
            fusedSupported = mSupportedMonitorTypes[GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE]
                    != GeofenceHardware.MONITOR_UNSUPPORTED;
        }

        if(gpsSupported) {
            if(fusedSupported) {
                return new int[] {
                        GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE,
                        GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE };
            } else {
                return new int[] { GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE };
            }
        } else if (fusedSupported) {
            return new int[] { GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE };
        } else {
            return new int[0];
        }
    }

    public int getStatusOfMonitoringType(int monitoringType) {
        synchronized (mSupportedMonitorTypes) {
            if (monitoringType >= mSupportedMonitorTypes.length || monitoringType < 0) {
                throw new IllegalArgumentException("Unknown monitoring type");
            }
            return mSupportedMonitorTypes[monitoringType];
        }
    }

    public boolean addCircularFence(
            int monitoringType,
            GeofenceHardwareRequestParcelable request,
            IGeofenceHardwareCallback callback) {
        int geofenceId = request.getId();

        // This API is not thread safe. Operations on the same geofence need to be serialized
        // by upper layers
        if (DEBUG) {
            String message = String.format(
                    "addCircularFence: monitoringType=%d, %s",
                    monitoringType,
                    request);
            Log.d(TAG, message);
        }
        boolean result;

        // The callback must be added before addCircularHardwareGeofence is called otherwise the
        // callback might not be called after the geofence is added in the geofence hardware.
        // This also means that the callback must be removed if the addCircularHardwareGeofence
        // operations is not called or fails.
        synchronized (mGeofences) {
            mGeofences.put(geofenceId, callback);
        }

        switch (monitoringType) {
            case GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE:
                if (mGpsService == null) return false;
                try {
                    result = mGpsService.addCircularHardwareGeofence(
                            request.getId(),
                            request.getLatitude(),
                            request.getLongitude(),
                            request.getRadius(),
                            request.getLastTransition(),
                            request.getMonitorTransitions(),
                            request.getNotificationResponsiveness(),
                            request.getUnknownTimer());
                } catch (RemoteException e) {
                    Log.e(TAG, "AddGeofence: Remote Exception calling LocationManagerService");
                    result = false;
                }
                break;
            case GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE:
                if(mFusedService == null) {
                    return false;
                }
                try {
                    mFusedService.addGeofences(
                            new GeofenceHardwareRequestParcelable[] { request });
                    result = true;
                } catch(RemoteException e) {
                    Log.e(TAG, "AddGeofence: RemoteException calling LocationManagerService");
                    result = false;
                }
                break;
            default:
                result = false;
        }
        if (result) {
            Message m = mReaperHandler.obtainMessage(REAPER_GEOFENCE_ADDED, callback);
            m.arg1 = monitoringType;
            mReaperHandler.sendMessage(m);
        } else {
            synchronized (mGeofences) {
                mGeofences.remove(geofenceId);
            }
        }

        if (DEBUG) Log.d(TAG, "addCircularFence: Result is: " + result);
        return result;
    }

    public boolean removeGeofence(int geofenceId, int monitoringType) {
        // This API is not thread safe. Operations on the same geofence need to be serialized
        // by upper layers
        if (DEBUG) Log.d(TAG, "Remove Geofence: GeofenceId: " + geofenceId);
        boolean result = false;

        synchronized (mGeofences) {
            if (mGeofences.get(geofenceId) == null) {
                throw new IllegalArgumentException("Geofence " + geofenceId + " not registered.");
            }
        }
        switch (monitoringType) {
            case GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE:
                if (mGpsService == null) return false;
                try {
                    result = mGpsService.removeHardwareGeofence(geofenceId);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoveGeofence: Remote Exception calling LocationManagerService");
                    result = false;
                }
                break;
            case GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE:
                if(mFusedService == null) {
                    return false;
                }
                try {
                    mFusedService.removeGeofences(new int[] { geofenceId });
                    result = true;
                } catch(RemoteException e) {
                    Log.e(TAG, "RemoveGeofence: RemoteException calling LocationManagerService");
                    result = false;
                }
                break;
            default:
                result = false;
        }
        if (DEBUG) Log.d(TAG, "removeGeofence: Result is: " + result);
        return result;
    }

    public boolean pauseGeofence(int geofenceId, int monitoringType) {
        // This API is not thread safe. Operations on the same geofence need to be serialized
        // by upper layers
        if (DEBUG) Log.d(TAG, "Pause Geofence: GeofenceId: " + geofenceId);
        boolean result;
        synchronized (mGeofences) {
            if (mGeofences.get(geofenceId) == null) {
                throw new IllegalArgumentException("Geofence " + geofenceId + " not registered.");
            }
        }
        switch (monitoringType) {
            case GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE:
                if (mGpsService == null) return false;
                try {
                    result = mGpsService.pauseHardwareGeofence(geofenceId);
                } catch (RemoteException e) {
                    Log.e(TAG, "PauseGeofence: Remote Exception calling LocationManagerService");
                    result = false;
                }
                break;
            case GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE:
                if(mFusedService == null) {
                    return false;
                }
                try {
                    mFusedService.pauseMonitoringGeofence(geofenceId);
                    result = true;
                } catch(RemoteException e) {
                    Log.e(TAG, "PauseGeofence: RemoteException calling LocationManagerService");
                    result = false;
                }
                break;
            default:
                result = false;
        }
        if (DEBUG) Log.d(TAG, "pauseGeofence: Result is: " + result);
        return result;
    }


    public boolean resumeGeofence(int geofenceId,  int monitoringType, int monitorTransition) {
        // This API is not thread safe. Operations on the same geofence need to be serialized
        // by upper layers
        if (DEBUG) Log.d(TAG, "Resume Geofence: GeofenceId: " + geofenceId);
        boolean result;
        synchronized (mGeofences) {
            if (mGeofences.get(geofenceId) == null) {
                throw new IllegalArgumentException("Geofence " + geofenceId + " not registered.");
            }
        }
        switch (monitoringType) {
            case GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE:
                if (mGpsService == null) return false;
                try {
                    result = mGpsService.resumeHardwareGeofence(geofenceId, monitorTransition);
                } catch (RemoteException e) {
                    Log.e(TAG, "ResumeGeofence: Remote Exception calling LocationManagerService");
                    result = false;
                }
                break;
            case GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE:
                if(mFusedService == null) {
                    return false;
                }
                try {
                    mFusedService.resumeMonitoringGeofence(geofenceId, monitorTransition);
                    result = true;
                } catch(RemoteException e) {
                    Log.e(TAG, "ResumeGeofence: RemoteException calling LocationManagerService");
                    result = false;
                }
                break;
            default:
                result = false;
        }
        if (DEBUG) Log.d(TAG, "resumeGeofence: Result is: " + result);
        return result;
    }

    public boolean registerForMonitorStateChangeCallback(int monitoringType,
            IGeofenceHardwareMonitorCallback callback) {
        Message reaperMessage =
                mReaperHandler.obtainMessage(REAPER_MONITOR_CALLBACK_ADDED, callback);
        reaperMessage.arg1 = monitoringType;
        mReaperHandler.sendMessage(reaperMessage);

        Message m = mCallbacksHandler.obtainMessage(CALLBACK_ADD, callback);
        m.arg1 = monitoringType;
        mCallbacksHandler.sendMessage(m);
        return true;
    }

    public boolean unregisterForMonitorStateChangeCallback(int monitoringType,
            IGeofenceHardwareMonitorCallback callback) {
        Message m = mCallbacksHandler.obtainMessage(CALLBACK_REMOVE, callback);
        m.arg1 = monitoringType;
        mCallbacksHandler.sendMessage(m);
        return true;
    }

    /**
     * Used to report geofence transitions
     */
    public void reportGeofenceTransition(
            int geofenceId,
            Location location,
            int transition,
            long transitionTimestamp,
            int monitoringType,
            int sourcesUsed) {
        if(location == null) {
            Log.e(TAG, String.format("Invalid Geofence Transition: location=%p", location));
            return;
        }
        if(DEBUG) {
            Log.d(
                    TAG,
                    "GeofenceTransition| " + location + ", transition:" + transition +
                    ", transitionTimestamp:" + transitionTimestamp + ", monitoringType:" +
                    monitoringType + ", sourcesUsed:" + sourcesUsed);
        }

        GeofenceTransition geofenceTransition = new GeofenceTransition(
                geofenceId,
                transition,
                transitionTimestamp,
                location,
                monitoringType,
                sourcesUsed);
        acquireWakeLock();

        Message message = mGeofenceHandler.obtainMessage(
                GEOFENCE_TRANSITION_CALLBACK,
                geofenceTransition);
        message.sendToTarget();
    }

    /**
     * Used to report Monitor status changes.
     */
    public void reportGeofenceMonitorStatus(
            int monitoringType,
            int monitoringStatus,
            Location location,
            int source) {
        setMonitorAvailability(monitoringType, monitoringStatus);
        acquireWakeLock();
        GeofenceHardwareMonitorEvent event = new GeofenceHardwareMonitorEvent(
                monitoringType,
                monitoringStatus,
                source,
                location);
        Message message = mCallbacksHandler.obtainMessage(GEOFENCE_STATUS, event);
        message.sendToTarget();
    }

    /**
     * Internal generic status report function for Geofence operations.
     *
     * @param operation The operation to be reported as defined internally.
     * @param geofenceId The id of the geofence the operation is related to.
     * @param operationStatus The status of the operation as defined in GeofenceHardware class. This
     *                        status is independent of the statuses reported by different HALs.
     */
    private void reportGeofenceOperationStatus(int operation, int geofenceId, int operationStatus) {
        acquireWakeLock();
        Message message = mGeofenceHandler.obtainMessage(operation);
        message.arg1 = geofenceId;
        message.arg2 = operationStatus;
        message.sendToTarget();
    }

    /**
     * Used to report the status of a Geofence Add operation.
     */
    public void reportGeofenceAddStatus(int geofenceId, int status) {
        if(DEBUG) Log.d(TAG, "AddCallback| id:" + geofenceId + ", status:" + status);
        reportGeofenceOperationStatus(ADD_GEOFENCE_CALLBACK, geofenceId, status);
    }

    /**
     * Used to report the status of a Geofence Remove operation.
     */
    public void reportGeofenceRemoveStatus(int geofenceId, int status) {
        if(DEBUG) Log.d(TAG, "RemoveCallback| id:" + geofenceId + ", status:" + status);
        reportGeofenceOperationStatus(REMOVE_GEOFENCE_CALLBACK, geofenceId, status);
    }

    /**
     * Used to report the status of a Geofence Pause operation.
     */
    public void reportGeofencePauseStatus(int geofenceId, int status) {
        if(DEBUG) Log.d(TAG, "PauseCallbac| id:" + geofenceId + ", status" + status);
        reportGeofenceOperationStatus(PAUSE_GEOFENCE_CALLBACK, geofenceId, status);
    }

    /**
     * Used to report the status of a Geofence Resume operation.
     */
    public void reportGeofenceResumeStatus(int geofenceId, int status) {
        if(DEBUG) Log.d(TAG, "ResumeCallback| id:" + geofenceId + ", status:" + status);
        reportGeofenceOperationStatus(RESUME_GEOFENCE_CALLBACK, geofenceId, status);
    }

    // All operations on mGeofences
    private Handler mGeofenceHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            int geofenceId;
            int status;
            IGeofenceHardwareCallback callback;
            switch (msg.what) {
                case ADD_GEOFENCE_CALLBACK:
                    geofenceId = msg.arg1;
                    synchronized (mGeofences) {
                        callback = mGeofences.get(geofenceId);
                    }

                    if (callback != null) {
                        try {
                            callback.onGeofenceAdd(geofenceId, msg.arg2);
                        } catch (RemoteException e) {Log.i(TAG, "Remote Exception:" + e);}
                    }
                    releaseWakeLock();
                    break;
                case REMOVE_GEOFENCE_CALLBACK:
                    geofenceId = msg.arg1;
                    synchronized (mGeofences) {
                        callback = mGeofences.get(geofenceId);
                    }

                    if (callback != null) {
                        try {
                            callback.onGeofenceRemove(geofenceId, msg.arg2);
                        } catch (RemoteException e) {}
                        IBinder callbackBinder = callback.asBinder();
                        boolean callbackInUse = false;
                        synchronized (mGeofences) {
                            mGeofences.remove(geofenceId);
                            // Check if the underlying binder is still useful for other geofences,
                            // if no, unlink the DeathRecipient to avoid memory leak.
                            for (int i = 0; i < mGeofences.size(); i++) {
                                 if (mGeofences.valueAt(i).asBinder() == callbackBinder) {
                                     callbackInUse = true;
                                     break;
                                 }
                            }
                        }

                        // Remove the reaper associated with this binder.
                        if (!callbackInUse) {
                            for (Iterator<Reaper> iterator = mReapers.iterator();
                                    iterator.hasNext();) {
                                Reaper reaper = iterator.next();
                                if (reaper.mCallback != null &&
                                        reaper.mCallback.asBinder() == callbackBinder) {
                                    iterator.remove();
                                    reaper.unlinkToDeath();
                                    if (DEBUG) Log.d(TAG, String.format("Removed reaper %s " +
                                          "because binder %s is no longer needed.",
                                          reaper, callbackBinder));
                                }
                            }
                        }
                    }
                    releaseWakeLock();
                    break;

                case PAUSE_GEOFENCE_CALLBACK:
                    geofenceId = msg.arg1;
                    synchronized (mGeofences) {
                        callback = mGeofences.get(geofenceId);
                    }

                    if (callback != null) {
                        try {
                            callback.onGeofencePause(geofenceId, msg.arg2);
                        } catch (RemoteException e) {}
                    }
                    releaseWakeLock();
                    break;

                case RESUME_GEOFENCE_CALLBACK:
                    geofenceId = msg.arg1;
                    synchronized (mGeofences) {
                        callback = mGeofences.get(geofenceId);
                    }

                    if (callback != null) {
                        try {
                            callback.onGeofenceResume(geofenceId, msg.arg2);
                        } catch (RemoteException e) {}
                    }
                    releaseWakeLock();
                    break;

                case GEOFENCE_TRANSITION_CALLBACK:
                    GeofenceTransition geofenceTransition = (GeofenceTransition)(msg.obj);
                    synchronized (mGeofences) {
                        callback = mGeofences.get(geofenceTransition.mGeofenceId);

                        // need to keep access to mGeofences synchronized at all times
                        if (DEBUG) Log.d(TAG, "GeofenceTransistionCallback: GPS : GeofenceId: " +
                                geofenceTransition.mGeofenceId +
                                " Transition: " + geofenceTransition.mTransition +
                                " Location: " + geofenceTransition.mLocation + ":" + mGeofences);
                    }

                    if (callback != null) {
                        try {
                            callback.onGeofenceTransition(
                                    geofenceTransition.mGeofenceId, geofenceTransition.mTransition,
                                    geofenceTransition.mLocation, geofenceTransition.mTimestamp,
                                    geofenceTransition.mMonitoringType);
                        } catch (RemoteException e) {}
                    }
                    releaseWakeLock();
                    break;
                case GEOFENCE_CALLBACK_BINDER_DIED:
                   // Find all geofences associated with this callback and remove them.
                   callback = (IGeofenceHardwareCallback) (msg.obj);
                   if (DEBUG) Log.d(TAG, "Geofence callback reaped:" + callback);
                   int monitoringType = msg.arg1;
                   synchronized (mGeofences) {
                       for (int i = 0; i < mGeofences.size(); i++) {
                            if (mGeofences.valueAt(i).equals(callback)) {
                                geofenceId = mGeofences.keyAt(i);
                                removeGeofence(mGeofences.keyAt(i), monitoringType);
                                mGeofences.remove(geofenceId);
                            }
                       }
                   }
            }
        }
    };

    // All operations on mCallbacks
    private Handler mCallbacksHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            int monitoringType;
            ArrayList<IGeofenceHardwareMonitorCallback> callbackList;
            IGeofenceHardwareMonitorCallback callback;

            switch (msg.what) {
                case GEOFENCE_STATUS:
                    GeofenceHardwareMonitorEvent event = (GeofenceHardwareMonitorEvent) msg.obj;
                    callbackList = mCallbacks[event.getMonitoringType()];
                    if (callbackList != null) {
                        if (DEBUG) Log.d(TAG, "MonitoringSystemChangeCallback: " + event);

                        for (IGeofenceHardwareMonitorCallback c : callbackList) {
                            try {
                                c.onMonitoringSystemChange(event);
                            } catch (RemoteException e) {
                                Log.d(TAG, "Error reporting onMonitoringSystemChange.", e);
                            }
                        }
                    }
                    releaseWakeLock();
                    break;
                case CALLBACK_ADD:
                    monitoringType = msg.arg1;
                    callback = (IGeofenceHardwareMonitorCallback) msg.obj;
                    callbackList = mCallbacks[monitoringType];
                    if (callbackList == null) {
                        callbackList = new ArrayList<IGeofenceHardwareMonitorCallback>();
                        mCallbacks[monitoringType] = callbackList;
                    }
                    if (!callbackList.contains(callback)) callbackList.add(callback);
                    break;
                case CALLBACK_REMOVE:
                    monitoringType = msg.arg1;
                    callback = (IGeofenceHardwareMonitorCallback) msg.obj;
                    callbackList = mCallbacks[monitoringType];
                    if (callbackList != null) {
                        callbackList.remove(callback);
                    }
                    break;
                case MONITOR_CALLBACK_BINDER_DIED:
                    callback = (IGeofenceHardwareMonitorCallback) msg.obj;
                    if (DEBUG) Log.d(TAG, "Monitor callback reaped:" + callback);
                    callbackList = mCallbacks[msg.arg1];
                    if (callbackList != null && callbackList.contains(callback)) {
                        callbackList.remove(callback);
                    }
            }
        }
    };

    // All operations on mReaper
    private Handler mReaperHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Reaper r;
            IGeofenceHardwareCallback callback;
            IGeofenceHardwareMonitorCallback monitorCallback;
            int monitoringType;

            switch (msg.what) {
                case REAPER_GEOFENCE_ADDED:
                    callback = (IGeofenceHardwareCallback) msg.obj;
                    monitoringType = msg.arg1;
                    r = new Reaper(callback, monitoringType);
                    if (!mReapers.contains(r)) {
                        mReapers.add(r);
                        IBinder b = callback.asBinder();
                        try {
                            b.linkToDeath(r, 0);
                        } catch (RemoteException e) {}
                    }
                    break;
                case REAPER_MONITOR_CALLBACK_ADDED:
                    monitorCallback = (IGeofenceHardwareMonitorCallback) msg.obj;
                    monitoringType = msg.arg1;

                    r = new Reaper(monitorCallback, monitoringType);
                    if (!mReapers.contains(r)) {
                        mReapers.add(r);
                        IBinder b = monitorCallback.asBinder();
                        try {
                            b.linkToDeath(r, 0);
                        } catch (RemoteException e) {}
                    }
                    break;
                case REAPER_REMOVED:
                    r = (Reaper) msg.obj;
                    mReapers.remove(r);
            }
        }
    };

    private class GeofenceTransition {
        private int mGeofenceId, mTransition;
        private long mTimestamp;
        private Location mLocation;
        private int mMonitoringType;
        private int mSourcesUsed;

        GeofenceTransition(
                int geofenceId,
                int transition,
                long timestamp,
                Location location,
                int monitoringType,
                int sourcesUsed) {
            mGeofenceId = geofenceId;
            mTransition = transition;
            mTimestamp = timestamp;
            mLocation = location;
            mMonitoringType = monitoringType;
            mSourcesUsed = sourcesUsed;
        }
    }

    private void setMonitorAvailability(int monitor, int val) {
        synchronized (mSupportedMonitorTypes) {
            mSupportedMonitorTypes[monitor] = val;
        }
    }


    int getMonitoringResolutionLevel(int monitoringType) {
        switch (monitoringType) {
            case GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE:
                return RESOLUTION_LEVEL_FINE;
            case GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE:
                return RESOLUTION_LEVEL_FINE;
        }
        return RESOLUTION_LEVEL_NONE;
    }

    class Reaper implements IBinder.DeathRecipient {
        private IGeofenceHardwareMonitorCallback mMonitorCallback;
        private IGeofenceHardwareCallback mCallback;
        private int mMonitoringType;

        Reaper(IGeofenceHardwareCallback c, int monitoringType) {
            mCallback = c;
            mMonitoringType = monitoringType;
        }

        Reaper(IGeofenceHardwareMonitorCallback c, int monitoringType) {
            mMonitorCallback = c;
            mMonitoringType = monitoringType;
        }

        @Override
        public void binderDied() {
            Message m;
            if (mCallback != null) {
                m = mGeofenceHandler.obtainMessage(GEOFENCE_CALLBACK_BINDER_DIED, mCallback);
                m.arg1 = mMonitoringType;
                mGeofenceHandler.sendMessage(m);
            } else if (mMonitorCallback != null) {
                m = mCallbacksHandler.obtainMessage(MONITOR_CALLBACK_BINDER_DIED, mMonitorCallback);
                m.arg1 = mMonitoringType;
                mCallbacksHandler.sendMessage(m);
            }
            Message reaperMessage = mReaperHandler.obtainMessage(REAPER_REMOVED, this);
            mReaperHandler.sendMessage(reaperMessage);
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + (mCallback != null ? mCallback.asBinder().hashCode() : 0);
            result = 31 * result + (mMonitorCallback != null
                    ? mMonitorCallback.asBinder().hashCode() : 0);
            result = 31 * result + mMonitoringType;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) return false;
            if (obj == this) return true;

            Reaper rhs = (Reaper) obj;
            return binderEquals(rhs.mCallback, mCallback) &&
                    binderEquals(rhs.mMonitorCallback, mMonitorCallback) &&
                    rhs.mMonitoringType == mMonitoringType;
        }

        /**
         * Compares the underlying Binder of the given two IInterface objects and returns true if
         * they equals. null values are accepted.
         */
        private boolean binderEquals(IInterface left, IInterface right) {
          if (left == null) {
            return right == null;
          } else {
            return right == null ? false : left.asBinder() == right.asBinder();
          }
        }

        /**
         * Unlinks this DeathRecipient.
         */
        private boolean unlinkToDeath() {
          if (mMonitorCallback != null) {
            return mMonitorCallback.asBinder().unlinkToDeath(this, 0);
          } else if (mCallback != null) {
            return mCallback.asBinder().unlinkToDeath(this, 0);
          }
          return true;
        }

        private boolean callbackEquals(IGeofenceHardwareCallback cb) {
          return mCallback != null && mCallback.asBinder() == cb.asBinder();
        }
    }

    int getAllowedResolutionLevel(int pid, int uid) {
        if (mContext.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION,
                pid, uid) == PackageManager.PERMISSION_GRANTED) {
            return RESOLUTION_LEVEL_FINE;
        } else if (mContext.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                pid, uid) == PackageManager.PERMISSION_GRANTED) {
            return RESOLUTION_LEVEL_COARSE;
        } else {
            return RESOLUTION_LEVEL_NONE;
        }
    }
}
