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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.ISensorService;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Config;
import android.util.Log;
import com.android.internal.R;

import java.util.ArrayList;


/**
 * Class that manages the device's sensors. It register clients and activate
 * the needed sensors. The sensor events themselves are not broadcasted from
 * this service, instead, a file descriptor is provided to each client they
 * can read events from.
 */

class SensorService extends ISensorService.Stub {
    private static final int SENSOR_NOTIFICATION_ACCURACY_LEVEL = 1;
    private static final String TAG = SensorService.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    private static final int SENSOR_DISABLE = -1;
    private NotificationManager mNotificationManager;
    private int mCompassAccuracy = -1;
    private final Context mContext;

    private final class Listener implements IBinder.DeathRecipient {
        final IBinder mListener;

        int mSensors = 0;
        int mDelay = 0x7FFFFFFF;
        
        Listener(IBinder listener) {
            mListener = listener;
        }
        
        void addSensor(int sensor, int delay) {
            mSensors |= sensor;
            if (mDelay > delay)
            	mDelay = delay;
        }
        
        void removeSensor(int sensor) {
            mSensors &= ~sensor;
        }

        boolean hasSensor(int sensor) {
            return ((mSensors & sensor) != 0);
        }

        public void binderDied() {
            if (localLOGV) Log.d(TAG, "sensor listener died");

            synchronized(mListeners) {
                mListeners.remove(this);
                for (int sensor = SensorManager.SENSOR_MAX; sensor > 0; sensor >>>= 1) {
                    if (hasSensor(sensor)) {
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

    public SensorService(Context context) {
        if (localLOGV) Log.d(TAG, "SensorService startup");
        _sensors_control_init();
        mNotificationManager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        mContext = context;
    }
    
    public ParcelFileDescriptor getDataChanel() throws RemoteException {
        return _sensors_control_open();
    }
    
    public void reportAccuracy(int sensor, int value) {
        if ((sensor & (SensorManager.SENSOR_ORIENTATION|SensorManager.SENSOR_ORIENTATION_RAW)) != 0) {
            synchronized (mNotificationManager) {
                if (value != mCompassAccuracy) {
                    Log.d(TAG, "Compass needs calibration, accuracy=" + value);
                    if (!SystemProperties.getBoolean("debug.sensors.notification", false)) {
                        // don't show the sensors notification by default
                        return;
                    }
                    mCompassAccuracy = value;
                    if (value == -1) {
                        mNotificationManager.cancel(0);
                    } else {
                        if (value <= SENSOR_NOTIFICATION_ACCURACY_LEVEL) {
                            long token = Binder.clearCallingIdentity();
                            try {
                                CharSequence banner = mContext.getString(R.string.compass_accuracy_banner);
                                CharSequence title = mContext.getString(R.string.compass_accuracy_notificaction_title);
                                CharSequence body = mContext.getString(R.string.compass_accuracy_notificaction_body);
                                Notification n = new Notification(R.drawable.stat_notify_calibrate_compass,
                                        banner, System.currentTimeMillis());
                                Intent bogusIntent = new Intent(mContext, SensorService.class);
                                PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0, bogusIntent,
                                        PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_ONE_SHOT);
                                n.setLatestEventInfo(mContext, title, body, contentIntent);
                                mNotificationManager.notify(0, n);
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        } else {
                            mNotificationManager.cancel(0);
                        }
                    }
                }
            }
        }
    }

    public boolean enableSensor(IBinder listener, int sensor, int enable)
             throws RemoteException {
        if (localLOGV) Log.d(TAG, "enableSensor " + sensor + " " + enable);

        if (listener == null) throw new NullPointerException("listener is null in enableSensor");

        synchronized(mListeners) {
            if (enable!=SENSOR_DISABLE && !_sensors_control_activate(sensor, true)) {
                Log.w(TAG, "could not enable sensor " + sensor);
                return false;
            }
                    
            IBinder binder = listener;
            Listener l = null;
            
            int minDelay = enable;
            int size = mListeners.size();
            for (int i = 0; i < size ; i++) {
                Listener test = mListeners.get(i);
                if (binder.equals(test.mListener)) {
                    l = test;
                }
                if (minDelay > test.mDelay)
                	minDelay = test.mDelay;
            }
            
            if (l == null && enable!=SENSOR_DISABLE) {
                l = new Listener(listener);
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
        }        
        return true;
    }

    private void deactivateIfUnused(int sensor) throws RemoteException {
        int size = mListeners.size();
        for (int i = 0; i < size ; i++) {
            if (mListeners.get(i).hasSensor(sensor))
                return;
        }
        _sensors_control_activate(sensor, false);
        reportAccuracy(sensor, -1);
    }

    private ArrayList<Listener> mListeners = new ArrayList<Listener>();

    private static native int _sensors_control_init();
    private static native ParcelFileDescriptor _sensors_control_open();
    private static native boolean _sensors_control_activate(int sensor, boolean activate);
    private static native int _sensors_control_set_delay(int ms);
}
