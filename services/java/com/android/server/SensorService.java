/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.hardware.ISensorService;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.IBinder;
import android.util.Config;
import android.util.Log;

import java.util.ArrayList;

import com.android.internal.app.IBatteryStats;
import com.android.server.am.BatteryStatsService;


/**
 * Class that manages the device's sensors. It register clients and activate
 * the needed sensors. The sensor events themselves are not broadcasted from
 * this service, instead, a file descriptor is provided to each client they
 * can read events from.
 */

class SensorService extends ISensorService.Stub {
    static final String TAG = SensorService.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    private static final int SENSOR_DISABLE = -1;
    
    /**
     * Battery statistics to be updated when sensors are enabled and diabled.
     */
    final IBatteryStats mBatteryStats = BatteryStatsService.getService();

    private final class Listener implements IBinder.DeathRecipient {
        final IBinder mToken;

        int mSensors = 0;
        int mDelay = 0x7FFFFFFF;
        
        Listener(IBinder token) {
            mToken = token;
        }
        
        void addSensor(int sensor, int delay) {
            mSensors |= (1<<sensor);
            if (mDelay > delay)
            	mDelay = delay;
        }
        
        void removeSensor(int sensor) {
            mSensors &= ~(1<<sensor);
        }

        boolean hasSensor(int sensor) {
            return ((mSensors & (1<<sensor)) != 0);
        }

        public void binderDied() {
            if (localLOGV) Log.d(TAG, "sensor listener died");
            synchronized(mListeners) {
                mListeners.remove(this);
                mToken.unlinkToDeath(this, 0);
                // go through the lists of sensors used by the listener that 
                // died and deactivate them.
                for (int sensor=0 ; sensor<32 && mSensors!=0 ; sensor++) {
                    if (hasSensor(sensor)) {
                        removeSensor(sensor);
                        try {
                            deactivateIfUnused(sensor);
                        } catch (RemoteException e) {
                            Log.w(TAG, "RemoteException in binderDied");
                        }
                    }
                }
                mListeners.notify();
            }
        }
    }

    @SuppressWarnings("unused")
    public SensorService(Context context) {
        if (localLOGV) Log.d(TAG, "SensorService startup");
        _sensors_control_init();
    }
    
    public ParcelFileDescriptor getDataChanel() throws RemoteException {
        return _sensors_control_open();
    }
    
    public boolean enableSensor(IBinder binder, int sensor, int enable)
             throws RemoteException {
        if (localLOGV) Log.d(TAG, "enableSensor " + sensor + " " + enable);
        
        // Inform battery statistics service of status change
        int uid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        if (enable == SENSOR_DISABLE) {
            mBatteryStats.noteStopSensor(uid, sensor);
        } else {
            mBatteryStats.noteStartSensor(uid, sensor);
        }
        Binder.restoreCallingIdentity(identity);

        if (binder == null) throw new NullPointerException("listener is null in enableSensor");

        synchronized(mListeners) {
            if (enable!=SENSOR_DISABLE && !_sensors_control_activate(sensor, true)) {
                Log.w(TAG, "could not enable sensor " + sensor);
                return false;
            }
                    
            Listener l = null;
            int minDelay = enable;
            for (Listener listener : mListeners) {
                if (binder == listener.mToken) {
                    l = listener;
                }
                if (minDelay > listener.mDelay)
                    minDelay = listener.mDelay;
            }
            
            if (l == null && enable!=SENSOR_DISABLE) {
                l = new Listener(binder);
                binder.linkToDeath(l, 0);
                mListeners.add(l);
                mListeners.notify();
            }
            
            if (l == null) {
                throw new NullPointerException("no Listener object in enableSensor");
            }
            
            if (minDelay >= 0) {
                _sensors_control_set_delay(minDelay);
            }
            
            if (enable != SENSOR_DISABLE) {
                l.addSensor(sensor, enable);
            } else {
                l.removeSensor(sensor);
                deactivateIfUnused(sensor);
                if (l.mSensors == 0) {
                    mListeners.remove(l);
                    binder.unlinkToDeath(l, 0);
                    mListeners.notify();
                }
            }
            
            if (mListeners.size() == 0) {
                _sensors_control_wake();
            }
        }        
        return true;
    }

    void deactivateIfUnused(int sensor) throws RemoteException {
        int size = mListeners.size();
        for (int i=0 ; i<size ; i++) {
            if (mListeners.get(i).hasSensor(sensor))
                return;
        }
        _sensors_control_activate(sensor, false);
    }

    ArrayList<Listener> mListeners = new ArrayList<Listener>();

    private static native int _sensors_control_init();
    private static native ParcelFileDescriptor _sensors_control_open();
    private static native boolean _sensors_control_activate(int sensor, boolean activate);
    private static native int _sensors_control_set_delay(int ms);
    private static native int _sensors_control_wake();
}
