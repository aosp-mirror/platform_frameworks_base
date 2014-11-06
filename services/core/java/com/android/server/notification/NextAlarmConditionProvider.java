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
import android.app.PendingIntent;
import android.app.AlarmManager.AlarmClockInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserHandle;
import android.service.notification.Condition;
import android.service.notification.ConditionProviderService;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.util.TimeUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.util.Locale;

/**
 * Built-in zen condition provider for alarm-clock-based conditions.
 *
 * <p>If the user's next alarm is within a lookahead threshold (config, default 12hrs), advertise
 * it as an exit condition for zen mode (unless the built-in downtime condition is also available).
 *
 * <p>When this next alarm is selected as the active exit condition, follow subsequent changes
 * to the user's next alarm, assuming it remains within the 12-hr window.
 *
 * <p>The next alarm is defined as {@link AlarmManager#getNextAlarmClock(int)}, which does not
 * survive a reboot.  Maintain the illusion of a consistent next alarm value by holding on to
 * a persisted condition until we receive the first value after reboot, or timeout with no value.
 */
public class NextAlarmConditionProvider extends ConditionProviderService {
    private static final String TAG = "NextAlarmConditions";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ACTION_TRIGGER = TAG + ".trigger";
    private static final String EXTRA_TRIGGER = "trigger";
    private static final int REQUEST_CODE = 100;
    private static final long SECONDS = 1000;
    private static final long MINUTES = 60 * SECONDS;
    private static final long HOURS = 60 * MINUTES;
    private static final long NEXT_ALARM_UPDATE_DELAY = 1 * SECONDS;  // treat clear+set as update
    private static final long EARLY = 5 * SECONDS;  // fire early, ensure alarm stream is unmuted
    private static final long WAIT_AFTER_CONNECT = 5 * MINUTES;// for initial alarm re-registration
    private static final long WAIT_AFTER_BOOT = 20 * SECONDS;  // for initial alarm re-registration
    private static final String NEXT_ALARM_PATH = "next_alarm";
    public static final ComponentName COMPONENT =
            new ComponentName("android", NextAlarmConditionProvider.class.getName());

    private final Context mContext = this;
    private final H mHandler = new H();

    private long mConnected;
    private boolean mRegistered;
    private AlarmManager mAlarmManager;
    private int mCurrentUserId;
    private long mLookaheadThreshold;
    private long mScheduledAlarmTime;
    private Callback mCallback;
    private Uri mCurrentSubscription;
    private PowerManager.WakeLock mWakeLock;
    private long mBootCompleted;

    public NextAlarmConditionProvider() {
        if (DEBUG) Slog.d(TAG, "new NextAlarmConditionProvider()");
    }

    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    NextAlarmConditionProvider:");
        pw.print("      mConnected="); pw.println(mConnected);
        pw.print("      mBootCompleted="); pw.println(mBootCompleted);
        pw.print("      mRegistered="); pw.println(mRegistered);
        pw.print("      mCurrentUserId="); pw.println(mCurrentUserId);
        pw.print("      mScheduledAlarmTime="); pw.println(formatAlarmDebug(mScheduledAlarmTime));
        pw.print("      mLookaheadThreshold="); pw.print(mLookaheadThreshold);
        pw.print(" ("); TimeUtils.formatDuration(mLookaheadThreshold, pw); pw.println(")");
        pw.print("      mCurrentSubscription="); pw.println(mCurrentSubscription);
        pw.print("      mWakeLock="); pw.println(mWakeLock);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    @Override
    public void onConnected() {
        if (DEBUG) Slog.d(TAG, "onConnected");
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        final PowerManager p = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = p.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mLookaheadThreshold = mContext.getResources()
                .getInteger(R.integer.config_next_alarm_condition_lookahead_threshold_hrs) * HOURS;
        init();
        mConnected = System.currentTimeMillis();
    }

    public void onUserSwitched() {
        if (DEBUG) Slog.d(TAG, "onUserSwitched");
        if (mConnected != 0) {
            init();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DEBUG) Slog.d(TAG, "onDestroy");
        if (mRegistered) {
            mContext.unregisterReceiver(mReceiver);
            mRegistered = false;
        }
        mConnected = 0;
    }

    @Override
    public void onRequestConditions(int relevance) {
        if (mConnected == 0 || (relevance & Condition.FLAG_RELEVANT_NOW) == 0) return;

        final AlarmClockInfo nextAlarm = mAlarmManager.getNextAlarmClock(mCurrentUserId);
        if (nextAlarm == null) return;  // no next alarm
        if (mCallback != null && mCallback.isInDowntime()) return;  // prefer downtime condition
        if (!isWithinLookaheadThreshold(nextAlarm)) return;  // alarm not within window

        // next alarm exists, and is within the configured lookahead threshold
        notifyCondition(newConditionId(), nextAlarm, Condition.STATE_TRUE, "request");
    }

    private boolean isWithinLookaheadThreshold(AlarmClockInfo alarm) {
        if (alarm == null) return false;
        final long delta = getEarlyTriggerTime(alarm) - System.currentTimeMillis();
        return delta > 0 && (mLookaheadThreshold <= 0 || delta < mLookaheadThreshold);
    }

