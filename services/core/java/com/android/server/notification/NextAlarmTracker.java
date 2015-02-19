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
 * limitations under the License.
 */

package com.android.server.notification;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AlarmManager.AlarmClockInfo;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Locale;

/** Helper for tracking updates to the current user's next alarm. */
public class NextAlarmTracker {
    private static final String TAG = "NextAlarmTracker";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ACTION_TRIGGER = TAG + ".trigger";
    private static final String EXTRA_TRIGGER = "trigger";
    private static final int REQUEST_CODE = 100;

    private static final long SECONDS = 1000;
    private static final long MINUTES = 60 * SECONDS;
    private static final long NEXT_ALARM_UPDATE_DELAY = 1 * SECONDS;  // treat clear+set as update
    private static final long EARLY = 5 * SECONDS;  // fire early, ensure alarm stream is unmuted
    private static final long WAIT_AFTER_INIT = 5 * MINUTES;// for initial alarm re-registration
    private static final long WAIT_AFTER_BOOT = 20 * SECONDS;  // for initial alarm re-registration

    private final Context mContext;
    private final H mHandler = new H();
    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();

    private long mInit;
    private boolean mRegistered;
    private AlarmManager mAlarmManager;
    private int mCurrentUserId;
    private long mScheduledAlarmTime;
    private long mBootCompleted;
    private PowerManager.WakeLock mWakeLock;

    public NextAlarmTracker(Context context) {
        mContext = context;
    }

    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    NextAlarmTracker:");
        pw.print("      len(mCallbacks)="); pw.println(mCallbacks.size());
        pw.print("      mRegistered="); pw.println(mRegistered);
        pw.print("      mInit="); pw.println(mInit);
        pw.print("      mBootCompleted="); pw.println(mBootCompleted);
        pw.print("      mCurrentUserId="); pw.println(mCurrentUserId);
        pw.print("      mScheduledAlarmTime="); pw.println(formatAlarmDebug(mScheduledAlarmTime));
        pw.print("      mWakeLock="); pw.println(mWakeLock);
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    public int getCurrentUserId() {
        return mCurrentUserId;
    }

    public AlarmClockInfo getNextAlarm() {
        return mAlarmManager.getNextAlarmClock(mCurrentUserId);
    }

    public void onUserSwitched() {
        reset();
    }

    public void init() {
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        final PowerManager p = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = p.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mInit = System.currentTimeMillis();
        reset();
    }

    public void reset() {
        if (mRegistered) {
            mContext.unregisterReceiver(mReceiver);
        }
        mCurrentUserId = ActivityManager.getCurrentUser();
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        filter.addAction(ACTION_TRIGGER);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        mContext.registerReceiverAsUser(mReceiver, new UserHandle(mCurrentUserId), filter, null,
                null);
        mRegistered = true;
        evaluate();
    }

    public void destroy() {
        if (mRegistered) {
            mContext.unregisterReceiver(mReceiver);
            mRegistered = false;
        }
    }

    public void evaluate() {
        mHandler.postEvaluate(0);
    }

    private void fireEvaluate(AlarmClockInfo nextAlarm, long wakeupTime, boolean booted) {
        for (Callback callback : mCallbacks) {
            callback.onEvaluate(nextAlarm, wakeupTime, booted);
        }
    }

    private void handleEvaluate() {
        final AlarmClockInfo nextAlarm = mAlarmManager.getNextAlarmClock(mCurrentUserId);
        final long triggerTime = getEarlyTriggerTime(nextAlarm);
        final long now = System.currentTimeMillis();
        final boolean alarmUpcoming = triggerTime > now;
        final boolean booted = isDoneWaitingAfterBoot(now);
        if (DEBUG) Slog.d(TAG, "handleEvaluate nextAlarm=" + formatAlarmDebug(triggerTime)
                + " alarmUpcoming=" + alarmUpcoming
                + " booted=" + booted);
        fireEvaluate(nextAlarm, triggerTime, booted);
        if (!booted) {
            // recheck after boot
            final long recheckTime = (mBootCompleted > 0 ? mBootCompleted : now) + WAIT_AFTER_BOOT;
            rescheduleAlarm(recheckTime);
            return;
        }
        if (alarmUpcoming) {
            // wake up just before the next alarm
            rescheduleAlarm(triggerTime);
        }
    }

    public static long getEarlyTriggerTime(AlarmClockInfo alarm) {
        return alarm != null ? (alarm.getTriggerTime() - EARLY) : 0;
    }

    private boolean isDoneWaitingAfterBoot(long time) {
        if (mBootCompleted > 0) return (time - mBootCompleted) > WAIT_AFTER_BOOT;
        if (mInit > 0) return (time - mInit) > WAIT_AFTER_INIT;
        return true;
    }

    public static String formatDuration(long millis) {
        final StringBuilder sb = new StringBuilder();
        TimeUtils.formatDuration(millis, sb);
        return sb.toString();
    }

    public String formatAlarm(AlarmClockInfo alarm) {
        return alarm != null ? formatAlarm(alarm.getTriggerTime()) : null;
    }

    private String formatAlarm(long time) {
        return formatAlarm(time, "Hm", "hma");
    }

    private String formatAlarm(long time, String skeleton24, String skeleton12) {
        final String skeleton = DateFormat.is24HourFormat(mContext) ? skeleton24 : skeleton12;
        final String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return DateFormat.format(pattern, time).toString();
    }

    public String formatAlarmDebug(AlarmClockInfo alarm) {
        return formatAlarmDebug(alarm != null ? alarm.getTriggerTime() : 0);
    }

    public String formatAlarmDebug(long time) {
        if (time <= 0) return Long.toString(time);
        return String.format("%s (%s)", time, formatAlarm(time, "Hms", "hmsa"));
    }

    private void rescheduleAlarm(long time) {
        if (DEBUG) Slog.d(TAG, "rescheduleAlarm " + time);
        final AlarmManager alarms = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, REQUEST_CODE,
                new Intent(ACTION_TRIGGER)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_TRIGGER, time),
                PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(pendingIntent);
        mScheduledAlarmTime = time;
        if (time > 0) {
            if (DEBUG) Slog.d(TAG, String.format("Scheduling alarm for %s (in %s)",
                    formatAlarmDebug(time), formatDuration(time - System.currentTimeMillis())));
            alarms.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DEBUG) Slog.d(TAG, "onReceive " + action);
            long delay = 0;
            if (action.equals(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)) {
                delay = NEXT_ALARM_UPDATE_DELAY;
                if (DEBUG) Slog.d(TAG, String.format("  next alarm for user %s: %s",
                        mCurrentUserId,
                        formatAlarmDebug(mAlarmManager.getNextAlarmClock(mCurrentUserId))));
            } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                mBootCompleted = System.currentTimeMillis();
            }
            mHandler.postEvaluate(delay);
            mWakeLock.acquire(delay + 5000);  // stay awake during evaluate
        }
    };

    private class H extends Handler {
        private static final int MSG_EVALUATE = 1;

        public void postEvaluate(long delay) {
            removeMessages(MSG_EVALUATE);
            sendEmptyMessageDelayed(MSG_EVALUATE, delay);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_EVALUATE) {
                handleEvaluate();
            }
        }
    }

    public interface Callback {
        void onEvaluate(AlarmClockInfo nextAlarm, long wakeupTime, boolean booted);
    }
}
