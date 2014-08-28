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

import android.os.Binder;
import android.os.IBinder;
import android.os.IPowerManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ServiceManager;
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
        new Test("Disable proximity (WAIT_FOR_DISTANT_PROXIMITY") {
            public void run() {
                mProx.release(PowerManager.WAIT_FOR_DISTANT_PROXIMITY);
            }
        },
        new Test("Enable proximity, wait 5 seconds then disable") {
            public void run() {
                mProx.acquire();
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mProx.release();
                    }
                }, 5000);
            }
        },
        new Test("Enable proximity, wait 5 seconds then disable  (WAIT_FOR_DISTANT_PROXIMITY)") {
            public void run() {
                mProx.acquire();
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mProx.release(PowerManager.WAIT_FOR_DISTANT_PROXIMITY);
                    }
                }, 5000);
            }
        },
    };
}
