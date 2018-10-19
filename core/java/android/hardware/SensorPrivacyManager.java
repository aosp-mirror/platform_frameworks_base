/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;

/**
 * This class provides access to the sensor privacy services; sensor privacy allows the
 * user to disable access to all sensors on the device. This class provides methods to query the
 * current state of sensor privacy as well as to register / unregister for notification when
 * the sensor privacy state changes.
 *
 * @hide
 */
@SystemService(Context.SENSOR_PRIVACY_SERVICE)
public final class SensorPrivacyManager {

    /**
     * A class implementing this interface can register with the {@link
     * android.hardware.SensorPrivacyManager} to receive notification when the sensor privacy
     * state changes.
     */
    public interface OnSensorPrivacyChangedListener {
        /**
         * Callback invoked when the sensor privacy state changes.
         *
         * @param enabled true if sensor privacy is enabled, false otherwise.
         */
        void onSensorPrivacyChanged(boolean enabled);
    }

    private static final Object sInstanceLock = new Object();

    @GuardedBy("sInstanceLock")
    private static SensorPrivacyManager sInstance;

    @NonNull
    private final Context mContext;

    @NonNull
    private final ISensorPrivacyManager mService;

    @NonNull
    private final ArrayMap<OnSensorPrivacyChangedListener, ISensorPrivacyListener> mListeners;

    /**
     * Private constructor to ensure only a single instance is created.
     */
    private SensorPrivacyManager(Context context, ISensorPrivacyManager service) {
        mContext = context;
        mService = service;
        mListeners = new ArrayMap<>();
    }

    /**
     * Returns the single instance of the SensorPrivacyManager.
     */
    public static SensorPrivacyManager getInstance(Context context) {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                try {
                    IBinder b = ServiceManager.getServiceOrThrow(Context.SENSOR_PRIVACY_SERVICE);
                    ISensorPrivacyManager service = ISensorPrivacyManager.Stub.asInterface(b);
                    sInstance = new SensorPrivacyManager(context, service);
                } catch (ServiceManager.ServiceNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
            return sInstance;
        }
    }

    /**
     * Sets sensor privacy to the specified state.
     *
     * @param enable the state to which sensor privacy should be set.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SENSOR_PRIVACY)
    public void setSensorPrivacy(boolean enable) {
        try {
            mService.setSensorPrivacy(enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a new listener to receive notification when the state of sensor privacy
     * changes.
     *
     * @param listener the OnSensorPrivacyChangedListener to be notified when the state of sensor
     *                 privacy changes.
     */
    public void addSensorPrivacyListener(final OnSensorPrivacyChangedListener listener) {
        synchronized (mListeners) {
            ISensorPrivacyListener iListener = mListeners.get(listener);
            if (iListener == null) {
                iListener = new ISensorPrivacyListener.Stub() {
                    @Override
                    public void onSensorPrivacyChanged(boolean enabled) {
                        listener.onSensorPrivacyChanged(enabled);
                    }
                };
                mListeners.put(listener, iListener);
            }

            try {
                mService.addSensorPrivacyListener(iListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters the specified listener from receiving notifications when the state of sensor
     * privacy changes.
     *
     * @param listener the OnSensorPrivacyChangedListener to be unregistered from notifications when
     *                 sensor privacy changes.
     */
    public void removeSensorPrivacyListener(OnSensorPrivacyChangedListener listener) {
        synchronized (mListeners) {
            ISensorPrivacyListener iListener = mListeners.get(listener);
            if (iListener != null) {
                mListeners.remove(iListener);
                try {
                    mService.removeSensorPrivacyListener(iListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Returns whether sensor privacy is currently enabled.
     *
     * @return true if sensor privacy is currently enabled, false otherwise.
     */
    public boolean isSensorPrivacyEnabled() {
        try {
            return mService.isSensorPrivacyEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
