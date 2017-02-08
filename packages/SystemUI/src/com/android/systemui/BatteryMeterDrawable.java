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
 * limitations under the License.
 */

package com.android.systemui;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import com.android.settingslib.graph.BatteryMeterDrawableBase;
import com.android.systemui.statusbar.policy.BatteryController;

public class BatteryMeterDrawable extends BatteryMeterDrawableBase implements
        BatteryController.BatteryStateChangeCallback {

    public static final String SHOW_PERCENT_SETTING = "status_bar_show_battery_percent";

    private BatteryController mBatteryController;
    private SettingObserver mSettingObserver;

    public BatteryMeterDrawable(Context context, int frameColor) {
        super(context, frameColor);

        mSettingObserver = new SettingObserver(new Handler(mContext.getMainLooper()));
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        setBatteryLevel(level);
        setPluggedIn(pluggedIn);
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        setPowerSave(isPowerSave);
    }

    public void startListening() {
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(SHOW_PERCENT_SETTING), false, mSettingObserver);
        updateShowPercent();
        mBatteryController.addCallback(this);
    }

    public void stopListening() {
        mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
        mBatteryController.removeCallback(this);
    }

    protected void updateShowPercent() {
        setShowPercent(0 != Settings.System.getInt(mContext.getContentResolver(),
                SHOW_PERCENT_SETTING, 0));
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        setPowerSave(mBatteryController.isPowerSave());
    }

    private final class SettingObserver extends ContentObserver {
        public SettingObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            updateShowPercent();
            postInvalidate();
        }
    }

}
