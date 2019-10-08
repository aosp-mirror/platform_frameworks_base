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
package com.android.statsd.dogfood;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.IntentService;
import android.app.StatsManager;
import android.app.StatsManager.StatsUnavailableException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.util.StatsLog;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.os.IStatsManager;
import android.os.ServiceManager;

import java.io.InputStream;

import static com.android.statsd.dogfood.DisplayProtoUtils.displayLogReport;

public class MainActivity extends Activity {
    private final static String TAG = "StatsdDogfood";
    private final static long CONFIG_ID = 987654321;

    final int[] mUids = {11111111, 2222222};
    StatsManager mStatsManager;
    TextView mReportText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        findViewById(R.id.app_a_wake_lock_acquire1).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onWakeLockAcquire(0, "wl_1");
                    }
                });

        findViewById(R.id.app_b_wake_lock_acquire1).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onWakeLockAcquire(1, "wl_1");
                    }
                });

        findViewById(R.id.app_a_wake_lock_acquire2).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onWakeLockAcquire(0, "wl_2");
                    }
                });

        findViewById(R.id.app_b_wake_lock_acquire2).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onWakeLockAcquire(1, "wl_2");
                    }
                });

        findViewById(R.id.app_a_wake_lock_release1).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onWakeLockRelease(0, "wl_1");
                    }
                });


        findViewById(R.id.app_b_wake_lock_release1).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onWakeLockRelease(1, "wl_1");
                    }
                });

        findViewById(R.id.app_a_wake_lock_release2).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onWakeLockRelease(0, "wl_2");
                    }
                });


        findViewById(R.id.app_b_wake_lock_release2).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onWakeLockRelease(1, "wl_2");
                    }
                });


        findViewById(R.id.plug).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                StatsLog.write(StatsLog.PLUGGED_STATE_CHANGED,
                        StatsLog.PLUGGED_STATE_CHANGED__STATE__BATTERY_PLUGGED_AC);
            }
        });

        findViewById(R.id.unplug).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                StatsLog.write(StatsLog.PLUGGED_STATE_CHANGED,
                        StatsLog.PLUGGED_STATE_CHANGED__STATE__BATTERY_PLUGGED_NONE);
            }
        });

        findViewById(R.id.screen_on).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                StatsLog.write(StatsLog.SCREEN_STATE_CHANGED,
                        StatsLog.SCREEN_STATE_CHANGED__STATE__DISPLAY_STATE_ON);
            }
        });

        findViewById(R.id.screen_off).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                StatsLog.write(StatsLog.SCREEN_STATE_CHANGED,
                        StatsLog.SCREEN_STATE_CHANGED__STATE__DISPLAY_STATE_OFF);
            }
        });

        findViewById(R.id.custom_start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                StatsLog.logStart(8);
            }
        });

        findViewById(R.id.custom_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                StatsLog.logStop(8);
            }
        });

        mReportText = (TextView) findViewById(R.id.report_text);

        findViewById(R.id.dump).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!statsdRunning()) {
                    return;
                }
                if (mStatsManager != null) {
                    try {
                        byte[] data = mStatsManager.getReports(CONFIG_ID);
                        if (data != null) {
                            displayData(data);
                            return;
                        }
                    } catch (StatsUnavailableException e) {
                        Log.e(TAG, "Failed to get data from statsd", e);
                    }
                    mReportText.setText("Failed!");
                }
            }
        });

        findViewById(R.id.push_config).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    if (!statsdRunning()) {
                        return;
                    }
                    Resources res = getResources();
                    InputStream inputStream = res.openRawResource(R.raw.statsd_baseline_config);

                    byte[] config = new byte[inputStream.available()];
                    inputStream.read(config);
                    if (mStatsManager != null) {
                        try {
                            mStatsManager.addConfig(CONFIG_ID, config);
                            Toast.makeText(
                                    MainActivity.this, "Config pushed", Toast.LENGTH_LONG).show();
                        } catch (StatsUnavailableException | IllegalArgumentException e) {
                            Toast.makeText(MainActivity.this, "Config push FAILED!",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "failed to read config", Toast.LENGTH_LONG);
                }
            }
        });

        PendingIntent pi = PendingIntent.getService(this, 0,
                new Intent(this, ReceiverIntentService.class), PendingIntent.FLAG_UPDATE_CURRENT);
        findViewById(R.id.set_receiver).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    if (!statsdRunning()) {
                        return;
                    }
                    if (mStatsManager != null) {
                        try {
                            mStatsManager.setFetchReportsOperation(pi, CONFIG_ID);
                            Toast.makeText(MainActivity.this,
                                    "Receiver specified to pending intent", Toast.LENGTH_LONG)
                                    .show();
                        } catch (StatsUnavailableException e) {
                            Toast.makeText(MainActivity.this, "Statsd did not set receiver",
                                    Toast.LENGTH_LONG)
                                    .show();
                        }
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "failed to set receiver", Toast.LENGTH_LONG);
                }
            }
        });
        findViewById(R.id.remove_receiver).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    if (!statsdRunning()) {
                        return;
                    }
                    if (mStatsManager != null) {
                        try {
                            mStatsManager.setFetchReportsOperation(null, CONFIG_ID);
                            Toast.makeText(MainActivity.this, "Receiver remove", Toast.LENGTH_LONG)
                                    .show();
                        } catch (StatsUnavailableException e) {
                            Toast.makeText(MainActivity.this, "Statsd did not remove receiver",
                                    Toast.LENGTH_LONG)
                                    .show();
                        }
                    }
                } catch (Exception e) {
                    Toast.makeText(
                            MainActivity.this, "failed to remove receiver", Toast.LENGTH_LONG);
                }
            }
        });
        mStatsManager = (StatsManager) getSystemService("stats");
    }

    private boolean statsdRunning() {
        if (IStatsManager.Stub.asInterface(ServiceManager.getService("stats")) == null) {
            Log.d(TAG, "Statsd not running");
            Toast.makeText(MainActivity.this, "Statsd NOT running!", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    @Override
    public void onNewIntent(Intent intent) {
        Log.d(TAG, "new intent: " + intent.getIntExtra("pkg", 0));
        int pkg = intent.getIntExtra("pkg", 0);
        String name = intent.getStringExtra("name");
        if (intent.hasExtra("acquire")) {
            onWakeLockAcquire(pkg, name);
        } else if (intent.hasExtra("release")) {
            onWakeLockRelease(pkg, name);
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
            displayLogReport(sb, reports);
            mReportText.setText(sb.toString());
        }
    }


    private void onWakeLockAcquire(int id, String name) {
        if (id > 1) {
            Log.d(TAG, "invalid pkg id");
            return;
        }
        int[] uids = new int[]{mUids[id]};
        String[] tags = new String[]{"acquire"};
        StatsLog.write(StatsLog.WAKELOCK_STATE_CHANGED, uids, tags,
                StatsLog.WAKELOCK_STATE_CHANGED__TYPE__PARTIAL_WAKE_LOCK, name,
                StatsLog.WAKELOCK_STATE_CHANGED__STATE__ACQUIRE);
        StringBuilder sb = new StringBuilder();
        sb.append("StagsLog.write(10, ").append(mUids[id]).append(", ").append(0)
                .append(", ").append(name).append(", 1);");
        Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
    }

    private void onWakeLockRelease(int id, String name) {
        if (id > 1) {
            Log.d(TAG, "invalid pkg id");
            return;
        }
        int[] uids = new int[]{mUids[id]};
        String[] tags = new String[]{"release"};
        StatsLog.write(StatsLog.WAKELOCK_STATE_CHANGED, uids, tags,
                StatsLog.WAKELOCK_STATE_CHANGED__TYPE__PARTIAL_WAKE_LOCK, name,
                StatsLog.WAKELOCK_STATE_CHANGED__STATE__RELEASE);
        StringBuilder sb = new StringBuilder();
        sb.append("StagsLog.write(10, ").append(mUids[id]).append(", ").append(0)
                .append(", ").append(name).append(", 0);");
        Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
    }

    public static class ReceiverIntentService extends IntentService {
        public ReceiverIntentService() {
            super("ReceiverIntentService");
        }

        /**
         * The IntentService calls this method from the default worker thread with
         * the intent that started the service. When this method returns, IntentService
         * stops the service, as appropriate.
         */
        @Override
        protected void onHandleIntent(Intent intent) {
            Log.i(TAG, "Received notification that we should call getData");
        }
    }
}
