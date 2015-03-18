/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.hardware.display.DisplayManager;
import android.net.INetworkPolicyManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.TimeUtils;
import android.view.Display;
import com.android.internal.app.IBatteryStats;
import com.android.server.am.BatteryStatsService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Keeps track of device idleness and drives low power mode based on that.
 */
public class DeviceIdleController extends SystemService {
    private static final String TAG = "DeviceIdleController";

    private static final String ACTION_STEP_IDLE_STATE =
            "com.android.server.device_idle.STEP_IDLE_STATE";

    // TODO: These need to be moved to system settings.

    /**
     * This is the time, after becoming inactive, at which we start looking at the
     * motion sensor to determine if the device is being left alone.  We don't do this
     * immediately after going inactive just because we don't want to be continually running
     * the significant motion sensor whenever the screen is off.
     */
    private static final long DEFAULT_INACTIVE_TIMEOUT = 30*60*1000L;
    /**
     * This is the time, after the inactive timeout elapses, that we will wait looking
     * for significant motion until we truly consider the device to be idle.
     */
    private static final long DEFAULT_IDLE_AFTER_INACTIVE_TIMEOUT = 30*60*1000L;
    /**
     * This is the initial time, after being idle, that we will allow ourself to be back
     * in the IDLE_PENDING state allowing the system to run normally until we return to idle.
     */
    private static final long DEFAULT_IDLE_PENDING_TIMEOUT = 5*60*1000L;
    /**
     * Maximum pending idle timeout (time spent running) we will be allowed to use.
     */
    private static final long DEFAULT_MAX_IDLE_PENDING_TIMEOUT = 10*60*1000L;
    /**
     * Scaling factor to apply to current pending idle timeout each time we cycle through
     * that state.
     */
    private static final float DEFAULT_IDLE_PENDING_FACTOR = 2f;
    /**
     * This is the initial time that we want to sit in the idle state before waking up
     * again to return to pending idle and allowing normal work to run.
     */
    private static final long DEFAULT_IDLE_TIMEOUT = 60*60*1000L;
    /**
     * Maximum idle duration we will be allowed to use.
     */
    private static final long DEFAULT_MAX_IDLE_TIMEOUT = 6*60*60*1000L;
    /**
     * Scaling factor to apply to current idle timeout each time we cycle through that state.
     */
    private static final float DEFAULT_IDLE_FACTOR = 2f;

    private AlarmManager mAlarmManager;
    private IBatteryStats mBatteryStats;
    private INetworkPolicyManager mNetworkPolicyManager;
    private DisplayManager mDisplayManager;
    private SensorManager mSensorManager;
    private Sensor mSigMotionSensor;
    private PendingIntent mAlarmIntent;
    private Display mCurDisplay;
    private boolean mScreenOn;
    private boolean mCharging;
    private boolean mSigMotionActive;

    /** Device is currently active. */
    private static final int STATE_ACTIVE = 0;
    /** Device is inactve (screen off, no motion) and we are waiting to for idle. */
    private static final int STATE_INACTIVE = 1;
    /** Device is past the initial inactive period, and waiting for the next idle period. */
    private static final int STATE_IDLE_PENDING = 2;
    /** Device is in the idle state, trying to stay asleep as much as possible. */
    private static final int STATE_IDLE = 3;
    private static String stateToString(int state) {
        switch (state) {
            case STATE_ACTIVE: return "ACTIVE";
            case STATE_INACTIVE: return "INACTIVE";
            case STATE_IDLE_PENDING: return "IDLE_PENDING";
            case STATE_IDLE: return "IDLE";
            default: return Integer.toString(state);
        }
    }

    private int mState;

    private long mNextAlarmTime;
    private long mNextIdlePendingDelay;
    private long mNextIdleDelay;

