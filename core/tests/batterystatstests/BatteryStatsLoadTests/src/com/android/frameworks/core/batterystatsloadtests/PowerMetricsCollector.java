/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.frameworks.core.batterystatsloadtests;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryConsumer;
import android.os.BatteryManager;
import android.os.BatteryStatsManager;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Process;
import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.TimeUtils;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PowerMetricsCollector implements TestRule {
    private final String mTag;
    private final float mBatteryDrainThresholdPct;
    private final int mTimeoutMillis;

    private final Instrumentation mInstrumentation;
    private final Context mContext;
    private final int mUid;
    private final ConditionVariable mSuspendingBatteryInput = new ConditionVariable();

    private long mStartTime;
    private volatile float mInitialBatteryLevel;
    private volatile float mCurrentBatteryLevel;
    private int mIterations;
    private PowerMetrics mInitialPowerMetrics;
    private PowerMetrics mFinalPowerMetrics;
    private List<PowerMetrics.Metric> mPowerMetricsDelta;
    private final BatteryStatsManager mBatteryStatsManager;
    private final BroadcastReceiver mBatteryLevelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleBatteryStatus(intent);
        }
    };
    private final Bundle mStatus = new Bundle();
    private final StringWriter mReportStringWriter = new StringWriter();
    private final IndentingPrintWriter mReportWriter =
            new IndentingPrintWriter(mReportStringWriter);

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                disableCharger();
                try {
                    mStartTime = SystemClock.uptimeMillis();
                    mContext.registerReceiver(mBatteryLevelReceiver,
                            new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    base.evaluate();
                    captureFinalPowerStatsData();
                    mStatus.putString("report", mReportStringWriter.toString());
                    mInstrumentation.sendStatus(Activity.RESULT_OK, mStatus);
                } finally {
                    mContext.unregisterReceiver(mBatteryLevelReceiver);
                    enableCharger();
                }
            }
        };
    }

    public PowerMetricsCollector(String tag, float batteryDrainThresholdPct, int timeoutMillis) {
        mTag = tag;
        mBatteryDrainThresholdPct = batteryDrainThresholdPct;
        mTimeoutMillis = timeoutMillis;

        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getContext();
        mUid = Process.myUid();
        mBatteryStatsManager = mContext.getSystemService(BatteryStatsManager.class);
    }

    private void disableCharger() {
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isCharging(intent)) {
                    mInitialBatteryLevel = mCurrentBatteryLevel = getBatteryLevel(intent);
                    mSuspendingBatteryInput.open();
                }
            }
        };
        final Intent intent = mContext.registerReceiver(
                receiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        if (isCharging(intent)) {
            mBatteryStatsManager.suspendBatteryInput();
            final boolean success = mSuspendingBatteryInput.block(10000);
            assertTrue("Timed out waiting for battery input to be suspended", success);
        }

        mContext.unregisterReceiver(receiver);
    }

    private void enableCharger() {
        mBatteryStatsManager.resetBattery(/* forceUpdate */false);
    }

    private PowerMetrics readBatteryStatsData() {
        return new PowerMetrics(mBatteryStatsManager.getBatteryUsageStats(), mUid);
    }

    protected void handleBatteryStatus(Intent intent) {
        if (mFinalPowerMetrics != null) {
            return;
        }

        if (isCharging(intent)) {
            fail("Device must remain disconnected from the power source "
                    + "for the duration of the test");
        }

        mCurrentBatteryLevel = getBatteryLevel(intent);
        Log.i(mTag, "Battery level = " + mCurrentBatteryLevel);

        // We delay tracking until the battery level drops.  If the resolution of
        // battery level is 1%, and the initially reported level is 73, we don't know whether
        // it's 73.1 or 73.7. Once it drops to 72, we can be confident that the real battery
        // level is very close to 72.0 and can start tracking.
        if (mInitialPowerMetrics == null && mCurrentBatteryLevel < mInitialBatteryLevel) {
            mInitialBatteryLevel = mCurrentBatteryLevel;
            mInitialPowerMetrics = readBatteryStatsData();
        }
    }

    private boolean isCharging(Intent intent) {
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;
    }

    private float getBatteryLevel(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        return level * 100 / (float) scale;
    }

    private void captureFinalPowerStatsData() {
        if (mFinalPowerMetrics != null) {
            return;
        }

        mFinalPowerMetrics = readBatteryStatsData();

        mPowerMetricsDelta = new ArrayList<>();
        List<PowerMetrics.Metric> initialPowerMetrics = mInitialPowerMetrics.getMetrics();
        List<PowerMetrics.Metric> finalPowerMetrics = mFinalPowerMetrics.getMetrics();
        for (PowerMetrics.Metric initialMetric : initialPowerMetrics) {
            PowerMetrics.Metric finalMetric = null;
            for (PowerMetrics.Metric metric : finalPowerMetrics) {
                if (metric.metricName.equals(initialMetric.metricName)) {
                    finalMetric = metric;
                    break;
                }
            }

            if (finalMetric != null) {
                PowerMetrics.Metric delta = new PowerMetrics.Metric();
                delta.metricName = initialMetric.metricName;
                delta.metricKind = initialMetric.metricKind;
                delta.statusKeyPrefix = initialMetric.statusKeyPrefix;
                delta.total = finalMetric.total - initialMetric.total;
                delta.value = finalMetric.value - initialMetric.value;
                mPowerMetricsDelta.add(delta);
            }
        }
    }

    /**
     * Returns false if sufficient data has been accumulated.
     */
    public boolean checkpoint() {
        long elapsedTime = SystemClock.uptimeMillis() - mStartTime;
        if (elapsedTime >= mTimeoutMillis) {
            Log.i(mTag, "Timeout reached " + TimeUtils.formatDuration(elapsedTime));
            captureFinalPowerStatsData();
            return false;
        }

        if (mInitialPowerMetrics == null) {
            return true;
        }

        if (mInitialBatteryLevel - mCurrentBatteryLevel >= mBatteryDrainThresholdPct) {
            Log.i(mTag,
                    "Battery drain reached " + (mInitialBatteryLevel - mCurrentBatteryLevel) + "%");
            captureFinalPowerStatsData();
            return false;
        }

        mIterations++;
        return true;
    }


    public int getIterationCount() {
        return mIterations;
    }

    public void report(String line) {
        mReportWriter.println(line);
    }

    public void reportMetrics() {
        List<PowerMetrics.Metric> initialPowerMetrics = mInitialPowerMetrics.getMetrics();
        List<PowerMetrics.Metric> finalPowerMetrics = mFinalPowerMetrics.getMetrics();

        mReportWriter.println("Power metrics at test start");
        mReportWriter.increaseIndent();
        reportPowerStatsData(initialPowerMetrics);
        mReportWriter.decreaseIndent();

        mReportWriter.println("Power metrics at test end");
        mReportWriter.increaseIndent();
        reportPowerStatsData(finalPowerMetrics);
        mReportWriter.decreaseIndent();

        mReportWriter.println("Power metrics delta");
        mReportWriter.increaseIndent();
        reportPowerStatsData(mPowerMetricsDelta);
        mReportWriter.decreaseIndent();
    }

    protected void reportPowerStatsData(List<PowerMetrics.Metric> metrics) {
        Locale locale = Locale.getDefault();
        for (PowerMetrics.Metric metric : metrics) {
            double proportion = metric.total != 0 ? metric.value * 100 / metric.total : 0;
            switch (metric.metricKind) {
                case POWER:
                    mReportWriter.println(
                            String.format(locale, "%-40s %7.1f mAh %4.1f%%", metric.metricName,
                                    metric.value, proportion));
                    break;
                case DURATION:
                    mReportWriter.println(
                            String.format(locale, "%-40s %,7d ms  %4.1f%%", metric.metricName,
                                    (long) metric.value, proportion));
                    break;
            }
        }
    }

    public void reportMetricAsPercentageOfDrainedPower(
            @BatteryConsumer.PowerComponent int component) {
        double drainedPower =
                mFinalPowerMetrics.getDrainedPower() - mInitialPowerMetrics.getDrainedPower();

        PowerMetrics.Metric metric = getPowerMetric(component);
        double metricDelta = metric.value;

        final double percent = metricDelta / drainedPower * 100;
        mStatus.putDouble(metric.statusKeyPrefix, metricDelta);
        mStatus.putDouble(metric.statusKeyPrefix + "_pct", percent);

        mReportWriter.println(String.format(Locale.getDefault(),
                "%s power consumed by the test: %.1f of %.1f mAh (%.1f%%)",
                metric.metricName, metricDelta, drainedPower, percent));
    }

    public PowerMetrics.Metric getPowerMetric(@BatteryConsumer.PowerComponent int component) {
        final String name = PowerMetrics.getPowerMetricName(component);
        for (PowerMetrics.Metric metric : mPowerMetricsDelta) {
            if (metric.metricName.equals(name)) {
                return metric;
            }
        }
        return null;
    }

    public PowerMetrics.Metric getTimeMetric(@BatteryConsumer.PowerComponent int component) {
        final String name = PowerMetrics.getDurationMetricName(component);
        for (PowerMetrics.Metric metric : mPowerMetricsDelta) {
            if (metric.metricName.equals(name)) {
                return metric;
            }
        }
        return null;
    }
}
