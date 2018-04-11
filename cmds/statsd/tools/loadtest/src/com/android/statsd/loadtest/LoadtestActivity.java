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

import android.annotation.Nullable;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.StatsManager;
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
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.MotionEvent;
import android.view.View.OnFocusChangeListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.StatsdStatsReport;
import com.android.internal.os.StatsdConfigProto.TimeUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a load test for statsd.
 * How it works:
 * <ul>
 * <li> Sets up and pushes a custom config with metrics that exercise a large swath of code paths.
 * <li> Periodically logs certain atoms into logd.
 * <li> Impact on battery can be printed to logcat, or a bug report can be filed and analyzed
 * in battery Historian.
 * </ul>
 * The load depends on how demanding the config is, as well as how frequently atoms are pushsed
 * to logd. Those are all controlled by 4 adjustable parameters:
 * <ul>
 * <li> The 'replication' parameter artificially multiplies the number of metrics in the config.
 * <li> The bucket size controls the time-bucketing the aggregate metrics.
 * <li> The period parameter controls how frequently atoms are pushed to logd.
 * <li> The 'burst' parameter controls how many atoms are pushed at the same time (per period).
 * </ul>
 */
public class LoadtestActivity extends Activity implements AdapterView.OnItemSelectedListener {

    private static final String TAG = "loadtest.LoadtestActivity";
    public static final String TYPE = "type";
    private static final String PUSH_ALARM = "push_alarm";
    public static final String PERF_ALARM = "perf_alarm";
    private static final String SET_REPLICATION = "set_replication";
    private static final String REPLICATION = "replication";
    private static final String START = "start";
    private static final String STOP = "stop";
    private static final Map<String, TimeUnit> TIME_UNIT_MAP = initializeTimeUnitMap();
    private static final List<String> TIME_UNIT_LABELS = initializeTimeUnitLabels();

    public final static class PusherAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Intent activityIntent = new Intent(context, LoadtestActivity.class);
            activityIntent.putExtra(TYPE, PUSH_ALARM);
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

    private static Map<String, TimeUnit> initializeTimeUnitMap() {
        Map<String, TimeUnit> labels = new HashMap();
        labels.put("1m", TimeUnit.ONE_MINUTE);
        labels.put("5m", TimeUnit.FIVE_MINUTES);
        labels.put("10m", TimeUnit.TEN_MINUTES);
        labels.put("30m", TimeUnit.THIRTY_MINUTES);
        labels.put("1h", TimeUnit.ONE_HOUR);
        labels.put("3h", TimeUnit.THREE_HOURS);
        labels.put("6h", TimeUnit.SIX_HOURS);
        labels.put("12h", TimeUnit.TWELVE_HOURS);
        labels.put("1d", TimeUnit.ONE_DAY);
        labels.put("1s", TimeUnit.CTS);
        return labels;
    }

    private static List<String> initializeTimeUnitLabels() {
        List<String> labels = new ArrayList();
        labels.add("1s");
        labels.add("1m");
        labels.add("5m");
        labels.add("10m");
        labels.add("30m");
        labels.add("1h");
        labels.add("3h");
        labels.add("6h");
        labels.add("12h");
        labels.add("1d");
        return labels;
    }

    private AlarmManager mAlarmMgr;

    /**
     * Used to periodically log atoms to logd.
     */
    private PendingIntent mPushPendingIntent;

    /**
     * Used to end the loadtest.
     */
    private PendingIntent mStopPendingIntent;

    private Button mStartStop;
    private EditText mReplicationText;
    private Spinner mBucketSpinner;
    private EditText mPeriodText;
    private EditText mBurstText;
    private EditText mDurationText;
    private TextView mReportText;
    private CheckBox mPlaceboCheckBox;
    private CheckBox mCountMetricCheckBox;
    private CheckBox mDurationMetricCheckBox;
    private CheckBox mEventMetricCheckBox;
    private CheckBox mValueMetricCheckBox;
    private CheckBox mGaugeMetricCheckBox;

    /**
     * When the load test started.
     */
    private long mStartedTimeMillis;

    /**
     * For measuring perf data.
     */
    private PerfData mPerfData;

