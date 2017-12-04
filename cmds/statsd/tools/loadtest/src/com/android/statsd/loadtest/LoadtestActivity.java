/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.statsd.loadtest;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IStatsManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.StatsLog;
import android.util.StatsManager;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.MotionEvent;
import android.view.View.OnFocusChangeListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Runs a load test for statsd.
 * How it works:
 * <ul>
 *   <li> Sets up and pushes a custom config with metrics that exercise a large swath of code paths.
 *   <li> Periodically logs certain atoms into logd.
 *   <li> Impact on battery can be printed to logcat, or a bug report can be filed and analyzed
 *        in battery Historian.
 * </ul>
 * The load depends on how demanding the config is, as well as how frequently atoms are pushsed
 * to logd. Those are all controlled by 4 adjustable parameters:
 * <ul>
 *   <li> The 'replication' parameter artificially multiplies the number of metrics in the config.
 *   <li> The bucket size controls the time-bucketing the aggregate metrics.
 *   <li> The period parameter controls how frequently atoms are pushed to logd.
 *   <li> The 'burst' parameter controls how many atoms are pushed at the same time (per period).
 * </ul>
 */
public class LoadtestActivity extends Activity {

    private static final String TAG = "StatsdLoadtest";
    private static final String TYPE = "type";
    private static final String ALARM = "push_alarm";
    private static final String START = "start";
    private static final String STOP = "stop";

