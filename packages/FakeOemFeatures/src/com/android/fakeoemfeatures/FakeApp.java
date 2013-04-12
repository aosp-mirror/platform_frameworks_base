/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.fakeoemfeatures;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Slog;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;

public class FakeApp extends Application {
    // Stuffing of 20MB
    static final int STUFFING_SIZE_BYTES = 20*1024*1024;
    static final int STUFFING_SIZE_INTS = STUFFING_SIZE_BYTES/4;
    int[] mStuffing;

    // Assume 4k pages.
    static final int PAGE_SIZE = 4*1024;

    static final long TICK_DELAY = 4*60*60*1000; // One hour
    static final int MSG_TICK = 1;
    final Handler mHandler = new Handler() {
        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TICK:
                    // Our service is IMPORTANT.  We know, we wrote it.
                    // We need to keep that thing running.  Because WE KNOW.
                    // Damn you users, STOP MESSING WITH US.
                    startService(new Intent(FakeApp.this, FakeBackgroundService.class));
                    sendEmptyMessageDelayed(MSG_TICK, TICK_DELAY);
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    // Always run another process for more per-process overhead.
    ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override public void onServiceDisconnected(ComponentName name) {
        }
    };
    ServiceConnection mServiceConnection2 = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override public void onServiceDisconnected(ComponentName name) {
        }
    };
    ServiceConnection mServiceConnection3 = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override public void onServiceDisconnected(ComponentName name) {
        }
    };

    @Override
    public void onCreate() {
        String processName = ActivityThread.currentProcessName();
        Slog.i("FakeOEMFeatures", "Creating app in process: " + processName);
        if (!getApplicationInfo().packageName.equals(processName)) {
            // If we are not in the main process of the app, then don't do
            // our extra overhead stuff.
            return;
        }

        final WindowManager wm = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        final Display display = wm.getDefaultDisplay();

        // Check to make sure we are not running on a user build.  If this
        // is a user build, WARN!  Do not want!
        if ("user".equals(android.os.Build.TYPE)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Should not be on user build");
            builder.setMessage("The app Fake OEM Features should not be installed on a "
                    + "user build.  Please remove this .apk before shipping this build to "
                    + " your customers!");
            builder.setCancelable(false);
            builder.setPositiveButton("I understand", null);
            Dialog dialog = builder.create();
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            dialog.show();
        }

        // Make a fake window that is always around eating graphics resources.
        FakeView view = new FakeView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        int maxSize = display.getMaximumSizeDimension();
        maxSize *= 2;
        lp.x = maxSize;
        lp.y = maxSize;
        lp.setTitle(getPackageName());
        wm.addView(view, lp);

        // Bind to a fake service we want to keep running in another process.
        bindService(new Intent(this, FakeCoreService.class), mServiceConnection,
                Context.BIND_AUTO_CREATE);
        bindService(new Intent(this, FakeCoreService2.class), mServiceConnection2,
                Context.BIND_AUTO_CREATE);
        bindService(new Intent(this, FakeCoreService3.class), mServiceConnection3,
                Context.BIND_AUTO_CREATE);

        // Start to a fake service that should run in the background of
        // another process.
        mHandler.sendEmptyMessage(MSG_TICK);

        // Make a fake allocation to consume some RAM.
        mStuffing = new int[STUFFING_SIZE_INTS];
        for (int i=0; i<STUFFING_SIZE_BYTES/PAGE_SIZE; i++) {
            // Fill each page with a unique value.
            final int VAL = i*2 + 100;
            final int OFF = (i*PAGE_SIZE)/4;
            for (int j=0; j<(PAGE_SIZE/4); j++) {
                mStuffing[OFF+j] = VAL;
            }
        }
    }
}