    /**
     * For communicating with statsd.
     */
    private StatsManager mStatsManager;

    private PowerManager mPowerManager;
    private WakeLock mWakeLock;

    /**
     * If true, we only measure the effect of the loadtest infrastructure. No atom are pushed and
     * the configuration is empty.
     */
    private boolean mPlacebo;

    /**
     * Whether to include CountMetric in the config.
     */
    private boolean mIncludeCountMetric;

    /**
     * Whether to include DurationMetric in the config.
     */
    private boolean mIncludeDurationMetric;

    /**
     * Whether to include EventMetric in the config.
     */
    private boolean mIncludeEventMetric;

    /**
     * Whether to include ValueMetric in the config.
     */
    private boolean mIncludeValueMetric;

    /**
     * Whether to include GaugeMetric in the config.
     */
    private boolean mIncludeGaugeMetric;

    /**
     * The burst size.
     */
    private int mBurst;

    /**
     * The metrics replication.
     */
    private int mReplication;

    /**
     * The period, in seconds, at which batches of atoms are pushed.
     */
    private long mPeriodSecs;

    /**
     * The bucket size, in minutes, for aggregate metrics.
     */
    private TimeUnit mBucket;

    /**
     * The duration, in minutes, of the loadtest.
     */
    private long mDurationMins;

    /**
     * Whether the loadtest has started.
     */
    private boolean mStarted = false;

    /**
     * Orchestrates the logging of pushed events into logd.
     */
    private SequencePusher mPusher;

    /**
     * Generates statsd configs.
     */
    private ConfigFactory mFactory;

    /**
     * For intra-minute periods.
     */
    private final Handler mHandler = new Handler();

