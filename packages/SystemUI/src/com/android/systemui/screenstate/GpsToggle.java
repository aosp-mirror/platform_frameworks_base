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

import android.content.Context;
import android.location.LocationManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

public class GpsToggle extends ScreenStateToggle {
    private static final String TAG = "ScreenStateService_GpsToggle";

    public GpsToggle(Context context){
        super(context);
    }

    protected boolean isEnabled(){
        int s = Settings.System.getInt(mContext.getContentResolver(), Settings.System.SCREEN_STATE_GPS, 0);
        if(s != 0)
            return true;
        else
            return false;
    }

    protected boolean doScreenOnAction(){
        return mDoAction;
    }

    protected boolean doScreenOffAction(){
        if (isGpsEnabled()){
            mDoAction = true;
        } else {
            mDoAction = false;
        }
        return mDoAction;
    }

    private boolean isGpsEnabled(){
        // TODO: check if gps is available on this device?
        return Settings.Secure.isLocationProviderEnabled(
                mContext.getContentResolver(), LocationManager.GPS_PROVIDER);

    }

    protected Runnable getScreenOffAction(){
        return new Runnable() {
            @Override
            public void run() {
                Settings.Secure.setLocationProviderEnabled(mContext.getContentResolver(),
                        LocationManager.GPS_PROVIDER, false);
                Log.d(TAG, "gps = false");
            }
        };
    }
    protected Runnable getScreenOnAction(){
        return new Runnable() {
            @Override
            public void run() {
                Settings.Secure.setLocationProviderEnabled(mContext.getContentResolver(),
                        LocationManager.GPS_PROVIDER, true);
                Log.d(TAG, "gps = true");
            }
        };
    }
}
