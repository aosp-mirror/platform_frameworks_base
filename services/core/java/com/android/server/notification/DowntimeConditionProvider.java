/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.AlarmManager.AlarmClockInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.ConditionProviderService;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.DowntimeInfo;
import android.text.format.DateFormat;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

/** Built-in zen condition provider for managing downtime */
public class DowntimeConditionProvider extends ConditionProviderService {
    private static final String TAG = "DowntimeConditions";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final ComponentName COMPONENT =
            new ComponentName("android", DowntimeConditionProvider.class.getName());

    private static final String ENTER_ACTION = TAG + ".enter";
    private static final int ENTER_CODE = 100;
    private static final String EXIT_ACTION = TAG + ".exit";
    private static final int EXIT_CODE = 101;
    private static final String EXTRA_TIME = "time";

    private final Calendar mCalendar = Calendar.getInstance();
    private final Context mContext = this;
    private final ArraySet<Integer> mDays = new ArraySet<Integer>();
    private final ArraySet<Long> mFiredAlarms = new ArraySet<Long>();

    private boolean mConnected;
    private NextAlarmTracker mTracker;
    private int mDowntimeMode;
    private ZenModeConfig mConfig;
    private Callback mCallback;

    public DowntimeConditionProvider() {
        if (DEBUG) Slog.d(TAG, "new DowntimeConditionProvider()");
    }

    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    DowntimeConditionProvider:");
        pw.print("      mConnected="); pw.println(mConnected);
        pw.print("      mDowntimeMode="); pw.println(Global.zenModeToString(mDowntimeMode));
        pw.print("      mFiredAlarms="); pw.println(mFiredAlarms);
    }

    public void attachBase(Context base) {
        attachBaseContext(base);
    }

    public IConditionProvider asInterface() {
        return (IConditionProvider) onBind(null);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    @Override
    public void onConnected() {
        if (DEBUG) Slog.d(TAG, "onConnected");
        mConnected = true;
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ENTER_ACTION);
        filter.addAction(EXIT_ACTION);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
        mTracker = mCallback.getNextAlarmTracker();
        mTracker.addCallback(mTrackerCallback);
        init();
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Slog.d(TAG, "onDestroy");
        mTracker.removeCallback(mTrackerCallback);
        mConnected = false;
    }

    @Override
    public void onRequestConditions(int relevance) {
        if (DEBUG) Slog.d(TAG, "onRequestConditions relevance=" + relevance);
        if ((relevance & Condition.FLAG_RELEVANT_NOW) != 0) {
            if (isInDowntime() && mConfig != null) {
                notifyCondition(createCondition(mConfig.toDowntimeInfo(), Condition.STATE_TRUE));
            }
        }
    }

    @Override
    public void onSubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onSubscribe conditionId=" + conditionId);
        final DowntimeInfo downtime = ZenModeConfig.tryParseDowntimeConditionId(conditionId);
        if (downtime != null && mConfig != null) {
            final int state = mConfig.toDowntimeInfo().equals(downtime) && isInDowntime()
                    ? Condition.STATE_TRUE : Condition.STATE_FALSE;
            if (DEBUG) Slog.d(TAG, "notify condition state: " + Condition.stateToString(state));
            notifyCondition(createCondition(downtime, state));
        }
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onUnsubscribe conditionId=" + conditionId);
    }

    public void setConfig(ZenModeConfig config) {
        if (Objects.equals(mConfig, config)) return;
        if (DEBUG) Slog.d(TAG, "setConfig");
        mConfig = config;
        if (mConnected) {
            init();
        }
    }

    public boolean isInDowntime() {
        return mDowntimeMode != Global.ZEN_MODE_OFF;
    }

    public Condition createCondition(DowntimeInfo downtime, int state) {
        if (downtime == null) return null;
        final Uri id = ZenModeConfig.toDowntimeConditionId(downtime);
        final String skeleton = DateFormat.is24HourFormat(mContext) ? "Hm" : "hma";
        final Locale locale = Locale.getDefault();
        final String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        final long time = getTime(System.currentTimeMillis(), downtime.endHour, downtime.endMinute);
        final String formatted = new SimpleDateFormat(pattern, locale).format(new Date(time));
        final String summary = mContext.getString(R.string.downtime_condition_summary, formatted);
        final String line1 = mContext.getString(R.string.downtime_condition_line_one);
        return new Condition(id, summary, line1, formatted, 0, state, Condition.FLAG_RELEVANT_NOW);
    }

    public boolean isDowntimeCondition(Condition condition) {
        return condition != null && ZenModeConfig.isValidDowntimeConditionId(condition.id);
    }

    private void init() {
        updateDays();
        reevaluateDowntime();
        updateAlarms();
    }

    private void updateDays() {
        mDays.clear();
        if (mConfig != null) {
            final int[] days = ZenModeConfig.tryParseDays(mConfig.sleepMode);
            for (int i = 0; days != null && i < days.length; i++) {
                mDays.add(days[i]);
            }
        }
    }

    private boolean isInDowntime(long time) {
        if (mConfig == null || mDays.size() == 0) return false;
        final long start = getTime(time, mConfig.sleepStartHour, mConfig.sleepStartMinute);
        long end = getTime(time, mConfig.sleepEndHour, mConfig.sleepEndMinute);
        if (start == end) return false;
        if (end < start) {
            end = addDays(end, 1);
        }
        final boolean orAlarm = mConfig.sleepNone;
        return isInDowntime(-1, time, start, end, orAlarm)
                || isInDowntime(0, time, start, end, orAlarm);
    }

    private boolean isInDowntime(int daysOffset, long time, long start, long end, boolean orAlarm) {
        final int n = Calendar.SATURDAY;
        final int day = ((getDayOfWeek(time) - 1) + (daysOffset % n) + n) % n + 1;
        start = addDays(start, daysOffset);
        end = addDays(end, daysOffset);
        if (orAlarm) {
            end = findFiredAlarm(start, end);
        }
        return mDays.contains(day) && time >= start && time < end;
    }

    private long findFiredAlarm(long start, long end) {
        final int N = mFiredAlarms.size();
        for (int i = 0; i < N; i++) {
            final long firedAlarm = mFiredAlarms.valueAt(i);
            if (firedAlarm > start && firedAlarm < end) {
                return firedAlarm;
            }
        }
        return end;
    }

    private void reevaluateDowntime() {
        final long now = System.currentTimeMillis();
        final boolean inDowntimeNow = isInDowntime(now);
        final int downtimeMode = inDowntimeNow ? (mConfig.sleepNone
                ? Global.ZEN_MODE_NO_INTERRUPTIONS : Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
                : Global.ZEN_MODE_OFF;
        if (DEBUG) Slog.d(TAG, "downtimeMode=" + downtimeMode);
        if (downtimeMode == mDowntimeMode) return;
        mDowntimeMode = downtimeMode;
        Slog.i(TAG, (isInDowntime() ? "Entering" : "Exiting" ) + " downtime");
        ZenLog.traceDowntime(mDowntimeMode, getDayOfWeek(now), mDays);
        fireDowntimeChanged();
    }

    private void fireDowntimeChanged() {
        if (mCallback != null) {
            mCallback.onDowntimeChanged(mDowntimeMode);
        }
    }

    private void updateAlarms() {
        if (mConfig == null) return;
        updateAlarm(ENTER_ACTION, ENTER_CODE, mConfig.sleepStartHour, mConfig.sleepStartMinute);
        updateAlarm(EXIT_ACTION, EXIT_CODE, mConfig.sleepEndHour, mConfig.sleepEndMinute);
    }

    private int getDayOfWeek(long time) {
        mCalendar.setTimeInMillis(time);
        return mCalendar.get(Calendar.DAY_OF_WEEK);
    }

    private long getTime(long millis, int hour, int min) {
        mCalendar.setTimeInMillis(millis);
        mCalendar.set(Calendar.HOUR_OF_DAY, hour);
        mCalendar.set(Calendar.MINUTE, min);
        mCalendar.set(Calendar.SECOND, 0);
        mCalendar.set(Calendar.MILLISECOND, 0);
        return mCalendar.getTimeInMillis();
    }

    private long addDays(long time, int days) {
        mCalendar.setTimeInMillis(time);
        mCalendar.add(Calendar.DATE, days);
        return mCalendar.getTimeInMillis();
    }

    private void updateAlarm(String action, int requestCode, int hr, int min) {
        final AlarmManager alarms = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        final long now = System.currentTimeMillis();
        mCalendar.setTimeInMillis(now);
        mCalendar.set(Calendar.HOUR_OF_DAY, hr);
        mCalendar.set(Calendar.MINUTE, min);
        mCalendar.set(Calendar.SECOND, 0);
        mCalendar.set(Calendar.MILLISECOND, 0);
        long time = mCalendar.getTimeInMillis();
        if (time <= now) {
            time = addDays(time, 1);
        }
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, requestCode,
                new Intent(action)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_TIME, time),
                PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(pendingIntent);
        if (mConfig.sleepMode != null) {
            if (DEBUG) Slog.d(TAG, String.format("Scheduling %s for %s, in %s, now=%s",
                    action, ts(time), NextAlarmTracker.formatDuration(time - now), ts(now)));
            alarms.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent);
        }
    }

    private static String ts(long time) {
        return new Date(time) + " (" + time + ")";
    }

    private void onEvaluateNextAlarm(AlarmClockInfo nextAlarm, long wakeupTime, boolean booted) {
        if (!booted) return;  // we don't know yet
        if (nextAlarm == null) return;  // not fireable
        if (DEBUG) Slog.d(TAG, "onEvaluateNextAlarm " + mTracker.formatAlarmDebug(nextAlarm));
        if (System.currentTimeMillis() > wakeupTime) {
            if (DEBUG) Slog.d(TAG, "Alarm fired: " + mTracker.formatAlarmDebug(wakeupTime));
            trimFiredAlarms();
            mFiredAlarms.add(wakeupTime);
        }
        reevaluateDowntime();
    }

    private void trimFiredAlarms() {
        // remove fired alarms over 2 days old
        final long keepAfter = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000;
        final int N = mFiredAlarms.size();
        for (int i = N - 1; i >= 0; i--) {
            final long firedAlarm = mFiredAlarms.valueAt(i);
            if (firedAlarm < keepAfter) {
                mFiredAlarms.removeAt(i);
            }
        }
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final long now = System.currentTimeMillis();
            if (ENTER_ACTION.equals(action) || EXIT_ACTION.equals(action)) {
                final long schTime = intent.getLongExtra(EXTRA_TIME, 0);
                if (DEBUG) Slog.d(TAG, String.format("%s scheduled for %s, fired at %s, delta=%s",
                        action, ts(schTime), ts(now), now - schTime));
            } else if (Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                if (DEBUG) Slog.d(TAG, "timezone changed to " + TimeZone.getDefault());
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                if (DEBUG) Slog.d(TAG, action + " fired at " + now);
            }
            reevaluateDowntime();
            updateAlarms();
        }
    };

    private final NextAlarmTracker.Callback mTrackerCallback = new NextAlarmTracker.Callback() {
        @Override
        public void onEvaluate(AlarmClockInfo nextAlarm, long wakeupTime, boolean booted) {
            DowntimeConditionProvider.this.onEvaluateNextAlarm(nextAlarm, wakeupTime, booted);
        }
    };

    public interface Callback {
        void onDowntimeChanged(int downtimeMode);
        NextAlarmTracker getNextAlarmTracker();
    }
}
