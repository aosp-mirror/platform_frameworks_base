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

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.IFusedGeofenceHardware;
import android.location.IGpsGeofenceHardware;
import android.os.Binder;
import android.os.IBinder;

/**
 * Service that handles hardware geofencing.
 *
 * @hide
 */
public class GeofenceHardwareService extends Service {
    private GeofenceHardwareImpl mGeofenceHardwareImpl;
    private Context mContext;

    @Override
    public void onCreate() {
        mContext = this;
        mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return false;
    }

    @Override
    public void onDestroy() {
        mGeofenceHardwareImpl = null;
    }


    private void checkPermission(int pid, int uid, int monitoringType) {
        if (mGeofenceHardwareImpl.getAllowedResolutionLevel(pid, uid) <
                mGeofenceHardwareImpl.getMonitoringResolutionLevel(monitoringType)) {
            throw new SecurityException("Insufficient permissions to access hardware geofence for"
                    + " type: " + monitoringType);
        }
    }

    private IBinder mBinder = new IGeofenceHardware.Stub() {
        @Override
        public void setGpsGeofenceHardware(IGpsGeofenceHardware service) {
            mGeofenceHardwareImpl.setGpsHardwareGeofence(service);
        }

        @Override
        public void setFusedGeofenceHardware(IFusedGeofenceHardware service) {
            mGeofenceHardwareImpl.setFusedGeofenceHardware(service);
        }

        @Override
        public int[] getMonitoringTypes() {
            mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                    "Location Hardware permission not granted to access hardware geofence");

            return mGeofenceHardwareImpl.getMonitoringTypes();
        }

        @Override
        public int getStatusOfMonitoringType(int monitoringType) {
            mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                    "Location Hardware permission not granted to access hardware geofence");

            return mGeofenceHardwareImpl.getStatusOfMonitoringType(monitoringType);
        }

        @Override
        public boolean addCircularFence(
                int monitoringType,
                GeofenceHardwareRequestParcelable request,
                IGeofenceHardwareCallback callback) {
            mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                    "Location Hardware permission not granted to access hardware geofence");
            checkPermission(Binder.getCallingPid(), Binder.getCallingUid(), monitoringType);
            return mGeofenceHardwareImpl.addCircularFence(monitoringType, request, callback);
        }

        @Override
        public boolean removeGeofence(int id, int monitoringType) {
            mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                    "Location Hardware permission not granted to access hardware geofence");

            checkPermission(Binder.getCallingPid(), Binder.getCallingUid(), monitoringType);
            return mGeofenceHardwareImpl.removeGeofence(id, monitoringType);
        }

        @Override
        public boolean pauseGeofence(int id, int monitoringType) {
            mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                    "Location Hardware permission not granted to access hardware geofence");

            checkPermission(Binder.getCallingPid(), Binder.getCallingUid(), monitoringType);
            return mGeofenceHardwareImpl.pauseGeofence(id, monitoringType);
        }

        @Override
        public boolean resumeGeofence(int id, int monitoringType, int monitorTransitions) {
            mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                    "Location Hardware permission not granted to access hardware geofence");

            checkPermission(Binder.getCallingPid(), Binder.getCallingUid(), monitoringType);
            return mGeofenceHardwareImpl.resumeGeofence(id, monitoringType, monitorTransitions);
        }

        @Override
        public boolean registerForMonitorStateChangeCallback(int monitoringType,
                IGeofenceHardwareMonitorCallback callback) {
            mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                    "Location Hardware permission not granted to access hardware geofence");

            checkPermission(Binder.getCallingPid(), Binder.getCallingUid(), monitoringType);
            return mGeofenceHardwareImpl.registerForMonitorStateChangeCallback(monitoringType,
                    callback);
        }

        @Override
        public boolean unregisterForMonitorStateChangeCallback(int monitoringType,
                IGeofenceHardwareMonitorCallback callback) {
            mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                    "Location Hardware permission not granted to access hardware geofence");

            checkPermission(Binder.getCallingPid(), Binder.getCallingUid(), monitoringType);
            return mGeofenceHardwareImpl.unregisterForMonitorStateChangeCallback(monitoringType,
                    callback);
        }
    };
}
