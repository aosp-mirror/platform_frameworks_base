/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.servicestests.apps.displaymanagertestapp;

import android.app.Activity;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

/**
 * A simple activity manipulating displays and listening to corresponding display events
 */
public class DisplayEventActivity extends Activity {
    private static final String TAG = DisplayEventActivity.class.getSimpleName();

    private static final String TEST_DISPLAYS = "DISPLAYS";
    private static final String TEST_MESSENGER = "MESSENGER";

    private static final int MESSAGE_LAUNCHED = 1;
    private static final int MESSAGE_CALLBACK = 2;

    private static final int DISPLAY_ADDED = 1;
    private static final int DISPLAY_CHANGED = 2;
    private static final int DISPLAY_REMOVED = 3;

    private int mExpectedDisplayCount;
    private int mSeenDisplayCount;
    private Messenger mMessenger;
    private DisplayManager mDisplayManager;
    private DisplayManager.DisplayListener mDisplayListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        mExpectedDisplayCount = 0;
        mSeenDisplayCount = intent.getIntExtra(TEST_DISPLAYS, 0);
        mMessenger = intent.getParcelableExtra(TEST_MESSENGER, Messenger.class);
        mDisplayManager = getApplicationContext().getSystemService(DisplayManager.class);
        mDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                callback(displayId, DISPLAY_ADDED);
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                callback(displayId, DISPLAY_REMOVED);
            }

            @Override
            public void onDisplayChanged(int displayId) {
                callback(displayId, DISPLAY_CHANGED);
            }
        };
        Handler handler = new Handler(Looper.getMainLooper());
        mDisplayManager.registerDisplayListener(mDisplayListener, handler);
        launched();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
    }

    private void launched() {
        try {
            Message msg = Message.obtain();
            msg.what = MESSAGE_LAUNCHED;
            msg.arg1 = Process.myPid();
            msg.arg2 = Process.myUid();
            Log.d(TAG, "Launched " + mSeenDisplayCount);
            mMessenger.send(msg);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    private void callback(int displayId, int event) {
        try {
            Message msg = Message.obtain();
            msg.what = MESSAGE_CALLBACK;
            msg.arg1 = displayId;
            msg.arg2 = event;
            Log.d(TAG, "Msg " + msg.arg1 + " " + msg.arg2);
            mMessenger.send(msg);
            if (event == DISPLAY_REMOVED) {
                mExpectedDisplayCount++;
                if (mExpectedDisplayCount >= mSeenDisplayCount) {
                    finish();
                }
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }
}