    @Override
    public void onSubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onSubscribe " + conditionId);
        if (!isNextAlarmCondition(conditionId)) {
            notifyCondition(conditionId, null, Condition.STATE_FALSE, "badCondition");
            return;
        }
        mCurrentSubscription = conditionId;
        mHandler.postEvaluate(0);
    }

    private static long getEarlyTriggerTime(AlarmClockInfo alarm) {
        return alarm != null ? (alarm.getTriggerTime() - EARLY) : 0;
    }

    private boolean isDoneWaitingAfterBoot(long time) {
        if (mBootCompleted > 0) return (time - mBootCompleted) > WAIT_AFTER_BOOT;
        if (mConnected > 0) return (time - mConnected) > WAIT_AFTER_CONNECT;
        return true;
    }

    private void handleEvaluate() {
        final AlarmClockInfo nextAlarm = mAlarmManager.getNextAlarmClock(mCurrentUserId);
        final long triggerTime = getEarlyTriggerTime(nextAlarm);
        final boolean withinThreshold = isWithinLookaheadThreshold(nextAlarm);
        final long now = System.currentTimeMillis();
        final boolean booted = isDoneWaitingAfterBoot(now);
        if (DEBUG) Slog.d(TAG, "handleEvaluate mCurrentSubscription=" + mCurrentSubscription
                + " nextAlarm=" + formatAlarmDebug(triggerTime)
                + " withinThreshold=" + withinThreshold
                + " booted=" + booted);
        if (mCurrentSubscription == null) return;  // no one cares
        if (!booted) {
            // we don't know yet
            notifyCondition(mCurrentSubscription, nextAlarm, Condition.STATE_UNKNOWN, "!booted");
            final long recheckTime = (mBootCompleted > 0 ? mBootCompleted : now) + WAIT_AFTER_BOOT;
            rescheduleAlarm(recheckTime);
            return;
        }
        if (!withinThreshold) {
            // triggertime invalid or in the past, condition = false
            notifyCondition(mCurrentSubscription, nextAlarm, Condition.STATE_FALSE, "!within");
            mCurrentSubscription = null;
            return;
        }
        // triggertime in the future, condition = true, schedule alarm
        notifyCondition(mCurrentSubscription, nextAlarm, Condition.STATE_TRUE, "within");
        rescheduleAlarm(triggerTime);
    }

    private static String formatDuration(long millis) {
        final StringBuilder sb = new StringBuilder();
        TimeUtils.formatDuration(millis, sb);
        return sb.toString();
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

    private void notifyCondition(Uri id, AlarmClockInfo alarm, int state, String reason) {
        final String formattedAlarm = alarm == null ? "" : formatAlarm(alarm.getTriggerTime());
        if (DEBUG) Slog.d(TAG, "notifyCondition " + Condition.stateToString(state)
                + " alarm=" + formattedAlarm + " reason=" + reason);
        notifyCondition(new Condition(id,
                mContext.getString(R.string.zen_mode_next_alarm_summary, formattedAlarm),
                mContext.getString(R.string.zen_mode_next_alarm_line_one),
                formattedAlarm, 0, state, Condition.FLAG_RELEVANT_NOW));
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onUnsubscribe " + conditionId);
        if (conditionId != null && conditionId.equals(mCurrentSubscription)) {
            mCurrentSubscription = null;
            rescheduleAlarm(0);
        }
    }

    public void attachBase(Context base) {
        attachBaseContext(base);
    }

    public IConditionProvider asInterface() {
        return (IConditionProvider) onBind(null);
    }

    private Uri newConditionId() {
        return new Uri.Builder().scheme(Condition.SCHEME)
                .authority(ZenModeConfig.SYSTEM_AUTHORITY)
                .appendPath(NEXT_ALARM_PATH)
                .appendPath(Integer.toString(mCurrentUserId))
                .build();
    }

    private boolean isNextAlarmCondition(Uri conditionId) {
        return conditionId != null && conditionId.getScheme().equals(Condition.SCHEME)
                && conditionId.getAuthority().equals(ZenModeConfig.SYSTEM_AUTHORITY)
                && conditionId.getPathSegments().size() == 2
                && conditionId.getPathSegments().get(0).equals(NEXT_ALARM_PATH)
                && conditionId.getPathSegments().get(1).equals(Integer.toString(mCurrentUserId));
    }

    private void init() {
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
        mHandler.postEvaluate(0);
    }

    private String formatAlarm(long time) {
        return formatAlarm(time, "Hm", "hma");
    }

    private String formatAlarm(long time, String skeleton24, String skeleton12) {
        final String skeleton = DateFormat.is24HourFormat(mContext) ? skeleton24 : skeleton12;
        final String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return DateFormat.format(pattern, time).toString();
    }

    private String formatAlarmDebug(AlarmClockInfo alarm) {
        return formatAlarmDebug(alarm != null ? alarm.getTriggerTime() : 0);
    }

    private String formatAlarmDebug(long time) {
        if (time <= 0) return Long.toString(time);
        return String.format("%s (%s)", time, formatAlarm(time, "Hms", "hmsa"));
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

    public interface Callback {
        boolean isInDowntime();
    }

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
}