    /**
     * Number of metrics in the current config.
     */
    private int mNumMetrics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "Starting loadtest Activity");

        setContentView(R.layout.activity_loadtest);
        mReportText = (TextView) findViewById(R.id.report_text);
        initBurst();
        initReplication();
        initBucket();
        initPeriod();
        initDuration();
        initPlacebo();
        initMetricWhitelist();

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
                    stopLoadtest();
                } else {
                    startLoadtest();
                }
            }
        });

        mAlarmMgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        mStatsManager = (StatsManager) getSystemService("stats");
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mFactory = new ConfigFactory(this);
        stopLoadtest();
        mReportText.setText("");
    }

    @Override
    public void onNewIntent(Intent intent) {
        String type = intent.getStringExtra(TYPE);
        if (type == null) {
            return;
        }
        switch (type) {
            case PERF_ALARM:
                onPerfAlarm();
                break;
            case PUSH_ALARM:
                onAlarm();
                break;
            case SET_REPLICATION:
                if (intent.hasExtra(REPLICATION)) {
                    setReplication(intent.getIntExtra(REPLICATION, 0));
                }
                break;
            case START:
                startLoadtest();
                break;
            case STOP:
                stopLoadtest();
                break;
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Destroying");
        mPerfData.onDestroy();
        stopLoadtest();
        clearConfigs();
        super.onDestroy();
    }

    @Nullable
    public StatsdStatsReport getMetadata() {
        if (!statsdRunning()) {
            return null;
        }
        if (mStatsManager != null) {
            byte[] data;
            try {
                data = mStatsManager.getStatsMetadata();
            } catch (StatsManager.StatsUnavailableException e) {
                Log.e(TAG, "Failed to get data from statsd", e);
                return null;
            }
            if (data != null) {
                StatsdStatsReport report = null;
                boolean good = false;
                try {
                    return StatsdStatsReport.parseFrom(data);
                } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                    Log.d(TAG, "Bad StatsdStatsReport");
                }
            }
        }
        return null;
    }

    @Nullable
    public List<ConfigMetricsReport> getData() {
        if (!statsdRunning()) {
            return null;
        }
        if (mStatsManager != null) {
            byte[] data;
            try {
                data = mStatsManager.getReports(ConfigFactory.CONFIG_ID);
            } catch (StatsManager.StatsUnavailableException e) {
                Log.e(TAG, "Failed to get data from statsd", e);
                return null;
            }
            if (data != null) {
                ConfigMetricsReportList reports = null;
                try {
                    reports = ConfigMetricsReportList.parseFrom(data);
                    Log.d(TAG, "Num reports: " + reports.getReportsCount());
                    StringBuilder sb = new StringBuilder();
                    DisplayProtoUtils.displayLogReport(sb, reports);
                    Log.d(TAG, sb.toString());
                } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                    Log.d(TAG, "Invalid data");
                }
                if (reports != null) {
                    return reports.getReportsList();
                }
            }
        }
        return null;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        String item = parent.getItemAtPosition(position).toString();

        mBucket = TIME_UNIT_MAP.get(item);
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // Another interface callback
    }

    private void onPerfAlarm() {
        if (mPerfData != null) {
            mPerfData.onAlarm(this);
        }
        // Piggy-back on that alarm to show the elapsed time.
        long elapsedTimeMins = (long) Math.floor(
                (SystemClock.elapsedRealtime() - mStartedTimeMillis) / 60 / 1000);
        mReportText.setText("Loadtest in progress.\n"
                + "num metrics =" + mNumMetrics
                + "\nElapsed time = " + elapsedTimeMins + " min(s)");
    }

    private void onAlarm() {
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

    /**
     * Schedules the next cycle of pushing atoms into logd.
     */
    private void scheduleNext() {
        Intent intent = new Intent(this, PusherAlarmReceiver.class);
        intent.putExtra(TYPE, PUSH_ALARM);
        mPushPendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
        long nextTime = SystemClock.elapsedRealtime() + mPeriodSecs * 1000;
        mAlarmMgr.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextTime, mPushPendingIntent);
    }

    private synchronized void startLoadtest() {
        if (mStarted) {
            return;
        }

        // Clean up the state.
        stopLoadtest();

        // Prepare to push a sequence of atoms to logd.
        mPusher = new SequencePusher(mBurst, mPlacebo);

        // Create a config and push it to statsd.
        if (!setConfig(mFactory.getConfig(mReplication, mBucket, mPlacebo,
                mIncludeCountMetric, mIncludeDurationMetric, mIncludeEventMetric,
                mIncludeValueMetric, mIncludeGaugeMetric))) {
            return;
        }

        // Remember to stop in the future.
        Intent intent = new Intent(this, StopperAlarmReceiver.class);
        intent.putExtra(TYPE, STOP);
        mStopPendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
        long nextTime = SystemClock.elapsedRealtime() + mDurationMins * 60 * 1000;
        mAlarmMgr.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextTime, mStopPendingIntent);

        // Log atoms.
        scheduleNext();

        // Start tracking performance.
        mPerfData = new PerfData(this, mPlacebo, mReplication, mBucket, mPeriodSecs, mBurst,
                mIncludeCountMetric, mIncludeDurationMetric, mIncludeEventMetric, mIncludeValueMetric,
                mIncludeGaugeMetric);
        mPerfData.startRecording(this);

        mReportText.setText("Loadtest in progress.\nnum metrics =" + mNumMetrics);
        mStartedTimeMillis = SystemClock.elapsedRealtime();

        updateStarted(true);
    }

    private synchronized void stopLoadtest() {
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
        if (mPerfData != null) {
            mPerfData.stopRecording(this);
            mPerfData.onDestroy();
            mPerfData = null;
        }

        // Obtain the latest data and display it.
        getData();

        long elapsedTimeMins = (long) Math.floor(
                (SystemClock.elapsedRealtime() - mStartedTimeMillis) / 60 / 1000);
        mReportText.setText("Loadtest ended. Elapsed time = " + elapsedTimeMins + " min(s)");
        clearConfigs();
        updateStarted(false);
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
        mBucketSpinner.setEnabled(!mPlacebo && !mStarted);
        mDurationText.setEnabled(!mStarted);
        mPlaceboCheckBox.setEnabled(!mStarted);

        boolean enabled = !mStarted && !mPlaceboCheckBox.isChecked();
        mCountMetricCheckBox.setEnabled(enabled);
        mDurationMetricCheckBox.setEnabled(enabled);
        mEventMetricCheckBox.setEnabled(enabled);
        mValueMetricCheckBox.setEnabled(enabled);
        mGaugeMetricCheckBox.setEnabled(enabled);
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
            if (mStarted) {
                try {
                    mStatsManager.removeConfig(ConfigFactory.CONFIG_ID);
                    Log.d(TAG, "Removed loadtest statsd configs.");
                } catch (StatsManager.StatsUnavailableException e) {
                    Log.e(TAG, "Failed to remove loadtest configs.", e);
                }
            }
        }
    }

    private boolean setConfig(ConfigFactory.ConfigMetadata configData) {
        if (mStatsManager != null) {
            try {
                mStatsManager.addConfig(ConfigFactory.CONFIG_ID, configData.bytes);
                mNumMetrics = configData.numMetrics;
                Log.d(TAG, "Config pushed to statsd");
                return true;
            } catch (StatsManager.StatsUnavailableException | IllegalArgumentException e) {
                Log.e(TAG, "Failed to push config to statsd", e);
            }
        }
        return false;
    }

    private synchronized void setReplication(int replication) {
        if (mStarted) {
            return;
        }
        mReplicationText.setText("" + replication);
    }

    private synchronized void setPeriodSecs(long periodSecs) {
        mPeriodSecs = periodSecs;
    }

    private synchronized void setBurst(int burst) {
        mBurst = burst;
    }

    private synchronized void setDurationMins(long durationMins) {
        mDurationMins = durationMins;
    }


    private void handleFocus(EditText editText) {
      /*
        editText.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && editText.getText().toString().isEmpty()) {
                    editText.setText("-1", TextView.BufferType.EDITABLE);
                }
            }
        });
      */
    }

    private void initBurst() {
        mBurst = getResources().getInteger(R.integer.burst_default);
        mBurstText = (EditText) findViewById(R.id.burst);
        mBurstText.addTextChangedListener(new NumericalWatcher(mBurstText, 0, 1000) {
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
        mReplicationText.addTextChangedListener(new NumericalWatcher(mReplicationText, 1, 4096) {
            @Override
            public void onNewValue(int newValue) {
                mReplication = newValue;
            }
        });
        handleFocus(mReplicationText);
    }

    private void initBucket() {
        String defaultValue = getResources().getString(R.string.bucket_default);
        mBucket = TimeUnit.valueOf(defaultValue);
        mBucketSpinner = (Spinner) findViewById(R.id.bucket_spinner);

        ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(
                this, R.layout.spinner_item, TIME_UNIT_LABELS);

        mBucketSpinner.setAdapter(dataAdapter);
        mBucketSpinner.setOnItemSelectedListener(this);

        for (String label : TIME_UNIT_MAP.keySet()) {
            if (defaultValue.equals(TIME_UNIT_MAP.get(label).toString())) {
                mBucketSpinner.setSelection(dataAdapter.getPosition(label));
            }
        }
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
                mPlacebo = mPlaceboCheckBox.isChecked();
                updateControlsEnabled();
            }
        });
    }

    private void initMetricWhitelist() {
        mCountMetricCheckBox = findViewById(R.id.include_count);
        mCountMetricCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mIncludeCountMetric = mCountMetricCheckBox.isChecked();
            }
        });
        mDurationMetricCheckBox = findViewById(R.id.include_duration);
        mDurationMetricCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mIncludeDurationMetric = mDurationMetricCheckBox.isChecked();
            }
        });
        mEventMetricCheckBox = findViewById(R.id.include_event);
        mEventMetricCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mIncludeEventMetric = mEventMetricCheckBox.isChecked();
            }
        });
        mValueMetricCheckBox = findViewById(R.id.include_value);
        mValueMetricCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mIncludeValueMetric = mValueMetricCheckBox.isChecked();
            }
        });
        mGaugeMetricCheckBox = findViewById(R.id.include_gauge);
        mGaugeMetricCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mIncludeGaugeMetric = mGaugeMetricCheckBox.isChecked();
            }
        });

        mIncludeCountMetric = mCountMetricCheckBox.isChecked();
        mIncludeDurationMetric = mDurationMetricCheckBox.isChecked();
        mIncludeEventMetric = mEventMetricCheckBox.isChecked();
        mIncludeValueMetric = mValueMetricCheckBox.isChecked();
        mIncludeGaugeMetric = mGaugeMetricCheckBox.isChecked();
    }
}