    public final static class PusherAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Intent activityIntent = new Intent(context, LoadtestActivity.class);
            activityIntent.putExtra(TYPE, ALARM);
            context.startActivity(activityIntent);
         }
    }

    public final static class StopperAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Intent activityIntent = new Intent(context, LoadtestActivity.class);
            activityIntent.putExtra(TYPE, STOP);
            context.startActivity(activityIntent);
         }
    }

    private AlarmManager mAlarmMgr;

    /** Used to periodically log atoms to logd. */
    private PendingIntent mPushPendingIntent;

    /** Used to end the loadtest. */
    private PendingIntent mStopPendingIntent;

    private Button mStartStop;
    private EditText mReplicationText;
    private EditText mBucketText;
    private EditText mPeriodText;
    private EditText mBurstText;
    private EditText mDurationText;
    private TextView mReportText;
    private CheckBox mPlaceboCheckBox;

    /** For measuring perf data. */
    private PerfData mPerfData;

    /** For communicating with statsd. */
    private StatsManager mStatsManager;

    private PowerManager mPowerManager;
    private WakeLock mWakeLock;

    /**
     * If true, we only measure the effect of the loadtest infrastructure. No atom are pushed and
     * the configuration is empty.
     */
    private boolean mPlacebo;

    /** The burst size. */
    private int mBurst;

    /** The metrics replication. */
    private int mReplication;

    /** The period, in seconds, at which batches of atoms are pushed. */
    private long mPeriodSecs;

    /** The bucket size, in minutes, for aggregate metrics. */
    private long mBucketMins;

    /** The duration, in minutes, of the loadtest. */
    private long mDurationMins;

    /** Whether the loadtest has started. */
    private boolean mStarted = false;

    /** Orchestrates the logging of pushed events into logd. */
    private SequencePusher mPusher;

    /** Generates statsd configs. */
    private ConfigFactory mFactory;

    /** For intra-minute periods. */
    private final Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "Starting loadtest");

        setContentView(R.layout.activity_loadtest);
        mReportText = (TextView) findViewById(R.id.report_text);
        initBurst();
        initReplication();
        initBucket();
        initPeriod();
        initDuration();
        initPlacebo();

        // Hide the keyboard outside edit texts.
        findViewById(R.id.outside).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (getCurrentFocus() != null) {
                    imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
                }
                return true;
            }
        });

        mStartStop = findViewById(R.id.start_stop);
        mStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mStarted) {
                    stopLoadtest(true);
                } else {
                    startLoadtest();
                }
            }
        });

        findViewById(R.id.display_output).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fetchAndDisplayData();
            }
        });

        findViewById(R.id.display_perf).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mPerfData.publishData(LoadtestActivity.this, mPlacebo, mReplication, mBucketMins,
                    mPeriodSecs, mBurst);
            }
        });

        mAlarmMgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        mStatsManager = (StatsManager) getSystemService("stats");
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mFactory = new ConfigFactory(this);
        mPerfData = new PerfData();
        stopLoadtest(false);
        mReportText.setText("");
    }

    @Override
    public void onNewIntent(Intent intent) {
        String type = intent.getStringExtra(TYPE);
        if (type == null) {
            return;
        }
        switch (type) {
            case ALARM:
              onAlarm(intent);
              break;
            case START:
                startLoadtest();
                break;
          case STOP:
                stopLoadtest(true);
                break;
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Destroying");
        stopLoadtest(false);
        clearConfigs();
        super.onDestroy();
    }

    private void onAlarm(Intent intent) {
        Log.d(TAG, "ON ALARM");

        // Set the next task.
        scheduleNext();

        // Do the work.
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StatsdLoadTest");
        mWakeLock.acquire();
        if (mPusher != null) {
            mPusher.next();
        }
        mWakeLock.release();
        mWakeLock = null;
    }

    /** Schedules the next cycle of pushing atoms into logd. */
    private void scheduleNext() {
        Intent intent = new Intent(this, PusherAlarmReceiver.class);
        intent.putExtra(TYPE, ALARM);
        mPushPendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
        long nextTime =  SystemClock.elapsedRealtime() + mPeriodSecs * 1000;
        mAlarmMgr.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextTime, mPushPendingIntent);
    }

    private synchronized void startLoadtest() {
        if (mStarted) {
            return;
        }

        // Clean up the state.
        stopLoadtest(false);

        // Prepare to push a sequence of atoms to logd.
        mPusher = new SequencePusher(mBurst, mPlacebo);

        // Create a config and push it to statsd.
        if (!setConfig(mFactory.getConfig(mReplication, mBucketMins * 60 * 1000, mPlacebo))) {
            return;
        }

        // Remember to stop in the future.
        Intent intent = new Intent(this, StopperAlarmReceiver.class);
        intent.putExtra(TYPE, STOP);
        mStopPendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
        long nextTime =  SystemClock.elapsedRealtime() + mDurationMins * 60 * 1000;
        mAlarmMgr.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextTime, mStopPendingIntent);

        // Log atoms.
        scheduleNext();

        // Reset battery data.
        mPerfData.resetData(this);

        mReportText.setText("");

        updateStarted(true);
    }

    private synchronized void stopLoadtest(boolean publishPerfData) {
        if (mPushPendingIntent != null) {
            Log.d(TAG, "Canceling pre-existing push alarm");
            mAlarmMgr.cancel(mPushPendingIntent);
            mPushPendingIntent = null;
        }
        if (mStopPendingIntent != null) {
            Log.d(TAG, "Canceling pre-existing stop alarm");
            mAlarmMgr.cancel(mStopPendingIntent);
            mStopPendingIntent = null;
        }
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
        fetchAndDisplayData();
        clearConfigs();
        updateStarted(false);
        if (publishPerfData) {
            mPerfData.publishData(this, mPlacebo, mReplication, mBucketMins, mPeriodSecs, mBurst);
        }
    }

    private synchronized void updateStarted(boolean started) {
        mStarted = started;
        mStartStop.setBackgroundColor(started ?
            Color.parseColor("#FFFF0000") : Color.parseColor("#FF00FF00"));
        mStartStop.setText(started ? getString(R.string.stop) : getString(R.string.start));
        updateControlsEnabled();
    }

    private void updateControlsEnabled() {
        mBurstText.setEnabled(!mPlacebo && !mStarted);
        mReplicationText.setEnabled(!mPlacebo && !mStarted);
        mPeriodText.setEnabled(!mStarted);
        mBucketText.setEnabled(!mPlacebo && !mStarted);
        mDurationText.setEnabled(!mStarted);
        mPlaceboCheckBox.setEnabled(!mStarted);
    }

    private void fetchAndDisplayData() {
        if (!statsdRunning()) {
            return;
        }
        if (mStatsManager != null) {
            byte[] data = mStatsManager.getData(ConfigFactory.CONFIG_NAME);
            if (data != null) {
                displayData(data);
            } else {
                mReportText.setText("Failed to pull data");
            }
        }
    }

    private void displayData(byte[] data) {
        com.android.os.StatsLog.ConfigMetricsReportList reports = null;
        boolean good = false;
        if (data != null) {
            try {
                reports = com.android.os.StatsLog.ConfigMetricsReportList.parseFrom(data);
                good = true;
            } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                // display it in the text view.
            }
        }
        int size = data == null ? 0 : data.length;
        StringBuilder sb = new StringBuilder();
        sb.append(good ? "Proto parsing OK!" : "Proto parsing Error!");
        sb.append(" size:").append(size).append("\n");

        if (good && reports != null) {
            DisplayProtoUtils.displayLogReport(sb, reports);
            mReportText.setText(sb.toString());
        }
    }

    private boolean statsdRunning() {
        if (IStatsManager.Stub.asInterface(ServiceManager.getService("stats")) == null) {
            Log.d(TAG, "Statsd not running");
            Toast.makeText(LoadtestActivity.this, "Statsd NOT running!", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private int sanitizeInt(int val, int min, int max) {
        if (val > max) {
            val = max;
        } else if (val < min) {
            val = min;
        }
        return val;
    }

    private void clearConfigs() {
        // TODO: Clear all configs instead of specific ones.
        if (mStatsManager != null) {
            if (!mStatsManager.removeConfiguration("fake")) {
                Log.d(TAG, "Removed \"fake\" statsd configs.");
            } else {
                Log.d(TAG, "Failed to remove \"fake\" config. Loadtest results cannot be trusted.");
            }
            if (mStarted) {
                if (!mStatsManager.removeConfiguration(ConfigFactory.CONFIG_NAME)) {
                    Log.d(TAG, "Removed loadtest statsd configs.");
                } else {
                    Log.d(TAG, "Failed to remove loadtest configs.");
                }
            }
        }
    }

    private boolean setConfig(byte[] config) {
      if (mStatsManager != null) {
            if (mStatsManager.addConfiguration(ConfigFactory.CONFIG_NAME,
                config, getPackageName(), LoadtestActivity.this.getClass().getName())) {
                Log.d(TAG, "Config pushed to statsd");
                return true;
            } else {
                Log.d(TAG, "Failed to push config to statsd");
            }
      }
      return false;
    }

    private synchronized void setReplication(int replication) {
        mReplication = replication;
    }

    private synchronized void setPeriodSecs(long periodSecs) {
        mPeriodSecs = periodSecs;
    }

    private synchronized void setBucketMins(long bucketMins) {
        mBucketMins = bucketMins;
    }

    private synchronized void setBurst(int burst) {
        mBurst = burst;
    }

    private synchronized void setDurationMins(long durationMins) {
        mDurationMins = durationMins;
    }

    private synchronized void setPlacebo(boolean placebo) {
        mPlacebo = placebo;
        updateControlsEnabled();
    }

    private void handleFocus(EditText editText) {
        editText.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && editText.getText().toString().isEmpty()) {
                    editText.setText("-1", TextView.BufferType.EDITABLE);
                }
            }
        });
    }

    private void initBurst() {
        mBurst = getResources().getInteger(R.integer.burst_default);
        mBurstText = (EditText) findViewById(R.id.burst);
        mBurstText.addTextChangedListener(new NumericalWatcher(mBurstText, 0, 50) {
            @Override
            public void onNewValue(int newValue) {
                setBurst(newValue);
            }
        });
        handleFocus(mBurstText);
    }

    private void initReplication() {
        mReplication = getResources().getInteger(R.integer.replication_default);
        mReplicationText = (EditText) findViewById(R.id.replication);
        mReplicationText.addTextChangedListener(new NumericalWatcher(mReplicationText, 1, 100) {
            @Override
            public void onNewValue(int newValue) {
                setReplication(newValue);
            }
        });
        handleFocus(mReplicationText);
    }

    private void initBucket() {
        mBucketMins = getResources().getInteger(R.integer.bucket_default);
        mBucketText = (EditText) findViewById(R.id.bucket);
        mBucketText.addTextChangedListener(new NumericalWatcher(mBucketText, 1, 24 * 60) {
            @Override
            public void onNewValue(int newValue) {
                setBucketMins(newValue);
            }
        });
        handleFocus(mBucketText);
    }

    private void initPeriod() {
        mPeriodSecs = getResources().getInteger(R.integer.period_default);
        mPeriodText = (EditText) findViewById(R.id.period);
        mPeriodText.addTextChangedListener(new NumericalWatcher(mPeriodText, 1, 60) {
            @Override
            public void onNewValue(int newValue) {
                setPeriodSecs(newValue);
            }
        });
        handleFocus(mPeriodText);
    }

    private void initDuration() {
        mDurationMins = getResources().getInteger(R.integer.duration_default);
        mDurationText = (EditText) findViewById(R.id.duration);
        mDurationText.addTextChangedListener(new NumericalWatcher(mDurationText, 1, 24 * 60) {
            @Override
            public void onNewValue(int newValue) {
                setDurationMins(newValue);
            }
        });
        handleFocus(mDurationText);
    }

    private void initPlacebo() {
        mPlaceboCheckBox = findViewById(R.id.placebo);
        mPlacebo = false;
        mPlaceboCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setPlacebo(((CheckBox) view).isChecked());
            }
        });
    }
}
