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

package com.example.android.demomodecontroller;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Toast;

public class DemoModeController extends Activity implements OnTouchListener {
    private static final String TAG = DemoModeController.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final Context mContext = this;
    private final Handler mHandler = new Handler();
    private final PointF mLastDown = new PointF();

    private View mContent;
    private Handler mBackground;
    private int mTouchSlop;
    private long mLastDownTime;
    private boolean mControllingColor;
    private Toast mToast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS  // so WM gives us enough room
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        getActionBar().hide();
        mContent = new View(mContext);
        mContent.setBackgroundColor(0xff33b5e5);
        mContent.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mContent.setOnTouchListener(this);
        setContentView(mContent);
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();

        final HandlerThread background = new HandlerThread("background");
        background.start();
        mBackground = new Handler(background.getLooper());
        updateMode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        exitDemoMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        exitDemoMode();
        mToast = Toast.makeText(mContext, R.string.help_text, Toast.LENGTH_LONG);
        mToast.show();
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (mToast != null) {
            mToast.cancel();
            mToast = null;
        }
        final int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            if (DEBUG) Log.d(TAG, "down");
            mHandler.postDelayed(mLongPressCheck, 500);
            final long now = SystemClock.uptimeMillis();
            if (now - mLastDownTime < 200) {
                toggleMode();
            }
            mLastDownTime = now;
            mLastDown.x = event.getX();
            mLastDown.y = event.getY();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            if (DEBUG) Log.d(TAG, "upOrCancel");
            mControllingColor = false;
            mHandler.removeCallbacks(mLongPressCheck);
        }
        if (action != MotionEvent.ACTION_MOVE) return false;

        float x = event.getX();
        float y = event.getY();
        if (Math.abs(mLastDown.x - x) > mTouchSlop || Math.abs(mLastDown.y - y) > mTouchSlop) {
            mHandler.removeCallbacks(mLongPressCheck);
        }
        x = Math.max(x, 0);
        y = Math.max(y, 0);
        final int h = mContent.getMeasuredHeight();
        final int w = mContent.getMeasuredWidth();
        x = Math.min(x, w);
        y = Math.min(y, h);

        y = h - y;
        x = w - x;

        if (mControllingColor) {
            final float hue = y / (h / 360);
            final float sat = 1 - (x / (float)w);
            final float val = x / (float)w;
            final int color = Color.HSVToColor(new float[]{hue, sat, val});
            if (DEBUG) Log.d(TAG, String.format("hsv=(%s,%s,%s) argb=#%08x", hue, sat, val, color));
            mContent.setBackgroundColor(color);
            return true;
        }

        final int hh = (int)x / (w / 12);
        if (hh != mHH) {
            mHH = hh;
            mBackground.removeCallbacks(mUpdateClock);
            mBackground.post(mUpdateClock);
        }

        final int mm = (int)y / (h / 60);
        if (mm != mMM) {
            mMM = mm;
            mBackground.removeCallbacks(mUpdateClock);
            mBackground.post(mUpdateClock);
        }

        final int batteryLevel = (int)y / (h / 101);
        if (batteryLevel != mBatteryLevel) {
            mBatteryLevel = batteryLevel;
            mBackground.removeCallbacks(mUpdateBattery);
            mBackground.post(mUpdateBattery);
        }

        final boolean batteryPlugged = x >= w / 2;
        if (batteryPlugged != mBatteryPlugged) {
            mBatteryPlugged = batteryPlugged;
            mBackground.removeCallbacks(mUpdateBattery);
            mBackground.post(mUpdateBattery);
        }

        final int mobileLevel = (int)y / (h / 10);
        if (mobileLevel != mMobileLevel) {
            mMobileLevel = mobileLevel;
            mBackground.removeCallbacks(mUpdateMobile);
            mBackground.post(mUpdateMobile);
        }

        final int wifiLevel = (int)y / (h / 10);
        if (wifiLevel != mWifiLevel) {
            mWifiLevel = wifiLevel;
            mBackground.removeCallbacks(mUpdateWifi);
            mBackground.post(mUpdateWifi);
        }

        final int statusSlots = (int)x / (w / 13);
        if (statusSlots != mStatusSlots) {
            mStatusSlots = statusSlots;
            mBackground.removeCallbacks(mUpdateStatus);
            mBackground.post(mUpdateStatus);
        }

        final int networkIcons = (int)x / (w / 4);
        if (networkIcons != mNetworkIcons) {
            mNetworkIcons = networkIcons;
            mBackground.removeCallbacks(mUpdateNetwork);
            mBackground.post(mUpdateNetwork);
        }

        final int mobileDataType = (int)y / (h / 9);
        if (mobileDataType != mMobileDataType) {
            mMobileDataType = mobileDataType;
            mBackground.removeCallbacks(mUpdateMobile);
            mBackground.post(mUpdateMobile);
        }
        return true;
    }

    private void toggleMode() {
        if (DEBUG) Log.d(TAG, "toggleMode");
        mBarMode = (mBarMode + 1) % 3;
        updateMode();
    }

    private void updateMode() {
        mBackground.removeCallbacks(mUpdateBarMode);
        mBackground.post(mUpdateBarMode);
    }

    private final Runnable mLongPressCheck = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "mControllingColor = true");
            mControllingColor = true;

        }
    };

    private void exitDemoMode() {
        if (DEBUG) Log.d(TAG, "exitDemoMode");
        final Intent intent = new Intent("com.android.systemui.demo");
        intent.putExtra("command", "exit");
        mContext.sendBroadcast(intent);
    }

    private int mStatusSlots; // 0 - 12
    private final Runnable mUpdateStatus = new Runnable() {
        @Override
        public void run() {
            final Intent intent = new Intent("com.android.systemui.demo");
            intent.putExtra("command", "status");
            intent.putExtra("volume", mStatusSlots < 1 ? "hide"
                    : mStatusSlots < 2 ? "silent" : "vibrate");
            intent.putExtra("bluetooth", mStatusSlots < 3 ? "hide"
                    : mStatusSlots < 4 ? "disconnected" : "connected");
            intent.putExtra("location", mStatusSlots < 5 ? "hide" : "show");
            intent.putExtra("alarm", mStatusSlots < 6 ? "hide" : "show");
            intent.putExtra("sync", mStatusSlots < 7 ? "hide" : "show");
            intent.putExtra("tty", mStatusSlots < 8 ? "hide" : "show");
            intent.putExtra("eri", mStatusSlots < 9 ? "hide" : "show");
            intent.putExtra("secure", mStatusSlots < 10 ? "hide" : "show");
            intent.putExtra("mute", mStatusSlots < 11 ? "hide" : "show");
            intent.putExtra("speakerphone", mStatusSlots < 12 ? "hide" : "show");
            mContext.sendBroadcast(intent);
        }
    };

    private int mNetworkIcons;  // 0:airplane  1:mobile  2:airplane+wifi  3:mobile+wifi
    private final Runnable mUpdateNetwork = new Runnable() {
        @Override
        public void run() {
            final Intent intent = new Intent("com.android.systemui.demo");
            intent.putExtra("command", "network");
            intent.putExtra("airplane", mNetworkIcons % 2 == 0 ? "show" : "hide");
            intent.putExtra("wifi", mNetworkIcons >= 2 ? "show" : "hide");
            intent.putExtra("mobile", mNetworkIcons % 2 == 1 ? "show" : "hide");
            mContext.sendBroadcast(intent);
        }
    };

    private int mWifiLevel; // 0 - 4, 5 - 9, fully
    private final Runnable mUpdateWifi = new Runnable() {
        @Override
        public void run() {
            final Intent intent = new Intent("com.android.systemui.demo");
            intent.putExtra("command", "network");
            intent.putExtra("wifi", mNetworkIcons >= 2 ? "show" : "hide");
            intent.putExtra("level", Integer.toString(mWifiLevel % 5));
            intent.putExtra("fully", Boolean.toString(mWifiLevel > 4));
            mContext.sendBroadcast(intent);
        }
    };

    private int mMobileLevel; // 0 - 4, 5 - 9, fully
    private int mMobileDataType; // 0 - 8
    private static final String getDataType(int dataType) {
        if (dataType == 1) return "1x";
        if (dataType == 2) return "3g";
        if (dataType == 3) return "4g";
        if (dataType == 4) return "e";
        if (dataType == 5) return "g";
        if (dataType == 6) return "h";
        if (dataType == 7) return "lte";
        if (dataType == 8) return "roam";
        return "";
    }
    private final Runnable mUpdateMobile = new Runnable() {
        @Override
        public void run() {
            final Intent intent = new Intent("com.android.systemui.demo");
            intent.putExtra("command", "network");
            intent.putExtra("mobile", mNetworkIcons % 2 == 1 ? "show" : "hide");
            intent.putExtra("level", Integer.toString(mMobileLevel % 5));
            intent.putExtra("fully", Boolean.toString(mMobileLevel > 4));
            intent.putExtra("datatype", getDataType(mMobileDataType));
            mContext.sendBroadcast(intent);
        }
    };

    private boolean mBatteryPlugged;
    private int mBatteryLevel; // 0 - 100
    private final Runnable mUpdateBattery = new Runnable() {
        @Override
        public void run() {
            final Intent intent = new Intent("com.android.systemui.demo");
            intent.putExtra("command", "battery");
            intent.putExtra("level", Integer.toString(mBatteryLevel));
            intent.putExtra("plugged", Boolean.toString(mBatteryPlugged));
            mContext.sendBroadcast(intent);
        }
    };

    private int mHH; // 0 - 11
    private int mMM; // 0 - 59
    private final Runnable mUpdateClock = new Runnable() {
        @Override
        public void run() {
            final Intent intent = new Intent("com.android.systemui.demo");
            intent.putExtra("command", "clock");
            intent.putExtra("hhmm", String.format("%02d%02d", mHH + 1, mMM));
            mContext.sendBroadcast(intent);
        }
    };

    private int mBarMode; // 0 - 2  (opaque, semi-transparent, translucent)
    private final Runnable mUpdateBarMode = new Runnable() {
        @Override
        public void run() {
            final Intent intent = new Intent("com.android.systemui.demo");
            intent.putExtra("command", "bars");
            intent.putExtra("mode", mBarMode == 1 ? "semi-transparent"
                    : mBarMode == 2 ? "translucent" : "opaque");
            mContext.sendBroadcast(intent);
        }
    };
}
