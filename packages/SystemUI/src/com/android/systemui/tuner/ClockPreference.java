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
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;

import androidx.preference.DropDownPreference;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.policy.Clock;

public class ClockPreference extends DropDownPreference implements TunerService.Tunable {

    private static final String SECONDS = "seconds";
    private static final String DEFAULT = "default";
    private static final String DISABLED = "disabled";

    private final String mClock;
    private boolean mClockEnabled;
    private boolean mHasSeconds;
    private ArraySet<String> mHideList;
    private boolean mHasSetValue;
    private boolean mReceivedSeconds;
    private boolean mReceivedClock;

    public ClockPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mClock = context.getString(com.android.internal.R.string.status_bar_clock);
        setEntryValues(new CharSequence[] { SECONDS, DEFAULT, DISABLED });
    }

    @Override
    public void onAttached() {
        super.onAttached();
        Dependency.get(TunerService.class).addTunable(this, StatusBarIconController.ICON_HIDE_LIST,
                Clock.CLOCK_SECONDS);
    }

    @Override
    public void onDetached() {
        Dependency.get(TunerService.class).removeTunable(this);
        super.onDetached();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
            mReceivedClock = true;
            mHideList = StatusBarIconController.getIconHideList(getContext(), newValue);
            mClockEnabled = !mHideList.contains(mClock);
        } else if (Clock.CLOCK_SECONDS.equals(key)) {
            mReceivedSeconds = true;
            mHasSeconds = newValue != null && Integer.parseInt(newValue) != 0;
        }
        if (!mHasSetValue && mReceivedClock && mReceivedSeconds) {
            // Because of the complicated tri-state it can end up looping and setting state back to
            // what the user didn't choose.  To avoid this, just set the state once and rely on the
            // preference to handle updates.
            mHasSetValue = true;
            if (mClockEnabled && mHasSeconds) {
                setValue(SECONDS);
            } else if (mClockEnabled) {
                setValue(DEFAULT);
            } else {
                setValue(DISABLED);
            }
        }
    }

    @Override
    protected boolean persistString(String value) {
        Dependency.get(TunerService.class).setValue(Clock.CLOCK_SECONDS, SECONDS.equals(value) ? 1
                : 0);
        if (DISABLED.equals(value)) {
            mHideList.add(mClock);
        } else {
            mHideList.remove(mClock);
        }
        Dependency.get(TunerService.class).setValue(StatusBarIconController.ICON_HIDE_LIST,
                TextUtils.join(",", mHideList));
        return true;
    }
}
