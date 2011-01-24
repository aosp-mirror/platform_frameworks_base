/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.ContentResolver;
import android.content.Context;
import android.os.AsyncTask;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Slog;
import android.view.IWindowManager;
import android.widget.CompoundButton;

public class BrightnessController implements ToggleSlider.Listener {
    private static final String TAG = "StatusBar.BrightnessController";

    // Backlight range is from 0 - 255. Need to make sure that user
    // doesn't set the backlight to 0 and get stuck
    private static final int MINIMUM_BACKLIGHT = android.os.Power.BRIGHTNESS_DIM + 10;
    private static final int MAXIMUM_BACKLIGHT = android.os.Power.BRIGHTNESS_ON;

    private Context mContext;
    private ToggleSlider mControl;
    private IPowerManager mPower;

    public BrightnessController(Context context, ToggleSlider control) {
        mContext = context;
        mControl = control;

        boolean automaticAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
        mPower = IPowerManager.Stub.asInterface(ServiceManager.getService("power"));

        if (automaticAvailable) {
            int automatic;
            try {
                automatic = Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE);
            } catch (SettingNotFoundException snfe) {
                automatic = 0;
            }
            control.setChecked(automatic != 0);
        } else {
            control.setChecked(false);
            //control.hideToggle();
        }
        
        int value;
        try {
            value = Settings.System.getInt(mContext.getContentResolver(), 
                    Settings.System.SCREEN_BRIGHTNESS);
        } catch (SettingNotFoundException ex) {
            value = MAXIMUM_BACKLIGHT;
        }

        control.setMax(MAXIMUM_BACKLIGHT - MINIMUM_BACKLIGHT);
        control.setValue(value - MINIMUM_BACKLIGHT);

        control.setOnChangedListener(this);
    }

    public void onChanged(ToggleSlider view, boolean tracking, boolean automatic, int value) {
        setMode(automatic ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        if (!automatic) {
            final int val = value + MINIMUM_BACKLIGHT;
            setBrightness(val);
            if (!tracking) {
                AsyncTask.execute(new Runnable() {
                        public void run() {
                            Settings.System.putInt(mContext.getContentResolver(), 
                                    Settings.System.SCREEN_BRIGHTNESS, val);
                        }
                    });
            }
        }
    }

    private void setMode(int mode) {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, mode);
    }
    
    private void setBrightness(int brightness) {
        try {
            mPower.setBacklightBrightness(brightness);
        } catch (RemoteException ex) {
        }        
    }
}