    private final Binder mBinder = new Binder() {
        @Override protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            DeviceIdleController.this.dump(fd, pw, args);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int plugged = intent.getIntExtra("plugged", 0);
                updateChargingLocked(plugged != 0);
            } else if (ACTION_STEP_IDLE_STATE.equals(intent.getAction())) {
                synchronized (DeviceIdleController.this) {
                    stepIdleStateLocked();
                }
            }
        }
    };

    private final DisplayManager.DisplayListener mDisplayListener
            = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {
        }

        @Override public void onDisplayRemoved(int displayId) {
        }

        @Override public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                synchronized (DeviceIdleController.this) {
                    updateDisplayLocked();
                }
            }
        }
    };

    private final TriggerEventListener mSigMotionListener = new TriggerEventListener() {
        @Override public void onTrigger(TriggerEvent event) {
            synchronized (DeviceIdleController.this) {
                significantMotionLocked();
            }
        }
    };

    public DeviceIdleController(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        synchronized (this) {
            mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            mBatteryStats = BatteryStatsService.getService();
            mNetworkPolicyManager = INetworkPolicyManager.Stub.asInterface(
                                ServiceManager.getService(Context.NETWORK_POLICY_SERVICE));
            mDisplayManager = (DisplayManager) getContext().getSystemService(
                    Context.DISPLAY_SERVICE);
            mSensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            mSigMotionSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);

            Intent intent = new Intent(ACTION_STEP_IDLE_STATE)
                    .setPackage("android")
                    .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mAlarmIntent = PendingIntent.getBroadcast(getContext(), 0, intent, 0);

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(ACTION_STEP_IDLE_STATE);
            getContext().registerReceiver(mReceiver, filter);

            mDisplayManager.registerDisplayListener(mDisplayListener, null);

            mScreenOn = true;
            // Start out assuming we are charging.  If we aren't, we will at least get
            // a battery update the next time the level drops.
            mCharging = true;
            mState = STATE_ACTIVE;
            updateDisplayLocked();
        }

        publishBinderService("deviceidle", mBinder);
    }

    void updateDisplayLocked() {
        mCurDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        // We consider any situation where the display is showing something to be it on,
        // because if there is anything shown we are going to be updating it at some
        // frequency so can't be allowed to go into deep sleeps.
        boolean screenOn = mCurDisplay.getState() != Display.STATE_OFF;;
        if (!screenOn && mScreenOn) {
            mScreenOn = false;
            becomeInactiveIfAppropriateLocked();
        } else if (screenOn) {
            mScreenOn = true;
            becomeActiveLocked();
        }
    }

    void updateChargingLocked(boolean charging) {
        if (!charging && mCharging) {
            mCharging = false;
            becomeInactiveIfAppropriateLocked();
        } else if (charging) {
            mCharging = charging;
            becomeActiveLocked();
        }
    }

    void becomeActiveLocked() {
        if (mState != STATE_ACTIVE) {
            try {
                mNetworkPolicyManager.setDeviceIdleMode(false);
                mBatteryStats.noteDeviceIdleMode(false, true, false);
            } catch (RemoteException e) {
            }
            mState = STATE_ACTIVE;
            mNextIdlePendingDelay = 0;
            mNextIdleDelay = 0;
            cancelAlarmLocked();
            stopMonitoringSignificantMotion();
        }
    }

    void becomeInactiveIfAppropriateLocked() {
        if (!mScreenOn && !mCharging && mState == STATE_ACTIVE) {
            // Screen has turned off; we are now going to become inactive and start
            // waiting to see if we will ultimately go idle.
            mState = STATE_INACTIVE;
            scheduleAlarmLocked(DEFAULT_INACTIVE_TIMEOUT, false);
        }
    }

    void stepIdleStateLocked() {
        switch (mState) {
            case STATE_INACTIVE:
                // We have now been inactive long enough, it is time to start looking
                // for significant motion and sleep some more while doing so.
                startMonitoringSignificantMotion();
                scheduleAlarmLocked(DEFAULT_IDLE_AFTER_INACTIVE_TIMEOUT, false);
                // Reset the upcoming idle delays.
                mNextIdlePendingDelay = DEFAULT_IDLE_PENDING_TIMEOUT;
                mNextIdleDelay = DEFAULT_IDLE_TIMEOUT;
                mState = STATE_IDLE_PENDING;
                break;
            case STATE_IDLE_PENDING:
                // We have been waiting to become idle, and now it is time!  This is the
                // only case where we want to use a wakeup alarm, because we do want to
                // drag the device out of its sleep state in this case to do the next
                // scheduled work.
                scheduleAlarmLocked(mNextIdleDelay, true);
                mNextIdleDelay = (long)(mNextIdleDelay*DEFAULT_IDLE_FACTOR);
                if (mNextIdleDelay > DEFAULT_MAX_IDLE_TIMEOUT) {
                    mNextIdleDelay = DEFAULT_MAX_IDLE_TIMEOUT;
                }
                mState = STATE_IDLE;
                try {
                    mNetworkPolicyManager.setDeviceIdleMode(true);
                    mBatteryStats.noteDeviceIdleMode(true, false, false);
                } catch (RemoteException e) {
                }
                break;
            case STATE_IDLE:
                // We have been idling long enough, now it is time to do some work.
                scheduleAlarmLocked(mNextIdlePendingDelay, false);
                mNextIdlePendingDelay = (long)(mNextIdlePendingDelay*DEFAULT_IDLE_PENDING_FACTOR);
                if (mNextIdlePendingDelay > DEFAULT_MAX_IDLE_PENDING_TIMEOUT) {
                    mNextIdlePendingDelay = DEFAULT_MAX_IDLE_PENDING_TIMEOUT;
                }
                mState = STATE_IDLE_PENDING;
                try {
                    mNetworkPolicyManager.setDeviceIdleMode(false);
                    mBatteryStats.noteDeviceIdleMode(false, false, false);
                } catch (RemoteException e) {
                }
                break;
        }
    }

    void significantMotionLocked() {
        // When the sensor goes off, its trigger is automatically removed.
        mSigMotionActive = false;
        // The device is not yet active, so we want to go back to the pending idle
        // state to wait again for no motion.  Note that we only monitor for significant
        // motion after moving out of the inactive state, so no need to worry about that.
        if (mState != STATE_ACTIVE) {
            mState = STATE_INACTIVE;
            try {
                mNetworkPolicyManager.setDeviceIdleMode(false);
                mBatteryStats.noteDeviceIdleMode(false, false, true);
            } catch (RemoteException e) {
            }
            stepIdleStateLocked();
        }
    }

    void startMonitoringSignificantMotion() {
        if (mSigMotionSensor != null && !mSigMotionActive) {
            mSensorManager.requestTriggerSensor(mSigMotionListener, mSigMotionSensor);
            mSigMotionActive = true;
        }
    }

    void stopMonitoringSignificantMotion() {
        if (mSigMotionActive) {
            mSensorManager.cancelTriggerSensor(mSigMotionListener, mSigMotionSensor);
            mSigMotionActive = false;
        }
    }

    void cancelAlarmLocked() {
        if (mNextAlarmTime != 0) {
            mNextAlarmTime = 0;
            mAlarmManager.cancel(mAlarmIntent);
        }
    }

    void scheduleAlarmLocked(long delay, boolean wakeup) {
        if (mSigMotionSensor == null) {
            // If there is no significant motion sensor on this device, then we won't schedule
            // alarms, because we can't determine if the device is not moving.  This effectively
            // turns off normal exeuction of device idling, although it is still possible to
            // manually poke it by pretending like the alarm is going off.
            return;
        }
        mNextAlarmTime = SystemClock.elapsedRealtime() + delay;
        mAlarmManager.set(wakeup ? AlarmManager.ELAPSED_REALTIME_WAKEUP
                : AlarmManager.ELAPSED_REALTIME, mNextAlarmTime, mAlarmIntent);
    }

    private void dumpHelp(PrintWriter pw) {
        pw.println("Device idle controller (deviceidle) dump options:");
        pw.println("  [-h] [CMD]");
        pw.println("  -h: print this help text.");
        pw.println("Commands:");
        pw.println("  step");
        pw.println("    Immediately step to next state, without waiting for alarm.");
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump DeviceIdleController from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }

        if (args != null) {
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("step".equals(arg)) {
                    synchronized (this) {
                        stepIdleStateLocked();
                        pw.print("Stepped to: "); pw.println(stateToString(mState));
                    }
                    return;
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    dumpHelp(pw);
                    return;
                } else {
                    pw.println("Unknown command: " + arg);
                    dumpHelp(pw);
                    return;
                }
            }
        }

        pw.print("  mSigMotionSensor="); pw.println(mSigMotionSensor);
        pw.print("  mCurDisplay="); pw.println(mCurDisplay);
        pw.print("  mScreenOn="); pw.println(mScreenOn);
        pw.print("  mCharging="); pw.println(mCharging);
        pw.print("  mSigMotionActive="); pw.println(mSigMotionActive);
        pw.print("  mState="); pw.println(stateToString(mState));
        if (mNextAlarmTime != 0) {
            pw.print("  mNextAlarmTime=");
            TimeUtils.formatDuration(mNextAlarmTime, SystemClock.elapsedRealtime(), pw);
            pw.println();
        }
        if (mNextIdlePendingDelay != 0) {
            pw.print("  mNextIdlePendingDelay=");
            TimeUtils.formatDuration(mNextIdlePendingDelay, pw);
            pw.println();
        }
        if (mNextIdleDelay != 0) {
            pw.print("  mNextIdleDelay=");
            TimeUtils.formatDuration(mNextIdleDelay, pw);
            pw.println();
        }
    }
}
