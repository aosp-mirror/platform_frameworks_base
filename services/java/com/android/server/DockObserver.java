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

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.UEventObserver;
import android.util.Log;

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

    private int mDockState;
    private boolean mPendingIntent;

    private final Context mContext;

    public DockObserver(Context context) {
        mContext = context;

        startObserving(DOCK_UEVENT_MATCH);

        init();  // set initial status
    }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Dock UEVENT: " + event.toString());
        }

        try {
            update(Integer.parseInt(event.get("SWITCH_STATE")));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Could not parse switch state from event " + event);
        }
    }

    private synchronized final void init() {
        char[] buffer = new char[1024];

        int newState = mDockState;
        try {
            FileReader file = new FileReader(DOCK_STATE_PATH);
            int len = file.read(buffer, 0, 1024);
            newState = Integer.valueOf((new String(buffer, 0, len)).trim());

        } catch (FileNotFoundException e) {
            Log.w(TAG, "This kernel does not have dock station support");
        } catch (Exception e) {
            Log.e(TAG, "" , e);
        }

        update(newState);
    }

    private synchronized final void update(int newState) {
        if (newState != mDockState) {
            mDockState = newState;

            mPendingIntent = true;
            mHandler.sendEmptyMessage(0);
        }
    }

    private synchronized final void sendIntent() {
        // Pack up the values and broadcast them to everyone
        Intent intent = new Intent(Intent.ACTION_DOCK_EVENT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Intent.EXTRA_DOCK_STATE, mDockState);

        // TODO: Should we require a permission?
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Broadcasting dock state " + mDockState);
        }
        ActivityManagerNative.broadcastStickyIntent(intent, null);
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mPendingIntent) {
                sendIntent();
                mPendingIntent = false;
            }
        }
    };
}
