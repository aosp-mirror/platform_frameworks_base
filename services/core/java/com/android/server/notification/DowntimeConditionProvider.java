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
import android.util.TimeUtils;

import com.android.internal.R;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
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

    private static final long SECONDS = 1000;
    private static final long MINUTES = 60 * SECONDS;
    private static final long HOURS = 60 * MINUTES;

    private final Context mContext = this;
    private final DowntimeCalendar mCalendar = new DowntimeCalendar();
    private final FiredAlarms mFiredAlarms = new FiredAlarms();
    private final ArraySet<Uri> mSubscriptions = new ArraySet<Uri>();
    private final ConditionProviders mConditionProviders;
    private final NextAlarmTracker mTracker;
    private final ZenModeHelper mZenModeHelper;

    private boolean mConnected;
    private long mLookaheadThreshold;
    private ZenModeConfig mConfig;
    private boolean mDowntimed;
    private boolean mConditionClearing;
    private boolean mRequesting;

    public DowntimeConditionProvider(ConditionProviders conditionProviders,
            NextAlarmTracker tracker, ZenModeHelper zenModeHelper) {
        if (DEBUG) Slog.d(TAG, "new DowntimeConditionProvider()");
        mConditionProviders = conditionProviders;
        mTracker = tracker;
        mZenModeHelper = zenModeHelper;
    }

    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    DowntimeConditionProvider:");
        pw.print("      mConnected="); pw.println(mConnected);
        pw.print("      mSubscriptions="); pw.println(mSubscriptions);
        pw.print("      mLookaheadThreshold="); pw.print(mLookaheadThreshold);
        pw.print(" ("); TimeUtils.formatDuration(mLookaheadThreshold, pw); pw.println(")");
        pw.print("      mCalendar="); pw.println(mCalendar);
        pw.print("      mFiredAlarms="); pw.println(mFiredAlarms);
        pw.print("      mDowntimed="); pw.println(mDowntimed);
        pw.print("      mConditionClearing="); pw.println(mConditionClearing);
        pw.print("      mRequesting="); pw.println(mRequesting);
    }

    public void attachBase(Context base) {
        attachBaseContext(base);
    }

    public IConditionProvider asInterface() {
        return (IConditionProvider) onBind(null);
    }

    @Override
    public void onConnected() {
        if (DEBUG) Slog.d(TAG, "onConnected");
        mConnected = true;
        mLookaheadThreshold = PropConfig.getInt(mContext, "downtime.condition.lookahead",
                R.integer.config_downtime_condition_lookahead_threshold_hrs) * HOURS;
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ENTER_ACTION);
        filter.addAction(EXIT_ACTION);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
        mTracker.addCallback(mTrackerCallback);
        mZenModeHelper.addCallback(mZenCallback);
        init();
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Slog.d(TAG, "onDestroy");
        mTracker.removeCallback(mTrackerCallback);
        mZenModeHelper.removeCallback(mZenCallback);
        mConnected = false;
    }

    @Override
    public void onRequestConditions(int relevance) {
        if (DEBUG) Slog.d(TAG, "onRequestConditions relevance=" + relevance);
        if (!mConnected) return;
        mRequesting = (relevance & Condition.FLAG_RELEVANT_NOW) != 0;
        evaluateSubscriptions();
    }

    @Override
    public void onSubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onSubscribe conditionId=" + conditionId);
        final DowntimeInfo downtime = ZenModeConfig.tryParseDowntimeConditionId(conditionId);
        if (downtime == null) return;
        mFiredAlarms.clear();
        mSubscriptions.add(conditionId);
        notifyCondition(downtime);
    }

    private boolean shouldShowCondition() {
        final long now = System.currentTimeMillis();
        if (DEBUG) Slog.d(TAG, "shouldShowCondition now=" + mCalendar.isInDowntime(now)
                + " lookahead="
                + (mCalendar.nextDowntimeStart(now) <= (now + mLookaheadThreshold)));
        return mCalendar.isInDowntime(now)
                || mCalendar.nextDowntimeStart(now) <= (now + mLookaheadThreshold);
    }

    private void notifyCondition(DowntimeInfo downtime) {
        if (mConfig == null) {
            // we don't know yet
            notifyCondition(createCondition(downtime, Condition.STATE_UNKNOWN));
            return;
        }
        if (!downtime.equals(mConfig.toDowntimeInfo())) {
            // not the configured downtime, consider it false
            notifyCondition(createCondition(downtime, Condition.STATE_FALSE));
            return;
        }
        if (!shouldShowCondition()) {
            // configured downtime, but not within the time range
            notifyCondition(createCondition(downtime, Condition.STATE_FALSE));
            return;
        }
        if (isZenNone() && mFiredAlarms.findBefore(System.currentTimeMillis())) {
            // within the configured time range, but wake up if none and the next alarm is fired
            notifyCondition(createCondition(downtime, Condition.STATE_FALSE));
            return;
        }
        // within the configured time range, condition still valid
        notifyCondition(createCondition(downtime, Condition.STATE_TRUE));
    }

    private boolean isZenNone() {
        return mZenModeHelper.getZenMode() == Global.ZEN_MODE_NO_INTERRUPTIONS;
    }

    private boolean isZenOff() {
        return mZenModeHelper.getZenMode() == Global.ZEN_MODE_OFF;
    }

    private void evaluateSubscriptions() {
        ArraySet<Uri> conditions = mSubscriptions;
        if (mConfig != null && mRequesting && shouldShowCondition()) {
            final Uri id = ZenModeConfig.toDowntimeConditionId(mConfig.toDowntimeInfo());
            if (!conditions.contains(id)) {
                conditions = new ArraySet<Uri>(conditions);
                conditions.add(id);
            }
        }
        for (Uri conditionId : conditions) {
            final DowntimeInfo downtime = ZenModeConfig.tryParseDowntimeConditionId(conditionId);
            if (downtime != null) {
                notifyCondition(downtime);
            }
        }
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        final boolean current = mSubscriptions.contains(conditionId);
        if (DEBUG) Slog.d(TAG, "onUnsubscribe conditionId=" + conditionId + " current=" + current);
        mSubscriptions.remove(conditionId);
        mFiredAlarms.clear();
    }

    public void setConfig(ZenModeConfig config) {
        if (Objects.equals(mConfig, config)) return;
        final boolean downtimeChanged = mConfig == null || config == null
                || !mConfig.toDowntimeInfo().equals(config.toDowntimeInfo());
        mConfig = config;
        if (DEBUG) Slog.d(TAG, "setConfig downtimeChanged=" + downtimeChanged);
        if (mConnected && downtimeChanged) {
            mDowntimed = false;
            init();
        }
        // when active, mark downtime as entered for today
        if (mConfig != null && mConfig.exitCondition != null
                && ZenModeConfig.isValidDowntimeConditionId(mConfig.exitCondition.id)) {
            mDowntimed = true;
        }
    }

    public void onManualConditionClearing() {
        mConditionClearing = true;
    }

    private Condition createCondition(DowntimeInfo downtime, int state) {
        if (downtime == null) return null;
        final Uri id = ZenModeConfig.toDowntimeConditionId(downtime);
        final String skeleton = DateFormat.is24HourFormat(mContext) ? "Hm" : "hma";
        final Locale locale = Locale.getDefault();
        final String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        final long now = System.currentTimeMillis();
        long endTime = mCalendar.getNextTime(now, downtime.endHour, downtime.endMinute);
        if (isZenNone()) {
            final AlarmClockInfo nextAlarm = mTracker.getNextAlarm();
            final long nextAlarmTime = nextAlarm != null ? nextAlarm.getTriggerTime() : 0;
            if (nextAlarmTime > now && nextAlarmTime < endTime) {
                endTime = nextAlarmTime;
            }
        }
        final String formatted = new SimpleDateFormat(pattern, locale).format(new Date(endTime));
        final String summary = mContext.getString(R.string.downtime_condition_summary, formatted);
        final String line1 = mContext.getString(R.string.downtime_condition_line_one);
        return new Condition(id, summary, line1, formatted, 0, state, Condition.FLAG_RELEVANT_NOW);
    }

    private void init() {
        mCalendar.setDowntimeInfo(mConfig != null ? mConfig.toDowntimeInfo() : null);
        evaluateSubscriptions();
        updateAlarms();
        evaluateAutotrigger();
    }

    private void updateAlarms() {
        if (mConfig == null) return;
        updateAlarm(ENTER_ACTION, ENTER_CODE, mConfig.sleepStartHour, mConfig.sleepStartMinute);
        updateAlarm(EXIT_ACTION, EXIT_CODE, mConfig.sleepEndHour, mConfig.sleepEndMinute);
    }


    private void updateAlarm(String action, int requestCode, int hr, int min) {
        final AlarmManager alarms = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        final long now = System.currentTimeMillis();
        final long time = mCalendar.getNextTime(now, hr, min);
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
        if (DEBUG) Slog.d(TAG, "onEvaluateNextAlarm " + mTracker.formatAlarmDebug(nextAlarm));
        if (nextAlarm != null && wakeupTime > 0 && System.currentTimeMillis() > wakeupTime) {
            if (DEBUG) Slog.d(TAG, "Alarm fired: " + mTracker.formatAlarmDebug(wakeupTime));
            mFiredAlarms.add(wakeupTime);
        }
        evaluateSubscriptions();
    }

    private void evaluateAutotrigger() {
        String skipReason = null;
        if (mConfig == null) {
            skipReason = "no config";
        } else if (mDowntimed) {
            skipReason = "already downtimed";
        } else if (mZenModeHelper.getZenMode() != Global.ZEN_MODE_OFF) {
            skipReason = "already in zen";
        } else if (!mCalendar.isInDowntime(System.currentTimeMillis())) {
            skipReason = "not in downtime";
        }
        if (skipReason != null) {
            ZenLog.traceDowntimeAutotrigger("Autotrigger skipped: " + skipReason);
            return;
        }
        ZenLog.traceDowntimeAutotrigger("Autotrigger fired");
        mZenModeHelper.setZenMode(mConfig.sleepNone ? Global.ZEN_MODE_NO_INTERRUPTIONS
                : Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS, "downtime");
        final Condition condition = createCondition(mConfig.toDowntimeInfo(), Condition.STATE_TRUE);
        mConditionProviders.setZenModeCondition(condition, "downtime");
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
                if (ENTER_ACTION.equals(action)) {
                    evaluateAutotrigger();
                } else /*EXIT_ACTION*/ {
                    mDowntimed = false;
                }
                mFiredAlarms.clear();
            } else if (Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                if (DEBUG) Slog.d(TAG, "timezone changed to " + TimeZone.getDefault());
                mCalendar.setTimeZone(TimeZone.getDefault());
                mFiredAlarms.clear();
            } else if (Intent.ACTION_TIME_CHANGED.equals(action)) {
                if (DEBUG) Slog.d(TAG, "time changed to " + now);
                mFiredAlarms.clear();
            } else {
                if (DEBUG) Slog.d(TAG, action + " fired at " + now);
            }
            evaluateSubscriptions();
            updateAlarms();
        }
    };

    private final NextAlarmTracker.Callback mTrackerCallback = new NextAlarmTracker.Callback() {
        @Override
        public void onEvaluate(AlarmClockInfo nextAlarm, long wakeupTime, boolean booted) {
            DowntimeConditionProvider.this.onEvaluateNextAlarm(nextAlarm, wakeupTime, booted);
        }
    };

    private final ZenModeHelper.Callback mZenCallback = new ZenModeHelper.Callback() {
        @Override
        void onZenModeChanged() {
            if (mConditionClearing && isZenOff()) {
                evaluateAutotrigger();
            }
            mConditionClearing = false;
            evaluateSubscriptions();
        }
    };

    private class FiredAlarms {
        private final ArraySet<Long> mFiredAlarms = new ArraySet<Long>();

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mFiredAlarms.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(mTracker.formatAlarmDebug(mFiredAlarms.valueAt(i)));
            }
            return sb.toString();
        }

        public void add(long firedAlarm) {
            mFiredAlarms.add(firedAlarm);
        }

        public void clear() {
            mFiredAlarms.clear();
        }

        public boolean findBefore(long time) {
            for (int i = 0; i < mFiredAlarms.size(); i++) {
                if (mFiredAlarms.valueAt(i) < time) {
                    return true;
                }
            }
            return false;
        }
    }
}
