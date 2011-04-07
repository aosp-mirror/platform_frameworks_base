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

import com.android.server.am.ActivityManagerService;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;

/** This class calls its monitor every minute. Killing this process if they don't return **/
public class Watchdog extends Thread {
    static final String TAG = "Watchdog";
    static final boolean localLOGV = false || false;

    // Set this to true to use debug default values.
    static final boolean DB = false;

    // Set this to true to have the watchdog record kernel thread stacks when it fires
    static final boolean RECORD_KERNEL_THREADS = true;

    static final int MONITOR = 2718;

    static final int TIME_TO_RESTART = DB ? 15*1000 : 60*1000;
    static final int TIME_TO_WAIT = TIME_TO_RESTART / 2;

    static final int MEMCHECK_DEFAULT_MIN_SCREEN_OFF = DB ? 1*60 : 5*60;   // 5 minutes
    static final int MEMCHECK_DEFAULT_MIN_ALARM = DB ? 1*60 : 3*60;        // 3 minutes
    static final int MEMCHECK_DEFAULT_RECHECK_INTERVAL = DB ? 1*60 : 5*60; // 5 minutes

    static final int REBOOT_DEFAULT_INTERVAL = DB ? 1 : 0;                 // never force reboot
    static final int REBOOT_DEFAULT_START_TIME = 3*60*60;                  // 3:00am
    static final int REBOOT_DEFAULT_WINDOW = 60*60;                        // within 1 hour

    static final String REBOOT_ACTION = "com.android.service.Watchdog.REBOOT";

    static Watchdog sWatchdog;

    /* This handler will be used to post message back onto the main thread */
    final Handler mHandler;
    final ArrayList<Monitor> mMonitors = new ArrayList<Monitor>();
    ContentResolver mResolver;
    BatteryService mBattery;
    PowerManagerService mPower;
    AlarmManagerService mAlarm;
    ActivityManagerService mActivity;
    boolean mCompleted;
    boolean mForceKillSystem;
    Monitor mCurrentMonitor;

    int mPhonePid;

    final Calendar mCalendar = Calendar.getInstance();
    int mMinScreenOff = MEMCHECK_DEFAULT_MIN_SCREEN_OFF;
    int mMinAlarm = MEMCHECK_DEFAULT_MIN_ALARM;
    boolean mNeedScheduledCheck;
    PendingIntent mCheckupIntent;
    PendingIntent mRebootIntent;

    long mBootTime;
    int mRebootInterval;

    boolean mReqRebootNoWait;     // should wait for one interval before reboot?
    int mReqRebootInterval = -1;  // >= 0 if a reboot has been requested
    int mReqRebootStartTime = -1; // >= 0 if a specific start time has been requested
    int mReqRebootWindow = -1;    // >= 0 if a specific window has been requested
    int mReqMinScreenOff = -1;    // >= 0 if a specific screen off time has been requested
    int mReqMinNextAlarm = -1;    // >= 0 if specific time to next alarm has been requested
    int mReqRecheckInterval= -1;  // >= 0 if a specific recheck interval has been requested

    /**
     * Used for scheduling monitor callbacks and checking memory usage.
     */
    final class HeartbeatHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MONITOR: {
                    // See if we should force a reboot.
                    int rebootInterval = mReqRebootInterval >= 0
                            ? mReqRebootInterval : Settings.Secure.getInt(
                            mResolver, Settings.Secure.REBOOT_INTERVAL,
                            REBOOT_DEFAULT_INTERVAL);
                    if (mRebootInterval != rebootInterval) {
                        mRebootInterval = rebootInterval;
                        // We have been running long enough that a reboot can
                        // be considered...
                        checkReboot(false);
                    }

                    final int size = mMonitors.size();
                    for (int i = 0 ; i < size ; i++) {
                        mCurrentMonitor = mMonitors.get(i);
                        mCurrentMonitor.monitor();
                    }

