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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
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

    private boolean mConnected;
    private boolean mInDowntime;
    private ZenModeConfig mConfig;
    private Callback mCallback;

    public DowntimeConditionProvider() {
        if (DEBUG) Slog.d(TAG, "new DowntimeConditionProvider()");
    }

    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    DowntimeConditionProvider:");
        pw.print("      mConnected="); pw.println(mConnected);
        pw.print("      mInDowntime="); pw.println(mInDowntime);
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
        init();
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Slog.d(TAG, "onDestroy");
        mConnected = false;
    }

    @Override
    public void onRequestConditions(int relevance) {
        if (DEBUG) Slog.d(TAG, "onRequestConditions relevance=" + relevance);
        if ((relevance & Condition.FLAG_RELEVANT_NOW) != 0) {
            if (mInDowntime && mConfig != null) {
                notifyCondition(createCondition(mConfig.toDowntimeInfo(), Condition.STATE_TRUE));
            }
        }
    }

    @Override
    public void onSubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onSubscribe conditionId=" + conditionId);
        final DowntimeInfo downtime = ZenModeConfig.tryParseDowntimeConditionId(conditionId);
        if (downtime != null && mConfig != null) {
            final int state = mConfig.toDowntimeInfo().equals(downtime) && mInDowntime
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
        return mInDowntime;
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
        return new Condition(id, summary, "", "", 0, state, Condition.FLAG_RELEVANT_NOW);
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
        return isInDowntime(-1, time, start, end) || isInDowntime(0, time, start, end);
    }

    private boolean isInDowntime(int daysOffset, long time, long start, long end) {
        final int day = ((getDayOfWeek(time) + daysOffset - 1) % Calendar.SATURDAY) + 1;
        start = addDays(start, daysOffset);
        end = addDays(end, daysOffset);
        return mDays.contains(day) && time >= start && time < end;
    }

    private void reevaluateDowntime() {
        final boolean inDowntime = isInDowntime(System.currentTimeMillis());
        if (DEBUG) Slog.d(TAG, "inDowntime=" + inDowntime);
        if (inDowntime == mInDowntime) return;
        Slog.i(TAG, (inDowntime ? "Entering" : "Exiting" ) + " downtime");
        mInDowntime = inDowntime;
        ZenLog.traceDowntime(mInDowntime, getDayOfWeek(System.currentTimeMillis()), mDays);
        fireDowntimeChanged();
    }

    private void fireDowntimeChanged() {
        if (mCallback != null) {
            mCallback.onDowntimeChanged(mInDowntime);
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
                new Intent(action).putExtra(EXTRA_TIME, time), PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(pendingIntent);
        if (mConfig.sleepMode != null) {
            if (DEBUG) Slog.d(TAG, String.format("Scheduling %s for %s, %s in the future, now=%s",
                    action, ts(time), time - now, ts(now)));
            alarms.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent);
        }
    }

    private static String ts(long time) {
        return new Date(time) + " (" + time + ")";
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
            } else {
                if (DEBUG) Slog.d(TAG, action + " fired at " + now);
            }
            reevaluateDowntime();
            updateAlarms();
        }
    };

    public interface Callback {
        void onDowntimeChanged(boolean inDowntime);
    }
}
