/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.UEventObserver;
import android.os.UserHandle;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * ThermalObserver for monitoring temperature changes.
 */
public class ThermalObserver extends SystemService {
    private static final String TAG = "ThermalObserver";

    private static final String CALLSTATE_UEVENT_MATCH =
            "DEVPATH=/devices/virtual/switch/thermalstate";

    private static final int MSG_THERMAL_STATE_CHANGED = 0;

    private static final int SWITCH_STATE_NORMAL = 0;
    private static final int SWITCH_STATE_WARNING = 1;
    private static final int SWITCH_STATE_EXCEEDED = 2;

    private final PowerManager mPowerManager;
    private final PowerManager.WakeLock mWakeLock;

    private final Object mLock = new Object();
    private Integer mLastState;

    private final UEventObserver mThermalWarningObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            updateLocked(Integer.parseInt(event.get("SWITCH_STATE")));
        }
    };

    private final Handler mHandler = new Handler(true /*async*/) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_THERMAL_STATE_CHANGED:
                    handleThermalStateChange(msg.arg1);
                    mWakeLock.release();
                    break;
            }
        }
    };

    public ThermalObserver(Context context) {
        super(context);
        mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mThermalWarningObserver.startObserving(CALLSTATE_UEVENT_MATCH);
    }

    private void updateLocked(int state) {
        Message message = new Message();
        message.what = MSG_THERMAL_STATE_CHANGED;
        message.arg1 = state;

        mWakeLock.acquire();
        mHandler.sendMessage(message);
    }

    private void handleThermalStateChange(int state) {
        synchronized (mLock) {
            mLastState = state;
            Intent intent = new Intent(Intent.ACTION_THERMAL_EVENT);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);

            final int thermalState;

            switch (state) {
                case SWITCH_STATE_WARNING:
                    thermalState = Intent.EXTRA_THERMAL_STATE_WARNING;
                    break;
                case SWITCH_STATE_EXCEEDED:
                    thermalState = Intent.EXTRA_THERMAL_STATE_EXCEEDED;
                    break;
                case SWITCH_STATE_NORMAL:
                default:
                    thermalState = Intent.EXTRA_THERMAL_STATE_NORMAL;
                    break;
            }

            intent.putExtra(Intent.EXTRA_THERMAL_STATE, thermalState);

            getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    @Override
    public void onStart() {
        publishBinderService(TAG, new BinderService());
    }

    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump thermal observer service from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (args == null || args.length == 0 || "-a".equals(args[0])) {
                        pw.println("Current Thermal Observer Service state:");
                        pw.println("  last state change: "
                                + (mLastState != null ? mLastState : "none"));
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
}
