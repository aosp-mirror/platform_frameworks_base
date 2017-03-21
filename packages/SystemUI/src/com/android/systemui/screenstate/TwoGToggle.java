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
import android.telephony.TelephonyManager;
import com.android.internal.telephony.Phone;
import android.util.Log;
import android.net.ConnectivityManager;

public class TwoGToggle extends ScreenStateToggle {
    private static final String TAG = "ScreenStateService_TwoGToggle";

    public TwoGToggle(Context context){
        super(context);
    }

    // TODO: samsung is creating a handler when switching to 2G :(
    protected boolean runInThread(){
        return false;
    }

    protected boolean isEnabled(){
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)){
            Log.d(TAG, "Data not enabled");
            return false;
        }
        int s = Settings.System.getInt(mContext.getContentResolver(), Settings.System.SCREEN_STATE_TWOG, 0);
        if(s!=0)
            return true;
        else
            return false;
    }

    protected boolean doScreenOnAction(){
        return mDoAction;
    }

    protected boolean doScreenOffAction(){
        if (isNotTwoGMode()){
            mDoAction = true;
        } else {
            mDoAction = false;
        }
        return mDoAction;
    }

    private int getCurrentPreferredNetworkMode() {
        int network = -1;
        int dataSim;
        try {
            dataSim = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION);
            if(dataSim != 0){
                network = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE + dataSim);
            } else {
                network = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE);
            }
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }
        Log.d(TAG, "preferred SIM data network: " + network);
        return network;
    }

    private boolean isNotTwoGMode(){
        return getCurrentPreferredNetworkMode() != Phone.NT_MODE_GSM_ONLY;
    }

    protected Runnable getScreenOffAction(){
        return new Runnable() {
            @Override
            public void run() {
                TelephonyManager tm = (TelephonyManager) mContext
                        .getSystemService(Context.TELEPHONY_SERVICE);
                Log.d(TAG, "2G = true");
                tm.toggle2G(true);
            }
        };
    }
    protected Runnable getScreenOnAction(){
        return new Runnable() {
            @Override
            public void run() {
                TelephonyManager tm = (TelephonyManager) mContext
                        .getSystemService(Context.TELEPHONY_SERVICE);
                Log.d(TAG, "2G = false");
                tm.toggle2G(false);
            }
        };
    }
}