                    synchronized (Watchdog.this) {
                        mCompleted = true;
                        mCurrentMonitor = null;
                    }
                } break;
            }
        }
    }

    final class RebootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (localLOGV) Slog.v(TAG, "Alarm went off, checking reboot.");
            checkReboot(true);
        }
    }

    final class RebootRequestReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            mReqRebootNoWait = intent.getIntExtra("nowait", 0) != 0;
            mReqRebootInterval = intent.getIntExtra("interval", -1);
            mReqRebootStartTime = intent.getIntExtra("startTime", -1);
            mReqRebootWindow = intent.getIntExtra("window", -1);
            mReqMinScreenOff = intent.getIntExtra("minScreenOff", -1);
            mReqMinNextAlarm = intent.getIntExtra("minNextAlarm", -1);
            mReqRecheckInterval = intent.getIntExtra("recheckInterval", -1);
            EventLog.writeEvent(EventLogTags.WATCHDOG_REQUESTED_REBOOT,
                    mReqRebootNoWait ? 1 : 0, mReqRebootInterval,
                            mReqRecheckInterval, mReqRebootStartTime,
                    mReqRebootWindow, mReqMinScreenOff, mReqMinNextAlarm);
            checkReboot(true);
        }
    }

    public interface Monitor {
        void monitor();
    }

    public static Watchdog getInstance() {
        if (sWatchdog == null) {
            sWatchdog = new Watchdog();
        }

        return sWatchdog;
    }

    private Watchdog() {
        super("watchdog");
        mHandler = new HeartbeatHandler();
    }

    public void init(Context context, BatteryService battery,
            PowerManagerService power, AlarmManagerService alarm,
            ActivityManagerService activity) {
        mResolver = context.getContentResolver();
        mBattery = battery;
        mPower = power;
        mAlarm = alarm;
        mActivity = activity;

        context.registerReceiver(new RebootReceiver(),
                new IntentFilter(REBOOT_ACTION));
        mRebootIntent = PendingIntent.getBroadcast(context,
                0, new Intent(REBOOT_ACTION), 0);

        context.registerReceiver(new RebootRequestReceiver(),
                new IntentFilter(Intent.ACTION_REBOOT),
                android.Manifest.permission.REBOOT, null);

        mBootTime = System.currentTimeMillis();
    }

    public void processStarted(String name, int pid) {
        synchronized (this) {
            if ("com.android.phone".equals(name)) {
                mPhonePid = pid;
            }
        }
    }

    public void addMonitor(Monitor monitor) {
        synchronized (this) {
            if (isAlive()) {
                throw new RuntimeException("Monitors can't be added while the Watchdog is running");
            }
            mMonitors.add(monitor);
        }
    }

    void checkReboot(boolean fromAlarm) {
        int rebootInterval = mReqRebootInterval >= 0 ? mReqRebootInterval
                : Settings.Secure.getInt(
                mResolver, Settings.Secure.REBOOT_INTERVAL,
                REBOOT_DEFAULT_INTERVAL);
        mRebootInterval = rebootInterval;
        if (rebootInterval <= 0) {
            // No reboot interval requested.
            if (localLOGV) Slog.v(TAG, "No need to schedule a reboot alarm!");
            mAlarm.remove(mRebootIntent);
            return;
        }

        long rebootStartTime = mReqRebootStartTime >= 0 ? mReqRebootStartTime
                : Settings.Secure.getLong(
                mResolver, Settings.Secure.REBOOT_START_TIME,
                REBOOT_DEFAULT_START_TIME);
        long rebootWindowMillis = (mReqRebootWindow >= 0 ? mReqRebootWindow
                : Settings.Secure.getLong(
                mResolver, Settings.Secure.REBOOT_WINDOW,
                REBOOT_DEFAULT_WINDOW)) * 1000;
        long recheckInterval = (mReqRecheckInterval >= 0 ? mReqRecheckInterval
                : Settings.Secure.getLong(
                mResolver, Settings.Secure.MEMCHECK_RECHECK_INTERVAL,
                MEMCHECK_DEFAULT_RECHECK_INTERVAL)) * 1000;

        retrieveBrutalityAmount();

        long realStartTime;
        long now;

        synchronized (this) {
            now = System.currentTimeMillis();
            realStartTime = computeCalendarTime(mCalendar, now,
                    rebootStartTime);

            long rebootIntervalMillis = rebootInterval*24*60*60*1000;
            if (DB || mReqRebootNoWait ||
                    (now-mBootTime) >= (rebootIntervalMillis-rebootWindowMillis)) {
                if (fromAlarm && rebootWindowMillis <= 0) {
                    // No reboot window -- just immediately reboot.
                    EventLog.writeEvent(EventLogTags.WATCHDOG_SCHEDULED_REBOOT, now,
                            (int)rebootIntervalMillis, (int)rebootStartTime*1000,
                            (int)rebootWindowMillis, "");
                    rebootSystem("Checkin scheduled forced");
                    return;
                }

                // Are we within the reboot window?
                if (now < realStartTime) {
                    // Schedule alarm for next check interval.
                    realStartTime = computeCalendarTime(mCalendar,
                            now, rebootStartTime);
                } else if (now < (realStartTime+rebootWindowMillis)) {
                    String doit = shouldWeBeBrutalLocked(now);
                    EventLog.writeEvent(EventLogTags.WATCHDOG_SCHEDULED_REBOOT, now,
                            (int)rebootInterval, (int)rebootStartTime*1000,
                            (int)rebootWindowMillis, doit != null ? doit : "");
                    if (doit == null) {
                        rebootSystem("Checked scheduled range");
                        return;
                    }

                    // Schedule next alarm either within the window or in the
                    // next interval.
                    if ((now+recheckInterval) >= (realStartTime+rebootWindowMillis)) {
                        realStartTime = computeCalendarTime(mCalendar,
                                now + rebootIntervalMillis, rebootStartTime);
                    } else {
                        realStartTime = now + recheckInterval;
                    }
                } else {
                    // Schedule alarm for next check interval.
                    realStartTime = computeCalendarTime(mCalendar,
                            now + rebootIntervalMillis, rebootStartTime);
                }
            }
        }

        if (localLOGV) Slog.v(TAG, "Scheduling next reboot alarm for "
                + ((realStartTime-now)/1000/60) + "m from now");
        mAlarm.remove(mRebootIntent);
        mAlarm.set(AlarmManager.RTC_WAKEUP, realStartTime, mRebootIntent);
    }

    /**
     * Perform a full reboot of the system.
     */
    void rebootSystem(String reason) {
        Slog.i(TAG, "Rebooting system because: " + reason);
        PowerManagerService pms = (PowerManagerService) ServiceManager.getService("power");
        pms.reboot(reason);
    }

    /**
     * Load the current Gservices settings for when
     * {@link #shouldWeBeBrutalLocked} will allow the brutality to happen.
     * Must not be called with the lock held.
     */
    void retrieveBrutalityAmount() {
        mMinScreenOff = (mReqMinScreenOff >= 0 ? mReqMinScreenOff
                : Settings.Secure.getInt(
                mResolver, Settings.Secure.MEMCHECK_MIN_SCREEN_OFF,
                MEMCHECK_DEFAULT_MIN_SCREEN_OFF)) * 1000;
        mMinAlarm = (mReqMinNextAlarm >= 0 ? mReqMinNextAlarm
                : Settings.Secure.getInt(
                mResolver, Settings.Secure.MEMCHECK_MIN_ALARM,
                MEMCHECK_DEFAULT_MIN_ALARM)) * 1000;
    }

    /**
     * Determine whether it is a good time to kill, crash, or otherwise
     * plunder the current situation for the overall long-term benefit of
     * the world.
     *
     * @param curTime The current system time.
     * @return Returns null if this is a good time, else a String with the
     * text of why it is not a good time.
     */
    String shouldWeBeBrutalLocked(long curTime) {
        if (mBattery == null || !mBattery.isPowered()) {
            return "battery";
        }

        if (mMinScreenOff >= 0 && (mPower == null ||
                mPower.timeSinceScreenOn() < mMinScreenOff)) {
            return "screen";
        }

        if (mMinAlarm >= 0 && (mAlarm == null ||
                mAlarm.timeToNextAlarm() < mMinAlarm)) {
            return "alarm";
        }

        return null;
    }

    static long computeCalendarTime(Calendar c, long curTime,
            long secondsSinceMidnight) {

        // start with now
        c.setTimeInMillis(curTime);

        int val = (int)secondsSinceMidnight / (60*60);
        c.set(Calendar.HOUR_OF_DAY, val);
        secondsSinceMidnight -= val * (60*60);
        val = (int)secondsSinceMidnight / 60;
        c.set(Calendar.MINUTE, val);
        c.set(Calendar.SECOND, (int)secondsSinceMidnight - (val*60));
        c.set(Calendar.MILLISECOND, 0);

        long newTime = c.getTimeInMillis();
        if (newTime < curTime) {
            // The given time (in seconds since midnight) has already passed for today, so advance
            // by one day (due to daylight savings, etc., the delta may differ from 24 hours).
            c.add(Calendar.DAY_OF_MONTH, 1);
            newTime = c.getTimeInMillis();
        }

        return newTime;
    }

    @Override
    public void run() {
        boolean waitedHalf = false;
        while (true) {
            mCompleted = false;
            mHandler.sendEmptyMessage(MONITOR);

            synchronized (this) {
                long timeout = TIME_TO_WAIT;

                // NOTE: We use uptimeMillis() here because we do not want to increment the time we
                // wait while asleep. If the device is asleep then the thing that we are waiting
                // to timeout on is asleep as well and won't have a chance to run, causing a false
                // positive on when to kill things.
                long start = SystemClock.uptimeMillis();
                while (timeout > 0 && !mForceKillSystem) {
                    try {
                        wait(timeout);  // notifyAll() is called when mForceKillSystem is set
                    } catch (InterruptedException e) {
                        Log.wtf(TAG, e);
                    }
                    timeout = TIME_TO_WAIT - (SystemClock.uptimeMillis() - start);
                }

                if (mCompleted && !mForceKillSystem) {
                    // The monitors have returned.
                    waitedHalf = false;
                    continue;
                }

                if (!waitedHalf) {
                    // We've waited half the deadlock-detection interval.  Pull a stack
                    // trace and wait another half.
                    ArrayList<Integer> pids = new ArrayList<Integer>();
                    pids.add(Process.myPid());
                    ActivityManagerService.dumpStackTraces(true, pids, null, null);
                    waitedHalf = true;
                    continue;
                }
            }

            // If we got here, that means that the system is most likely hung.
            // First collect stack traces from all threads of the system process.
            // Then kill this process so that the system will restart.

            final String name = (mCurrentMonitor != null) ?
                    mCurrentMonitor.getClass().getName() : "null";
            EventLog.writeEvent(EventLogTags.WATCHDOG, name);

            ArrayList<Integer> pids = new ArrayList<Integer>();
            pids.add(Process.myPid());
            if (mPhonePid > 0) pids.add(mPhonePid);
            // Pass !waitedHalf so that just in case we somehow wind up here without having
            // dumped the halfway stacks, we properly re-initialize the trace file.
            final File stack = ActivityManagerService.dumpStackTraces(
                    !waitedHalf, pids, null, null);

            // Give some extra time to make sure the stack traces get written.
            // The system's been hanging for a minute, another second or two won't hurt much.
            SystemClock.sleep(2000);

            // Pull our own kernel thread stacks as well if we're configured for that
            if (RECORD_KERNEL_THREADS) {
                dumpKernelStackTraces();
            }

            // Try to add the error to the dropbox, but assuming that the ActivityManager
            // itself may be deadlocked.  (which has happened, causing this statement to
            // deadlock and the watchdog as a whole to be ineffective)
            Thread dropboxThread = new Thread("watchdogWriteToDropbox") {
                    public void run() {
                        mActivity.addErrorToDropBox(
                                "watchdog", null, null, null, name, null, stack, null);
                    }
                };
            dropboxThread.start();
            try {
                dropboxThread.join(2000);  // wait up to 2 seconds for it to return.
            } catch (InterruptedException ignored) {}

            // Only kill the process if the debugger is not attached.
            if (!Debug.isDebuggerConnected()) {
                Slog.w(TAG, "*** WATCHDOG KILLING SYSTEM PROCESS: " + name);
                Process.killProcess(Process.myPid());
                System.exit(10);
            } else {
                Slog.w(TAG, "Debugger connected: Watchdog is *not* killing the system process");
            }

            waitedHalf = false;
        }
    }

    private File dumpKernelStackTraces() {
        String tracesPath = SystemProperties.get("dalvik.vm.stack-trace-file", null);
        if (tracesPath == null || tracesPath.length() == 0) {
            return null;
        }

        native_dumpKernelStacks(tracesPath);
        return new File(tracesPath);
    }

    private native void native_dumpKernelStacks(String tracesPath);
}
