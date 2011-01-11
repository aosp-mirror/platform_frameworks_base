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

package com.android.statusbartest;

import android.app.ListActivity;
import android.app.Notification;
import android.app.NotificationManager;
import android.widget.ArrayAdapter;
import android.view.View;
import android.os.Binder;
import android.os.IBinder;
import android.os.IPowerManager;
import android.widget.ListView;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.os.RemoteException;
import android.os.Vibrator;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocalPowerManager;
import android.os.ServiceManager;
import android.util.Log;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.RemoteViews;
import android.widget.Toast;
import android.os.PowerManager;

public class PowerTest extends TestActivity
{
    private final static String TAG = "PowerTest";
    IPowerManager mPowerManager;
    int mPokeState = 0;
    IBinder mPokeToken = new Binder();
    Handler mHandler = new Handler();
    PowerManager mPm;
    PowerManager.WakeLock mProx;

    @Override
    protected String tag() {
        return TAG;
    }

    @Override
    protected Test[] tests() {
        mPowerManager = IPowerManager.Stub.asInterface(ServiceManager.getService("power"));
        mPm = (PowerManager)getSystemService("power");
        mProx = mPm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "PowerTest-prox");
        
        return mTests;
    }
    private Test[] mTests = new Test[] {
        new Test("Enable settings widget") {
            public void run() {
                PackageManager pm = getPackageManager();
                pm.setComponentEnabledSetting(new ComponentName("com.android.settings",
                            "com.android.settings.widget.SettingsAppWidgetProvider"),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);

            }
        },
        new Test("Disable settings widget") {
            public void run() {
                PackageManager pm = getPackageManager();
                pm.setComponentEnabledSetting(new ComponentName("com.android.settings",
                            "com.android.settings.widget.SettingsAppWidgetProvider"),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);

            }
        },
        new Test("Enable proximity") {
            public void run() {
                mProx.acquire();
            }
        },
        new Test("Disable proximity") {
            public void run() {
                mProx.release();
            }
        },
        new Test("Disable proximity (WAIT_FOR_PROXIMITY_NEGATIVE)") {
            public void run() {
                mProx.release(PowerManager.WAIT_FOR_PROXIMITY_NEGATIVE);
            }
        },
        new Test("Touch events don't poke") {
            public void run() {
                mPokeState |= LocalPowerManager.POKE_LOCK_IGNORE_TOUCH_EVENTS;
                try {
                    mPowerManager.setPokeLock(mPokeState, mPokeToken, TAG);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        },

        new Test("Touch events poke") {
            public void run() {
                mPokeState &= ~LocalPowerManager.POKE_LOCK_IGNORE_TOUCH_EVENTS;
                try {
                    mPowerManager.setPokeLock(mPokeState, mPokeToken, TAG);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        },
        new Test("Short timeout") {
            public void run() {
                mPokeState &= ~LocalPowerManager.POKE_LOCK_TIMEOUT_MASK;
                mPokeState |= LocalPowerManager.POKE_LOCK_SHORT_TIMEOUT;
                try {
                    mPowerManager.setPokeLock(mPokeState, mPokeToken, TAG);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        },
        new Test("Medium timeout") {
            public void run() {
                mPokeState &= ~LocalPowerManager.POKE_LOCK_TIMEOUT_MASK;
                mPokeState |= LocalPowerManager.POKE_LOCK_MEDIUM_TIMEOUT;
                try {
                    mPowerManager.setPokeLock(mPokeState, mPokeToken, TAG);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        },
        new Test("Normal timeout") {
            public void run() {
                mPokeState &= ~LocalPowerManager.POKE_LOCK_TIMEOUT_MASK;
                try {
                    mPowerManager.setPokeLock(mPokeState, mPokeToken, TAG);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        },
        new Test("Illegal timeout") {
            public void run() {
                mPokeState |= LocalPowerManager.POKE_LOCK_SHORT_TIMEOUT
                        | LocalPowerManager.POKE_LOCK_MEDIUM_TIMEOUT;
                try {
                    mPowerManager.setPokeLock(mPokeState, mPokeToken, TAG);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        },
    };
}
