/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.PowerManager;
import android.os.Vibrator;
import android.service.dreams.DozeHardware;
import android.service.dreams.DreamService;
import android.util.Log;

import com.android.systemui.SystemUIApplication;

public class DozeService extends DreamService {
    private static final boolean DEBUG = false;

    private static final String TEASE_ACTION = "com.android.systemui.doze.tease";

    private final String mTag = String.format("DozeService.%08x", hashCode());
    private final Context mContext = this;

    private Host mHost;
    private DozeHardware mDozeHardware;
    private SensorManager mSensors;
    private Sensor mSigMotionSensor;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private boolean mDreaming;
    private boolean mTeaseReceiverRegistered;

    public DozeService() {
        if (DEBUG) Log.d(mTag, "new DozeService()");
        setDebug(DEBUG);
    }

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(mTag, "onCreate");
        super.onCreate();

        if (getApplication() instanceof SystemUIApplication) {
            final SystemUIApplication app = (SystemUIApplication) getApplication();
            mHost = app.getComponent(Host.class);
        }

        setWindowless(true);

        mSensors = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mSigMotionSensor = mSensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, mTag);
    }

    @Override
    public void onAttachedToWindow() {
        if (DEBUG) Log.d(mTag, "onAttachedToWindow");
        super.onAttachedToWindow();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        mDozeHardware = getDozeHardware();
        if (DEBUG) Log.d(mTag, "onDreamingStarted canDoze=" + canDoze()
                + " dozeHardware=" + mDozeHardware);
        mDreaming = true;
        listenForTeaseSignals(true);
        requestDoze();
    }

    public void stayAwake(long millis) {
        if (mDreaming && millis > 0) {
            mWakeLock.acquire(millis);
        }
    }

    public void startDozing() {
        if (DEBUG) Log.d(mTag, "startDozing mDreaming=" + mDreaming);
        if (!mDreaming) {
            Log.w(mTag, "Not dozing, no longer dreaming");
            return;
        }

        super.startDozing();
    }

    @Override
    public void onDreamingStopped() {
        if (DEBUG) Log.d(mTag, "onDreamingStopped isDozing=" + isDozing());
        super.onDreamingStopped();

        mDreaming = false;
        mDozeHardware = null;
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        listenForTeaseSignals(false);
        stopDozing();
        dozingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (DEBUG) Log.d(mTag, "onDetachedFromWindow");
        super.onDetachedFromWindow();

        dozingStopped();
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(mTag, "onDestroy");
        super.onDestroy();

        dozingStopped();
    }

    private void requestDoze() {
        if (mHost != null) {
            mHost.requestDoze(this);
        }
    }

    private void requestTease() {
        if (mHost != null) {
            mHost.requestTease(this);
        }
    }

    private void dozingStopped() {
        if (mHost != null) {
            mHost.dozingStopped(this);
        }
    }

    private void listenForTeaseSignals(boolean listen) {
        if (DEBUG) Log.d(mTag, "listenForTeaseSignals: " + listen);
        if (mHost == null) return;
        listenForSignificantMotion(listen);
        if (listen) {
            mContext.registerReceiver(mTeaseReceiver, new IntentFilter(TEASE_ACTION));
            mTeaseReceiverRegistered = true;
            mHost.addCallback(mHostCallback);
        } else {
            if (mTeaseReceiverRegistered) {
                mContext.unregisterReceiver(mTeaseReceiver);
            }
            mTeaseReceiverRegistered = false;
            mHost.removeCallback(mHostCallback);
        }
    }

    private void listenForSignificantMotion(boolean listen) {
        if (mSigMotionSensor == null) return;
        if (listen) {
            mSensors.requestTriggerSensor(mSigMotionListener, mSigMotionSensor);
        } else {
            mSensors.cancelTriggerSensor(mSigMotionListener, mSigMotionSensor);
        }
    }

    private static String triggerEventToString(TriggerEvent event) {
        if (event == null) return null;
        final StringBuilder sb = new StringBuilder("TriggerEvent[")
                .append(event.timestamp).append(',')
                .append(event.sensor.getName());
        if (event.values != null) {
            for (int i = 0; i < event.values.length; i++) {
                sb.append(',').append(event.values[i]);
            }
        }
        return sb.append(']').toString();
    }

    private final TriggerEventListener mSigMotionListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            if (DEBUG) Log.d(mTag, "sigMotion.onTrigger: " + triggerEventToString(event));
            if (DEBUG) {
                final Vibrator v = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    v.vibrate(1000);
                }
            }
            requestTease();
            listenForSignificantMotion(true);  // reregister, this sensor only fires once
        }
    };

    private final BroadcastReceiver mTeaseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(mTag, "Received tease intent");
            requestTease();
        }
    };

    private final Host.Callback mHostCallback = new Host.Callback() {
        @Override
        public void onNewNotifications() {
            if (DEBUG) Log.d(mTag, "onNewNotifications");
            requestTease();
        }
    };

    public interface Host {
        void addCallback(Callback callback);
        void removeCallback(Callback callback);
        void requestDoze(DozeService dozeService);
        void requestTease(DozeService dozeService);
        void dozingStopped(DozeService dozeService);

        public interface Callback {
            void onNewNotifications();
        }
    }
}
