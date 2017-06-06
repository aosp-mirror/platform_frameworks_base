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
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.telephony.TelephonyManager;
import android.util.Log;

public class MobileDataToggle extends ScreenStateToggle {
    private static final String TAG = "ScreenStateService_MobileDataToggle";

    public MobileDataToggle(Context context){
        super(context);
    }

    protected boolean isEnabled(){
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)){
            return false;
        }
        int s = Settings.System.getInt(mContext.getContentResolver(), Settings.System.SCREEN_STATE_MOBILE_DATA, 0);
        if(s!=0)
            return true;
        else
            return false;
    }

    protected boolean doScreenOnAction(){
        return mDoAction;
    }

    protected boolean doScreenOffAction(){
        if (isMobileDataEnabled()){
            mDoAction = true;
        } else {
            mDoAction = false;
        }
        return mDoAction;
    }

    private boolean isMobileDataEnabled(){
            TelephonyManager telephonyService = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
            return telephonyService.getDataEnabled();
    }

    private void setMobileDataState(boolean mobileDataEnabled){
        TelephonyManager telephonyService = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        telephonyService.setDataEnabled(mobileDataEnabled);
    }

    protected Runnable getScreenOffAction(){
        return new Runnable() {
            @Override
            public void run() {
                setMobileDataState(false);
                Log.d(TAG, "mobileData = false");
            }
        };
    }
    protected Runnable getScreenOnAction(){
        return new Runnable() {
            @Override
            public void run() {
                setMobileDataState(true);
                Log.d(TAG, "mobileData = true");
            }
        };
    }
}
