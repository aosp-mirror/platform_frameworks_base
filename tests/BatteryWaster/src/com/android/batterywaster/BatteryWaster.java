/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.batterywaster;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/**
 * So you thought sync used up your battery life.
 */
public class BatteryWaster extends Activity {
    TextView mLog;
    DateFormat mDateFormat;
    IntentFilter mFilter;
    PowerManager.WakeLock mPartialWakeLock;
    SpinThread mThread;

    boolean mWasting, mWaking;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set the layout for this activity.  You can find it
        // in res/layout/hello_activity.xml
        setContentView(R.layout.battery_waster);

        findViewById(R.id.checkbox).setOnClickListener(mClickListener);
        findViewById(R.id.checkbox_wake).setOnClickListener(mWakeClickListener);
        mLog = (TextView)findViewById(R.id.log);

        mDateFormat = DateFormat.getInstance();

        mFilter = new IntentFilter();
        mFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        mFilter.addAction(Intent.ACTION_BATTERY_LOW);
        mFilter.addAction(Intent.ACTION_BATTERY_OKAY);
        mFilter.addAction(Intent.ACTION_POWER_CONNECTED);

        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryWaster");
        mPartialWakeLock.setReferenceCounted(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (((CheckBox)findViewById(R.id.checkbox)).isChecked()) {
            startRunning();
        }
        if (((CheckBox)findViewById(R.id.checkbox_wake)).isChecked()) {
            mWaking = true;
            updateWakeLock();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRunning();
        if (mPartialWakeLock.isHeld()) {
            mPartialWakeLock.release();
        }
    }

    View.OnClickListener mClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            CheckBox checkbox = (CheckBox)v;
            if (checkbox.isChecked()) {
                startRunning();
            } else {
                stopRunning();
            }
        }
    };

    View.OnClickListener mWakeClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            CheckBox checkbox = (CheckBox)v;
            if (checkbox.isChecked()) {
                mWaking = true;
                updateWakeLock();
            } else {
                mWaking = false;
                updateWakeLock();
            }
        }
    };

    void startRunning() {
        if (!mWasting) {
            log("Start");
            registerReceiver(mReceiver, mFilter);
            mWasting = true;
            updateWakeLock();
            if (mThread == null) {
                mThread = new SpinThread();
                mThread.start();
            }
        }
    }

    void stopRunning() {
        if (mWasting) {
            log("Stop");
            unregisterReceiver(mReceiver);
            mWasting = false;
            updateWakeLock();
            if (mThread != null) {
                mThread.quit();
                mThread = null;
            }
        }
    }

    void updateWakeLock() {
        if (mWasting) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (mWaking) {
            if (!mPartialWakeLock.isHeld()) {
                mPartialWakeLock.acquire();
            }
        } else {
            if (mPartialWakeLock.isHeld()) {
                mPartialWakeLock.release();
            }
        }
    }

    void log(String s) {
        mLog.setText(mLog.getText() + "\n" + mDateFormat.format(new Date()) + ": " + s);
    }

    BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String title = action;
            int index = title.lastIndexOf('.');
            if (index >= 0) {
                title = title.substring(index + 1);
            }
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                log(title + ": level=" + level);
            } else {
                log(title);
            }
        }
    };

    class SpinThread extends Thread {
        private boolean mStop;

        public void quit() {
            synchronized (this) {
                mStop = true;
            }
        }

        public void run() {
            while (true) {
                synchronized (this) {
                    if (mStop) {
                        return;
                    }
                }
            }
        }
    }
}


