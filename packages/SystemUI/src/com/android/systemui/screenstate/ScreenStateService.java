/*
 * Copyright (C) 2007 The Android Open Source Project
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
package com.android.systemui.screenstate;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.database.ContentObserver;
import android.content.ContentResolver;
import android.os.Handler;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

public class ScreenStateService extends Service  {

    private static final String TAG = "ScreenStateService";
    private BroadcastReceiver mPowerKeyReceiver;
    private TwoGToggle mTwoGToggle;
    private GpsToggle mGpsToggle;
    private MobileDataToggle mMobileDataToggle;
    private boolean mEnabled = true;
    private Context mContext;
    private List<ScreenStateToggle> fEnabledToggles;
    private List<ScreenStateToggle> fAllToggles;

    private Handler scrOnHandler;
    private Handler scrOffHandler;
    private boolean offScheduled = true;
    private boolean onScheduled = false;

    private Runnable scrOffTask = new Runnable() {
        public void run() {
            Log.v(TAG,"scrOffTask");
                Iterator<ScreenStateToggle> nextToggle = fEnabledToggles.iterator();
                while(nextToggle.hasNext()){
                    ScreenStateToggle toggle = nextToggle.next();
                    toggle.doScreenOff();
                }
            offScheduled = false;
            }
    };

    private Runnable scrOnTask = new Runnable() {
        public void run() {
            Log.v(TAG,"scrOnTask");
                Iterator<ScreenStateToggle> nextToggle = fEnabledToggles.iterator();
                while(nextToggle.hasNext()){
                    ScreenStateToggle toggle = nextToggle.next();
                    toggle.doScreenOn();
                }
            onScheduled = false;
            }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        if (mEnabled){
            unregisterReceiver();
        }
    }

    @Override
    public void onStart(Intent intent, int startid)
    {
        Log.d(TAG, "onStart");
        mContext = getApplicationContext();

        // firewall
        int s = Settings.System.getInt(mContext.getContentResolver(), Settings.System.START_SCREEN_STATE_SERVICE, 0);
        if(s!=0)
            mEnabled = true;
        else
            mEnabled = false;

        if (mEnabled){
            registerBroadcastReceiver();
        }

        scrOnHandler = new Handler();
        scrOffHandler = new Handler();

        fAllToggles = new ArrayList<ScreenStateToggle>();
        mTwoGToggle = new TwoGToggle(mContext);
        fAllToggles.add(mTwoGToggle);
        mGpsToggle = new GpsToggle(mContext);
        fAllToggles.add(mGpsToggle);
        mMobileDataToggle = new MobileDataToggle(mContext);
        fAllToggles.add(mMobileDataToggle);

        updateEnabledToggles();
    }

    private void registerBroadcastReceiver() {
        final IntentFilter theFilter = new IntentFilter();
        /** System Defined Broadcast */
        theFilter.addAction(Intent.ACTION_SCREEN_ON);
        theFilter.addAction(Intent.ACTION_SCREEN_OFF);
        theFilter.addAction("android.intent.action.SCREEN_STATE_SERVICE_UPDATE");

        mPowerKeyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String strAction = intent.getAction();

                if (strAction.equals(Intent.ACTION_SCREEN_OFF)){
                    Log.d(TAG, "screen off");
                    if(onScheduled){
                        scrOnHandler.removeCallbacks(scrOnTask);
                    } else {
                        int sec = Settings.System.getInt(mContext.getContentResolver(), Settings.System.SCREEN_STATE_OFF_DELAY, 0);
                        Log.d(TAG, "screen off delay: " + sec);
                        scrOffHandler.postDelayed(scrOffTask, sec * 1000);
                        offScheduled = true;
                    }
                }
                if (strAction.equals(Intent.ACTION_SCREEN_ON)) {
                    Log.d(TAG, "scren on");
                    if(offScheduled){
                        scrOffHandler.removeCallbacks(scrOffTask);
                    } else {
                        int sec = Settings.System.getInt(mContext.getContentResolver(), Settings.System.SCREEN_STATE_ON_DELAY, 0);
                        Log.d(TAG, "screen on delay: " + sec);
                        scrOnHandler.postDelayed(scrOnTask, sec * 1000);
                        onScheduled = true;
                    }
                }
                if (strAction.equals("android.intent.action.SCREEN_STATE_SERVICE_UPDATE")){
                    Log.d(TAG, "update enabled toggles");
                    updateEnabledToggles();
                }
            }
        };

        Log.d(TAG, "registerBroadcastReceiver");
        mContext.registerReceiver(mPowerKeyReceiver, theFilter);
    }

    private void unregisterReceiver() {
        try {
            Log.d(TAG, "unregisterReceiver");
            mContext.unregisterReceiver(mPowerKeyReceiver);
        }
        catch (IllegalArgumentException e) {
            mPowerKeyReceiver = null;
        }
    }

    private void updateEnabledToggles() {
        fEnabledToggles = new ArrayList<ScreenStateToggle>();
        Iterator<ScreenStateToggle> nextToggle = fAllToggles.iterator();
        while(nextToggle.hasNext()){
            ScreenStateToggle toggle = nextToggle.next();
            if (toggle.isEnabled()){
                Log.d(TAG, "active toggle "+ toggle.getClass().getName());
                fEnabledToggles.add(toggle);
            }
        }
    }
}
