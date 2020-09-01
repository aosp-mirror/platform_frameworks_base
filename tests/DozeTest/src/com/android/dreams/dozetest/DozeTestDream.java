/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.dreams.dozetest;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.PowerManager;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Display;
import android.widget.TextView;

import java.util.Date;

/**
 * Simple test for doze mode.
 * <p>
 * adb shell setprop debug.doze.component com.android.dreams.dozetest/.DozeTestDream
 * </p>
 */
public class DozeTestDream extends DreamService {
    private static final String TAG = DozeTestDream.class.getSimpleName();
    private static final boolean DEBUG = false;

    // Amount of time to allow to update the time shown on the screen before releasing
    // the wakelock.  This timeout is design to compensate for the fact that we don't
    // currently have a way to know when time display contents have actually been
    // refreshed once the dream has finished rendering a new frame.
    private static final int UPDATE_TIME_TIMEOUT = 100;

    // Not all hardware supports dozing.  We should use Display.STATE_DOZE but
    // for testing purposes it is convenient to use Display.STATE_ON so the
    // test still works on hardware that does not support dozing.
    private static final int DISPLAY_STATE_WHEN_DOZING = Display.STATE_ON;

    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private AlarmManager mAlarmManager;
    private PendingIntent mAlarmIntent;
    private Handler mHandler = new Handler();

    private TextView mAlarmClock;

    private final Date mTime = new Date();
    private java.text.DateFormat mTimeFormat;

    private boolean mDreaming;

    private long mLastTime = Long.MIN_VALUE;

    @Override
    public void onCreate() {
        super.onCreate();

        mPowerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mAlarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent("com.android.dreams.dozetest.ACTION_ALARM");
        intent.setPackage(getPackageName());
        IntentFilter filter = new IntentFilter();
        filter.addAction(intent.getAction());
        registerReceiver(mAlarmReceiver, filter);
        mAlarmIntent = PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        setDozeScreenState(DISPLAY_STATE_WHEN_DOZING);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterReceiver(mAlarmReceiver);
        mAlarmIntent.cancel();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(R.layout.dream);
        setScreenBright(false);

        mAlarmClock = (TextView)findViewById(R.id.alarm_clock);

        mTimeFormat = DateFormat.getTimeFormat(this);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();

        mDreaming = true;

        Log.d(TAG, "Dream started: canDoze=" + canDoze());

        performTimeUpdate();

        startDozing();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();

        mDreaming = false;

        Log.d(TAG, "Dream ended: isDozing=" + isDozing());

        stopDozing();
        cancelTimeUpdate();
    }

    private void performTimeUpdate() {
        if (mDreaming) {
            long now = System.currentTimeMillis();
            now -= now % 60000; // back up to last minute boundary
            if (mLastTime == now) {
                return;
            }

            mLastTime = now;
            mTime.setTime(now);
            mAlarmClock.setText(mTimeFormat.format(mTime));

            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, now + 60000, mAlarmIntent);

            mWakeLock.acquire(UPDATE_TIME_TIMEOUT + 5000 /*for testing brightness*/);

            // flash the screen a bit to test these functions
            setDozeScreenState(DISPLAY_STATE_WHEN_DOZING);
            setDozeScreenBrightness(200);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    setDozeScreenBrightness(50);
                }
            }, 2000);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    setDozeScreenState(Display.STATE_OFF);
                }
            }, 5000);
        }
    }

    private void cancelTimeUpdate() {
        mAlarmManager.cancel(mAlarmIntent);
    }

    private final BroadcastReceiver mAlarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            performTimeUpdate();
        }
    };
}
