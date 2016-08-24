/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.settingslib;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.BatteryStats.HistoryItem;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.Log;
import android.util.SparseIntArray;
import com.android.internal.os.BatteryStatsHelper;
import com.android.settingslib.graph.UsageView;

public class BatteryInfo {

    public String mChargeLabelString;
    public int mBatteryLevel;
    public boolean mDischarging = true;
    public long remainingTimeUs = 0;
    public String batteryPercentString;
    public String remainingLabel;
    private BatteryStats mStats;
    private boolean mCharging;
    private long timePeriod;

    public interface Callback {
        void onBatteryInfoLoaded(BatteryInfo info);
    }

    public void bindHistory(final UsageView view, BatteryDataParser... parsers) {
        BatteryDataParser parser = new BatteryDataParser() {
            SparseIntArray points = new SparseIntArray();

            @Override
            public void onParsingStarted(long startTime, long endTime) {
                timePeriod = endTime - startTime - remainingTimeUs / 1000;
                view.clearPaths();
                view.configureGraph((int) (endTime - startTime), 100, remainingTimeUs != 0,
                        mCharging);
            }

            @Override
            public void onDataPoint(long time, HistoryItem record) {
                points.put((int) time, record.batteryLevel);
            }

            @Override
            public void onDataGap() {
                if (points.size() > 1) {
                    view.addPath(points);
                }
                points.clear();
            }

            @Override
            public void onParsingDone() {
                if (points.size() > 1) {
                    view.addPath(points);
                }
            }
        };
        BatteryDataParser[] parserList = new BatteryDataParser[parsers.length + 1];
        for (int i = 0; i < parsers.length; i++) {
            parserList[i] = parsers[i];
        }
        parserList[parsers.length] = parser;
        parse(mStats, remainingTimeUs, parserList);
        final Context context = view.getContext();
        String timeString = context.getString(R.string.charge_length_format,
                Formatter.formatShortElapsedTime(context, timePeriod));
        String remaining = "";
        if (remainingTimeUs != 0) {
            remaining = context.getString(R.string.remaining_length_format,
                    Formatter.formatShortElapsedTime(context, remainingTimeUs / 1000));
        }
        view.setBottomLabels(new CharSequence[]{timeString, remaining});
    }

    public static void getBatteryInfo(final Context context, final Callback callback) {
        BatteryInfo.getBatteryInfo(context, callback, false);
    }

    public static void getBatteryInfo(final Context context, final Callback callback,
            boolean shortString) {
        new AsyncTask<Void, Void, BatteryStats>() {
            @Override
            protected BatteryStats doInBackground(Void... params) {
                BatteryStatsHelper statsHelper = new BatteryStatsHelper(context, true);
                statsHelper.create((Bundle) null);
                return statsHelper.getStats();
            }

            @Override
            protected void onPostExecute(BatteryStats batteryStats) {
                final long elapsedRealtimeUs = SystemClock.elapsedRealtime() * 1000;
                Intent batteryBroadcast = context.registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                BatteryInfo batteryInfo = BatteryInfo.getBatteryInfo(context,
                        batteryBroadcast, batteryStats, elapsedRealtimeUs, shortString);
                callback.onBatteryInfoLoaded(batteryInfo);
            }
        }.execute();
    }

    public static BatteryInfo getBatteryInfo(Context context, Intent batteryBroadcast,
                                             BatteryStats stats, long elapsedRealtimeUs) {
        return BatteryInfo.getBatteryInfo(context, batteryBroadcast, stats, elapsedRealtimeUs,
                false);
    }

    public static BatteryInfo getBatteryInfo(Context context, Intent batteryBroadcast,
            BatteryStats stats, long elapsedRealtimeUs, boolean shortString) {
        BatteryInfo info = new BatteryInfo();
        info.mStats = stats;
        info.mBatteryLevel = Utils.getBatteryLevel(batteryBroadcast);
        info.batteryPercentString = Utils.formatPercentage(info.mBatteryLevel);
        info.mCharging = batteryBroadcast.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        final Resources resources = context.getResources();
        if (!info.mCharging) {
            final long drainTime = stats.computeBatteryTimeRemaining(elapsedRealtimeUs);
            if (drainTime > 0) {
                info.remainingTimeUs = drainTime;
                String timeString = Formatter.formatShortElapsedTime(context,
                        drainTime / 1000);
                info.remainingLabel = resources.getString(
                        shortString ? R.string.power_remaining_duration_only_short
                                : R.string.power_remaining_duration_only,
                        timeString);
                info.mChargeLabelString = resources.getString(
                        shortString ? R.string.power_discharging_duration_short
                                : R.string.power_discharging_duration,
                        info.batteryPercentString, timeString);
            } else {
                info.remainingLabel = null;
                info.mChargeLabelString = info.batteryPercentString;
            }
        } else {
            final long chargeTime = stats.computeChargeTimeRemaining(elapsedRealtimeUs);
            final String statusLabel = Utils.getBatteryStatus(
                    resources, batteryBroadcast, shortString);
            final int status = batteryBroadcast.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            if (chargeTime > 0 && status != BatteryManager.BATTERY_STATUS_FULL) {
                info.mDischarging = false;
                info.remainingTimeUs = chargeTime;
                String timeString = Formatter.formatShortElapsedTime(context,
                        chargeTime / 1000);
                int plugType = batteryBroadcast.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                int resId;
                if (plugType == BatteryManager.BATTERY_PLUGGED_AC) {
                    resId = shortString ? R.string.power_charging_duration_ac_short
                            : R.string.power_charging_duration_ac;
                } else if (plugType == BatteryManager.BATTERY_PLUGGED_USB) {
                    resId = shortString ? R.string.power_charging_duration_usb_short
                            : R.string.power_charging_duration_usb;
                } else if (plugType == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                    resId = shortString ? R.string.power_charging_duration_wireless_short
                            : R.string.power_charging_duration_wireless;
                } else {
                    resId = shortString ? R.string.power_charging_duration_short
                            : R.string.power_charging_duration;
                }
                info.remainingLabel = resources.getString(R.string.power_remaining_duration_only,
                        timeString);
                info.mChargeLabelString = resources.getString(
                        resId, info.batteryPercentString, timeString);
            } else {
                info.remainingLabel = statusLabel;
                info.mChargeLabelString = resources.getString(
                        R.string.power_charging, info.batteryPercentString, statusLabel);
            }
        }
        return info;
    }

