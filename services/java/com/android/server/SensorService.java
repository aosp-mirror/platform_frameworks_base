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
import android.os.Bundle;
import android.os.RemoteException;
import android.os.IBinder;
import android.util.Config;
import android.util.Slog;
import android.util.PrintWriterPrinter;
import android.util.Printer;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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
    private int mCurrentDelay = 0;
    
    /**
     * Battery statistics to be updated when sensors are enabled and disabled.
     */
    final IBatteryStats mBatteryStats = BatteryStatsService.getService();

    private final class Listener implements IBinder.DeathRecipient {
        final IBinder mToken;
        final int mUid;

        int mSensors = 0;
        int mDelay = 0x7FFFFFFF;
        
        Listener(IBinder token, int uid) {
            mToken = token;
            mUid = uid;
        }
        
        void addSensor(int sensor, int delay) {
            mSensors |= (1<<sensor);
            if (delay < mDelay)
            	mDelay = delay;
        }
        
        void removeSensor(int sensor) {
            mSensors &= ~(1<<sensor);
        }

        boolean hasSensor(int sensor) {
            return ((mSensors & (1<<sensor)) != 0);
        }

        public void binderDied() {
            if (localLOGV) Slog.d(TAG, "sensor listener died");
            synchronized(mListeners) {
                mListeners.remove(this);
                mToken.unlinkToDeath(this, 0);
                // go through the lists of sensors used by the listener that 
                // died and deactivate them.
                for (int sensor=0 ; sensor<32 && mSensors!=0 ; sensor++) {
                    if (hasSensor(sensor)) {
                        removeSensor(sensor);
                        deactivateIfUnusedLocked(sensor);
                        try {
                            mBatteryStats.noteStopSensor(mUid, sensor);
                        } catch (RemoteException e) {
                            // oops. not a big deal.
                        }
                    }
                }
                if (mListeners.size() == 0) {
                    _sensors_control_wake();
                    _sensors_control_close();
                } else {
                    // TODO: we should recalculate the delay, since removing
                    // a listener may increase the overall rate.
                }
                mListeners.notify();
            }
        }
    }

    @SuppressWarnings("unused")
    public SensorService(Context context) {
        if (localLOGV) Slog.d(TAG, "SensorService startup");
        _sensors_control_init();
    }
    
    public Bundle getDataChannel() throws RemoteException {
        // synchronize so we do not require sensor HAL to be thread-safe.
        synchronized(mListeners) {
            return _sensors_control_open();
        }
    }

    public boolean enableSensor(IBinder binder, String name, int sensor, int enable)
            throws RemoteException {
        
        if (localLOGV) Slog.d(TAG, "enableSensor " + name + "(#" + sensor + ") " + enable);

        if (binder == null) {
            Slog.e(TAG, "listener is null (sensor=" + name + ", id=" + sensor + ")");
            return false;
        }

        if (enable < 0 && (enable != SENSOR_DISABLE)) {
            Slog.e(TAG, "invalid enable parameter (enable=" + enable +
                    ", sensor=" + name + ", id=" + sensor + ")");
            return false;
        }

        boolean res;
        int uid = Binder.getCallingUid();
        synchronized(mListeners) {
            res = enableSensorInternalLocked(binder, uid, name, sensor, enable);
            if (res == true) {
                // Inform battery statistics service of status change
                long identity = Binder.clearCallingIdentity();
                if (enable == SENSOR_DISABLE) {
                    mBatteryStats.noteStopSensor(uid, sensor);
                } else {
                    mBatteryStats.noteStartSensor(uid, sensor);
                }
                Binder.restoreCallingIdentity(identity);
            }
        }
        return res;
    }

    private boolean enableSensorInternalLocked(IBinder binder, int uid,
            String name, int sensor, int enable) throws RemoteException {

        // check if we have this listener
        Listener l = null;
        for (Listener listener : mListeners) {
            if (binder == listener.mToken) {
                l = listener;
                break;
            }
        }

        if (enable != SENSOR_DISABLE) {
            // Activate the requested sensor
            if (_sensors_control_activate(sensor, true) == false) {
                Slog.w(TAG, "could not enable sensor " + sensor);
                return false;
            }

            if (l == null) {
                /*
                 * we don't have a listener for this binder yet, so
                 * create a new one and add it to the list.
                 */
                l = new Listener(binder, uid);
                binder.linkToDeath(l, 0);
                mListeners.add(l);
                mListeners.notify();
            }

            // take note that this sensor is now used by this client
            l.addSensor(sensor, enable);

        } else {

            if (l == null) {
                /*
                 *  This client isn't in the list, this usually happens
                 *  when enabling the sensor failed, but the client
                 *  didn't handle the error and later tries to shut that
                 *  sensor off.
                 */
                Slog.w(TAG, "listener with binder " + binder +
                        ", doesn't exist (sensor=" + name +
                        ", id=" + sensor + ")");
                return false;
            }

            // remove this sensor from this client
            l.removeSensor(sensor);

            // see if we need to deactivate this sensors=
            deactivateIfUnusedLocked(sensor);

            // if the listener doesn't have any more sensors active
            // we can get rid of it
            if (l.mSensors == 0) {
                // we won't need this death notification anymore
                binder.unlinkToDeath(l, 0);
                // remove the listener from the list
                mListeners.remove(l);
                // and if the list is empty, turn off the whole sensor h/w
                if (mListeners.size() == 0) {
                    _sensors_control_wake();
                    _sensors_control_close();
                }
                mListeners.notify();
            }
        }

        // calculate and set the new delay
        int minDelay = 0x7FFFFFFF;
        for (Listener listener : mListeners) {
            if (listener.mDelay < minDelay)
                minDelay = listener.mDelay;
        }
        if (minDelay != 0x7FFFFFFF) {
            mCurrentDelay = minDelay;
            _sensors_control_set_delay(minDelay);
        }

        return true;
    }

    private void deactivateIfUnusedLocked(int sensor) {
        int size = mListeners.size();
        for (int i=0 ; i<size ; i++) {
            if (mListeners.get(i).hasSensor(sensor)) {
                // this sensor is still in use, don't turn it off
                return;
            }
        }
        if (_sensors_control_activate(sensor, false) == false) {
            Slog.w(TAG, "could not disable sensor " + sensor);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mListeners) {
            Printer pr = new PrintWriterPrinter(pw);
            int c = 0;
            pr.println(mListeners.size() + " listener(s), delay=" + mCurrentDelay + " ms");
            for (Listener l : mListeners) {
                pr.println("listener[" + c + "] " +
                        "sensors=0x" + Integer.toString(l.mSensors, 16) +
                        ", uid=" + l.mUid +
                        ", delay=" +
                        l.mDelay + " ms");
                c++;
            }
        }
    }

    private ArrayList<Listener> mListeners = new ArrayList<Listener>();

    private static native int _sensors_control_init();
    private static native Bundle _sensors_control_open();
    private static native int _sensors_control_close();
    private static native boolean _sensors_control_activate(int sensor, boolean activate);
    private static native int _sensors_control_set_delay(int ms);
    private static native int _sensors_control_wake();
}
