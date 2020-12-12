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

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import android.util.TimeUtils;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;
import com.android.internal.os.BatteryStatsHelper;
import com.android.internal.os.LoggingPrintStream;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PowerMetricsCollector implements TestRule {
    private final String mTag;
    private final float mBatteryDrainThresholdPct;
    private final int mTimeoutMillis;

    private final Context mContext;
    private final UserManager mUserManager;
    private final int mUid;
    private final BatteryStatsHelper mStatsHelper;
    private final CountDownLatch mSuspendingBatteryInput = new CountDownLatch(1);

    private long mStartTime;
    private volatile float mInitialBatteryLevel;
    private volatile float mCurrentBatteryLevel;
    private int mIterations;
    private PowerMetrics mInitialPowerMetrics;
    private PowerMetrics mFinalPowerMetrics;
    private List<PowerMetrics.Metric> mPowerMetricsDelta;
    private Intent mBatteryStatus;

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                BroadcastReceiver batteryBroadcastReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        handleBatteryStatus(intent);
                    }
                };
                mBatteryStatus = mContext.registerReceiver(batteryBroadcastReceiver,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                disableCharger();
                try {
                    prepareBatteryLevelMonitor();
                    mStartTime = SystemClock.uptimeMillis();
                    base.evaluate();
                    captureFinalPowerStatsData();
                } finally {
                    mContext.unregisterReceiver(batteryBroadcastReceiver);
                    enableCharger();
                }
            }
        };
    }

    public PowerMetricsCollector(String tag, float batteryDrainThresholdPct, int timeoutMillis) {
        mTag = tag;
        mBatteryDrainThresholdPct = batteryDrainThresholdPct;
        mTimeoutMillis = timeoutMillis;

        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = instrumentation.getContext();
        mUid = Process.myUid();
        mUserManager = mContext.getSystemService(UserManager.class);
        // TODO(b/175324611): Use BatteryUsageStats instead
        mStatsHelper = new BatteryStatsHelper(mContext, false /* collectBatteryBroadcast */);
        mStatsHelper.create((Bundle) null);
    }

    private void disableCharger() throws InterruptedException {
        SystemUtil.runShellCommand("dumpsys battery suspend_input");
        final boolean success = mSuspendingBatteryInput.await(10, TimeUnit.SECONDS);
        assertTrue("Timed out waiting for battery input to be suspended", success);
    }

    private void enableCharger() {
        SystemUtil.runShellCommand("dumpsys battery reset");
    }

    private PowerMetrics readBatteryStatsData() {
        mStatsHelper.clearStats();
        mStatsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED,
                mUserManager.getUserProfiles());
        return new PowerMetrics(mStatsHelper, mUid);
    }

    protected void prepareBatteryLevelMonitor() {
        handleBatteryStatus(mBatteryStatus);
        mInitialBatteryLevel = mCurrentBatteryLevel;
    }

    protected void handleBatteryStatus(Intent intent) {
        if (mFinalPowerMetrics != null) {
            return;
        }

        final boolean isCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;

        if (mSuspendingBatteryInput.getCount() > 0) {
            if (!isCharging) {
                mSuspendingBatteryInput.countDown();
            }
            return;
        }

        if (isCharging) {
            fail("Device must remain disconnected from the power source "
                    + "for the duration of the test");
        }

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        mCurrentBatteryLevel = level * 100 / (float) scale;
        Log.i(mTag, "Battery level = " + mCurrentBatteryLevel);

        // We delay tracking until the battery level drops.  If the resolution of
        // battery level is 1%, and the initially reported level is 73, we don't know whether
        // it's 73.1 or 73.7. Once it drops to 72, we can be confident that the real battery
        // level it is very close to 72.0 and can start tracking.
        if (mInitialPowerMetrics == null && mCurrentBatteryLevel < mInitialBatteryLevel) {
            mInitialBatteryLevel = mCurrentBatteryLevel;
            mInitialPowerMetrics = readBatteryStatsData();
        }
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
                if (metric.title.equals(initialMetric.title)) {
                    finalMetric = metric;
                    break;
                }
            }

            if (finalMetric != null) {
                PowerMetrics.Metric delta = new PowerMetrics.Metric();
                delta.metricType = initialMetric.metricType;
                delta.metricKind = initialMetric.metricKind;
                delta.title = initialMetric.title;
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

    public void dumpMetrics() {
        dumpMetrics(new LoggingPrintStream() {
            @Override
            protected void log(String line) {
                Log.i(mTag, line);
            }
        });
    }

    public void dumpMetrics(PrintStream out) {
        List<PowerMetrics.Metric> initialPowerMetrics = mInitialPowerMetrics.getMetrics();
        List<PowerMetrics.Metric> finalPowerMetrics = mFinalPowerMetrics.getMetrics();

        out.println("== Power metrics at test start");
        dumpPowerStatsData(out, initialPowerMetrics);

        out.println("== Power metrics at test end");
        dumpPowerStatsData(out, finalPowerMetrics);

        out.println("== Power metrics delta");
        dumpPowerStatsData(out, mPowerMetricsDelta);
    }

    protected void dumpPowerStatsData(PrintStream out, List<PowerMetrics.Metric> metrics) {
        Locale locale = Locale.getDefault();
        for (PowerMetrics.Metric metric : metrics) {
            double proportion = metric.total != 0 ? metric.value * 100 / metric.total : 0;
            switch (metric.metricKind) {
                case POWER:
                    out.println(
                            String.format(locale, "    %-30s %7.1f mAh %4.1f%%", metric.title,
                                    metric.value, proportion));
                    break;
                case DURATION:
                    out.println(
                            String.format(locale, "    %-30s %,7d ms  %4.1f%%", metric.title,
                                    (long) metric.value, proportion));
                    break;
            }
        }
    }

    public void dumpMetricAsPercentageOfDrainedPower(String metricType) {
        double minDrainedPower =
                mFinalPowerMetrics.getMinDrainedPower() - mInitialPowerMetrics.getMinDrainedPower();
        double maxDrainedPower =
                mFinalPowerMetrics.getMaxDrainedPower() - mInitialPowerMetrics.getMaxDrainedPower();

        PowerMetrics.Metric metric = getMetric(metricType);
        double metricDelta = metric.value;

        if (maxDrainedPower - minDrainedPower < 0.1f) {
            Log.i(mTag, String.format(Locale.getDefault(),
                    "%s power consumed by the test: %.1f of %.1f mAh (%.1f%%)",
                    metric.title, metricDelta, maxDrainedPower,
                    metricDelta / maxDrainedPower * 100));
        } else {
            Log.i(mTag, String.format(Locale.getDefault(),
                    "%s power consumed by the test: %.1f of %.1f - %.1f mAh (%.1f%% - %.1f%%)",
                    metric.title, metricDelta, minDrainedPower, maxDrainedPower,
                    metricDelta / minDrainedPower * 100, metricDelta / maxDrainedPower * 100));
        }
    }

    public PowerMetrics.Metric getMetric(String metricType) {
        for (PowerMetrics.Metric metric : mPowerMetricsDelta) {
            if (metric.metricType.equals(metricType)) {
                return metric;
            }
        }
        return null;
    }
}