    public interface BatteryDataParser {
        void onParsingStarted(long startTime, long endTime);

        void onDataPoint(long time, HistoryItem record);

        void onDataGap();

        void onParsingDone();
    }

    private static void parse(BatteryStats stats, long remainingTimeUs,
            BatteryDataParser... parsers) {
        long startWalltime = 0;
        long endDateWalltime = 0;
        long endWalltime = 0;
        long historyStart = 0;
        long historyEnd = 0;
        byte lastLevel = -1;
        long curWalltime = startWalltime;
        long lastWallTime = 0;
        long lastRealtime = 0;
        int lastInteresting = 0;
        int pos = 0;
        boolean first = true;
        if (stats.startIteratingHistoryLocked()) {
            final HistoryItem rec = new HistoryItem();
            while (stats.getNextHistoryLocked(rec)) {
                pos++;
                if (first) {
                    first = false;
                    historyStart = rec.time;
                }
                if (rec.cmd == HistoryItem.CMD_CURRENT_TIME
                        || rec.cmd == HistoryItem.CMD_RESET) {
                    // If there is a ridiculously large jump in time, then we won't be
                    // able to create a good chart with that data, so just ignore the
                    // times we got before and pretend like our data extends back from
                    // the time we have now.
                    // Also, if we are getting a time change and we are less than 5 minutes
                    // since the start of the history real time, then also use this new
                    // time to compute the base time, since whatever time we had before is
                    // pretty much just noise.
                    if (rec.currentTime > (lastWallTime + (180 * 24 * 60 * 60 * 1000L))
                            || rec.time < (historyStart + (5 * 60 * 1000L))) {
                        startWalltime = 0;
                    }
                    lastWallTime = rec.currentTime;
                    lastRealtime = rec.time;
                    if (startWalltime == 0) {
                        startWalltime = lastWallTime - (lastRealtime - historyStart);
                    }
                }
                if (rec.isDeltaData()) {
                    if (rec.batteryLevel != lastLevel || pos == 1) {
                        lastLevel = rec.batteryLevel;
                    }
                    lastInteresting = pos;
                    historyEnd = rec.time;
                }
            }
        }
        stats.finishIteratingHistoryLocked();
        endDateWalltime = lastWallTime + historyEnd - lastRealtime;
        endWalltime = endDateWalltime + (remainingTimeUs / 1000);

        int i = 0;
        final int N = lastInteresting;

        for (int j = 0; j < parsers.length; j++) {
            parsers[j].onParsingStarted(startWalltime, endWalltime);
        }
        if (endDateWalltime > startWalltime && stats.startIteratingHistoryLocked()) {
            final HistoryItem rec = new HistoryItem();
            while (stats.getNextHistoryLocked(rec) && i < N) {
                if (rec.isDeltaData()) {
                    curWalltime += rec.time - lastRealtime;
                    lastRealtime = rec.time;
                    long x = (curWalltime - startWalltime);
                    if (x < 0) {
                        x = 0;
                    }
                    for (int j = 0; j < parsers.length; j++) {
                        parsers[j].onDataPoint(x, rec);
                    }
                } else {
                    long lastWalltime = curWalltime;
                    if (rec.cmd == HistoryItem.CMD_CURRENT_TIME
                            || rec.cmd == HistoryItem.CMD_RESET) {
                        if (rec.currentTime >= startWalltime) {
                            curWalltime = rec.currentTime;
                        } else {
                            curWalltime = startWalltime + (rec.time - historyStart);
                        }
                        lastRealtime = rec.time;
                    }

                    if (rec.cmd != HistoryItem.CMD_OVERFLOW
                            && (rec.cmd != HistoryItem.CMD_CURRENT_TIME
                            || Math.abs(lastWalltime - curWalltime) > (60 * 60 * 1000))) {
                        for (int j = 0; j < parsers.length; j++) {
                            parsers[j].onDataGap();
                        }
                    }
                }
                i++;
            }
        }

        stats.finishIteratingHistoryLocked();

        for (int j = 0; j < parsers.length; j++) {
            parsers[j].onParsingDone();
        }
    }
}
