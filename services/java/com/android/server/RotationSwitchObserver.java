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
 *
 */

package com.android.server;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import android.widget.Toast;
import android.view.IWindowManager;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.os.AsyncTask;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

/**
 * <p>RotationLockObserver monitors for rotation lock switch state
 */
class RotationSwitchObserver extends UEventObserver {
    private static final String TAG = RotationSwitchObserver.class
        .getSimpleName();
    private static final boolean LOG = true;

    private static final String LOCK_UEVENT_MATCH =
        "DEVPATH=/devices/virtual/switch/rotationlock";
    private static final String LOCK_STATE_PATH =
        "/sys/class/switch/rotationlock/state";

    private static final int MSG_LOCK_STATE = 0;

    private int mLockState;
    private int mPreviousLockState;

    private boolean mSystemReady;

    private final Context mContext;

    private boolean mAutoRotation;

    public RotationSwitchObserver(Context context) {
        mContext = context;
        init();  // set initial status

        startObserving(LOCK_UEVENT_MATCH);
    }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "Switch UEVENT: " + event.toString());
        }

        synchronized (this) {
            try {
                int newState = Integer.parseInt(event.get("SWITCH_STATE"));
                if (newState != mLockState) {
                    mPreviousLockState = mLockState;
                    mLockState = newState;
                    if (mSystemReady) {
                        update();
                    }
                }
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Could not parse switch state from event "
                        + event);
            }
        }
    }

    private final void init() {
        char[] buffer = new char[1024];

        try {
            FileReader file = new FileReader(LOCK_STATE_PATH);
            int len = file.read(buffer, 0, 1024);
            file.close();
            mPreviousLockState = mLockState =
                Integer.valueOf((new String(buffer, 0, len)).trim());
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "This kernel does not have rotation switch support");
        } catch (NumberFormatException e) {
            Slog.e(TAG, "" , e);
        } catch (IOException e) {
            Slog.e(TAG, "" , e);
        }
    }

    void systemReady() {
        synchronized (this) {
            mSystemReady = true;
        }
    }

    private final void update() {
        mHandler.sendEmptyMessage(MSG_LOCK_STATE);
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOCK_STATE:
                    synchronized (this) {
                        boolean autoRotate = mLockState == 0;
                        int toastId = autoRotate
                            ? com.android.internal.R.string.toast_rotation_unlocked
                            : com.android.internal.R.string.toast_rotation_locked;

                        setAutoRotation(autoRotate);

                        Toast.makeText(mContext, mContext.getString(toastId),
                            Toast.LENGTH_SHORT).show();
                    break;
                }
            }
        }
    };

    private void setAutoRotation(final boolean autorotate) {
        mAutoRotation = autorotate;
        AsyncTask.execute(new Runnable() {
                public void run() {
                    try {
                        IWindowManager wm = IWindowManager.Stub.asInterface(
                                ServiceManager
                                        .getService(Context.WINDOW_SERVICE));
                        if (autorotate) {
                            wm.thawRotation();
                        } else {
                            wm.freezeRotation(-1);
                        }
                    } catch (RemoteException exc) {
                        Log.w(TAG, "Unable to save auto-rotate setting");
                    }
                }
            });
    }
}
