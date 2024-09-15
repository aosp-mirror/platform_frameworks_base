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

import static android.provider.Settings.System.SHOW_BATTERY_PERCENT;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;

import androidx.preference.DropDownPreference;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;

public class BatteryPreference extends DropDownPreference implements TunerService.Tunable {

    private static final String PERCENT = "percent";
    private static final String DEFAULT = "default";
    private static final String DISABLED = "disabled";

    private final String mBattery;
    private boolean mBatteryEnabled;
    private boolean mHasPercentage;
    private ArraySet<String> mHideList;
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
                SHOW_BATTERY_PERCENT, 0) != 0;
        Dependency.get(TunerService.class).addTunable(this, StatusBarIconController.ICON_HIDE_LIST);
    }

    @Override
    public void onDetached() {
        Dependency.get(TunerService.class).removeTunable(this);
        super.onDetached();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
            mHideList = StatusBarIconController.getIconHideList(getContext(), newValue);
            mBatteryEnabled = !mHideList.contains(mBattery);
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
        Settings.System.putInt(getContext().getContentResolver(), SHOW_BATTERY_PERCENT, v ? 1 : 0);
        if (DISABLED.equals(value)) {
            mHideList.add(mBattery);
        } else {
            mHideList.remove(mBattery);
        }
        Dependency.get(TunerService.class).setValue(StatusBarIconController.ICON_HIDE_LIST,
                TextUtils.join(",", mHideList));
        return true;
    }
}
