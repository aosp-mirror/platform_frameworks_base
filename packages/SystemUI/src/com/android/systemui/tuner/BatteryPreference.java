/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;

import android.content.Context;
import android.provider.Settings;
import android.support.v7.preference.DropDownPreference;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.statusbar.phone.StatusBarIconController;

import static com.android.systemui.BatteryMeterDrawable.SHOW_PERCENT_SETTING;

public class BatteryPreference extends DropDownPreference implements TunerService.Tunable {

    private static final String PERCENT = "percent";
    private static final String DEFAULT = "default";
    private static final String DISABLED = "disabled";

    private final String mBattery;
    private boolean mBatteryEnabled;
    private boolean mHasPercentage;
    private ArraySet<String> mBlacklist;
    private boolean mHasSetValue;

    public BatteryPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mBattery = context.getString(com.android.internal.R.string.status_bar_battery);
        setEntryValues(new CharSequence[] {PERCENT, DEFAULT, DISABLED });
    }

    @Override
    public void onAttached() {
        super.onAttached();
        mHasPercentage = Settings.System.getInt(getContext().getContentResolver(),
                SHOW_PERCENT_SETTING, 0) != 0;
        TunerService.get(getContext()).addTunable(this, StatusBarIconController.ICON_BLACKLIST);
    }

    @Override
    public void onDetached() {
        TunerService.get(getContext()).removeTunable(this);
        super.onDetached();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (StatusBarIconController.ICON_BLACKLIST.equals(key)) {
            mBlacklist = StatusBarIconController.getIconBlacklist(newValue);
            mBatteryEnabled = !mBlacklist.contains(mBattery);
        }
        if (!mHasSetValue) {
            // Because of the complicated tri-state it can end up looping and setting state back to
            // what the user didn't choose.  To avoid this, just set the state once and rely on the
            // preference to handle updates.
            mHasSetValue = true;
            if (mBatteryEnabled && mHasPercentage) {
                setValue(PERCENT);
            } else if (mBatteryEnabled) {
                setValue(DEFAULT);
            } else {
                setValue(DISABLED);
            }
        }
    }

    @Override
    protected boolean persistString(String value) {
        final boolean v = PERCENT.equals(value);
        MetricsLogger.action(getContext(), MetricsEvent.TUNER_BATTERY_PERCENTAGE, v);
        Settings.System.putInt(getContext().getContentResolver(), SHOW_PERCENT_SETTING, v ? 1 : 0);
        if (DISABLED.equals(value)) {
            mBlacklist.add(mBattery);
        } else {
            mBlacklist.remove(mBattery);
        }
        TunerService.get(getContext()).setValue(StatusBarIconController.ICON_BLACKLIST,
                TextUtils.join(",", mBlacklist));
        return true;
    }
}
