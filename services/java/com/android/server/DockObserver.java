/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.widget.LockPatternUtils;

import java.io.FileReader;
import java.io.FileNotFoundException;

/**
 * <p>DockObserver monitors for a docking station.
 */
class DockObserver extends UEventObserver {
    private static final String TAG = DockObserver.class.getSimpleName();
    private static final boolean LOG = false;

    private static final String DOCK_UEVENT_MATCH = "DEVPATH=/devices/virtual/switch/dock";
    private static final String DOCK_STATE_PATH = "/sys/class/switch/dock/state";

    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private boolean mSystemReady;

    private final Context mContext;

    private PowerManagerService mPowerManager;

    private KeyguardManager.KeyguardLock mKeyguardLock;
    private boolean mKeyguardDisabled;
    private LockPatternUtils mLockPatternUtils;

    // The broadcast receiver which receives the result of the ordered broadcast sent when
    // the dock state changes. The original ordered broadcast is sent with an initial result
    // code of RESULT_OK. If any of the registered broadcast receivers changes this value, e.g.,
    // to RESULT_CANCELED, then the intent to start a dock app will not be sent.
    private final BroadcastReceiver mResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getResultCode() != Activity.RESULT_OK) {
                return;
            }
            
            // Launch a dock activity
            String category;
            switch (mDockState) {
                case Intent.EXTRA_DOCK_STATE_CAR:
                    category = Intent.CATEGORY_CAR_DOCK;
                    break;
                case Intent.EXTRA_DOCK_STATE_DESK:
                    category = Intent.CATEGORY_DESK_DOCK;
                    break;
                default:
                    category = null;
                    break;
            }
            if (category != null) {
                intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(category);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    mContext.startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Log.w(TAG, e.getCause());
                }
            }
        }
    };

    public DockObserver(Context context, PowerManagerService pm) {
        mContext = context;
        mPowerManager = pm;
        mLockPatternUtils = new LockPatternUtils(context.getContentResolver());
        init();  // set initial status
        startObserving(DOCK_UEVENT_MATCH);
    }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Dock UEVENT: " + event.toString());
        }

        synchronized (this) {
            try {
                int newState = Integer.parseInt(event.get("SWITCH_STATE"));
                if (newState != mDockState) {
                    mDockState = newState;
                    if (mSystemReady) {
                        update();
                    }
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Could not parse switch state from event " + event);
            }
        }
    }

    private final void init() {
        char[] buffer = new char[1024];

        try {
            FileReader file = new FileReader(DOCK_STATE_PATH);
            int len = file.read(buffer, 0, 1024);
            mDockState = Integer.valueOf((new String(buffer, 0, len)).trim());

        } catch (FileNotFoundException e) {
            Log.w(TAG, "This kernel does not have dock station support");
        } catch (Exception e) {
            Log.e(TAG, "" , e);
        }
    }

    void systemReady() {
        synchronized (this) {
            KeyguardManager keyguardManager =
                    (KeyguardManager)mContext.getSystemService(Context.KEYGUARD_SERVICE);
            mKeyguardLock = keyguardManager.newKeyguardLock(TAG);

            // don't bother broadcasting undocked here
            if (mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                update();
            }
            mSystemReady = true;
        }
    }

    private final void update() {
        mHandler.sendEmptyMessage(0);
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            synchronized (this) {
                Log.i(TAG, "Dock state changed: " + mDockState);
                if (Settings.Secure.getInt(mContext.getContentResolver(),
                        Settings.Secure.DEVICE_PROVISIONED, 0) == 0) {
                    Log.i(TAG, "Device not provisioned, skipping dock broadcast");
                    return;
                }
                // Pack up the values and broadcast them to everyone
                mPowerManager.userActivityWithForce(SystemClock.uptimeMillis(), false, true);
                Intent intent = new Intent(Intent.ACTION_DOCK_EVENT);
                intent.putExtra(Intent.EXTRA_DOCK_STATE, mDockState);
                
                // Send the ordered broadcast; the result receiver will receive after all
                // broadcasts have been sent. If any broadcast receiver changes the result
                // code from the initial value of RESULT_OK, then the result receiver will
                // not launch the corresponding dock application. This gives apps a chance
                // to override the behavior and stay in their app even when the device is
                // placed into a dock.
                mContext.sendStickyOrderedBroadcast(
                        intent, mResultReceiver, null, Activity.RESULT_OK, null, null);
            }
        }
    };
}
